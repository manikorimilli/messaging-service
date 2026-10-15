package com.example.messaging_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import com.example.messaging_service.dto.SendMessageRequest;
import com.example.messaging_service.dto.SendMessageResponse;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;

/**
 * Runs a single send: picks the provider, calls it, and turns network failures into
 * MessagingException. The only place that calls a provider.
 */
@Service
public class MessageService {

	private static final Logger log = LoggerFactory.getLogger(MessageService.class);

	private final ProviderRegistry registry;

	public MessageService(ProviderRegistry registry) {
		this.registry = registry;
	}

	public SendMessageResponse send(SendMessageRequest request) {
		MessageProvider provider = registry.resolve(request.provider(), request.type());
		OutboundMessage message = new OutboundMessage(request.type(), request.to(), request.body());

		try {
			ProviderResult result = provider.send(message);
			// Never log the body; mask the number.
			log.info("{} sent via {} to {}: id={} status={}", message.type(), provider.id(), mask(message.to()),
					result.providerMessageId(), result.providerStatus());
			return SendMessageResponse.accepted(provider.id(), result);
		}
		catch (ResourceAccessException e) {
			throw logged(MessagingException.fromNetworkFailure(provider.id(), e), message);
		}
		catch (MessagingException e) {
			throw logged(e, message);
		}
	}

	private static MessagingException logged(MessagingException e, OutboundMessage message) {
		log.warn("{} failed via {} to {}: {} {}", message.type(), e.provider(), mask(message.to()), e.code(),
				e.getMessage());
		return e;
	}

	/** "+919876543210" becomes "+91******3210". */
	static String mask(String to) {
		if (to == null || to.length() <= 7) {
			return "***";
		}
		return to.substring(0, 3) + "*".repeat(to.length() - 7) + to.substring(to.length() - 4);
	}

}
