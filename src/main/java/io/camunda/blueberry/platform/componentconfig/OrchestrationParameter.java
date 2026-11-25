package io.camunda.blueberry.platform.componentconfig;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.exception.CommunicationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Access the configuration for Zeebe
 * Information come from the actuator, or from BlueberryConfig
 */
@Component
public class OrchestrationParameter {
    /**
     * The keys of the normalized result of accessParameters() (ResultParameter): they are not parameters of the cluster
     */
    public static final String CONTAINER_BUCKETNAME = "container.bucketName";
    public static final String CONTAINER_BASEPATH = "container.basePath";
    public static final String AZURE_CONNECTIONSTRING = "ZEEBE_BROKER_DATA_BACKUP_AZURE_CONNECTIONSTRING";
    public static final String STORE_GCS = "GCS";
    public static final String STORE_S3 = "S3";
    public static final String STORE_AZURE = "AZURE";
    public static final String STORE_FILESYSTEM = "FILESYSTEM";
    /**
     * Where the fallback values are in the application.yaml: blueberry.orchestrationConfig.{blueberryConfig of the parameter}
     */
    public static final String CONFIG_PREFIX = "blueberry.orchestrationConfig.";
    /**
     * The directory of the Elasticsearch repository (type fs), as Elasticsearch sees it. Only in the configuration:
     * it is not a variable of Zeebe
     */
    public static final ParameterDefinition FILESYSTEM_PATH = new ParameterDefinition(List.of(), "filesystem.path");
    /**
     * The secondary storage of the cluster: elasticsearch, opensearch, rdbms... In the actuator camunda.data.secondary-storage.type,
     * in the YAML of Blueberry secondaryStorage
     */
    public static final ParameterDefinition SECONDARY_STORAGE = new ParameterDefinition(
            List.of("camunda.data.secondary-storage.type", "camunda.data.secondaryStorage.type", "CAMUNDA_DATA_SECONDARYSTORAGE_TYPE"), "secondaryStorage");
    /**
     * The JDBC url of the secondary storage when its type is rdbms: it gives the vendor
     */
    public static final ParameterDefinition SECONDARY_STORAGE_RDBMS_URL = new ParameterDefinition(
            List.of("camunda.data.secondary-storage.rdbms.url", "camunda.data.secondaryStorage.rdbms.url", "CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL"), "secondaryStorageRdbmsUrl");
    /**
     * The Elasticsearch snapshot repository of the history backup (POST /v2/backups/history). The cluster has no default value:
     * without it the history backup is refused (403 "No backup repository configured")
     */
    public static final ParameterDefinition BACKUP_REPOSITORY_NAME = new ParameterDefinition(
            List.of("camunda.data.secondary-storage.elasticsearch.backup.repository-name", "CAMUNDA_DATA_SECONDARYSTORAGE_ELASTICSEARCH_BACKUP_REPOSITORYNAME",
                    "camunda.data.secondary-storage.opensearch.backup.repository-name", "CAMUNDA_DATA_SECONDARYSTORAGE_OPENSEARCH_BACKUP_REPOSITORYNAME"), "backupRepositoryName");
    /**
     * For each parameter (the name), the names searched in /actuator/env: the first one found wins
     */
    public static final Map<String, List<String>> ENVIRONMENT;
    public static final String BACKUP_MODE_CONTINUOUS = "CONTINUOUS";
    public static final String BACKUP_MODE_MANUAL = "MANUAL";
    /**
     * The variables of Zeebe 8.10 (primary storage) and the variables of the older versions (8.8), with the property of the
     * configuration file: Blueberry searches each of them in /actuator/env
     */
    static final String ENV_PREFIX = "CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_";
    static final String LEGACY_PREFIX = "ZEEBE_BROKER_DATA_BACKUP_";
    static final String PROPERTY_PREFIX = "camunda.data.primary-storage.backup.";
    public static final ParameterDefinition CONTAINER_CONTAINERTYPE = new ParameterDefinition(
            List.of(ENV_PREFIX + "STORE", LEGACY_PREFIX + "STORE", PROPERTY_PREFIX + "store"), "container.containerType");
    public static final List<String> PARAMETERS_KEYS = List.of(CONTAINER_CONTAINERTYPE.name(), "backupStore", "container.azure.connectionString");
    public static final ParameterDefinition FILESYSTEM_BASEPATH = new ParameterDefinition(
            List.of(ENV_PREFIX + "FILESYSTEM_BASEPATH", LEGACY_PREFIX + "FILESYSTEM_BASEPATH", PROPERTY_PREFIX + "filesystem.base-path"), "filesystem.basePath");
    public static final ParameterDefinition GCS_BUCKETNAME = new ParameterDefinition(
            List.of(ENV_PREFIX + "GCS_BUCKETNAME", LEGACY_PREFIX + "GCS_BUCKETNAME", PROPERTY_PREFIX + "gcs.bucket-name"), "gcs.bucketName");
    public static final ParameterDefinition GCS_BASEPATH = new ParameterDefinition(
            List.of(ENV_PREFIX + "GCS_BASEPATH", LEGACY_PREFIX + "GCS_BASEPATH", PROPERTY_PREFIX + "gcs.base-path"), "gcs.basePath");
    public static final ParameterDefinition S3_BUCKETNAME = new ParameterDefinition(
            List.of(ENV_PREFIX + "S3_BUCKETNAME", LEGACY_PREFIX + "S3_BUCKETNAME", PROPERTY_PREFIX + "s3.bucket-name"), "s3.bucketName");
    public static final ParameterDefinition S3_BASEPATH = new ParameterDefinition(
            List.of(ENV_PREFIX + "S3_BASEPATH", LEGACY_PREFIX + "S3_BASEPATH", PROPERTY_PREFIX + "s3.base-path"), "s3.basePath");
    public static final ParameterDefinition S3_REGION = new ParameterDefinition(
            List.of(ENV_PREFIX + "S3_REGION", LEGACY_PREFIX + "S3_REGION", PROPERTY_PREFIX + "s3.region"), "s3.region");
    public static final ParameterDefinition AZURE_CONTAINERNAME = new ParameterDefinition(
            List.of(ENV_PREFIX + "AZURE_CONTAINERNAME", LEGACY_PREFIX + "AZURE_CONTAINERNAME", PROPERTY_PREFIX + "azure.container-name"), "azure.containerName");
    public static final ParameterDefinition AZURE_BASEPATH = new ParameterDefinition(
            List.of(ENV_PREFIX + "AZURE_BASEPATH", LEGACY_PREFIX + "AZURE_BASEPATH", PROPERTY_PREFIX + "azure.base-path"), "azure.basePath");
    // The backup schedule of the primary storage
    public static final ParameterDefinition BACKUP_CONTINUOUS = new ParameterDefinition(
            List.of(ENV_PREFIX + "CONTINUOUS", PROPERTY_PREFIX + "continuous"), "backupContinuous");
    public static final ParameterDefinition BACKUP_SCHEDULE = new ParameterDefinition(
            List.of(ENV_PREFIX + "SCHEDULE", PROPERTY_PREFIX + "schedule"), "backupSchedule");
    public static final ParameterDefinition BACKUP_CHECKPOINT_INTERVAL = new ParameterDefinition(
            List.of(ENV_PREFIX + "CHECKPOINTINTERVAL", PROPERTY_PREFIX + "checkpoint-interval"), "backupCheckpointInterval");
    public static final ParameterDefinition BACKUP_RETENTION_WINDOW = new ParameterDefinition(
            List.of(ENV_PREFIX + "RETENTION_WINDOW", PROPERTY_PREFIX + "retention.window"), "backupRetentionWindow");
    /**
     * All the parameters collected from the cluster
     */
    public static final List<ParameterDefinition> PARAMETERS = List.of(CONTAINER_CONTAINERTYPE, FILESYSTEM_BASEPATH, FILESYSTEM_PATH,
            GCS_BUCKETNAME, GCS_BASEPATH, S3_BUCKETNAME, S3_BASEPATH, S3_REGION, AZURE_CONTAINERNAME, AZURE_BASEPATH,
            SECONDARY_STORAGE, SECONDARY_STORAGE_RDBMS_URL, BACKUP_REPOSITORY_NAME,
            BACKUP_CONTINUOUS, BACKUP_SCHEDULE, BACKUP_CHECKPOINT_INTERVAL, BACKUP_RETENTION_WINDOW);
    private static final List<String> actuatorKeys = List.of("contexts",
            "Camunda",
            "beans",
            "zeebe.broker-io.camunda.application.commons.configuration.BrokerBasedConfiguration$BrokerBasedProperties",
            "data",
            "backup");

