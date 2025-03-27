package io.camunda.blueberry.platform;

import io.camunda.blueberry.connect.KubernetesConnect;
import io.camunda.blueberry.platform.rule.Rule;
import io.camunda.blueberry.exception.OperationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlatformManager {


    List<Rule> listRules;


    KubernetesConnect kubernetesConnect;


    @Autowired
    public PlatformManager(List<Rule> rules, KubernetesConnect kubernetesConnect)
    {
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

        // Check the connection
        if (! kubernetesConnect.isConnected())
            kubernetesConnect.connection();

        return listRules.stream()
                .map(t -> t.check())
                .toList();
    }
    public List<Rule.RuleInfo> configure() throws OperationException {

        // Check the connection
        if (! kubernetesConnect.isConnected())
            kubernetesConnect.connection();

        return listRules.stream()
                .map(t -> t.configure())
                .toList();
    }
}
