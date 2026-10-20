package com.example.messaging_service.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.MessagingException;

/**
 * Outgoing JSON for a batch: counts plus one result per number. A batch can be partly
 * successful, so the outcome of each number is in {@code results}.
 *
 * @param total numbers tried, after duplicates were removed
 */
public record BatchSendResponse(String provider, int total, int accepted, int failed, int duplicatesRemoved,
		List<RecipientResult> results) {

	public static BatchSendResponse of(String provider, int duplicatesRemoved, List<RecipientResult> results) {
		int accepted = (int) results.stream().filter(RecipientResult::isAccepted).count();
		return new BatchSendResponse(provider, results.size(), accepted, results.size() - accepted, duplicatesRemoved,
				List.copyOf(results));
	}

	/**
	 * The outcome for one number. The number is returned in full: the caller sent it. Fields that
	 * do not apply are left out of the JSON.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RecipientResult(String to, String status, String providerMessageId, String providerStatus,
			String code, String providerErrorCode, String detail) {

		public static RecipientResult accepted(String to, SendMessageResponse response) {
			return new RecipientResult(to, "ACCEPTED", response.providerMessageId(), response.providerStatus(), null,
					null, null);
		}

		public static RecipientResult failed(String to, MessagingException e) {
			return new RecipientResult(to, "FAILED", null, null, e.code().name(), e.providerErrorCode(),
					e.getMessage());
		}

		public static RecipientResult failed(String to, ErrorCode code, String detail) {
			return new RecipientResult(to, "FAILED", null, null, code.name(), null, detail);
		}

		boolean isAccepted() {
			return "ACCEPTED".equals(status);
		}

	}

}
