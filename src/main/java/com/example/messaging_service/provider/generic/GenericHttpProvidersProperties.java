package com.example.messaging_service.provider.generic;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.messaging_service.dto.MessageType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import tools.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Everything about providers, bound from {@code providers.json}: routing per channel
 * ({@code messaging.channels.<channel>.*}) and one description per provider
 * ({@code messaging.http-providers.<name>.*}). {@code ProviderRegistry} turns each description into
 * one {@link GenericHttpProvider} at startup. Missing parts stay null here and are reported then.
 * The Jackson annotations let the provider APIs and db.json read and write the same block format.
 */
@ConfigurationProperties("messaging")
public record GenericHttpProvidersProperties(Map<String, ChannelSettings> channels,
		Map<String, Description> httpProviders) {

	public GenericHttpProvidersProperties {
		// Channel keys are lower case ("sms"), whatever case the config file used.
		Map<String, ChannelSettings> normalized = new HashMap<>();
		if (channels != null) {
			channels.forEach((name, settings) -> normalized.put(name.toLowerCase(Locale.ROOT), settings));
		}
		channels = Map.copyOf(normalized);
		httpProviders = (httpProviders == null) ? Map.of() : Map.copyOf(httpProviders);
	}

	/**
	 * Routing for one channel, or null when the config has no block for it. The registry checks
	 * at startup that every channel with providers has a block.
	 */
	public ChannelSettings channel(MessageType type) {
		return channels.get(type.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * Which providers may send on a channel, and which one is used when a request names none.
	 * Ids are trimmed and upper-cased, so "vonage" in the config matches the provider id "VONAGE".
	 */
	public record ChannelSettings(String primaryProvider, List<String> enabledProviders) {

		public ChannelSettings {
			primaryProvider = normalizeId(primaryProvider);
			enabledProviders = (enabledProviders == null) ? List.of()
					: enabledProviders.stream().map(ChannelSettings::normalizeId).filter(Objects::nonNull).toList();
		}

		public boolean isEnabled(String id) {
			return enabledProviders.contains(normalizeId(id));
		}

		private static String normalizeId(String id) {
			return (id == null || id.isBlank()) ? null : id.trim().toUpperCase(Locale.ROOT);
		}

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
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public record Request(String method, String url, Auth auth, Body body) {
	}

	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public record Auth(AuthType type, String username, String password, String token, String headerName,
			String headerValue) {
	}

	public enum AuthType {

		BASIC, BEARER, HEADER, NONE;

		/** Reads "basic", "Basic", ... from JSON. An unknown value becomes 400 VALIDATION_FAILED. */
		@JsonCreator
		public static AuthType from(String value) {
			return parse(AuthType.class, value, "request.auth.type");
		}

		@JsonValue
		public String json() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	/**
	 * @param fields body fields and their templates, e.g. {@code to: "{{to_digits}}"}
	 * @param unicodeFields extra fields added only when the text is not plain GSM-7
	 */
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public record Body(BodyFormat format, Map<String, String> fields, Map<String, String> unicodeFields) {

		public Body {
			// Keep the order written in the config, so requests and logs read like the YAML.
			fields = (fields == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
			unicodeFields = (unicodeFields == null) ? Map.of()
					: Collections.unmodifiableMap(new LinkedHashMap<>(unicodeFields));
		}

	}

	public enum BodyFormat {

		FORM, JSON;

		@JsonCreator
		public static BodyFormat from(String value) {
			return parse(BodyFormat.class, value, "request.body.format");
		}

		@JsonValue
		public String json() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value, String setting) {
		if (value == null) {
			return null;
		}
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equalsIgnoreCase(value.trim())) {
				return constant;
			}
		}
		List<String> allowed = Arrays.stream(type.getEnumConstants())
			.map(constant -> constant.name().toLowerCase(Locale.ROOT))
			.toList();
		throw new IllegalArgumentException("%s '%s' is not one of %s".formatted(setting, value, allowed));
	}

	/** How to read the reply. Paths are JSONPath, e.g. {@code $.messages[0].message-id}. */
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
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
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public record Success(List<String> httpStatus, String each, String field, String expected) {

		public Success {
			httpStatus = (httpStatus == null || httpStatus.isEmpty()) ? List.of("2xx") : List.copyOf(httpStatus);
		}

	}

	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public record ErrorPaths(String codePath, String messagePath) {
	}

	/**
	 * @param httpStatus statuses that mean "try later"; defaults to 429 and 5xx
	 * @param codes provider error codes that mean "try later", e.g. Vonage "1" and "5"
	 */
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public record Retry(List<String> httpStatus, List<String> codes) {

		public Retry {
			httpStatus = (httpStatus == null || httpStatus.isEmpty()) ? List.of("429", "5xx")
					: List.copyOf(httpStatus);
			codes = (codes == null) ? List.of() : List.copyOf(codes);
		}

	}

}
