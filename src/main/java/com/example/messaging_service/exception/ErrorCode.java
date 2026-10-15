package com.example.messaging_service.exception;

import org.springframework.http.HttpStatus;

/**
 * Every error code the API can return, with its HTTP status and title. One table for all errors.
 */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
	UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "Unsupported provider"),
	UNSUPPORTED_MESSAGE_TYPE(HttpStatus.BAD_REQUEST, "Unsupported message type"),
	PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Provider unavailable"),
	PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Provider timeout"),
	PROVIDER_REJECTED(HttpStatus.BAD_GATEWAY, "Provider rejected the message"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private final HttpStatus status;

	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

}
