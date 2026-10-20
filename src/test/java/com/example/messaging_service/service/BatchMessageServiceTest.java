package com.example.messaging_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.dto.BatchSendResponse.RecipientResult;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;

class BatchMessageServiceTest {

	private static final String A = "+910000000001";

	private static final String B = "+910000000002";

	private static final String C = "+910000000003";

	/** Numbers the fake provider was asked to send to, in order. */
	private final List<String> sent = new ArrayList<>();

	@Test
	void sendsEveryNumberThroughTheRequestedProvider() {
		BatchMessageService service = serviceWith(message -> new ProviderResult("SM-" + message.to(), "queued"));

		BatchSendResponse response = service.send(MessageType.SMS, "fake", List.of(A, B), "Hello");

		assertThat(sent).containsExactly(A, B);
		assertThat(response.provider()).isEqualTo("FAKE");
		assertThat(response.total()).isEqualTo(2);
		assertThat(response.accepted()).isEqualTo(2);
		assertThat(response.failed()).isZero();
		assertThat(response.results()).containsExactly(
				new RecipientResult(A, "ACCEPTED", "SM-" + A, "queued", null, null, null),
				new RecipientResult(B, "ACCEPTED", "SM-" + B, "queued", null, null, null));
	}

	@Test
	void usesThePrimaryProviderWhenNoneIsGiven() {
		BatchMessageService service = serviceWith(message -> new ProviderResult("id", "ok"));

		assertThat(service.send(MessageType.SMS, null, List.of(A), "Hello").provider()).isEqualTo("FAKE");
	}

	@Test
	void oneFailedNumberDoesNotStopTheBatch() {
		BatchMessageService service = serviceWith(message -> {
			if (message.to().equals(B)) {
				throw new MessagingException(ErrorCode.PROVIDER_REJECTED, "FAKE", "21608", "unverified");
			}
			return new ProviderResult("id", "queued");
		});

		BatchSendResponse response = service.send(MessageType.SMS, null, List.of(A, B, C), "Hello");

		assertThat(sent).containsExactly(A, B, C);
		assertThat(response.accepted()).isEqualTo(2);
		assertThat(response.failed()).isEqualTo(1);
		assertThat(response.results().get(1))
			.isEqualTo(new RecipientResult(B, "FAILED", null, null, "PROVIDER_REJECTED", "21608", "unverified"));
	}

	@Test
	void unexpectedErrorIsRecordedAndTheBatchContinues() {
		BatchMessageService service = serviceWith(message -> {
			if (message.to().equals(A)) {
				throw new IllegalStateException("secret internals");
			}
			return new ProviderResult("id", "queued");
		});

		BatchSendResponse response = service.send(MessageType.SMS, null, List.of(A, B), "Hello");

		assertThat(response.results().get(0)).isEqualTo(
				new RecipientResult(A, "FAILED", null, null, "INTERNAL_ERROR", null, "An unexpected error occurred."));
		assertThat(response.results().get(1).status()).isEqualTo("ACCEPTED");
	}

	@Test
	void failedNumbersAreNotRetried() {
		BatchMessageService service = serviceWith(message -> {
			throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, "FAKE", "down");
		});

		service.send(MessageType.SMS, null, List.of(A), "Hello");

		assertThat(sent).containsExactly(A);
	}

	@Test
	void duplicateNumbersAreSentOnce() {
		BatchMessageService service = serviceWith(message -> new ProviderResult("id", "queued"));

		BatchSendResponse response = service.send(MessageType.SMS, null, List.of(A, B, A, A), "Hello");

		assertThat(sent).containsExactly(A, B);
		assertThat(response.total()).isEqualTo(2);
		assertThat(response.duplicatesRemoved()).isEqualTo(2);
	}

	@Test
	void numberNotInE164FailsWithoutCallingTheProvider() {
		BatchMessageService service = serviceWith(message -> new ProviderResult("id", "queued"));

		BatchSendResponse response = service.send(MessageType.SMS, null, List.of("12345", A), "Hello");

		assertThat(sent).containsExactly(A);
		assertThat(response.results().get(0).code()).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void moreThanTheLimitIsRejectedBeforeAnySend() {
		BatchMessageService service = serviceWith(message -> new ProviderResult("id", "queued"), 2);

		assertThatThrownBy(() -> service.send(MessageType.SMS, null, List.of(A, B, C), "Hello"))
			.isInstanceOfSatisfying(MessagingException.class, e -> {
				assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
				assertThat(e.getMessage()).isEqualTo("recipients must contain at most 2 numbers, got 3.");
			});
		assertThat(sent).isEmpty();
	}

	@Test
	void emptyListIsRejected() {
		BatchMessageService service = serviceWith(message -> new ProviderResult("id", "queued"));

		assertThatThrownBy(() -> service.send(MessageType.SMS, null, List.of(), "Hello"))
			.isInstanceOfSatisfying(MessagingException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	void unknownProviderIsRejectedBeforeAnySend() {
		BatchMessageService service = serviceWith(message -> new ProviderResult("id", "queued"));

		assertThatThrownBy(() -> service.send(MessageType.SMS, "SINCH", List.of(A), "Hello"))
			.isInstanceOfSatisfying(MessagingException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_PROVIDER));
		assertThat(sent).isEmpty();
	}

	private BatchMessageService serviceWith(Function<OutboundMessage, ProviderResult> send) {
		return serviceWith(send, 100);
	}

	private BatchMessageService serviceWith(Function<OutboundMessage, ProviderResult> send, int maxRecipients) {
		MessageProvider provider = new MessageProvider() {

			@Override
			public String id() {
				return "FAKE";
			}

			@Override
			public Set<MessageType> supportedTypes() {
				return EnumSet.of(MessageType.SMS);
			}

			@Override
			public ProviderResult send(OutboundMessage message) {
				sent.add(message.to());
				return send.apply(message);
			}

		};
		GenericHttpProvidersProperties properties = new GenericHttpProvidersProperties(
				Map.of("sms", new ChannelSettings("FAKE", List.of("FAKE"))), Map.of());
		ProviderRegistry registry = new ProviderRegistry(List.of(provider), properties, RestClient.builder());
		return new BatchMessageService(registry, new MessageService(registry), maxRecipients);
	}

}
