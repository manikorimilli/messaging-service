package com.example.messaging_service.provider.generic;

import java.util.List;
import java.util.Map;

import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

/**
 * Turns a provider's HTTP reply into a {@link ProviderResult} or a {@link MessagingException},
 * following the block's {@code response} rules.
 */
final class ResponseReader {

	// Missing paths read as null (or an empty list) instead of throwing.
	private static final Configuration JSON = Configuration.defaultConfiguration()
		.addOptions(Option.SUPPRESS_EXCEPTIONS);

	private final String id;

	private final String name;

	private final Response rules;

	ResponseReader(String id, String name, Response rules) {
		this.id = id;
		this.name = name;
		this.rules = rules;
	}

	ProviderResult read(int status, String body) {
		DocumentContext json = parse(body);

		if (matches(rules.retry().httpStatus(), status)) {
			throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, id,
					"%s is unavailable (HTTP %d).".formatted(name, status));
		}
		if (!matches(rules.success().httpStatus(), status)) {
			String reason = read(json, rules.error().messagePath());
			throw failure(read(json, rules.error().codePath()), (reason == null) ? "HTTP " + status : reason);
		}
		if (rules.success().each() != null) {
			checkEachItem(json);
		}
		return new ProviderResult(read(json, rules.idPath()), read(json, rules.statusPath()));
	}

	/** For replies like Vonage's, where HTTP 200 is not enough: every item must pass. */
	private void checkEachItem(DocumentContext json) {
		Object items = (json == null) ? null : json.read(rules.success().each());
		if (!(items instanceof List<?> list) || list.isEmpty()) {
			throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, id, "%s returned an empty reply.".formatted(name));
		}
		for (Object item : list) {
			Object value = (item instanceof Map<?, ?> map) ? map.get(rules.success().field()) : null;
			if (!rules.success().expected().equals(String.valueOf(value))) {
				String code = read(json, rules.error().codePath());
				code = (code == null) ? String.valueOf(value) : code;
				String reason = read(json, rules.error().messagePath());
				throw failure(code, (reason == null) ? "status " + code : reason);
			}
		}
	}

	private MessagingException failure(String code, String reason) {
		if (code != null && rules.retry().codes().contains(code)) {
			return new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, id, code,
					"%s is unavailable: %s".formatted(name, reason));
		}
		return new MessagingException(ErrorCode.PROVIDER_REJECTED, id, code,
				"%s rejected the message: %s".formatted(name, reason));
	}

	/** "2xx" matches 200 to 299, "429" matches only 429. */
	static boolean matches(List<String> patterns, int status) {
		for (String pattern : patterns) {
			boolean match = pattern.endsWith("xx") ? status / 100 == pattern.charAt(0) - '0'
					: Integer.parseInt(pattern) == status;
			if (match) {
				return true;
			}
		}
		return false;
	}

	/** The value at a path as text; for a list (e.g. a filter), its first item. */
	private static String read(DocumentContext json, String path) {
		if (json == null || path == null) {
			return null;
		}
		Object value = json.read(path);
		if (value instanceof List<?> list) {
			value = list.isEmpty() ? null : list.get(0);
		}
		return (value == null) ? null : String.valueOf(value);
	}

	private static DocumentContext parse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return JsonPath.using(JSON).parse(body);
		}
		catch (RuntimeException e) {
			return null; // not JSON, e.g. an HTML error page; the rules then fall back to the HTTP status
		}
	}

}
