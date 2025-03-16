package io.camunda.blueberry.client;

import io.camunda.blueberry.client.toolbox.KubenetesToolbox;
import io.camunda.blueberry.client.toolbox.WebActuator;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class TaskListAPI implements CamundaApplication {

    private final BlueberryConfig blueberryConfig;
    private final WebActuator webActuator;
    private final KubenetesToolbox kubenetesToolbox;
    Logger logger = LoggerFactory.getLogger(TaskListAPI.class);

    public TaskListAPI(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        webActuator = new WebActuator(restTemplate);
        kubenetesToolbox = new KubenetesToolbox();
        this.blueberryConfig = blueberryConfig;
    }

    public void connection() {

    }

    public COMPONENT getComponent() {
        return COMPONENT.TASKLIST;
    }

    public boolean exist() {
        return kubenetesToolbox.isPodExist("tasklist");
    }

    public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        return webActuator.startBackup(CamundaApplication.COMPONENT.TASKLIST, backupId, blueberryConfig.getTasklistActuatorUrl(), operationLog);
    }

    public void waitBackup(Long backupId, OperationLog operationLog) {
        webActuator.waitBackup(CamundaApplication.COMPONENT.TASKLIST, backupId, blueberryConfig.getTasklistActuatorUrl(), operationLog);
    }

}

