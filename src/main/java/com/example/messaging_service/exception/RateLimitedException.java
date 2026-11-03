package com.example.messaging_service.exception;

/** A caller asked for more SMS than the rate limit allows right now. Returned as 429. */
public class RateLimitedException extends MessagingException {

	/** Seconds until the request would fit, or null when waiting does not help. */
	private final Long retryAfterSeconds;

	public RateLimitedException(String detail, Long retryAfterSeconds) {
		super(ErrorCode.RATE_LIMITED, null, detail);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public Long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
