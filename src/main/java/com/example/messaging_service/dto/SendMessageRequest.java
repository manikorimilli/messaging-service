package com.example.messaging_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Incoming JSON for POST /api/v1/messages.
 *
 * @param provider optional provider id, e.g. "TWILIO"; the channel's primary is used when absent
 */
public record SendMessageRequest(
		@NotNull MessageType type,
		String provider,
		@NotBlank @Pattern(regexp = SendMessageRequest.E164, message = SendMessageRequest.E164_MESSAGE) String to,
		@NotBlank @Size(max = 1600) String body) {

	/** Phone number format shared by the single and the batch API. */
	public static final String E164 = "^\\+[1-9]\\d{7,14}$";

	public static final String E164_MESSAGE = "must be in E.164 format, for example +919876543210";

}
