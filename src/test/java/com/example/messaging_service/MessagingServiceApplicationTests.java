package com.example.messaging_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole application with both real adapters and dummy credentials. No request here reaches
 * a provider; adapter HTTP calls are covered by the adapter tests.
 */
@SpringBootTest(properties = { "messaging.providers.twilio.account-sid=ACtest",
		"messaging.providers.twilio.auth-token=test-token", "messaging.providers.vonage.api-key=test-key",
		"messaging.providers.vonage.api-secret=test-secret" })
@AutoConfigureMockMvc
class MessagingServiceApplicationTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void httpClientIsJdkWithConfiguredTimeouts(@Autowired ClientHttpRequestFactoryBuilder<?> factoryBuilder,
			@Autowired HttpClientSettings settings) {
		assertThat(factoryBuilder).isInstanceOf(JdkClientHttpRequestFactoryBuilder.class);
		assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void unknownProviderListsTheRealAdapters() throws Exception {
		send("""
				{ "type": "SMS", "provider": "SINCH", "to": "+919876543210", "body": "Hi" }
				""").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_PROVIDER"))
			.andExpect(jsonPath("$.detail")
				.value("Provider 'SINCH' is not supported. Supported providers: TWILIO, VONAGE."));
	}

	@Test
	void invalidRequestIsRejectedBeforeAnyProviderIsCalled() throws Exception {
		send("""
				{ "type": "SMS", "to": "12345", "body": "" }
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	private ResultActions send(String json) throws Exception {
		return mvc.perform(post("/api/v1/messages").contentType(MediaType.APPLICATION_JSON).content(json));
	}

}
