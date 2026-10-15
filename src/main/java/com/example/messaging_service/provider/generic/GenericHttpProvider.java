package com.example.messaging_service.provider.generic;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Auth;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.BodyFormat;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;

/**
 * Sends messages through any REST API described in {@code providers.json}, like a saved request in
 * Postman: the block says what to send and how to read the reply. One object per block, created
 * at startup by {@link GenericHttpProviderRegistration}.
 */
class GenericHttpProvider implements MessageProvider {

	private static final Logger log = LoggerFactory.getLogger(GenericHttpProvider.class);

	private final String key;

	private final Description description;

	private final RestClient client;

	private final ResponseReader reader;

	/**
	 * @param key the block name under {@code messaging.http-providers}, e.g. "vonage"
	 * @throws IllegalStateException listing every problem when the description is incomplete
	 */
	GenericHttpProvider(String key, Description description, RestClient.Builder builder) {
		List<String> problems = DescriptionValidator.problems(description);
		if (!problems.isEmpty()) {
			throw new IllegalStateException(
					"Invalid messaging.http-providers.%s:\n - %s".formatted(key, String.join("\n - ", problems)));
		}
		this.key = key;
		this.description = description;
		// Spring Boot's builder already carries the shared JDK client and timeouts.
		this.client = builder.build();
		this.reader = new ResponseReader(description.id(), description.name(), description.response());
	}

	@Override
	public String id() {
		return description.id();
	}

	@Override
	public Set<MessageType> supportedTypes() {
		return EnumSet.copyOf(description.channels());
	}

	/** True when every variable has a value. Logs which ones are empty (names only, never values). */
	@Override
	public boolean isConfigured() {
		List<String> missing = description.variables()
			.entrySet()
			.stream()
			.filter(variable -> !StringUtils.hasText(variable.getValue()))
			.map(Map.Entry::getKey)
			.sorted()
			.toList();
		if (!missing.isEmpty()) {
			log.warn("messaging.http-providers.{}.variables has no value for {}", key, missing);
		}
		return missing.isEmpty();
	}

	@Override
	public ProviderResult send(OutboundMessage message) {
		Request request = description.request();
		Map<String, String> values = TemplateFiller.values(message, description.variables());

		Map<String, String> fields = new LinkedHashMap<>();
		request.body().fields().forEach((name, template) -> fields.put(name, TemplateFiller.fill(template, values)));
		if (!GsmCharset.isGsm7(message.body())) {
			request.body().unicodeFields()
				.forEach((name, template) -> fields.put(name, TemplateFiller.fill(template, values)));
		}
		log.debug("{}: {} {} with fields {}", id(), request.method(), request.url(), fields.keySet());

		return client.method(HttpMethod.valueOf(request.method().toUpperCase(Locale.ROOT)))
			.uri(URI.create(TemplateFiller.fill(request.url(), values)))
			.headers(headers -> authenticate(headers, request.auth(), values))
			.contentType(request.body().format() == BodyFormat.FORM ? MediaType.APPLICATION_FORM_URLENCODED
					: MediaType.APPLICATION_JSON)
			.body(request.body().format() == BodyFormat.FORM ? toForm(fields) : fields)
			.exchange((req, response) -> reader.read(response.getStatusCode().value(),
					StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
	}

	private static void authenticate(HttpHeaders headers, Auth auth, Map<String, String> values) {
		switch (auth.type()) {
			case BASIC -> headers.setBasicAuth(TemplateFiller.fill(auth.username(), values),
					TemplateFiller.fill(auth.password(), values));
			case BEARER -> headers.setBearerAuth(TemplateFiller.fill(auth.token(), values));
			case HEADER -> headers.set(auth.headerName(), TemplateFiller.fill(auth.headerValue(), values));
			case NONE -> {
			}
		}
	}

	private static MultiValueMap<String, String> toForm(Map<String, String> fields) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		fields.forEach(form::add);
		return form;
	}

}
