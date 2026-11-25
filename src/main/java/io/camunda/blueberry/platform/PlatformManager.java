package io.camunda.blueberry.platform;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.rule.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class PlatformManager {
    private final OrchestrationParameter orchestrationParameter;
    Logger logger = LoggerFactory.getLogger(PlatformManager.class);
    List<Rule> listRules;


    @Autowired
    public PlatformManager(List<Rule> rules, OrchestrationParameter orchestrationParameter) {
        this.listRules = rules;
        this.orchestrationParameter = orchestrationParameter;
    }


    /**
     * The rules, always in the same order: by name (then by class, so two rules with the same name keep a stable order).
     * The list of the checkup page, the checkup and the configuration use this order.
     */
    public List<Rule> getOrderedRules() {
        return listRules.stream()
                .sorted(Comparator.comparing(Rule::getName).thenComparing(rule -> rule.getClass().getName()))
                .toList();
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
        return getOrderedRules().stream()
                .map(t -> t.check())
                .toList();
    }

    public List<Rule.RuleInfo> configure() throws OperationException {
        orchestrationParameter.refresh();
        return getOrderedRules().stream()
                .map(t -> t.configure())
                .toList();
    }
}
