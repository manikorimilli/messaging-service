package com.example.messaging_service.provider.vonage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;

class VonageSmsProviderTest {

	private static final String URL = "https://rest.nexmo.com/sms/json";

	private static final OutboundMessage MESSAGE = new OutboundMessage(MessageType.SMS, "+919876543210", "Hello");

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final VonageSmsProvider provider = new VonageSmsProvider(builder,
			new VonageProperties("key123", "secret456", "MyBrand"));

	@Test
	void postsFormWithoutPlusAndReadsMessageId() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("from", "MyBrand");
		form.add("to", "919876543210");
		form.add("text", "Hello");
		String basic = Base64.getEncoder().encodeToString("key123:secret456".getBytes(StandardCharsets.UTF_8));

		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Basic " + basic))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().formData(form))
			.andRespond(accepted());

		ProviderResult result = provider.send(MESSAGE);

		assertThat(result).isEqualTo(new ProviderResult("0A0000000123ABCD1", "0"));
		server.verify();
	}

	@Test
	void nonLatinTextIsSentAsUnicode() {
		server.expect(requestTo(URL))
			.andExpect(content().formDataContains(Map.of("type", "unicode", "text", "नमस्ते")))
			.andRespond(accepted());

		provider.send(new OutboundMessage(MessageType.SMS, "+919876543210", "नमस्ते"));
		server.verify();
	}

	@Test
	void detectsGsmAndUnicodeText() {
		assertThat(VonageSmsProvider.needsUnicode("Your code is 482913. Cost: £5 {ok} €")).isFalse();
		assertThat(VonageSmsProvider.needsUnicode("नमस्ते")).isTrue();
		assertThat(VonageSmsProvider.needsUnicode("Thanks 🙂")).isTrue();
	}

	@Test
	void http200WithNonZeroStatusIsRejected() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{ "message-count": "1",
				  "messages": [ { "status": "29", "error-text": "Non White-listed Destination - rejected" } ] }
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_REJECTED);
			assertThat(e.provider()).isEqualTo("VONAGE");
			assertThat(e.providerErrorCode()).isEqualTo("29");
			assertThat(e.getMessage()).isEqualTo("Vonage rejected the message: Non White-listed Destination - rejected");
		});
	}

	@Test
	void throttledStatusIsUnavailable() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{ "message-count": "1", "messages": [ { "status": "1", "error-text": "Throughput Rate Exceeded" } ] }
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
			assertThat(e.providerErrorCode()).isEqualTo("1");
		});
	}

	@Test
	void failedPartOfLongMessageIsReported() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{ "message-count": "2", "messages": [
				  { "status": "0", "message-id": "part-1" },
				  { "status": "9", "error-text": "Partner quota exceeded" } ] }
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class,
				e -> assertThat(e.providerErrorCode()).isEqualTo("9"));
	}

	@Test
	void serverErrorIsUnavailable() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
	}

	@Test
	void clientErrorIsRejected() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_REJECTED));
	}

	@Test
	void isConfiguredOnlyWithAllThreeSettings() {
		assertThat(provider.isConfigured()).isTrue();
		assertThat(new VonageSmsProvider(RestClient.builder(), new VonageProperties("key123", null, "MyBrand"))
			.isConfigured()).isFalse();
	}

	private static ResponseCreator accepted() {
		return withSuccess("""
				{ "message-count": "1",
				  "messages": [ { "to": "919876543210", "message-id": "0A0000000123ABCD1", "status": "0",
				                  "remaining-balance": "3.14", "message-price": "0.03", "network": "40410" } ] }
				""", MediaType.APPLICATION_JSON);
	}

}
