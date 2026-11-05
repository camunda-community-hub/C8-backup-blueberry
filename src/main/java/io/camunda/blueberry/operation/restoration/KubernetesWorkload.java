package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;

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
}
