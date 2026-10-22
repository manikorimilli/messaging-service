package com.example.messaging_service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.dto.ProviderConfig;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Auth;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.AuthType;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Body;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.BodyFormat;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ErrorPaths;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Request;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Response;

/** A Plivo provider as sent to the provider APIs, with test values. */
public final class ProviderExamples {

	private ProviderExamples() {
	}

	public static Map<String, String> plivoVariables() {
		Map<String, String> variables = new LinkedHashMap<>();
		variables.put("auth-id", "MA123");
		variables.put("auth-token", "real-token");
		variables.put("from", "+15550001111");
		return variables;
	}

	public static ProviderConfig plivo() {
		return plivo(true, plivoVariables());
	}

	public static ProviderConfig plivo(boolean enabled, Map<String, String> variables) {
		return new ProviderConfig("PLIVO", "Plivo", EnumSet.of(MessageType.SMS), enabled, variables,
				new Request("POST", "https://api.plivo.com/v1/Account/{{auth-id}}/Message/",
						new Auth(AuthType.BASIC, "{{auth-id}}", "{{auth-token}}", null, null, null),
						new Body(BodyFormat.JSON, Map.of("src", "{{from}}", "dst", "{{to_digits}}", "text", "{{text}}"),
								null)),
				new Response(null, "$.message_uuid[0]", null, new ErrorPaths(null, "$.error"), null));
	}

	/** The same Plivo provider as the JSON body a client sends. */
	public static final String PLIVO_JSON = """
			{
			  "id": "plivo",
			  "display-name": "Plivo",
			  "channels": ["SMS"],
			  "enabled": true,
			  "variables": { "auth-id": "MA123", "auth-token": "real-token", "from": "+15550001111" },
			  "request": {
			    "method": "POST",
			    "url": "https://api.plivo.com/v1/Account/{{auth-id}}/Message/",
			    "auth": { "type": "basic", "username": "{{auth-id}}", "password": "{{auth-token}}" },
			    "body": {
			      "format": "json",
			      "fields": { "src": "{{from}}", "dst": "{{to_digits}}", "text": "{{text}}" }
			    }
			  },
			  "response": {
			    "success": { "http-status": ["2xx"] },
			    "id-path": "$.message_uuid[0]",
			    "error": { "message-path": "$.error" }
			  }
			}
			""";

}
