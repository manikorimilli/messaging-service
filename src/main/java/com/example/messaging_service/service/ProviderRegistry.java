package com.example.messaging_service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.generic.GenericHttpProvider;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;

/**
 * Owns every provider. Builds one {@link GenericHttpProvider} per block in providers.json, adds
 * any hand-written {@link MessageProvider} beans, picks the one to use for a request, and gives
 * back each provider's description. Checks the configuration once at startup so a request never
 * fails because of it.
 */
@Component
public class ProviderRegistry {

	private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

	static final String MASK = "****";

	// channel -> (provider id -> provider), e.g. SMS -> { TWILIO: ..., VONAGE: ... }
	private final Map<MessageType, Map<String, MessageProvider>> adapters = new EnumMap<>(MessageType.class);

	// Every provider id we have a provider for, on any channel. Sorted for error messages.
	private final Set<String> knownProviderIds = new TreeSet<>();

	private final GenericHttpProvidersProperties properties;

	@Autowired
	public ProviderRegistry(ObjectProvider<MessageProvider> handWritten, GenericHttpProvidersProperties properties,
			RestClient.Builder builder) {
		this(handWritten.orderedStream().toList(), properties, builder);
	}

	/**
	 * @param handWritten providers written as Java classes (SDK, JWT, ...); usually none
	 * @param builder Spring Boot's builder with the shared HTTP client; each provider gets a copy
	 */
	public ProviderRegistry(List<MessageProvider> handWritten, GenericHttpProvidersProperties properties,
			RestClient.Builder builder) {
		this.properties = properties;
		List<String> problems = new ArrayList<>();

		List<MessageProvider> providers = new ArrayList<>(handWritten);
		new TreeMap<>(properties.httpProviders()).forEach((key, description) -> {
			try {
				providers.add(new GenericHttpProvider(key, description, builder.clone()));
			}
			catch (IllegalStateException e) {
				problems.add(e.getMessage().replace("\n - ", "\n     - "));
			}
		});

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

	/**
	 * The providers.json block of one registered provider, with every variable value masked.
	 * Empty when there is no such provider for the channel, or when it is a hand-written class.
	 */
	public Optional<Description> description(MessageType type, String id) {
		MessageProvider provider = adapters.getOrDefault(type, Map.of()).get(normalizeId(id));
		return (provider instanceof GenericHttpProvider generic) ? Optional.of(masked(generic.description()))
				: Optional.empty();
	}

	/** The providers.json blocks of every registered provider for a channel, sorted by id, masked. */
	public List<Description> descriptions(MessageType type) {
		return adapters.getOrDefault(type, Map.of())
			.values()
			.stream()
			.filter(GenericHttpProvider.class::isInstance)
			.map(provider -> masked(((GenericHttpProvider) provider).description()))
			.sorted(Comparator.comparing(Description::id))
			.toList();
	}

	/** Secrets live in variables: a filled value becomes "****", an empty one stays empty. */
	private static Description masked(Description d) {
		Map<String, String> variables = new LinkedHashMap<>();
		d.variables().forEach((name, value) -> variables.put(name, StringUtils.hasText(value) ? MASK : ""));
		return new Description(d.id(), d.displayName(), d.channels(), variables, d.request(), d.response());
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
				problems.add("%s is enabled for %s but its credentials are missing".formatted(id, type));
			}
		}
	}

	private static String normalizeId(String id) {
		return (id == null || id.isBlank()) ? null : id.trim().toUpperCase(Locale.ROOT);
	}

}
