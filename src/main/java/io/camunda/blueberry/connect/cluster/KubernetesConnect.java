package io.camunda.blueberry.connect.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes implementation: Optimize is a Deployment, stopped and started by a scale.
 * The Deployment is blueberry.cluster.kubernetes.optimizeDeployment, else the Helm release + "-optimize"
 * (blueberry.cluster.kubernetes.helmRelease).
 */
@Component
public class KubernetesConnect implements ClusterConnect {
    public static final String NAME = "kubernetes";
    private static final BackupComponentInt.COMPONENT OPTIMIZE = BackupComponentInt.COMPONENT.OPTIMIZE;

    private final BlueberryConfig blueberryConfig;
    private final KubernetesClientProvider clientProvider;
    private final KubernetesWorkload kubernetesWorkload;

    public KubernetesConnect(BlueberryConfig blueberryConfig, KubernetesClientProvider clientProvider, KubernetesWorkload kubernetesWorkload) {
        this.blueberryConfig = blueberryConfig;
        this.clientProvider = clientProvider;
        this.kubernetesWorkload = kubernetesWorkload;
    }

    /**
     * Read GET /version and take the field gitVersion only.
     * client.getKubernetesVersion() is not used: its class (VersionInfo) does not know the fields that recent clusters add
     * (emulationMajor, emulationMinor, minCompatibilityMajor...), and fails with an UnrecognizedPropertyException.
     */
    private static String readVersion(KubernetesClient client) throws Exception {
        String base = client.getMasterUrl().toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        HttpRequest request = client.getHttpClient().newHttpRequestBuilder().uri(base + "version").build();
        HttpResponse<String> response = client.getHttpClient().sendAsync(request, String.class).get(15, TimeUnit.SECONDS);
        if (!response.isSuccessful()) {
            throw new IllegalStateException("GET " + base + "version answered " + response.code());
        }
        return new ObjectMapper().readTree(response.body()).path("gitVersion").asText("");
    }

    /* ******************************************************************** */
    /*  Check                                                               */
    /* ******************************************************************** */

    /**
     * The Kubernetes client often gives a message like "An error has occurred.": the real reason is in the causes.
     * Return the message of each cause, from the exception to the root cause.
     */
    static String describe(Throwable throwable) {
        StringBuilder message = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (message.length() > 0) {
                message.append(" <- ");
            }
            message.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return message.toString();
    }

    /* ******************************************************************** */
    /*  Stop / start                                                        */
    /* ******************************************************************** */

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<Step> checkAccess() {
        List<Step> steps = new ArrayList<>();
        String namespace = blueberryConfig.getNamespace();

        // 1. Create the client
        KubernetesClient client;
        try {
            client = clientProvider.client();
        } catch (Exception e) {
            steps.add(Step.failed("Connect to the Kubernetes API", "kubectl cluster-info", fixExplanation(describe(e)), fixConfiguration()));
            return steps;
        }

        // 2. Run a command in the namespace: list the deployments. This is the real test: it is what the restore does.
        String command = "kubectl -n " + namespace + " get deployments";
        try {
            int count = client.apps().deployments().inNamespace(namespace).list().getItems().size();
            steps.add(Step.correct("List the deployments of the namespace " + namespace + ": " + count + " found", command));
        } catch (Exception e) {
            steps.add(Step.failed("List the deployments of the namespace " + namespace, command,
                    fixExplanation(describe(e)) + "\nIf the API answers, check the rights of the user or of the service account "
                            + "(get, list on deployments, patch or update on deployments/scale).",
                    fixConfiguration()));
            return steps;
        }

        // 3. The version of the cluster: an information only. The Kubernetes client can't read the version answer of recent
        //    clusters (new field emulationMajor), this must not make the check fail when the commands work.
        try {
            steps.add(Step.info("Read the version of the Kubernetes cluster: " + readVersion(client), "kubectl version", ""));
        } catch (Exception e) {
            steps.add(Step.info("Read the version of the Kubernetes cluster", "kubectl version",
                    "The version can't be read, it is not needed: " + describe(e)));
        }
        return steps;
    }

