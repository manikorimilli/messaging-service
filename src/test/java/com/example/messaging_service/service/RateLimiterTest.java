package com.example.messaging_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.InstantSource;

import org.junit.jupiter.api.Test;

import com.example.messaging_service.exception.ErrorCode;
import com.example.messaging_service.exception.RateLimitedException;

class RateLimiterTest {

	private Instant now = Instant.parse("2026-11-03T10:00:00Z");

	private final InstantSource clock = () -> now;

	@Test
	void allowsUpToTheLimitThenAsksToWait() {
		RateLimiter limiter = new RateLimiter(true, 60, clock);

		limiter.acquire("a", 60);

		assertThatThrownBy(() -> limiter.acquire("a", 1)).isInstanceOfSatisfying(RateLimitedException.class, e -> {
			assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
			assertThat(e.retryAfterSeconds()).isEqualTo(1);
			assertThat(e.getMessage()).isEqualTo("Rate limit is 60 SMS per minute. Try again in 1 seconds.");
		});
	}

	@Test
	void allowanceRefillsEvenlyOverTheMinute() {
		RateLimiter limiter = new RateLimiter(true, 60, clock);
		limiter.acquire("a", 60);

		now = now.plusSeconds(10); // 60 per minute: 10 more after 10 seconds

		limiter.acquire("a", 10);
		assertThatThrownBy(() -> limiter.acquire("a", 1)).isInstanceOf(RateLimitedException.class);
	}

	@Test
	void rejectedRequestTakesNothing() {
		RateLimiter limiter = new RateLimiter(true, 100, clock);
		limiter.acquire("a", 50);

		assertThatThrownBy(() -> limiter.acquire("a", 60)).isInstanceOfSatisfying(RateLimitedException.class,
				e -> assertThat(e.retryAfterSeconds()).isEqualTo(6));
		assertThatCode(() -> limiter.acquire("a", 50)).doesNotThrowAnyException();
	}

	@Test
	void callersHaveTheirOwnAllowance() {
		RateLimiter limiter = new RateLimiter(true, 10, clock);
		limiter.acquire("a", 10);

		assertThatCode(() -> limiter.acquire("b", 10)).doesNotThrowAnyException();
	}

	@Test
	void requestBiggerThanTheLimitCanNeverFit() {
		RateLimiter limiter = new RateLimiter(true, 10, clock);

		assertThatThrownBy(() -> limiter.acquire("a", 11)).isInstanceOfSatisfying(RateLimitedException.class, e -> {
			assertThat(e.retryAfterSeconds()).isNull();
			assertThat(e.getMessage()).isEqualTo("This request has 11 SMS; the limit is 10 SMS per minute.");
		});
	}

	@Test
	void turnedOffNeverLimits() {
		RateLimiter limiter = new RateLimiter(false, 1, clock);

		assertThatCode(() -> limiter.acquire("a", 500)).doesNotThrowAnyException();
	}

}
