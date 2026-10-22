package com.example.messaging_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.example.messaging_service.dto.ProviderConfig;
import com.example.messaging_service.provider.store.ProviderStore;

/** The provider APIs over HTTP, with the real registry and a db.json in a temp folder. */
@SpringBootTest(properties = { "messaging.http-providers.twilio.variables.account-sid=ACtest",
		"messaging.http-providers.twilio.variables.api-key=SKtest",
		"messaging.http-providers.twilio.variables.api-secret=test-secret",
		"messaging.http-providers.twilio.variables.from=+15550001111",
		"messaging.http-providers.vonage.variables.api-key=test-key",
		"messaging.http-providers.vonage.variables.api-secret=test-secret",
		"messaging.http-providers.vonage.variables.from=MyBrand" })
@AutoConfigureMockMvc
class ProviderApiTests {

	@TempDir
	static Path dir;

	@DynamicPropertySource
	static void dbFile(DynamicPropertyRegistry registry) {
		registry.add("messaging.db-file", () -> dir.resolve("db.json").toString());
	}

	@Autowired
	private MockMvc mvc;

	@Test
	void registerListUpdateAndUnregister() throws Exception {
		Path file = dir.resolve("db.json");

		mvc.perform(post("/api/v1/providers").contentType(MediaType.APPLICATION_JSON).content(ProviderExamples.PLIVO_JSON))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/v1/providers/PLIVO"))
			.andExpect(jsonPath("$.id").value("PLIVO"))
			.andExpect(jsonPath("$.source").value("db.json"))
			.andExpect(jsonPath("$.read-only").value(false))
			.andExpect(jsonPath("$.enabled").value(true))
			.andExpect(jsonPath("$.variables.auth-token").value("****"))
			.andExpect(jsonPath("$.request.auth.type").value("basic"));
		assertThat(Files.readString(file)).contains("real-token");

		mvc.perform(post("/api/v1/providers").contentType(MediaType.APPLICATION_JSON).content(ProviderExamples.PLIVO_JSON))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROVIDER_ALREADY_EXISTS"));

		mvc.perform(get("/api/v1/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id").value(contains("PLIVO", "TWILIO", "VONAGE")))
			.andExpect(jsonPath("$[1].source").value("providers.json"))
			.andExpect(jsonPath("$[1].read-only").value(true))
			.andExpect(jsonPath("$[1].variables.api-secret").value("****"));

		mvc.perform(put("/api/v1/providers/plivo").contentType(MediaType.APPLICATION_JSON)
			.content(ProviderExamples.PLIVO_JSON.replace("\"real-token\"", "\"****\"")
				.replace("\"enabled\": true", "\"enabled\": false")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false));
		// What a restart reads back: the stored secret was kept.
		assertThat(new ProviderStore(file).load()).singleElement()
			.extracting(ProviderConfig::variables)
			.satisfies(variables -> assertThat(variables).containsEntry("auth-token", "real-token"));

		mvc.perform(delete("/api/v1/providers/PLIVO")).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/providers/PLIVO"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROVIDER_NOT_FOUND"));
		assertThat(new ProviderStore(file).load()).isEmpty();
	}

	@Test
	void invalidBlockGives400WithEveryProblem() throws Exception {
		mvc.perform(post("/api/v1/providers").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"id\": \"broken\", \"channels\": [\"SMS\"] }"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.problems").value(hasItem("request is missing")))
			.andExpect(jsonPath("$.problems").value(hasItem("response is missing")));

		mvc.perform(post("/api/v1/providers").contentType(MediaType.APPLICATION_JSON)
			.content(ProviderExamples.PLIVO_JSON.replace("\"basic\"", "\"basik\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("request.auth.type 'basik' is not one of [basic, bearer, header, none]"));
	}

	@Test
	void providersFromProvidersJsonAreReadOnly() throws Exception {
		mvc.perform(get("/api/v1/providers/twilio"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.read-only").value(true));
		mvc.perform(put("/api/v1/providers/TWILIO").contentType(MediaType.APPLICATION_JSON)
			.content(ProviderExamples.PLIVO_JSON.replace("\"plivo\"", "\"TWILIO\"")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROVIDER_READ_ONLY"));
		mvc.perform(delete("/api/v1/providers/VONAGE"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROVIDER_READ_ONLY"));
	}

}
