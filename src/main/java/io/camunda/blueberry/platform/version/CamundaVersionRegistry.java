package io.camunda.blueberry.platform.version;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class CamundaVersionRegistry {

    public static final String DEFAULT_MINOR = "8.7";

    private static final List<String> SUPPORTED = List.of("8.7", "8.8", "8.9", "8.10");

    public List<String> getSupportedMinors() {
        return SUPPORTED;
    }

    public Optional<CamundaVersionProfile> find(String minorVersion) {
        if (minorVersion == null || !SUPPORTED.contains(minorVersion)) {
            return Optional.empty();
        }
        return Optional.of(CamundaVersionProfile.forMinor(minorVersion));
    }

    public CamundaVersionProfile getDefault() {
        return CamundaVersionProfile.forMinor(DEFAULT_MINOR);
    }
}
