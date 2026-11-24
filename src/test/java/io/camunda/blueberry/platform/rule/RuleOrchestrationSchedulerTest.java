package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleOrchestrationSchedulerTest {

    private static Rule.RuleInfo check(Map<String, String> configuration) {
        // the actuator can't be reached: the values come from the configuration
        return new RuleOrchestrationScheduler(TestOrchestration.build(configuration, null)).check();
    }

    @Test
    void theModeIsAnInfoWhateverItsValue() {
        for (Map<String, String> configuration : new Map[]{Map.of(), Map.of("backupContinuous", "false")}) {
            Rule.RuleInfo info = check(configuration);
            assertEquals(Rule.RuleStatus.INFO, info.getListVerifications().get(0).actionStatus());
            // nothing else is checked outside of the continuous mode
            assertEquals(1, info.getListVerifications().size());
            assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
        }
    }

    @Test
    void continuousModeWithSchedulerAndRetentionIsCorrect() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupSchedule", "PT10M", "backupRetentionWindow", "P7D"));

        assertEquals(3, info.getListVerifications().size());
        assertEquals("Backup mode: [CONTINUOUS] (config)", info.getListVerifications().get(0).action());
        assertEquals("Backup scheduler: [PT10M] (config)", info.getListVerifications().get(1).action());
        assertEquals(Rule.RuleStatus.CORRECT, info.getListVerifications().get(1).actionStatus());
        assertEquals(Rule.RuleStatus.CORRECT, info.getListVerifications().get(2).actionStatus());
        assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
    }

    @Test
    void continuousModeWithoutSchedulerIsAnError() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupRetentionWindow", "P7D"));

        assertEquals(Rule.RuleStatus.FAILED, info.getListVerifications().get(1).actionStatus());
        assertEquals(Rule.RuleStatus.CORRECT, info.getListVerifications().get(2).actionStatus());
        assertEquals(Rule.RuleStatus.FAILED, info.getStatus());
    }

    @Test
    void continuousModeWithoutRetentionIsAnError() {
        Rule.RuleInfo info = check(Map.of("backupContinuous", "true", "backupSchedule", "PT10M"));

        assertEquals(Rule.RuleStatus.CORRECT, info.getListVerifications().get(1).actionStatus());
        assertEquals(Rule.RuleStatus.FAILED, info.getListVerifications().get(2).actionStatus());
        assertEquals(Rule.RuleStatus.FAILED, info.getStatus());
    }
}
