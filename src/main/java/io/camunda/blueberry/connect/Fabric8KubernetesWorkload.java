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
            case STATEFUL_SET -> client().apps().statefulSets().inNamespace(namespace()).withName(workload.name()).scale(replicas);
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
        client().apps().statefulSets().inNamespace(namespace()).withName(statefulSetName).edit(statefulSet -> {
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
        });
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
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                String logs = client().pods().inNamespace(namespace()).withName(podName).getLog();
                if (logs != null && logs.contains(expectedText)) {
                    return;
                }
            } catch (Exception ignored) {
                // The broker may still be starting or crash-looping. The runbook treats that as normal.
            }
            sleep();
        }
        throw kube("Pod " + podName + " did not log \"" + expectedText + "\".");
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
        client().apps().statefulSets().inNamespace(namespace()).withName(statefulSetName).edit(statefulSet -> {
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
        });
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
