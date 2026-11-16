package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;

import java.util.List;

/**
 * Kubernetes actions used by the 8.7 restore procedure.
 */
public interface KubernetesWorkload {

    boolean exists(WorkloadRef workload) throws OperationException;

    int replicas(WorkloadRef workload) throws OperationException;

    void scale(WorkloadRef workload, int replicas) throws OperationException;

    void waitUntilReady(WorkloadRef workload, int replicas, int timeoutSeconds) throws OperationException;

    void waitPodsDeleted(String labelSelector, int timeoutSeconds) throws OperationException;

    void setStatefulSetEnv(String statefulSetName, String envName, String value) throws OperationException;

    void removeStatefulSetEnv(String statefulSetName, String envName) throws OperationException;

    void rolloutRestartStatefulSet(String statefulSetName) throws OperationException;

    void deletePersistentVolumeClaimsContaining(String nameToken) throws OperationException;

    void waitForPodLog(String podName, String expectedText, int timeoutSeconds) throws OperationException;

    /**
     * Wait until the log of the pod (the current run of the container, or the previous one) contains one of the texts.
     *
     * @return the text found
     */
    default String waitForPodLogAny(String podName, List<String> expectedTexts, int timeoutSeconds) throws OperationException {
        waitForPodLog(podName, expectedTexts.get(0), timeoutSeconds);
        return expectedTexts.get(0);
    }
}
