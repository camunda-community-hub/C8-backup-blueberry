package io.camunda.blueberry.platform.componentconfig;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.platform.rule.AccessParameterValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Access the configuration for Zeebe
 * Information come from the actuator, or from BlueberryConfig
 */
@Component
public class ConfigZeebe {
    public static final String CONTAINER_CONTAINERTYPE = "container.containerType";
    public static final String CONTAINER_BUCKETNAME = "container.bucketName";
    public static final String CONTAINER_BASEPATH = "container.basePath";
    public static final String AZURE_CONNECTIONSTRING = "ZEEBE_BROKER_DATA_BACKUP_AZURE_CONNECTIONSTRING";
    public static final List<String> PARAMETERS_KEYS = List.of(
            CONTAINER_CONTAINERTYPE,
            "backupStore",
            "container.azure.connectionString"
    );
    public static final String STORE_GCS = "GCS";
    public static final String STORE_S3 = "S3";
    public static final String STORE_AZURE = "AZURE";
    private static final List<String> actuatorKeys = List.of("contexts",
            "Camunda",
            "beans",
            "zeebe.broker-io.camunda.application.commons.configuration.BrokerBasedConfiguration$BrokerBasedProperties",
            "data",
            "backup");
    private final BlueberryConfig blueberryConfig;
    Logger logger = LoggerFactory.getLogger(ConfigZeebe.class);

    ConfigZeebe(BlueberryConfig blueberryConfig,
                AccessParameterValue accessParameterValue) {
        this.blueberryConfig = blueberryConfig;
    }

    public AccessParameterValue.ResultParameter accessParameters() {

        // By the actuator
        AccessParameterValue.ResultParameter resultParameter = new AccessParameterValue.ResultParameter();
        Map<String, Object> actuator;
        String actuatorUrl = blueberryConfig.getZeebeActuatorUrl() + "/actuator/configprops";
        try {
            AccessActuator accessActuator = new AccessActuator();
            actuator = accessActuator.accessActuator(CamundaApplicationInt.COMPONENT.ZEEBE,
                    actuatorUrl);
            resultParameter.accessActuator = true;
            resultParameter = fullFillFromActuator(resultParameter, actuator);
            return resultParameter;
        } catch (Exception e) {
            logger.info("Can't access actuator ZEEBE via URL[{}] : ",
                    actuatorUrl, e);
            resultParameter.accessActuator = false;
            resultParameter.info = "Can't access actuator ZEEBE on " + actuatorUrl + " : " + e.getMessage();
            resultParameter = fullFillFromConfiguration(resultParameter);
        }
        return resultParameter;
    }

    private AccessParameterValue.ResultParameter fullFillFromActuator(AccessParameterValue.ResultParameter resultParameter,
                                                                      Map<String, Object> actuator) {
        AccessActuator accessActuator = new AccessActuator();

        Object properties = accessActuator.extractFromActuator(actuator,
                List.of("contexts",
                        "Camunda",
                        "beans",
                        "zeebe.broker-io.camunda.application.commons.configuration.BrokerBasedConfiguration$BrokerBasedProperties",
                        "properties",
                        "data",
                        "backup"));
        if (properties == null || !(properties instanceof Map)) {
            logger.info("Can't extract properties from actuator, no values from {}",
                    String.join("/", actuatorKeys));
            resultParameter.info = "Can't extract properties from actuator, no values from " +
                    String.join("/", actuatorKeys);
            resultParameter.accessActuator = false;
            return resultParameter;
        }
        Map<String, Object> propertiesMap = (Map<String, Object>) properties;
        Object store = propertiesMap.get("store");
        resultParameter.parameters.put(CONTAINER_CONTAINERTYPE, store);

        if (STORE_GCS.equals(store)) {
            Map<String, Object> gcsProperties = (Map<String, Object>) propertiesMap.get("gcs");
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, gcsProperties == null ? null : gcsProperties.get("bucketName"));
            resultParameter.parameters.put(CONTAINER_BASEPATH, gcsProperties == null ? null : gcsProperties.get("basePath"));
        }
        if (STORE_S3.equals(store)) {
            Map<String, Object> gcsProperties = (Map<String, Object>) propertiesMap.get("s3");
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, gcsProperties == null ? null : gcsProperties.get("bucketName"));
            resultParameter.parameters.put(CONTAINER_BASEPATH, gcsProperties == null ? null : gcsProperties.get("basePath"));
        }
        if (STORE_AZURE.equals(store)) {
            Map<String, Object> gcsProperties = (Map<String, Object>) propertiesMap.get("azure");
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, gcsProperties == null ? null : gcsProperties.get("containerName"));
            resultParameter.parameters.put(CONTAINER_BASEPATH, gcsProperties == null ? null : gcsProperties.get("basePath"));
        }
        return resultParameter;
    }

    private AccessParameterValue.ResultParameter fullFillFromConfiguration(AccessParameterValue.ResultParameter resultParameter) {
        String store = blueberryConfig.getZeebeContainerType();
        resultParameter.parameters.put(CONTAINER_CONTAINERTYPE, store);
        if ("GCS".equals(store)) {
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, blueberryConfig.getGcsBucket());
            resultParameter.parameters.put(CONTAINER_BASEPATH, blueberryConfig.getGcsBasePath());
        }
        if ("S3".equals(store)) {
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, blueberryConfig.getS3Bucket());
            resultParameter.parameters.put(CONTAINER_BASEPATH, blueberryConfig.getS3BasePath());
        }
        if ("AZURE".equals(store)) {
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, blueberryConfig.getAzureContainerName());
            resultParameter.parameters.put(CONTAINER_BASEPATH, blueberryConfig.getAzureBasePath());
        }

        return resultParameter;

    }
}
