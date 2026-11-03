package com.example.messaging_service.controller;

import java.util.HashSet;

import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.messaging_service.dto.BatchSendRequest;
import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.dto.SendMessageRequest;
import com.example.messaging_service.dto.SendMessageResponse;
import com.example.messaging_service.service.BatchMessageService;
import com.example.messaging_service.service.MessageService;
import com.example.messaging_service.service.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

	private final MessageService messageService;

	private final BatchMessageService batchMessageService;

	private final RateLimiter rateLimiter;

	public MessageController(MessageService messageService, BatchMessageService batchMessageService,
			RateLimiter rateLimiter) {
		this.messageService = messageService;
		this.batchMessageService = batchMessageService;
		this.rateLimiter = rateLimiter;
	}

	/** 202 Accepted: the provider took the message; delivery is not confirmed. 429 over the rate limit. */
	@PostMapping
	public ResponseEntity<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request,
			HttpServletRequest http) {
		rateLimiter.acquire(caller(http), 1);
		return ResponseEntity.accepted().body(messageService.send(request));
	}

	/**
	 * 200 OK: the batch ran. Each number's outcome is in the results; some may have failed. 429 over
	 * the rate limit, where every unique number counts as one SMS and nothing is sent.
	 */
	@PostMapping("/batch")
	public BatchSendResponse sendBatch(@Valid @RequestBody BatchSendRequest request, HttpServletRequest http) {
		// An over-long list is the batch service's 400, not a 429.
		if (request.recipients().size() <= batchMessageService.maxRecipients()) {
			rateLimiter.acquire(caller(http), new HashSet<>(request.recipients()).size());
		}
		return batchMessageService.send(request.type(), request.provider(), request.recipients(), request.body());
	}

	/**
	 * The caller's IP address. Behind Azure's front end the client is the last entry of
	 * X-Forwarded-For, which Azure adds itself (an entry a client sends is further left), as
	 * "ip:port".
	 */
	static String caller(HttpServletRequest http) {
		String forwarded = http.getHeader("X-Forwarded-For");
		if (!StringUtils.hasText(forwarded)) {
			return http.getRemoteAddr();
		}
		String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
		if (last.startsWith("[")) {
			return last.substring(1, Math.max(1, last.indexOf(']'))); // [IPv6]:port
		}
		int colon = last.indexOf(':');
		boolean ipv4WithPort = colon > 0 && colon == last.lastIndexOf(':');
		return ipv4WithPort ? last.substring(0, colon) : last;
	}

}
