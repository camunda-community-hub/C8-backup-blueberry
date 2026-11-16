package io.camunda.blueberry.operation.backup;

import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BackupManagerPartialTest {

    @Test
    void completeWhenAllRequiredComponentsPresent() {
        BackupInfo info = new BackupInfo();
        info.backupId = 10;
        info.status = BackupInfo.Status.COMPLETED;
        info.components = new HashSet<>(Set.of(
                CamundaApplicationInt.COMPONENT.OPERATE,
                CamundaApplicationInt.COMPONENT.TASKLIST,
                CamundaApplicationInt.COMPONENT.ZEEBE));

        Set<CamundaApplicationInt.COMPONENT> required = EnumSet.of(
                CamundaApplicationInt.COMPONENT.OPERATE,
                CamundaApplicationInt.COMPONENT.TASKLIST,
                CamundaApplicationInt.COMPONENT.ZEEBE);

        BackupCompleteness.Detail detail = BackupCompleteness.fromBackup(info, required);

        assertTrue(detail.complete());
        assertTrue(detail.missingComponents().isEmpty());
        assertEquals(3, detail.presentComponents().size());
    }

    @Test
    void incompleteWhenComponentMissing() {
        BackupInfo info = new BackupInfo();
        info.backupId = 11;
        info.status = BackupInfo.Status.COMPLETED;
        info.components = new HashSet<>(Set.of(CamundaApplicationInt.COMPONENT.OPERATE));

        Set<CamundaApplicationInt.COMPONENT> required = EnumSet.of(
                CamundaApplicationInt.COMPONENT.OPERATE,
                CamundaApplicationInt.COMPONENT.ZEEBE);

        BackupCompleteness.Detail detail = BackupCompleteness.fromBackup(info, required);

        assertFalse(detail.complete());
        assertEquals(1, detail.presentComponents().size());
        assertTrue(detail.missingComponents().contains("ZEEBE"));
    }

    @Test
    void partialBackupStatusAlignsWithMissingComponents() {
        BackupInfo info = new BackupInfo();
        info.backupId = 12;
        info.status = BackupInfo.Status.PARTIALBACKUP;
        info.components = new HashSet<>(Set.of(
                CamundaApplicationInt.COMPONENT.OPERATE,
                CamundaApplicationInt.COMPONENT.TASKLIST));

        Set<CamundaApplicationInt.COMPONENT> required = EnumSet.of(
                CamundaApplicationInt.COMPONENT.OPERATE,
                CamundaApplicationInt.COMPONENT.TASKLIST,
                CamundaApplicationInt.COMPONENT.ZEEBERECORD);

        BackupCompleteness.Detail detail = BackupCompleteness.fromBackup(info, required);

        assertFalse(detail.complete());
        assertTrue(detail.missingComponents().contains("ZEEBERECORD"));
    }

    @Test
    void requiredSetOmitsInactiveOptimize() {
        List<BackupComponentInt> components = List.of(
                stub(CamundaApplicationInt.COMPONENT.OPERATE, true),
                stub(CamundaApplicationInt.COMPONENT.OPTIMIZE, false),
                stub(CamundaApplicationInt.COMPONENT.ZEEBE, true));

        Set<CamundaApplicationInt.COMPONENT> required = BackupCompleteness.requiredActiveComponents(components);

        assertFalse(required.contains(CamundaApplicationInt.COMPONENT.OPTIMIZE));
        assertEquals(EnumSet.of(CamundaApplicationInt.COMPONENT.OPERATE, CamundaApplicationInt.COMPONENT.ZEEBE),
                required);
    }

    private static BackupComponentInt stub(CamundaApplicationInt.COMPONENT component, boolean active) {
        return new BackupComponentInt() {
            @Override
            public boolean isActive() {
                return active;
            }

            @Override
            public CamundaApplicationInt.COMPONENT getComponent() {
                return component;
            }

            @Override
            public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
                return null;
            }

            @Override
            public BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException {
                return null;
            }

            @Override
            public List<BackupInfo> getListBackups() throws OperationException {
                return List.of();
            }

            @Override
            public String getUrlListBackup() {
                return "";
            }
        };
    }
}
