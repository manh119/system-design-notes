package com.example.archive.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Kich hoat job archive theo lich cron (mac dinh 2h sang moi ngay,
 * chinh trong application.yml: archive.job.cron).
 *
 * Moi lan chay dung 1 JobParameters unique (timestamp) de Spring Batch
 * coi day la 1 JobInstance moi (Spring Batch mac dinh khong cho chay lai
 * job voi cung parameters neu lan truoc da COMPLETED).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BannerArchiveScheduler {

    private final JobLauncher jobLauncher;
    private final Job archiveUserBannersJob;

    //@Scheduled(cron = "${archive.job.cron:0 0 2 * * *}")
    public void runArchiveJob() {
        try {
            var params = new JobParametersBuilder()
                    .addString("runId", Instant.now().toString())
                    .toJobParameters();

            log.info("Bat dau job archive user_banners...");
            jobLauncher.run(archiveUserBannersJob, params);
        } catch (Exception e) {
            log.error("Job archive user_banners that bai", e);
            // TODO: ban co the ban alert (Slack/Telegram/email) o day
        }
    }
}
