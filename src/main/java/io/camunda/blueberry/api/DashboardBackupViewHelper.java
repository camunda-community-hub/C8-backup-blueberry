package io.camunda.blueberry.api;

import io.camunda.blueberry.connect.BackupInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds dashboard backup timeline entries from merged {@link BackupInfo} lists.
 */
public final class DashboardBackupViewHelper {

    static final int MAX_TIMELINE_ENTRIES = 20;

    private DashboardBackupViewHelper() {
    }

    public static List<Map<String, Object>> buildHistory(List<BackupInfo> listBackups) {
        List<BackupInfo> sorted = new ArrayList<>(listBackups);
        sorted.sort(Comparator.comparingLong(BackupInfo::getBackupId).reversed());

        List<Map<String, Object>> history = new ArrayList<>();
        int limit = Math.min(MAX_TIMELINE_ENTRIES, sorted.size());
        for (int i = 0; i < limit; i++) {
            BackupInfo info = sorted.get(i);
            Map<String, Object> point = new HashMap<>();
            point.put("label", "ID " + info.backupId);
            point.put("value", historyBarValue(info.status));
            point.put("status", statusKey(info.status));
            if (info.backupTime != null) {
                point.put("backupTime", info.backupTime.toString() + "Z");
            }
            history.add(point);
        }
        return history;
    }

    /**
     * All the backups, oldest first (no cap): backup id, status and backup time. The UI groups them by day.
     */
    public static List<Map<String, Object>> buildTimeline(List<BackupInfo> listBackups) {
        List<BackupInfo> sorted = new ArrayList<>(listBackups);
        sorted.sort(Comparator.comparingLong(BackupInfo::getBackupId));

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (BackupInfo info : sorted) {
            Map<String, Object> point = new HashMap<>();
            point.put("backupId", info.backupId);
            point.put("status", statusKey(info.status));
            if (info.backupTime != null) {
                point.put("backupTime", info.backupTime.toString() + "Z");
            }
            timeline.add(point);
        }
        return timeline;
    }

    public static Map<String, Object> latestBackupSummary(List<BackupInfo> listBackups) {
        Map<String, Object> summary = new HashMap<>();
        if (listBackups == null || listBackups.isEmpty()) {
            summary.put("present", false);
            return summary;
        }
        BackupInfo latest = listBackups.stream()
                .max(Comparator.comparingLong(BackupInfo::getBackupId))
                .orElseThrow();
        summary.put("present", true);
        summary.put("backupId", latest.backupId);
        summary.put("status", statusKey(latest.status));
        if (latest.backupTime != null) {
            summary.put("backupTime", latest.backupTime.toString() + "Z");
        }
        return summary;
    }

    static String statusKey(BackupInfo.Status status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return status.name();
    }

    static double historyBarValue(BackupInfo.Status status) {
        if (status == null) {
            return 0.5;
        }
        return switch (status) {
            case COMPLETED -> 1.0;
            case PARTIALBACKUP -> 0.7;
            case IN_PROGRESS -> 0.5;
            case FAILED -> 0.3;
            default -> 0.4;
        };
    }
}
