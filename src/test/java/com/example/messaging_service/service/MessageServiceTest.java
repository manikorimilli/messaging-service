package com.example.messaging_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.dto.SendMessageRequest;
import com.example.messaging_service.dto.SendMessageResponse;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties;
import com.example.messaging_service.provider.generic.GenericHttpProvidersProperties.ChannelSettings;

class MessageServiceTest {

	private static final SendMessageRequest REQUEST = new SendMessageRequest(MessageType.SMS, null, "+919876543210",
			"Hello");

	@Test
	void returnsAcceptedResponseWithProviderResult() {
		MessageService service = serviceWith(message -> new ProviderResult("SM123", "queued"));

		SendMessageResponse response = service.send(REQUEST);

		assertThat(response.status()).isEqualTo("ACCEPTED");
		assertThat(response.provider()).isEqualTo("FAKE");
		assertThat(response.providerMessageId()).isEqualTo("SM123");
		assertThat(response.providerStatus()).isEqualTo("queued");
		assertThat(response.acceptedAt()).isNotNull();
	}

	@Test
	void passesRequestFieldsToProvider() {
		MessageService service = serviceWith(message -> {
			assertThat(message).isEqualTo(new OutboundMessage(MessageType.SMS, "+919876543210", "Hello"));
			return new ProviderResult("id", "ok");
		});

		service.send(REQUEST);
	}

	@Test
	void providerRejectionPassesThroughUnchanged() {
		MessagingException rejected = new MessagingException(ErrorCode.PROVIDER_REJECTED, "FAKE", "21211", "bad number");
		MessageService service = serviceWith(message -> {
			throw rejected;
		});

		assertThatThrownBy(() -> service.send(REQUEST)).isSameAs(rejected);
	}

	@Test
	void refusedConnectionIsProviderUnavailable() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		MessageService service = serviceWith(realHttpCall(closedPort));

		assertThatThrownBy(() -> service.send(REQUEST)).isInstanceOfSatisfying(MessagingException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
	}

	@Test
	void serverThatNeverAnswersIsProviderTimeout() throws Exception {
		// The OS completes the TCP handshake from the backlog, but nothing ever reads or replies.
		try (ServerSocket silent = new ServerSocket(0)) {
			MessageService service = serviceWith(realHttpCall(silent.getLocalPort()));

			assertThatThrownBy(() -> service.send(REQUEST)).isInstanceOfSatisfying(MessagingException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_TIMEOUT));
		}
	}

	@Test
	void masksPhoneNumber() {
		assertThat(MessageService.mask("+919876543210")).isEqualTo("+91******3210");
		assertThat(MessageService.mask(null)).isEqualTo("***");
	}

	/** A provider body that makes a real HTTP call with the JDK client, like the adapters do. */
	private static Function<OutboundMessage, ProviderResult> realHttpCall(int port) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
		factory.setReadTimeout(Duration.ofMillis(300));
		RestClient client = RestClient.builder().requestFactory(factory).baseUrl("http://localhost:" + port).build();
		return message -> {
			client.post().uri("/send").body("x").retrieve().toBodilessEntity();
			return new ProviderResult("id", "ok");
		};
	}

	private static MessageService serviceWith(Function<OutboundMessage, ProviderResult> send) {
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
				return send.apply(message);
			}

		};
		GenericHttpProvidersProperties properties = new GenericHttpProvidersProperties(
				Map.of("sms", new ChannelSettings("FAKE", List.of("FAKE"))), Map.of());
		return new MessageService(new ProviderRegistry(List.of(provider), properties, RestClient.builder()));
	}

}
