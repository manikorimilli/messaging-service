package com.example.messaging_service.service;

import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.messaging_service.exception.RateLimitedException;

/**
 * Limits how many SMS one caller can send: each caller has an allowance of sms-per-minute SMS
 * that refills evenly over a minute. Kept in memory, so it resets on restart and each running
 * copy of the app counts on its own.
 */
@Service
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private static final long MINUTE = 60_000;

	// Above this many callers, the ones that are full again are dropped: they behave like new callers.
	private static final int CLEANUP_ABOVE = 1_000;

	private final boolean enabled;

	private final int perMinute;

	private final InstantSource clock;

	private final Map<String, Allowance> callers = new ConcurrentHashMap<>();

	@Autowired
	public RateLimiter(@Value("${messaging.rate-limit.enabled:true}") boolean enabled,
			@Value("${messaging.rate-limit.sms-per-minute:100}") int perMinute) {
		this(enabled, perMinute, InstantSource.system());
	}

	RateLimiter(boolean enabled, int perMinute, InstantSource clock) {
		this.enabled = enabled;
		this.perMinute = perMinute;
		this.clock = clock;
	}

	/**
	 * Takes {@code count} SMS from the caller's allowance.
	 *
	 * @throws RateLimitedException when the allowance is too low; nothing is taken then
	 */
	public void acquire(String caller, int count) {
		if (!enabled) {
			return;
		}
		if (count > perMinute) {
			throw new RateLimitedException("This request has %d SMS; the limit is %d SMS per minute."
				.formatted(count, perMinute), null);
		}
		long now = clock.millis();
		if (callers.size() > CLEANUP_ABOVE) {
			callers.values().removeIf(allowance -> allowance.isFull(now));
		}
		Allowance allowance = callers.computeIfAbsent(caller, key -> new Allowance(perMinute, now));
		long waitSeconds = allowance.take(count, now);
		if (waitSeconds > 0) {
			log.warn("Rate limit reached for {}: asked for {} SMS", caller, count);
			throw new RateLimitedException("Rate limit is %d SMS per minute. Try again in %d seconds."
				.formatted(perMinute, waitSeconds), waitSeconds);
		}
	}

	private static final class Allowance {

		private final int perMinute;

		private double left;

		private long updatedAt;

		Allowance(int perMinute, long now) {
			this.perMinute = perMinute;
			this.left = perMinute;
			this.updatedAt = now;
		}

		/** Takes count and returns 0, or takes nothing and returns the seconds to wait. */
		synchronized long take(int count, long now) {
			left = Math.min(perMinute, left + (double) (now - updatedAt) * perMinute / MINUTE);
			updatedAt = now;
			if (left >= count) {
				left -= count;
				return 0;
			}
			return Math.max(1, (long) Math.ceil((count - left) * 60 / perMinute));
		}

		synchronized boolean isFull(long now) {
			return now - updatedAt >= MINUTE;
		}

	}

}
