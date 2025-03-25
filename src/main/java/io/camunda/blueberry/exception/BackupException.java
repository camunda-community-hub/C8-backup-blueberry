package io.camunda.blueberry.exception;

import io.camunda.blueberry.access.CamundaApplication;

import java.util.HashMap;
import java.util.Map;

public class BackupException extends OperationException {

    private final long backupId;
    private final CamundaApplication.COMPONENT component;

    public BackupException(CamundaApplication.COMPONENT component, int status, String error, String message, Long backupId) {
        super(BLUEBERRYERRORCODE.BACKUP, status, error, message);
        this.component = component;
        this.backupId = backupId;
    }

    public static BackupException getInstanceFromException(CamundaApplication.COMPONENT component, Long backupId, Exception e) {
        OperationException operationException = getInstanceFromException(BLUEBERRYERRORCODE.BACKUP, e);
        return new BackupException(component, operationException.getStatus(), operationException.getError(), operationException.getMessage(), backupId);
    }

    public long getBackupId() {
        return backupId;
    }

    public CamundaApplication.COMPONENT getComponent() {
        return component;
    }

    @Override
    public Map<String, Object> getRecord() {
        Map mapRecord = new HashMap<String, Object>();
        mapRecord.putAll(super.getRecord());
        mapRecord.put("backupId", backupId);
        mapRecord.put("component", component);
        return mapRecord;
    }


}