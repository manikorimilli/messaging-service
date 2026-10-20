package com.example.messaging_service.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Incoming JSON for POST /api/v1/messages/batch: one body to many numbers through one provider.
 * The upper limit on recipients is a setting (messaging.batch.max-recipients), checked by
 * BatchMessageService.
 *
 * @param provider optional provider id, e.g. "TWILIO"; the channel's primary is used when absent
 */
public record BatchSendRequest(
		@NotNull MessageType type,
		String provider,
		@NotEmpty List<@NotBlank @Pattern(regexp = SendMessageRequest.E164,
				message = SendMessageRequest.E164_MESSAGE) String> recipients,
		@NotBlank @Size(max = 1600) String body) {
}
