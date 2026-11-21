package io.camunda.blueberry.api;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.ZeebeConnect;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("blueberry")

public class ParametersRestController {
    Logger logger = LoggerFactory.getLogger(ParametersRestController.class);
    @Autowired
    private BlueberryConfig blueberryConfig;
    @Autowired
    private ZeebeConnect zeebeConnect;
    @Autowired
    private OrchestrationParameter orchestrationParameter;

    /**
     * The container and the secondary storage come from the orchestration cluster (the actuator, else the configuration).
     * They are given under the names the screen knows
     */
    private void addOrchestrationParameters(Map<String, Object> allParameters) {
        allParameters.put("containerType", orchestrationParameter.getParameterString(OrchestrationParameter.CONTAINER_CONTAINERTYPE));
        allParameters.put("azureContainerName", orchestrationParameter.getParameterString(OrchestrationParameter.AZURE_CONTAINERNAME));
        allParameters.put("azureBasePath", orchestrationParameter.getParameterString(OrchestrationParameter.AZURE_BASEPATH));
        allParameters.put("s3Bucket", orchestrationParameter.getParameterString(OrchestrationParameter.S3_BUCKETNAME));
        allParameters.put("s3BasePath", orchestrationParameter.getParameterString(OrchestrationParameter.S3_BASEPATH));
        allParameters.put("s3Region", orchestrationParameter.getParameterString(OrchestrationParameter.S3_REGION));
        allParameters.put("gcsBucket", orchestrationParameter.getParameterString(OrchestrationParameter.GCS_BUCKETNAME));
        allParameters.put("gcsBasePath", orchestrationParameter.getParameterString(OrchestrationParameter.GCS_BASEPATH));
        allParameters.put("filesystemPath", orchestrationParameter.getParameterString(OrchestrationParameter.FILESYSTEM_PATH));
        allParameters.put("secondaryStorage", orchestrationParameter.getParameterString(OrchestrationParameter.SECONDARY_STORAGE));
    }

    /**
     * Check the system
     * Does Zeebe declare a container? A Type storage?
     * Does Elasticsearch define a repository for each component (Optimize, ZeebeRecord)?
     *
     * @return
     */
    @GetMapping(value = "/api/parameters/getall", produces = "application/json")
    public Map<String, Object> getAll() {
        try {
            logger.debug("Rest [/api/parameters/getall]");
            Map<String, Object> allParameters = new HashMap<>();
            allParameters.putAll(blueberryConfig.getAll());
            addOrchestrationParameters(allParameters);
            allParameters.putAll(zeebeConnect.getParameters());

            return allParameters;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
