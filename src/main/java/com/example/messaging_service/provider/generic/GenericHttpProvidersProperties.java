package com.example.messaging_service.provider.generic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.messaging_service.dto.MessageType;

/**
 * Provider descriptions, bound from {@code messaging.http-providers.<name>.*} (see
 * {@code providers.json}). Each description is turned into one {@link GenericHttpProvider} at
 * startup. Missing parts stay null here; {@link GenericHttpProvider} reports them at startup.
 */
@ConfigurationProperties("messaging")
public record GenericHttpProvidersProperties(Map<String, Description> httpProviders) {

	public GenericHttpProvidersProperties {
		httpProviders = (httpProviders == null) ? Map.of() : Map.copyOf(httpProviders);
	}

	/**
	 * One provider.
	 *
	 * @param id provider id used in requests and routing, e.g. "VONAGE"
	 * @param displayName name used in error messages, e.g. "Vonage"; defaults to the id
	 * @param channels channels this provider can send, e.g. [SMS]
	 * @param variables secrets and settings usable as {{name}} in templates; all must be filled
	 * for an enabled provider
	 */
	public record Description(String id, String displayName, Set<MessageType> channels,
			Map<String, String> variables, Request request, Response response) {

		public Description {
			// A variable left empty in YAML may bind as null; keep it, isConfigured() reports it.
			variables = (variables == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
		}

		String name() {
			return (displayName == null || displayName.isBlank()) ? id : displayName;
		}

	}

	/** What to send. Only variables may be used in the URL; message values go in the body. */
	public record Request(String method, String url, Auth auth, Body body) {
	}

	public record Auth(AuthType type, String username, String password, String token, String headerName,
			String headerValue) {
	}

	public enum AuthType {

		BASIC, BEARER, HEADER, NONE

	}

	/**
	 * @param fields body fields and their templates, e.g. {@code to: "{{to_digits}}"}
	 * @param unicodeFields extra fields added only when the text is not plain GSM-7
	 */
	public record Body(BodyFormat format, Map<String, String> fields, Map<String, String> unicodeFields) {

		public Body {
			// Keep the order written in the config, so requests and logs read like the YAML.
			fields = (fields == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
			unicodeFields = (unicodeFields == null) ? Map.of()
					: Collections.unmodifiableMap(new LinkedHashMap<>(unicodeFields));
		}

	}

	public enum BodyFormat {

		FORM, JSON

	}

	/** How to read the reply. Paths are JSONPath, e.g. {@code $.messages[0].message-id}. */
	public record Response(Success success, String idPath, String statusPath, ErrorPaths error, Retry retry) {

		public Response {
			success = (success == null) ? new Success(null, null, null, null) : success;
			error = (error == null) ? new ErrorPaths(null, null) : error;
			retry = (retry == null) ? new Retry(null, null) : retry;
		}

	}

	/**
	 * @param httpStatus statuses that may mean success, e.g. "2xx" or "200"; defaults to 2xx
	 * @param each optional list that must be checked item by item, e.g. {@code $.messages}
	 * @param field the field each item must have
	 * @param expected the value that field must have, e.g. "0"
	 */
	public record Success(List<String> httpStatus, String each, String field, String expected) {

		public Success {
			httpStatus = (httpStatus == null || httpStatus.isEmpty()) ? List.of("2xx") : List.copyOf(httpStatus);
		}

	}

	public record ErrorPaths(String codePath, String messagePath) {
	}

	/**
	 * @param httpStatus statuses that mean "try later"; defaults to 429 and 5xx
	 * @param codes provider error codes that mean "try later", e.g. Vonage "1" and "5"
	 */
	public record Retry(List<String> httpStatus, List<String> codes) {

		public Retry {
			httpStatus = (httpStatus == null || httpStatus.isEmpty()) ? List.of("429", "5xx")
					: List.copyOf(httpStatus);
			codes = (codes == null) ? List.of() : List.copyOf(codes);
		}

	}

}
