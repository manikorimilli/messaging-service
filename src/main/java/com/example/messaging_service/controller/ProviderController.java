package com.example.messaging_service.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.messaging_service.dto.ProviderConfig;
import com.example.messaging_service.dto.ProviderResponse;
import com.example.messaging_service.service.ProviderRegistry;

/** Register, update, unregister and list providers. Every rule lives in ProviderRegistry. */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

	private final ProviderRegistry registry;

	public ProviderController(ProviderRegistry registry) {
		this.registry = registry;
	}

	/** 201 Created: saved to db.json and usable right away. */
	@PostMapping
	public ResponseEntity<ProviderResponse> register(@RequestBody ProviderConfig config) {
		ProviderResponse saved = registry.register(config);
		return ResponseEntity.created(URI.create("/api/v1/providers/" + saved.id())).body(saved);
	}

	@PutMapping("/{id}")
	public ProviderResponse update(@PathVariable String id, @RequestBody ProviderConfig config) {
		return registry.update(id, config);
	}

	/** 204 No Content: removed from db.json and no longer usable. */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> unregister(@PathVariable String id) {
		registry.unregister(id);
		return ResponseEntity.noContent().build();
	}

	/** Every provider, from providers.json and db.json. */
	@GetMapping
	public List<ProviderResponse> providers() {
		return registry.providers();
	}

	@GetMapping("/{id}")
	public ProviderResponse provider(@PathVariable String id) {
		return registry.provider(id);
	}

}
