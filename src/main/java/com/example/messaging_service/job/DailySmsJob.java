package com.example.messaging_service.job;

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

import com.example.messaging_service.dto.BatchSendResponse;
import com.example.messaging_service.dto.MessageType;
import com.example.messaging_service.exception.MessagingException;
import com.example.messaging_service.service.BatchMessageService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Every day (messaging.batch-job.cron, default 10:00 IST) reads batch-targets.json and sends each
 * provider group through {@link BatchMessageService}, in chunks of at most max-recipients
 * numbers. Off unless messaging.batch-job.enabled=true. Results go to the logs only.
 */
@Component
@ConditionalOnBooleanProperty("messaging.batch-job.enabled")
public class DailySmsJob {

	private static final Logger log = LoggerFactory.getLogger(DailySmsJob.class);

	private final BatchTargetsReader reader;

	private final BatchMessageService batchMessageService;

	public DailySmsJob(@Value("${messaging.batch-job.targets-file}") Resource targetsFile, JsonMapper mapper,
			BatchMessageService batchMessageService) {
		this.reader = new BatchTargetsReader(targetsFile, mapper);
		this.batchMessageService = batchMessageService;
	}

	/** Never throws: an exception here would only end up in Spring's scheduler log. */
	@Scheduled(cron = "${messaging.batch-job.cron}", zone = "${messaging.batch-job.zone}")
	public void run() {
		try {
			BatchTargets targets = reader.read();
			log.info("Batch job started: file version {}, {} groups", targets.version(), targets.groups().size());
			Totals totals = new Totals();
			for (BatchTargets.Group group : targets.groups()) {
				sendGroup(group, totals);
			}
			log.info("Batch job finished: total={} accepted={} failed={}", totals.total, totals.accepted,
					totals.failed);
		}
		catch (RuntimeException e) {
			log.error("Batch job sent nothing: {}", e.getMessage());
		}
	}

	private void sendGroup(BatchTargets.Group group, Totals totals) {
		// Duplicates are removed before splitting, so a number repeated far apart is still sent once.
		List<String> unique = new ArrayList<>(new LinkedHashSet<>(group.recipients()));
		int chunkSize = batchMessageService.maxRecipients();
		Totals groupTotals = new Totals();
		try {
			for (int from = 0; from < unique.size(); from += chunkSize) {
				List<String> chunk = unique.subList(from, Math.min(from + chunkSize, unique.size()));
				groupTotals.add(batchMessageService.send(MessageType.SMS, group.provider(), chunk, group.body()));
			}
		}
		catch (MessagingException e) {
			// An unknown or disabled provider fails the first chunk before any SMS is sent.
			log.error("{} group skipped: {} {}", group.provider(), e.code(), e.getMessage());
			return;
		}
		totals.addAll(groupTotals);
		log.info("{} group done: total={} accepted={} failed={} duplicatesRemoved={}", group.provider(),
				groupTotals.total, groupTotals.accepted, groupTotals.failed, group.recipients().size() - unique.size());
	}

	private static final class Totals {

		int total;

		int accepted;

		int failed;

		void add(BatchSendResponse response) {
			total += response.total();
			accepted += response.accepted();
			failed += response.failed();
		}

		void addAll(Totals other) {
			total += other.total;
			accepted += other.accepted;
			failed += other.failed;
		}

	}

}
