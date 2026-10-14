package com.example.messaging_service.dto;

import java.time.Instant;

import com.example.messaging_service.provider.ProviderResult;

/**
 * Outgoing JSON on success. {@code status} is always ACCEPTED in this PoC: the provider took the
 * message, delivery is not confirmed.
 */
public record SendMessageResponse(String status, String provider, String providerMessageId, String providerStatus,
		Instant acceptedAt) {

	public static SendMessageResponse accepted(String provider, ProviderResult result) {
		return new SendMessageResponse("ACCEPTED", provider, result.providerMessageId(), result.providerStatus(),
				Instant.now());
	}

}
