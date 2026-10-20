package com.example.messaging_service.job;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads batch-targets.json from a "classpath:" or "file:" location. Reads the file again on every
 * call and keeps nothing between calls, so a file replaced overnight is picked up by the next run.
 */
class BatchTargetsReader {

	private final Resource file;

	private final JsonMapper mapper;

	BatchTargetsReader(Resource file, JsonMapper mapper) {
		this.file = file;
		this.mapper = mapper;
	}

	/** Throws IllegalStateException when the file is missing, not JSON, or has an invalid group. */
	BatchTargets read() {
		BatchTargets targets;
		try (InputStream in = file.getInputStream()) {
			targets = mapper.readValue(in, BatchTargets.class);
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not read %s: %s".formatted(file.getDescription(), e.getMessage()));
		}
		catch (JacksonException e) {
			throw new IllegalStateException(
					"%s is not valid JSON: %s".formatted(file.getDescription(), e.getOriginalMessage()));
		}
		List<String> problems = problems(targets);
		if (!problems.isEmpty()) {
			throw new IllegalStateException(
					"Invalid %s:\n - %s".formatted(file.getDescription(), String.join("\n - ", problems)));
		}
		return targets;
	}

	private static List<String> problems(BatchTargets targets) {
		List<String> problems = new ArrayList<>();
		if (targets == null || targets.groups() == null || targets.groups().isEmpty()) {
			problems.add("groups is missing or empty");
			return problems;
		}
		for (int i = 0; i < targets.groups().size(); i++) {
			BatchTargets.Group group = targets.groups().get(i);
			if (group == null) {
				problems.add("groups[%d] is empty".formatted(i));
				continue;
			}
			if (!StringUtils.hasText(group.provider())) {
				problems.add("groups[%d].provider is missing".formatted(i));
			}
			if (!StringUtils.hasText(group.body())) {
				problems.add("groups[%d].body is missing".formatted(i));
			}
			if (group.recipients() == null || group.recipients().isEmpty()) {
				problems.add("groups[%d].recipients is missing or empty".formatted(i));
			}
		}
		return problems;
	}

}
