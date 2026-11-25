package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.cluster.ClusterConnect;
import io.camunda.blueberry.connect.cluster.ClusterFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The restore stops Optimize, then starts it again: Blueberry must reach the platform where Optimize runs (Kubernetes or Docker,
 * blueberry.cluster.type) and act on it. The check is skipped when Optimize is not in the cluster.
 */
@Component
public class RuleClusterAccess implements Rule {

    private final ClusterFactory clusterFactory;
    private final OptimizeConnect optimizeConnect;

    RuleClusterAccess(ClusterFactory clusterFactory, OptimizeConnect optimizeConnect) {
        this.clusterFactory = clusterFactory;
        this.optimizeConnect = optimizeConnect;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Cluster Access";
    }

    @Override
    public String getExplanations() {
        return "Blueberry must reach the cluster (Kubernetes or Docker, blueberry.cluster.type) and stop Optimize during the restore. "
                + "Needed only when Optimize is in the cluster";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        ClusterConnect cluster = clusterFactory.getCluster();

        // The type of cluster
        if (cluster == null) {
            ruleInfo.addVerificationsWithError("Cluster type: [" + clusterFactory.getConfiguredType() + "]", RuleStatus.FAILED,
                    "blueberry.cluster.type",
                    "The cluster type is unknown. Supported: " + String.join(", ", clusterFactory.getSupportedNames()),
                    "blueberry:\n  cluster:\n    type: kubernetes   # or docker");
            ruleInfo.addError("Cluster type unknown");
            ruleInfo.setStatus(RuleStatus.FAILED);
            return ruleInfo;
        }
        ruleInfo.addInfo("Cluster type: " + cluster.getName(), "blueberry.cluster.type", "");

        if (!optimizeConnect.isActive()) {
            // The cluster is used to stop Optimize: nothing to verify when Optimize is not in the cluster
            ruleInfo.addInfo("Optimize not in the cluster", "", "The cluster is used to stop Optimize during the restore: the access is not verified.");
            ruleInfo.setStatus(RuleStatus.INFO);
            return ruleInfo;
        }

        ruleInfo.setStatus(RuleStatus.INPROGRESS);
        boolean failed = false;
        for (ClusterConnect.Step step : cluster.checkAccess()) {
            String action = step.action();
            String command = step.command();
            String error = step.error();
            String configuration = step.configuration();
            switch (step.level()) {
                case CORRECT -> ruleInfo.addVerifications(action, RuleStatus.CORRECT, command);
                case INFO -> ruleInfo.addInfo(action, command, error == null ? "" : error);
                case FAILED -> {
                    failed = true;
                    ruleInfo.addVerificationsWithError(action, RuleStatus.FAILED, command, error, configuration);
                }
            }
        }
        if (failed) {
            ruleInfo.addError("The cluster (" + cluster.getName() + ") is not accessible");
            ruleInfo.setStatus(RuleStatus.FAILED);
        } else {
            ruleInfo.addDetails("The cluster (" + cluster.getName() + ") is accessible");
            ruleInfo.setStatus(RuleStatus.CORRECT);
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
}
