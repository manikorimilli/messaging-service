package com.example.messaging_service.dto;

import java.util.Map;
import java.util.Set;

import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One provider as the provider APIs return it. Every filled variable is masked as "****".
 *
 * @param source "providers.json" or "db.json"
 * @param readOnly true for providers from providers.json: the API cannot change or remove them
 * @param enabled true when the provider may send on its channels right now
 */
@JsonNaming(KebabCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderResponse(String id, String displayName, Set<MessageType> channels, String source,
		boolean readOnly, boolean enabled, Map<String, String> variables, Request request, Response response) {
}
