package io.camunda.blueberry.client;


import java.util.List;


public class ActuatorBackupStatusResponse {
    private int backupId;
    private String state;
    private String failureReason;
    private List<Details> details;

    public int getBackupId() {
        return backupId;
    }

    public String getState() {
        return state;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public List<Details> getDetails() {
        return details;
    }

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
