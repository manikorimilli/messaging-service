package com.example.messaging_service.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One provider added through the API: the same block as in providers.json, plus {@code enabled}.
 * It is the request body of POST and PUT /api/v1/providers and the format stored in db.json.
 *
 * @param enabled true when the provider may send; missing means false
 * @param variables raw values (secrets and settings) usable as {{name}} in templates
 */
@JsonNaming(KebabCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderConfig(String id, String displayName, Set<MessageType> channels, Boolean enabled,
		Map<String, String> variables, Request request, Response response) {

	public ProviderConfig {
		// Boolean, not boolean: Jackson 3 rejects a missing primitive, and a missing value means false.
		enabled = Boolean.TRUE.equals(enabled);
		variables = (variables == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
	}

	public Description toDescription() {
		return new Description(id, displayName, channels, variables, request, response);
	}

	public ProviderConfig withId(String newId) {
		return new ProviderConfig(newId, displayName, channels, enabled, variables, request, response);
	}

	public ProviderConfig withVariables(Map<String, String> newVariables) {
		return new ProviderConfig(id, displayName, channels, enabled, newVariables, request, response);
	}

}
