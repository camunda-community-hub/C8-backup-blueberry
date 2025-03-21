package io.camunda.blueberry.api;


import io.camunda.blueberry.platform.PlatformManager;
import io.camunda.blueberry.platform.rule.Rule;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("blueberry")

public class ConfigurationRestController {

    Logger logger = LoggerFactory.getLogger(ConfigurationRestController.class);

    @Autowired
    private PlatformManager platformManager;

    /**
     * Check the system
     * Does Zeebe declare a container? A Type storage?
     * Does Elasticsearch as repository for each component like OperateAccess?
     *
     * @return
     */
    @GetMapping(value = "/api/configuration/check", produces = "application/json")
    public List<Map<@NotNull String, Object>> check() {
        try {
            logger.debug("Rest [/api/configuration/check]");
            List<Rule.RuleInfo> listRules = platformManager.checkAllRules();
            logger.info("End Rest [/api/platform/check] {} rules", listRules.size());
            return listRules.stream()
                    .sorted(Comparator.comparing(Rule.RuleInfo::getName)) // Sort by name
                    .map(ruleInfo -> {
                        Map<String, Object> info = Map.of("name", ruleInfo.getName(),
                                "valid", ruleInfo.isValid(),
                                "status", ruleInfo.getStatus().toString(),
                                "detail", ruleInfo.getDetails(),
                                "explanations", ruleInfo.getRule().getExplanations(),
                                "urldocumentation", ruleInfo.getRule().getUrlDocumentation(),
                                "verifications", ruleInfo.getListVerifications()
                                        .stream()
                                        .map(tuple -> {
                                            return Map.of(
                                                    "action", tuple.action(),
                                                    "actionStatus", tuple.actionStatus().toString(),
                                                    "command", tuple.command()==null? "": tuple.command()
                                            );
                                        })
                                        .toList());
                        return info;
                    })
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping(value = "/api/configuration/configure", produces = "application/json")
    public List<Map<@NotNull String, Object>> configure() {

        try {
            logger.debug("Rest [/api/configuration/check]");
            List<Rule.RuleInfo> listRules = platformManager.configure();
            logger.info("End Rest [/api/platform/configure] {} rules", listRules.size());
            return listRules.stream()
                    .sorted(Comparator.comparing(Rule.RuleInfo::getName)) // Sort by name
                    .map(ruleInfo -> {
                        Map<String, Object> info = Map.of("name", ruleInfo.getName(),
                                "valid", ruleInfo.isValid(),
                                "status", ruleInfo.getStatus().toString(),
                                "detail", ruleInfo.getDetails(),
                                "explanations", ruleInfo.getRule().getExplanations(),
                                "urldocumentation", ruleInfo.getRule().getUrlDocumentation(),
                                "verifications", ruleInfo.getListVerifications()
                                        .stream()
                                        .map(tuple -> {
                                            return Map.of(
                                                    "action", tuple.action(),
                                                    "actionStatus", tuple.actionStatus().toString()
                                            );
                                        })
                                        .toList());
                        return info;
                    })
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
