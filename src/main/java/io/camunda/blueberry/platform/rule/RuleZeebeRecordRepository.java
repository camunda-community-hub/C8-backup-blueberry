package io.camunda.blueberry.platform.rule;


import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.ElasticSearchConnect;
import io.camunda.blueberry.connect.OperationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Operate define a repository, and the repository exist in ElasticSearch
 */
@Component
public class RuleZeebeRecordRepository implements Rule {


    @Autowired
    BlueberryConfig blueberryConfig;

    @Autowired
    ElasticSearchConnect elasticSearchConnect;

    @Override
    public boolean validRule() {
        // is a repository is define in the cluster?
        return true;
    }

    @Override
    public String getName() {
        return "Zeebe Record Repository";
    }

    public String getExplanations() {
        return "A repository must be defined in ElasticSearch to backup Zeebe Record data, and map it to a valid container.";
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

            //------------ Second step, verify if the repository exists in elasticSearch
            if (ruleInfo.inProgress()) {
                // now check if the repository exists in Elastic search
                OperationResult operationResult = elasticSearchConnect.existRepository(blueberryConfig.getZeebeRecordRepository());
                accessElasticsearchRepository = operationResult.resultBoolean;
                ruleInfo.addVerificationsButWillBeFixed("Check Elasticsearch repository [" + blueberryConfig.getZeebeRecordRepository() + "] :"
                                + operationResult.details,
                        accessElasticsearchRepository ? RuleStatus.CORRECT : RuleStatus.FAILED,
                        operationResult.command);

                // if the repository exists, then we stop the rule execution here
                if (accessElasticsearchRepository) {
                    ruleInfo.addDetails("Repository exist in Elastic search");
                } else {
                    // if we don't execute the rule, we stop here on a failure
                    if (!execute) {
                        ruleInfo.addError("Repository does not exist in Elastic search, and must be created");
                        ruleInfo.setStatus(RuleStatus.FAILED);
                    }
                }
            }


            // Third step, create the repository if asked
            if (execute && ruleInfo.inProgress()) {
                OperationResult operationResult = elasticSearchConnect.createRepository(blueberryConfig.getZeebeRecordRepository(),
                        blueberryConfig.getZeebeContainerType(),
                        blueberryConfig.getZeebeRecordContainerBasePath());
                if (operationResult.success) {
                    ruleInfo.addDetails("Repository is created in ElasticSearch");
                    ruleInfo.setStatus(RuleStatus.CORRECT);
                } else {
                    ruleInfo.addError("Error when creating the repository in ElasticSearch :" + operationResult.details);
                    ruleInfo.setStatus(RuleStatus.FAILED);
                }
                ruleInfo.addVerifications("Check Elasticsearch repository [" + blueberryConfig.getZeebeRecordRepository()
                                + "] ContainerType[" + blueberryConfig.getZeebeContainerType()
                                + "] ContainerName[" + blueberryConfig.getAzureContainerName()
                                + "] basePath[" + blueberryConfig.getZeebeRecordContainerBasePath() + "]",
                        operationResult.success ? RuleStatus.CORRECT : RuleStatus.FAILED,
                        operationResult.command);

            }
        }
        if (ruleInfo.inProgress()) {
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }
}
