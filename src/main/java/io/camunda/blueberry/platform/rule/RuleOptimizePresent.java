package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.secondary.ElasticSearchConnect;
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
 * When Optimize is present, Elasticsearch must be present too (Optimize reads the Zeebe records in it, even when the secondary storage
 * is a database): a verification checks it, FAILED if Elasticsearch is not there.
 * Each verification gives the value in [] and its origin in ().
 */
@Component
public class RuleOptimizePresent implements Rule {
    private final BlueberryConfig blueberryConfig;
    private final OptimizeConnect optimizeConnect;
    private final ElasticSearchConnect elasticSearchConnect;

    RuleOptimizePresent(BlueberryConfig blueberryConfig, OptimizeConnect optimizeConnect, ElasticSearchConnect elasticSearchConnect) {
        this.blueberryConfig = blueberryConfig;
        this.optimizeConnect = optimizeConnect;
        this.elasticSearchConnect = elasticSearchConnect;
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

        // Optimize is in the cluster: Elasticsearch must be there too
        if (optimizeConnect.isActive()) {
            checkElasticsearch(ruleInfo);
        }
        return ruleInfo;
    }

    /**
     * Optimize reads the Zeebe records in Elasticsearch, even when the secondary storage is a database. The cluster answers first (Elasticsearch
     * answers on its URL), then the configuration (blueberry.elasticsearch.enabled)
     */
    private void checkElasticsearch(RuleInfo ruleInfo) {
        String url = blueberryConfig.getElasticsearchUrl();
        if (elasticSearchConnect.isConnected()) {
            ruleInfo.addVerifications("Elasticsearch (needed by Optimize) answers on [" + url + "] (actuator)", RuleStatus.CORRECT, url);
            return;
        }
        String problem;
        if (elasticSearchConnect.isActive()) {
            ruleInfo.addVerifications("Elasticsearch is enabled [true] (config) but does not answer on [" + url + "] (actuator)", RuleStatus.FAILED, url);
            problem = "Optimize needs Elasticsearch for the snapshot of the Zeebe records, and it does not answer on [" + url + "]";
        } else {
            ruleInfo.addVerifications("Elasticsearch is not present: [" + url + "] (config)", RuleStatus.FAILED, url);
            problem = "Optimize needs Elasticsearch, even when the secondary storage is a database: set blueberry.elasticsearch.enabled and blueberry.elasticsearchurl";
        }
        ruleInfo.addError(problem);
        ruleInfo.setStatus(RuleStatus.FAILED);
    }

    @Override
    public RuleInfo configure() {
        return check();
    }
}
