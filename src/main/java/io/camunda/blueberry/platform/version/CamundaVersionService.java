package io.camunda.blueberry.platform.version;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class CamundaVersionService {

    private final CamundaVersionRegistry registry;
    private final AtomicReference<CamundaVersionProfile> active;

    public CamundaVersionService(
            CamundaVersionRegistry registry,
            @Value("${blueberry.camunda.minorVersion:8.7}") String configuredMinor) {
        this.registry = registry;
        this.active = new AtomicReference<>(registry.find(configuredMinor).orElseGet(registry::getDefault));
    }

    public CamundaVersionProfile getActiveProfile() {
        return active.get();
    }

    public CamundaVersionProfile setActiveMinor(String minorVersion) {
        CamundaVersionProfile profile = registry.find(minorVersion)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported Camunda minor version: " + minorVersion));
        active.set(profile);
        return profile;
    }
}
