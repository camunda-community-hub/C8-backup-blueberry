package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.KubernetesClientProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Check that blueberry can run commands on the Kubernetes API: read the version of the cluster, and list the deployments
 * of the namespace. The restore needs it (scale the components, delete the volumes).
 */
@Component
public class RuleKubernetesAccess implements Rule {

    private final KubernetesClientProvider kubernetesClientProvider;
    private final BlueberryConfig blueberryConfig;

    RuleKubernetesAccess(KubernetesClientProvider kubernetesClientProvider, BlueberryConfig blueberryConfig) {
        this.kubernetesClientProvider = kubernetesClientProvider;
        this.blueberryConfig = blueberryConfig;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Kubernetes Access";
    }

    @Override
    public String getExplanations() {
        return "Blueberry must reach the Kubernetes API and run commands, to restore a backup";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        ruleInfo.setStatus(RuleStatus.INPROGRESS);
        String namespace = blueberryConfig.getNamespace();

        // 1. Create the client
        KubernetesClient client;
        try {
            client = kubernetesClientProvider.client();
        } catch (Exception e) {
            ruleInfo.addVerificationsWithError("Connect to the Kubernetes API", RuleStatus.FAILED,
                    "kubectl cluster-info", fixExplanation(describe(e)), fixConfiguration());
            ruleInfo.addError("Kubernetes is not accessible");
            ruleInfo.setStatus(RuleStatus.FAILED);
            return ruleInfo;
        }

        // 2. Run a command in the namespace: list the deployments. This is the real test: it is what the restore does.
        String command = "kubectl -n " + namespace + " get deployments";
        boolean accessible = false;
        try {
            int count = client.apps().deployments().inNamespace(namespace).list().getItems().size();
            ruleInfo.addVerifications("List the deployments of the namespace " + namespace + ": " + count + " found",
                    RuleStatus.CORRECT, command);
            ruleInfo.addDetails("Kubernetes API is accessible");
            ruleInfo.setStatus(RuleStatus.CORRECT);
            accessible = true;
        } catch (Exception e) {
            ruleInfo.addVerificationsWithError("List the deployments of the namespace " + namespace, RuleStatus.FAILED,
                    command, fixExplanation(describe(e)) + "\nIf the API answers, check the rights of the user or of the service account "
                            + "(get, list on deployments and statefulsets, patch and update to scale them).",
                    fixConfiguration());
            ruleInfo.addError("Kubernetes is not accessible");
            ruleInfo.setStatus(RuleStatus.FAILED);
        }

        // 3. The version of the cluster: an information only. The Kubernetes client can't read the version answer of recent
        //    clusters (new field emulationMajor), this must not make the rule fail when the commands work.
        if (accessible) {
            try {
                String version = readVersion(client);
                ruleInfo.addInfo("Read the version of the Kubernetes cluster: " + version, "kubectl version", "");
            } catch (Exception e) {
                ruleInfo.addInfo("Read the version of the Kubernetes cluster", "kubectl version",
                        "The version can't be read, it is not needed: " + describe(e));
            }
        }
        return ruleInfo;
    }

    /**
     * Nothing can be created from blueberry
     */
    @Override
    public RuleInfo configure() {
        return check();
    }

    /**
     * Read GET /version and take the field gitVersion only.
     * client.getKubernetesVersion() is not used: its class (VersionInfo) does not know the fields that recent clusters add
     * (emulationMajor, emulationMinor, minCompatibilityMajor...), and fails with an UnrecognizedPropertyException.
     * The request goes through the client: the authentication of the client is used.
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

    private String fixExplanation(String problem) {
        return "Blueberry can't connect to the Kubernetes API: " + problem + "\n"
                + "Outside the cluster, set blueberry.kubeConfig to a kubeconfig that works, and check it with: kubectl -n "
                + blueberryConfig.getNamespace() + " get deployments\n"
                + "On GKE, the kubeconfig needs gke-gcloud-auth-plugin in the PATH of the process running blueberry "
                + "(on Windows, use 'kubectl proxy --port=8001' and a kubeconfig that points to http://localhost:8001).\n"
                + "Inside the cluster, leave blueberry.kubeConfig empty and give a service account to the pod.";
    }

    /**
     * Examples of configuration, displayed in a box under the explanation
     */
    private String fixConfiguration() {
        return "# Blueberry configuration (application.yaml), outside the cluster\n"
                + "blueberry:\n"
                + "  kubeConfig: /path/to/kubeconfig\n"
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
