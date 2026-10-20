package com.example.messaging_service.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.dto.BatchSendResponse.RecipientResult;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.service.BatchMessageService;

@WebMvcTest(BatchMessageController.class)
class BatchMessageControllerTest {

	private static final String VALID = """
			{ "type": "SMS", "provider": "TWILIO",
			  "recipients": ["+910000000001", "+910000000002"], "body": "Your bill is ready" }
			""";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private BatchMessageService service;

	@Test
	void batchReturns200WithCountsAndOneResultPerNumber() throws Exception {
		given(service.send(eq(MessageType.SMS), eq("TWILIO"), eq(List.of("+910000000001", "+910000000002")),
				eq("Your bill is ready")))
			.willReturn(BatchSendResponse.of("TWILIO", 1,
					List.of(new RecipientResult("+910000000001", "ACCEPTED", "SM1", "queued", null, null, null),
							new RecipientResult("+910000000002", "FAILED", null, null, "PROVIDER_REJECTED", "21608",
									"unverified"))));

		send(VALID).andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value("TWILIO"))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.accepted").value(1))
			.andExpect(jsonPath("$.failed").value(1))
			.andExpect(jsonPath("$.duplicatesRemoved").value(1))
			.andExpect(jsonPath("$.results[0].to").value("+910000000001"))
			.andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
			.andExpect(jsonPath("$.results[0].providerMessageId").value("SM1"))
			.andExpect(jsonPath("$.results[0].code").doesNotExist())
			.andExpect(jsonPath("$.results[1].to").value("+910000000002"))
			.andExpect(jsonPath("$.results[1].status").value("FAILED"))
			.andExpect(jsonPath("$.results[1].code").value("PROVIDER_REJECTED"))
			.andExpect(jsonPath("$.results[1].providerErrorCode").value("21608"))
			.andExpect(jsonPath("$.results[1].providerMessageId").doesNotExist());
	}

	@Test
	void providerIsOptional() throws Exception {
		given(service.send(eq(MessageType.SMS), isNull(), anyList(), any()))
			.willReturn(BatchSendResponse.of("TWILIO", 0, List.of()));

		send("""
				{ "type": "SMS", "recipients": ["+910000000001"], "body": "Hi" }
				""").andExpect(status().isOk());
	}

	@Test
	void missingFieldsAreListed() throws Exception {
		validationFailed(send("{}"))
			.andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("type", "recipients", "body")));
		verifyNoInteractions(service);
	}

	@Test
	void emptyRecipientsAreRejected() throws Exception {
		validationFailed(send("""
				{ "type": "SMS", "recipients": [], "body": "Hi" }
				""")).andExpect(jsonPath("$.errors[0].field").value("recipients"));
	}

	@Test
	void everyNumberMustBeE164() throws Exception {
		validationFailed(send("""
				{ "type": "SMS", "recipients": ["+910000000001", "9876543210"], "body": "Hi" }
				""")).andExpect(jsonPath("$.errors[0].field").value("recipients[1]"))
			.andExpect(jsonPath("$.errors[0].message").value("must be in E.164 format, for example +919876543210"));
		verifyNoInteractions(service);
	}

	@Test
	void tooManyNumbersBecomesValidationProblem() throws Exception {
		given(service.send(any(), any(), anyList(), any())).willThrow(new MessagingException(
				ErrorCode.VALIDATION_FAILED, null, "recipients must contain at most 100 numbers, got 101."));

		validationFailed(send(VALID))
			.andExpect(jsonPath("$.detail").value("recipients must contain at most 100 numbers, got 101."));
	}

	@Test
	void unknownProviderIs400() throws Exception {
		given(service.send(any(), any(), anyList(), any())).willThrow(
				new MessagingException(ErrorCode.UNSUPPORTED_PROVIDER, null, "Provider 'SINCH' is not supported."));

		send(VALID).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_PROVIDER"));
	}

	private ResultActions send(String json) throws Exception {
		return mvc.perform(post("/api/v1/messages/batch").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static ResultActions validationFailed(ResultActions result) throws Exception {
		return result.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.instance").value("/api/v1/messages/batch"));
	}

}
