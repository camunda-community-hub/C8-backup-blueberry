package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.operation.backup.BackupCompleteness;
import io.camunda.blueberry.platform.version.CamundaVersionProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestorePreflightTest {

    private static final CamundaVersionProfile PROFILE = CamundaVersionProfile.forMinor("8.7");

    @Test
    void allowsCompleteBackup() {
        BackupCompleteness.Detail detail = new BackupCompleteness.Detail(
                5, true, List.of("OPERATE", "ZEEBE"), List.of(), "COMPLETED");
        RestorePreflight.Result result = RestorePreflight.evaluate(5, List.of(detail), PROFILE);
        assertTrue(result.allowed());
        assertEquals("8.7", result.camundaMinorVersion());
    }

    @Test
    void blocksPartialBackup() {
        BackupCompleteness.Detail detail = new BackupCompleteness.Detail(
                6, false, List.of("OPERATE"), List.of("ZEEBE"), "PARTIALBACKUP");
        RestorePreflight.Result result = RestorePreflight.evaluate(6, List.of(detail), PROFILE);
        assertFalse(result.allowed());
        assertTrue(result.message().contains("Missing"));
    }

    @Test
    void blocksUnknownBackupId() {
        RestorePreflight.Result result = RestorePreflight.evaluate(99, List.of(), PROFILE);
        assertFalse(result.allowed());
    }

    @Test
    void blocksInProgressBackup() {
        BackupCompleteness.Detail detail = new BackupCompleteness.Detail(
                7, false, List.of("OPERATE"), List.of(), "IN_PROGRESS");
        RestorePreflight.Result result = RestorePreflight.evaluate(7, List.of(detail), PROFILE);
        assertFalse(result.allowed());
        assertTrue(result.message().contains("in progress"));
    }

    @Test
    void profileRestorePhasesIncludeManualKubernetesSteps() {
        List<String> ids = PROFILE.restorePhases().stream().map(p -> p.id()).toList();
        assertTrue(ids.contains("zeebe_broker_restore"));
        assertTrue(ids.contains("zeebe_scale_down"));
    }

    @Test
    void restore810UsesTheRestoreApiWithoutKubernetes() {
        List<String> ids = CamundaVersionProfile.forMinor("8.10").restorePhases().stream().map(p -> p.id()).toList();
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("validate", "recovery_mode", "secondary_storage", "zeebe_restore", "processing"), ids);
        // the number of phases is the number of steps of the executor
        org.junit.jupiter.api.Assertions.assertEquals(RestoreApiExecutor.NUMBER_OF_STEPS, ids.size());
    }

    @Test
    void theOtherVersionsAreDescribedNotExecuted() {
        for (String minor : List.of("8.7", "8.8", "8.9")) {
            boolean automatedRestore = CamundaVersionProfile.forMinor(minor).restorePhases().stream()
                    .anyMatch(p -> p.id().equals("recovery_mode"));
            org.junit.jupiter.api.Assertions.assertFalse(automatedRestore, minor);
        }
    }
}
