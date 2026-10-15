package com.example.messaging_service.provider.generic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Auth;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Body;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;

/**
 * Checks a provider description at startup and lists every problem, so a bad block stops the app
 * instead of failing a customer's request.
 */
final class DescriptionValidator {

	static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH");

	private static final Pattern HTTP_STATUS = Pattern.compile("[1-5](xx|\\d\\d)");

	private DescriptionValidator() {
	}

	static List<String> problems(Description d) {
		List<String> problems = new ArrayList<>();
		if (isBlank(d.id())) {
			problems.add("id is missing");
		}
		if (d.channels() == null || d.channels().isEmpty()) {
			problems.add("channels is missing");
		}
		for (String name : d.variables().keySet()) {
			if (TemplateFiller.BUILT_INS.contains(name)) {
				problems.add("variables.%s clashes with the built-in {{%s}}".formatted(name, name));
			}
		}
		checkRequest(d.request(), d.variables().keySet(), problems);
		checkResponse(d.response(), problems);
		return problems;
	}

	private static void checkRequest(Request request, Set<String> variables, List<String> problems) {
		if (request == null) {
			problems.add("request is missing");
			return;
		}
		if (isBlank(request.method())) {
			problems.add("request.method is missing");
		}
		else if (!METHODS.contains(request.method().toUpperCase())) {
			problems.add("request.method must be POST, PUT or PATCH");
		}

		if (isBlank(request.url())) {
			problems.add("request.url is missing");
		}
		else {
			if (!request.url().startsWith("https://") && !request.url().startsWith("http://")) {
				problems.add("request.url must start with https:// or http://");
			}
			// Message values (to, text) are never put in the URL: they would need URL encoding.
			for (String name : TemplateFiller.placeholders(request.url())) {
				if (!variables.contains(name)) {
					problems.add("request.url may only use variables, not {{%s}}".formatted(name));
				}
			}
		}

		checkAuth(request.auth(), variables, problems);
		checkBody(request.body(), variables, problems);
	}

	private static void checkAuth(Auth auth, Set<String> variables, List<String> problems) {
		if (auth == null || auth.type() == null) {
			problems.add("request.auth.type is missing (basic, bearer, header or none)");
			return;
		}
		switch (auth.type()) {
			case BASIC -> {
				required(auth.username(), "request.auth.username", variables, problems);
				required(auth.password(), "request.auth.password", variables, problems);
			}
			case BEARER -> required(auth.token(), "request.auth.token", variables, problems);
			case HEADER -> {
				if (isBlank(auth.headerName())) {
					problems.add("request.auth.header-name is missing");
				}
				required(auth.headerValue(), "request.auth.header-value", variables, problems);
			}
			case NONE -> {
			}
		}
	}

	private static void checkBody(Body body, Set<String> variables, List<String> problems) {
		if (body == null || body.format() == null) {
			problems.add("request.body.format is missing (form or json)");
			return;
		}
		if (body.fields().isEmpty()) {
			problems.add("request.body.fields is missing");
		}
		checkTemplates(body.fields(), "request.body.fields", variables, problems);
		checkTemplates(body.unicodeFields(), "request.body.unicode-fields", variables, problems);
	}

	private static void checkResponse(Response response, List<String> problems) {
		if (response == null) {
			problems.add("response is missing");
			return;
		}
		if (isBlank(response.idPath())) {
			problems.add("response.id-path is missing");
		}
		checkPath(response.idPath(), "response.id-path", problems);
		checkPath(response.statusPath(), "response.status-path", problems);
		checkPath(response.error().codePath(), "response.error.code-path", problems);
		checkPath(response.error().messagePath(), "response.error.message-path", problems);
		checkPath(response.success().each(), "response.success.each", problems);
		if (response.success().each() != null
				&& (isBlank(response.success().field()) || response.success().expected() == null)) {
			problems.add("response.success.each needs field and expected");
		}
		checkStatuses(response.success().httpStatus(), "response.success.http-status", problems);
		checkStatuses(response.retry().httpStatus(), "response.retry.http-status", problems);
	}

	private static void required(String template, String setting, Set<String> variables, List<String> problems) {
		if (isBlank(template)) {
			problems.add(setting + " is missing");
			return;
		}
		checkTemplate(template, setting, variables, problems);
	}

	private static void checkTemplates(Map<String, String> templates, String setting, Set<String> variables,
			List<String> problems) {
		templates.forEach((field, template) -> checkTemplate(template, setting + "." + field, variables, problems));
	}

	private static void checkTemplate(String template, String setting, Set<String> variables,
			List<String> problems) {
		for (String name : TemplateFiller.placeholders(template)) {
			if (!TemplateFiller.BUILT_INS.contains(name) && !variables.contains(name)) {
				problems.add("unknown placeholder {{%s}} in %s".formatted(name, setting));
			}
		}
	}

	private static void checkPath(String path, String setting, List<String> problems) {
		if (isBlank(path)) {
			return;
		}
		try {
			JsonPath.compile(path);
		}
		catch (InvalidPathException e) {
			problems.add("%s is not valid JSONPath: %s".formatted(setting, path));
		}
	}

	private static void checkStatuses(List<String> statuses, String setting, List<String> problems) {
		for (String status : statuses) {
			if (!HTTP_STATUS.matcher(status).matches()) {
				problems.add("%s: '%s' is not a status like 200 or 2xx".formatted(setting, status));
			}
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
