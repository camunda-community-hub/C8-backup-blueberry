package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestorationPodTest {

    private static RestoreLayout layout() {
        return new RestoreLayout("camunda", "camunda",
                new RestoreLayout.Repositories("r", "r", "r", "z"),
                WorkloadRef.deployment("camunda-operate"), WorkloadRef.deployment("camunda-tasklist"),
                WorkloadRef.deployment("camunda-optimize"), WorkloadRef.deployment("camunda-zeebe-gateway"),
                WorkloadRef.statefulSet("camunda-zeebe"), WorkloadRef.deployment("camunda-connectors"),
                "app.kubernetes.io/name=zeebe", 30, 30);
    }

    @Test
    void detectsTheSuccessAndTheRestartedContainerOfEveryBroker() throws Exception {
        OperationLog log = new OperationLog();
        Map<String, String> logsPerPod = Map.of(
                "camunda-zeebe-0", RestorationPod.SUCCESS_LOG,
                "camunda-zeebe-1", RestorationPod.NOT_EMPTY_LOG,
                "camunda-zeebe-2", RestorationPod.NOT_EMPTY_LOG);
        FakeKubernetes kubernetes = new FakeKubernetes(logsPerPod);

        new RestorationPod().restoreBrokers(kubernetes, layout(), 3, 3, log);

        // The restore is announced with the backup id, and the brokers are started
        assertTrue(kubernetes.actions.contains("env camunda-zeebe ZEEBE_RESTORE=true"));
        assertTrue(kubernetes.actions.contains("env camunda-zeebe ZEEBE_RESTORE_FROM_BACKUP_ID=3"));
        assertTrue(kubernetes.actions.contains("scale camunda-zeebe 3"));

        // The user is told which message was detected, in the order of the brokers
        List<String> messages = log.getMessages().stream().map(m -> m.message).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("camunda-zeebe-0") && m.contains("restore succeeded")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("camunda-zeebe-1") && m.contains("considered successful")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("camunda-zeebe-2") && m.contains("considered successful")));
    }

    @Test
    void failsWhenABrokerLogsNothing() {
        OperationLog log = new OperationLog();
        // The pod 1 never logs: the detection times out
        Map<String, String> logsPerPod = Map.of(
                "camunda-zeebe-0", RestorationPod.SUCCESS_LOG,
                "camunda-zeebe-2", RestorationPod.NOT_EMPTY_LOG);
        FakeKubernetes kubernetes = new FakeKubernetes(logsPerPod);

        OperationException error = assertThrows(OperationException.class,
                () -> new RestorationPod().restoreBrokers(kubernetes, layout(), 3, 3, log));

        assertTrue(error.getMessage().contains("camunda-zeebe-1"));
        assertEquals(false, error.getMessage().contains("camunda-zeebe-0"));
    }

    /**
     * Only the actions used by the restoration of the brokers are recorded
     */
    private static final class FakeKubernetes implements KubernetesWorkload {
        private final List<String> actions = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, String> logsPerPod;

        FakeKubernetes(Map<String, String> logsPerPod) {
            this.logsPerPod = logsPerPod;
        }

        @Override
        public boolean exists(WorkloadRef workload) {
            return true;
        }

        @Override
        public int replicas(WorkloadRef workload) {
            return 0;
        }

        @Override
        public void scale(WorkloadRef workload, int replicas) {
            actions.add("scale " + workload.name() + " " + replicas);
        }

        @Override
        public void waitUntilReady(WorkloadRef workload, int replicas, int timeoutSeconds) {
        }

        @Override
        public void waitPodsDeleted(String labelSelector, int timeoutSeconds) {
        }

        @Override
        public void setStatefulSetEnv(String statefulSetName, String envName, String value) {
            actions.add("env " + statefulSetName + " " + envName + "=" + value);
        }

        @Override
        public void removeStatefulSetEnv(String statefulSetName, String envName) {
        }

        @Override
        public void rolloutRestartStatefulSet(String statefulSetName) {
        }

        @Override
        public void deletePersistentVolumeClaimsContaining(String nameToken) {
        }

        @Override
        public void waitForPodLog(String podName, String expectedText, int timeoutSeconds) {
        }

        @Override
        public String waitForPodLogAny(String podName, List<String> expectedTexts, int timeoutSeconds)
                throws OperationException {
            String text = logsPerPod.get(podName);
            if (text == null) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 500,
                        "timeout", "Pod " + podName + " did not log any of " + expectedTexts);
            }
            return text;
        }
    }
}
