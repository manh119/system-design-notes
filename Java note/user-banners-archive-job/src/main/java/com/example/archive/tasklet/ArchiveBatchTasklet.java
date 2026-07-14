package com.example.archive.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buoc 2: di chuyen du lieu theo tung batch nho.
 *
 * Ky thuat quan trong: dung 1 cau lenh SQL duy nhat ket hop
 * DELETE ... RETURNING ... INSERT (CTE) de PostgreSQL tu xu ly
 * viec "cat" du lieu ngay trong DB - khong keo du lieu qua tang Java.
 * => Nhanh hon rat nhieu so voi doc 10 trieu dong ra JVM roi ghi lai.
 *
 * Moi lan goi execute() la 1 batch, chay trong 1 transaction rieng
 * (khong phai 1 transaction khong lo cho ca 10 trieu dong):
 *  - Neu job bi loi giua chung, cac batch da chay thanh cong van giu nguyen
 *    (du lieu da chuyen sang archive va xoa khoi bang goc).
 *  - Chay lai job se tu dong tiep tuc vi dieu kien WHERE id <= maxId
 *    tu nhien loai bo nhung dong da bi xoa roi.
 *
 * Tasklet tra ve CONTINUABLE de Spring Batch tu goi lai cho toi khi
 * khong con dong nao de xu ly (FINISHED).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveBatchTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;

    @Value("${archive.job.batch-size:20000}")
    private int batchSize;

    @Value("${archive.job.sleep-between-batches-ms:50}")
    private long sleepMs;

    private static final String MOVE_BATCH_SQL = """
            WITH moved_rows AS (
                DELETE FROM user_banners
                WHERE id IN (
                    SELECT id FROM user_banners
                    WHERE id <= ?
                    ORDER BY id
                    LIMIT ?
                )
                RETURNING *
            )
            INSERT INTO user_banners_archive
            SELECT *
            FROM moved_rows
            ON CONFLICT (id) DO NOTHING
            """;
//private static final String MOVE_BATCH_SQL = """
//            WITH moved_rows AS (
//                DELETE FROM user_banners
//                WHERE id IN (
//                    SELECT id FROM user_banners
//                    LIMIT ?
//                )
//                RETURNING *
//            )
//            INSERT INTO user_banners_archive
//            SELECT *
//            FROM moved_rows
//            ON CONFLICT (id) DO NOTHING
//            """;

    private long totalMoved = 0;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {

        Long maxId = chunkContext.getStepContext().getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .getLong(SnapshotMaxIdTasklet.MAX_ID_KEY);

        int moved = jdbcTemplate.update(MOVE_BATCH_SQL, maxId, batchSize);
//        int moved = jdbcTemplate.update(MOVE_BATCH_SQL, batchSize);
        totalMoved += moved;

        contribution.incrementWriteCount(moved);

        if (moved == 0) {
            log.info("Hoan tat archive. Tong so dong da chuyen: {}", totalMoved);
            return RepeatStatus.FINISHED;
        }

        log.info("Da chuyen 1 batch: {} dong (luy ke: {})", moved, totalMoved);

        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return RepeatStatus.CONTINUABLE;
    }
}
