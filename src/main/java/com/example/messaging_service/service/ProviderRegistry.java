package com.example.messaging_service.service;

import java.util.ArrayList;
import java.util.Collections;
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
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.dto.ProviderConfig;
import com.example.messaging_service.dto.ProviderResponse;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.InvalidProviderException;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.generic.GenericHttpProvider;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.Description;
import com.example.messaging_service.provider.store.ProviderStore;

/**
 * Owns every provider. Builds one {@link GenericHttpProvider} per block in providers.json and per
 * provider in db.json, adds any hand-written {@link MessageProvider} beans, picks the one to use
 * for a request, and gives back each provider's description. Checks the configuration once at
 * startup so a request never fails because of it.
 * <p>
 * Providers in db.json can be registered, updated and unregistered while the app runs. Each change
 * is checked, saved to db.json, and only then made live by swapping in a new snapshot. Providers
 * from providers.json are read-only.
 */
@Component
public class ProviderRegistry {

	private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

	static final String MASK = "****";

	static final String FROM_CONFIG = "providers.json";

	static final String FROM_DB = "db.json";

	private static final Pattern VALID_ID = Pattern.compile("[A-Z0-9_-]+");

	private final GenericHttpProvidersProperties properties;

	private final RestClient.Builder builder;

	private final ProviderStore store;

	// Providers from providers.json and Java classes. Built once, never change.
	private final List<MessageProvider> fixed;

	private final Set<String> fixedIds = new TreeSet<>();

	// Everything a request needs, replaced as a whole on every change. Requests read it without a lock.
	private volatile Snapshot snapshot;

	@Autowired
	public ProviderRegistry(ObjectProvider<MessageProvider> handWritten, GenericHttpProvidersProperties properties,
			RestClient.Builder builder, ProviderStore store) {
		this(handWritten.orderedStream().toList(), properties, builder, store);
	}

	/** No stored providers; for tests. */
	public ProviderRegistry(List<MessageProvider> handWritten, GenericHttpProvidersProperties properties,
			RestClient.Builder builder) {
		this(handWritten, properties, builder, ProviderStore.inMemory());
	}

	/**
	 * @param handWritten providers written as Java classes (SDK, JWT, ...); usually none
	 * @param builder Spring Boot's builder with the shared HTTP client; each provider gets a copy
	 * @param store where providers added through the API are kept (db.json)
	 */
	public ProviderRegistry(List<MessageProvider> handWritten, GenericHttpProvidersProperties properties,
			RestClient.Builder builder, ProviderStore store) {
		this.properties = properties;
		this.builder = builder;
		this.store = store;
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
		this.fixed = List.copyOf(providers);
		fixed.forEach(provider -> fixedIds.add(provider.id()));

		Snapshot first = snapshot(loadStored(problems), problems);
		for (MessageType type : MessageType.values()) {
			checkChannel(first, type, problems);
		}

		if (!problems.isEmpty()) {
			throw new IllegalStateException(
					"Invalid messaging configuration:\n - " + String.join("\n - ", problems));
		}
		this.snapshot = first;
		first.adapters()
			.forEach((type, byId) -> log.info("{} adapters {}, enabled {}, primary {}", type,
					new TreeSet<>(byId.keySet()), enabledIds(first, type), primary(type)));
		log.info("{} provider(s) loaded from {}", first.stored().size(), store.location());
	}

