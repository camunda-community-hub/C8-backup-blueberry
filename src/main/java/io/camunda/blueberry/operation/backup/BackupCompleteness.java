package io.camunda.blueberry.operation.backup;

import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.CamundaApplicationInt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Derives complete / partial backup status from merged {@link BackupInfo} and active components.
 */
public final class BackupCompleteness {

    private BackupCompleteness() {
    }

    public record Detail(
            long backupId,
            boolean complete,
            List<String> presentComponents,
            List<String> missingComponents,
            String backupStatus
    ) {
    }

    public static Set<CamundaApplicationInt.COMPONENT> requiredActiveComponents(
            List<io.camunda.blueberry.connect.BackupComponentInt> components) {
        return components.stream()
                .filter(io.camunda.blueberry.connect.BackupComponentInt::isActive)
                .map(io.camunda.blueberry.connect.BackupComponentInt::getComponent)
                .collect(Collectors.toSet());
    }

    public static Detail fromBackup(BackupInfo backupInfo, Set<CamundaApplicationInt.COMPONENT> required) {
        List<String> present = backupInfo.components.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .collect(Collectors.toCollection(ArrayList::new));

        List<String> missing = required.stream()
                .filter(c -> !backupInfo.components.contains(c))
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .collect(Collectors.toCollection(ArrayList::new));

        String status = backupInfo.status == null ? "" : backupInfo.status.name();
        boolean complete = backupInfo.status == BackupInfo.Status.COMPLETED && missing.isEmpty();

        return new Detail(backupInfo.backupId, complete, present, missing, status);
    }

    public static List<Detail> fromList(List<BackupInfo> backups, Set<CamundaApplicationInt.COMPONENT> required) {
        return backups.stream()
                .map(b -> fromBackup(b, required))
                .toList();
    }
}
