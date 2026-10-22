package com.example.messaging_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.ProviderExamples;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.dto.ProviderConfig;
import com.example.messaging_service.dto.ProviderResponse;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.InvalidProviderException;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvider;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Auth;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.AuthType;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Body;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.BodyFormat;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;
import com.example.messaging_service.provider.store.ProviderStore;

/** Register, update and unregister on a real db.json in a temp folder. ALPHA stands in for providers.json. */
class ProviderRegistrationTest {

	@TempDir
	Path dir;

	private final RestClient.Builder builder = RestClient.builder();

	private ProviderStore store;

	private ProviderRegistry registry;

	@BeforeEach
	void start() {
		store = new ProviderStore(dir.resolve("db.json"));
		registry = restart();
	}

	@Nested
	class Register {

		@Test
		void providerCanSendRightAway() {
			registry.register(ProviderExamples.plivo());

			assertThat(registry.resolve("plivo", MessageType.SMS)).isInstanceOf(GenericHttpProvider.class)
				.extracting(MessageProvider::id)
				.isEqualTo("PLIVO");
		}

		@Test
		void idIsUpperCased() {
			ProviderResponse saved = registry.register(ProviderExamples.plivo().withId(" plivo "));

			assertThat(saved.id()).isEqualTo("PLIVO");
			assertThat(store.load()).extracting(ProviderConfig::id).containsExactly("PLIVO");
		}

		@Test
		void responseMasksSecretsButDbJsonKeepsRealValues() {
			ProviderResponse saved = registry.register(ProviderExamples.plivo());

			assertThat(saved.source()).isEqualTo("db.json");
			assertThat(saved.readOnly()).isFalse();
			assertThat(saved.enabled()).isTrue();
			assertThat(saved.variables()).containsOnly(Map.entry("auth-id", "****"),
					Map.entry("auth-token", "****"), Map.entry("from", "****"));
			assertThat(store.load().get(0).variables()).containsEntry("auth-token", "real-token");
		}

		@Test
		void providerIsStillThereAfterARestart() {
			registry.register(ProviderExamples.plivo());

			assertThat(restart().resolve("PLIVO", MessageType.SMS).id()).isEqualTo("PLIVO");
		}

