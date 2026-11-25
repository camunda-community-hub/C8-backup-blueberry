package io.camunda.blueberry.api;

import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupRestControllerTest {

    private static final String ENV = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
            + "\"CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_CONTINUOUS\":{\"value\":\"true\"},"
            + "\"CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_SCHEDULE\":{\"value\":\"PT30M\"}}}]}";

    @Test
    void theParametersComeFromTheClusterThenFromTheConfiguration() {
        BackupRestController controller = new BackupRestController(null, null,
                TestOrchestration.build(Map.of("backupRetentionWindow", "P7D", "backupSchedule", "PT10M"), ENV));

        Map<String, Object> parameter = controller.backupParameter();

        assertEquals("CONTINUOUS", parameter.get("mode"));
        assertEquals("ACTUATOR", parameter.get("modeOrigin"));
        // the cluster wins over the configuration
        assertEquals("PT30M", parameter.get("schedule"));
        assertEquals("ACTUATOR", parameter.get("scheduleOrigin"));
        // not in the cluster: the configuration
        assertEquals("P7D", parameter.get("retentionWindow"));
        assertEquals("CONFIG", parameter.get("retentionWindowOrigin"));
    }

    @Test
    void manualModeAndUnknownValues() {
        BackupRestController controller = new BackupRestController(null, null,
                TestOrchestration.build(Map.of("backupContinuous", "false"), null));

        Map<String, Object> parameter = controller.backupParameter();

        assertEquals("MANUAL", parameter.get("mode"));
        assertEquals("CONFIG", parameter.get("modeOrigin"));
        assertEquals("", parameter.get("schedule"));
        assertEquals("", parameter.get("scheduleOrigin"));
    }

    @Test
    void theSchedulerAndTheRetentionAreBlankInManualMode() {
        // the configuration of Blueberry gives values, but the cluster is in manual mode: they are not used
        BackupRestController controller = new BackupRestController(null, null,
                TestOrchestration.build(Map.of("backupContinuous", "false", "backupSchedule", "PT10M", "backupRetentionWindow", "P7D"), null));

        Map<String, Object> parameter = controller.backupParameter();

        assertEquals("MANUAL", parameter.get("mode"));
        assertEquals("", parameter.get("schedule"));
        assertEquals("", parameter.get("scheduleOrigin"));
        assertEquals("", parameter.get("retentionWindow"));
        assertEquals("", parameter.get("retentionWindowOrigin"));
    }
}
