package io.camunda.blueberry.connect;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.toolbox.KubenetesToolbox;
import io.camunda.blueberry.connect.toolbox.WebActuator;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class TaskListConnect implements CamundaApplicationInt {

    private final BlueberryConfig blueberryConfig;
    private final WebActuator webActuator;
    private final KubenetesToolbox kubenetesToolbox;
    Logger logger = LoggerFactory.getLogger(TaskListConnect.class);

    public TaskListConnect(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        webActuator = new WebActuator(restTemplate);
        kubenetesToolbox = new KubenetesToolbox();
        this.blueberryConfig = blueberryConfig;
    }

    public void connection() {

    }

    public boolean isConnected() {
        return webActuator.isConnected(COMPONENT.TASKLIST, blueberryConfig.getTasklistActuatorUrl() + "/actuator");
    }

    @Override
    public boolean isActive() {
        return blueberryConfig.getTasklistActuatorUrl() != null && blueberryConfig.getTasklistActuatorUrl().length() > 0;
    }

    /**
     * Return the connection information plus information on the way to connect, in order to give back more feedback
     *
     * @return
     */
    public CamundaApplicationInt.ConnectionInfo isConnectedInformation() {
        return new CamundaApplicationInt.ConnectionInfo(isConnected(), "Url Connection [" + blueberryConfig.getTasklistActuatorUrl() + "/actuator]");
    }

    public COMPONENT getComponent() {
        return COMPONENT.TASKLIST;
    }

    public boolean exist() {
        return kubenetesToolbox.isPodExist("tasklist");
    }

    @Override
    public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        return webActuator.startBackup(CamundaApplicationInt.COMPONENT.TASKLIST, backupId, blueberryConfig.getTasklistActuatorUrl(), operationLog);
    }

    @Override
    public BackupOperation waitBackup(Long backupId, OperationLog operationLog) {
        return webActuator.waitBackup(CamundaApplicationInt.COMPONENT.TASKLIST, backupId, blueberryConfig.getTasklistActuatorUrl(), operationLog);
    }

    @Override
    public List<BackupInfo> getListBackups() throws OperationException {
        return webActuator.getListBackups(COMPONENT.TASKLIST, getUrlListBackup());
    }

    @Override
    public String getUrlListBackup() {
        return  blueberryConfig.getTasklistActuatorUrl()+"/actuator/backups";
    }
}

