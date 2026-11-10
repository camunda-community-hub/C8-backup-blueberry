package io.camunda.blueberry.api;

import io.camunda.blueberry.platform.version.CamundaVersionProfile;
import io.camunda.blueberry.platform.version.CamundaVersionRegistry;
import io.camunda.blueberry.platform.version.CamundaVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("blueberry")
public class VersionRestController {

    private final CamundaVersionService versionService;
    private final CamundaVersionRegistry registry;

    public VersionRestController(CamundaVersionService versionService, CamundaVersionRegistry registry) {
        this.versionService = versionService;
        this.registry = registry;
    }

    @GetMapping(value = "/api/version", produces = "application/json")
    public Map<String, Object> getVersion() {
        Map<String, Object> result = new HashMap<>(versionService.getActiveProfile().toMap());
        result.put("supportedMinors", registry.getSupportedMinors());
        return result;
    }

    @PutMapping(value = "/api/version", produces = "application/json")
    public Map<String, Object> setVersion(@RequestBody Map<String, String> body) {
        String minor = body.get("minorVersion");
        CamundaVersionProfile profile = versionService.setActiveMinor(minor);
        Map<String, Object> result = new HashMap<>(profile.toMap());
        result.put("supportedMinors", registry.getSupportedMinors());
        return result;
    }
}
