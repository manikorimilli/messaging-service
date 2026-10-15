package com.example.messaging_service.provider;

import java.util.Set;

import com.example.messaging_service.dto.MessageType;

/**
 * The contract every provider adapter implements. The only thing the core and the adapters
 * share.
 */
public interface MessageProvider {

	/**
	 * Stable upper-case provider id used in requests and config, e.g. "TWILIO". Two adapters may
	 * share an id if they serve different channels (Twilio SMS now, Twilio WhatsApp later).
	 */
	String id();

	/** Channels this adapter can send. For now always EnumSet.of(SMS). */
	Set<MessageType> supportedTypes();

	/** False when credentials are missing. Checked at startup for every enabled provider. */
	default boolean isConfigured() {
		return true;
	}

	/**
	 * Sends one message. Returns when the provider has ACCEPTED it (not when it is delivered).
	 * Throws MessagingException (PROVIDER_REJECTED or PROVIDER_UNAVAILABLE) when the provider
	 * answers with an error.
	 */
	ProviderResult send(OutboundMessage message);

}
