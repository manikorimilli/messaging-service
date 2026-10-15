package com.example.messaging_service.provider.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;

/**
 * The "twilio" block in providers.json must behave exactly like the former TwilioSmsProvider:
 * same request on the wire, same results, same errors.
 */
class TwilioBlockTest {

	private static final String URL = "https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json";

	private static final OutboundMessage MESSAGE = new OutboundMessage(MessageType.SMS, "+919876543210", "Hello");

	private static final Map<String, String> VARIABLES = Map.of("account-sid", "AC123", "api-key", "SK456",
			"api-secret", "key-secret", "from", "+15550001111");

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final GenericHttpProvider provider = ProviderBlocks.provider("twilio", VARIABLES, builder);

	@Test
	void identity() {
		assertThat(provider.id()).isEqualTo("TWILIO");
		assertThat(provider.supportedTypes()).containsExactly(MessageType.SMS);
	}

	@Test
	void postsFormWithBasicAuthAndReadsSidAndStatus() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("To", "+919876543210");
		form.add("From", "+15550001111");
		form.add("Body", "Hello");
		String basic = Base64.getEncoder().encodeToString("SK456:key-secret".getBytes(StandardCharsets.UTF_8));

		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Basic " + basic))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().formData(form))
			.andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
					{ "sid": "SM2c7f", "status": "queued", "to": "+919876543210", "num_segments": "1" }
					"""));

		ProviderResult result = provider.send(MESSAGE);

		assertThat(result).isEqualTo(new ProviderResult("SM2c7f", "queued"));
		server.verify();
	}

	@Test
	void nonLatinTextIsSentUnchanged() {
		server.expect(requestTo(URL))
			.andExpect(content().formData(form("नमस्ते")))
			.andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
					{ "sid": "SM1", "status": "queued" }
					"""));

		provider.send(new OutboundMessage(MessageType.SMS, "+919876543210", "नमस्ते"));
		server.verify();
	}

	@Test
	void clientErrorIsRejectedWithTwilioCode() {
		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
					{ "code": 21211, "message": "The 'To' number is not a valid phone number.",
					  "more_info": "https://www.twilio.com/docs/errors/21211", "status": 400 }
					"""));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_REJECTED);
			assertThat(e.provider()).isEqualTo("TWILIO");
			assertThat(e.providerErrorCode()).isEqualTo("21211");
			assertThat(e.getMessage())
				.isEqualTo("Twilio rejected the message: The 'To' number is not a valid phone number.");
		});
	}

	@Test
	void clientErrorWithoutJsonIsStillRejected() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_REJECTED);
			assertThat(e.providerErrorCode()).isNull();
			assertThat(e.getMessage()).isEqualTo("Twilio rejected the message: HTTP 401");
		});
	}

	@Test
	void serverErrorIsUnavailable() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
			assertThat(e.getMessage()).isEqualTo("Twilio is unavailable (HTTP 503).");
		});
	}

	@Test
	void throttledIsUnavailable() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
	}

	@Test
	void isConfiguredOnlyWithEveryVariable() {
		assertThat(provider.isConfigured()).isTrue();
		assertThat(ProviderBlocks.provider("twilio", Map.of("account-sid", "AC123", "api-key", "SK456", "api-secret",
				"", "from", "+15550001111"), RestClient.builder()).isConfigured()).isFalse();
	}

	private static MultiValueMap<String, String> form(String body) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("To", "+919876543210");
		form.add("From", "+15550001111");
		form.add("Body", body);
		return form;
	}

}
