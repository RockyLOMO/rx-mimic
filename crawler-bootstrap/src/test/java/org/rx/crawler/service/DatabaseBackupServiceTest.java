package org.rx.crawler.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.io.EntityDatabase;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class DatabaseBackupServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseBackupService backupService;
    private EntityDatabase taskDb;
    private EntityDatabase priceDb;

    @BeforeEach
    public void setup() {
        backupService = new DatabaseBackupService();
        taskDb = mock(EntityDatabase.class);
        priceDb = mock(EntityDatabase.class);

        ReflectionTestUtils.setField(backupService, "taskDatabase", taskDb);
        ReflectionTestUtils.setField(backupService, "priceDatabase", priceDb);
        ReflectionTestUtils.setField(backupService, "backupDir", tempDir.toString());
        ReflectionTestUtils.setField(backupService, "backupEnabled", true);
        ReflectionTestUtils.setField(backupService, "retentionDays", 30);
    }

    @Test
    public void testPerformBackupExecutesSqlAndRecordsReport() throws IOException {
        AtomicReference<String> taskSql = new AtomicReference<>();
        AtomicReference<String> priceSql = new AtomicReference<>();

        when(taskDb.executeUpdate(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            taskSql.set(sql);
            // Simulate H2 creating the zip file
            String pathStr = sql.substring("BACKUP TO '".length(), sql.length() - 1);
            Files.writeString(Path.of(pathStr), "mock-task-db-content");
            return 1;
        });

        when(priceDb.executeUpdate(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            priceSql.set(sql);
            String pathStr = sql.substring("BACKUP TO '".length(), sql.length() - 1);
            Files.writeString(Path.of(pathStr), "mock-price-db-content");
            return 1;
        });

        DatabaseBackupService.BackupReport report = backupService.performBackup("Test backup");

        assertNotNull(report);
        assertTrue(report.isSuccess());
        assertEquals("Test backup", report.getTriggerReason());
        assertEquals(2, report.getItems().size());

        assertTrue(taskSql.get().startsWith("BACKUP TO '"));
        assertTrue(taskSql.get().contains("browser-task-db-"));
        assertTrue(taskSql.get().endsWith(".zip'"));

        assertTrue(priceSql.get().startsWith("BACKUP TO '"));
        assertTrue(priceSql.get().contains("product-price-db-"));
        assertTrue(priceSql.get().endsWith(".zip'"));

        long latestTs = backupService.findLatestBackupTimestamp();
        assertTrue(latestTs > 0);
    }

    @Test
    public void testCleanupExpiredBackups() throws IOException {
        Path oldFile = tempDir.resolve("browser-task-db-20260101-000000.zip");
        Files.writeString(oldFile, "old-backup");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(35, ChronoUnit.DAYS)));

        Path recentFile = tempDir.resolve("browser-task-db-20261101-000000.zip");
        Files.writeString(recentFile, "recent-backup");
        Files.setLastModifiedTime(recentFile, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        when(taskDb.executeUpdate(anyString())).thenReturn(1);
        when(priceDb.executeUpdate(anyString())).thenReturn(1);

        DatabaseBackupService.BackupReport report = backupService.performBackup("Cleanup test");

        assertFalse(Files.exists(oldFile), "Old backup should be deleted");
        assertTrue(Files.exists(recentFile), "Recent backup should be kept");
        assertEquals(1, report.getCleanedOldFiles());
    }

    @Test
    public void testBackupFailureHandledGracefully() {
        when(taskDb.executeUpdate(anyString())).thenThrow(new RuntimeException("Disk full"));
        when(priceDb.executeUpdate(anyString())).thenReturn(1);

        DatabaseBackupService.BackupReport report = backupService.performBackup("Failure test");

        assertNotNull(report);
        assertFalse(report.isSuccess());
        assertFalse(report.getItems().get("browser-task-db").isSuccess());
        assertTrue(report.getItems().get("browser-task-db").getErrorMessage().contains("Disk full"));
        assertTrue(report.getItems().get("product-price-db").isSuccess());
    }
}
