package io.camunda.blueberry.connect.container;

import io.camunda.blueberry.config.BlueberryConfig;
import org.springframework.stereotype.Component;

@Component
public class ContainerAzure implements Container {

    public static final String AZURE = "azure";
    BlueberryConfig blueberryConfig;

    ContainerAzure(BlueberryConfig blueberryConfig) {
        this.blueberryConfig = blueberryConfig;
    }

    @Override
    public String getType() {
        return AZURE;
    }

    @Override
    public String getElasticsearchPayload(String basePathInsideContainer) {
        String containerName = blueberryConfig.getAzureContainerName();
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
        String containerName = blueberryConfig.getAzureContainerName();
        return "Container type[" + AZURE + "] name[" + containerName + "]";
    }
}
