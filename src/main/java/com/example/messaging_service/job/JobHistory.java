package com.example.messaging_service.job;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;

import com.example.messaging_service.dto.BatchSendResponse.RecipientResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What one run of the daily job did, as kept in jobHistory.json:
 * {@code { "runs": [ { ...run... } ] }}, oldest first. Fields that do not apply are left out.
 */
public final class JobHistory {

	private JobHistory() {
	}

	/**
	 * @param status COMPLETED (every number accepted), FAILED (none accepted, for example the targets
	 * file could not be read) or PARTIAL (anything in between)
	 * @param error why the run stopped early; absent when every group was tried
	 */
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Run(String runId, OffsetDateTime startedAt, OffsetDateTime finishedAt, String status,
			String targetsVersion, int total, int accepted, int failed, Failure error, List<Group> groups) {

		static Run of(OffsetDateTime startedAt, OffsetDateTime finishedAt, String targetsVersion, Failure error,
				List<Group> groups) {
			int total = sum(groups, Group::total);
			int accepted = sum(groups, Group::accepted);
			boolean everyGroupSent = error == null && groups.stream().allMatch(group -> group.error() == null);
			String status = (everyGroupSent && accepted == total) ? "COMPLETED" : (accepted == 0) ? "FAILED" : "PARTIAL";
			return new Run(UUID.randomUUID().toString(), startedAt, finishedAt, status, targetsVersion, total, accepted,
					total - accepted, error, List.copyOf(groups));
		}

		private static int sum(List<Group> groups, ToIntFunction<Group> count) {
			return groups.stream().mapToInt(count).sum();
		}

	}

	/**
	 * One provider group of batch-targets.json.
	 *
	 * @param status SENT, or SKIPPED when the provider refused the group (unknown or disabled); numbers
	 * sent before that still count
	 */
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Group(String provider, String status, int total, int accepted, int failed, int duplicatesRemoved,
			Failure error, List<Result> results) {

		static Group of(String provider, int duplicatesRemoved, List<RecipientResult> results, Failure error) {
			int accepted = (int) results.stream().filter(result -> "ACCEPTED".equals(result.status())).count();
			return new Group(provider, (error == null) ? "SENT" : "SKIPPED", results.size(), accepted,
					results.size() - accepted, duplicatesRemoved, error,
					results.isEmpty() ? null : results.stream().map(Result::of).toList());
		}

	}

	/** The outcome for one number, the same fields as the batch API returns. */
	@JsonNaming(KebabCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Result(String to, String status, String providerMessageId, String providerStatus, String code,
			String providerErrorCode, String detail) {

		static Result of(RecipientResult result) {
			return new Result(result.to(), result.status(), result.providerMessageId(), result.providerStatus(),
					result.code(), result.providerErrorCode(), result.detail());
		}

	}

	/** @param code the error code when there is one, for example UNSUPPORTED_PROVIDER */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Failure(String code, String detail) {
	}

}
