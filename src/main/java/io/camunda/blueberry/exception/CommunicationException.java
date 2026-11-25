package io.camunda.blueberry.exception;

import io.camunda.blueberry.connect.BackupComponentInt;

public class CommunicationException extends OperationException {

    public CommunicationException(OperationException.BLUEBERRYERRORCODE blueberryErrorCode, Exception e) {
        super(blueberryErrorCode, 0, "No communication", e.getMessage());
    }

    public static CommunicationException getInstanceFromComponent(BackupComponentInt.COMPONENT component, Exception e) {
        OperationException.BLUEBERRYERRORCODE blueberryErrorCode;
        switch (component) {
            case ZEEBE:
                blueberryErrorCode = BLUEBERRYERRORCODE.NO_ZEEBE_CONNECTION;
                break;
            case OPTIMIZE:
                blueberryErrorCode = BLUEBERRYERRORCODE.NO_OPTIMIZE_CONNECTION;
                break;
            default:
                blueberryErrorCode = BLUEBERRYERRORCODE.NO_ZEEBE_CONNECTION;
                break;
        }
        return new CommunicationException(blueberryErrorCode, e);
    }
}

