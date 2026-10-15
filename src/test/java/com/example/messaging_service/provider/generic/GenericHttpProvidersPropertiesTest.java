package com.example.messaging_service.provider.generic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.AuthType;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.BodyFormat;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;

class GenericHttpProvidersPropertiesTest {

	// Loads the real application.properties, which imports providers.json. Variables are pinned so a
	// developer's local .env cannot change the result.
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withPropertyValues("messaging.http-providers.twilio.variables.account-sid=AC123",
				"messaging.http-providers.vonage.variables.from=Vonage APIs")
		.withUserConfiguration(BindOnly.class);

	@Test
	void bindsSmsRoutingFromProvidersJson() {
		runner.run(context -> {
			ChannelSettings sms = context.getBean(GenericHttpProvidersProperties.class).channel(MessageType.SMS);

			assertThat(sms.primaryProvider()).isEqualTo("TWILIO");
			assertThat(sms.enabledProviders()).containsExactly("TWILIO", "VONAGE");
		});
	}

	@Test
	void routingCanBeOverriddenAndIdsAreCaseInsensitive() {
		// Same keys an environment variable such as MESSAGING_CHANNELS_SMS_PRIMARYPROVIDER sets.
		runner
			.withPropertyValues("messaging.channels.sms.primary-provider= vonage ",
					"messaging.channels.sms.enabled-providers=twilio, Vonage")
			.run(context -> {
				ChannelSettings sms = context.getBean(GenericHttpProvidersProperties.class).channel(MessageType.SMS);

				assertThat(sms.primaryProvider()).isEqualTo("VONAGE");
				assertThat(sms.enabledProviders()).containsExactly("TWILIO", "VONAGE");
				assertThat(sms.isEnabled("Twilio")).isTrue();
				assertThat(sms.isEnabled(" vonage ")).isTrue();
				assertThat(sms.isEnabled("SINCH")).isFalse();
				assertThat(sms.isEnabled(null)).isFalse();
			});
	}

	@Test
	void missingChannelBlockGivesNull() {
		assertThat(new GenericHttpProvidersProperties(null, null).channel(MessageType.SMS)).isNull();
		assertThat(new GenericHttpProvidersProperties(Map.of(), Map.of()).channel(MessageType.SMS)).isNull();
	}

	@Test
	void channelKeysAreMatchedInLowerCase() {
		GenericHttpProvidersProperties properties = new GenericHttpProvidersProperties(
				Map.of("SMS", new ChannelSettings("TWILIO", List.of("TWILIO"))), null);

		assertThat(properties.channel(MessageType.SMS)).isNotNull();
	}

	@Test
	void blankPrimaryAndBlankEnabledEntriesBecomeAbsent() {
		ChannelSettings settings = new ChannelSettings("  ", Arrays.asList("TWILIO", " ", null));

		assertThat(settings.primaryProvider()).isNull();
		assertThat(settings.enabledProviders()).containsExactly("TWILIO");
		assertThat(new ChannelSettings("TWILIO", null).enabledProviders()).isEmpty();
	}

	@Test
	void bindsTwilioBlockKeepingFieldNameCase() {
		runner.run(context -> {
			Description twilio = context.getBean(GenericHttpProvidersProperties.class).httpProviders().get("twilio");

			assertThat(twilio.id()).isEqualTo("TWILIO");
			assertThat(twilio.name()).isEqualTo("Twilio");
			assertThat(twilio.channels()).containsExactly(MessageType.SMS);
			assertThat(twilio.variables()).containsEntry("account-sid", "AC123")
				.containsKeys("api-key", "api-secret", "from");
			assertThat(twilio.request().method()).isEqualTo("POST");
			assertThat(twilio.request().url())
				.isEqualTo("https://api.twilio.com/2010-04-01/Accounts/{{account-sid}}/Messages.json");
			assertThat(twilio.request().auth().type()).isEqualTo(AuthType.BASIC);
			assertThat(twilio.request().body().format()).isEqualTo(BodyFormat.FORM);
			assertThat(twilio.request().body().fields()).containsExactly(
					org.assertj.core.api.Assertions.entry("To", "{{to}}"),
					org.assertj.core.api.Assertions.entry("From", "{{from}}"),
					org.assertj.core.api.Assertions.entry("Body", "{{text}}"));
			assertThat(twilio.response().idPath()).isEqualTo("$.sid");
			assertThat(twilio.response().retry().httpStatus()).containsExactly("429", "5xx");
		});
	}

	@Test
	void bindsVonageBlockWithItsSpecialRules() {
		runner.run(context -> {
			Description vonage = context.getBean(GenericHttpProvidersProperties.class).httpProviders().get("vonage");

			assertThat(vonage.id()).isEqualTo("VONAGE");
			assertThat(vonage.variables()).containsEntry("from", "Vonage APIs");
			assertThat(vonage.request().body().fields()).containsEntry("to", "{{to_digits}}");
			assertThat(vonage.request().body().unicodeFields()).containsEntry("type", "unicode");
			assertThat(vonage.response().success().httpStatus()).isEqualTo(List.of("200"));
			assertThat(vonage.response().success().each()).isEqualTo("$.messages");
			assertThat(vonage.response().success().field()).isEqualTo("status");
			assertThat(vonage.response().success().expected()).isEqualTo("0");
			assertThat(vonage.response().idPath()).isEqualTo("$.messages[0].message-id");
			assertThat(vonage.response().retry().codes()).containsExactly("1", "5");
		});
	}

	@Configuration
	@EnableConfigurationProperties(GenericHttpProvidersProperties.class)
	static class BindOnly {

	}

}