    @Override
    public int stopOptimize(OperationLog log) throws OperationException {
        WorkloadRef workload = workload();
        if (!kubernetesWorkload.exists(workload)) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 404, "Optimize Deployment not found",
                    "The Deployment [" + workload.name() + "] does not exist in the namespace [" + blueberryConfig.getNamespace()
                            + "]. Set blueberry.cluster.kubernetes.optimizeDeployment (or blueberry.cluster.kubernetes.helmRelease) "
                            + "and blueberry.cluster.kubernetes.namespace.");
        }
        int replicas = kubernetesWorkload.replicas(workload);
        if (replicas == 0) {
            log.info(OPTIMIZE, "Optimize is already stopped (Deployment " + workload.name() + " has 0 replica)");
            return 0;
        }
        log.info(OPTIMIZE, "Scale down the Deployment " + workload.name() + " from " + replicas + " to 0 replica"
                + " (kubectl -n " + blueberryConfig.getNamespace() + " scale deployment " + workload.name() + " --replicas=0)");
        kubernetesWorkload.scale(workload, 0);
        kubernetesWorkload.waitUntilReady(workload, 0, blueberryConfig.getKubernetesPodDeleteTimeoutSeconds());
        log.info(OPTIMIZE, "Optimize is stopped");
        return replicas;
    }

    /* ******************************************************************** */
    /*  Tools                                                               */
    /* ******************************************************************** */

    @Override
    public void startOptimize(int replicas, OperationLog log) throws OperationException {
        if (replicas <= 0) {
            return;
        }
        WorkloadRef workload = workload();
        log.info(OPTIMIZE, "Scale up the Deployment " + workload.name() + " to " + replicas + " replica"
                + " (kubectl -n " + blueberryConfig.getNamespace() + " scale deployment " + workload.name() + " --replicas=" + replicas + ")");
        kubernetesWorkload.scale(workload, replicas);
        kubernetesWorkload.waitUntilReady(workload, replicas, blueberryConfig.getKubernetesPodDeleteTimeoutSeconds());
        log.info(OPTIMIZE, "Optimize is started");
    }

    private WorkloadRef workload() {
        return WorkloadRef.deployment(blueberryConfig.getKubernetesOptimizeDeployment());
    }

    private String fixExplanation(String problem) {
        return "Blueberry can't connect to the Kubernetes API: " + problem + "\n"
                + "Outside the cluster, set blueberry.cluster.kubernetes.kubeConfig to a kubeconfig that works, and check it with: kubectl -n "
                + blueberryConfig.getNamespace() + " get deployments\n"
                + "On GKE, the kubeconfig needs gke-gcloud-auth-plugin in the PATH of the process running blueberry "
                + "(on Windows, use 'kubectl proxy --port=8001' and a kubeconfig that points to http://localhost:8001).\n"
                + "Inside the cluster, leave blueberry.cluster.kubernetes.kubeConfig empty and give a service account to the pod.";
    }

    /**
     * Examples of configuration, displayed in a box under the explanation
     */
    private String fixConfiguration() {
        return "# Blueberry configuration (application.yaml), outside the cluster\n"
                + "blueberry:\n"
                + "  cluster:\n"
                + "    type: kubernetes\n"
                + "    kubernetes:\n"
                + "      kubeConfig: /path/to/kubeconfig\n"
                + "\n"
                + "# kubeconfig to use with 'kubectl proxy --port=8001' (kube-proxy.yaml)\n"
                + "apiVersion: v1\n"
                + "kind: Config\n"
                + "clusters:\n"
                + "  - name: proxy\n"
                + "    cluster:\n"
                + "      server: http://localhost:8001\n"
                + "contexts:\n"
                + "  - name: proxy\n"
                + "    context:\n"
                + "      cluster: proxy\n"
                + "      user: proxy\n"
                + "current-context: proxy\n"
                + "users:\n"
                + "  - name: proxy\n"
                + "    user: {}";
    }
}

