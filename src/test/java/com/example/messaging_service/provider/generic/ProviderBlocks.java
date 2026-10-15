package com.example.messaging_service.provider.generic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;

/** Loads a block from the real providers.json, with test values for its variables. */
final class ProviderBlocks {

	private ProviderBlocks() {
	}

	static Description load(String key, Map<String, String> variables) {
		try {
			MutablePropertySources sources = new MutablePropertySources();
			Map<String, Object> overrides = new HashMap<>();
			variables.forEach((name, value) -> overrides.put("messaging.http-providers." + key + ".variables." + name, value));
			sources.addFirst(new MapPropertySource("test-variables", overrides));
			new YamlPropertySourceLoader().load("providers.json", new ClassPathResource("providers.json"))
				.forEach(sources::addLast);

			Binder binder = new Binder(ConfigurationPropertySources.from(sources),
					new PropertySourcesPlaceholdersResolver(sources));
			return binder.bind("messaging", GenericHttpProvidersProperties.class).get().httpProviders().get(key);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static GenericHttpProvider provider(String key, Map<String, String> variables, RestClient.Builder builder) {
		return new GenericHttpProvider(key, load(key, variables), builder);
	}

}
