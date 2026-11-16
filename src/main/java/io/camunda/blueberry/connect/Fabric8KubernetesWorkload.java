package io.camunda.blueberry.connect;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.restoration.KubernetesWorkload;
import io.camunda.blueberry.operation.restoration.WorkloadRef;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric8 implementation of the restore workload actions. The client is created on the first call.
 */
@Component
public class Fabric8KubernetesWorkload implements KubernetesWorkload {

    private final BlueberryConfig blueberryConfig;
    private final KubernetesClientProvider clientProvider;

    public Fabric8KubernetesWorkload(BlueberryConfig blueberryConfig, KubernetesClientProvider clientProvider) {
        this.blueberryConfig = blueberryConfig;
        this.clientProvider = clientProvider;
    }

    @Override
    public boolean exists(WorkloadRef workload) throws OperationException {
        return switch (workload.kind()) {
            case DEPLOYMENT -> client().apps().deployments().inNamespace(namespace()).withName(workload.name()).get() != null;
            case STATEFUL_SET -> client().apps().statefulSets().inNamespace(namespace()).withName(workload.name()).get() != null;
        };
    }

    @Override
    public int replicas(WorkloadRef workload) throws OperationException {
        Integer replicas = switch (workload.kind()) {
            case DEPLOYMENT -> {
                var deployment = client().apps().deployments().inNamespace(namespace()).withName(workload.name()).get();
                yield deployment == null || deployment.getSpec() == null ? null : deployment.getSpec().getReplicas();
            }
            case STATEFUL_SET -> {
                var statefulSet = client().apps().statefulSets().inNamespace(namespace()).withName(workload.name()).get();
                yield statefulSet == null || statefulSet.getSpec() == null ? null : statefulSet.getSpec().getReplicas();
            }
        };
        if (replicas == null) {
            throw missing(workload.name());
        }
        return replicas;
    }

    @Override
    public void scale(WorkloadRef workload, int replicas) throws OperationException {
        switch (workload.kind()) {
            case DEPLOYMENT -> client().apps().deployments().inNamespace(namespace()).withName(workload.name()).scale(replicas);
            case STATEFUL_SET -> retryOnConflict(() -> client().apps().statefulSets().inNamespace(namespace()).withName(workload.name()).scale(replicas));
        }
    }

