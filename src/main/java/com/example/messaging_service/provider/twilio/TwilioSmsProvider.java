package com.example.messaging_service.provider.twilio;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.provider.MessageProvider;
import com.example.messaging_service.provider.OutboundMessage;
import com.example.messaging_service.provider.ProviderResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Sends SMS through Twilio's Messages API.
 *
 * @see <a href="https://www.twilio.com/docs/messaging/api/message-resource">Message resource</a>
 */
@Component
class TwilioSmsProvider implements MessageProvider {

	static final String BASE_URL = "https://api.twilio.com/2010-04-01";

	private static final String ID = "TWILIO";

	private final RestClient client;

	private final TwilioProperties props;

	TwilioSmsProvider(RestClient.Builder builder, TwilioProperties props) {
		this.props = props;
		// Spring Boot gives each bean its own builder, already set up with the shared timeouts.
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
		return StringUtils.hasText(props.accountSid()) && StringUtils.hasText(props.apiKey())
				&& StringUtils.hasText(props.apiSecret()) && StringUtils.hasText(props.smsFrom());
	}

	@Override
	public ProviderResult send(OutboundMessage message) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("To", message.to());
		form.add("From", props.smsFrom());
		form.add("Body", message.body());

		return client.post()
			.uri("/Accounts/{accountSid}/Messages.json", props.accountSid())
			.headers(headers -> headers.setBasicAuth(props.apiKey(), props.apiSecret()))
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.exchange((request, response) -> {
				int status = response.getStatusCode().value();
				if (response.getStatusCode().is2xxSuccessful()) {
					TwilioMessage created = response.bodyTo(TwilioMessage.class);
					return new ProviderResult(created.sid(), created.status());
				}
				// 429 (throttled) and 5xx are on Twilio's side: the message was not accepted, try later.
				if (status == HttpStatus.TOO_MANY_REQUESTS.value() || response.getStatusCode().is5xxServerError()) {
					throw new MessagingException(ErrorCode.PROVIDER_UNAVAILABLE, ID,
							"Twilio is unavailable (HTTP %d).".formatted(status));
				}
				TwilioError error = readError(response);
				String code = (error.code() == null) ? null : error.code().toString();
				String reason = (error.message() == null) ? "HTTP " + status : error.message();
				throw new MessagingException(ErrorCode.PROVIDER_REJECTED, ID, code,
						"Twilio rejected the message: " + reason);
			});
	}

	private static TwilioError readError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
		try {
			TwilioError error = response.bodyTo(TwilioError.class);
			return (error != null) ? error : new TwilioError(null, null);
		}
		catch (RestClientException e) {
			return new TwilioError(null, null);
		}
	}

	/** The parts of Twilio's 201 reply we use, e.g. {"sid": "SM...", "status": "queued"}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record TwilioMessage(String sid, String status) {
	}

	/** Twilio's error body, e.g. {"code": 21211, "message": "The 'To' number ... is not a valid phone number."}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record TwilioError(Integer code, String message) {
	}

}
