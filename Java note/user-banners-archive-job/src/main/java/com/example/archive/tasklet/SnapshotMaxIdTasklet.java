package com.example.archive.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Buoc 1: chup lai MAX(id) hien tai cua bang user_banners.
 * Muc dich: job chi archive nhung dong DA TON TAI tai thoi diem bat dau,
 * khong dung vao du lieu moi duoc insert trong luc job dang chay
 * (vi bang duoc insert lien tuc trong ngay).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotMaxIdTasklet implements Tasklet {

    public static final String MAX_ID_KEY = "maxId";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM user_banners", Long.class);

        log.info("Snapshot maxId = {}", maxId);

        chunkContext.getStepContext().getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .putLong(MAX_ID_KEY, maxId == null ? 0L : maxId);

        return RepeatStatus.FINISHED;
    }
}
