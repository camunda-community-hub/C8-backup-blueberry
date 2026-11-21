package io.camunda.blueberry.platform.componentconfig;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.CommunicationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Access the configuration for Zeebe
 * Information come from the actuator, or from BlueberryConfig
 */
@Component
public class OrchestrationParameter {
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
    public static final String STORE_FILESYSTEM = "FILESYSTEM";

    /**
     * The variables of Zeebe 8.10 (primary storage) and the variables of the older versions (8.8), with the property of the
     * configuration file: Blueberry searches each of them in /actuator/env
     */
    static final String ENV_PREFIX = "CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_";
    static final String LEGACY_PREFIX = "ZEEBE_BROKER_DATA_BACKUP_";
    static final String PROPERTY_PREFIX = "camunda.data.primary-storage.backup.";
    public static final String FILESYSTEM_BASEPATH = "filesystem.basePath";
    /**
     * The directory of the Elasticsearch repository (type fs), as Elasticsearch sees it. Only in the configuration:
     * it is not a variable of Zeebe
     */
    public static final String FILESYSTEM_PATH = "filesystem.path";
    public static final String GCS_BUCKETNAME = "gcs.bucketName";
    public static final String GCS_BASEPATH = "gcs.basePath";
    public static final String S3_BUCKETNAME = "s3.bucketName";
    public static final String S3_BASEPATH = "s3.basePath";
    public static final String S3_REGION = "s3.region";
    public static final String AZURE_CONTAINERNAME = "azure.containerName";
    public static final String AZURE_BASEPATH = "azure.basePath";
    /**
     * The secondary storage of the cluster: elasticsearch, opensearch, rdbms... (camunda.data.secondary-storage.type)
     */
    public static final String SECONDARY_STORAGE = "secondaryStorage";
    public static final String BACKUP_CONTINUOUS = "backupContinuous";
    public static final String BACKUP_SCHEDULE = "backupSchedule";
    public static final String BACKUP_CHECKPOINT_INTERVAL = "backupCheckpointInterval";
    public static final String BACKUP_RETENTION_WINDOW = "backupRetentionWindow";

    /**
     * Where the fallback values are in the application.yaml. The name of a parameter is the end of the key:
     * blueberry.orchestrationConfig.container.containerType, blueberry.orchestrationConfig.backupSchedule, ...
     */
    public static final String CONFIG_PREFIX = "blueberry.orchestrationConfig.";

    /**
     * For each parameter (the key), the names searched in /actuator/env: the first one found wins
     */
    public static final Map<String, List<String>> ENVIRONMENT;

