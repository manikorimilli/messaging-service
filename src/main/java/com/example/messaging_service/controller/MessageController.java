package com.example.messaging_service.controller;

import org.springframework.http.ResponseEntity;
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

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

	private final MessageService messageService;

	private final BatchMessageService batchMessageService;

	public MessageController(MessageService messageService, BatchMessageService batchMessageService) {
		this.messageService = messageService;
		this.batchMessageService = batchMessageService;
	}

	/** 202 Accepted: the provider took the message; delivery is not confirmed. */
	@PostMapping
	public ResponseEntity<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
		return ResponseEntity.accepted().body(messageService.send(request));
	}

	/** 200 OK: the batch ran. Each number's outcome is in the results; some may have failed. */
	@PostMapping("/batch")
	public BatchSendResponse sendBatch(@Valid @RequestBody BatchSendRequest request) {
		return batchMessageService.send(request.type(), request.provider(), request.recipients(), request.body());
	}

}
