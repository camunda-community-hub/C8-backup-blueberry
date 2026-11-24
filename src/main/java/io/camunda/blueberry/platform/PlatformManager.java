package io.camunda.blueberry.platform;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.rule.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlatformManager {
    Logger logger = LoggerFactory.getLogger(PlatformManager.class);


    List<Rule> listRules;

    private final OrchestrationParameter orchestrationParameter;


    @Autowired
    public PlatformManager(List<Rule> rules, OrchestrationParameter orchestrationParameter) {
        this.listRules = rules;
        this.orchestrationParameter = orchestrationParameter;
    }


    /**
     * Check the system
     * Does Zeebe declare a container? A Type storage?
     * Does Elasticsearch as repository for each component for Optimize?
     *
     * @return
     */
    public List<Rule.RuleInfo> checkAllRules() throws OperationException {
        // The rules read the parameters of the cluster (cached): read them again, the cluster may have changed
        orchestrationParameter.refresh();
        logger.info("Check all rules");
        return listRules.stream()
                .map(t -> t.check())
                .toList();
    }

    public List<Rule.RuleInfo> configure() throws OperationException {
        orchestrationParameter.refresh();
        return listRules.stream()
                .map(t -> t.configure())
                .toList();
    }
}
