package com.example.messaging_service.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into one RFC 9457 ProblemDetail with a stable {@code code} field. The
 * base class keeps Spring's own handling for 404, 405, 415 and similar.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MessagingException.class)
	public ProblemDetail handleMessaging(MessagingException e) {
		ProblemDetail problem = problem(e.code(), e.getMessage());
		if (e.provider() != null) {
			problem.setProperty("provider", e.provider());
		}
		if (e.providerErrorCode() != null) {
			problem.setProperty("providerErrorCode", e.providerErrorCode());
		}
		return problem;
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception e) {
		log.error("Unexpected error", e);
		return problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.toList();
		ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "The request has invalid fields.");
		problem.setProperty("errors", errors);
		return ResponseEntity.status(problem.getStatus()).body(problem);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		// MessageType.from throws IllegalArgumentException for an unknown type; its message is safe to show.
		String detail = (e.getMostSpecificCause() instanceof IllegalArgumentException cause) ? cause.getMessage()
				: "The request body is not valid JSON.";
		ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, detail);
		return ResponseEntity.status(problem.getStatus()).body(problem);
	}

	private static ProblemDetail problem(ErrorCode code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setTitle(code.title());
		problem.setProperty("code", code.name());
		return problem;
	}

}
