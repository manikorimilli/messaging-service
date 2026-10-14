package com.example.messaging_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.dto.SendMessageRequest;
import com.example.messaging_service.dto.SendMessageResponse;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.service.MessageService;

@WebMvcTest(MessageController.class)
class MessageControllerTest {

	private static final String VALID = """
			{ "type": "SMS", "provider": "TWILIO", "to": "+919876543210", "body": "Welcome!" }
			""";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private MessageService service;

	@Test
	void acceptedMessageReturns202WithBody() throws Exception {
		given(service.send(any())).willReturn(new SendMessageResponse("ACCEPTED", "TWILIO", "SM123", "queued",
				Instant.parse("2026-10-14T10:15:30Z")));

		send(VALID).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.provider").value("TWILIO"))
			.andExpect(jsonPath("$.providerMessageId").value("SM123"))
			.andExpect(jsonPath("$.providerStatus").value("queued"))
			.andExpect(jsonPath("$.acceptedAt").value("2026-10-14T10:15:30Z"));
	}

	@Test
	void lowercaseTypeIsAccepted() throws Exception {
		given(service.send(any())).willReturn(new SendMessageResponse("ACCEPTED", "TWILIO", "SM1", "queued", null));

		send("""
				{ "type": "sms", "to": "+919876543210", "body": "Hi" }
				""").andExpect(status().isAccepted());

		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(service).send(captor.capture());
		assertThat(captor.getValue().type()).isEqualTo(MessageType.SMS);
		assertThat(captor.getValue().provider()).isNull();
	}

	@Test
	void missingFieldsAreListed() throws Exception {
		validationFailed(send("{}")).andExpect(jsonPath("$.detail").value("The request has invalid fields."))
			.andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("type", "to", "body")));
	}

	@Test
	void phoneNumberMustBeE164() throws Exception {
		validationFailed(send("""
				{ "type": "SMS", "to": "9876543210", "body": "Hi" }
				""")).andExpect(jsonPath("$.errors[0].field").value("to"))
			.andExpect(jsonPath("$.errors[0].message").value("must be in E.164 format, for example +919876543210"));
	}

	@Test
	void bodyLongerThan1600IsRejected() throws Exception {
		validationFailed(send("""
				{ "type": "SMS", "to": "+919876543210", "body": "%s" }
				""".formatted("a".repeat(1601)))).andExpect(jsonPath("$.errors[0].field").value("body"));
	}

	@Test
	void unknownTypeIsValidationFailure() throws Exception {
		validationFailed(send("""
				{ "type": "EMAIL", "to": "+919876543210", "body": "Hi" }
				""")).andExpect(jsonPath("$.detail").value("type must be one of [SMS]"));
	}

	@Test
	void malformedJsonIsValidationFailure() throws Exception {
		validationFailed(send("{ not json")).andExpect(jsonPath("$.detail").value("The request body is not valid JSON."));
	}

	@ParameterizedTest
	@CsvSource({
			"UNSUPPORTED_PROVIDER, 400, Unsupported provider",
			"UNSUPPORTED_MESSAGE_TYPE, 400, Unsupported message type",
			"PROVIDER_UNAVAILABLE, 503, Provider unavailable",
			"PROVIDER_TIMEOUT, 504, Provider timeout",
			"PROVIDER_REJECTED, 502, Provider rejected the message" })
	void messagingExceptionBecomesProblemDetail(ErrorCode code, int status, String title) throws Exception {
		given(service.send(any())).willThrow(new MessagingException(code, "TWILIO", "21211", "some detail"));

		send(VALID).andExpect(status().is(status))
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.detail").value("some detail"))
			.andExpect(jsonPath("$.instance").value("/api/v1/messages"))
			.andExpect(jsonPath("$.code").value(code.name()))
			.andExpect(jsonPath("$.provider").value("TWILIO"))
			.andExpect(jsonPath("$.providerErrorCode").value("21211"));
	}

	@Test
	void absentProviderFieldsAreLeftOut() throws Exception {
		given(service.send(any()))
			.willThrow(new MessagingException(ErrorCode.UNSUPPORTED_PROVIDER, null, "Provider 'SINCH' is not supported."));

		send(VALID).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.provider").doesNotExist())
			.andExpect(jsonPath("$.providerErrorCode").doesNotExist());
	}

	@Test
	void unexpectedErrorIs500WithoutDetails() throws Exception {
		given(service.send(any())).willThrow(new IllegalStateException("secret internals"));

		send(VALID).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
	}

	@Test
	void springErrorsStayStandard() throws Exception {
		mvc.perform(get("/api/v1/messages"))
			.andExpect(status().isMethodNotAllowed());
	}

	private ResultActions send(String json) throws Exception {
		return mvc.perform(post("/api/v1/messages").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static ResultActions validationFailed(ResultActions result) throws Exception {
		return result.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.title").value("Validation failed"))
			.andExpect(jsonPath("$.instance").value("/api/v1/messages"));
	}

}