    static {
        Map<String, List<String>> environment = new LinkedHashMap<>();
        environment.put(CONTAINER_CONTAINERTYPE, List.of(ENV_PREFIX + "STORE", LEGACY_PREFIX + "STORE", PROPERTY_PREFIX + "store"));
        environment.put(FILESYSTEM_BASEPATH, List.of(ENV_PREFIX + "FILESYSTEM_BASEPATH", LEGACY_PREFIX + "FILESYSTEM_BASEPATH", PROPERTY_PREFIX + "filesystem.base-path"));
        // Not a variable of Zeebe: no name to search, the value comes from the configuration
        environment.put(FILESYSTEM_PATH, List.of());
        environment.put(GCS_BUCKETNAME, List.of(ENV_PREFIX + "GCS_BUCKETNAME", LEGACY_PREFIX + "GCS_BUCKETNAME", PROPERTY_PREFIX + "gcs.bucket-name"));
        environment.put(GCS_BASEPATH, List.of(ENV_PREFIX + "GCS_BASEPATH", LEGACY_PREFIX + "GCS_BASEPATH", PROPERTY_PREFIX + "gcs.base-path"));
        environment.put(S3_BUCKETNAME, List.of(ENV_PREFIX + "S3_BUCKETNAME", LEGACY_PREFIX + "S3_BUCKETNAME", PROPERTY_PREFIX + "s3.bucket-name"));
        environment.put(S3_BASEPATH, List.of(ENV_PREFIX + "S3_BASEPATH", LEGACY_PREFIX + "S3_BASEPATH", PROPERTY_PREFIX + "s3.base-path"));
        environment.put(S3_REGION, List.of(ENV_PREFIX + "S3_REGION", LEGACY_PREFIX + "S3_REGION", PROPERTY_PREFIX + "s3.region"));
        environment.put(AZURE_CONTAINERNAME, List.of(ENV_PREFIX + "AZURE_CONTAINERNAME", LEGACY_PREFIX + "AZURE_CONTAINERNAME", PROPERTY_PREFIX + "azure.container-name"));
        environment.put(AZURE_BASEPATH, List.of(ENV_PREFIX + "AZURE_BASEPATH", LEGACY_PREFIX + "AZURE_BASEPATH", PROPERTY_PREFIX + "azure.base-path"));
        // The secondary storage
        environment.put(SECONDARY_STORAGE, List.of("camunda.data.secondary-storage.type", "camunda.data.secondaryStorage.type", "CAMUNDA_DATA_SECONDARYSTORAGE_TYPE"));
        // The backup schedule of the primary storage
        environment.put(BACKUP_CONTINUOUS, List.of(ENV_PREFIX + "CONTINUOUS", PROPERTY_PREFIX + "continuous"));
        environment.put(BACKUP_SCHEDULE, List.of(ENV_PREFIX + "SCHEDULE", PROPERTY_PREFIX + "schedule"));
        environment.put(BACKUP_CHECKPOINT_INTERVAL, List.of(ENV_PREFIX + "CHECKPOINTINTERVAL", PROPERTY_PREFIX + "checkpoint-interval"));
        environment.put(BACKUP_RETENTION_WINDOW, List.of(ENV_PREFIX + "RETENTION_WINDOW", PROPERTY_PREFIX + "retention.window"));
        ENVIRONMENT = Collections.unmodifiableMap(environment);
    }

    /**
     * A parameter of the orchestration cluster
     */
    public static class Parameter {
        /**
         * Where the value comes from
         */
        public enum Origin {
            /**
             * /actuator/env of Zeebe
             */
            ACTUATOR,
            /**
             * blueberry.orchestrationConfig in the application.yaml: the actuator can't be reached, or it does not give the variable
             */
            CONFIG
        }

        private final String name;
        private final Object value;
        private final Origin origin;

        public Parameter(String name, Object value, Origin origin) {
            this.name = name;
            this.value = value;
            this.origin = origin;
        }

        public String getName() {
            return name;
        }

        /**
         * @return the value, null if neither the actuator nor the configuration gave it
         */
        public Object getValue() {
            return value;
        }

        public Origin getOrigin() {
            return origin;
        }
    }
    private static final List<String> actuatorKeys = List.of("contexts",
            "Camunda",
            "beans",
            "zeebe.broker-io.camunda.application.commons.configuration.BrokerBasedConfiguration$BrokerBasedProperties",
            "data",
            "backup");
    private final BlueberryConfig blueberryConfig;
    Logger logger = LoggerFactory.getLogger(OrchestrationParameter.class);

    private final AccessActuator accessActuator;

    /**
     * Parameter name in Blueberry => variable searched in the environment of the component
     */
    private final Map<String, String> dictionary = Map.of(
            "optimizeRepository", "CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME",
            "zeebeRepository", "",
            "container.containerType", "ZEEBE_BROKER_DATA_BACKUP_STORE");

    private final Environment springEnvironment;

    /**
     * One entry for each parameter of ENVIRONMENT. Replaced as a whole by refresh()
     */
    private volatile Map<String, Parameter> parameterMap = Map.of();

    /**
     * The last answer of /actuator/env
     */
    private volatile AccessActuator.Result lastEnvironment = new AccessActuator.Result(false, false, Map.of(), "");

    public OrchestrationParameter(BlueberryConfig blueberryConfig,
                           AccessActuator accessActuator,
                           Environment springEnvironment) {
        this.blueberryConfig = blueberryConfig;
        this.accessActuator = accessActuator;
        this.springEnvironment = springEnvironment;
    }

