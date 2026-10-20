package com.example.messaging_service.job;

import java.util.List;

/**
 * The shape of batch-targets.json: phone numbers grouped by provider.
 *
 * @param version label for the file, usually the date it was prepared; only logged
 */
public record BatchTargets(String version, List<Group> groups) {

	/**
	 * Numbers that go through one provider. Every number in the group gets the same body.
	 */
	public record Group(String provider, String body, List<String> recipients) {
	}

}
