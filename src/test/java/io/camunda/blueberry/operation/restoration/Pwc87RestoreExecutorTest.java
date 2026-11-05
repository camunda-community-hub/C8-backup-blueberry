package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Pwc87RestoreExecutorTest {

    @Test
    void followsPwc87RunbookOrder() throws Exception {
        RestoreLayout layout = layout();
        FakeKubernetes kubernetes = new FakeKubernetes();
        kubernetes.present(layout.operate(), 1);
        kubernetes.present(layout.tasklist(), 1);
        kubernetes.present(layout.optimize(), 1);
        kubernetes.present(layout.gateway(), 1);
        kubernetes.present(layout.zeebe(), 3);

        FakeElasticsearch elasticsearch = new FakeElasticsearch();
        elasticsearch.indices.addAll(List.of("operate-list-view", "tasklist-task", ".security", "metrics"));
        elasticsearch.templates.addAll(List.of("operate-template", "tasklist-template", "optimize-template", "zeebe-record"));
        elasticsearch.snapshots.addAll(List.of(
                "camunda_operate_42_8.7.16_part_2_of_2",
                "camunda_operate_42_8.7.16_part_1_of_2",
                "camunda_operate_12_8.7.16_part_1_of_6",
                "camunda_tasklist_42_8.7.16_part_1_of_2",
                "camunda_tasklist_42_8.7.16_part_2_of_2",
                "camunda_optimize_42_8.7.16_part_1_of_1",
                "camunda_zeebe_records_backup_42"
        ));

        new Pwc87RestoreExecutor(kubernetes, elasticsearch, new RestorationPod())
                .execute(42, layout, new OperationLog());

        assertEquals(List.of(
                "camunda_operate_42_8.7.16_part_1_of_2",
                "camunda_operate_42_8.7.16_part_2_of_2",
                "camunda_tasklist_42_8.7.16_part_1_of_2",
                "camunda_tasklist_42_8.7.16_part_2_of_2",
                "camunda_optimize_42_8.7.16_part_1_of_1",
                "camunda_zeebe_records_backup_42"
        ), elasticsearch.restored);
        assertEquals(List.of("operate-list-view", "tasklist-task"), elasticsearch.deleted);
        assertTrue(kubernetes.actions.contains("deletePvc zeebe"));
        assertTrue(kubernetes.actions.contains("env camunda87helm-zeebe ZEEBE_RESTORE=true"));
        assertTrue(kubernetes.actions.contains("env camunda87helm-zeebe ZEEBE_RESTORE_FROM_BACKUP_ID=42"));
        assertTrue(kubernetes.actions.contains("removeEnv camunda87helm-zeebe ZEEBE_RESTORE"));
        assertTrue(kubernetes.actions.contains("restart camunda87helm-zeebe"));
        assertTrue(kubernetes.actions.indexOf("deletePvc zeebe") > kubernetes.actions.indexOf("scale camunda87helm-zeebe 0"));
        assertTrue(kubernetes.actions.indexOf("env camunda87helm-zeebe ZEEBE_RESTORE=true")
                > kubernetes.actions.indexOf("deletePvc zeebe"));
        int lastOperate = kubernetes.actions.lastIndexOf("scale camunda87helm-operate 1");
        int pvc = kubernetes.actions.indexOf("deletePvc zeebe");
        assertTrue(lastOperate > pvc);
    }

    @Test
    void doesNotDeleteVolumesWhenASnapshotIsMissing() {
        RestoreLayout layout = layout();
        FakeKubernetes kubernetes = new FakeKubernetes();
        kubernetes.present(layout.operate(), 1);
        kubernetes.present(layout.tasklist(), 1);
        kubernetes.present(layout.gateway(), 1);
        kubernetes.present(layout.zeebe(), 1);
        FakeElasticsearch elasticsearch = new FakeElasticsearch();
        elasticsearch.templates.addAll(List.of("operate-template", "tasklist-template", "zeebe-record"));
        elasticsearch.snapshots.add("camunda_operate_7_8.7.16_part_1_of_2");

        OperationException error = assertThrows(OperationException.class, () ->
                new Pwc87RestoreExecutor(kubernetes, elasticsearch, new RestorationPod())
                        .execute(7, layout, new OperationLog()));

        assertTrue(error.getMessage().contains("Operate"));
        assertTrue(elasticsearch.restored.isEmpty());
        assertFalse(kubernetes.actions.contains("deletePvc zeebe"));
        assertFalse(kubernetes.actions.contains("scale camunda87helm-operate 1")
                && kubernetes.actions.get(kubernetes.actions.size() - 1).equals("scale camunda87helm-operate 1"));
    }

    @Test
    void stopsBeforeIndexDeletionWhenTemplatesAreMissing() {
        RestoreLayout layout = layout();
        FakeKubernetes kubernetes = new FakeKubernetes();
        kubernetes.present(layout.operate(), 1);
        kubernetes.present(layout.tasklist(), 1);
        kubernetes.present(layout.gateway(), 1);
        kubernetes.present(layout.zeebe(), 1);
        FakeElasticsearch elasticsearch = new FakeElasticsearch();
        elasticsearch.indices.add("operate-list-view");
        elasticsearch.templates.add("operate-template");

        assertThrows(OperationException.class, () ->
                new Pwc87RestoreExecutor(kubernetes, elasticsearch, new RestorationPod())
                        .execute(7, layout, new OperationLog()));

        assertTrue(elasticsearch.deleted.isEmpty());
        assertFalse(kubernetes.actions.contains("deletePvc zeebe"));
    }

    private static RestoreLayout layout() {
        return new RestoreLayout(
                "camunda",
                "camunda87helm",
                "camunda-backups",
                WorkloadRef.deployment("camunda87helm-operate"),
                WorkloadRef.deployment("camunda87helm-tasklist"),
                WorkloadRef.deployment("camunda87helm-optimize"),
                WorkloadRef.deployment("camunda87helm-zeebe-gateway"),
                WorkloadRef.statefulSet("camunda87helm-zeebe"),
                WorkloadRef.deployment("camunda87helm-connectors"),
                "app.kubernetes.io/name=zeebe",
                30,
                30
        );
    }

    private static final class FakeKubernetes implements KubernetesWorkload {
        private final Map<String, Integer> replicas = new LinkedHashMap<>();
        private final List<String> actions = new ArrayList<>();

        void present(WorkloadRef workload, int replicaCount) {
            replicas.put(key(workload), replicaCount);
        }

        @Override
        public boolean exists(WorkloadRef workload) {
            return replicas.containsKey(key(workload));
        }

        @Override
        public int replicas(WorkloadRef workload) throws OperationException {
            if (!exists(workload)) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 400, "missing", workload.name());
            }
            return replicas.get(key(workload));
        }

        @Override
        public void scale(WorkloadRef workload, int replicaCount) {
            actions.add("scale " + workload.name() + " " + replicaCount);
            replicas.put(key(workload), replicaCount);
        }

        @Override
        public void waitUntilReady(WorkloadRef workload, int replicaCount, int timeoutSeconds) {
            actions.add("wait " + workload.name() + " " + replicaCount);
        }

        @Override
        public void waitPodsDeleted(String labelSelector, int timeoutSeconds) {
            actions.add("waitPods " + labelSelector);
        }

        @Override
        public void setStatefulSetEnv(String statefulSetName, String envName, String value) {
            actions.add("env " + statefulSetName + " " + envName + "=" + value);
        }

        @Override
        public void removeStatefulSetEnv(String statefulSetName, String envName) {
            actions.add("removeEnv " + statefulSetName + " " + envName);
        }

        @Override
        public void rolloutRestartStatefulSet(String statefulSetName) {
            actions.add("restart " + statefulSetName);
        }

        @Override
        public void deletePersistentVolumeClaimsContaining(String nameToken) {
            actions.add("deletePvc " + nameToken);
        }

        @Override
        public void waitForPodLog(String podName, String expectedText, int timeoutSeconds) {
            actions.add("log " + podName);
        }

        private static String key(WorkloadRef workload) {
            return workload.kind() + ":" + workload.name();
        }
    }

    private static final class FakeElasticsearch implements ElasticsearchRestoreApi {
        private final List<String> indices = new ArrayList<>();
        private final List<String> snapshots = new ArrayList<>();
        private final List<String> templates = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private final List<String> restored = new ArrayList<>();

        @Override
        public List<String> listIndices() {
            return List.copyOf(indices);
        }

        @Override
        public void deleteIndex(String index) {
            deleted.add(index);
        }

        @Override
        public List<String> listSnapshotNames(String repository) {
            return List.copyOf(snapshots);
        }

        @Override
        public void restoreSnapshot(String repository, String snapshot) {
            restored.add(snapshot);
        }

        @Override
        public List<String> listIndexTemplateNames() {
            return List.copyOf(templates);
        }
    }
}
