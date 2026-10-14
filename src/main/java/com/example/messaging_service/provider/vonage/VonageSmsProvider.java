package com.example.messaging_service.provider.vonage;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sends SMS through the Vonage SMS API. Vonage answers HTTP 200 even when it refuses a message;
 * the real outcome is {@code messages[].status}, where "0" means accepted.
 *
 * @see <a href="https://developer.vonage.com/en/api/sms">SMS API</a>
 */
@Component
class VonageSmsProvider implements MessageProvider {

	static final String BASE_URL = "https://rest.nexmo.com";

	private static final String ID = "VONAGE";

	// GSM 03.38 basic and extension characters. Anything else must be sent with type=unicode.
	private static final String GSM_7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
			+ "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà" + "\f^{}\\[~]|€";

	// Vonage statuses that mean "try again later" rather than "this message is refused".
	private static final Set<String> UNAVAILABLE_STATUSES = Set.of("1", "5"); // throttled, internal error

	private final RestClient client;

	private final VonageProperties props;

	VonageSmsProvider(RestClient.Builder builder, VonageProperties props) {
		this.props = props;
		this.client = builder.baseUrl(BASE_URL).build();
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public Set<MessageType> supportedTypes() {
		return EnumSet.of(MessageType.SMS);
	}

	@Override
	public boolean isConfigured() {
		return StringUtils.hasText(props.apiKey()) && StringUtils.hasText(props.apiSecret())
				&& StringUtils.hasText(props.smsFrom());
	}

	@Override
	public ProviderResult send(OutboundMessage message) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("from", props.smsFrom());
		form.add("to", message.to().substring(1)); // Vonage wants E.164 without the "+"
		form.add("text", message.body());
		if (needsUnicode(message.body())) {
			form.add("type", "unicode");
		}

		return client.post()
			.uri("/sms/json")
			.headers(headers -> headers.setBasicAuth(props.apiKey(), props.apiSecret()))
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.exchange((request, response) -> {
				int status = response.getStatusCode().value();
				if (status == HttpStatus.TOO_MANY_REQUESTS.value() || response.getStatusCode().is5xxServerError()) {
					throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, ID,
							"Vonage is unavailable (HTTP %d).".formatted(status));
				}
				if (!response.getStatusCode().is2xxSuccessful()) {
					throw new MessagingException(ErrorCode.PROVIDER_REJECTED, ID,
							"Vonage rejected the message: HTTP " + status);
				}
				VonageResponse body = response.bodyTo(VonageResponse.class);
				if (body == null || body.messages() == null || body.messages().isEmpty()) {
					throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, ID, "Vonage returned an empty reply.");
				}
				// A long text is split into parts, one entry each; all must be accepted.
				for (VonageMessage part : body.messages()) {
					if (!"0".equals(part.status())) {
						throw failure(part);
					}
				}
				VonageMessage first = body.messages().get(0);
				return new ProviderResult(first.messageId(), first.status());
			});
	}

	private static MessagingException failure(VonageMessage part) {
		if (UNAVAILABLE_STATUSES.contains(part.status())) {
			return new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, ID, part.status(),
					"Vonage is unavailable: " + part.errorText());
		}
		return new MessagingException(ErrorCode.PROVIDER_REJECTED, ID, part.status(),
				"Vonage rejected the message: " + part.errorText());
	}

	static boolean needsUnicode(String text) {
		return text.codePoints().anyMatch(c -> GSM_7.indexOf(c) < 0);
	}

	/** e.g. {"message-count": "1", "messages": [{"status": "0", "message-id": "0A00..."}]} */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record VonageResponse(List<VonageMessage> messages) {
	}

	/** On failure: {"status": "29", "error-text": "Non White-listed Destination - rejected"}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record VonageMessage(String status, @JsonProperty("message-id") String messageId,
			@JsonProperty("error-text") String errorText) {
	}

}
