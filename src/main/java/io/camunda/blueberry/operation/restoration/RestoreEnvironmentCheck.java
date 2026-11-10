package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.ElasticSearchConnect;
import io.camunda.blueberry.connect.Fabric8KubernetesWorkload;
import io.camunda.blueberry.connect.KubernetesConnect;
import io.camunda.blueberry.exception.OperationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live checks before a destructive restore (Kubernetes API + Elasticsearch HTTP).
 */
@Component
public class RestoreEnvironmentCheck {

    private final BlueberryConfig blueberryConfig;
    private final KubernetesConnect kubernetesConnect;
    private final ElasticSearchConnect elasticSearchConnect;
    private final Fabric8KubernetesWorkload kubernetesWorkload;

    public RestoreEnvironmentCheck(BlueberryConfig blueberryConfig,
                                     KubernetesConnect kubernetesConnect,
                                     ElasticSearchConnect elasticSearchConnect,
                                     Fabric8KubernetesWorkload kubernetesWorkload) {
        this.blueberryConfig = blueberryConfig;
        this.kubernetesConnect = kubernetesConnect;
        this.elasticSearchConnect = elasticSearchConnect;
        this.kubernetesWorkload = kubernetesWorkload;
    }

    public Map<String, Object> readiness() {
        RestoreLayout layout = RestoreLayout.from(blueberryConfig);
        List<String> blockers = new ArrayList<>();
        Map<String, Object> workloads = new LinkedHashMap<>();

        boolean kubernetes = kubernetesConnect.isConnected();
        boolean elasticsearch = elasticSearchConnect.isConnected();
        if (!kubernetes) {
            blockers.add("Kubernetes API is not reachable.");
        }
        if (!elasticsearch) {
            blockers.add("Elasticsearch is not reachable at " + blueberryConfig.getElasticsearchUrl());
        }

        if (kubernetes) {
            for (WorkloadRef ref : List.of(layout.operate(), layout.tasklist(), layout.gateway(), layout.zeebe())) {
                try {
                    workloads.put(ref.name(), kubernetesWorkload.exists(ref));
                } catch (OperationException e) {
                    workloads.put(ref.name(), false);
                    blockers.add(e.getMessage());
                }
            }
            try {
                if (kubernetesWorkload.exists(layout.optimize())) {
                    workloads.put(layout.optimize().name(), true);
                }
            } catch (OperationException ignored) {
                // Optimize optional
            }
            for (Map.Entry<String, Object> entry : workloads.entrySet()) {
                if (Boolean.FALSE.equals(entry.getValue())) {
                    blockers.add("Missing workload " + entry.getKey() + " in namespace " + layout.namespace());
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kubernetesConnected", kubernetes);
        result.put("elasticsearchConnected", elasticsearch);
        result.put("executionEnabled", blueberryConfig.isRestoreExecutionEnabled());
        result.put("namespace", layout.namespace());
        result.put("helmRelease", layout.helmRelease());
        result.put("repositories", layout.repositories());
        result.put("workloads", workloads);
        result.put("ready", blockers.isEmpty() && blueberryConfig.isRestoreExecutionEnabled());
        result.put("blockers", blockers);
        return result;
    }
}
