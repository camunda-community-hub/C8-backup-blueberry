package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OptimizeConnect;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleOptimizePresentTest {

    private static BlueberryConfig config(boolean enabled, String optimizeUrl, String zeebeUrl) throws Exception {
        BlueberryConfig config = new BlueberryConfig();
        set(config, "optimizeEnabled", enabled);
        set(config, "optimizeActuatorUrl", optimizeUrl);
        set(config, "zeebeActuatorUrl", zeebeUrl);
        return config;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * @param answers true: Optimize answers on its actuator (the cluster)
     */
    private static OptimizeConnect optimize(BlueberryConfig config, boolean answers) {
        return new OptimizeConnect(config, new RestTemplate()) {
            @Override
            public boolean isConnected() {
                return answers;
            }
        };
    }

    private static Rule.RuleInfo check(BlueberryConfig config, boolean answers) {
        return new RuleOptimizePresent(config, optimize(config, answers)).check();
    }

    @Test
    void theRuleIsCalledOptimisePresent() throws Exception {
        BlueberryConfig config = config(true, "http://o:8092", "http://z:9600");
        assertEquals("Optimise is present", new RuleOptimizePresent(config, optimize(config, true)).getName());
    }

    @Test
    void theClusterFirst_OptimizeIsPresentEvenWhenTheConfigurationSaysFalse() throws Exception {
        BlueberryConfig config = config(false, "http://o:8092", "http://z:9600");

        Rule.RuleInfo info = check(config, true);

        assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
        assertEquals(Rule.RuleStatus.CORRECT, info.getListVerifications().get(0).actionStatus());
        assertTrue(info.getListVerifications().get(0).action().contains("(actuator)"));
        // and the other components see it as present
        assertTrue(optimize(config, true).isActive());
        assertEquals("actuator", optimize(config, true).getPresenceOrigin());
    }

    @Test
    void ifTheClusterDoesNotAnswer_TheConfigurationDecides() throws Exception {
        BlueberryConfig disabled = config(false, "http://o:8092", "http://z:9600");
        Rule.RuleInfo absent = check(disabled, false);
        assertEquals(Rule.RuleStatus.CORRECT, absent.getStatus());
        assertEquals(Rule.RuleStatus.INFO, absent.getListVerifications().get(0).actionStatus());
        assertEquals(false, optimize(disabled, false).isActive());

        BlueberryConfig enabled = config(true, "http://o:8092", "http://z:9600");
        Rule.RuleInfo failed = check(enabled, false);
        assertEquals(Rule.RuleStatus.FAILED, failed.getStatus());
        assertEquals(true, optimize(enabled, false).isActive());
        assertEquals("config", optimize(enabled, false).getPresenceOrigin());
    }

    @Test
    void noUrlOrTheUrlOfZeebeIsNotOptimize() throws Exception {
        for (String url : new String[]{"@null", "", "http://localhost:9600/"}) {
            BlueberryConfig config = config(true, url, "http://localhost:9600");
            Rule.RuleInfo info = check(config, true);
            assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
            assertEquals(Rule.RuleStatus.INFO, info.getListVerifications().get(0).actionStatus());
            assertEquals(false, optimize(config, true).isActive());
        }
    }
}
