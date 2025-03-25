package io.camunda.blueberry.access;

import io.camunda.zeebe.protocol.management.BackupStatusCode;

import java.time.LocalDateTime;

public class BackupInfo {

    public long backupId;
    public String backupName;
    /**
     * Time in UTC
     */
    public LocalDateTime backupTime;
    public Status status;

    public static Status fromZeebeStatus(BackupStatusCode status) {
        switch (status) {
            case FAILED -> {
                return Status.FAILED;
            }
            case COMPLETED -> {
                return Status.COMPLETED;
            }
            case DOES_NOT_EXIST -> {
                return Status.UNKNOWN;
            }
            case IN_PROGRESS -> {
                return Status.INPROGRESS;
            }
            case SBE_UNKNOWN -> {
                return Status.UNKNOWN;
            }
            case NULL_VAL -> {
                return Status.UNKNOWN;
            }
            default -> {
                return Status.UNKNOWN;
            }
        }
    }

    public enum Status {COMPLETED, FAILED, INPROGRESS, UNKNOWN}
}
