package com.example.messaging_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.example.messaging_service.config.MessagingProperties.ChannelSettings;
import com.example.messaging_service.dto.MessageType;

class MessagingPropertiesTest {

	// Loads the real application.properties and binds only MessagingProperties, nothing else.
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(BindOnly.class);

	@Test
	void bindsSmsRoutingFromApplicationProperties() {
		runner.run(context -> {
			ChannelSettings sms = context.getBean(MessagingProperties.class).channel(MessageType.SMS);

			assertThat(sms.primaryProvider()).isEqualTo("TWILIO");
			assertThat(sms.enabledProviders()).containsExactly("TWILIO", "VONAGE");
		});
	}

	@Test
	void overrideSwitchesPrimaryAndIdsAreCaseInsensitive() {
		runner
			.withPropertyValues("messaging.channels.sms.primary-provider= vonage ",
					"messaging.channels.sms.enabled-providers=twilio, Vonage")
			.run(context -> {
				ChannelSettings sms = context.getBean(MessagingProperties.class).channel(MessageType.SMS);

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
		assertThat(new MessagingProperties(null).channel(MessageType.SMS)).isNull();
		assertThat(new MessagingProperties(Map.of()).channel(MessageType.SMS)).isNull();
	}

	@Test
	void channelKeysAreMatchedInLowerCase() {
		MessagingProperties properties = new MessagingProperties(
				Map.of("SMS", new ChannelSettings("TWILIO", List.of("TWILIO"))));

		assertThat(properties.channel(MessageType.SMS)).isNotNull();
	}

	@Test
	void blankPrimaryAndBlankEnabledEntriesBecomeAbsent() {
		ChannelSettings settings = new ChannelSettings("  ", Arrays.asList("TWILIO", " ", null));

		assertThat(settings.primaryProvider()).isNull();
		assertThat(settings.enabledProviders()).containsExactly("TWILIO");
	}

	@Test
	void missingEnabledListIsEmpty() {
		assertThat(new ChannelSettings("TWILIO", null).enabledProviders()).isEmpty();
	}

	@Configuration
	@EnableConfigurationProperties(MessagingProperties.class)
	static class BindOnly {

	}

}
