package io.camunda.blueberry.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class OperationException extends Exception {

    private static final Logger logger = LoggerFactory.getLogger(OperationException.class);
    // error message: Internal Server error, etc...
    private final String error;
    // Message: backupIdIs null for example
    private final String message;
    // Http status;: 400, 500
    private final int status;
    private final BLUEBERRYERRORCODE blueberryErrorCode;


    /**
     * @param blueberryErrorCode the Blueberry code, categorize the error
     * @param status             Http Status, returned by Zeebe or any other API called
     * @param error              the error title
     * @param message            the detailled message
     */
    public OperationException(BLUEBERRYERRORCODE blueberryErrorCode, int status, String error, String message) {
        this.blueberryErrorCode = blueberryErrorCode;
        this.status = status;
        this.error = error;
        this.message = message;
    }

    public static OperationException getInstanceFromCode(BLUEBERRYERRORCODE blueberryErrorCode, String error, String message) {
        return new OperationException(blueberryErrorCode, 500, error, message);

    }

    /**
     * Decode the error from an exception
     * Expected format: 400 : "{"message":"Cannot process backup requests. No backup store is configured. To use this feature, configure backup in broker configuration."}"
     *
     * @param blueberryErrorCode code where the error come from
     * @param e                  the exception
     */
    public static OperationException getInstanceFromException(BLUEBERRYERRORCODE blueberryErrorCode, Exception e) {

        String cause = e.getMessage();
        try {
            // Extract the status code
            String[] parts = cause.split(" : ", 2);
            int status = Integer.parseInt(parts[0].trim());

            // Extract and parse the JSON message
            String jsonPart = parts[1].trim(); // Remove extra quotes
            jsonPart = jsonPart.substring(1, jsonPart.length() - 1);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(jsonPart);

            String message = jsonNode.get("message").asText();

            return new OperationException(blueberryErrorCode, status, "Error", message);

        } catch (Exception decodeError) {
            logger.error("Can't decode message [{}], expected format is <StatusCode>: <Json>", cause);
        }
        return new OperationException(blueberryErrorCode, 500, "Error", e.getMessage());
    }

    public BLUEBERRYERRORCODE getBlueberryErrorCode() {
        return blueberryErrorCode;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public String getHumanInformation() {
        return "BlueberryCode[" + blueberryErrorCode + "] Status[" + status + "] Code[" + error + "] " + message;
    }

    public Map<String, Object> getRecord() {
        return Map.of("blueberryerrorcode", blueberryErrorCode.toString(),
                "status", status,
                "error", error,
                "message", message);

    }

    public enum BLUEBERRYERRORCODE {
        BACKUP_LIST, BACKUP, CHECK, KUBERNETES_CLIENT, ELASTICSEARCH_CLIENT, STATUS_EXPORTER, NO_ZEEBE_CONNECTION,
        NO_OPERATE_CONNECTION,
        NO_TASKLIST_CONNECTION,
        NO_OPTIMIZE_CONNECTION
    }
}
