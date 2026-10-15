package com.example.messaging_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties;
import com.example.messaging_service.provider.generic.GenericHttpProvider;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Auth;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.AuthType;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Body;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.BodyFormat;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;

class ProviderRegistryTest {

	private static final FakeProvider TWILIO = sms("TWILIO");

	private static final FakeProvider VONAGE = sms("VONAGE");

	// Known provider id with no SMS adapter, standing in for an email-only provider.
	private static final FakeProvider SENDGRID = new FakeProvider("SENDGRID", Set.of(), true);

	@Nested
	class Resolve {

		private final ProviderRegistry registry = registry(List.of(TWILIO, VONAGE),
				smsConfig("TWILIO", "TWILIO", "VONAGE"));

		@Test
		void usesExplicitProvider() {
			assertThat(registry.resolve("VONAGE", MessageType.SMS)).isSameAs(VONAGE);
		}

		@Test
		void usesPrimaryWhenProviderIsMissingOrBlank() {
			assertThat(registry.resolve(null, MessageType.SMS)).isSameAs(TWILIO);
			assertThat(registry.resolve("  ", MessageType.SMS)).isSameAs(TWILIO);
		}

		@Test
		void providerIdIsCaseInsensitive() {
			assertThat(registry.resolve(" vonage ", MessageType.SMS)).isSameAs(VONAGE);
		}

