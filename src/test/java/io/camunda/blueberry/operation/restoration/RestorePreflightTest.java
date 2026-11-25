package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.operation.backup.BackupCompleteness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestorePreflightTest {


    @Test
    void allowsCompleteBackup() {
        BackupCompleteness.Detail detail = new BackupCompleteness.Detail(
                5, true, List.of("OPERATE", "ZEEBE"), List.of(), "COMPLETED");
        RestorePreflight.Result result = RestorePreflight.evaluate(5, List.of(detail));
        assertTrue(result.allowed());
    }

    @Test
    void blocksPartialBackup() {
        BackupCompleteness.Detail detail = new BackupCompleteness.Detail(
                6, false, List.of("OPERATE"), List.of("ZEEBE"), "PARTIALBACKUP");
        RestorePreflight.Result result = RestorePreflight.evaluate(6, List.of(detail));
        assertFalse(result.allowed());
        assertTrue(result.message().contains("Missing"));
    }

    @Test
    void blocksUnknownBackupId() {
        RestorePreflight.Result result = RestorePreflight.evaluate(99, List.of());
        assertFalse(result.allowed());
    }

    @Test
    void blocksInProgressBackup() {
        BackupCompleteness.Detail detail = new BackupCompleteness.Detail(
                7, false, List.of("OPERATE"), List.of(), "IN_PROGRESS");
        RestorePreflight.Result result = RestorePreflight.evaluate(7, List.of(detail));
        assertFalse(result.allowed());
        assertTrue(result.message().contains("in progress"));
    }

    @Test
    void theStepsOfTheRestoreAreFixed() {
        assertEquals(List.of(RestoreJob.STEP_1_VALIDATE, RestoreJob.STEP_2_STOP_OPTIMIZE, RestoreJob.STEP_3_RECOVERY_MODE,
                RestoreJob.STEP_4_RESTORE_SECONDARY_STORAGE, RestoreJob.STEP_5_RESTORE_ZEEBE, RestoreJob.STEP_6_WAIT_PROCESSING),
                RestoreJob.STEPS);
        assertEquals(6, RestoreJob.NUMBER_OF_STEPS);
    }
}