    @Override
    public void waitUntilReady(WorkloadRef workload, int replicas, int timeoutSeconds) throws OperationException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            int ready = readyReplicas(workload);
            int spec = replicas(workload);
            if (replicas == 0) {
                if (spec == 0 && ready == 0) {
                    return;
                }
            } else if (spec == replicas && ready >= replicas) {
                return;
            }
            sleep();
        }
        throw kube("Timed out waiting for " + workload.name() + " to reach " + replicas + " ready replicas.");
    }

    @Override
    public void waitPodsDeleted(String labelSelector, int timeoutSeconds) throws OperationException {
        String[] parts = labelSelector.split("=", 2);
        if (parts.length != 2) {
            throw kube("Pod label selector must be key=value. Received " + labelSelector);
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Pod> pods = client().pods().inNamespace(namespace()).withLabel(parts[0], parts[1]).list().getItems();
            if (pods == null || pods.isEmpty()) {
                return;
            }
            sleep();
        }
        throw kube("Timed out waiting for pods with label " + labelSelector + " to be deleted.");
    }

    @Override
    public void setStatefulSetEnv(String statefulSetName, String envName, String value) throws OperationException {
        editZeebeEnv(statefulSetName, envName, value, false);
    }

    @Override
    public void removeStatefulSetEnv(String statefulSetName, String envName) throws OperationException {
        editZeebeEnv(statefulSetName, envName, null, true);
    }

    @Override
    public void rolloutRestartStatefulSet(String statefulSetName) throws OperationException {
        String restartedAt = Instant.now().toString();
        retryOnConflict(() -> client().apps().statefulSets().inNamespace(namespace()).withName(statefulSetName).edit(statefulSet -> {
            if (statefulSet.getSpec().getTemplate().getMetadata() == null) {
                statefulSet.getSpec().getTemplate().setMetadata(new ObjectMeta());
            }
            Map<String, String> annotations = statefulSet.getSpec().getTemplate().getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new HashMap<>();
                statefulSet.getSpec().getTemplate().getMetadata().setAnnotations(annotations);
            }
            annotations.put("kubectl.kubernetes.io/restartedAt", restartedAt);
            return statefulSet;
        }));
    }

    @Override
    public void deletePersistentVolumeClaimsContaining(String nameToken) throws OperationException {
        List<PersistentVolumeClaim> claims = client().persistentVolumeClaims().inNamespace(namespace()).list().getItems();
        if (claims == null) {
            return;
        }
        for (PersistentVolumeClaim claim : claims) {
            String name = claim.getMetadata().getName();
            if (name != null && name.contains(nameToken)) {
                client().persistentVolumeClaims().inNamespace(namespace()).withName(name).delete();
            }
        }
    }

    @Override
    public void waitForPodLog(String podName, String expectedText, int timeoutSeconds) throws OperationException {
        waitForPodLogAny(podName, List.of(expectedText), timeoutSeconds);
    }

    /**
     * The restore of Zeebe runs in the main container, then the container exits and Kubernetes starts it again.
     * The log of the first run is lost after a restart: look at the current run and at the previous one.
     */
    @Override
    public String waitForPodLogAny(String podName, List<String> expectedTexts, int timeoutSeconds) throws OperationException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String logs : readLogs(podName)) {
                for (String expectedText : expectedTexts) {
                    if (logs.contains(expectedText)) {
                        return expectedText;
                    }
                }
            }
            sleep();
        }
        throw kube("Pod " + podName + " did not log any of " + expectedTexts + ".");
    }

    /**
     * The log of the current run of the container, then the log of the previous run. A log that can't be read is ignored:
     * the broker may be starting, or crash-looping, this is normal.
     */
    private List<String> readLogs(String podName) {
        List<String> logs = new ArrayList<>();
        try {
            String current = client().pods().inNamespace(namespace()).withName(podName).getLog();
            if (current != null) {
                logs.add(current);
            }
        } catch (Exception ignored) {
            // Container not started
        }
        try {
            String previous = client().pods().inNamespace(namespace()).withName(podName).terminated().getLog();
            if (previous != null) {
                logs.add(previous);
            }
        } catch (Exception ignored) {
            // No previous run yet
        }
        return logs;
    }

    private int readyReplicas(WorkloadRef workload) throws OperationException {
        Integer ready = switch (workload.kind()) {
            case DEPLOYMENT -> {
                var deployment = client().apps().deployments().inNamespace(namespace()).withName(workload.name()).get();
                yield deployment == null || deployment.getStatus() == null ? null : deployment.getStatus().getReadyReplicas();
            }
            case STATEFUL_SET -> {
                var statefulSet = client().apps().statefulSets().inNamespace(namespace()).withName(workload.name()).get();
                yield statefulSet == null || statefulSet.getStatus() == null ? null : statefulSet.getStatus().getReadyReplicas();
            }
        };
        return ready == null ? 0 : ready;
    }

    private void editZeebeEnv(String statefulSetName, String envName, String value, boolean remove) throws OperationException {
        retryOnConflict(() -> client().apps().statefulSets().inNamespace(namespace()).withName(statefulSetName).edit(statefulSet -> {
            Container container = zeebeContainer(statefulSet);
            List<EnvVar> env = container.getEnv();
            if (env == null) {
                env = new ArrayList<>();
                container.setEnv(env);
            }
            env.removeIf(var -> envName.equals(var.getName()));
            if (!remove) {
                env.add(new EnvVarBuilder().withName(envName).withValue(value).build());
            }
            return statefulSet;
        }));
    }

    /**
     * The StatefulSet is updated by its controller (status, pods) while Blueberry modifies it: Kubernetes then answers
     * 409 "the object has been modified; please apply your changes to the latest version". The edit is a
     * read-modify-write, so it is simply done again on the latest version.
     */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws OperationException;
    }

    private void retryOnConflict(ThrowingRunnable operation) throws OperationException {
        int attempt = 0;
        while (true) {
            try {
                operation.run();
                return;
            } catch (KubernetesClientException e) {
                attempt++;
                if (e.getCode() != 409 || attempt >= 10) {
                    throw e;
                }
                sleep();
            }
        }
    }

    private static Container zeebeContainer(StatefulSet statefulSet) {
        List<Container> containers = statefulSet.getSpec().getTemplate().getSpec().getContainers();
        return containers.stream()
                .filter(container -> "zeebe".equals(container.getName()))
                .findFirst()
                .orElse(containers.get(0));
    }

    private KubernetesClient client() throws OperationException {
        return clientProvider.client();
    }

    private String namespace() {
        return blueberryConfig.getNamespace();
    }

    private static void sleep() throws OperationException {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw kube("Restore wait was interrupted.");
        }
    }

    private static OperationException missing(String name) {
        return kube("Kubernetes workload " + name + " was not found.");
    }

    private static OperationException kube(String message) {
        return new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 500,
                "Kubernetes restore failed", message);
    }
}
