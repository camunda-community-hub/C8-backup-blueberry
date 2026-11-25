package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.ZeebeRestApi;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.platform.componentconfig.Parameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live checks before a destructive restore with the Restore API: the REST API of the cluster and the secondary storage.
 * Optimize does not block: the restore stops it (Kubernetes) and starts it again.
 */
@Component
public class RestoreEnvironmentCheck {

    private final BlueberryConfig blueberryConfig;
    private final ZeebeRestApi zeebeRestApi;
    private final SecondaryStorageFactory secondaryStorageFactory;

    @Autowired
    public RestoreEnvironmentCheck(BlueberryConfig blueberryConfig, ZeebeRestApi zeebeRestApi,
                                   SecondaryStorageFactory secondaryStorageFactory) {
        this.blueberryConfig = blueberryConfig;
        this.zeebeRestApi = zeebeRestApi;
        this.secondaryStorageFactory = secondaryStorageFactory;
    }

    public Map<String, Object> readiness() {
        List<String> blockers = new ArrayList<>();

        // The REST API of the cluster: /v2/mode and /v2/restore (Camunda 8.10)
        ZeebeRestApi.RestResult topology = zeebeRestApi.getTopology();
        boolean restApi = topology.isSuccess();
        if (!restApi) {
            blockers.add("The REST API of the cluster does not answer: GET " + zeebeRestApi.getBaseUrl() + "/topology answered "
                    + topology.status() + ". The Restore API exists from Camunda 8.10. Check camunda.client.zeebe.restAddress "
                    + "(or blueberry.zeebeRestUrl) and the credentials.");
        }

        // The secondary storage, and what Blueberry can do with it
        Parameter secondaryStorageParameter = secondaryStorageFactory.getValue();
        String secondaryStorageValue = secondaryStorageFactory.getValueString();
        SecondaryStorageInt secondaryStorage = secondaryStorageFactory.getSecondaryStorage();
        if (secondaryStorage == null) {
            blockers.add(secondaryStorageValue.isEmpty()
                    ? "The secondary storage is unknown: set blueberry.secondaryStorage (" + String.join(", ", secondaryStorageFactory.getSupportedNames()) + ")."
                    : "Blueberry has no implementation for the secondary storage [" + secondaryStorageValue + "]. Supported: "
                    + String.join(", ", secondaryStorageFactory.getSupportedNames()));
        } else if (!secondaryStorage.supportsRestore()) {
            blockers.add("Blueberry can't restore the secondary storage [" + secondaryStorage.getName() + "].");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restoreApiConnected", restApi);
        result.put("restoreApiUrl", zeebeRestApi.getBaseUrl());
        result.put("secondaryStorage", secondaryStorageValue);
        result.put("secondaryStorageSource", secondaryStorageParameter.getOriginLabel());
        result.put("executionEnabled", blueberryConfig.isRestoreExecutionEnabled());
        result.put("ready", blockers.isEmpty() && blueberryConfig.isRestoreExecutionEnabled());
        result.put("blockers", blockers);
        return result;
    }
}

