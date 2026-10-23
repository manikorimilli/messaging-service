package com.example.messaging_service.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads jobHistory.json for the job-runs API. Runs are returned exactly as the daily job wrote
 * them. The file is read on every call, so a run the job just added shows up right away.
 */
@Service
public class JobHistoryService {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Path file;

	public JobHistoryService(@Value("${messaging.batch-job.history-file}") Path file) {
		this.file = file;
	}

	/** Every run, newest first, without the per-number results. Empty when the job has not run yet. */
	public List<JsonNode> runs() {
		List<JsonNode> runs = read();
		for (JsonNode run : runs) {
			run.path("groups").forEach(group -> ((ObjectNode) group).remove("results"));
		}
		return runs.reversed();
	}

	/** One run with every number's result. */
	public JsonNode run(String runId) {
		return read().stream()
			.filter(run -> runId.equals(run.path("run-id").asString()))
			.findFirst()
			.orElseThrow(() -> new MessagingException(ErrorCode.JOB_RUN_NOT_FOUND, null,
					"Job run '%s' was not found.".formatted(runId)));
	}

	private List<JsonNode> read() {
		List<JsonNode> runs = new ArrayList<>();
		if (Files.notExists(file)) {
			return runs;
		}
		try {
			JSON.readTree(Files.readString(file)).path("runs").forEach(runs::add);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
		}
		return runs;
	}

}
