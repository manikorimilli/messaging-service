package com.example.messaging_service.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.messaging_service.dto.BatchSendRequest;
import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.service.BatchMessageService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/messages")
public class BatchMessageController {

	private final BatchMessageService batchMessageService;

	public BatchMessageController(BatchMessageService batchMessageService) {
		this.batchMessageService = batchMessageService;
	}

	/** 200 OK: the batch ran. Each number's outcome is in the results; some may have failed. */
	@PostMapping("/batch")
	public BatchSendResponse send(@Valid @RequestBody BatchSendRequest request) {
		return batchMessageService.send(request.type(), request.provider(), request.recipients(), request.body());
	}

}
