package com.example.messaging_service.provider.generic;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.messaging_service.provider.OutboundMessage;

/**
 * Replaces {@code {{name}}} and {@code {name}} placeholders with values. Replacement is a single
 * pass, so a value that itself contains braces (e.g. in the message text) is never expanded again.
 * {@code ${...}} is never a placeholder here: it is left as written.
 */
final class TemplateFiller {

	/** Placeholders filled from the message, available in every provider block. */
	static final Set<String> BUILT_INS = Set.of("to", "to_digits", "text");

	/** Prefix of the uaap names: message values under the names the console uses. */
	static final String UAAP_PREFIX = "uaap.";

	/** Each uaap name and the built-in it is filled like. */
	static final Map<String, String> UAAP = Map.of(
			"uaap.message.to", "to",
			"uaap.message.to_digits", "to_digits",
			"uaap.message.text", "text",
			"uaap.message.template", "text");

	/** {@code {{name}}} (spaces allowed inside) or {@code {name}}, but not {@code ${name}}. */
	private static final Pattern PLACEHOLDER = Pattern
		.compile("(?<!\\$)\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}|(?<![${])\\{([A-Za-z0-9_.-]+)}(?!})");

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
			String name = name(matcher);
			String value = values.get(UAAP.getOrDefault(name, name));
			if (value == null) {
				throw new IllegalArgumentException("Unknown placeholder " + matcher.group());
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/** Placeholder names used in a template, e.g. "{{to}} {from}" gives [to, from]. */
	static Set<String> placeholders(String template) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			names.add(name(matcher));
		}
		return names;
	}

	/** True for a name filled from the message: a built-in or a known uaap name. */
	static boolean isMessageValue(String name) {
		return BUILT_INS.contains(name) || UAAP.containsKey(name);
	}

	private static String name(Matcher matcher) {
		return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
	}

}
