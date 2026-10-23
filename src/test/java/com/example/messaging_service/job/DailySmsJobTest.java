package com.example.messaging_service.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;
import com.example.messaging_service.service.BatchMessageService;
import com.example.messaging_service.service.MessageService;
import com.example.messaging_service.service.ProviderRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DailySmsJobTest {

	/** Every SMS the fake providers were asked to send, as "PROVIDER to body". */
	private final List<String> sent = new ArrayList<>();

	/** Size of every batch the job asked for, in order. */
	private final List<Integer> chunkSizes = new ArrayList<>();

	@TempDir
	private Path dir;

	@Test
	void sendsEachGroupThroughItsProviderWithItsBody() {
		job("""
				{ "version": "2026-10-20", "groups": [
				  { "provider": "ALPHA", "body": "Bill ready", "recipients": ["+910000000001", "+910000000002"] },
				  { "provider": "BETA", "body": "Shipped", "recipients": ["+910000000003"] } ] }
				""", 100).run();

		assertThat(sent).containsExactly("ALPHA +910000000001 Bill ready", "ALPHA +910000000002 Bill ready",
				"BETA +910000000003 Shipped");
	}

	@Test
	void bigGroupIsSplitIntoChunksOfTheLimit() {
		job("""
				{ "groups": [ { "provider": "ALPHA", "body": "Hi", "recipients": [
				  "+910000000001", "+910000000002", "+910000000003", "+910000000004", "+910000000005" ] } ] }
				""", 2).run();

		assertThat(chunkSizes).containsExactly(2, 2, 1);
		assertThat(sent).hasSize(5);
	}

	@Test
	void duplicatesAreRemovedBeforeSplitting() {
		// With chunks of 2, the second +910000000001 would land in another chunk if not removed first.
		job("""
				{ "groups": [ { "provider": "ALPHA", "body": "Hi", "recipients": [
				  "+910000000001", "+910000000002", "+910000000003", "+910000000001" ] } ] }
				""", 2).run();

		assertThat(sent).containsExactly("ALPHA +910000000001 Hi", "ALPHA +910000000002 Hi", "ALPHA +910000000003 Hi");
	}

	@Test
	void sameNumberInTwoGroupsIsSentByBoth() {
		job("""
				{ "groups": [
				  { "provider": "ALPHA", "body": "Hi", "recipients": ["+910000000001"] },
				  { "provider": "BETA", "body": "Hi", "recipients": ["+910000000001"] } ] }
				""", 100).run();

		assertThat(sent).containsExactly("ALPHA +910000000001 Hi", "BETA +910000000001 Hi");
	}

	@Test
	void unknownProviderSkipsOnlyThatGroup() {
		job("""
				{ "groups": [
				  { "provider": "SINCH", "body": "Hi", "recipients": ["+910000000001"] },
				  { "provider": "BETA", "body": "Hi", "recipients": ["+910000000002"] } ] }
				""", 100).run();

		assertThat(sent).containsExactly("BETA +910000000002 Hi");
	}

	@Test
	void failedNumbersDoNotStopTheRestOfTheRun() {
		job("""
				{ "groups": [
				  { "provider": "ALPHA", "body": "Hi", "recipients": ["+910000000009", "+910000000001"] },
				  { "provider": "BETA", "body": "Hi", "recipients": ["+910000000002"] } ] }
				""", 1, message -> {
			if (message.to().equals("+910000000009")) {
				throw new MessagingException(ErrorCode.PROVIDER_REJECTED, "ALPHA", "21608", "unverified");
			}
			return new ProviderResult("id", "queued");
		}).run();

		assertThat(sent).containsExactly("ALPHA +910000000009 Hi", "ALPHA +910000000001 Hi", "BETA +910000000002 Hi");
	}

	@Test
	void brokenFileSendsNothingAndDoesNotThrow() {
		job("{ not json", 100).run();

		assertThat(sent).isEmpty();
	}

	@Test
	void eachRunIsAddedToTheHistoryWithEveryNumber() throws Exception {
		job("""
				{ "version": "2026-10-23", "groups": [
				  { "provider": "ALPHA", "body": "Hi", "recipients": ["+910000000009", "+910000000001", "+910000000001"] },
				  { "provider": "SINCH", "body": "Hi", "recipients": ["+910000000002"] } ] }
				""", 100, message -> {
			if (message.to().equals("+910000000009")) {
				throw new MessagingException(ErrorCode.PROVIDER_REJECTED, "ALPHA", "21608", "unverified");
			}
			return new ProviderResult("SM1", "queued");
		}).run();

		JsonNode run = history().get("runs").get(0);
		assertThat(run.get("run-id").asString()).isNotBlank();
		assertThat(run.get("started-at").asString()).endsWith("+05:30");
		assertThat(run.get("finished-at").asString()).isNotBlank();
		assertThat(run.get("status").asString()).isEqualTo("PARTIAL");
		assertThat(run.get("targets-version").asString()).isEqualTo("2026-10-23");
		assertThat(run.get("total").asInt()).isEqualTo(2);
		assertThat(run.get("accepted").asInt()).isEqualTo(1);
		assertThat(run.get("failed").asInt()).isEqualTo(1);
		assertThat(run.has("error")).isFalse();

		JsonNode alpha = run.get("groups").get(0);
		assertThat(alpha.get("status").asString()).isEqualTo("SENT");
		assertThat(alpha.get("duplicates-removed").asInt()).isEqualTo(1);
		JsonNode rejected = alpha.get("results").get(0);
		assertThat(rejected.get("to").asString()).isEqualTo("+910000000009");
		assertThat(rejected.get("status").asString()).isEqualTo("FAILED");
		assertThat(rejected.get("code").asString()).isEqualTo("PROVIDER_REJECTED");
		assertThat(rejected.get("provider-error-code").asString()).isEqualTo("21608");
		JsonNode accepted = alpha.get("results").get(1);
		assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
		assertThat(accepted.get("provider-message-id").asString()).isEqualTo("SM1");
		assertThat(accepted.get("provider-status").asString()).isEqualTo("queued");
		assertThat(accepted.has("code")).isFalse();

		JsonNode sinch = run.get("groups").get(1);
		assertThat(sinch.get("status").asString()).isEqualTo("SKIPPED");
		assertThat(sinch.get("error").get("code").asString()).isEqualTo("UNSUPPORTED_PROVIDER");
		assertThat(sinch.has("results")).isFalse();
	}

	@Test
	void laterRunsAreAddedAfterEarlierOnesWhichStayAsWritten() throws Exception {
		String json = """
				{ "groups": [ { "provider": "ALPHA", "body": "Hi", "recipients": ["+910000000001"] } ] }
				""";
		job(json, 100).run();
		String first = history().get("runs").get(0).toString();

		job(json, 100).run();

		JsonNode runs = history().get("runs");
		assertThat(runs).hasSize(2);
		assertThat(runs.get(0).toString()).isEqualTo(first);
		assertThat(runs.get(1).get("run-id").asString()).isNotEqualTo(runs.get(0).get("run-id").asString());
	}

	private JsonNode history() throws Exception {
		return JsonMapper.builder().build().readTree(Files.readString(dir.resolve("jobHistory.json")));
	}

	private DailySmsJob job(String json, int maxRecipients) {
		return job(json, maxRecipients, message -> new ProviderResult("id", "queued"));
	}

	private DailySmsJob job(String json, int maxRecipients, Function<OutboundMessage, ProviderResult> reply) {
		GenericHttpProvidersProperties properties = new GenericHttpProvidersProperties(
				Map.of("sms", new ChannelSettings("ALPHA", List.of("ALPHA", "BETA"))), Map.of());
		ProviderRegistry registry = new ProviderRegistry(List.of(provider("ALPHA", reply), provider("BETA", reply)),
				properties, RestClient.builder());
		BatchMessageService batch = new BatchMessageService(registry, new MessageService(registry), maxRecipients) {

			@Override
			public BatchSendResponse send(MessageType type, String provider, List<String> recipients, String body) {
				chunkSizes.add(recipients.size());
				return super.send(type, provider, recipients, body);
			}

		};
		return new DailySmsJob(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)),
				dir.resolve("jobHistory.json"), ZoneId.of("Asia/Kolkata"), JsonMapper.builder().build(), batch);
	}

	private MessageProvider provider(String id, Function<OutboundMessage, ProviderResult> reply) {
		return new MessageProvider() {

			@Override
			public String id() {
				return id;
			}

			@Override
			public Set<MessageType> supportedTypes() {
				return EnumSet.of(MessageType.SMS);
			}

			@Override
			public ProviderResult send(OutboundMessage message) {
				sent.add(id + " " + message.to() + " " + message.body());
				return reply.apply(message);
			}

		};
	}

}
