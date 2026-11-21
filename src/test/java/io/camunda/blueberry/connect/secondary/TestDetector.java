package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.TestOrchestration;

import java.util.Map;

/**
 * Build a SecondaryStorageDetector for the tests
 */
public final class TestDetector {
    private TestDetector() {
    }

    /**
     * @param configured the secondary storage of blueberry.orchestrationConfig (empty: none)
     * @param envJson    the answer of Zeebe to /actuator/env. Null: the actuator can't be reached
     */
    public static SecondaryStorageDetector detector(String configured, String envJson) {
        Map<String, String> configuration = configured == null || configured.isEmpty()
                ? Map.of()
                : Map.of(OrchestrationParameter.SECONDARY_STORAGE, configured);
        return new SecondaryStorageDetector(new BlueberryConfig(), TestOrchestration.build(configuration, envJson));
    }
}
