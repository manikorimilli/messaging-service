package com.example.messaging_service.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;

/**
 * With the job turned on, it is registered on the configured cron. The cron here is set to a time
 * the test never reaches, so no SMS is sent.
 */
@SpringBootTest(properties = { "messaging.db-file=target/test-db/DailySmsJobScheduleTest.json",
		"messaging.batch-job.enabled=true", "messaging.batch-job.cron=0 0 3 1 1 *",
		"messaging.http-providers.twilio.variables.account-sid=ACtest",
		"messaging.http-providers.twilio.variables.api-key=SKtest",
		"messaging.http-providers.twilio.variables.api-secret=test-secret",
		"messaging.http-providers.twilio.variables.from=+15550001111",
		"messaging.http-providers.vonage.variables.api-key=test-key",
		"messaging.http-providers.vonage.variables.api-secret=test-secret",
		"messaging.http-providers.vonage.variables.from=MyBrand" })
class DailySmsJobScheduleTest {

	@Test
	void jobRunsOnTheConfiguredCron(@Autowired DailySmsJob job,
			@Autowired ScheduledAnnotationBeanPostProcessor scheduler) {
		assertThat(job).isNotNull();
		assertThat(scheduler.getScheduledTasks()).extracting(ScheduledTask::getTask)
			.filteredOn(CronTask.class::isInstance)
			.extracting(task -> ((CronTask) task).getExpression())
			.containsExactly("0 0 3 1 1 *");
	}

}