    /**
     * Collect all the parameters in /actuator/env of Zeebe. The value of a parameter is the one of the actuator, or, when the
     * actuator can't be reached or does not give it, the one of blueberry.orchestrationConfig in the application.yaml.
     */
    @PostConstruct
    public void refresh() {
        AccessActuator.Result environment = accessActuator.readEnvironment(ENVIRONMENT);
        Map<String, Parameter> collected = new LinkedHashMap<>();
        for (String name : ENVIRONMENT.keySet()) {
            String fromActuator = environment.values().get(name);
            if (fromActuator != null) {
                collected.put(name, new Parameter(name, fromActuator, Parameter.Origin.ACTUATOR));
            } else {
                String fromConfig = springEnvironment.getProperty(CONFIG_PREFIX + name);
                collected.put(name, new Parameter(name,
                        fromConfig == null || fromConfig.isBlank() ? null : fromConfig.trim(),
                        Parameter.Origin.CONFIG));
            }
        }
        this.lastEnvironment = environment;
        this.parameterMap = collected;
        logger.info("Orchestration parameters collected: actuator reachable={}, {} of {} read in the actuator",
                environment.reachable(), environment.values().size(), collected.size());
    }

    /**
     * @return the value of the parameter, null if it is unknown or has no value
     */
    public Object getParameterValue(String parameterName) {
        Parameter parameter = parameterMap.get(parameterName);
        return parameter == null ? null : parameter.getValue();
    }

