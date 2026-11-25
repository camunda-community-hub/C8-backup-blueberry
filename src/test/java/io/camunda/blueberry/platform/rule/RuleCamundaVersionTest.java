package io.camunda.blueberry.platform.rule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCamundaVersionTest {

    private static Rule.RuleInfo check(String version) {
        return new RuleCamundaVersion(() -> version).check();
    }

    @Test
    void version88AndAboveIsCorrect() {
        for (String version : new String[]{"8.8.0", "8.8.3", "8.9.1", "8.10.0", "8.10.2-alpha1", "9.0.0"}) {
            Rule.RuleInfo info = check(version);
            assertEquals(Rule.RuleStatus.CORRECT, info.getStatus(), version);
            assertEquals(Rule.RuleStatus.CORRECT, info.getListVerifications().get(0).actionStatus(), version);
            assertTrue(info.getListVerifications().get(0).action().contains(version), version);
        }
    }

    @Test
    void anOlderVersionFails() {
        for (String version : new String[]{"8.7.5", "8.6.0", "7.20.0"}) {
            assertEquals(Rule.RuleStatus.FAILED, check(version).getStatus(), version);
        }
    }

    @Test
    void anUnknownVersionFails() {
        assertEquals(Rule.RuleStatus.FAILED, check("").getStatus());
        assertEquals(Rule.RuleStatus.FAILED, check(null).getStatus());
        assertEquals(Rule.RuleStatus.FAILED, check("snapshot").getStatus());
    }
}
