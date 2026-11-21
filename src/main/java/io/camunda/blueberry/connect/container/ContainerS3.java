package io.camunda.blueberry.connect.container;

import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.springframework.stereotype.Component;

@Component
public class ContainerS3 implements Container {

    public static final String S3 = "s3";
    private final OrchestrationParameter orchestrationParameter;

    public ContainerS3(OrchestrationParameter orchestrationParameter) {
        this.orchestrationParameter = orchestrationParameter;
    }

    @Override
    public String getType() {
        return S3;
    }

    @Override
    public String getElasticsearchPayload(String basePathInsideContainer) {
        String bucket = orchestrationParameter.getParameterString(OrchestrationParameter.S3_BUCKETNAME);
        String region = orchestrationParameter.getParameterString(OrchestrationParameter.S3_REGION);
        return String.format("""
                {
                  "type": "%s",
                  "settings": {
                    "bucket": "%s",
                    "base_path": "%s",
                    "region": "%s"
                  }
                }
                """, S3, bucket, basePathInsideContainer, region);
    }

    @Override
    public String getInformation() {
        String bucket = orchestrationParameter.getParameterString(OrchestrationParameter.S3_BUCKETNAME);
        String region = orchestrationParameter.getParameterString(OrchestrationParameter.S3_REGION);
        return "Container type[" + S3 + "] bucket[" + bucket + "] region[" + region + "]";
    }
}