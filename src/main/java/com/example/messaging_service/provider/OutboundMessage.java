package com.example.messaging_service.provider;

import com.example.messaging_service.dto.MessageType;

/**
 * Provider-neutral message given to a provider adapter.
 */
public record OutboundMessage(MessageType type, String to, String body) {
}
