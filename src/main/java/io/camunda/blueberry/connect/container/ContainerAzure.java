package io.camunda.blueberry.connect.container;

import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.springframework.stereotype.Component;

@Component
public class ContainerAzure implements Container {

    public static final String AZURE = "azure";
    OrchestrationParameter orchestrationParameter;

    ContainerAzure(OrchestrationParameter orchestrationParameter) {
        this.orchestrationParameter = orchestrationParameter;
    }

    @Override
    public String getType() {
        return AZURE;
    }

    @Override
    public String getElasticsearchPayload(String basePathInsideContainer) {
        String containerName = orchestrationParameter.getParameterString(OrchestrationParameter.AZURE_CONTAINERNAME);
        return String.format("""
                {
                  "type": "%s",
                  "settings": {
                    "container": "%s",
                    "base_path": "%s"
                  }
                }
                """, AZURE, containerName, basePathInsideContainer);

    }

    public String getInformation() {
        String containerName = orchestrationParameter.getParameterString(OrchestrationParameter.AZURE_CONTAINERNAME);
        return "Container type[" + AZURE + "] name[" + containerName + "]";
    }
}