    /**
     * @return the value of the parameter as a text, trimmed, empty if there is none
     */
    public String getParameterString(String parameterName) {
        Object value = getParameterValue(parameterName);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * @return the value in the configuration (blueberry.orchestrationConfig), whatever the actuator says. Empty if there is none
     */
    public String getConfiguredValue(String parameterName) {
        String value = springEnvironment.getProperty(CONFIG_PREFIX + parameterName);
        return value == null ? "" : value.trim();
    }

    /**
     * @return why the last read of /actuator/env gave nothing (not exposed, hidden...). Empty if it worked
     */
    public String getEnvironmentNote() {
        return lastEnvironment.note();
    }

    /**
     * @return all the parameters collected
     */
    public List<Parameter> getParameters() {
        return List.copyOf(parameterMap.values());
    }

    /**
     * The values of the parameters, by name
     *
     * @param origin only the parameters with this origin (null: all of them); a parameter without value is ignored
     */
    private Map<String, String> valuesOf(Parameter.Origin origin) {
        Map<String, String> values = new HashMap<>();
        for (Parameter parameter : parameterMap.values()) {
            if (parameter.getValue() != null && (origin == null || parameter.getOrigin() == origin)) {
                values.put(parameter.getName(), String.valueOf(parameter.getValue()));
            }
        }
        return values;
    }

    /**
     * Access the parameter via the actuator method (/actuator/env of a component), then via the Blueberry configuration
     *
     * @param component      component to access
     * @param listParameters parameters searched in the list
     * @param actuatorUrl    url to access parameters
     */
    public ResultParameter accessParameterViaActuator(CamundaApplicationInt.COMPONENT component, List<String> listParameters, String actuatorUrl) throws CommunicationException {

        ResultParameter resultParameter = new ResultParameter();
        Map<String, Object> actuator;

        try {
            List<String> listParameterActuator = listParameters.stream().map(dictionary::get).toList();
            logger.info("Access actuator component [{}] via URL[{}] : ", component, actuatorUrl);
            actuator = readSystemEnvironment(listParameterActuator, actuatorUrl);
        } catch (Exception e) {
            if (e instanceof org.springframework.web.client.HttpStatusCodeException httpError) {
                // Expected: 404/401/403 when /actuator/env is not exposed or protected, 503 when the component is stopped
                // (during a restore). The access is optional, the value comes from the Blueberry configuration.
                // No stack trace for that.
                logger.info("Can't access actuator component [{}] via URL[{}]: HTTP {}. The 'env' endpoint is not exposed, or the component is not running (optional): the Blueberry configuration is used.",
                        component, actuatorUrl, httpError.getStatusCode().value());
            } else {
                logger.info("Can't access actuator component [{}] via URL[{}] : ", component, actuatorUrl, e);
            }
            throw CommunicationException.getInstanceFromComponent(component, e);
        }
        // The /actuator/env of the component answered
        resultParameter.envReachable = true;
        // access configuration
        Map<String, Object> configuration = blueberryConfig.getAll();

        for (String parameter : listParameters) {
            String keyInActuator = dictionary.get(parameter);
            if (keyInActuator != null && actuator.containsKey(keyInActuator)) {
                Object value = actuator.get(keyInActuator);
                if (AccessActuator.MASKED_VALUE.equals(value)) {
                    // The component hides the values (management.endpoint.env.show-values): the value is not usable
                    resultParameter.masked = true;
                    resultParameter.parameters.put(parameter, configuration.get(parameter));
                } else {
                    resultParameter.parameters.put(parameter, value);
                    resultParameter.accessActuator = true;
                }
            } else {
                // The value of the blueberry configuration
                resultParameter.parameters.put(parameter, configuration.get(parameter));
            }

            logger.debug("Access actuator parameter [{}]=>Key [{}] Exist? {} value[{}] : ", parameter,
                    keyInActuator,
                    actuator.containsKey(keyInActuator),
                    resultParameter.parameters.get(parameter));

        }
        return resultParameter;
    }

    /**
     * Read the variables of the systemEnvironment property source in the /actuator/env of a component
     */
    private Map<String, Object> readSystemEnvironment(List<String> listParameters, String actuatorUrl) {
        Map<String, Object> result = new HashMap<>();
        RestTemplate restTemplate = new RestTemplate();
        logger.info("Access actuator url [{}]", actuatorUrl);

        Map<String, Object> env = restTemplate.getForObject(actuatorUrl, Map.class);
        if (env == null)
            return result;
        for (String param : listParameters) {
            List<Map<String, Object>> propertySources = (List<Map<String, Object>>) env.get("propertySources");
            for (Map<String, Object> propertySource : propertySources) {
                if (propertySource.get("name").equals("systemEnvironment")) {
                    Map<String, Object> properties = (Map<String, Object>) propertySource.get("properties");
                    Map<String, Object> propertie = (Map<String, Object>) properties.get(param);
                    // The variable may not exist in the environment of the component
                    if (propertie != null) {
                        result.put(param, propertie.get("value"));
                    }
                }
            }
        }
        return result;
    }

    public static class ResultParameter {
        /**
         * True when the value was read in the /env of the component
         */
        public boolean accessActuator;
        /**
         * True when /env of the component answered, even if the value was not found or hidden
         */
        public boolean envReachable;
        /**
         * True when /env answered, but the component hides the value (******). Set management.endpoint.env.show-values=ALWAYS
         */
        public boolean masked;
        public String info;
        public Map<String, Object> parameters = new HashMap<>();
    }

    public ResultParameter accessParameters() {

        // The rule asks the state of the cluster now: the parameters are collected again
        refresh();
        AccessActuator.Result environment = lastEnvironment;

        // 1. The environment of Zeebe: the variables CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_* (/actuator/env)
        Map<String, String> fromActuator = valuesOf(Parameter.Origin.ACTUATOR);
        if (fromActuator.containsKey(CONTAINER_CONTAINERTYPE)) {
            ResultParameter fromEnvironment = new ResultParameter();
            fromEnvironment.accessActuator = true;
            fromEnvironment.envReachable = true;
            fromEnvironment.info = "Read in the environment of Zeebe (" + blueberryConfig.getZeebeActuatorUrl() + "/actuator/env)";
            return fullFillFromEnvironment(fromEnvironment, fromActuator);
        }

        // 2. The configuration properties of the broker, 3. the configuration of Blueberry
        ResultParameter resultParameter = new ResultParameter();
        resultParameter.envReachable = environment.reachable();
        resultParameter.masked = environment.masked();
        String environmentNote = environment.note().isEmpty()
                ? "The variable " + ENV_PREFIX + "STORE is not defined in the environment of Zeebe."
                : environment.note();
        Map<String, Object> actuator;
        String actuatorUrl = blueberryConfig.getZeebeActuatorUrl() + "/actuator/configprops";
        try {
            actuator = accessActuator.accessActuator(CamundaApplicationInt.COMPONENT.ZEEBE,
                    actuatorUrl);
            resultParameter.accessActuator = true;
            resultParameter = fullFillFromActuator(resultParameter, actuator);
            if (!resultParameter.accessActuator) {
                // configprops answers, but the broker properties are not where this version puts them
                resultParameter.info = environmentNote + " " + resultParameter.info + " The values of the Blueberry configuration are used.";
                resultParameter = fullFillFromConfiguration(resultParameter);
            }
            return resultParameter;
        } catch (Exception e) {
            logger.info("Can't access actuator ZEEBE via URL[{}] : ",
                    actuatorUrl, e);
            resultParameter.accessActuator = false;
            resultParameter.info = environmentNote + " Can't access actuator ZEEBE on " + actuatorUrl + " : " + e.getMessage()
                    + " The values of the Blueberry configuration are used.";
            resultParameter = fullFillFromConfiguration(resultParameter);
        }
        return resultParameter;
    }

    /**
     * The values of the variables CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_* read in /actuator/env
     */
    static ResultParameter fullFillFromEnvironment(ResultParameter resultParameter,
                                                                        Map<String, String> values) {
        String store = values.get(CONTAINER_CONTAINERTYPE);
        resultParameter.parameters.put(CONTAINER_CONTAINERTYPE, store);
        if (STORE_FILESYSTEM.equalsIgnoreCase(store)) {
            putIfPresent(resultParameter, CONTAINER_BASEPATH, values.get("filesystem.basePath"));
        }
        if (STORE_GCS.equalsIgnoreCase(store)) {
            putIfPresent(resultParameter, CONTAINER_BUCKETNAME, values.get("gcs.bucketName"));
            putIfPresent(resultParameter, CONTAINER_BASEPATH, values.get("gcs.basePath"));
        }
        if (STORE_S3.equalsIgnoreCase(store)) {
            putIfPresent(resultParameter, CONTAINER_BUCKETNAME, values.get("s3.bucketName"));
            putIfPresent(resultParameter, CONTAINER_BASEPATH, values.get("s3.basePath"));
        }
        if (STORE_AZURE.equalsIgnoreCase(store)) {
            putIfPresent(resultParameter, CONTAINER_BUCKETNAME, values.get("azure.containerName"));
            putIfPresent(resultParameter, CONTAINER_BASEPATH, values.get("azure.basePath"));
        }
        return resultParameter;
    }

    private static void putIfPresent(ResultParameter resultParameter, String key, String value) {
        if (value != null && !value.isBlank()) {
            resultParameter.parameters.put(key, value);
        }
    }

    private ResultParameter fullFillFromActuator(ResultParameter resultParameter,
                                                                      Map<String, Object> actuator) {

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

        if (STORE_GCS.equalsIgnoreCase(String.valueOf(store))) {
            Map<String, Object> gcsProperties = (Map<String, Object>) propertiesMap.get("gcs");
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, gcsProperties == null ? null : gcsProperties.get("bucketName"));
            resultParameter.parameters.put(CONTAINER_BASEPATH, gcsProperties == null ? null : gcsProperties.get("basePath"));
        }
        if (STORE_S3.equalsIgnoreCase(String.valueOf(store))) {
            Map<String, Object> gcsProperties = (Map<String, Object>) propertiesMap.get("s3");
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, gcsProperties == null ? null : gcsProperties.get("bucketName"));
            resultParameter.parameters.put(CONTAINER_BASEPATH, gcsProperties == null ? null : gcsProperties.get("basePath"));
        }
        if (STORE_AZURE.equalsIgnoreCase(String.valueOf(store))) {
            Map<String, Object> gcsProperties = (Map<String, Object>) propertiesMap.get("azure");
            resultParameter.parameters.put(CONTAINER_BUCKETNAME, gcsProperties == null ? null : gcsProperties.get("containerName"));
            resultParameter.parameters.put(CONTAINER_BASEPATH, gcsProperties == null ? null : gcsProperties.get("basePath"));
        }
        return resultParameter;
    }

    private ResultParameter fullFillFromConfiguration(ResultParameter resultParameter) {
        // the parameters collected: the actuator, then blueberry.orchestrationConfig
        Map<String, String> values = valuesOf(null);
        values.putIfAbsent(CONTAINER_CONTAINERTYPE, "");
        return fullFillFromEnvironment(resultParameter, values);
    }
}
