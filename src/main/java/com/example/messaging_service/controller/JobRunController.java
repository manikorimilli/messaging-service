package com.example.messaging_service.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.messaging_service.service.JobHistoryService;

import tools.jackson.databind.JsonNode;

/** The daily job's history from jobHistory.json. Read-only. */
@RestController
@RequestMapping("/api/v1/job-runs")
public class JobRunController {

	private final JobHistoryService history;

	public JobRunController(JobHistoryService history) {
		this.history = history;
	}

	/** Every run, newest first: totals per run and per group, without the per-number results. */
	@GetMapping
	public List<JsonNode> runs() {
		return history.runs();
	}

	/** One run with every number's result. */
	@GetMapping("/{runId}")
	public JsonNode run(@PathVariable String runId) {
		return history.run(runId);
	}

}