		@Test
		void unknownProviderIsUnsupported() {
			assertThatThrownBy(() -> registry.resolve("sinch", MessageType.SMS))
				.isInstanceOfSatisfying(MessagingException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.UNSUPPORTED_PROVIDER);
					assertThat(ex.provider()).isNull();
				})
				.hasMessage("Provider 'SINCH' is not supported. Supported providers: TWILIO, VONAGE.");
		}

		@Test
		void providerNotEnabledForChannelIsUnavailable() {
			ProviderRegistry onlyTwilioEnabled = registry(List.of(TWILIO, VONAGE),
					smsConfig("TWILIO", "TWILIO"));

			assertThatThrownBy(() -> onlyTwilioEnabled.resolve("VONAGE", MessageType.SMS))
				.isInstanceOfSatisfying(MessagingException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
					assertThat(ex.provider()).isEqualTo("VONAGE");
				})
				.hasMessage("Provider 'VONAGE' is currently disabled for SMS.");
		}

		@Test
		void knownProviderWithoutAdapterForChannelIsUnsupportedMessageType() {
			ProviderRegistry withSendGrid = registry(List.of(TWILIO, SENDGRID),
					smsConfig("TWILIO", "TWILIO"));

			assertThatThrownBy(() -> withSendGrid.resolve("SENDGRID", MessageType.SMS))
				.isInstanceOfSatisfying(MessagingException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.UNSUPPORTED_MESSAGE_TYPE);
					assertThat(ex.provider()).isEqualTo("SENDGRID");
				})
				.hasMessage("Provider 'SENDGRID' does not support SMS.");
		}

		@Test
		void channelWithNoAdaptersAndNoConfigIsUnsupportedMessageType() {
			ProviderRegistry noSms = registry(List.of(SENDGRID), new GenericHttpProvidersProperties(Map.of(), Map.of()));

			assertThatThrownBy(() -> noSms.resolve(null, MessageType.SMS))
				.isInstanceOfSatisfying(MessagingException.class,
						ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNSUPPORTED_MESSAGE_TYPE))
				.hasMessage("No provider is configured for SMS.");
		}

	}

	@Nested
	class StartupChecks {

		@Test
		void validConfigurationStarts() {
			registry(List.of(TWILIO, VONAGE), smsConfig("VONAGE", "TWILIO", "VONAGE"));
		}

		@Test
		void disabledProviderNeedsNoCredentials() {
			registry(List.of(TWILIO, new FakeProvider("VONAGE", EnumSet.of(MessageType.SMS), false)),
					smsConfig("TWILIO", "TWILIO"));
		}

		@Test
		void failsOnDuplicateChannelAndId() {
			assertStartupFails(List.of(TWILIO, sms("TWILIO")), smsConfig("TWILIO", "TWILIO"),
					"Two adapters for (SMS, TWILIO)");
		}

		@Test
		void failsWhenChannelWithAdaptersHasNoConfigBlock() {
			assertStartupFails(List.of(TWILIO), new GenericHttpProvidersProperties(Map.of(), Map.of()),
					"SMS has adapters [TWILIO] but no messaging.channels.sms block");
		}

		@Test
		void failsWhenPrimaryIsNotSet() {
			assertStartupFails(List.of(TWILIO), smsConfig(null, "TWILIO"),
					"messaging.channels.sms.primary-provider is not set");
		}

		@Test
		void failsWhenPrimaryHasNoAdapter() {
			assertStartupFails(List.of(TWILIO), smsConfig("VONAGE", "TWILIO"),
					"primary-provider VONAGE has no adapter for SMS");
		}

		@Test
		void failsWhenPrimaryIsNotEnabled() {
			assertStartupFails(List.of(TWILIO, VONAGE), smsConfig("VONAGE", "TWILIO"),
					"primary-provider VONAGE is not in messaging.channels.sms.enabled-providers");
		}

		@Test
		void failsWhenEnabledIdHasNoAdapter() {
			assertStartupFails(List.of(TWILIO), smsConfig("TWILIO", "TWILIO", "TWILLIO"),
					"enabled-providers contains TWILLIO, which has no adapter for SMS");
		}

		@Test
		void failsWhenEnabledProviderHasNoCredentials() {
			assertStartupFails(List.of(new FakeProvider("TWILIO", EnumSet.of(MessageType.SMS), false)),
					smsConfig("TWILIO", "TWILIO"),
					"TWILIO is enabled for SMS but its credentials are missing");
		}

		@Test
		void failsWhenAdapterIdIsNotUpperCase() {
			assertStartupFails(List.of(TWILIO, sms("vonage")), smsConfig("TWILIO", "TWILIO"),
					"has id 'vonage'; ids must be upper case");
		}

		@Test
		void reportsEveryProblemAtOnce() {
			assertThatThrownBy(() -> registry(List.of(TWILIO), smsConfig("VONAGE", "TWILLIO")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("primary-provider VONAGE has no adapter")
				.hasMessageContaining("contains TWILLIO");
		}

		private void assertStartupFails(List<MessageProvider> providers, GenericHttpProvidersProperties properties,
				String expected) {
			assertThatThrownBy(() -> registry(providers, properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("Invalid messaging configuration:")
				.hasMessageContaining(expected);
		}

	}

	@Nested
	class ProvidersFromConfig {

		// Two blocks: ALPHA is enabled for SMS, BETA is described but not enabled (its secret is empty).
		private final GenericHttpProvidersProperties config = new GenericHttpProvidersProperties(
				Map.of("sms", new ChannelSettings("ALPHA", List.of("ALPHA"))),
				Map.of("alpha", block("ALPHA", Map.of("token", "tok-123", "from", "+15550001111")),
						"beta", block("BETA", Map.of("token", "", "from", "+15550002222"))));

		@Test
		void buildsOneProviderPerBlock() {
			ProviderRegistry registry = registry(List.of(), config);

			assertThat(registry.resolve(null, MessageType.SMS)).isInstanceOf(GenericHttpProvider.class)
				.extracting(MessageProvider::id)
				.isEqualTo("ALPHA");
		}

		@Test
		void givesBackTheBlockWithSecretsMasked() {
			ProviderRegistry registry = registry(List.of(), config);

			Description alpha = registry.description(MessageType.SMS, "alpha").orElseThrow();

			assertThat(alpha.id()).isEqualTo("ALPHA");
			assertThat(alpha.request().url()).isEqualTo("https://api.example-sms.com/v1/send");
			assertThat(alpha.request().body().fields()).containsEntry("dst", "{{to}}");
			assertThat(alpha.variables()).containsEntry("token", "****").containsEntry("from", "****");
		}

		@Test
		void emptySecretStaysEmptySoItIsVisibleAsMissing() {
			ProviderRegistry registry = registry(List.of(), config);

			assertThat(registry.description(MessageType.SMS, "BETA").orElseThrow().variables())
				.containsEntry("token", "")
				.containsEntry("from", "****");
		}

		@Test
		void listsEveryBlockForAChannelSortedById() {
			ProviderRegistry registry = registry(List.of(), config);

			assertThat(registry.descriptions(MessageType.SMS)).extracting(Description::id)
				.containsExactly("ALPHA", "BETA");
		}

		@Test
		void handWrittenOrUnknownProviderHasNoDescription() {
			GenericHttpProvidersProperties withHandWritten = new GenericHttpProvidersProperties(
					Map.of("sms", new ChannelSettings("TWILIO", List.of("TWILIO"))), config.httpProviders());
			ProviderRegistry registry = registry(List.of(TWILIO), withHandWritten);

			assertThat(registry.description(MessageType.SMS, "TWILIO")).isEmpty();
			assertThat(registry.description(MessageType.SMS, "SINCH")).isEmpty();
			assertThat(registry.descriptions(MessageType.SMS)).extracting(Description::id)
				.containsExactly("ALPHA", "BETA");
		}

		@Test
		void brokenBlockIsReportedWithTheOtherStartupProblems() {
			Description broken = new Description("GAMMA", null, EnumSet.of(MessageType.SMS), Map.of(), null, null);
			GenericHttpProvidersProperties withBroken = new GenericHttpProvidersProperties(
					Map.of("sms", new ChannelSettings("ALPHA", List.of("ALPHA", "GAMMA"))),
					Map.of("alpha", config.httpProviders().get("alpha"), "gamma", broken));

			assertThatThrownBy(() -> registry(List.of(), withBroken)).isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("Invalid messaging configuration:")
				.hasMessageContaining("Invalid messaging.http-providers.gamma:")
				.hasMessageContaining("request is missing")
				.hasMessageContaining("enabled-providers contains GAMMA, which has no adapter for SMS");
		}

		private static Description block(String id, Map<String, String> variables) {
			return new Description(id, null, EnumSet.of(MessageType.SMS), variables,
					new Request("POST", "https://api.example-sms.com/v1/send",
							new Auth(AuthType.BEARER, null, null, "{{token}}", null, null),
							new Body(BodyFormat.JSON, Map.of("src", "{{from}}", "dst", "{{to}}", "text", "{{text}}"), null)),
					new Response(null, "$.message_id", null, null, null));
		}

	}

	private static ProviderRegistry registry(List<MessageProvider> providers,
			GenericHttpProvidersProperties properties) {
		return new ProviderRegistry(providers, properties, RestClient.builder());
	}

	private static GenericHttpProvidersProperties smsConfig(String primary, String... enabled) {
		return new GenericHttpProvidersProperties(Map.of("sms", new ChannelSettings(primary, List.of(enabled))), Map.of());
	}

	private static FakeProvider sms(String id) {
		return new FakeProvider(id, EnumSet.of(MessageType.SMS), true);
	}

	record FakeProvider(String id, Set<MessageType> supportedTypes, boolean configured) implements MessageProvider {

		@Override
		public boolean isConfigured() {
			return configured;
		}

		@Override
		public ProviderResult send(OutboundMessage message) {
			throw new UnsupportedOperationException("not used by registry tests");
		}

	}

}
