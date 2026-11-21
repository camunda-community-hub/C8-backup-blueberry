package io.camunda.blueberry.connect.container;

import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.springframework.stereotype.Component;

@Component
public class ContainerGCS implements Container {

    public static final String GCS = "gcs";
    private final OrchestrationParameter orchestrationParameter;

    public ContainerGCS(OrchestrationParameter orchestrationParameter) {
        this.orchestrationParameter = orchestrationParameter;
    }

    @Override
    public String getType() {
        return GCS;
    }

    @Override
    public String getElasticsearchPayload(String basePathInsideContainer) {
        String bucket = orchestrationParameter.getParameterString(OrchestrationParameter.GCS_BUCKETNAME);

        return String.format("""
                {
                  "type": "%s",
                  "settings": {
                    "bucket": "%s",
                    "base_path": "%s",
                    "client": "default"                
                  }
                }
                """, GCS, bucket, basePathInsideContainer);
    }

    @Override
    public String getInformation() {
        String bucket = orchestrationParameter.getParameterString(OrchestrationParameter.GCS_BUCKETNAME);
        return "Container type[" + GCS + "] bucket[" + bucket + "]";
    }
}