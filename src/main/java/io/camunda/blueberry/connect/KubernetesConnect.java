package io.camunda.blueberry.connect;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.restoration.RestoreLayout;
import io.camunda.blueberry.operation.restoration.WorkloadRef;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class KubernetesConnect {

    private static final List<String> OPERATE_REPO_ENV = List.of(
            "CAMUNDA_OPERATE_BACKUP_REPOSITORYNAME",
            "CAMUNDA_OPERATE_BACKUP_REPOSITORY_NAME");
    private static final List<String> TASKLIST_REPO_ENV = List.of(
            "CAMUNDA_TASKLIST_BACKUP_REPOSITORYNAME",
            "CAMUNDA_TASKLIST_BACKUP_REPOSITORY_NAME");
    private static final List<String> OPTIMIZE_REPO_ENV = List.of(
            "CAMUNDA_OPTIMIZE_BACKUP_REPOSITORYNAME",
            "CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME",
            "CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY");

    private final BlueberryConfig blueberryConfig;
    private final KubernetesClientProvider clientProvider;
    private final Fabric8KubernetesWorkload kubernetesWorkload;
    Logger logger = LoggerFactory.getLogger(KubernetesConnect.class);

    KubernetesConnect(BlueberryConfig blueberryConfig,
                      KubernetesClientProvider clientProvider,
                      Fabric8KubernetesWorkload kubernetesWorkload) {
        this.blueberryConfig = blueberryConfig;
        this.clientProvider = clientProvider;
        this.kubernetesWorkload = kubernetesWorkload;
    }

    public OperationResult connection() {
        OperationResult result = new OperationResult();
        result.success = isConnected();
        result.details = result.success
                ? "Connected to Kubernetes API"
                : "Cannot reach Kubernetes API. Set blueberry.kubeConfig or run Blueberry in-cluster.";
        return result;
    }

    public boolean isConnected() {
        return clientProvider.ping();
    }

    public OperationResult existComponent(CamundaApplicationInt.COMPONENT component, String nameSpace) {
        OperationResult operationResult = new OperationResult();
        operationResult.command = "kubectl get deploy,sts -n " + nameSpace;
        try {
            WorkloadRef workload = workloadFor(component);
            if (workload == null) {
                operationResult.success = false;
                operationResult.resultBoolean = false;
                operationResult.details = "No Kubernetes workload mapping for " + component;
                return operationResult;
            }
            boolean exists = kubernetesWorkload.exists(workload);
            operationResult.success = true;
            operationResult.resultBoolean = exists;
            operationResult.details = exists
                    ? workload.name() + " exists"
                    : workload.name() + " not found in namespace " + nameSpace;
            return operationResult;
        } catch (OperationException e) {
            operationResult.success = false;
            operationResult.resultBoolean = false;
            operationResult.details = e.getMessage();
            return operationResult;
        }
    }

    public OperationResult getRepositoryName(CamundaApplicationInt.COMPONENT component, String nameSpace) {
        OperationResult operationResult = new OperationResult();
        RestoreLayout layout = RestoreLayout.from(blueberryConfig);
        String deploymentName = switch (component) {
            case OPERATE -> layout.operate().name();
            case TASKLIST -> layout.tasklist().name();
            case OPTIMIZE -> layout.optimize().name();
            default -> null;
        };
        operationResult.command = deploymentName == null
                ? "n/a"
                : "kubectl get deploy " + deploymentName + " -n " + nameSpace + " -o jsonpath={.spec.template.spec.containers[*].env}";

        List<String> envNames = switch (component) {
            case OPERATE -> OPERATE_REPO_ENV;
            case TASKLIST -> TASKLIST_REPO_ENV;
            case OPTIMIZE -> OPTIMIZE_REPO_ENV;
            default -> List.of();
        };

        if (deploymentName == null || envNames.isEmpty()) {
            operationResult.success = false;
            operationResult.details = "Repository env lookup is not defined for " + component;
            return operationResult;
        }

        try {
            Optional<String> fromCluster = readRepositoryFromDeployment(nameSpace, deploymentName, envNames);
            if (fromCluster.isPresent()) {
                operationResult.resultSt = fromCluster.get();
                operationResult.success = true;
                operationResult.details = "Read from Deployment " + deploymentName;
                return operationResult;
            }
        } catch (Exception e) {
            logger.warn("Could not read repository from Deployment {}: {}", deploymentName, e.getMessage());
        }

        operationResult.resultSt = fallbackRepository(component);
        operationResult.success = operationResult.resultSt != null && !operationResult.resultSt.isBlank();
        operationResult.details = operationResult.success
                ? "Fallback to application.yaml (Deployment env not found)"
                : "No repository in cluster or config";
        return operationResult;
    }

    public String getCurrentNamespace() {
        return blueberryConfig.getNamespace();
    }

    private Optional<String> readRepositoryFromDeployment(String namespace, String deploymentName, List<String> envNames)
            throws OperationException {
        Deployment deployment = clientProvider.client().apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName)
                .get();
        if (deployment == null || deployment.getSpec() == null
                || deployment.getSpec().getTemplate().getSpec() == null) {
            return Optional.empty();
        }
        for (Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
            if (container.getEnv() == null) {
                continue;
            }
            for (EnvVar envVar : container.getEnv()) {
                if (envVar.getName() == null || envVar.getValue() == null) {
                    continue;
                }
                for (String expected : envNames) {
                    if (expected.equalsIgnoreCase(envVar.getName())) {
                        return Optional.of(envVar.getValue());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String fallbackRepository(CamundaApplicationInt.COMPONENT component) {
        return switch (component) {
            case OPERATE -> blueberryConfig.getOperateRepository();
            case TASKLIST -> blueberryConfig.getTasklistRepository();
            case OPTIMIZE -> blueberryConfig.getOptimizeRepository();
            default -> null;
        };
    }

    private WorkloadRef workloadFor(CamundaApplicationInt.COMPONENT component) {
        RestoreLayout layout = RestoreLayout.from(blueberryConfig);
        return switch (component) {
            case OPERATE -> layout.operate();
            case TASKLIST -> layout.tasklist();
            case OPTIMIZE -> layout.optimize();
            case ZEEBE -> layout.zeebe();
            default -> null;
        };
    }
}
