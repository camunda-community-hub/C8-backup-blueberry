package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Same-environment restore from the PwC Camunda 8.7 runbook, section 3.
 * Kubernetes changes use scale and StatefulSet env patches. Elasticsearch restore sends no index filter.
 * A failure after index deletion does not scale the applications back up.
 */
import org.springframework.stereotype.Component;

@Component
public final class Pwc87RestoreExecutor {

    private final KubernetesWorkload kubernetes;
    private final ElasticsearchRestoreApi elasticsearch;
    private final RestorationPod restorationPod;

    public Pwc87RestoreExecutor(KubernetesWorkload kubernetes,
                                ElasticsearchRestoreApi elasticsearch,
                                RestorationPod restorationPod) {
        this.kubernetes = kubernetes;
        this.elasticsearch = elasticsearch;
        this.restorationPod = restorationPod;
    }

    public void execute(long backupId, RestoreLayout layout, OperationLog log) throws OperationException {
        log.info(null, "Restore follows the PwC Camunda 8.7 runbook, section 3. "
                + "Release " + layout.helmRelease() + " in namespace " + layout.namespace()
                + ". Repository " + layout.elasticsearchRepository() + ".");
        log.info(null, "Data written after this backup is lost. Zeebe volumes are deleted after the snapshots restore.");

        Map<WorkloadRef, Integer> original = captureReplicas(layout);
        int zeebeReplicas = original.get(layout.zeebe());
        if (zeebeReplicas < 1) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 400,
                    "Zeebe has no replicas",
                    "StatefulSet " + layout.zeebe().name() + " has 0 replicas. Restore needs the backup cluster size.");
        }
        boolean optimizeRequired = original.containsKey(layout.optimize());

        log.operationStep(null, "Stop components except Elasticsearch");
        scale(original, false);
        kubernetes.waitPodsDeleted(layout.zeebePodLabel(), layout.podDeleteTimeoutSeconds());

        log.operationStep(null, "Start components to create index templates");
        scale(original, true);
        waitReady(layout, original, true);
        requireTemplates(elasticsearch.listIndexTemplateNames(), optimizeRequired);

        log.operationStep(null, "Stop components again");
        scale(original, false);
        kubernetes.waitPodsDeleted(layout.zeebePodLabel(), layout.podDeleteTimeoutSeconds());

        log.operationStep(null, "Delete Camunda indices");
        for (String index : elasticsearch.listIndices()) {
            if (isCamundaIndex(index)) {
                log.info(null, "Delete index " + index);
                elasticsearch.deleteIndex(index);
            }
        }

        log.operationStep(null, "Restore Elasticsearch snapshots");
        List<Pwc87Snapshots.Planned> snapshots = Pwc87Snapshots.plan(
                elasticsearch.listSnapshotNames(layout.elasticsearchRepository()),
                backupId,
                optimizeRequired);
        for (Pwc87Snapshots.Planned snapshot : snapshots) {
            log.info(null, "Restore snapshot " + snapshot.name());
            elasticsearch.restoreSnapshot(layout.elasticsearchRepository(), snapshot.name());
        }

        log.operationStep(null, "Delete Zeebe volumes");
        kubernetes.deletePersistentVolumeClaimsContaining("zeebe");

        log.operationStep(null, "Restore Zeebe brokers");
        restorationPod.restoreBrokers(kubernetes, layout, zeebeReplicas, backupId, log);

        log.operationStep(null, "Return to normal operation");
        kubernetes.removeStatefulSetEnv(layout.zeebe().name(), "ZEEBE_RESTORE");
        kubernetes.removeStatefulSetEnv(layout.zeebe().name(), "ZEEBE_RESTORE_FROM_BACKUP_ID");
        scaleApplications(original);
        kubernetes.rolloutRestartStatefulSet(layout.zeebe().name());
        kubernetes.waitUntilReady(layout.zeebe(), zeebeReplicas, layout.zeebeRestoreTimeoutSeconds());
        waitReady(layout, applicationsOnly(original), false);

        log.operationStep(null, "Validate the restored cluster");
        log.info(null, "Open Operate and confirm process instances from the backup time.");
        log.info(null, "Open Tasklist and confirm open user tasks from the backup time.");
        log.info(null, "Open Optimize and confirm that reports load.");
        log.info(null, "Start one test process and confirm that it completes.");
    }

    static boolean isCamundaIndex(String index) {
        if (index == null || index.isBlank() || index.startsWith(".")) {
            return false;
        }
        String name = index.toLowerCase(Locale.ROOT);
        return name.contains("operate") || name.contains("tasklist") || name.contains("optimize") || name.contains("zeebe");
    }

    private Map<WorkloadRef, Integer> captureReplicas(RestoreLayout layout) throws OperationException {
        Map<WorkloadRef, Integer> original = new LinkedHashMap<>();
        require(layout.operate(), original);
        require(layout.tasklist(), original);
        if (kubernetes.exists(layout.optimize())) {
            original.put(layout.optimize(), kubernetes.replicas(layout.optimize()));
        }
        if (kubernetes.exists(layout.connectors())) {
            original.put(layout.connectors(), kubernetes.replicas(layout.connectors()));
        }
        require(layout.gateway(), original);
        require(layout.zeebe(), original);
        return original;
    }

    private void require(WorkloadRef workload, Map<WorkloadRef, Integer> original) throws OperationException {
        if (!kubernetes.exists(workload)) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 400,
                    "Workload missing",
                    "Kubernetes workload " + workload.name() + " was not found.");
        }
        original.put(workload, kubernetes.replicas(workload));
    }

    private void scale(Map<WorkloadRef, Integer> original, boolean atLeastOne) throws OperationException {
        for (Map.Entry<WorkloadRef, Integer> entry : original.entrySet()) {
            int replicas = atLeastOne ? Math.max(entry.getValue(), 1) : 0;
            kubernetes.scale(entry.getKey(), replicas);
        }
    }

    private void scaleApplications(Map<WorkloadRef, Integer> original) throws OperationException {
        for (Map.Entry<WorkloadRef, Integer> entry : applicationsOnly(original).entrySet()) {
            kubernetes.scale(entry.getKey(), entry.getValue());
        }
    }

    private Map<WorkloadRef, Integer> applicationsOnly(Map<WorkloadRef, Integer> original) {
        Map<WorkloadRef, Integer> apps = new LinkedHashMap<>();
        for (Map.Entry<WorkloadRef, Integer> entry : original.entrySet()) {
            if (entry.getKey().kind() != WorkloadRef.Kind.STATEFUL_SET) {
                apps.put(entry.getKey(), entry.getValue());
            }
        }
        return apps;
    }

    private void waitReady(RestoreLayout layout, Map<WorkloadRef, Integer> original, boolean atLeastOne)
            throws OperationException {
        for (Map.Entry<WorkloadRef, Integer> entry : original.entrySet()) {
            int replicas = atLeastOne ? Math.max(entry.getValue(), 1) : entry.getValue();
            if (replicas > 0) {
                int timeout = entry.getKey().kind() == WorkloadRef.Kind.STATEFUL_SET
                        ? layout.zeebeRestoreTimeoutSeconds()
                        : layout.podDeleteTimeoutSeconds();
                kubernetes.waitUntilReady(entry.getKey(), replicas, timeout);
            }
        }
    }

    private void requireTemplates(List<String> names, boolean requireOptimize) throws OperationException {
        String joined = String.join(" ", names).toLowerCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        if (!joined.contains("operate")) {
            missing.add("operate");
        }
        if (!joined.contains("tasklist")) {
            missing.add("tasklist");
        }
        if (!joined.contains("zeebe")) {
            missing.add("zeebe");
        }
        if (requireOptimize && !joined.contains("optimize")) {
            missing.add("optimize");
        }
        if (!missing.isEmpty()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Index templates missing",
                    "Index templates are missing for: " + String.join(", ", missing)
                            + ". Indices were not deleted.");
        }
    }
}
