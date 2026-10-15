package com.example.messaging_service.provider.generic;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.messaging_service.provider.OutboundMessage;

/**
 * Replaces {@code {{name}}} placeholders with values. Replacement is a single pass, so a value
 * that itself contains {@code {{...}}} (e.g. in the message text) is never expanded again.
 */
final class TemplateFiller {

	/** Placeholders filled from the message, available in every provider block. */
	static final Set<String> BUILT_INS = Set.of("to", "to_digits", "text");

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)\\s*}}");

	private TemplateFiller() {
	}

	/** The values for one message: the built-ins plus the block's variables. */
	static Map<String, String> values(OutboundMessage message, Map<String, String> variables) {
		Map<String, String> values = new HashMap<>(variables);
		values.put("to", message.to());
		values.put("to_digits", message.to().startsWith("+") ? message.to().substring(1) : message.to());
		values.put("text", message.body());
		return values;
	}

	static String fill(String template, Map<String, String> values) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String value = values.get(matcher.group(1));
			if (value == null) {
				throw new IllegalArgumentException("Unknown placeholder {{" + matcher.group(1) + "}}");
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/** Placeholder names used in a template, e.g. "{{to}} {{from}}" gives [to, from]. */
	static Set<String> placeholders(String template) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}

}
