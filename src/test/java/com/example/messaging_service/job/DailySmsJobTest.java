package com.example.messaging_service.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
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

import tools.jackson.databind.json.JsonMapper;

class DailySmsJobTest {

	/** Every SMS the fake providers were asked to send, as "PROVIDER to body". */
	private final List<String> sent = new ArrayList<>();

	/** Size of every batch the job asked for, in order. */
	private final List<Integer> chunkSizes = new ArrayList<>();

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
		return new DailySmsJob(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)), JsonMapper.builder().build(),
				batch);
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
