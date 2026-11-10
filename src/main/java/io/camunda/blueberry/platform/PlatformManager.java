package io.camunda.blueberry.platform;

import io.camunda.blueberry.connect.KubernetesConnect;
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


    KubernetesConnect kubernetesConnect;


    @Autowired
    public PlatformManager(List<Rule> rules, KubernetesConnect kubernetesConnect) {
        this.listRules = rules;
        this.kubernetesConnect = kubernetesConnect;
    }


    /**
     * Check the system
     * Does Zeebe declare a container? A Type storage?
     * Does Elasticsearch as repository for each component like OperateConnect?
     *
     * @return
     */
    public List<Rule.RuleInfo> checkAllRules() throws OperationException {

        // Kubernetes is optional for the rules: a failure is logged, the rules still run
        tryKubernetesConnection();

        logger.info("Check all rules");
        return listRules.stream()
                .map(t -> t.check())
                .toList();
    }

    public List<Rule.RuleInfo> configure() throws OperationException {

        // Kubernetes is optional for the rules: a failure is logged, the rules still run
        tryKubernetesConnection();

        return listRules.stream()
                .map(t -> t.configure())
                .toList();
    }

    /**
     * Try to connect to Kubernetes. Never throws: the rules (Elasticsearch, Zeebe, actuator) do not need it.
     */
    private void tryKubernetesConnection() {
        try {
            if (!kubernetesConnect.isConnected())
                kubernetesConnect.connection();
        } catch (Exception e) {
            logger.warn("Kubernetes is not reachable, rules run without it: {}", e.getMessage());
        }
    }
}
