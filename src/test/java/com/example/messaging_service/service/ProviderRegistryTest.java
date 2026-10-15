package com.example.messaging_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.messaging_service.config.MessagingProperties;
import com.example.messaging_service.config.MessagingProperties.ChannelSettings;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;

class ProviderRegistryTest {

	private static final FakeProvider TWILIO = sms("TWILIO");

	private static final FakeProvider VONAGE = sms("VONAGE");

	// Known provider id with no SMS adapter, standing in for an email-only provider.
	private static final FakeProvider SENDGRID = new FakeProvider("SENDGRID", Set.of(), true);

	@Nested
	class Resolve {

		private final ProviderRegistry registry = new ProviderRegistry(List.of(TWILIO, VONAGE),
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
			ProviderRegistry onlyTwilioEnabled = new ProviderRegistry(List.of(TWILIO, VONAGE),
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
			ProviderRegistry withSendGrid = new ProviderRegistry(List.of(TWILIO, SENDGRID),
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
			ProviderRegistry noSms = new ProviderRegistry(List.of(SENDGRID), new MessagingProperties(Map.of()));

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
			new ProviderRegistry(List.of(TWILIO, VONAGE), smsConfig("VONAGE", "TWILIO", "VONAGE"));
		}

		@Test
		void disabledProviderNeedsNoCredentials() {
			new ProviderRegistry(List.of(TWILIO, new FakeProvider("VONAGE", EnumSet.of(MessageType.SMS), false)),
					smsConfig("TWILIO", "TWILIO"));
		}

		@Test
		void failsOnDuplicateChannelAndId() {
			assertStartupFails(List.of(TWILIO, sms("TWILIO")), smsConfig("TWILIO", "TWILIO"),
					"Two adapters for (SMS, TWILIO)");
		}

		@Test
		void failsWhenChannelWithAdaptersHasNoConfigBlock() {
			assertStartupFails(List.of(TWILIO), new MessagingProperties(Map.of()),
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
			assertThatThrownBy(() -> new ProviderRegistry(List.of(TWILIO), smsConfig("VONAGE", "TWILLIO")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("primary-provider VONAGE has no adapter")
				.hasMessageContaining("contains TWILLIO");
		}

		private void assertStartupFails(List<MessageProvider> providers, MessagingProperties properties,
				String expected) {
			assertThatThrownBy(() -> new ProviderRegistry(providers, properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("Invalid messaging configuration:")
				.hasMessageContaining(expected);
		}

	}

	private static MessagingProperties smsConfig(String primary, String... enabled) {
		return new MessagingProperties(Map.of("sms", new ChannelSettings(primary, List.of(enabled))));
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
