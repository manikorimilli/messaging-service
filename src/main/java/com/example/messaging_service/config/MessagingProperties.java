package com.example.messaging_service.config;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.messaging_service.dto.MessageType;

/**
 * Routing per channel, bound from {@code messaging.channels.<channel>.*}. Provider credentials
 * live under {@code messaging.providers.<provider>.*} and are read by each adapter's own
 * properties class, not here.
 */
@ConfigurationProperties("messaging")
public record MessagingProperties(Map<String, ChannelSettings> channels) {

	public MessagingProperties {
		// Keys are channel names in lower case ("sms"), whatever case the config file used.
		Map<String, ChannelSettings> normalized = new HashMap<>();
		if (channels != null) {
			channels.forEach((name, settings) -> normalized.put(name.toLowerCase(Locale.ROOT), settings));
		}
		channels = Map.copyOf(normalized);
	}

	/**
	 * Settings for one channel, or null when the config has no block for it. The registry
	 * checks at startup that every channel with adapters has a block.
	 */
	public ChannelSettings channel(MessageType type) {
		return channels.get(type.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * Provider ids are trimmed and upper-cased here, so "vonage" in the config matches the
	 * adapter id "VONAGE".
	 */
	public record ChannelSettings(String primaryProvider, List<String> enabledProviders) {

		public ChannelSettings {
			primaryProvider = normalizeId(primaryProvider);
			enabledProviders = (enabledProviders == null) ? List.of()
					: enabledProviders.stream().map(ChannelSettings::normalizeId).filter(Objects::nonNull).toList();
		}

		public boolean isEnabled(String id) {
			return enabledProviders.contains(normalizeId(id));
		}

		private static String normalizeId(String id) {
			return (id == null || id.isBlank()) ? null : id.trim().toUpperCase(Locale.ROOT);
		}

	}

}
