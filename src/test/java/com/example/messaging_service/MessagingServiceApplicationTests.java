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

@SpringBootTest
class MessagingServiceApplicationTests {

	@Test
	void contextLoads() {
	}

	/**
	 * Temporary: application.properties enables TWILIO and VONAGE for SMS, and the registry
	 * refuses to start without their adapters. These stubs stand in until the real adapters
	 * exist. Step 7 removes the Twilio stub, Step 8 the Vonage one.
	 */
	@TestConfiguration
	static class StubProviders {

		@Bean
		MessageProvider twilioStub() {
			return new StubProvider("TWILIO");
		}

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
