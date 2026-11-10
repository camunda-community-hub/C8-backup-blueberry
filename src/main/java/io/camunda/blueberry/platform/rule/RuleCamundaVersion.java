package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.platform.ClusterVersionReader;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read and display the version of the Helm chart and the version of Camunda, from the Kubernetes API.
 */
@Component
public class RuleCamundaVersion implements Rule {

    private final ClusterVersionReader clusterVersionReader;
    private final BlueberryConfig blueberryConfig;

    RuleCamundaVersion(ClusterVersionReader clusterVersionReader, BlueberryConfig blueberryConfig) {
        this.clusterVersionReader = clusterVersionReader;
        this.blueberryConfig = blueberryConfig;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Camunda Version";
    }

    @Override
    public String getExplanations() {
        return "Display the version of the Helm chart and the version of Camunda of the release";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        String command = "kubectl -n " + blueberryConfig.getNamespace() + " get statefulset "
                + blueberryConfig.getRestoreHelmRelease() + "-zeebe --show-labels";
        try {
            ClusterVersionReader.Versions versions = clusterVersionReader.read();

            if (versions.chartVersion().isBlank()) {
                ruleInfo.addVerificationsWithError("Read the version of the Helm chart", RuleStatus.FAILED, command,
                        "The label helm.sh/chart of " + versions.workload() + " is missing or has no version: ["
                                + versions.chartLabel() + "]");
            } else {
                ruleInfo.addVerifications("Helm chart version: " + versions.chartVersion()
                        + " (" + versions.chartLabel() + ")", RuleStatus.INFO, command);
            }
            if (versions.camundaVersion().isBlank()) {
                ruleInfo.addVerificationsWithError("Read the version of Camunda", RuleStatus.FAILED, command,
                        "The label app.kubernetes.io/version and the image tag of " + versions.workload() + " give no version");
            } else {
                ruleInfo.addVerifications("Camunda version: " + versions.camundaVersion(), RuleStatus.INFO, command);
            }

            boolean complete = !versions.chartVersion().isBlank() && !versions.camundaVersion().isBlank();
            if (complete) {
                ruleInfo.addDetails("Helm chart " + versions.chartVersion() + ", Camunda " + versions.camundaVersion());
                ruleInfo.setStatus(RuleStatus.CORRECT);
            } else {
                ruleInfo.addError("The version can't be read");
                ruleInfo.setStatus(RuleStatus.FAILED);
            }
        } catch (Exception e) {
            ruleInfo.addVerificationsWithError("Read the versions in Kubernetes", RuleStatus.FAILED, command,
                    "Can't read the labels of the StatefulSet " + blueberryConfig.getRestoreHelmRelease() + "-zeebe: "
                            + RuleKubernetesAccess.describe(e));
            ruleInfo.addError("The version can't be read");
            ruleInfo.setStatus(RuleStatus.FAILED);
        }
        return ruleInfo;
    }

    @Override
    public RuleInfo configure() {
        return check();
    }
}
