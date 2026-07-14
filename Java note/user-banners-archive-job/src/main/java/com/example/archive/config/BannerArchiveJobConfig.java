package com.example.archive.config;

import com.example.archive.tasklet.ArchiveBatchTasklet;
import com.example.archive.tasklet.SnapshotMaxIdTasklet;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class BannerArchiveJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final SnapshotMaxIdTasklet snapshotMaxIdTasklet;
    private final ArchiveBatchTasklet archiveBatchTasklet;

    @Bean
    public Job archiveUserBannersJob() {
        return new JobBuilder("archiveUserBannersJob", jobRepository)
                .start(snapshotMaxIdStep())
                .next(archiveBatchStep())
                .build();
    }

    @Bean
    public Step snapshotMaxIdStep() {
        return new StepBuilder("snapshotMaxIdStep", jobRepository)
                .tasklet(snapshotMaxIdTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step archiveBatchStep() {
        return new StepBuilder("archiveBatchStep", jobRepository)
                .tasklet(archiveBatchTasklet, transactionManager)
                // startLimit cao vi step nay se duoc goi lai (CONTINUABLE) nhieu lan trong 1 lan chay job
                .build();
    }
}
