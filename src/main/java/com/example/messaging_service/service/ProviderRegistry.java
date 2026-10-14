package com.example.messaging_service.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.messaging_service.config.MessagingProperties;
import com.example.messaging_service.config.MessagingProperties.ChannelSettings;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;

/**
 * Holds every provider adapter, keyed by channel and provider id, and picks the one to use for
 * a request. Checks the configuration once at startup so a request never fails because of it.
 */
@Component
public class ProviderRegistry {

	private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

	// channel -> (provider id -> adapter), e.g. SMS -> { TWILIO: ..., VONAGE: ... }
	private final Map<MessageType, Map<String, MessageProvider>> adapters = new EnumMap<>(MessageType.class);

	// Every provider id we have an adapter for, on any channel. Sorted for error messages.
	private final Set<String> knownProviderIds = new TreeSet<>();

	private final MessagingProperties properties;

	public ProviderRegistry(List<MessageProvider> providers, MessagingProperties properties) {
		this.properties = properties;
		List<String> problems = new ArrayList<>();

		for (MessageProvider provider : providers) {
			if (provider.id() == null || !provider.id().equals(normalizeId(provider.id()))) {
				problems.add("%s has id '%s'; ids must be upper case with no spaces"
					.formatted(provider.getClass().getSimpleName(), provider.id()));
				continue;
			}
			knownProviderIds.add(provider.id());
			for (MessageType type : provider.supportedTypes()) {
				MessageProvider existing = adapters.computeIfAbsent(type, t -> new HashMap<>())
					.putIfAbsent(provider.id(), provider);
				if (existing != null) {
					problems.add("Two adapters for (%s, %s): %s and %s".formatted(type, provider.id(),
							existing.getClass().getSimpleName(), provider.getClass().getSimpleName()));
				}
			}
		}

		for (MessageType type : MessageType.values()) {
			checkChannel(type, problems);
		}

		if (!problems.isEmpty()) {
			throw new IllegalStateException(
					"Invalid messaging configuration:\n - " + String.join("\n - ", problems));
		}
		adapters.forEach((type, byId) -> log.info("{} adapters {}, enabled {}, primary {}", type,
				new TreeSet<>(byId.keySet()), properties.channel(type).enabledProviders(),
				properties.channel(type).primaryProvider()));
	}

	/**
	 * Picks the provider for one request: the requested one, or the channel's primary when none
	 * is given. Throws MessagingException when it is unknown, has no adapter for the channel, or
	 * is not enabled for the channel.
	 */
	public MessageProvider resolve(String requested, MessageType type) {
		ChannelSettings channel = properties.channel(type);
		String id = normalizeId(requested);
		if (id == null) {
			if (channel == null) {
				throw new MessagingException(ErrorCode.UNSUPPORTED_MESSAGE_TYPE, null,
						"No provider is configured for %s.".formatted(type));
			}
			id = channel.primaryProvider();
		}

		if (!knownProviderIds.contains(id)) {
			throw new MessagingException(ErrorCode.UNSUPPORTED_PROVIDER, null,
					"Provider '%s' is not supported. Supported providers: %s.".formatted(id,
							String.join(", ", knownProviderIds)));
		}
		MessageProvider provider = adapters.getOrDefault(type, Map.of()).get(id);
		if (provider == null) {
			throw new MessagingException(ErrorCode.UNSUPPORTED_MESSAGE_TYPE, id,
					"Provider '%s' does not support %s.".formatted(id, type));
		}
		// Not null here: startup guarantees a config block for every channel with adapters.
		if (!channel.isEnabled(id)) {
			throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, id,
					"Provider '%s' is currently disabled for %s.".formatted(id, type));
		}
		return provider;
	}

	private void checkChannel(MessageType type, List<String> problems) {
		Map<String, MessageProvider> channelAdapters = adapters.getOrDefault(type, Map.of());
		ChannelSettings channel = properties.channel(type);
		String key = "messaging.channels." + type.name().toLowerCase(Locale.ROOT);

		if (channel == null) {
			if (!channelAdapters.isEmpty()) {
				problems.add("%s has adapters %s but no %s block".formatted(type, new TreeSet<>(channelAdapters.keySet()),
						key));
			}
			return;
		}

		String primary = channel.primaryProvider();
		if (primary == null) {
			problems.add("%s.primary-provider is not set".formatted(key));
		}
		else if (!channelAdapters.containsKey(primary)) {
			problems.add("%s.primary-provider %s has no adapter for %s".formatted(key, primary, type));
		}
		else if (!channel.isEnabled(primary)) {
			problems.add("%s.primary-provider %s is not in %s.enabled-providers".formatted(key, primary, key));
		}

		for (String id : channel.enabledProviders()) {
			MessageProvider provider = channelAdapters.get(id);
			if (provider == null) {
				problems.add("%s.enabled-providers contains %s, which has no adapter for %s".formatted(key, id, type));
			}
			else if (!provider.isConfigured()) {
				problems.add("%s is enabled for %s but its credentials are missing (messaging.providers.%s.*)"
					.formatted(id, type, id.toLowerCase(Locale.ROOT)));
			}
		}
	}

	private static String normalizeId(String id) {
		return (id == null || id.isBlank()) ? null : id.trim().toUpperCase(Locale.ROOT);
	}

}