		@Test
		void sendsWithTheStoredValues() {
			MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
			ProviderRegistry withServer = restart();
			withServer.register(ProviderExamples.plivo());
			String basic = Base64.getEncoder().encodeToString("MA123:real-token".getBytes(StandardCharsets.UTF_8));
			server.expect(requestTo("https://api.plivo.com/v1/Account/MA123/Message/"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Basic " + basic))
				.andExpect(jsonPath("$.dst").value("15550002222"))
				.andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
					.body("{ \"message_uuid\": [\"uuid-1\"] }"));

			ProviderResult result = withServer.resolve("PLIVO", MessageType.SMS)
				.send(new OutboundMessage(MessageType.SMS, "+15550002222", "Hello"));

			assertThat(result.providerMessageId()).isEqualTo("uuid-1");
			server.verify();
		}

		@Test
		void disabledProviderIsSavedButCannotSend() {
			Map<String, String> noToken = ProviderExamples.plivoVariables();
			noToken.put("auth-token", "");

			registry.register(ProviderExamples.plivo(false, noToken));

			assertThat(store.load()).hasSize(1);
			assertCode(() -> registry.resolve("PLIVO", MessageType.SMS), ErrorCode.PROVIDER_UNAVAILABLE);
		}

		@Test
		void takenIdIsRejected() {
			registry.register(ProviderExamples.plivo());

			assertCode(() -> registry.register(ProviderExamples.plivo()), ErrorCode.PROVIDER_ALREADY_EXISTS);
			assertCode(() -> registry.register(ProviderExamples.plivo().withId("alpha")),
					ErrorCode.PROVIDER_ALREADY_EXISTS);
		}

		@Test
		void badIdIsRejected() {
			assertProblems(() -> registry.register(ProviderExamples.plivo().withId("pl ivo")),
					"id 'pl ivo' may only use letters, digits, '_' and '-'");
			assertProblems(() -> registry.register(ProviderExamples.plivo().withId(null)), "id is missing");
		}

		@Test
		void brokenBlockListsEveryProblemAndSavesNothing() {
			ProviderConfig broken = new ProviderConfig("PLIVO", null, EnumSet.of(MessageType.SMS), true,
					Map.of("token", "t"), null, null);

			assertProblems(() -> registry.register(broken), "request is missing", "response is missing");
			assertThat(store.load()).isEmpty();
			assertCode(() -> registry.resolve("PLIVO", MessageType.SMS), ErrorCode.UNSUPPORTED_PROVIDER);
		}

		@Test
		void placeholdersAndMasksAreRejected() {
			Map<String, String> variables = ProviderExamples.plivoVariables();
			variables.put("auth-token", "${PLIVO_AUTH_TOKEN}");
			variables.put("auth-id", "****");

			assertProblems(() -> registry.register(ProviderExamples.plivo(true, variables)),
					"variables.auth-token: send the real value; placeholders like ${...} are not supported",
					"variables.auth-id is \"****\" but there is no stored value to keep");
		}

		@Test
		void enabledProviderNeedsEveryVariable() {
			Map<String, String> noToken = ProviderExamples.plivoVariables();
			noToken.put("auth-token", "");

			assertProblems(() -> registry.register(ProviderExamples.plivo(true, noToken)),
					"these variables have no value: auth-token; every variable needs one when the provider is enabled");
		}

		@Test
		void failedSaveChangesNothing() throws IOException {
			// A folder where the temp file should go makes the write fail.
			Files.createDirectories(dir.resolve("db.json.tmp/blocked"));

			assertThatThrownBy(() -> registry.register(ProviderExamples.plivo()))
				.isInstanceOf(UncheckedIOException.class);
			assertCode(() -> registry.resolve("PLIVO", MessageType.SMS), ErrorCode.UNSUPPORTED_PROVIDER);
			assertThat(registry.providers()).extracting(ProviderResponse::id).containsExactly("ALPHA");
		}

	}

	@Nested
	class Update {

		@Test
		void replacesTheBlockAndKeepsMaskedSecrets() {
			registry.register(ProviderExamples.plivo());
			MessageProvider before = registry.resolve("PLIVO", MessageType.SMS);
			Map<String, String> variables = Map.of("auth-id", "****", "auth-token", "****", "from", "+15550009999");

			ProviderResponse saved = registry.update("plivo", ProviderExamples.plivo(true, variables).withId(null));

			assertThat(saved.variables()).containsEntry("auth-token", "****");
			assertThat(store.load().get(0).variables()).containsEntry("auth-id", "MA123")
				.containsEntry("auth-token", "real-token")
				.containsEntry("from", "+15550009999");
			assertThat(registry.resolve("PLIVO", MessageType.SMS)).isNotSameAs(before);
		}

		@Test
		void canDisableAProvider() {
			registry.register(ProviderExamples.plivo());

			registry.update("PLIVO", ProviderExamples.plivo(false, ProviderExamples.plivoVariables()));

			assertCode(() -> registry.resolve("PLIVO", MessageType.SMS), ErrorCode.PROVIDER_UNAVAILABLE);
		}

		@Test
		void unknownReadOnlyAndMismatchedIdsAreRejected() {
			registry.register(ProviderExamples.plivo());

			assertCode(() -> registry.update("SINCH", ProviderExamples.plivo().withId(null)), ErrorCode.PROVIDER_NOT_FOUND);
			assertCode(() -> registry.update("alpha", ProviderExamples.plivo().withId(null)),
					ErrorCode.PROVIDER_READ_ONLY);
			assertProblems(() -> registry.update("PLIVO", ProviderExamples.plivo().withId("OTHER")),
					"id in the body ('OTHER') does not match the id in the path ('PLIVO')");
		}

	}

	@Nested
	class Unregister {

