package com.example.messaging_service.dto;

import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * The channel a message is sent on. New channels (WHATSAPP, EMAIL) are added here later.
 */
public enum MessageType {

	SMS;

	/**
	 * Accepts any letter case ("sms", "Sms") from JSON. An unknown value throws, which the
	 * exception handler turns into 400 VALIDATION_FAILED.
	 */
	@JsonCreator
	public static MessageType from(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		for (MessageType type : values()) {
			if (type.name().equals(normalized)) {
				return type;
			}
		}
		throw new IllegalArgumentException("type must be one of " + Arrays.toString(values()));
	}

}
