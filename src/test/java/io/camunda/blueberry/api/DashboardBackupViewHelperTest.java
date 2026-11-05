package io.camunda.blueberry.api;

import io.camunda.blueberry.connect.BackupInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardBackupViewHelperTest {

    @Test
    void buildHistory_sortsNewestFirstAndCapsAtMaxEntries() {
        List<BackupInfo> backups = List.of(info(5, BackupInfo.Status.COMPLETED), info(12, BackupInfo.Status.FAILED),
                info(8, BackupInfo.Status.PARTIALBACKUP));
        List<Map<String, Object>> history = DashboardBackupViewHelper.buildHistory(backups);
        assertEquals(3, history.size());
        assertEquals("ID 12", history.get(0).get("label"));
        assertEquals("FAILED", history.get(0).get("status"));
        assertEquals("ID 8", history.get(1).get("label"));
        assertEquals("ID 5", history.get(2).get("label"));
    }

    @Test
    void buildHistory_capsAtTwentyNewestIds() {
        List<BackupInfo> backups = new ArrayList<>();
        for (long id = 1; id <= 25; id++) {
            backups.add(info(id, BackupInfo.Status.COMPLETED));
        }
        List<Map<String, Object>> history = DashboardBackupViewHelper.buildHistory(backups);
        assertEquals(DashboardBackupViewHelper.MAX_TIMELINE_ENTRIES, history.size());
        assertEquals("ID 25", history.get(0).get("label"));
        assertEquals("ID 6", history.get(19).get("label"));
    }

    @Test
    void latestBackupSummary_picksHighestId() {
        Map<String, Object> summary = DashboardBackupViewHelper.latestBackupSummary(
                List.of(info(3, BackupInfo.Status.COMPLETED), info(9, BackupInfo.Status.PARTIALBACKUP)));
        assertTrue((Boolean) summary.get("present"));
        assertEquals(9L, summary.get("backupId"));
        assertEquals("PARTIALBACKUP", summary.get("status"));
    }

    @Test
    void latestBackupSummary_emptyWhenNoBackups() {
        Map<String, Object> summary = DashboardBackupViewHelper.latestBackupSummary(List.of());
        assertFalse((Boolean) summary.get("present"));
    }

    private static BackupInfo info(long id, BackupInfo.Status status) {
        BackupInfo backupInfo = new BackupInfo();
        backupInfo.backupId = id;
        backupInfo.status = status;
        backupInfo.backupTime = LocalDateTime.of(2026, 1, 15, 10, 0);
        return backupInfo;
    }
}
