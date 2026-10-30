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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.job.DailySmsJob;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.service.ProviderRegistry;

/**
 * The whole application with both real adapters and dummy credentials. No request here reaches
 * a provider; adapter HTTP calls are covered by the adapter tests.
 */
@SpringBootTest(properties = { "messaging.db-file=target/test-db/MessagingServiceApplicationTests.json",
		"messaging.http-providers.twilio.variables.account-sid=ACtest",
		"messaging.http-providers.twilio.variables.api-key=SKtest",
		"messaging.http-providers.twilio.variables.api-secret=test-secret",
		"messaging.http-providers.twilio.variables.from=+15550001111",
		"messaging.http-providers.vonage.variables.api-key=test-key",
		"messaging.http-providers.vonage.variables.api-secret=test-secret",
		"messaging.http-providers.vonage.variables.from=MyBrand" })
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
	void registryGivesBackEveryProvidersJsonBlockWithSecretsMasked(@Autowired ProviderRegistry registry) {
		assertThat(registry.descriptions(MessageType.SMS)).extracting(Description::id)
			.containsExactly("TWILIO", "VONAGE");

		Description twilio = registry.description(MessageType.SMS, "twilio").orElseThrow();
		assertThat(twilio.request().url())
			.isEqualTo("https://api.twilio.com/2010-04-01/Accounts/{{account-sid}}/Messages.json");
		assertThat(twilio.variables()).containsOnlyKeys("account-sid", "api-key", "api-secret", "from")
			.allSatisfy((name, value) -> assertThat(value).isEqualTo("****"));
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

	@Test
	void batchWithUnknownProviderIsRejectedBeforeAnySend() throws Exception {
		mvc.perform(post("/api/v1/messages/batch").contentType(MediaType.APPLICATION_JSON).content("""
				{ "type": "SMS", "provider": "SINCH", "recipients": ["+910000000001"], "body": "Hi" }
				""")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_PROVIDER"));
	}

	@Test
	void dailyJobIsOffByDefault(@Autowired ApplicationContext context) {
		assertThat(context.getBeansOfType(DailySmsJob.class)).isEmpty();
	}

	private ResultActions send(String json) throws Exception {
		return mvc.perform(post("/api/v1/messages").contentType(MediaType.APPLICATION_JSON).content(json));
	}

}
