package io.camunda.blueberry.platform.componentconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.CommunicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Access the management API (actuator) of a component. Two ways:
 * <ul>
 *     <li>{@link #accessActuator} / {@link #extractFromActuator}: read an actuator endpoint (for example /actuator/configprops)
 *     as a Map, and walk in it</li>
 *     <li>{@link #readEnvironment}: read the environment of Zeebe, GET /actuator/env</li>
 * </ul>
 * The environment gives the variables of the container, for example
 * <pre>
 * CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_STORE=FILESYSTEM
 * CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_FILESYSTEM_BASEPATH=/usr/local/camunda/backups/zeebe
 * </pre>
 * and the properties of the configuration files (camunda.data.primary-storage.backup.store).
 * <p>
 * The endpoint is not available by default. Zeebe must have
 * <pre>
 * MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=...,env     (expose the endpoint)
 * MANAGEMENT_ENDPOINT_ENV_ACCESS=unrestricted
 * MANAGEMENT_ENDPOINT_ENV_SHOWVALUES=ALWAYS             (else the values are hidden: ******)
 * </pre>
 */
@Component
public class AccessActuator {
    public static final String MASKED_VALUE = "******";

    /**
     * What /actuator/env gave
     *
     * @param reachable /actuator/env answered
     * @param masked    the endpoint answered, but it hides at least one of the values asked (******)
     * @param values    the values found, by the key of the request
     * @param note      why something is missing: not exposed, hidden, not defined. Empty if everything was found
     */
    public record Result(boolean reachable, boolean masked, Map<String, String> values, String note) {
    }

    private final Logger logger = LoggerFactory.getLogger(AccessActuator.class);
    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;

    public AccessActuator(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        this.blueberryConfig = blueberryConfig;
        this.restTemplate = restTemplate;
    }

    protected Map<String, Object> accessActuator(CamundaApplicationInt.COMPONENT component,
                                              String actuatorUrl) throws CommunicationException {
        RestTemplate plainRestTemplate = new RestTemplate();
        logger.info("Access actuator url [{}]", actuatorUrl);

        try {
            Map<String, Object> env = plainRestTemplate.getForObject(actuatorUrl, Map.class);
            return env == null ? new HashMap<String, Object>() : env;
        } catch (Exception e) {
            throw CommunicationException.getInstanceFromComponent(component, e);
        }
    }

    protected Object extractFromActuator(Map<String, Object> actuator, List<String> listKeys) {
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

    /**
     * Read the environment of Zeebe: GET /actuator/env
     *
     * @param wanted for each key, the names to search (the variable of the environment, the property): the first one found wins
     */
    protected Result readEnvironment(Map<String, List<String>> wanted) {
        String url = blueberryConfig.getZeebeActuatorUrl() + "/actuator/env";
        try {
            String body = restTemplate.getForObject(url, String.class);
            return parse(body, wanted);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String note = (status == 404 || status == 401 || status == 403)
                    ? "The endpoint " + url + " is not exposed by Zeebe (HTTP " + status + "). Add 'env' to MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE, "
                    + "and set MANAGEMENT_ENDPOINT_ENV_ACCESS=unrestricted and MANAGEMENT_ENDPOINT_ENV_SHOWVALUES=ALWAYS."
                    : "Can't read " + url + ": HTTP " + status;
            logger.info("Zeebe environment: {}", note);
            return new Result(false, false, Map.of(), note);
        } catch (Exception e) {
            String note = "Can't read " + url + ": " + e.getMessage();
            logger.info("Zeebe environment: {}", note);
            return new Result(false, false, Map.of(), note);
        }
    }

    /**
     * @param body   the answer of /actuator/env: {"propertySources": [{"name": ..., "properties": {"NAME": {"value": ...}}}]}
     * @param wanted for each key, the names to search
     */
    static Result parse(String body, Map<String, List<String>> wanted) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean masked = false;
        try {
            JsonNode propertySources = new ObjectMapper().readTree(body == null ? "{}" : body).path("propertySources");
            for (Map.Entry<String, List<String>> request : wanted.entrySet()) {
                // The property sources are listed by priority (the environment variables before the configuration files):
                // the first source which defines one of the names gives the value. Searching name by name would give the
                // value of a configuration file even when an environment variable overrides it.
                search:
                for (JsonNode propertySource : propertySources) {
                    for (String name : request.getValue()) {
                        JsonNode property = propertySource.path("properties").path(name);
                        if (property.isMissingNode()) {
                            continue;
                        }
                        String value = property.path("value").asText("");
                        if (MASKED_VALUE.equals(value)) {
                            // hidden at the highest priority: a value of a lower source would not be the effective one
                            masked = true;
                            break search;
                        }
                        if (!value.isEmpty()) {
                            values.put(request.getKey(), value.trim());
                            break search;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return new Result(true, false, Map.of(), "The answer of /actuator/env is not JSON: " + e.getMessage());
        }
        String note = "";
        if (masked && values.size() < wanted.size()) {
            note = "Zeebe hides the values of its environment (******): set MANAGEMENT_ENDPOINT_ENV_SHOWVALUES=ALWAYS.";
        }
        return new Result(true, masked, values, note);
    }
}
