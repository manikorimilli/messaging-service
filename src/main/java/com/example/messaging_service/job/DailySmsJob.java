package com.example.messaging_service.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.messaging_service.dto.BatchSendResponse.RecipientResult;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.service.BatchMessageService;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every day (messaging.batch-job.cron, default 10:00 IST) reads batch-targets.json and sends each
 * provider group through {@link BatchMessageService}, in chunks of at most max-recipients
 * numbers. Off unless messaging.batch-job.enabled=true. Results go to the logs, and every run is
 * added to jobHistory.json (messaging.batch-job.history-file).
 */
@Component
@ConditionalOnBooleanProperty("messaging.batch-job.enabled")
public class DailySmsJob {

	private static final Logger log = LoggerFactory.getLogger(DailySmsJob.class);

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	private final BatchTargetsReader reader;

	private final Path historyFile;

	private final ZoneId zone;

	private final BatchMessageService batchMessageService;

	public DailySmsJob(@Value("${messaging.batch-job.targets-file}") Resource targetsFile,
			@Value("${messaging.batch-job.history-file}") Path historyFile,
			@Value("${messaging.batch-job.zone}") ZoneId zone, JsonMapper mapper,
			BatchMessageService batchMessageService) {
		this.reader = new BatchTargetsReader(targetsFile, mapper);
		this.historyFile = historyFile;
		this.zone = zone;
		this.batchMessageService = batchMessageService;
	}

	/** Never throws: an exception here would only end up in Spring's scheduler log. */
	@Scheduled(cron = "${messaging.batch-job.cron}", zone = "${messaging.batch-job.zone}")
	public void run() {
		OffsetDateTime startedAt = OffsetDateTime.now(zone);
		String version = null;
		JobHistory.Failure error = null;
		List<JobHistory.Group> groups = new ArrayList<>();
		try {
			BatchTargets targets = reader.read();
			version = targets.version();
			log.info("Batch job started: file version {}, {} groups", version, targets.groups().size());
			for (BatchTargets.Group group : targets.groups()) {
				groups.add(sendGroup(group));
			}
		}
		catch (RuntimeException e) {
			error = new JobHistory.Failure(null, e.getMessage());
			log.error("Batch job sent nothing: {}", e.getMessage());
		}
		JobHistory.Run run = JobHistory.Run.of(startedAt, OffsetDateTime.now(zone), version, error, groups);
		if (error == null) {
			log.info("Batch job finished: total={} accepted={} failed={}", run.total(), run.accepted(), run.failed());
		}
		addToHistory(run);
	}

	/** Adds the run to the end of jobHistory.json. Earlier runs are kept as they are. */
	private void addToHistory(JobHistory.Run run) {
		try {
			ObjectNode root = Files.exists(historyFile) ? (ObjectNode) JSON.readTree(Files.readString(historyFile))
					: JSON.createObjectNode();
			ArrayNode runs = root.has("runs") ? (ArrayNode) root.get("runs") : root.putArray("runs");
			runs.add(JSON.valueToTree(run));
			Files.writeString(historyFile, JSON.writeValueAsString(root));
		}
		catch (IOException | RuntimeException e) {
			log.error("Batch job run {} not added to {}: {}", run.runId(), historyFile.toAbsolutePath(), e.getMessage());
		}
	}

	private JobHistory.Group sendGroup(BatchTargets.Group group) {
		// Duplicates are removed before splitting, so a number repeated far apart is still sent once.
		List<String> unique = new ArrayList<>(new LinkedHashSet<>(group.recipients()));
		int chunkSize = batchMessageService.maxRecipients();
		List<RecipientResult> results = new ArrayList<>();
		JobHistory.Failure error = null;
		try {
			for (int from = 0; from < unique.size(); from += chunkSize) {
				List<String> chunk = unique.subList(from, Math.min(from + chunkSize, unique.size()));
				results.addAll(batchMessageService.send(MessageType.SMS, group.provider(), chunk, group.body()).results());
			}
		}
		catch (MessagingException e) {
			// An unknown or disabled provider fails the first chunk before any SMS is sent.
			error = new JobHistory.Failure(e.code().name(), e.getMessage());
			log.error("{} group skipped: {} {}", group.provider(), e.code(), e.getMessage());
		}
		JobHistory.Group done = JobHistory.Group.of(group.provider(), group.recipients().size() - unique.size(),
				results, error);
		if (error == null) {
			log.info("{} group done: total={} accepted={} failed={} duplicatesRemoved={}", group.provider(),
					done.total(), done.accepted(), done.failed(), done.duplicatesRemoved());
		}
		return done;
	}

}