	/**
	 * Picks the provider for one request: the requested one, or the channel's primary when none
	 * is given. Throws MessagingException when it is unknown, has no adapter for the channel, or
	 * is not enabled for the channel.
	 */
	public MessageProvider resolve(String requested, MessageType type) {
		Snapshot current = snapshot;
		String id = normalizeId(requested);
		if (id == null) {
			id = primary(type);
			if (id == null) {
				throw new MessagingException(ErrorCode.UNSUPPORTED_MESSAGE_TYPE, null,
						"No provider is configured for %s.".formatted(type));
			}
		}

		if (!current.knownIds().contains(id)) {
			throw new MessagingException(ErrorCode.UNSUPPORTED_PROVIDER, null,
					"Provider '%s' is not supported. Supported providers: %s.".formatted(id,
							String.join(", ", current.knownIds())));
		}
		MessageProvider provider = current.adapters().getOrDefault(type, Map.of()).get(id);
		if (provider == null) {
			throw new MessagingException(ErrorCode.UNSUPPORTED_MESSAGE_TYPE, id,
					"Provider '%s' does not support %s.".formatted(id, type));
		}
		if (!isEnabled(current, id, type)) {
			throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, id,
					"Provider '%s' is currently disabled for %s.".formatted(id, type));
		}
		return provider;
	}

	/**
	 * The block of one provider, with every variable value masked. Empty when there is no such
	 * provider for the channel, or when it is a hand-written class.
	 */
	public Optional<Description> description(MessageType type, String id) {
		MessageProvider provider = snapshot.adapters().getOrDefault(type, Map.of()).get(normalizeId(id));
		return (provider instanceof GenericHttpProvider generic) ? Optional.of(masked(generic.description()))
				: Optional.empty();
	}

	/** The blocks of every provider for a channel, sorted by id, masked. */
	public List<Description> descriptions(MessageType type) {
		return snapshot.adapters()
			.getOrDefault(type, Map.of())
			.values()
			.stream()
			.filter(GenericHttpProvider.class::isInstance)
			.map(provider -> masked(((GenericHttpProvider) provider).description()))
			.sorted(Comparator.comparing(Description::id))
			.toList();
	}

	/** Every provider from providers.json and db.json, sorted by id, secrets masked. */
	public List<ProviderResponse> providers() {
		Snapshot current = snapshot;
		Map<String, ProviderResponse> byId = new TreeMap<>();
		for (MessageProvider provider : fixed) {
			if (provider instanceof GenericHttpProvider generic) {
				byId.put(provider.id(), response(current, generic.description(), FROM_CONFIG));
			}
		}
		current.stored()
			.values()
			.forEach(stored -> byId.put(stored.config().id(),
					response(current, stored.config().toDescription(), FROM_DB)));
		return List.copyOf(byId.values());
	}

	/** One provider from providers.json or db.json, secrets masked. 404 when there is none. */
	public ProviderResponse provider(String id) {
		String normalized = normalizeId(id);
		return providers().stream()
			.filter(provider -> provider.id().equals(normalized))
			.findFirst()
			.orElseThrow(() -> notFound(normalized));
	}

	/** Adds a provider to db.json and makes it usable right away. */
	public synchronized ProviderResponse register(ProviderConfig config) {
		String id = requireValidId(config.id());
		if (snapshot.knownIds().contains(id)) {
			throw new MessagingException(ErrorCode.PROVIDER_ALREADY_EXISTS, id,
					"Provider '%s' already exists.".formatted(id));
		}
		ProviderResponse saved = apply(config.withId(id));
		log.info("Registered provider {} (enabled: {})", id, saved.enabled());
		return saved;
	}

	/**
	 * Replaces a provider in db.json. A variable sent as "****" keeps its stored value, so secrets
	 * do not have to be sent again.
	 */
	public synchronized ProviderResponse update(String id, ProviderConfig config) {
		String normalized = requireValidId(id);
		String bodyId = normalizeId(config.id());
		if (bodyId != null && !bodyId.equals(normalized)) {
			throw new InvalidProviderException(
					"id in the body ('%s') does not match the id in the path ('%s')".formatted(bodyId, normalized));
		}
		Stored current = requireStored(normalized);

		Map<String, String> variables = new LinkedHashMap<>(config.variables());
		variables.replaceAll((name, value) -> MASK.equals(value) && current.config().variables().containsKey(name)
				? current.config().variables().get(name) : value);

		ProviderResponse saved = apply(config.withId(normalized).withVariables(variables));
		log.info("Updated provider {} (enabled: {})", normalized, saved.enabled());
		return saved;
	}

	/** Removes a provider from db.json. It stops being usable right away. */
	public synchronized void unregister(String id) {
		String normalized = requireValidId(id);
		requireStored(normalized);
		for (MessageType type : MessageType.values()) {
			ChannelSettings channel = properties.channel(type);
			if (channel != null && (normalized.equals(channel.primaryProvider()) || channel.isEnabled(normalized))) {
				throw new MessagingException(ErrorCode.PROVIDER_IN_USE, normalized,
						"Provider '%s' is named in the %s routing in providers.json and cannot be removed."
							.formatted(normalized, type));
			}
		}
		Map<String, Stored> next = new TreeMap<>(snapshot.stored());
		next.remove(normalized);
		swap(next);
		log.info("Unregistered provider {}", normalized);
	}

	/** Check, save, switch. Nothing changes when a check or the save fails. */
	private ProviderResponse apply(ProviderConfig config) {
		String id = config.id();
		List<String> problems = new ArrayList<>(GenericHttpProvider.problems(config.toDescription()));
		List<String> empty = new ArrayList<>();
		config.variables().forEach((name, value) -> {
			if (value != null && value.contains("${")) {
				problems.add("variables.%s: send the real value; placeholders like ${...} are not supported"
					.formatted(name));
			}
			else if (MASK.equals(value)) {
				problems.add("variables.%s is \"%s\" but there is no stored value to keep".formatted(name, MASK));
			}
			else if (!StringUtils.hasText(value)) {
				empty.add(name);
			}
		});
		if (!empty.isEmpty() && isEnabledAnywhere(id, config)) {
			problems.add("these variables have no value: %s; every variable needs one when the provider is enabled"
				.formatted(String.join(", ", empty)));
		}
		if (!problems.isEmpty()) {
			throw new InvalidProviderException(problems);
		}

		Map<String, Stored> next = new TreeMap<>(snapshot.stored());
		next.put(id, new Stored(config, build(config)));
		Snapshot saved = swap(next);
		return response(saved, config.toDescription(), FROM_DB);
	}

	/** Saves the stored providers to db.json first, then makes them live. */
	private Snapshot swap(Map<String, Stored> stored) {
		List<String> problems = new ArrayList<>();
		Snapshot next = snapshot(stored, problems);
		if (!problems.isEmpty()) {
			// register() already rejected taken ids, so this means a bug, not bad input.
			throw new IllegalStateException(String.join("; ", problems));
		}
		store.save(stored.values().stream().map(Stored::config).toList());
		snapshot = next;
		return next;
	}

	private GenericHttpProvider build(ProviderConfig config) {
		return new GenericHttpProvider(config.id().toLowerCase(Locale.ROOT), config.toDescription(), builder.clone());
	}

	/** Reads db.json at startup. A broken entry is a startup problem, like a broken providers.json block. */
	private Map<String, Stored> loadStored(List<String> problems) {
		Map<String, Stored> stored = new TreeMap<>();
		for (ProviderConfig config : store.load()) {
			String id = normalizeId(config.id());
			if (id == null || !VALID_ID.matcher(id).matches()) {
				problems.add("%s has a provider with id '%s'; ids may only use letters, digits, '_' and '-'"
					.formatted(store.location(), config.id()));
				continue;
			}
			if (fixedIds.contains(id) || stored.containsKey(id)) {
				problems.add("%s has provider %s, which already exists".formatted(store.location(), id));
				continue;
			}
			try {
				ProviderConfig normalized = config.withId(id);
				stored.put(id, new Stored(normalized, build(normalized)));
			}
			catch (IllegalStateException e) {
				problems.add("In %s: %s".formatted(store.location(), e.getMessage().replace("\n - ", "\n     - ")));
			}
		}
		return stored;
	}

	private Snapshot snapshot(Map<String, Stored> stored, List<String> problems) {
		Map<MessageType, Map<String, MessageProvider>> adapters = new EnumMap<>(MessageType.class);
		Set<String> knownIds = new TreeSet<>();

		List<MessageProvider> providers = new ArrayList<>(fixed);
		stored.values().forEach(entry -> providers.add(entry.provider()));
		for (MessageProvider provider : providers) {
			if (provider.id() == null || !provider.id().equals(normalizeId(provider.id()))) {
				problems.add("%s has id '%s'; ids must be upper case with no spaces"
					.formatted(provider.getClass().getSimpleName(), provider.id()));
				continue;
			}
			knownIds.add(provider.id());
			for (MessageType type : provider.supportedTypes()) {
				MessageProvider existing = adapters.computeIfAbsent(type, t -> new HashMap<>())
					.putIfAbsent(provider.id(), provider);
				if (existing != null) {
					problems.add("Two adapters for (%s, %s): %s and %s".formatted(type, provider.id(),
							existing.getClass().getSimpleName(), provider.getClass().getSimpleName()));
				}
			}
		}
		adapters.replaceAll((type, byId) -> Collections.unmodifiableMap(byId));
		return new Snapshot(Collections.unmodifiableMap(adapters), Collections.unmodifiableSet(knownIds),
				Collections.unmodifiableMap(stored));
	}

	/** Enabled in providers.json routing, or stored with "enabled": true. */
	private boolean isEnabled(Snapshot current, String id, MessageType type) {
		ChannelSettings channel = properties.channel(type);
		if (channel != null && channel.isEnabled(id)) {
			return true;
		}
		Stored stored = current.stored().get(id);
		return stored != null && stored.config().enabled() && stored.provider().supportedTypes().contains(type);
	}

	private boolean isEnabledAnywhere(String id, ProviderConfig config) {
		if (config.enabled()) {
			return true;
		}
		for (MessageType type : MessageType.values()) {
			ChannelSettings channel = properties.channel(type);
			if (channel != null && channel.isEnabled(id)) {
				return true;
			}
		}
		return false;
	}

	private Set<String> enabledIds(Snapshot current, MessageType type) {
		Set<String> ids = new TreeSet<>();
		current.adapters().getOrDefault(type, Map.of()).keySet().forEach(id -> {
			if (isEnabled(current, id, type)) {
				ids.add(id);
			}
		});
		return ids;
	}

	private String primary(MessageType type) {
		ChannelSettings channel = properties.channel(type);
		return (channel == null) ? null : channel.primaryProvider();
	}

	private ProviderResponse response(Snapshot current, Description d, String source) {
		boolean enabled = d.channels() != null
				&& d.channels().stream().anyMatch(type -> isEnabled(current, d.id(), type));
		return new ProviderResponse(d.id(), d.displayName(), d.channels(), source, FROM_CONFIG.equals(source),
				enabled, masked(d).variables(), d.request(), d.response());
	}

	private String requireValidId(String id) {
		String normalized = normalizeId(id);
		if (normalized == null) {
			throw new InvalidProviderException("id is missing");
		}
		if (!VALID_ID.matcher(normalized).matches()) {
			throw new InvalidProviderException(
					"id '%s' may only use letters, digits, '_' and '-'".formatted(id.trim()));
		}
		return normalized;
	}

	private Stored requireStored(String id) {
		if (fixedIds.contains(id)) {
			throw new MessagingException(ErrorCode.PROVIDER_READ_ONLY, id,
					"Provider '%s' comes from providers.json and cannot be changed through the API.".formatted(id));
		}
		Stored stored = snapshot.stored().get(id);
		if (stored == null) {
			throw notFound(id);
		}
		return stored;
	}

	private static MessagingException notFound(String id) {
		return new MessagingException(ErrorCode.PROVIDER_NOT_FOUND, null,
				"Provider '%s' was not found.".formatted(id));
	}

	/** Secrets live in variables: a filled value becomes "****", an empty one stays empty. */
	private static Description masked(Description d) {
		Map<String, String> variables = new LinkedHashMap<>();
		d.variables().forEach((name, value) -> variables.put(name, StringUtils.hasText(value) ? MASK : ""));
		return new Description(d.id(), d.displayName(), d.channels(), variables, d.request(), d.response());
	}

	private void checkChannel(Snapshot current, MessageType type, List<String> problems) {
		Map<String, MessageProvider> channelAdapters = current.adapters().getOrDefault(type, Map.of());
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
		for (Stored stored : current.stored().values()) {
			String id = stored.config().id();
			if (stored.config().enabled() && stored.provider().supportedTypes().contains(type)
					&& !channel.isEnabled(id) && !stored.provider().isConfigured()) {
				problems.add("%s is enabled in %s but its credentials are missing".formatted(id, store.location()));
			}
		}
	}

	private static String normalizeId(String id) {
		return (id == null || id.isBlank()) ? null : id.trim().toUpperCase(Locale.ROOT);
	}

	/** A provider kept in db.json: the block as saved and the provider built from it. */
	private record Stored(ProviderConfig config, GenericHttpProvider provider) {
	}

	/** Everything a request needs, consistent at one moment in time. */
	private record Snapshot(Map<MessageType, Map<String, MessageProvider>> adapters, Set<String> knownIds,
			Map<String, Stored> stored) {
	}

}
