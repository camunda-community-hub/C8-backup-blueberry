package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleOrchestrationSchedulerTest {

    private static Rule.RuleInfo check(Map<String, String> configuration) {
        // the actuator can't be reached: the values come from the configuration
        return new RuleOrchestrationScheduler(TestOrchestration.build(configuration, null)).check();
    }

    private static Rule.RuleInfo.Tuple verification(Rule.RuleInfo info, String startsWith) {
        return info.getListVerifications().stream().filter(v -> v.action() != null && v.action().startsWith(startsWith)).findFirst().orElseThrow();
    }

    @Test
    void theModeIsAnInfoWhateverItsValue() {
        for (Map<String, String> configuration : new Map[]{Map.of(), Map.of("backupContinuous", "false", "secondaryStorage", "elasticsearch")}) {
            Rule.RuleInfo info = check(configuration);
            assertEquals(Rule.RuleStatus.INFO, info.getListVerifications().get(0).actionStatus());
            assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
        }
        // mode unknown: nothing else is said
        assertEquals(1, check(Map.of()).getListVerifications().size());
    }

    @Test
    void continuousModeShowsTheRetentionPeriodInTheModeInformation() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupSchedule", "PT10M", "backupRetentionWindow", "P7D", "secondaryStorage", "elasticsearch"));

        assertEquals("Backup mode: [CONTINUOUS] (config)", info.getListVerifications().get(0).action());
        assertTrue(info.getListVerifications().get(0).error().contains("Backup retention period: [P7D] (config)"));
    }

    @Test
    void continuousModeWithSchedulerAndRetentionIsCorrect() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupSchedule", "PT10M", "backupRetentionWindow", "P7D", "secondaryStorage", "elasticsearch"));

        assertEquals(Rule.RuleStatus.CORRECT, verification(info, "Backup scheduler").actionStatus());
        assertEquals("Backup scheduler: [PT10M] (config)", verification(info, "Backup scheduler").action());
        assertEquals(Rule.RuleStatus.CORRECT, verification(info, "Backup retention period").actionStatus());
        assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
    }

    @Test
    void continuousModeWithoutSchedulerIsAnError() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupRetentionWindow", "P7D", "secondaryStorage", "elasticsearch"));

        assertEquals(Rule.RuleStatus.FAILED, verification(info, "Backup scheduler").actionStatus());
        assertEquals(Rule.RuleStatus.CORRECT, verification(info, "Backup retention period").actionStatus());
        assertEquals(Rule.RuleStatus.FAILED, info.getStatus());
    }

    @Test
    void continuousModeWithoutRetentionIsAnError() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupSchedule", "PT10M", "secondaryStorage", "elasticsearch"));

        assertEquals(Rule.RuleStatus.CORRECT, verification(info, "Backup scheduler").actionStatus());
        assertEquals(Rule.RuleStatus.FAILED, verification(info, "Backup retention period").actionStatus());
        assertEquals(Rule.RuleStatus.FAILED, info.getStatus());
    }

    @Test
    void elasticsearchOrOpensearch_ZeebeBacksUpTheSecondaryStorage() {
        for (String mode : new String[]{"false", "true"}) {
            for (String storage : new String[]{"elasticsearch", "opensearch"}) {
                Rule.RuleInfo info = check(Map.of("backupContinuous", mode, "backupSchedule", "PT10M", "backupRetentionWindow", "P7D", "secondaryStorage", storage));

                Rule.RuleInfo.Tuple tuple = verification(info, ("true".equals(mode) ? "Continuous" : "Manual") + " mode, secondary storage [" + storage + "] (config)");
                assertEquals(Rule.RuleStatus.CORRECT, tuple.actionStatus());
                assertTrue(tuple.action().endsWith("Zeebe will execute the backup on the secondary storage"));
            }
        }
    }

    @Test
    void rdbmsInManualModeZeebeMustBePaused() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "false", "secondaryStorage", "rdbms"));

        Rule.RuleInfo.Tuple tuple = verification(info, "Manual mode, secondary storage [rdbms] (config)");
        assertEquals(Rule.RuleStatus.INFO, tuple.actionStatus());
        assertTrue(tuple.error().contains("pause Zeebe"));
        assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
    }

    @Test
    void rdbmsInContinuousModeABackupMustExistInTheRetentionPeriod() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupSchedule", "PT10M", "backupRetentionWindow", "P7D", "secondaryStorage", "rdbms"));

        Rule.RuleInfo.Tuple tuple = verification(info, "Continuous mode, secondary storage [rdbms] (config)");
        assertEquals(Rule.RuleStatus.INFO, tuple.actionStatus());
        assertTrue(tuple.error().contains("independently of Zeebe"));
        assertTrue(tuple.error().contains("last [P7D]"));
    }

    @Test
    void unknownSecondaryStorageIsAnInformation() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "false"));

        assertEquals(Rule.RuleStatus.INFO, verification(info, "Manual mode, secondary storage [] (config)").actionStatus());
    }
}
