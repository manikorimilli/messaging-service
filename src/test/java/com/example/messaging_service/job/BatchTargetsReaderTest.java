package com.example.messaging_service.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import tools.jackson.databind.json.JsonMapper;

class BatchTargetsReaderTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Test
	void readsGroupsInOrder() {
		BatchTargets targets = read("""
				{ "version": "2026-10-20", "groups": [
				  { "provider": "TWILIO", "body": "Bill ready", "recipients": ["+910000000001", "+910000000002"] },
				  { "provider": "VONAGE", "body": "Shipped", "recipients": ["+910000000003"] } ] }
				""");

		assertThat(targets.version()).isEqualTo("2026-10-20");
		assertThat(targets.groups()).containsExactly(
				new BatchTargets.Group("TWILIO", "Bill ready", List.of("+910000000001", "+910000000002")),
				new BatchTargets.Group("VONAGE", "Shipped", List.of("+910000000003")));
	}

	@Test
	void theFileShippedWithTheProjectIsValid() {
		BatchTargets targets = new BatchTargetsReader(new ClassPathResource("batch-targets.json"), MAPPER).read();

		assertThat(targets.groups()).extracting(BatchTargets.Group::provider).containsExactly("TWILIO", "VONAGE");
	}

	@Test
	void readsTheFileAgainOnEveryCall(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("batch-targets.json");
		Files.writeString(file, """
				{ "version": "day-1", "groups": [ { "provider": "TWILIO", "body": "Hi", "recipients": ["+910000000001"] } ] }
				""");
		BatchTargetsReader reader = new BatchTargetsReader(new FileSystemResource(file), MAPPER);
		assertThat(reader.read().version()).isEqualTo("day-1");

		Files.writeString(file, """
				{ "version": "day-2", "groups": [ { "provider": "TWILIO", "body": "Hi", "recipients": ["+910000000002"] } ] }
				""");

		assertThat(reader.read().version()).isEqualTo("day-2");
	}

	@Test
	void missingFileIsAnError(@TempDir Path dir) {
		BatchTargetsReader reader = new BatchTargetsReader(new FileSystemResource(dir.resolve("nope.json")), MAPPER);

		assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not read");
	}

	@Test
	void brokenJsonIsAnError() {
		assertThatThrownBy(() -> read("{ not json")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("is not valid JSON");
	}

	@Test
	void missingGroupsIsAnError() {
		assertThatThrownBy(() -> read("{ \"version\": \"x\" }")).hasMessageContaining("groups is missing or empty");
	}

	@Test
	void everyProblemInAGroupIsListed() {
		assertThatThrownBy(() -> read("""
				{ "groups": [ { "provider": "TWILIO", "body": "Hi", "recipients": ["+910000000001"] },
				              { "provider": " ", "recipients": [] } ] }
				""")).hasMessageContaining("groups[1].provider is missing")
			.hasMessageContaining("groups[1].body is missing")
			.hasMessageContaining("groups[1].recipients is missing or empty")
			.hasMessageNotContaining("groups[0]");
	}

	private static BatchTargets read(String json) {
		return new BatchTargetsReader(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)), MAPPER).read();
	}

}
