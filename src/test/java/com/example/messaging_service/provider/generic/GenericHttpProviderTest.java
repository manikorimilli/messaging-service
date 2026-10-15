package com.example.messaging_service.provider.generic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Auth;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.AuthType;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Body;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.BodyFormat;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ErrorPaths;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Retry;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Success;

/** Engine features not used by the Twilio and Vonage blocks, and the startup checks. */
class GenericHttpProviderTest {

	private static final String URL = "https://api.example-sms.com/v1/send";

	private static final OutboundMessage MESSAGE = new OutboundMessage(MessageType.SMS, "+919876543210",
			"Say \"hi\"");

	private static final Map<String, String> VARIABLES = Map.of("token", "tok-123", "from", "+15550001111");

	private static final Body JSON_BODY = new Body(BodyFormat.JSON,
			Map.of("src", "{{from}}", "dst", "{{to}}", "text", "{{text}}"), null);

	private static final Response RESPONSE = new Response(null, "$.message_uuid[0]", null,
			new ErrorPaths("$.error_code", "$.error"), new Retry(null, List.of("RATE_LIMIT")));

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	@Test
	void sendsJsonBodyWithBearerToken() {
		GenericHttpProvider provider = provider(new Auth(AuthType.BEARER, null, null, "{{token}}", null, null));

		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer tok-123"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			// Jackson builds the JSON, so the quotes in the text are escaped correctly
			.andExpect(content().json("""
					{ "src": "+15550001111", "dst": "+919876543210", "text": "Say \\"hi\\"" }
					""", JsonCompareMode.STRICT))
			.andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
					{ "message_uuid": ["db3ce55a-7f1d-11e1-8ea7-1231380bc196"], "api_id": "x" }
					"""));

		ProviderResult result = provider.send(MESSAGE);

		assertThat(result).isEqualTo(new ProviderResult("db3ce55a-7f1d-11e1-8ea7-1231380bc196", null));
		server.verify();
	}

	@Test
	void sendsCustomAuthHeader() {
		GenericHttpProvider provider = provider(new Auth(AuthType.HEADER, null, null, null, "X-API-KEY", "{{token}}"));

		server.expect(requestTo(URL))
			.andExpect(header("X-API-KEY", "tok-123"))
			.andExpect(headerDoesNotExist("Authorization"))
			.andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
					{ "message_uuid": ["id-1"] }
					"""));

		provider.send(MESSAGE);
		server.verify();
	}

	@Test
	void sendsWithoutAuth() {
		GenericHttpProvider provider = provider(new Auth(AuthType.NONE, null, null, null, null, null));

		server.expect(requestTo(URL))
			.andExpect(headerDoesNotExist("Authorization"))
			.andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
					{ "message_uuid": ["id-1"] }
					"""));

		provider.send(MESSAGE);
		server.verify();
	}

	@Test
	void providerErrorCodeListedAsRetryIsUnavailable() {
		GenericHttpProvider provider = provider(new Auth(AuthType.NONE, null, null, null, null, null));

		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
					{ "error_code": "RATE_LIMIT", "error": "Slow down" }
					"""));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
			assertThat(e.providerErrorCode()).isEqualTo("RATE_LIMIT");
			assertThat(e.getMessage()).isEqualTo("Example is unavailable: Slow down");
		});
	}

	@Test
	void nonJsonErrorBodyFallsBackToHttpStatus() {
		GenericHttpProvider provider = provider(new Auth(AuthType.NONE, null, null, null, null, null));

		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_HTML).body("<html>Forbidden</html>"));

		assertThatThrownBy(() -> provider.send(MESSAGE)).isInstanceOfSatisfying(MessagingException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_REJECTED);
			assertThat(e.getMessage()).isEqualTo("Example rejected the message: HTTP 403");
		});
	}

	@Test
	void reportsEveryProblemInADescription() {
		Description broken = new Description("EXAMPLE", null, EnumSet.of(MessageType.SMS), Map.of("to", "x"),
				new Request("GET", "ftp://example.com/{{text}}", new Auth(AuthType.BASIC, "{{api_key}}", null, null, null, null),
						new Body(BodyFormat.FORM, Map.of("text", "{{txt}}"), null)),
				new Response(new Success(List.of("2x"), "$.messages", null, null), null, "$.messages[0", null, null));

		assertThatThrownBy(() -> new GenericHttpProvider("example", broken, builder))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("Invalid messaging.http-providers.example:")
			.hasMessageContaining("variables.to clashes with the built-in {{to}}")
			.hasMessageContaining("request.method must be POST, PUT or PATCH")
			.hasMessageContaining("request.url must start with https:// or http://")
			.hasMessageContaining("request.url may only use variables, not {{text}}")
			.hasMessageContaining("unknown placeholder {{api_key}} in request.auth.username")
			.hasMessageContaining("request.auth.password is missing")
			.hasMessageContaining("unknown placeholder {{txt}} in request.body.fields.text")
			.hasMessageContaining("response.id-path is missing")
			.hasMessageContaining("response.status-path is not valid JSONPath")
			.hasMessageContaining("response.success.each needs field and expected")
			.hasMessageContaining("response.success.http-status: '2x' is not a status like 200 or 2xx");
	}

	@Test
	void reportsMissingParts() {
		Description empty = new Description(null, null, null, null, null, null);

		assertThatThrownBy(() -> new GenericHttpProvider("empty", empty, builder))
			.hasMessageContaining("id is missing")
			.hasMessageContaining("channels is missing")
			.hasMessageContaining("request is missing")
			.hasMessageContaining("response is missing");
	}

	@Test
	void matchesStatusPatterns() {
		assertThat(ResponseReader.matches(List.of("2xx"), 201)).isTrue();
		assertThat(ResponseReader.matches(List.of("2xx"), 301)).isFalse();
		assertThat(ResponseReader.matches(List.of("429", "5xx"), 429)).isTrue();
		assertThat(ResponseReader.matches(List.of("429", "5xx"), 503)).isTrue();
		assertThat(ResponseReader.matches(List.of("200"), 201)).isFalse();
	}

	private GenericHttpProvider provider(Auth auth) {
		Description description = new Description("EXAMPLE", "Example", EnumSet.of(MessageType.SMS), VARIABLES,
				new Request("POST", URL, auth, JSON_BODY), RESPONSE);
		return new GenericHttpProvider("example", description, builder);
	}

}
