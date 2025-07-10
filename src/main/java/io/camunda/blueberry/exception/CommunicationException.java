package io.camunda.blueberry.exception;

import io.camunda.blueberry.connect.CamundaApplicationInt;

public class CommunicationException extends OperationException {

    public CommunicationException(OperationException.BLUEBERRYERRORCODE blueberryErrorCode, Exception e) {
        super(blueberryErrorCode, 0, "No communication", e.getMessage());
    }

    public static CommunicationException getInstanceFromComponent(CamundaApplicationInt.COMPONENT component, Exception e) {
        OperationException.BLUEBERRYERRORCODE blueberryErrorCode;
        switch (component) {
            case TASKLIST:
                blueberryErrorCode = BLUEBERRYERRORCODE.NO_TASKLIST_CONNECTION;
                break;
            case OPERATE:
                blueberryErrorCode = BLUEBERRYERRORCODE.NO_OPERATE_CONNECTION;
                break;
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
