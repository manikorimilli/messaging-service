package com.example.messaging_service.provider.vonage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vonage credentials and sender, bound from {@code messaging.providers.vonage.*}.
 *
 * @param smsFrom a number or an alphanumeric sender id, depending on the destination country
 */
@ConfigurationProperties("messaging.providers.vonage")
public record VonageProperties(String apiKey, String apiSecret, String smsFrom) {
}
