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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class Pwc87RestoreExecutor {

    /**
     * After the templates are requested, check them every 2 seconds, 10 times
     */
    private static final long TEMPLATE_CHECK_PAUSE_MILLIS = 2000;
    private static final int TEMPLATE_CHECK_ATTEMPTS = 10;

    private final KubernetesWorkload kubernetes;
    private final ElasticsearchRestoreApi elasticsearch;
    private final RestorationPod restorationPod;
    private final ZeebeTemplateSeeder templateSeeder;
    private final long templateCheckPauseMillis;
    private final int templateCheckAttempts;

    @Autowired
    public Pwc87RestoreExecutor(KubernetesWorkload kubernetes,
                                ElasticsearchRestoreApi elasticsearch,
                                RestorationPod restorationPod,
                                ZeebeTemplateSeeder templateSeeder) {
        this(kubernetes, elasticsearch, restorationPod, templateSeeder,
                TEMPLATE_CHECK_PAUSE_MILLIS, TEMPLATE_CHECK_ATTEMPTS);
    }

    Pwc87RestoreExecutor(KubernetesWorkload kubernetes,
                         ElasticsearchRestoreApi elasticsearch,
                         RestorationPod restorationPod,
                         ZeebeTemplateSeeder templateSeeder,
                         long templateCheckPauseMillis,
                         int templateCheckAttempts) {
        this.kubernetes = kubernetes;
        this.elasticsearch = elasticsearch;
        this.restorationPod = restorationPod;
        this.templateSeeder = templateSeeder;
        this.templateCheckPauseMillis = templateCheckPauseMillis;
        this.templateCheckAttempts = templateCheckAttempts;
    }

    /**
     * Run the restore. Whatever the failure, the components are restarted: without them, the list of the backups is not
     * accessible (Zeebe is down), and no restore can be done.
     */
    public void execute(long backupId, RestoreLayout layout, OperationLog log) throws OperationException {
        RestoreState state = new RestoreState();
        try {
            run(backupId, layout, log, state);
        } catch (OperationException | RuntimeException e) {
            restartComponents(layout, state, log, e);
            throw e;
        }
    }

    /**
     * What the restore changed in the cluster: needed to restart the components after a failure
     */
    private static final class RestoreState {
        /**
         * Replicas of the components before the restore. Null until step 1 read them
         */
        private Map<WorkloadRef, Integer> original;
        /**
         * True when the restore started to change the cluster (stop the components)
         */
        private boolean clusterChanged = false;
    }

    /**
     * After a failure: remove the Zeebe restore variables (a restart with them would restore Zeebe again), and scale every
     * component to its original number of replicas. Each action is tried even if a previous one failed.
     */
    private void restartComponents(RestoreLayout layout, RestoreState state, OperationLog log, Exception failure) {
        if (state.original == null || !state.clusterChanged) {
            log.info(null, "The restore stopped before any change: the components were not stopped, nothing to restart.");
            return;
        }
        log.warning(null, "The restore failed (" + failure.getMessage() + "). "
                + "The components are restarted, so the list of the backups is accessible again.");
        for (String variable : new String[]{"ZEEBE_RESTORE", "ZEEBE_RESTORE_FROM_BACKUP_ID"}) {
            try {
                kubernetes.removeStatefulSetEnv(layout.zeebe().name(), variable);
            } catch (Exception e) {
                log.error(null, "Can't remove the variable " + variable + " of " + layout.zeebe().name() + ": " + e.getMessage());
            }
        }
        for (Map.Entry<WorkloadRef, Integer> entry : state.original.entrySet()) {
            try {
                kubernetes.scale(entry.getKey(), entry.getValue());
                log.info(null, "Restart " + entry.getKey().name() + " with " + entry.getValue() + " replicas.");
            } catch (Exception e) {
                log.error(null, "Can't restart " + entry.getKey().name() + ": " + e.getMessage());
            }
        }
        log.warning(null, "Check the data before the next restore: the Elasticsearch indices may have been deleted, "
                + "and some snapshots may not be restored.");
    }

    private void run(long backupId, RestoreLayout layout, OperationLog log, RestoreState state) throws OperationException {
        log.info(null, "Restore follows the PwC Camunda 8.7 runbook, section 3. "
                + "Release " + layout.helmRelease() + " in namespace " + layout.namespace() + ".");
        log.info(null, "Repositories (the ones used by the backup): Operate [" + layout.repositories().operate()
                + "], Tasklist [" + layout.repositories().tasklist()
                + "], Optimize [" + layout.repositories().optimize()
                + "], Zeebe records [" + layout.repositories().zeebeRecords() + "].");
        log.info(null, "Data written after this backup is lost. Zeebe volumes are deleted after the snapshots restore.");

        // Step 1: validate the backup AND the environment, before any change. If something is wrong, nothing was touched.
        log.operationStep(null, "Validate backup completeness and the environment");
        Map<WorkloadRef, Integer> original = captureReplicas(layout);
        state.original = original;
        log.info(null, "Kubernetes: the components of the release " + layout.helmRelease() + " exist in the namespace "
                + layout.namespace() + ".");
        int zeebeReplicas = original.get(layout.zeebe());
        if (zeebeReplicas < 1) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 400,
                    "Zeebe has no replicas",
                    "StatefulSet " + layout.zeebe().name() + " has 0 replicas. Restore needs the backup cluster size.");
        }
        log.info(null, "Kubernetes: Zeebe has " + zeebeReplicas + " replicas.");
        boolean optimizeRequired = original.containsKey(layout.optimize());

        List<Pwc87Snapshots.Planned> snapshots = planSnapshots(backupId, layout, optimizeRequired);
        log.info(null, "Elasticsearch: the repositories answer, and the " + snapshots.size()
                + " snapshots of the backup " + backupId + " are complete.");
        for (Pwc87Snapshots.Planned snapshot : snapshots) {
            log.info(null, "Snapshot " + snapshot.name() + " in the repository " + snapshot.repository());
        }

        // Before touching anything: the templates must exist. Nothing is stopped or deleted until they exist.
        log.operationStep(null, "Check that the index templates exist");
        List<String> missingAtStart = missingTemplates(elasticsearch.listIndexTemplateNames());
        boolean templatesExist = missingAtStart.isEmpty();
        log.info(null, templatesExist
                ? "All the index templates exist."
                : "Some index templates are missing: " + String.join(", ", missingAtStart));

        log.operationStep(null, "Create the missing index templates");
        if (templatesExist) {
            log.info(null, "Nothing to create, the index templates exist.");
        } else {
            createTemplates(layout, log);
        }

        log.operationStep(null, "Stop components except Elasticsearch");
        // From here, the cluster is changed: a failure restarts the components
        state.clusterChanged = true;
        scale(original, false);
        kubernetes.waitPodsDeleted(layout.zeebePodLabel(), layout.podDeleteTimeoutSeconds());

        log.operationStep(null, "Start components to create index templates, then stop them again");
        scale(original, true);
        waitReady(layout, original, true);
        requireTemplates(elasticsearch.listIndexTemplateNames(), layout);
        scale(original, false);
        kubernetes.waitPodsDeleted(layout.zeebePodLabel(), layout.podDeleteTimeoutSeconds());

        log.operationStep(null, "Delete Camunda indices");
        for (String index : elasticsearch.listIndices()) {
            if (isCamundaIndex(index)) {
                log.info(null, "Delete index " + index);
                elasticsearch.deleteIndex(index);
            }
        }

        // The snapshots were planned and checked at the first step
        log.operationStep(null, "Restore Elasticsearch snapshots");
        for (Pwc87Snapshots.Planned snapshot : snapshots) {
            log.info(null, "Restore snapshot " + snapshot.name() + " from the repository " + snapshot.repository());
            elasticsearch.restoreSnapshot(snapshot.repository(), snapshot.name());
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

        log.operationStep(null, "Cluster restarted");
        kubernetes.waitUntilReady(layout.zeebe(), zeebeReplicas, layout.zeebeRestoreTimeoutSeconds());
        waitReady(layout, applicationsOnly(original), false);

        // Not a step: the restore must be validated manually
        log.info(null, "The restore is done and must be validated manually: open Operate, Tasklist and Optimize, "
                + "check the data from the backup time, and start one test process.");
    }

    /**
     * Deploy a process in Zeebe, so the Zeebe exporter creates its templates, then wait for the templates:
     * check every 2 seconds, 10 times. Stop with an error if they are still missing.
     */
    private void createTemplates(RestoreLayout layout, OperationLog log) throws OperationException {
        templateSeeder.seed(log);
        for (int attempt = 1; attempt <= templateCheckAttempts; attempt++) {
            pause();
            List<String> missing = missingTemplates(elasticsearch.listIndexTemplateNames());
            if (missing.isEmpty()) {
                log.info(null, "The index templates exist (check " + attempt + "/" + templateCheckAttempts + ").");
                return;
            }
            log.info(null, "Check " + attempt + "/" + templateCheckAttempts + ": templates still missing for "
                    + String.join(", ", missing));
        }
        // Still missing: stop here, nothing was stopped or deleted
        requireTemplates(elasticsearch.listIndexTemplateNames(), layout);
    }

    private void pause() throws OperationException {
        try {
            Thread.sleep(templateCheckPauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 500,
                    "Restore interrupted", "Interrupted while waiting for the index templates");
        }
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

    /**
     * Find the snapshots of the backup in the repository of each component. Fail, with a way to check, if a repository
     * or a snapshot is missing: nothing was stopped or deleted yet.
     */
    private List<Pwc87Snapshots.Planned> planSnapshots(long backupId, RestoreLayout layout, boolean optimizeRequired)
            throws OperationException {
        try {
            return Pwc87Snapshots.plan(layout.repositories(), elasticsearch::listSnapshotNames, backupId, optimizeRequired);
        } catch (OperationException e) {
            String es = elasticsearch.baseUrl();
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Backup not ready to restore",
                    e.getMessage() + "\n"
                            + "The restore reads the repositories of the backup: Operate [" + layout.repositories().operate()
                            + "], Tasklist [" + layout.repositories().tasklist()
                            + "], Optimize [" + layout.repositories().optimize()
                            + "], Zeebe records [" + layout.repositories().zeebeRecords() + "]. Nothing was stopped or deleted.\n"
                            + "This may come from a desynchronization between Blueberry and the components: each component writes "
                            + "its backups in the repository of ITS configuration, and the restore reads the repository of the "
                            + "BLUEBERRY configuration. They may not point to the same repository, for Tasklist, Operate, Optimize "
                            + "or Zeebe records:\n"
                            + "  - Operate: Blueberry operateRepository [" + layout.repositories().operate()
                            + "], component variable CAMUNDA_OPERATE_BACKUP_REPOSITORY_NAME\n"
                            + "  - Tasklist: Blueberry tasklistRepository [" + layout.repositories().tasklist()
                            + "], component variable CAMUNDA_TASKLIST_BACKUP_REPOSITORY_NAME\n"
                            + "  - Optimize: Blueberry optimizeRepository [" + layout.repositories().optimize()
                            + "], component variable CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME\n"
                            + "  - Zeebe records: Blueberry elasticsearch.zeebeRecordRepository [" + layout.repositories().zeebeRecords()
                            + "], written by Blueberry during the backup: check that it was not changed after the backup\n"
                            + "Compare them in the table of repositories (Parameters page, or GET /blueberry/api/configuration/repositories), "
                            + "and set the Blueberry configuration to the repository used by the component when the backup was made.\n"
                            + "List the repositories of Elasticsearch:\n"
                            + "  curl -s '" + es + "/_snapshot?pretty'\n"
                            + "List the snapshots of a repository:\n"
                            + "  curl -s '" + es + "/_cat/snapshots/<repository>?v'");
        }
    }

    /**
     * @return the components (operate, tasklist, zeebe) for which Elasticsearch has no index template:
     * a template name must contain the component name
     */
    private static List<String> missingTemplates(List<String> names) {
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
        // Optimize is not checked: it creates its own indices, and only some of them have templates
        return missing;
    }

    private void requireTemplates(List<String> names, RestoreLayout layout) throws OperationException {
        List<String> missing = missingTemplates(names);
        if (!missing.isEmpty()) {
            String es = elasticsearch.baseUrl();
            StringBuilder message = new StringBuilder();
            message.append("Index templates are missing for: ").append(String.join(", ", missing))
                    .append(". Indices were not deleted.\n")
                    .append("After the components started, Elasticsearch must have an index template whose name contains each of these words. ")
                    .append("Each component creates its templates when it starts (Zeebe: through its Elasticsearch exporter).\n")
                    .append("Elasticsearch has two kinds of templates (composable and legacy, Optimize uses the legacy ones). ")
                    .append("This command lists both:\n")
                    .append("  curl -s '").append(es).append("/_cat/templates?v'\n")
                    .append("Check the missing ones:\n");
            for (String name : missing) {
                message.append("  curl -s '").append(es).append("/_cat/templates/*").append(name).append("*?v'\n");
            }
            message.append("Check that the components are running:\n")
                    .append("  kubectl -n ").append(layout.namespace()).append(" get pods");
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Index templates missing", message.toString());
        }
    }
}
