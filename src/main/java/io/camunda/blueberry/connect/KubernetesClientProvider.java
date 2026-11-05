package io.camunda.blueberry.connect;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.OperationException;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

/**
 * Shared Fabric8 client for Kubernetes API calls (restore, exploration, configuration rules).
 */
@Component
public class KubernetesClientProvider {

    private final BlueberryConfig blueberryConfig;
    private KubernetesClient client;
    private final Logger logger = LoggerFactory.getLogger(KubernetesClientProvider.class);

    public KubernetesClientProvider(BlueberryConfig blueberryConfig) {
        this.blueberryConfig = blueberryConfig;
    }

    public synchronized KubernetesClient client() throws OperationException {
        if (client != null) {
            return client;
        }
        try {
            Config config = buildFabric8Config();
            client = new KubernetesClientBuilder().withConfig(config).build();
            return client;
        } catch (Exception e) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 500,
                    "Kubernetes connection failed", e.getMessage());
        }
    }

    private Config buildFabric8Config() throws Exception {
        String kubeConfigPath = blueberryConfig.getKubeConfig();
        String kubeContext = blueberryConfig.getKubeContext();

        if (kubeConfigPath != null) {
            File configFile = Path.of(kubeConfigPath).toFile();
            if (!configFile.isFile()) {
                throw new IllegalArgumentException("blueberry.kubeConfig is not a file: " + kubeConfigPath);
            }
            if (kubeContext != null) {
                logger.warn(
                        "blueberry.kubeContext is ignored when blueberry.kubeConfig is set; "
                                + "set current-context in that file or unset kubeConfig and use kubeContext only.");
            }
            logger.info("Kubernetes client from kubeconfig {}", configFile.getAbsolutePath());
            return Config.fromKubeconfig(configFile);
        }

        if (kubeContext != null) {
            logger.info("Kubernetes client from default kubeconfig, context {}", kubeContext);
            return Config.autoConfigure(kubeContext);
        }

        logger.info("Kubernetes client from default kubeconfig / in-cluster config");
        return Config.autoConfigure(null);
    }

    public boolean ping() {
        try {
            client().getKubernetesVersion();
            return true;
        } catch (Exception e) {
            logger.debug("Kubernetes ping failed: {}", e.getMessage());
            if (looksLikeGkeAuthFailure(e)) {
                logger.warn(
                        "Kubernetes auth may have failed for a GKE context. Refresh with "
                                + "`gcloud auth login`, ensure `gke-gcloud-auth-plugin` is on PATH for this process, "
                                + "then `kubectl get nodes`. For local clusters, set blueberry.kubeContext "
                                + "(e.g. kind-camunda-platform-local). Details: {}", e.getMessage());
            }
            return false;
        }
    }

    private static boolean looksLikeGkeAuthFailure(Throwable e) {
        while (e != null) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("gke-gcloud-auth-plugin") || msg.contains("ExecCredential"))) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
