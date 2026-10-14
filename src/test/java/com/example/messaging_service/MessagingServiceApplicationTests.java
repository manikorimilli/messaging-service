package com.example.messaging_service;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;

@SpringBootTest(properties = { "messaging.providers.twilio.account-sid=ACtest",
		"messaging.providers.twilio.auth-token=test-token" })
class MessagingServiceApplicationTests {

	@Test
	void contextLoads() {
	}

	/**
	 * Temporary: application.properties enables TWILIO and VONAGE for SMS, and the registry
	 * refuses to start without its adapter. This stub stands in until the real Vonage adapter
	 * exists. Step 8 removes it.
	 */
	@TestConfiguration
	static class StubProviders {

		@Bean
		MessageProvider vonageStub() {
			return new StubProvider("VONAGE");
		}

	}

	record StubProvider(String id) implements MessageProvider {

		@Override
		public Set<MessageType> supportedTypes() {
			return EnumSet.of(MessageType.SMS);
		}

		@Override
		public ProviderResult send(OutboundMessage message) {
			throw new UnsupportedOperationException("stub");
		}

	}

}
