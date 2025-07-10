package io.camunda.blueberry.platform.componentconfig;

import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.CommunicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AccessActuator {
    Logger logger = LoggerFactory.getLogger(AccessActuator.class);

    public Map<String, Object> accessActuator(CamundaApplicationInt.COMPONENT component,
                                              String actuatorUrl) throws CommunicationException {
        Map<String, Object> result = new HashMap<>();
        RestTemplate restTemplate = new RestTemplate();
        logger.info("Access actuator url [{}]", actuatorUrl);

        try {
            Map<String, Object> env = restTemplate.getForObject(actuatorUrl, Map.class);
            return env == null ? new HashMap<String, Object>() : env;
        } catch (Exception e) {
            throw CommunicationException.getInstanceFromComponent(component, e);
        }
    }

    public Object extractFromActuator(Map<String, Object> actuator, List<String> listKeys) {
        Object pointerActuator = actuator;
        String currentPath = "";
        for (int i = 0; i < listKeys.size(); i++) {
            if (pointerActuator instanceof Map pointerActuatorMap) {
                if (pointerActuatorMap.containsKey(listKeys.get(i))) {
                    currentPath += listKeys.get(i) + "/";
                    pointerActuator = pointerActuatorMap.get(listKeys.get(i));
                } else {
                    logger.debug("Child element is not a Map for key [{}] on path [{}]", listKeys.get(i), currentPath);
                    return null;
                }
            } else {
                logger.debug("No child element found for key [{}] on path [{}]", listKeys.get(i), currentPath);
                return null;
            }
        }
        return pointerActuator;
    }
}
