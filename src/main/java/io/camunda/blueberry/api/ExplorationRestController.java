package io.camunda.blueberry.api;


import io.camunda.blueberry.access.KubernetesAccess;
import io.camunda.blueberry.access.OperationResult;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.config.ExplorationCluster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("blueberry")

public class ExplorationRestController {

    @Autowired
    KubernetesAccess kubernetesAccess;

    @Autowired
    BlueberryConfig blueberryConfig;

    @Autowired
    ExplorationCluster explorationCluster;

    @GetMapping(value = "/api/exploration/cluster", produces = "application/json")
    public Map<String, Object> getClusterInformation() {
        Map<String, Object> result = new HashMap<>();
        OperationResult operationResult = explorationCluster.getRepositoryPerComponent();
        result.put("containers", operationResult.resultMap);
        return result;
    }

}
