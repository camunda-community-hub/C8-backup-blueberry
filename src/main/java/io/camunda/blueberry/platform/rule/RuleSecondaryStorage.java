package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.connect.secondary.SecondaryStorageDetector;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Get the secondary storage of the cluster (Elasticsearch, OpenSearch, a database), and check that Blueberry knows how to
 * back it up.
 * The value is detected on the cluster (/actuator/env of Zeebe: camunda.data.secondary-storage.type). If the detection is
 * not possible, the value of the configuration (blueberry.secondaryStorage) is used.
 */
@Component
public class RuleSecondaryStorage implements Rule {
    private final SecondaryStorageFactory secondaryStorageFactory;

    RuleSecondaryStorage(SecondaryStorageFactory secondaryStorageFactory) {
        this.secondaryStorageFactory = secondaryStorageFactory;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Secondary storage";
    }

    @Override
    public String getExplanations() {
        return "The data of Operate and Tasklist (and the Zeebe records) are in the secondary storage: Elasticsearch, OpenSearch or a database. "
                + "Blueberry reads it in the configuration of the cluster (camunda.data.secondary-storage.type, via the actuator 'env' of Zeebe). "
                + "If the cluster does not give it, Blueberry uses the value blueberry.secondaryStorage of its configuration. "
                + "The backup of the secondary storage is different for each type.";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of("https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/backup-and-restore/");
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        SecondaryStorageDetector.Detection detection = secondaryStorageFactory.detect();

        // Detected on the cluster?
        if (!detection.detected().isEmpty()) {
            ruleInfo.addVerifications("Secondary storage detected on the cluster: [" + detection.detected() + "]",
                    RuleStatus.CORRECT, null);
        } else {
            ruleInfo.addVerifications("Secondary storage not detected on the cluster. " + detection.note(),
                    RuleStatus.DEACTIVATED, null);
        }

        // In the configuration? (option B)
        if (!detection.configured().isEmpty()) {
            ruleInfo.addVerifications("Secondary storage in the Blueberry configuration (blueberry.secondaryStorage): ["
                    + detection.configured() + "]", RuleStatus.CORRECT, null);
        } else {
            ruleInfo.addVerifications("No secondary storage in the Blueberry configuration (blueberry.secondaryStorage)",
                    RuleStatus.DEACTIVATED, null);
        }

        // Both are given, and differ? ("rdbms" is a type: the configuration gives the database)
        String detected = normalize(detection.detected());
        String configured = normalize(detection.configured());
        boolean sameStorage = detected.equals(configured) || "rdbms".equals(detected);
        if (!detected.isEmpty() && !configured.isEmpty() && !sameStorage) {
            ruleInfo.addVerifications("The configuration [" + detection.configured() + "] is not the secondary storage of the cluster ["
                    + detection.detected() + "]", RuleStatus.FAILED, null);
            ruleInfo.addError("blueberry.secondaryStorage is [" + detection.configured() + "] but the cluster uses [" + detection.detected()
                    + "]. Correct the configuration, or remove it.");
            ruleInfo.setStatus(RuleStatus.FAILED);
        }

        if (detection.value().isEmpty()) {
            ruleInfo.addError("The secondary storage is unknown: it is not detected, and blueberry.secondaryStorage is not set. "
                    + "Set blueberry.secondaryStorage to one of: " + String.join(", ", secondaryStorageFactory.getSupportedNames()));
            ruleInfo.addVerifications("Secondary storage used by the backup: none", RuleStatus.FAILED, null);
            ruleInfo.setStatus(RuleStatus.FAILED);
            return ruleInfo;
        }

        // Does Blueberry know how to back it up?
        SecondaryStorageInt secondaryStorage = secondaryStorageFactory.getSecondaryStorage();
        if (secondaryStorage != null) {
            ruleInfo.addVerifications("Backup of [" + detection.value() + "] by " + secondaryStorage.getName()
                    + " (" + detection.source() + ")", RuleStatus.CORRECT, null);
            ruleInfo.addDetails("Secondary storage: " + detection.value() + " (" + detection.source() + ")");
        } else {
            ruleInfo.addVerifications("Backup of [" + detection.value() + "]: Blueberry has no implementation", RuleStatus.FAILED, null);
            ruleInfo.addError("Blueberry can't back up the secondary storage [" + detection.value() + "]. Supported: "
                    + String.join(", ", secondaryStorageFactory.getSupportedNames()));
            ruleInfo.setStatus(RuleStatus.FAILED);
        }

        if (ruleInfo.inProgress()) {
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }

    @Override
    public RuleInfo configure() {
        // Nothing to create: this rule only reads
        return check();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
