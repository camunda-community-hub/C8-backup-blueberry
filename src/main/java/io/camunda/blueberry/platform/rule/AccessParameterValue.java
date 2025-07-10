package io.camunda.blueberry.platform.rule;


import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.CommunicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Access parameter value, via the env or via the configuration
 */
@Component

public class AccessParameterValue {
    private final Map<String, String> dictionary = Map.of("operateRepository", "CAMUNDA_OPERATE_BACKUP_REPOSITORY_NAME",
            "tasklistRepository", "CAMUNDA_TASKLIST_BACKUP_REPOSITORY_NAME",
            "optimizeRepository", "CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME",
            "zeebeRepository", "",
            "container.containerType", "ZEEBE_BROKER_DATA_BACKUP_STORE");
    Logger logger = LoggerFactory.getLogger(AccessParameterValue.class);
    BlueberryConfig blueberryConfig;


    public AccessParameterValue(BlueberryConfig blueberryConfig) {
        this.blueberryConfig = blueberryConfig;
    }

    /**
     * Access the parameter via the actuator method
     *
     * @param component      component to access
     * @param listParameters parameters searched in the list
     * @param actuatorUrl    url to access parameters
     * @return
     */
    public ResultParameter accessParameterViaActuator(CamundaApplicationInt.COMPONENT component, List<String> listParameters, String actuatorUrl) throws CommunicationException {

        // By the actuator
        ResultParameter resultParameter = new ResultParameter();
        Map<String, Object> actuator;

        try {
            List<String> listParameterActuator = listParameters.stream().map(dictionary::get).toList();
            logger.info("Access actuator component [{}] via URL[{}] : ", component, actuatorUrl);
            actuator = accessActuator(listParameterActuator, actuatorUrl);
        } catch (Exception e) {
            logger.info("Can't access actuator component [{}] via URL[{}] : ", component, actuatorUrl, e);
            actuator = Collections.emptyMap();
            throw CommunicationException.getInstanceFromComponent(component, e);
        }
        // access configuration
        Map<String, Object> configuration = accessConfiguration();

        for (String parameter : listParameters) {
            String keyInActuator = dictionary.get(parameter);
            if (keyInActuator != null && actuator.containsKey(keyInActuator)) {
                resultParameter.parameters.put(parameter, actuator.get(keyInActuator));
                resultParameter.accessActuator = true;
            } else {
                resultParameter.parameters.put(parameter, configuration.containsKey(parameter));
            }

            logger.debug("Access actuator parameter [{}]=>Key [{}] Exist? {} value[{}] : ", parameter,
                    keyInActuator,
                    actuator.containsKey(keyInActuator),
                    resultParameter.parameters.get(parameter));

        }
        return resultParameter;
    }

    /**
     * Return the parameters from the configuration
     *
     * @return
     */
    private Map<String, Object> accessConfiguration() {
        return blueberryConfig.getAll();
    }

    private Map<String, Object> accessActuator(List<String> listParameters, String actuatorUrl) {
        Map<String, Object> result = new HashMap<>();
        RestTemplate restTemplate = new RestTemplate();
        logger.info("Access actuator url [{}]", actuatorUrl);

        Map<String, Object> env = restTemplate.getForObject(actuatorUrl, Map.class);
        if (env == null)
            return result;
        for (String param : listParameters) {
            List<Map<String, Object>> propertySources = (List<Map<String, Object>>) env.get("propertySources");
            for (Map<String, Object> propertySource : propertySources) {
                if (propertySource.get("name").equals("systemEnvironment")) {
                    Map<String, Object> properties = (Map<String, Object>) propertySource.get("properties");
                    Map<String, Object> propertie = (Map<String, Object>) properties.get(param);
                    result.put(param, propertie.get("value"));
                }
            }
        }
        return result;
    }

    public static class ResultParameter {
        public boolean accessActuator;
        public String info;
        public Map<String, Object> parameters = new HashMap<>();
    }
}
