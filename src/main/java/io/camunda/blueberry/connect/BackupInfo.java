package io.camunda.blueberry.connect;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BackupInfo {

    public long backupId;
    public String backupName;
    /**
     * Time in UTC
     */
    public LocalDateTime backupTime;
    public Status status;

    public List<Details> details;
    /**
     * Register which conmponent declare this backup
     */
    public Set<CamundaApplicationInt.COMPONENT> components = new HashSet<>();

    public static Status fromZeebeStatus(String status) {
        switch (status) {
            case "FAILED" -> {
                return Status.FAILED;
            }
            case "INCOMPLETE" -> {
                return Status.FAILED;
            }
            case "COMPLETED" -> {
                return Status.COMPLETED;
            }
            case "DOES_NOT_EXIST" -> {
                return Status.UNKNOWN;
            }
            case "IN_PROGRESS" -> {
                return Status.IN_PROGRESS;
            }
            case "SBE_UNKNOWN" -> {
                return Status.UNKNOWN;
            }
            case "NULL_VAL" -> {
                return Status.UNKNOWN;
            }
            default -> {
                return Status.UNKNOWN;
            }
        }
    }

    public long getBackupId() {
        return backupId;
    }

    public enum Status {COMPLETED, FAILED, IN_PROGRESS, UNKNOWN, PARTIALBACKUP}

    public static class Details {
        private String snapshotName;
        private String state;
        private String startTime;
        private List<String> failures;

        public String getSnapshotName() {
            return snapshotName;
        }

        public String getState() {
            return state;
        }

        public String getStartTime() {
            return startTime;
        }

        public List<String> getFailures() {
            return failures;
        }
    }
}
