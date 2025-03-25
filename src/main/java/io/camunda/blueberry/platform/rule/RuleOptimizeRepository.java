package io.camunda.blueberry.platform.rule;


import io.camunda.blueberry.access.*;
import io.camunda.blueberry.config.BlueberryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Operate define a repository, and the repository exist in ElasticSearch
 */
@Component
public class RuleOptimizeRepository implements Rule {


    @Autowired
    BlueberryConfig blueberryConfig;

    @Autowired
    KubernetesAccess kubernetesAccess;

    @Autowired
    ElasticSearchAccess elasticSearchAccess;

    @Override
    public boolean validRule() {
        // is Operate is define in the cluster?
        return blueberryConfig.getOptimizeActuatorUrl() != null;
    }

    @Override
    public String getName() {
        return "Optimize Repository";
    }

    public String getExplanations() {
        return "Optimize must define a repository name. Elastsearch must define this repository, and map it to a valid container.";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
    }


    @Override
    public RuleInfo check() {
        return operation(false);
    }

    @Override
    public RuleInfo configure() {
        return operation(true);
    }

    private RuleInfo operation(boolean execute) {

        boolean accessPodRepository = false;
        boolean accessElasticsearchRepository = false;
        boolean createElasticsearchRepository = false;


        // get the Pod description
        RuleInfo ruleInfo = new RuleInfo(this);

        if (validRule()) {
            // ---------- First step, ask Operate for the name of the repository
            // the rule is in progress
            ruleInfo.setStatus(RuleStatus.INPROGRESS);
            String optimizeRepository = null;
            OperationResult operationResult = kubernetesAccess.getRepositoryName(CamundaApplication.COMPONENT.OPTIMIZE, blueberryConfig.getNamespace());
            if (!operationResult.success) {
                ruleInfo.addDetails("Can't access the Repository name in the pod, or does not exist");
                ruleInfo.addDetails(operationResult.details);
                ruleInfo.setStatus(RuleStatus.FAILED);
            } else {
                optimizeRepository = operationResult.resultSt;

            }
            ruleInfo.addVerifications("Access pod repository, retrieve [" + optimizeRepository + "]", ruleInfo.inProgress() ? RuleStatus.CORRECT : RuleStatus.FAILED,
                    operationResult.command);


            //------------ Second step, verify if the repository exist in elasticSearch
            if (ruleInfo.inProgress()) {
                // now check if the repository exist in Elastic search
                operationResult = elasticSearchAccess.existRepository(optimizeRepository);
                accessElasticsearchRepository = operationResult.resultBoolean;
                ruleInfo.addVerifications("Check Elasticsearch repository [" + optimizeRepository + "] :"
                                + operationResult.details,
                        accessElasticsearchRepository ? RuleStatus.CORRECT : RuleStatus.FAILED,
                        operationResult.command);

                // if the repository exist, then we stop the rule execution here
                if (accessElasticsearchRepository) {
                    ruleInfo.addDetails("Repository exist in Elastic search");
                    ruleInfo.setStatus(RuleStatus.CORRECT);
                } else {
                    // if we don't execute the rule, we stop here on a failure
                    if (!execute) {
                        ruleInfo.addDetails("Repository does not exist in Elastic search, and must be created");
                        ruleInfo.setStatus(RuleStatus.FAILED);
                    }
                }
            }


            // Third step, create the repository if asked
            if (execute && ruleInfo.inProgress()) {

                operationResult = elasticSearchAccess.createRepository(optimizeRepository,
                        blueberryConfig.getContainerType(),
                        blueberryConfig.getOptimizeContainerBasePath());
                if (operationResult.success) {
                    ruleInfo.addDetails("Repository is created in ElasticSearch");
                    ruleInfo.setStatus(RuleStatus.CORRECT);
                } else {
                    ruleInfo.addDetails("Error when creating the repository in ElasticSearch :" + operationResult.details);
                    ruleInfo.setStatus(RuleStatus.FAILED);
                }
                ruleInfo.addVerifications("Check Elasticsearch repository [" + optimizeRepository

                                + "] basePath[" + blueberryConfig.getOperateContainerBasePath()
                                + "] "+operationResult.details,
                        ruleInfo.getStatus(),
                        operationResult.command);

            }
        }
        return ruleInfo;
    }
}
