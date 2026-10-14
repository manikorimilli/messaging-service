package com.example.messaging_service.provider;

/**
 * Provider-neutral reply: the provider's message id and its raw status (e.g. "queued").
 */
public record ProviderResult(String providerMessageId, String providerStatus) {
}
