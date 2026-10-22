package com.example.messaging_service.config;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies.NamingBase;
import tools.jackson.databind.annotation.JsonNaming;

/** Swagger UI at /swagger-ui.html and the OpenAPI JSON at /v3/api-docs. */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI messagingOpenApi() {
		return new OpenAPI().info(new Info().title("Messaging Service API")
			.version("v1")
			.description("Send SMS through Twilio, Vonage or a provider registered through the API."));
	}

	/**
	 * The docs generator reads Jackson 2 annotations, so it misses our Jackson 3 {@code @JsonNaming}
	 * and would show {@code displayName} where the API expects {@code display-name}.
	 */
	@Bean
	public ModelConverter jsonNamingConverter() {
		return new ModelConverter() {
			@Override
			public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
				Schema<?> schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
				JsonNaming naming = Json.mapper().constructType(type.getType()).getRawClass().getAnnotation(JsonNaming.class);
				if (schema == null || naming == null
						|| !(newInstance(naming.value()) instanceof NamingBase strategy)) {
					return schema;
				}
				Schema<?> model = schema.get$ref() == null ? schema
						: context.getDefinedModels().get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
				if (model != null && model.getProperties() != null) {
					// NamingBase ignores the config and member arguments and just translates the name.
					UnaryOperator<String> rename = name -> strategy.nameForField(null, null, name);
					Map<String, Schema> renamed = new LinkedHashMap<>();
					model.getProperties().forEach((name, property) -> renamed.put(rename.apply(name), property));
					model.setProperties(renamed);
					List<String> required = model.getRequired();
					if (required != null) {
						model.setRequired(required.stream().map(rename).toList());
					}
				}
				return schema;
			}
		};
	}

	private static Object newInstance(Class<?> type) {
		try {
			return type.getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException e) {
			return null;
		}
	}

}
