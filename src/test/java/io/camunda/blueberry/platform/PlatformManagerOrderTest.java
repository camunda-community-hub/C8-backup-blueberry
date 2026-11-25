package io.camunda.blueberry.platform;

import io.camunda.blueberry.platform.rule.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformManagerOrderTest {

    private static Rule rule(String name) {
        return new Rule() {
            public boolean validRule() {
                return true;
            }

            public String getName() {
                return name;
            }

            public String getExplanations() {
                return "";
            }

            public List<String> getUrlDocumentation() {
                return List.of();
            }

            public RuleInfo check() {
                return new RuleInfo(this);
            }

            public RuleInfo configure() {
                return check();
            }
        };
    }

    @Test
    void theRulesAreAlwaysInTheSameOrder() {
        Rule b = rule("B rule");
        Rule a = rule("A rule");
        Rule c = rule("C rule");

        List<String> first = new PlatformManager(List.of(b, c, a), null).getOrderedRules().stream().map(Rule::getName).toList();
        List<String> second = new PlatformManager(List.of(a, b, c), null).getOrderedRules().stream().map(Rule::getName).toList();

        assertEquals(List.of("A rule", "B rule", "C rule"), first);
        assertEquals(first, second);
    }
}
