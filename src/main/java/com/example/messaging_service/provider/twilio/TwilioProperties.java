package com.example.messaging_service.provider.twilio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Twilio credentials and sender, bound from {@code messaging.providers.twilio.*}.
 *
 * @param smsFrom the Twilio phone number SMS is sent from, in E.164 format
 */
@ConfigurationProperties("messaging.providers.twilio")
public record TwilioProperties(String accountSid, String authToken, String smsFrom) {
}
