package com.example.archive.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveBannerSchedule {

    private static final String ARCHIVE_SQL = """ 
            WITH to_move AS ( 
            SELECT id 
            FROM user_banners 
            LIMIT ? 
            ), 
            moved_rows AS ( 
            DELETE FROM user_banners 
            WHERE id IN (SELECT id FROM to_move) 
            RETURNING * 
            ) 
            INSERT INTO user_banners_archive 
            SELECT * FROM moved_rows 
            """;

    @Value("${archive.job.batch-size}")
    private int batchSizeArchive;

    private final JdbcTemplate jdbcTemplate;

    //@Scheduled(cron = "${archive.job.cron}")
    public void archiveBannerSchedule() {
        // Chốt mốc cắt MỘT LẦN: archive mọi banner tạo trước 00:00 hôm nay
//        Timestamp cutoff = Timestamp.valueOf(LocalDate.now().atStartOfDay());

        log.info("[archiveBannerSchedule] Bắt đầu archive user_banners, cutoff={}, batchSize={}", batchSizeArchive);

        long startTime = System.currentTimeMillis();
//        long lastId = 0L;
        long totalMigrated = 0L;
        int batchCount = 0;

        try {
            while (true) {
                int totalMoved = jdbcTemplate.update(ARCHIVE_SQL, batchSizeArchive);

                if (totalMoved == 0) {
                    break;
                }
//
//                // ORDER BY id đảm bảo danh sách tăng dần -> phần tử cuối là con trỏ mới
//                lastId = movedIds.get(movedIds.size() - 1);
                totalMigrated += batchSizeArchive;
                batchCount++;

                log.info("[archiveBannerSchedule] Batch #{} tổng {}.",
                        batchCount, totalMigrated);
                if (totalMigrated >= 50000000) {
                    break;
                }
            }

            log.info("[archiveBannerSchedule] Hoàn tất! {} rows / {} batches trong {} ms.",
                    totalMigrated, batchCount, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
//            log.error("[archiveBannerSchedule] Lỗi khi archive. Đã migrate {} rows, dừng tại lastId={}. ",
//                    totalMigrated, lastId, e);
            throw e; // để scheduler/monitoring biết job đã fail
        }
    }
}



