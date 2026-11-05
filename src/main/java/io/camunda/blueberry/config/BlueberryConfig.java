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

    @Value("${blueberry.zeebeActuatorUrl:http://localhost:9600}")
    private String zeebeActuatorUrl;

    /**
     * The url contains a placeholder {BROKERID}, for example "http://camunda-zeebe-{{BROKERID}:9600" or "http://camunda-zeebe:960{{BROKERID}" to use portfoward
     */
    @Value("${blueberry.zeebeActuatorPodUrl:http://localhost:9600}")
    private String zeebeActuatorPodUrl;


    @Value("${blueberry.elasticsearchurl:http://localhost:9200}")
    private String elasticsearchUrl;

    /**
     * true: the zeebe-record indices are backed up in Elasticsearch (ZEEBERECORD component). false: ignored
     */
    @Value("${blueberry.elasticsearch.enabled:false}")
    private boolean elasticsearchEnabled;

    /**
     * false: Optimize is not part of the cluster, it is ignored (no connection, no backup, no rule)
     */
    @Value("${blueberry.optimize.enabled:true}")
    private boolean optimizeEnabled;

    @Value("${blueberry.namespace:camunda}")
    private String namespace;


    @Value("${blueberry.kubeConfig:}")
    private String kubeConfig;


    /**
     * Repository in Elastic search can be created to backup indexes
     */
    @Value("${blueberry.elasticsearch.optimizeContainerBasePath:optimize}")
    private String optimizeContainerBasePath;

    @Value("${blueberry.elasticsearch.zeebeRecordContainerBasePath:zeeberecord}")
    private String zeebeRecordContainerBasePath;

    @Value("${blueberry.elasticsearch.zeebeRecordRepository:camunda_zeebe_records_backup}")
    private String zeebeRecordRepository;

    /**
     * These values are temporary. KubernetesConnect should get that values directly from pôds
     */
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

    public String getZeebeActuatorUrl() {
        return zeebeActuatorUrl;
    }

    public String getZeebeActuatorPodUrl() {
        return zeebeActuatorPodUrl;
    }

    public String getZeebeBrokerActuatorUrl(long brokerId) {
        String actuatorUrl = getZeebeActuatorPodUrl();
        // value is something like "http://camunda-zeebe-{BROKERID}:9600" or "http://camunda-zeebe:960{BROKERID}"
        return actuatorUrl.replaceAll("\\{BROKERID\\}", String.valueOf(brokerId));
    }

    public String getElasticsearchUrl() {
        return elasticsearchUrl;
    }

    public boolean isElasticsearchEnabled() {
        return elasticsearchEnabled;
    }

    public boolean isOptimizeEnabled() {
        return optimizeEnabled;
    }

    public String getNamespace() {
        return namespace;
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

    public String getZeebeRecordRepository() {
        return zeebeRecordRepository;
    }

    public String getOptimizeContainerBasePath() {
        return optimizeContainerBasePath;
    }

    public String getZeebeRecordContainerBasePath() {
        return zeebeRecordContainerBasePath;
    }

    public String getKubeConfig() {
        return kubeConfig == null || kubeConfig.trim().isEmpty() ? null : kubeConfig;
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