    static {
        Map<String, List<String>> environment = new LinkedHashMap<>();
        for (ParameterDefinition definition : PARAMETERS) {
            environment.put(definition.name(), definition.actuatorNames());
        }
        ENVIRONMENT = Collections.unmodifiableMap(environment);
    }

    private final BlueberryConfig blueberryConfig;
    private final AccessActuator accessActuator;

    /**
     * Parameter name in Blueberry => variable searched in the environment of the component
     */
    private final Map<String, String> dictionary = Map.of(
            "optimizeRepository", "CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME",
            "zeebeRepository", "",
            "container.containerType", "ZEEBE_BROKER_DATA_BACKUP_STORE");

    private final Environment springEnvironment;
    Logger logger = LoggerFactory.getLogger(OrchestrationParameter.class);
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
     * The values of the variables CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_* read in /actuator/env
     */
    static ResultParameter fullFillFromEnvironment(ResultParameter resultParameter,
                                                   Map<String, String> values) {
        String store = values.get(CONTAINER_CONTAINERTYPE.name());
        resultParameter.parameters.put(CONTAINER_CONTAINERTYPE.name(), store);
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

    /**
     * Collect all the parameters in /actuator/env of Zeebe. The value of a parameter is the one of the actuator, or, when the
     * actuator can't be reached or does not give it, the one of blueberry.orchestrationConfig in the application.yaml.
     */
    @PostConstruct
    public void refresh() {
        AccessActuator.Result environment = accessActuator.readEnvironment(ENVIRONMENT);
        Map<String, Parameter> collected = new LinkedHashMap<>();
        for (ParameterDefinition definition : PARAMETERS) {
            String name = definition.name();
            String fromActuator = environment.values().get(name);
            String fromConfig = getConfiguredValue(definition);
            if (fromActuator != null) {
                collected.put(name, new Parameter(name, fromActuator, Parameter.Origin.ACTUATOR));
            } else {
                collected.put(name, new Parameter(name, fromConfig.isEmpty() ? null : fromConfig, Parameter.Origin.CONFIG));
            }
        }
        this.lastEnvironment = environment;
        this.parameterMap = collected;
        logger.info("Orchestration parameters collected: actuator reachable={}, {} of {} read in the actuator",
                environment.reachable(), environment.values().size(), collected.size());
    }

    /**
     * @return the parameter (value and origin), null if the name is unknown
     */
    public Parameter getParameter(String parameterName) {
        return parameterMap.get(parameterName);
    }

    public Parameter getParameter(ParameterDefinition definition) {
        return parameterMap.get(definition.name());
    }

    public Object getParameterValue(ParameterDefinition definition) {
        return getParameterValue(definition.name());
    }

    public String getParameterString(ParameterDefinition definition) {
        return getParameterString(definition.name());
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
     * @return the value in the configuration (blueberry.orchestrationConfig), whatever the actuator says. Empty if there is none.
     * Private: the applications use getParameter() or getParameterValue()
     */
    private String getConfiguredValue(ParameterDefinition definition) {
        String value = springEnvironment.getProperty(CONFIG_PREFIX + definition.blueberryConfig());
        return value == null ? "" : value.trim();
    }

    /**
     * The backup mode of the cluster, from the parameter backupContinuous
     *
     * @return CONTINUOUS or MANUAL, empty if the cluster does not say
     */
    public String getBackupMode() {
        String continuous = getParameterString(BACKUP_CONTINUOUS);
        return continuous.isEmpty() ? "" : Boolean.parseBoolean(continuous) ? BACKUP_MODE_CONTINUOUS : BACKUP_MODE_MANUAL;
    }

    /**
     * The secondary storage implemented by Blueberry: the value of the parameter secondaryStorage (elasticsearch, opensearch...).
     * When the value is "rdbms" (a type of storage, not a database), the vendor is read in the JDBC url of the cluster
     * (jdbc:postgresql: gives postgres, jdbc:sqlserver: gives sqlserver...).
     *
     * @return the name, empty if it is unknown (rdbms without a readable JDBC url)
     */
    public String getSecondaryStorageKind() {
        String type = getParameterString(SECONDARY_STORAGE);
        if (!"rdbms".equalsIgnoreCase(type)) {
            return type;
        }
        String url = getParameterString(SECONDARY_STORAGE_RDBMS_URL).toLowerCase(java.util.Locale.ROOT);
        if (url.startsWith("jdbc:postgresql:")) {
            return "postgres";
        }
        if (url.startsWith("jdbc:sqlserver:")) {
            return "sqlserver";
        }
        // mysql, mariadb, oracle, h2...: the vendor of the url, without an implementation in Blueberry
        return url.startsWith("jdbc:") ? url.substring(5, url.indexOf(':', 5) > 0 ? url.indexOf(':', 5) : url.length()) : "";
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
    public ResultParameter accessParameterViaActuator(BackupComponentInt.COMPONENT component, List<String> listParameters, String actuatorUrl) throws CommunicationException {

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

    public ResultParameter accessParameters() {

        // The rule asks the state of the cluster now: the parameters are collected again
        refresh();
        AccessActuator.Result environment = lastEnvironment;

        // 1. The environment of Zeebe: the variables CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_* (/actuator/env)
        Map<String, String> fromActuator = valuesOf(Parameter.Origin.ACTUATOR);
        if (fromActuator.containsKey(CONTAINER_CONTAINERTYPE.name())) {
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
            actuator = accessActuator.accessActuator(BackupComponentInt.COMPONENT.ZEEBE,
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
        resultParameter.parameters.put(CONTAINER_CONTAINERTYPE.name(), store);

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
        values.putIfAbsent(CONTAINER_CONTAINERTYPE.name(), "");
        return fullFillFromEnvironment(resultParameter, values);
    }

    /**
     * The definition of a parameter: how to find it in the actuator, and how to find it in the Blueberry configuration. The two
     * names are independent: the actuator calls it camunda.data.secondary-storage.type, the YAML of Blueberry secondaryStorage.
     */
    public static final class ParameterDefinition {
        private final List<String> actuatorNames;
        private final String blueberryConfig;

        /**
         * @param actuatorNames   the names searched in /actuator/env (the variable, the property): the first one found wins. Empty: the
         *                        parameter does not exist in the cluster, it comes from the configuration only
         * @param blueberryConfig the key in blueberry.orchestrationConfig of the application.yaml. It is also the name of the parameter
         */
        public ParameterDefinition(List<String> actuatorNames, String blueberryConfig) {
            this.actuatorNames = List.copyOf(actuatorNames);
            this.blueberryConfig = blueberryConfig;
        }

        /**
         * @return the name of the parameter: the key used to get it (getParameter, getParameterValue)
         */
        public String name() {
            return blueberryConfig;
        }

        public List<String> actuatorNames() {
            return actuatorNames;
        }

        public String blueberryConfig() {
            return blueberryConfig;
        }

        @Override
        public String toString() {
            return blueberryConfig;
        }
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
}

