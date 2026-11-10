package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.platform.ClusterVersionReader;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The Helm chart must be 12.1.0 or later: charts before this version ignore ZEEBE_RESTORE, so Zeebe can't be restored.
 */
@Component
public class RuleHelmChartVersion implements Rule {

    private final ClusterVersionReader clusterVersionReader;

    RuleHelmChartVersion(ClusterVersionReader clusterVersionReader) {
        this.clusterVersionReader = clusterVersionReader;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Helm Chart Version";
    }

    @Override
    public String getExplanations() {
        return "The Helm chart must be " + ClusterVersionReader.MIN_HELM_CHART_FOR_ZEEBE_RESTORE
                + " or later: older charts ignore ZEEBE_RESTORE";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        String minimum = ClusterVersionReader.MIN_HELM_CHART_FOR_ZEEBE_RESTORE;
        String action = "The Helm chart version must be " + minimum + " or later";
        try {
            ClusterVersionReader.Versions versions = clusterVersionReader.read();
            if (versions.chartVersion().isBlank()) {
                ruleInfo.addVerificationsWithError(action, RuleStatus.FAILED, "helm list",
                        "The version of the Helm chart can't be read: the label helm.sh/chart of " + versions.workload()
                                + " is [" + versions.chartLabel() + "]");
                ruleInfo.addError("The Helm chart version can't be checked");
                ruleInfo.setStatus(RuleStatus.FAILED);
            } else if (ClusterVersionReader.compareVersions(versions.chartVersion(), minimum) >= 0) {
                ruleInfo.addVerifications("Helm chart " + versions.chartVersion() + " is " + minimum + " or later",
                        RuleStatus.CORRECT, "helm list");
                ruleInfo.addDetails("Helm chart " + versions.chartVersion());
                ruleInfo.setStatus(RuleStatus.CORRECT);
            } else {
                ruleInfo.addVerificationsWithError(action + ": " + versions.chartVersion(), RuleStatus.FAILED, "helm list",
                        "The Helm chart " + versions.chartVersion() + " is before " + minimum + ". "
                                + "It ignores ZEEBE_RESTORE: the restore of Zeebe does not work. Upgrade the chart "
                                + "(helm upgrade ... --version " + minimum + " or later).");
                ruleInfo.addError("Helm chart before " + minimum);
                ruleInfo.setStatus(RuleStatus.FAILED);
            }
        } catch (Exception e) {
            ruleInfo.addVerificationsWithError(action, RuleStatus.FAILED, "helm list",
                    "The version of the Helm chart can't be read: " + RuleKubernetesAccess.describe(e));
            ruleInfo.addError("The Helm chart version can't be checked");
            ruleInfo.setStatus(RuleStatus.FAILED);
        }
        return ruleInfo;
    }

    @Override
    public RuleInfo configure() {
        return check();
    }
}
