package io.camunda.blueberry.client;

import io.camunda.blueberry.client.toolbox.KubenetesToolbox;
import io.camunda.blueberry.client.toolbox.WebActuator;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.operation.OperationLog;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Component
/**
 * Manage communication to OperateAPI
 */
public class OperateAPI implements CamundaApplication {


    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;
    private final WebActuator webActuator;
    private final KubenetesToolbox kubenetesToolbox;

    public OperateAPI(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        webActuator = new WebActuator(restTemplate);
        kubenetesToolbox = new KubenetesToolbox();
        this.blueberryConfig = blueberryConfig;
        this.restTemplate = restTemplate;
    }

    public void connection() {

    }


    public boolean exist() {
        return kubenetesToolbox.isPodExist("operate");
    }


    public CamundaApplication.BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        return webActuator.startBackup(CamundaApplication.COMPONENT.OPERATE, backupId, blueberryConfig.getOperateActuatorUrl(), operationLog);

    }



public void waitBackup(Long backupId, OperationLog operationLog) {
    webActuator.waitBackup(CamundaApplication.COMPONENT.OPERATE, backupId, blueberryConfig.getOperateActuatorUrl(), operationLog);
}

/**
 * According to the documentation, Operate has a API to get all backup
 * https://docs.camunda.io/docs/8.7/self-managed/operational-guides/backup-restore/operate-tasklist-backup/#get-backups-list-api
 */
public List<BackupInfo> getListBackup() {
    return Collections.emptyList();
}


}
