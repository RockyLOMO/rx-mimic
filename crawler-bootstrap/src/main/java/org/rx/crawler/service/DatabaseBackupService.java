package org.rx.crawler.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.io.EntityDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Slf4j
@Service
public class DatabaseBackupService {
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long TWO_DAYS_MILLIS = 2L * 24 * 60 * 60 * 1000L;

    @Autowired
    @Qualifier("entityDatabase")
    private EntityDatabase taskDatabase;

    @Autowired(required = false)
    @Qualifier("priceDatabase")
    private EntityDatabase priceDatabase;

    @Value("${app.storage.backupDir:./backups/db}")
    private String backupDir;

    @Value("${app.storage.backupEnabled:true}")
    private boolean backupEnabled;

    @Value("${app.storage.backupRetentionDays:30}")
    private int retentionDays;

    private final ReentrantLock backupLock = new ReentrantLock();

    @Getter
    private volatile BackupReport lastReport;

    @Getter
    public static class BackupReport {
        private final String triggerReason;
        private final LocalDateTime timestamp = LocalDateTime.now();
        private final Map<String, BackupItem> items = new LinkedHashMap<>();
        private int cleanedOldFiles;
        private boolean success = true;

        public BackupReport(String triggerReason) {
            this.triggerReason = triggerReason;
        }
    }

    @Getter
    public static class BackupItem {
        private final String dbName;
        private final String targetPath;
        private final long fileSizeBytes;
        private final long durationMillis;
        private final boolean success;
        private final String errorMessage;

        public BackupItem(String dbName, String targetPath, long fileSizeBytes, long durationMillis, boolean success, String errorMessage) {
            this.dbName = dbName;
            this.targetPath = targetPath;
            this.fileSizeBytes = fileSizeBytes;
            this.durationMillis = durationMillis;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * 定时任务：每 2 天执行一次（默认凌晨 3 点）
     */
    @Scheduled(cron = "${app.storage.backupCron:0 0 3 */2 * ?}")
    public void scheduledBackup() {
        if (!backupEnabled) {
            log.info("Database backup is disabled by configuration");
            return;
        }
        performBackup("Scheduled cron trigger");
    }

    /**
     * 服务就绪后检查：若上次备份距今超过 48 小时或从未备份，自动补备份
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!backupEnabled) {
            return;
        }
        CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
            try {
                long lastBackupTime = findLatestBackupTimestamp();
                long now = System.currentTimeMillis();
                if (lastBackupTime <= 0 || (now - lastBackupTime) >= TWO_DAYS_MILLIS) {
                    log.info("Database backup catch-up triggered (last backup: {}, elapsed: {} ms)",
                            lastBackupTime > 0 ? Instant.ofEpochMilli(lastBackupTime).toString() : "never",
                            lastBackupTime > 0 ? (now - lastBackupTime) : -1);
                    performBackup("Startup catch-up: last backup is older than 48 hours or missing");
                } else {
                    log.info("Database backup is fresh (last backup: {}), no catch-up needed",
                            Instant.ofEpochMilli(lastBackupTime).toString());
                }
            } catch (Exception e) {
                log.warn("Database backup startup check failed, error={}", e.getMessage());
            }
        });
    }

    /**
     * 核心备份执行方法
     */
    public BackupReport performBackup(String reason) {
        if (!backupLock.tryLock()) {
            log.warn("Database backup is already in progress, skipping request, reason={}", reason);
            return lastReport;
        }
        try {
            log.info("Starting database backup, reason={}", reason);
            Path dir = Paths.get(backupDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);

            String timeTag = LocalDateTime.now().format(FILE_TIME_FORMATTER);
            BackupReport report = new BackupReport(reason);

            // 1. 备份 taskDatabase (browser-task-db)
            if (taskDatabase != null) {
                Path taskBackupFile = dir.resolve("browser-task-db-" + timeTag + ".zip");
                backupSingleDatabase(taskDatabase, "browser-task-db", taskBackupFile, report);
            }

            // 2. 备份 priceDatabase (product-price-db)
            if (priceDatabase != null) {
                Path priceBackupFile = dir.resolve("product-price-db-" + timeTag + ".zip");
                backupSingleDatabase(priceDatabase, "product-price-db", priceBackupFile, report);
            }

            // 3. 清理超期备份文件
            int cleaned = cleanupExpiredBackups(dir, retentionDays);
            report.cleanedOldFiles = cleaned;

            this.lastReport = report;
            log.info("Database backup completed, success={}, cleanedOldFiles={}, reason={}",
                    report.isSuccess(), cleaned, reason);
            return report;
        } catch (Exception e) {
            log.error("Database backup process failed, reason={}, error={}", reason, e.getMessage(), e);
            BackupReport report = new BackupReport(reason);
            report.success = false;
            this.lastReport = report;
            return report;
        } finally {
            backupLock.unlock();
        }
    }

    private void backupSingleDatabase(EntityDatabase db, String dbName, Path targetFile, BackupReport report) {
        long start = System.currentTimeMillis();
        // H2 SQL BACKUP TO 要求路径为单引号包围，路径使用正斜杠
        String sqlPath = targetFile.toString().replace('\\', '/');
        String backupSql = "BACKUP TO '" + sqlPath + "'";
        try {
            db.executeUpdate(backupSql);
            long duration = System.currentTimeMillis() - start;
            long fileSize = Files.exists(targetFile) ? Files.size(targetFile) : 0L;
            log.info("Database [{}] backed up to [{}] (size: {} bytes, duration: {} ms)",
                    dbName, targetFile, fileSize, duration);
            report.getItems().put(dbName, new BackupItem(dbName, targetFile.toString(), fileSize, duration, true, null));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Database [{}] backup failed, sql={}, error={}", dbName, backupSql, e.getMessage(), e);
            report.success = false;
            report.getItems().put(dbName, new BackupItem(dbName, targetFile.toString(), 0L, duration, false, e.getMessage()));
        }
    }

    private int cleanupExpiredBackups(Path dir, int days) {
        if (days <= 0 || !Files.exists(dir)) {
            return 0;
        }
        long expireBefore = System.currentTimeMillis() - (long) days * 24 * 60 * 60 * 1000L;
        int cleaned = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return (name.startsWith("browser-task-db-") || name.startsWith("product-price-db-"))
                        && name.endsWith(".zip");
            }).toList();

            for (Path file : files) {
                try {
                    long lastModified = Files.getLastModifiedTime(file).toMillis();
                    if (lastModified < expireBefore) {
                        Files.deleteIfExists(file);
                        cleaned++;
                        log.info("Cleaned up expired database backup file: {}", file);
                    }
                } catch (Exception e) {
                    log.warn("Failed to delete expired backup file {}, error={}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan directory for expired backups, dir={}, error={}", dir, e.getMessage());
        }
        return cleaned;
    }

    public long findLatestBackupTimestamp() {
        Path dir = Paths.get(backupDir).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            return -1L;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return (name.startsWith("browser-task-db-") || name.startsWith("product-price-db-"))
                        && name.endsWith(".zip");
            }).mapToLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }).max().orElse(-1L);
        } catch (Exception e) {
            return -1L;
        }
    }
}
