package io.camunda.blueberry.platform;

import io.camunda.blueberry.exception.OperationException;
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


    @Autowired
    public PlatformManager(List<Rule> rules) {
        this.listRules = rules;
    }


    /**
     * Check the system
     * Does Zeebe declare a container? A Type storage?
     * Does Elasticsearch as repository for each component for Optimize?
     *
     * @return
     */
    public List<Rule.RuleInfo> checkAllRules() throws OperationException {
        logger.info("Check all rules");
        return listRules.stream()
                .map(t -> t.check())
                .toList();
    }

    public List<Rule.RuleInfo> configure() throws OperationException {
        return listRules.stream()
                .map(t -> t.configure())
                .toList();
    }
}
