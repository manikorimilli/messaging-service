package com.example.messaging_service.exception;

import java.util.List;

/**
 * A provider sent to the provider APIs is invalid. Carries every problem so the client can fix
 * them all at once. Becomes 400 VALIDATION_FAILED with a "problems" list.
 */
public class InvalidProviderException extends RuntimeException {

	private final List<String> problems;

	public InvalidProviderException(List<String> problems) {
		super("The provider configuration is invalid.");
		this.problems = List.copyOf(problems);
	}

	public InvalidProviderException(String problem) {
		this(List.of(problem));
	}

	public List<String> problems() {
		return problems;
	}

}
