package com.example.messaging_service.provider.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.messaging_service.dto.ProviderConfig;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes db.json, the file that keeps providers added through the API. File work only:
 * every rule lives in ProviderRegistry. The file looks like
 * {@code { "providers": { "plivo": { ...block... } } }}, keyed by id in lower case.
 */
@Component
public class ProviderStore {

	private static final Logger log = LoggerFactory.getLogger(ProviderStore.class);

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	// Null: nothing is written to disk (tests).
	private final Path file;

	private List<ProviderConfig> memory = List.of();

	public ProviderStore(@Value("${messaging.db-file}") Path file) {
		this.file = file;
	}

	/** A store that keeps providers in memory only, for tests. */
	public static ProviderStore inMemory() {
		return new ProviderStore(null);
	}

	/** Where the providers are kept, for messages. */
	public String location() {
		return (file == null) ? "memory" : file.toAbsolutePath().toString();
	}

	/**
	 * Every stored provider. Creates an empty file when there is none yet.
	 *
	 * @throws IllegalStateException when the file cannot be read or is not valid JSON; it is left
	 * untouched so nothing is lost
	 */
	public List<ProviderConfig> load() {
		if (file == null) {
			return memory;
		}
		try {
			if (Files.notExists(file)) {
				save(List.of());
				log.info("Created {}", location());
				return List.of();
			}
			return List.copyOf(JSON.readValue(Files.readString(file), Db.class).providers().values());
		}
		catch (IOException | JacksonException e) {
			throw new IllegalStateException("Could not read %s: %s".formatted(location(), e.getMessage()), e);
		}
	}

	/**
	 * Replaces the stored providers with these. Writes a temp file first and renames it over the
	 * old one, so a crash leaves either the old file or the new one, never half a file.
	 *
	 * @throws UncheckedIOException when the file cannot be written; the old file stays as it was
	 */
	public void save(Collection<ProviderConfig> providers) {
		if (file == null) {
			memory = List.copyOf(providers);
			return;
		}
		Map<String, ProviderConfig> byKey = new TreeMap<>();
		providers.forEach(provider -> byKey.put(provider.id().toLowerCase(Locale.ROOT), provider));
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			if (file.toAbsolutePath().getParent() != null) {
				Files.createDirectories(file.toAbsolutePath().getParent());
			}
			Files.writeString(temp, JSON.writeValueAsString(new Db(byKey)));
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not write " + location(), e);
		}
	}

	record Db(Map<String, ProviderConfig> providers) {

		Db {
			providers = (providers == null) ? Map.of() : providers;
		}

	}

}
