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
     * true: the zeebe-record indices are backed up in Elasticsearch (secondary storage). false: ignored
     */
    @Value("${blueberry.elasticsearch.enabled:false}")
    private boolean elasticsearchEnabled;

    /**
     * Base url of the Zeebe REST API (/v2/...). Empty: the rest address of the Camunda client is used
     * (camunda.client.zeebe.rest-address)
     */
    @Value("${blueberry.zeebeRestUrl:}")
    private String zeebeRestUrl;

    /**
     * Basic authentication of the REST API. Same values as the Camunda client by default
     */
    @Value("${blueberry.zeebeRestUsername:${camunda.client.auth.username:}}")
    private String zeebeRestUsername;

    @Value("${blueberry.zeebeRestPassword:${camunda.client.auth.password:}}")
    private String zeebeRestPassword;

    /**
     * false: Optimize is not part of the cluster, it is ignored (no connection, no backup, no rule)
     */
    @Value("${blueberry.optimize.enabled:true}")
    private boolean optimizeEnabled;

    @Value("${blueberry.restore.execution:false}")
    private boolean restoreExecution;

    @Value("${blueberry.restore.zeebeRestoreTimeoutSeconds:1800}")
    private int restoreZeebeTimeoutSeconds;


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
     * These values are temporary. They should come from the pods
     */
    @Value("${blueberry.optimizeRepository}")
    private String optimizeRepository;

    @Value("${blueberry.zeebeRepository}")
    private String zeebeRepository;


    // The containers (type, bucket, directory...) and the secondary storage are parameters of the orchestration cluster:
    // see OrchestrationParameter (the actuator of Zeebe, else blueberry.orchestrationConfig)


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

    /* ******************************************************************** */
    /*  Restore (blueberry.restore.*)                                        */
    /* ******************************************************************** */

    /**
     * blueberry.restore.execution: true to let Blueberry run the restore (it deletes data). False: the restore is only described.
     */
    public boolean isRestoreExecutionEnabled() {
        return restoreExecution;
    }

    /**
     * Time allowed to the restore of Zeebe, in seconds (the recovery mode, the restore, the return to the processing mode)
     */
    public int getRestoreZeebeTimeoutSeconds() {
        return restoreZeebeTimeoutSeconds;
    }

    public boolean isElasticsearchEnabled() {
        return elasticsearchEnabled;
    }

    public boolean isOptimizeEnabled() {
        return optimizeEnabled;
    }

    public String getZeebeRestUrl() {
        return zeebeRestUrl == null ? "" : zeebeRestUrl.trim();
    }

    public String getZeebeRestUsername() {
        return zeebeRestUsername == null ? "" : zeebeRestUsername;
    }

    public String getZeebeRestPassword() {
        return zeebeRestPassword == null ? "" : zeebeRestPassword;
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

    public String getOptimizeRepository() {
        return optimizeRepository;
    }

    public String getZeebeRepository() {
        return zeebeRepository;
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
