package com.example.messaging_service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The job-runs API over HTTP, reading a jobHistory.json in a temp folder. */
@SpringBootTest(properties = { "messaging.http-providers.twilio.variables.account-sid=ACtest",
		"messaging.http-providers.twilio.variables.api-key=SKtest",
		"messaging.http-providers.twilio.variables.api-secret=test-secret",
		"messaging.http-providers.twilio.variables.from=+15550001111",
		"messaging.http-providers.vonage.variables.api-key=test-key",
		"messaging.http-providers.vonage.variables.api-secret=test-secret",
		"messaging.http-providers.vonage.variables.from=MyBrand" })
@AutoConfigureMockMvc
class JobRunApiTests {

	private static final String HISTORY = """
			{ "runs": [
			  { "run-id": "run-1", "started-at": "2026-10-22T10:00:00+05:30", "status": "COMPLETED",
			    "total": 1, "accepted": 1, "failed": 0,
			    "groups": [ { "provider": "TWILIO", "status": "SENT", "total": 1, "accepted": 1, "failed": 0,
			      "results": [ { "to": "+910000000001", "status": "ACCEPTED", "provider-message-id": "SM1" } ] } ] },
			  { "run-id": "run-2", "started-at": "2026-10-23T10:00:00+05:30", "status": "FAILED",
			    "total": 0, "accepted": 0, "failed": 0, "groups": [] } ] }
			""";

	@TempDir
	static Path dir;

	@DynamicPropertySource
	static void files(DynamicPropertyRegistry registry) {
		registry.add("messaging.db-file", () -> dir.resolve("db.json").toString());
		registry.add("messaging.batch-job.history-file", () -> dir.resolve("jobHistory.json").toString());
	}

	@Autowired
	private MockMvc mvc;

	@Test
	void listIsNewestFirstWithoutTheNumbers() throws Exception {
		Files.writeString(dir.resolve("jobHistory.json"), HISTORY);

		mvc.perform(get("/api/v1/job-runs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].run-id").value("run-2"))
			.andExpect(jsonPath("$[1].run-id").value("run-1"))
			.andExpect(jsonPath("$[1].started-at").value("2026-10-22T10:00:00+05:30"))
			.andExpect(jsonPath("$[1].groups[0].accepted").value(1))
			.andExpect(jsonPath("$[1].groups[0].results").doesNotExist());
	}

	@Test
	void oneRunHasEveryNumber() throws Exception {
		Files.writeString(dir.resolve("jobHistory.json"), HISTORY);

		mvc.perform(get("/api/v1/job-runs/run-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[0].results[0].to").value("+910000000001"))
			.andExpect(jsonPath("$.groups[0].results[0].provider-message-id").value("SM1"));

		mvc.perform(get("/api/v1/job-runs/nope"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("JOB_RUN_NOT_FOUND"));
	}

	@Test
	void noHistoryYetGivesAnEmptyList() throws Exception {
		Files.deleteIfExists(dir.resolve("jobHistory.json"));

		mvc.perform(get("/api/v1/job-runs")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

}
