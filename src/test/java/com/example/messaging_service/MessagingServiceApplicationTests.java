package com.example.messaging_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = { "messaging.providers.twilio.account-sid=ACtest",
		"messaging.providers.twilio.auth-token=test-token", "messaging.providers.vonage.api-key=test-key",
		"messaging.providers.vonage.api-secret=test-secret" })
class MessagingServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