		@Test
		void providerStopsBeingUsable() {
			registry.register(ProviderExamples.plivo());

			registry.unregister("plivo");

			assertThat(store.load()).isEmpty();
			assertCode(() -> registry.resolve("PLIVO", MessageType.SMS), ErrorCode.UNSUPPORTED_PROVIDER);
		}

		@Test
		void readOnlyAndUnknownIdsAreRejected() {
			assertCode(() -> registry.unregister("ALPHA"), ErrorCode.PROVIDER_READ_ONLY);
			assertCode(() -> registry.unregister("PLIVO"), ErrorCode.PROVIDER_NOT_FOUND);
		}

		@Test
		void providerNamedInRoutingIsInUse() {
			store.save(List.of(ProviderExamples.plivo()));
			ProviderRegistry routed = new ProviderRegistry(List.of(), properties("ALPHA", "PLIVO"), builder, store);

			assertCode(() -> routed.unregister("PLIVO"), ErrorCode.PROVIDER_IN_USE);
			assertThat(store.load()).hasSize(1);
		}

	}

	@Nested
	class Read {

		@Test
		void listsEveryProviderWithWhereItComesFrom() {
			registry.register(ProviderExamples.plivo());

			List<ProviderResponse> providers = registry.providers();

			assertThat(providers).extracting(ProviderResponse::id, ProviderResponse::source, ProviderResponse::readOnly,
					ProviderResponse::enabled)
				.containsExactly(tuple("ALPHA", "providers.json", true, true),
						tuple("PLIVO", "db.json", false, true));
			assertThat(providers.get(0).variables()).containsEntry("token", "****");
		}

		@Test
		void findsOneProviderById() {
			assertThat(registry.provider("alpha").source()).isEqualTo("providers.json");
			assertCode(() -> registry.provider("PLIVO"), ErrorCode.PROVIDER_NOT_FOUND);
		}

	}

	@Nested
	class Startup {

		@Test
		void failsOnABrokenStoredProvider() {
			store.save(List.of(new ProviderConfig("PLIVO", null, EnumSet.of(MessageType.SMS), false, Map.of(), null,
					null)));

			assertThatThrownBy(ProviderRegistrationTest.this::restart).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("request is missing");
		}

		@Test
		void failsWhenAStoredIdIsTaken() {
			store.save(List.of(ProviderExamples.plivo().withId("ALPHA")));

			assertThatThrownBy(ProviderRegistrationTest.this::restart).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("has provider ALPHA, which already exists");
		}

		@Test
		void failsWhenAnEnabledStoredProviderHasNoCredentials() {
			Map<String, String> noToken = ProviderExamples.plivoVariables();
			noToken.put("auth-token", "");
			store.save(List.of(ProviderExamples.plivo(true, noToken)));

			assertThatThrownBy(ProviderRegistrationTest.this::restart).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PLIVO is enabled in")
				.hasMessageContaining("its credentials are missing");
		}

	}

	private ProviderRegistry restart() {
		return new ProviderRegistry(List.of(), properties("ALPHA"), builder, store);
	}

	private static GenericHttpProvidersProperties properties(String... enabled) {
		Description alpha = new Description("ALPHA", null, EnumSet.of(MessageType.SMS), Map.of("token", "tok"),
				new Request("POST", "https://api.example-sms.com/v1/send",
						new Auth(AuthType.BEARER, null, null, "{{token}}", null, null),
						new Body(BodyFormat.JSON, Map.of("dst", "{{to}}", "text", "{{text}}"), null)),
				new Response(null, "$.id", null, null, null));
		return new GenericHttpProvidersProperties(Map.of("sms", new ChannelSettings("ALPHA", List.of(enabled))),
				Map.of("alpha", alpha));
	}

	private static void assertCode(Runnable call, ErrorCode code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(MessagingException.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
	}

	private static void assertProblems(Runnable call, String... problems) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(InvalidProviderException.class,
				ex -> assertThat(ex.problems()).contains(problems));
	}

}
