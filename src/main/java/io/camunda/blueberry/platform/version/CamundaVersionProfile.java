package io.camunda.blueberry.platform.version;

import java.util.List;
import java.util.Map;

/**
 * Procedure and documentation profile for a Camunda minor release (8.7–8.10).
 */
public record CamundaVersionProfile(
        String minorVersion,
        String docsBackupUrl,
        String docsRestoreUrl,
        boolean zeebeExportPauseSoftQuery,
        String minHelmChartVersionForZeebeRestore
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "minorVersion", minorVersion,
                "docsBackupUrl", docsBackupUrl,
                "docsRestoreUrl", docsRestoreUrl,
                "zeebeExportPauseSoftQuery", zeebeExportPauseSoftQuery,
                "minHelmChartVersionForZeebeRestore", minHelmChartVersionForZeebeRestore
        );
    }

    /**
     * Ordered restore phases. Camunda 8.7 follows the PwC runbook and can run when execution is enabled.
     * Other minors stay manual until a runbook for that version exists.
     */
    /**
     * High-level backup steps Blueberry runs for the selected Camunda version (shown in the Backup UI).
     */
    public List<RestorePhase> backupPhases() {
        if ("8.7".equals(minorVersion)) {
            return List.of(
                    RestorePhase.automated("webapps", "Backup Operate, Tasklist, and Optimize (Elasticsearch snapshots via actuator)"),
                    RestorePhase.automated("zeebe_pause", "Pause Zeebe exporters"),
                    RestorePhase.automated("zeebe_record", "Backup Zeebe record indices in Elasticsearch"),
                    RestorePhase.automated("zeebe_data", "Backup Zeebe broker data to object storage"),
                    RestorePhase.automated("zeebe_resume", "Resume Zeebe exporters"));
        }
        String doc = docsBackupUrl;
        return List.of(
                RestorePhase.manual("overview", "Follow the official Camunda " + minorVersion + " backup procedure",
                        "See " + doc));
    }

    public List<RestorePhase> restorePhases() {
        if ("8.10".equals(minorVersion)) {
            // The Restore API: REST only, no Kubernetes, the brokers are not restarted. See RestoreApiExecutor
            return List.of(
                    RestorePhase.automated("validate", "Validate the backup (Zeebe, secondary storage) and the cluster (REST API, Optimize stopped)"),
                    RestorePhase.automated("recovery_mode", "Recovery mode: PATCH /v2/mode?mode=RECOVERING"),
                    RestorePhase.automated("secondary_storage", "Restore the secondary storage (Elasticsearch: dry run, delete the indices, restore the snapshots; a database: its native restore)"),
                    RestorePhase.automated("zeebe_restore", "Restore Zeebe: POST /v2/restore, then GET /v2/restore until it is finished"),
                    RestorePhase.automated("processing", "The cluster leaves the recovery mode: GET /v2/topology")
            );
        }
        // The other versions (8.7, 8.8, 8.9): the restore is described, not executed. The automated restore of 8.7
        // (Kubernetes, PwC runbook) is in the branch pr-54.
        String doc = docsRestoreUrl;
        return List.of(
                RestorePhase.automated("preflight", "Validate backup completeness"),
                RestorePhase.manual("es_templates", "Restore Elasticsearch templates",
                        "Follow " + doc + " — start components briefly on a clean datastore if required."),
                RestorePhase.manual("es_snapshots", "Restore Elasticsearch snapshots (Operate, Tasklist, Optimize, Zeebe records)",
                        "Use the same backup ID for every snapshot. See " + doc),
                RestorePhase.manual("zeebe_scale_down", "Scale down Zeebe brokers",
                        "Manual Kubernetes step. See doc/restore/README.md in this repository."),
                RestorePhase.manual("zeebe_broker_restore", "Restore Zeebe broker data (ZEEBE_RESTORE)",
                        "Requires Helm chart >= " + minHelmChartVersionForZeebeRestore + ". See " + doc),
                RestorePhase.manual("zeebe_scale_up", "Scale up Zeebe brokers",
                        "Manual Kubernetes step after restore pods complete."),
                RestorePhase.automated("verify", "Post-restore verification checklist")
        );
    }

    public static CamundaVersionProfile forMinor(String minor) {
        String base = "https://docs.camunda.io/docs/" + minor + "/self-managed/operational-guides/backup-restore/";
        return switch (minor) {
            case "8.8" -> new CamundaVersionProfile("8.8", base + "backup/", base + "restore/", true, "12.1.0");
            case "8.9" -> new CamundaVersionProfile("8.9", base + "backup/", base + "restore/", true, "12.1.0");
            case "8.10" -> new CamundaVersionProfile("8.10", base + "backup/", base + "restore/", true, "12.1.0");
            default -> new CamundaVersionProfile("8.7",
                    "https://docs.camunda.io/docs/8.7/self-managed/operational-guides/backup-restore/backup/",
                    "https://docs.camunda.io/docs/8.7/self-managed/operational-guides/backup-restore/restore/",
                    true,
                    "12.1.0");
        };
    }
}
