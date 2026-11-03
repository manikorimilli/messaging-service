package com.example.messaging_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.dto.SendMessageResponse;
import com.example.messaging_service.service.BatchMessageService;
import com.example.messaging_service.service.MessageService;
import com.example.messaging_service.service.RateLimiter;

/** The real rate limiter in front of mocked services, with a limit of 2 SMS per minute. */
@WebMvcTest(controllers = MessageController.class, properties = "messaging.rate-limit.sms-per-minute=2")
@Import(RateLimiter.class)
class MessageRateLimitTest {

	private static final String ONE = """
			{ "type": "SMS", "to": "+919876543210", "body": "Hi" }
			""";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private MessageService service;

	@MockitoBean
	private BatchMessageService batchService;

	@Test
	void overTheLimitIs429WithRetryAfterAndNothingIsSent() throws Exception {
		given(service.send(any())).willReturn(new SendMessageResponse("ACCEPTED", "TWILIO", "SM1", "queued", null));

		send("/api/v1/messages", ONE, "10.0.0.1").andExpect(status().isAccepted());
		send("/api/v1/messages", ONE, "10.0.0.1").andExpect(status().isAccepted());
		send("/api/v1/messages", ONE, "10.0.0.1").andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "30"))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(jsonPath("$.title").value("Too many requests"))
			.andExpect(jsonPath("$.detail").value("Rate limit is 2 SMS per minute. Try again in 30 seconds."));

		verify(service, times(2)).send(any());
	}

	@Test
	void otherCallersAreNotAffected() throws Exception {
		given(service.send(any())).willReturn(new SendMessageResponse("ACCEPTED", "TWILIO", "SM1", "queued", null));
		send("/api/v1/messages", ONE, "10.0.0.2").andExpect(status().isAccepted());
		send("/api/v1/messages", ONE, "10.0.0.2").andExpect(status().isAccepted());

		send("/api/v1/messages", ONE, "10.0.0.3").andExpect(status().isAccepted());
	}

	@Test
	void batchCountsEveryUniqueNumber() throws Exception {
		given(batchService.maxRecipients()).willReturn(100);
		given(batchService.send(any(), any(), anyList(), any()))
			.willReturn(new BatchSendResponse("TWILIO", 2, 2, 0, 1, List.of()));
		String twoNumbers = """
				{ "type": "SMS", "recipients": ["+910000000001", "+910000000002", "+910000000001"], "body": "Hi" }
				""";

		send("/api/v1/messages/batch", twoNumbers, "10.0.0.4").andExpect(status().isOk());
		send("/api/v1/messages", ONE, "10.0.0.4").andExpect(status().isTooManyRequests());
	}

	@Test
	void batchOverTheLimitSendsNothing() throws Exception {
		given(batchService.maxRecipients()).willReturn(100);

		send("/api/v1/messages/batch", """
				{ "type": "SMS", "recipients": ["+910000000001", "+910000000002", "+910000000003"], "body": "Hi" }
				""", "10.0.0.5").andExpect(status().isTooManyRequests())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.detail").value("This request has 3 SMS; the limit is 2 SMS per minute."));

		verify(batchService).maxRecipients();
		verify(batchService, times(0)).send(any(), any(), anyList(), any());
		verifyNoInteractions(service);
	}

	@Test
	void callerIsTheLastForwardedAddressWithoutItsPort() {
		assertThat(MessageController.caller(request(null))).isEqualTo("127.0.0.1");
		assertThat(MessageController.caller(request("203.0.113.7:51234"))).isEqualTo("203.0.113.7");
		assertThat(MessageController.caller(request("1.2.3.4, 203.0.113.7:51234"))).isEqualTo("203.0.113.7");
		assertThat(MessageController.caller(request("[2001:db8::1]:51234"))).isEqualTo("2001:db8::1");
		assertThat(MessageController.caller(request("2001:db8::1"))).isEqualTo("2001:db8::1");
	}

	private ResultActions send(String path, String json, String forwardedFor) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
			.header("X-Forwarded-For", forwardedFor)
			.content(json));
	}

	private static MockHttpServletRequest request(String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("127.0.0.1");
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		return request;
	}

}
