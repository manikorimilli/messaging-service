package com.example.messaging_service.provider.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.messaging_service.ProviderExamples;
import com.example.messaging_service.dto.ProviderConfig;

class ProviderStoreTest {

	@TempDir
	Path dir;

	@Test
	void createsAnEmptyFileWhenThereIsNone() throws IOException {
		Path file = dir.resolve("data/db.json");

		assertThat(new ProviderStore(file).load()).isEmpty();
		assertThat(Files.readString(file)).contains("\"providers\"");
	}

	@Test
	void loadsWhatWasSaved() {
		ProviderStore store = new ProviderStore(dir.resolve("db.json"));
		ProviderConfig plivo = ProviderExamples.plivo();

		store.save(List.of(plivo));

		assertThat(new ProviderStore(dir.resolve("db.json")).load()).containsExactly(plivo);
	}

	@Test
	void writesTheProvidersJsonFormatWithRealValues() throws IOException {
		Path file = dir.resolve("db.json");

		new ProviderStore(file).save(List.of(ProviderExamples.plivo()));

		assertThat(Files.readString(file)).contains("\"plivo\"", "\"display-name\"", "\"id-path\"", "\"basic\"",
				"\"json\"", "\"enabled\" : true", "real-token");
		assertThat(dir.resolve("db.json.tmp")).doesNotExist();
	}

	@Test
	void invalidJsonFailsAndLeavesTheFileAsItWas() throws IOException {
		Path file = dir.resolve("db.json");
		Files.writeString(file, "{ not json");

		assertThatThrownBy(() -> new ProviderStore(file).load()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Could not read")
			.hasMessageContaining("db.json");
		assertThat(Files.readString(file)).isEqualTo("{ not json");
	}

}
