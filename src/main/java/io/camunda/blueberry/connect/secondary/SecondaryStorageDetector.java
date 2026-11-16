package io.camunda.blueberry.connect.secondary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;

/**
 * Find the secondary storage of the cluster.
 * <p>
 * Detection: the actuator /actuator/env of the Zeebe (orchestration cluster) pod gives the property
 * camunda.data.secondary-storage.type (or the environment variable CAMUNDA_DATA_SECONDARYSTORAGE_TYPE).
 * It works only if the actuator exposes the endpoint 'env' with its values (management.endpoint.env.show-values).
 * Option B: the value blueberry.secondaryStorage of the configuration.
 */
@Component
public class SecondaryStorageDetector {
    /**
     * Names of the property in /actuator/env, according to the source
     */
    static final List<String> PROPERTY_NAMES = List.of(
            "camunda.data.secondary-storage.type",
            "camunda.data.secondaryStorage.type",
            "CAMUNDA_DATA_SECONDARYSTORAGE_TYPE");
    static final String MASKED_VALUE = "******";

    public static final String SOURCE_CLUSTER = "cluster";
    public static final String SOURCE_CONFIGURATION = "configuration";
    public static final String SOURCE_NONE = "none";

    private final Logger logger = LoggerFactory.getLogger(SecondaryStorageDetector.class);
    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecondaryStorageDetector(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        this.blueberryConfig = blueberryConfig;
        this.restTemplate = restTemplate;
    }

    /**
     * Result of the detection
     *
     * @param detected   value found in the cluster, empty if not detected
     * @param configured value of the configuration (blueberry.secondaryStorage), empty if none
     * @param value      the value to use: the detected one, else the configured one
     * @param source     cluster, configuration or none: where the value comes from
     * @param note       why the detection did not work, empty if it worked
     */
    public record Detection(String detected, String configured, String value, String source, String note) {
    }

    /**
     * The detection reads the cluster: it is kept 60 seconds
     */
    private static final long CACHE_MILLIS = 60_000;
    private volatile Detection lastDetection;
    private volatile long lastDetectionTime;

    public Detection detect() {
        Detection cached = lastDetection;
        if (cached != null && System.currentTimeMillis() - lastDetectionTime < CACHE_MILLIS) {
            return cached;
        }
        Detection detection = detectNow();
        lastDetection = detection;
        lastDetectionTime = System.currentTimeMillis();
        return detection;
    }

    private Detection detectNow() {
        String configured = blueberryConfig.getSecondaryStorage();
        String note = "";
        String detected = "";
        try {
            detected = readInCluster();
            if (detected.isEmpty()) {
                note = "The secondary storage is not defined in the /actuator/env of Zeebe (the endpoint 'env' may not be exposed, or its values are hidden).";
            }
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 401 || status == 403) {
                // Expected: the actuator does not expose 'env' by default. It is optional: the configuration is used.
                note = "The endpoint " + blueberryConfig.getZeebeActuatorUrl() + "/actuator/env is not exposed by Zeebe (HTTP " + status
                        + "). To let Blueberry detect the secondary storage, add 'env' to management.endpoints.web.exposure.include "
                        + "(environment variable MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE) and set MANAGEMENT_ENDPOINT_ENV_SHOWVALUES=ALWAYS. "
                        + "Without it, the value of blueberry.secondaryStorage is used.";
            } else {
                note = "Can't read " + blueberryConfig.getZeebeActuatorUrl() + "/actuator/env: HTTP " + status;
            }
            logger.info("Secondary storage: detection impossible. {}", note);
        } catch (Exception e) {
            note = "Can't read " + blueberryConfig.getZeebeActuatorUrl() + "/actuator/env: " + e.getMessage();
            logger.info("Secondary storage: detection impossible. {}", note);
        }

        String value = detected;
        String source = SOURCE_CLUSTER;
        // "rdbms" is a type of storage, not a database: the vendor (sqlserver, postgres) comes from the configuration
        boolean needsVendor = detected.isEmpty() || "rdbms".equals(detected);
        if (needsVendor && !configured.isEmpty()) {
            value = configured;
            source = SOURCE_CONFIGURATION;
        }
        if (value.isEmpty()) {
            source = SOURCE_NONE;
        }
        return new Detection(detected, configured, value, source, note);
    }

    /**
     * @return the type found in /actuator/env (lower case), empty if the property is not there or is hidden
     */
    private String readInCluster() throws Exception {
        String url = blueberryConfig.getZeebeActuatorUrl() + "/actuator/env";
        String body = restTemplate.getForObject(url, String.class);
        if (body == null) {
            return "";
        }
        JsonNode propertySources = objectMapper.readTree(body).path("propertySources");
        for (JsonNode propertySource : propertySources) {
            JsonNode properties = propertySource.path("properties");
            for (String name : PROPERTY_NAMES) {
                JsonNode property = properties.path(name);
                if (property.isMissingNode()) {
                    continue;
                }
                String value = property.path("value").asText("");
                if (!value.isEmpty() && !MASKED_VALUE.equals(value)) {
                    return value.trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        return "";
    }
}
