package com.example.messaging_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.messaging_service.dto.SendMessageRequest;
import com.example.messaging_service.dto.SendMessageResponse;
import com.example.messaging_service.service.MessageService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

	private final MessageService messageService;

	public MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	/** 202 Accepted: the provider took the message; delivery is not confirmed. */
	@PostMapping
	public ResponseEntity<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
		return ResponseEntity.accepted().body(messageService.send(request));
	}

}
