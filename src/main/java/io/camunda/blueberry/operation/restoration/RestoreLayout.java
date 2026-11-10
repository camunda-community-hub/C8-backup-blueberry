package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.config.BlueberryConfig;

/**
 * Workload names for one Camunda Helm release. Defaults follow the PwC 8.7 runbook
 * (release {@code camunda87helm}, namespace {@code camunda}).
 */
public record RestoreLayout(
        String namespace,
        String helmRelease,
        Repositories repositories,
        WorkloadRef operate,
        WorkloadRef tasklist,
        WorkloadRef optimize,
        WorkloadRef gateway,
        WorkloadRef zeebe,
        WorkloadRef connectors,
        String zeebePodLabel,
        int podDeleteTimeoutSeconds,
        int zeebeRestoreTimeoutSeconds
) {

    /**
     * Elasticsearch repositories holding the snapshots of each component.
     * They are the repositories used by the backup: the same source, the BlueberryConfig.
     */
    public record Repositories(String operate, String tasklist, String optimize, String zeebeRecords) {

        public static Repositories from(BlueberryConfig config) {
            return new Repositories(
                    config.getOperateRepository(),
                    config.getTasklistRepository(),
                    config.getOptimizeRepository(),
                    config.getZeebeRecordRepository());
        }
    }

    public static RestoreLayout from(BlueberryConfig config) {
        String release = config.getRestoreHelmRelease();
        return new RestoreLayout(
                config.getNamespace(),
                release,
                Repositories.from(config),
                WorkloadRef.deployment(release + "-operate"),
                WorkloadRef.deployment(release + "-tasklist"),
                WorkloadRef.deployment(release + "-optimize"),
                WorkloadRef.deployment(release + "-zeebe-gateway"),
                WorkloadRef.statefulSet(release + "-zeebe"),
                WorkloadRef.deployment(release + "-connectors"),
                "app.kubernetes.io/name=zeebe",
                config.getRestorePodDeleteTimeoutSeconds(),
                config.getRestoreZeebeTimeoutSeconds()
        );
    }

    public String zeebePodName(int ordinal) {
        return zeebe.name() + "-" + ordinal;
    }
}
