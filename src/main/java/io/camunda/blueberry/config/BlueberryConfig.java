package io.camunda.blueberry.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(value = "blueberry")
@Component
public class BlueberryConfig {

    @Value("${blueberry.optimizeActuatorUrl:@null}")
    private String optimizeActuatorUrl;

    @Value("${blueberry.operateActuatorUrl:@null}")
    private String operateActuatorUrl;

    @Value("${blueberry.tasklistActuatorUrl:@null}")
    private String tasklistActuatorUrl;

    @Value("${blueberry.zeebeActuatorUrl:http://localhost:9600}")
    private String zeebeActuatorUrl;

    @Value("${blueberry.elasticsearchurl:http://localhost:9200}")
    private String elasticsearchUrl;

    @Value("${blueberry.namespace:camunda}")
    private String namespace;

    @Value("${blueberry.kubeConfig:}")
    private String kubeConfig;

    /** Optional kubectl context name (e.g. kind-camunda-platform-local). Uses ~/.kube/config when kubeConfig is unset. */
    @Value("${blueberry.kubeContext:}")
    private String kubeContext;

    @Value("${blueberry.restore.execution:false}")
    private boolean restoreExecution;

    @Value("${blueberry.restore.helmRelease:camunda87helm}")
    private String restoreHelmRelease;

    @Value("${blueberry.restore.elasticsearchRepository:}")
    private String restoreElasticsearchRepository;

    @Value("${blueberry.restore.podDeleteTimeoutSeconds:300}")
    private int restorePodDeleteTimeoutSeconds;

    @Value("${blueberry.restore.zeebeRestoreTimeoutSeconds:1800}")
    private int restoreZeebeTimeoutSeconds;


    /**
     * Repository in Elastic search can be created to backup indexes
     */
    @Value("${blueberry.elasticsearch.operateContainerBasePath:operate}")
    private String operateContainerBasePath;

    @Value("${blueberry.elasticsearch.tasklistContainerBasePath:tasklist}")
    private String tasklistContainerBasePath;

    @Value("${blueberry.elasticsearch.optimizeContainerBasePath:optimize}")
    private String optimizeContainerBasePath;

    @Value("${blueberry.elasticsearch.zeebeRecordContainerBasePath:zeeberecord}")
    private String zeebeRecordContainerBasePath;

    @Value("${blueberry.elasticsearch.zeebeRecordRepository:camunda_zeebe_records_backup}")
    private String zeebeRecordRepository;

    /**
     * These values are temporary. KubernetesConnect should get that values directly from pôds
     */
    @Value("${blueberry.operateRepository}")
    private String operateRepository;

    @Value("${blueberry.tasklistRepository}")
    private String tasklistRepository;

    @Value("${blueberry.optimizeRepository}")
    private String optimizeRepository;

    @Value("${blueberry.zeebeRepository}")
    private String zeebeRepository;


    // Containers
    @Value("${blueberry.container.containerType:}")
    private String containerType;

    @Value("${blueberry.container.azure.containerName:}")
    private String azureContainerName;
    @Value("${blueberry.container.azure.basePath:}")
    private String azureBasePath;

    // S3 specific configuration
    @Value("${blueberry.container.s3.bucket:}")
    private String s3Bucket;

    @Value("${blueberry.container.s3.basePath:}")
    private String s3BasePath;

    @Value("${blueberry.container.gcs.bucket:}")
    private String gcsBucket;

    @Value("${blueberry.container.gcs.basePath:}")
    private String gcsBasePath;

    @Value("${blueberry.container.s3.region:}")
    private String s3Region;


    // Getters for general properties
    public String getOptimizeActuatorUrl() {
        return optimizeActuatorUrl;
    }

    public String getOperateActuatorUrl() {
        return operateActuatorUrl;
    }

    public String getTasklistActuatorUrl() {
        return tasklistActuatorUrl;
    }

    public String getZeebeActuatorUrl() {
        return zeebeActuatorUrl;
    }

    public String getElasticsearchUrl() {
        return elasticsearchUrl;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isRestoreExecutionEnabled() {
        return restoreExecution;
    }

    public String getRestoreHelmRelease() {
        return restoreHelmRelease;
    }

    public String getRestoreElasticsearchRepository() {
        return restoreElasticsearchRepository;
    }

    public int getRestorePodDeleteTimeoutSeconds() {
        return restorePodDeleteTimeoutSeconds;
    }

    public int getRestoreZeebeTimeoutSeconds() {
        return restoreZeebeTimeoutSeconds;
    }

    public String getAzureContainerName() {
        return azureContainerName;
    }

    public String getAzureBasePath() {
        return azureBasePath;
    }

    public String getZeebeContainerType() {
        return containerType;
    }

    public String getOperateContainerBasePath() {
        return operateContainerBasePath;
    }

    public String getZeebeRecordRepository() {
        return zeebeRecordRepository;
    }

    public String getOptimizeContainerBasePath() {
        return optimizeContainerBasePath;
    }

    public String getTasklistContainerBasePath() {
        return tasklistContainerBasePath;
    }

    public String getZeebeRecordContainerBasePath() {
        return zeebeRecordContainerBasePath;
    }

    public String getKubeConfig() {
        return kubeConfig == null || kubeConfig.trim().isEmpty() ? null : kubeConfig;
    }

    public String getKubeContext() {
        return kubeContext == null || kubeContext.trim().isEmpty() ? null : kubeContext.trim();
    }

    public String getOperateRepository() {
        return operateRepository;
    }

    public String getTasklistRepository() {
        return tasklistRepository;
    }

    public String getOptimizeRepository() {
        return optimizeRepository;
    }

    public String getZeebeRepository() {
        return zeebeRepository;
    }

    // Getters for S3 configuration
    public String getS3Bucket() {
        return s3Bucket;
    }

    public String getS3BasePath() {
        return s3BasePath;
    }

    public String getS3Region() {
        return s3Region;
    }

    // Getters for S3 configuration
    public String getGcsBucket() {
        return gcsBucket;
    }

    public String getGcsBasePath() {
        return gcsBasePath;
    }


    /**
     * Access all configuration variables and return it dynamically.
     *
     * @return map of all variables, value
     */
    public Map<String, Object> getAll() {
        Map<String, Object> values = new HashMap<>();
        for (Field field : this.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                values.put(field.getName(), field.get(this));
            } catch (IllegalAccessException e) {
                values.put(field.getName(), "ACCESS_DENIED");
            }
        }
        return values;
    }
}
