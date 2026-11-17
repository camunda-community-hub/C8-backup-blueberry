package io.camunda.blueberry.platform;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.platform.rule.AccessParameterValue;
import io.camunda.blueberry.platform.rule.RuleOperateRepository;
import io.camunda.blueberry.platform.rule.RuleOptimizeRepository;
import io.camunda.blueberry.platform.rule.RuleTasklistRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Elasticsearch repositories of the components: the value in the blueberry configuration, and the value read in the cluster.
 * <p>
 * The value in the cluster is read in the /actuator/env of the component. It is empty when the component does not expose
 * "env", does not answer, or hides the values (******): the "comment" gives the reason.
 */
@Component
public class RepositoryInformation {

    /**
     * @param name          component: operate, tasklist, optimize, zeeberecord
     * @param configuration repository in the blueberry configuration (used by the backup and the restore)
     * @param cluster       repository read in the cluster. Empty: the value can't be read, see the comment
     * @param comment       why the cluster value is empty. Empty when the cluster value is given
     */
    public record Repository(String name, String configuration, String cluster, String comment) {
    }

    /**
     * The actuator is called at most every 30 seconds: the pages ask this list often
     */
    private static final long CACHE_MILLIS = 30_000;

    private final BlueberryConfig blueberryConfig;
    private final AccessParameterValue accessParameterValue;

    private List<Repository> cache;
    private long cacheTime = 0;

    public RepositoryInformation(BlueberryConfig blueberryConfig, AccessParameterValue accessParameterValue) {
        this.blueberryConfig = blueberryConfig;
        this.accessParameterValue = accessParameterValue;
    }

    public synchronized List<Repository> list() {
        if (cache != null && System.currentTimeMillis() - cacheTime < CACHE_MILLIS) {
            return cache;
        }
        List<Repository> repositories = new ArrayList<>();
        repositories.add(readInCluster("operate", blueberryConfig.getOperateRepository(),
                CamundaApplicationInt.COMPONENT.OPERATE, RuleOperateRepository.OPERATE_REPOSITORY,
                blueberryConfig.getOperateActuatorUrl()));
        repositories.add(readInCluster("tasklist", blueberryConfig.getTasklistRepository(),
                CamundaApplicationInt.COMPONENT.TASKLIST, RuleTasklistRepository.TASKLIST_REPOSITORY,
                blueberryConfig.getTasklistActuatorUrl()));
        repositories.add(readInCluster("optimize", blueberryConfig.getOptimizeRepository(),
                CamundaApplicationInt.COMPONENT.OPTIMIZE, RuleOptimizeRepository.OPTIMIZE_REPOSITORY,
                blueberryConfig.getOptimizeActuatorUrl()));
        // The repository of the Zeebe records is created by blueberry: no component declares it
        repositories.add(new Repository("zeeberecord", blueberryConfig.getZeebeRecordRepository(), "", ""));
        cache = repositories;
        cacheTime = System.currentTimeMillis();
        return repositories;
    }

    /**
     * Read the repository declared by the component. When it can't be read, the cluster value is empty and the comment gives the reason.
     */
    private Repository readInCluster(String name, String configuration, CamundaApplicationInt.COMPONENT component,
                                     String parameter, String actuatorUrl) {
        if (actuatorUrl == null || actuatorUrl.isBlank()) {
            return new Repository(name, configuration, "", "The actuator url of the component is not defined");
        }
        try {
            AccessParameterValue.ResultParameter result = accessParameterValue.accessParameterViaActuator(
                    component, List.of(parameter), actuatorUrl + "/actuator/env");
            Object value = result.parameters.get(parameter);
            if (result.accessActuator && value instanceof String repository) {
                return new Repository(name, configuration, repository, "");
            }
            if (result.masked) {
                return new Repository(name, configuration, "",
                        "env is exposed, but the values are hidden (******): set management.endpoint.env.show-values to ALWAYS");
            }
            return new Repository(name, configuration, "", "The variable of the repository is not defined in the component");
        } catch (Exception e) {
            // The actuator does not answer, or does not expose env
            return new Repository(name, configuration, "", "env is not exposed");
        }
    }
}
