package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OptimizeConnect;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Is Optimize part of the cluster? Optimize is optional: without it, there is nothing to back up for it.
 * The cluster answers first (Optimize answers on its actuator, blueberry.optimizeActuatorUrl), even if blueberry.optimize.enabled
 * is false. If it does not answer, the configuration (blueberry.optimize.enabled) is used.
 * <ul>
 *     <li>CORRECT, with a correct verification: Optimize answers on its actuator</li>
 *     <li>CORRECT, with an information: no URL to ask, or Optimize does not answer and blueberry.optimize.enabled is false</li>
 *     <li>FAILED: blueberry.optimize.enabled is true, but Optimize does not answer</li>
 * </ul>
 * Each verification gives the value in [] and its origin in ().
 */
@Component
public class RuleOptimizePresent implements Rule {
    private final BlueberryConfig blueberryConfig;
    private final OptimizeConnect optimizeConnect;

    RuleOptimizePresent(BlueberryConfig blueberryConfig, OptimizeConnect optimizeConnect) {
        this.blueberryConfig = blueberryConfig;
        this.optimizeConnect = optimizeConnect;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Optimise is present";
    }

    @Override
    public String getExplanations() {
        return "Optimize is an optional component of the cluster. Blueberry asks the cluster first: if Optimize answers on its actuator, "
                + "it is present, whatever blueberry.optimize.enabled says. If it does not answer, blueberry.optimize.enabled decides. "
                + "When Optimize is present, Blueberry backs it up and checks its repository. When it is not, Blueberry ignores it.";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of("https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/optimize-backup/");
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        String optimizeUrl = blueberryConfig.getOptimizeActuatorUrl();
        String actuator = optimizeUrl + "/actuator";

        if (!optimizeConnect.hasActuatorUrl()) {
            ruleInfo.addInfo("Optimize URL (blueberry.optimizeActuatorUrl): [" + optimizeUrl + "] (config)", null,
                    "There is no URL to ask Optimize (it is empty, or it is the URL of Zeebe): Optimize is not in the cluster");
            ruleInfo.setStatus(RuleStatus.CORRECT);
        } else if (optimizeConnect.isReachable()) {
            ruleInfo.addVerifications("Optimize answers on [" + actuator + "] (actuator)", RuleStatus.CORRECT, actuator);
            ruleInfo.setStatus(RuleStatus.CORRECT);
        } else if (blueberryConfig.isOptimizeEnabled()) {
            ruleInfo.addVerifications("Optimize is enabled [true] (config) but does not answer on [" + actuator + "] (actuator)",
                    RuleStatus.FAILED, actuator);
            ruleInfo.setStatus(RuleStatus.FAILED);
        } else {
            ruleInfo.addInfo("Optimize does not answer on [" + actuator + "] (actuator), and is not enabled [false] (config)", actuator,
                    "Optimize is not in the cluster");
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }

    @Override
    public RuleInfo configure() {
        return check();
    }
}
