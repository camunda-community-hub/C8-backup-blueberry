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
    public List<RestorePhase> restorePhases() {
        if ("8.7".equals(minorVersion)) {
            return List.of(
                    RestorePhase.automated("preflight", "Validate backup completeness"),
                    RestorePhase.automated("zeebe_scale_down", "Stop Operate, Tasklist, Optimize, the gateway, and Zeebe"),
                    RestorePhase.automated("es_templates", "Start components so they create index templates"),
                    RestorePhase.automated("es_delete_indices", "Delete Camunda indices"),
                    RestorePhase.automated("es_snapshots", "Restore full Elasticsearch snapshots with no index filter"),
                    RestorePhase.automated("zeebe_delete_volumes", "Delete Zeebe persistent volume claims"),
                    RestorePhase.automated("zeebe_broker_restore", "Restore Zeebe brokers with ZEEBE_RESTORE"),
                    RestorePhase.automated("zeebe_scale_up", "Remove ZEEBE_RESTORE and start the other components"),
                    RestorePhase.manual("verify", "Confirm Operate, Tasklist, Optimize, and one test process",
                            "Open each application and start one test process. Do not treat the job log as proof.")
            );
        }
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
