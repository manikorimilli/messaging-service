package com.example.messaging_service.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.dto.BatchSendResponse.RecipientResult;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.dto.SendMessageRequest;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;

/**
 * Sends one body to many numbers through one provider. Used by the batch API and by the daily
 * job. Each number goes through {@link MessageService}, one after another; a failed number is
 * recorded and the loop continues. Nothing is retried.
 */
@Service
public class BatchMessageService {

	private static final Logger log = LoggerFactory.getLogger(BatchMessageService.class);

	private static final Pattern E164 = Pattern.compile(SendMessageRequest.E164);

	private final ProviderRegistry registry;

	private final MessageService messageService;

	private final int maxRecipients;

	public BatchMessageService(ProviderRegistry registry, MessageService messageService,
			@Value("${messaging.batch.max-recipients:100}") int maxRecipients) {
		this.registry = registry;
		this.messageService = messageService;
		this.maxRecipients = maxRecipients;
	}

	/**
	 * Throws MessagingException before anything is sent when the list is empty or too long, or
	 * the provider cannot be used. After that it never throws: every number gets a result.
	 */
	public BatchSendResponse send(MessageType type, String provider, List<String> recipients, String body) {
		if (recipients == null || recipients.isEmpty()) {
			throw new MessagingException(ErrorCode.VALIDATION_FAILED, null, "recipients must not be empty.");
		}
		if (recipients.size() > maxRecipients) {
			throw new MessagingException(ErrorCode.VALIDATION_FAILED, null,
					"recipients must contain at most %d numbers, got %d.".formatted(maxRecipients, recipients.size()));
		}
		// Every number gets the same body, so a repeated number would only get the same SMS twice.
		Set<String> unique = new LinkedHashSet<>(recipients);
		String providerId = registry.resolve(provider, type).id();

		List<RecipientResult> results = new ArrayList<>(unique.size());
		for (String to : unique) {
			results.add(sendOne(type, providerId, to, body));
		}
		BatchSendResponse response = BatchSendResponse.of(providerId, recipients.size() - unique.size(), results);
		log.info("Batch via {} done: total={} accepted={} failed={} duplicatesRemoved={}", providerId,
				response.total(), response.accepted(), response.failed(), response.duplicatesRemoved());
		return response;
	}

	public int maxRecipients() {
		return maxRecipients;
	}

	private RecipientResult sendOne(MessageType type, String providerId, String to, String body) {
		// The API validates numbers up front; numbers from the job's file are checked here.
		if (to == null || !E164.matcher(to).matches()) {
			log.warn("{} skipped via {} to {}: not E.164", type, providerId, MessageService.mask(to));
			return RecipientResult.failed(to, ErrorCode.VALIDATION_FAILED, "to " + SendMessageRequest.E164_MESSAGE);
		}
		try {
			// MessageService logs each success and failure with the number masked.
			return RecipientResult.accepted(to, messageService.send(new SendMessageRequest(type, providerId, to, body)));
		}
		catch (MessagingException e) {
			return RecipientResult.failed(to, e);
		}
		catch (RuntimeException e) {
			log.error("{} failed via {} to {}: unexpected error", type, providerId, MessageService.mask(to), e);
			return RecipientResult.failed(to, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
		}
	}

}
