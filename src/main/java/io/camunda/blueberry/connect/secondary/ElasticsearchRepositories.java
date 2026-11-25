package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.Parameter;
import io.camunda.blueberry.platform.rule.RuleOptimizeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The names of the Elasticsearch snapshot repositories, as {@link Parameter}: the value, the name of the parameter that gave it,
 * and its origin (ACTUATOR or CONFIG). One rule, for the backup and for the restore: the value declared by the cluster
 * (the actuator), else the configuration of Blueberry.
 * <ul>
 *   <li>Zeebe records (and the history backup): the parameter backupRepositoryName of the orchestration cluster
 *   (CAMUNDA_DATA_SECONDARYSTORAGE_ELASTICSEARCH_BACKUP_REPOSITORYNAME in the actuator of Zeebe, else
 *   blueberry.orchestrationConfig.backupRepositoryName, else blueberry.elasticsearch.zeebeRecordRepository)</li>
 *   <li>Optimize: CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME in the actuator of Optimize, else blueberry.optimizeRepository</li>
 * </ul>
 * Optimize is stopped by the restore, and its actuator no longer answers: the value read before is kept (see {@link #refresh()}).
 */
@Component
public class ElasticsearchRepositories {

    private final BlueberryConfig blueberryConfig;
    private final OrchestrationParameter orchestrationParameter;
    private final Logger logger = LoggerFactory.getLogger(ElasticsearchRepositories.class);

    /**
     * The repository of Optimize read in its actuator, kept for the time it does not answer
     */
    private Parameter optimizeFromActuator;

    public ElasticsearchRepositories(BlueberryConfig blueberryConfig, OrchestrationParameter orchestrationParameter) {
        this.blueberryConfig = blueberryConfig;
        this.orchestrationParameter = orchestrationParameter;
    }

    /**
     * Read the names now: the restore calls it before Optimize is stopped.
     */
    public void refresh() {
        optimize();
        zeebeRecord();
    }

    /**
     * The repository of the Zeebe records (and of the history backup). The name of the parameter is the variable of the cluster
     * (ACTUATOR), or the key of the configuration (CONFIG)
     */
    public Parameter zeebeRecord() {
        OrchestrationParameter.ParameterDefinition definition = OrchestrationParameter.BACKUP_REPOSITORY_NAME;
        Parameter parameter = orchestrationParameter.getParameter(definition);
        if (parameter != null && parameter.getValue() != null && !parameter.getValueString().isBlank()) {
            String name = parameter.getOrigin() == Parameter.Origin.ACTUATOR
                    ? definition.actuatorNames().get(0)
                    : "blueberry.orchestrationConfig." + definition.blueberryConfig();
            return new Parameter(name, parameter.getValue(), parameter.getOrigin());
        }
        return new Parameter("blueberry.elasticsearch.zeebeRecordRepository", blueberryConfig.getZeebeRecordRepository(), Parameter.Origin.CONFIG);
    }

    /**
     * The repository of Optimize
     */
    public synchronized Parameter optimize() {
        String url = blueberryConfig.getOptimizeActuatorUrl();
        if (url != null && !url.isBlank()) {
            try {
                OrchestrationParameter.ResultParameter result = orchestrationParameter.accessParameterViaActuator(
                        BackupComponentInt.COMPONENT.OPTIMIZE, List.of(RuleOptimizeRepository.OPTIMIZE_REPOSITORY), url + "/actuator/env");
                Object value = result.parameters.get(RuleOptimizeRepository.OPTIMIZE_REPOSITORY);
                if (result.accessActuator && value instanceof String repository && !repository.isBlank()) {
                    optimizeFromActuator = new Parameter("CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME", repository, Parameter.Origin.ACTUATOR);
                    return optimizeFromActuator;
                }
            } catch (Exception e) {
                logger.debug("The actuator of Optimize does not answer: {}", e.getMessage());
            }
        }
        if (optimizeFromActuator != null) {
            // Optimize is stopped: the value read when it was running
            return optimizeFromActuator;
        }
        return new Parameter("blueberry.optimizeRepository", blueberryConfig.getOptimizeRepository(), Parameter.Origin.CONFIG);
    }
}
