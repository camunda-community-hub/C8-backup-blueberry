package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.operation.backup.BackupCompleteness;
import io.camunda.blueberry.platform.version.CamundaVersionProfile;

import java.util.List;
import java.util.Optional;

public final class RestorePreflight {

    private RestorePreflight() {
    }

    public record Result(
            boolean allowed,
            long backupId,
            boolean complete,
            List<String> presentComponents,
            List<String> missingComponents,
            String backupStatus,
            String message,
            String camundaMinorVersion,
            String docsRestoreUrl
    ) {
    }

    public static Result evaluate(
            long backupId,
            List<BackupCompleteness.Detail> allBackups,
            CamundaVersionProfile profile) {
        Optional<BackupCompleteness.Detail> match = allBackups.stream()
                .filter(d -> d.backupId() == backupId)
                .findFirst();

        if (match.isEmpty()) {
            return new Result(false, backupId, false, List.of(), List.of(), "",
                    "No backup found for ID " + backupId + ".",
                    profile.minorVersion(), profile.docsRestoreUrl());
        }

        BackupCompleteness.Detail detail = match.get();
        if (BackupInfo.Status.IN_PROGRESS.name().equals(detail.backupStatus())) {
            return new Result(false, backupId, false, detail.presentComponents(), detail.missingComponents(),
                    detail.backupStatus(),
                    "Backup is still in progress.",
                    profile.minorVersion(), profile.docsRestoreUrl());
        }
        if (!detail.complete()) {
            return new Result(false, backupId, false, detail.presentComponents(), detail.missingComponents(),
                    detail.backupStatus(),
                    "Backup is incomplete. Missing components: " + String.join(", ", detail.missingComponents()),
                    profile.minorVersion(), profile.docsRestoreUrl());
        }

        return new Result(true, backupId, true, detail.presentComponents(), detail.missingComponents(),
                detail.backupStatus(),
                "Backup is complete and eligible for restore planning.",
                profile.minorVersion(), profile.docsRestoreUrl());
    }
}
