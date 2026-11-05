package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.config.BlueberryConfig;

/**
 * Workload names for one Camunda Helm release. Defaults follow the PwC 8.7 runbook
 * (release {@code camunda87helm}, namespace {@code camunda}).
 */
public record RestoreLayout(
        String namespace,
        String helmRelease,
        String elasticsearchRepository,
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
    public static RestoreLayout from(BlueberryConfig config) {
        String release = config.getRestoreHelmRelease();
        String repository = config.getRestoreElasticsearchRepository();
        if (repository == null || repository.isBlank()) {
            repository = config.getOperateRepository();
        }
        return new RestoreLayout(
                config.getNamespace(),
                release,
                repository,
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
