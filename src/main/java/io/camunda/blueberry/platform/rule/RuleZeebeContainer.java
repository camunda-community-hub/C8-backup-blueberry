package io.camunda.blueberry.platform.rule;


import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.platform.componentconfig.ConfigZeebe;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Operate define a repository, and the repository exist in ElasticSearch
 */
@Component
public class RuleZeebeContainer implements Rule {
    private final BlueberryConfig blueberryConfig;
    private final AccessParameterValue accessParameterValue;
    private final ConfigZeebe configZeebe;

    RuleZeebeContainer(BlueberryConfig blueberryConfig,
                       AccessParameterValue accessParameterValue,
                       ConfigZeebe configZeebe) {
        this.blueberryConfig = blueberryConfig;
        this.accessParameterValue = accessParameterValue;
        this.configZeebe = configZeebe;
    }

    @Override
    public boolean validRule() {

        return blueberryConfig.getZeebeActuatorUrl() != null;
    }

    @Override
    public String getName() {
        return "Zeebe Container";
    }

    public String getExplanations() {
        return "Zeebe must define a container to backup the data.";
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

        // get the Pod description
        RuleInfo ruleInfo = new RuleInfo(this);
        if (validRule()) {

            AccessParameterValue.ResultParameter resultParameter;

            resultParameter = configZeebe.accessParameters();
            ruleInfo.addVerificationsAssertBoolean("Container type defined: [" + resultParameter.parameters.get(ConfigZeebe.CONTAINER_CONTAINERTYPE) + "]",
                    resultParameter.parameters.containsKey(ConfigZeebe.CONTAINER_CONTAINERTYPE),
                    null);

            if (!resultParameter.parameters.containsKey(ConfigZeebe.CONTAINER_CONTAINERTYPE)) {
                ruleInfo.addError("Value for parameter [" + ConfigZeebe.CONTAINER_CONTAINERTYPE + "] is required");
                ruleInfo.setStatus(RuleStatus.FAILED);
            }

            // According to the type of storage, different check
            List<String> additionalParametersToCheck = null;
            if (ConfigZeebe.STORE_AZURE.equals(resultParameter.parameters.get(ConfigZeebe.CONTAINER_CONTAINERTYPE))) {
                additionalParametersToCheck = List.of(ConfigZeebe.AZURE_CONNECTIONSTRING, ConfigZeebe.CONTAINER_BASEPATH, ConfigZeebe.CONTAINER_BUCKETNAME);
            }
            if (ConfigZeebe.STORE_GCS.equals(resultParameter.parameters.get(ConfigZeebe.CONTAINER_CONTAINERTYPE))) {
                additionalParametersToCheck = List.of(ConfigZeebe.CONTAINER_BASEPATH, ConfigZeebe.CONTAINER_BUCKETNAME);
            }
            if (ConfigZeebe.STORE_S3.equals(resultParameter.parameters.get(ConfigZeebe.CONTAINER_CONTAINERTYPE))) {
                additionalParametersToCheck = List.of(ConfigZeebe.CONTAINER_BASEPATH, ConfigZeebe.CONTAINER_BUCKETNAME);
            }

            if (additionalParametersToCheck != null) {

                for (String parameterKey : additionalParametersToCheck) {
                    ruleInfo.addVerificationsAssertBoolean("Parameter [" + parameterKey + "]",
                            resultParameter.parameters.containsKey(parameterKey),
                            null);

                    if (!resultParameter.parameters.containsKey(parameterKey)) {
                        ruleInfo.addError("Missing parameter [" + parameterKey + "]");
                        ruleInfo.setStatus(RuleStatus.FAILED);
                    }
                }


            }
        } else
            ruleInfo.setStatus(RuleStatus.DEACTIVATED);

        if (ruleInfo.inProgress()) {
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }


}
