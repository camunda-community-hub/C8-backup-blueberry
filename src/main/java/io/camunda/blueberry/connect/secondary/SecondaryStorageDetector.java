package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Find the secondary storage of the cluster.
 * <p>
 * It is the parameter {@link OrchestrationParameter#SECONDARY_STORAGE} of the orchestration cluster: the actuator /actuator/env
 * of the Zeebe (orchestration cluster) pod gives the property camunda.data.secondary-storage.type (or the environment variable
 * CAMUNDA_DATA_SECONDARYSTORAGE_TYPE). It works only if the actuator exposes the endpoint 'env' with its values
 * (management.endpoint.env.show-values).
 * Option B: the value blueberry.orchestrationConfig.secondaryStorage of the configuration.
 */
@Component
public class SecondaryStorageDetector {
    public static final String SOURCE_CLUSTER = "cluster";
    public static final String SOURCE_CONFIGURATION = "configuration";
    public static final String SOURCE_NONE = "none";

    private final Logger logger = LoggerFactory.getLogger(SecondaryStorageDetector.class);
    private final BlueberryConfig blueberryConfig;
    private final OrchestrationParameter orchestrationParameter;

    public SecondaryStorageDetector(BlueberryConfig blueberryConfig, OrchestrationParameter orchestrationParameter) {
        this.blueberryConfig = blueberryConfig;
        this.orchestrationParameter = orchestrationParameter;
    }

    /**
     * Result of the detection
     *
     * @param detected   value found in the cluster, empty if not detected
     * @param configured value of the configuration (blueberry.orchestrationConfig.secondaryStorage), empty if none
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
        // read the cluster again
        orchestrationParameter.refresh();

        String configured = orchestrationParameter.getConfiguredValue(OrchestrationParameter.SECONDARY_STORAGE);
        String detected = "";
        String note = "";
        OrchestrationParameter.Parameter parameter = orchestrationParameter.getParameters().stream()
                .filter(p -> OrchestrationParameter.SECONDARY_STORAGE.equals(p.getName()))
                .findFirst().orElse(null);
        if (parameter != null && parameter.getOrigin() == OrchestrationParameter.Parameter.Origin.ACTUATOR && parameter.getValue() != null) {
            detected = String.valueOf(parameter.getValue()).trim().toLowerCase(Locale.ROOT);
        }
        if (detected.isEmpty()) {
            String environmentNote = orchestrationParameter.getEnvironmentNote();
            note = environmentNote.isEmpty()
                    ? "The secondary storage is not defined in the /actuator/env of Zeebe (the endpoint 'env' may not be exposed, or its values are hidden)."
                    + " Without it, the value of blueberry.orchestrationConfig.secondaryStorage is used."
                    : environmentNote + " Without it, the value of blueberry.orchestrationConfig.secondaryStorage is used.";
            logger.info("Secondary storage: detection impossible. {} (actuator {})", note, blueberryConfig.getZeebeActuatorUrl());
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
}
