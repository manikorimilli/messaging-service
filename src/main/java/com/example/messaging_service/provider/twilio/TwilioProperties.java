package com.example.messaging_service.provider.twilio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Twilio credentials and sender, bound from {@code messaging.providers.twilio.*}.
 *
 * @param accountSid the account the message is sent from (AC...); part of the API URL
 * @param apiKey API key SID (SK...) used to log in. For local testing the Account SID also works.
 * @param apiSecret the API key's secret. For local testing the Auth Token also works.
 * @param smsFrom the Twilio phone number SMS is sent from, in E.164 format
 */
@ConfigurationProperties("messaging.providers.twilio")
public record TwilioProperties(String accountSid, String apiKey, String apiSecret, String smsFrom) {
}
