package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.connect.ZeebeConnect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blueberry backs up and restores Camunda 8.8 and above (the backup and restore REST API of the cluster).
 * The version is the one the gateway of Zeebe gives (the topology).
 */
@Component
public class RuleCamundaVersion implements Rule {

    static final int MINIMUM_MAJOR = 8;
    static final int MINIMUM_MINOR = 8;
    private static final Pattern VERSION = Pattern.compile("^v?([0-9]+)[.]([0-9]+)(?:[.]([0-9]+))?.*");

    private final Supplier<String> versionSupplier;

    @Autowired
    public RuleCamundaVersion(ZeebeConnect zeebeConnect) {
        this(zeebeConnect::getZeebeVersion);
    }

    RuleCamundaVersion(Supplier<String> versionSupplier) {
        this.versionSupplier = versionSupplier;
    }

    /**
     * @return {major, minor} of a version like 8.10.1 or 8.8.0-alpha1, null if it is not a version
     */
    static Integer[] parse(String version) {
        Matcher matcher = VERSION.matcher(version.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new Integer[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    static boolean isAtLeast(Integer[] version, int major, int minor) {
        return version[0] > major || (version[0] == major && version[1] >= minor);
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Camunda Version";
    }

    @Override
    public String getExplanations() {
        return "Blueberry works with Camunda " + MINIMUM_MAJOR + "." + MINIMUM_MINOR + " or above";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        String command = "GET /v2/topology (gatewayVersion)";
        String minimum = MINIMUM_MAJOR + "." + MINIMUM_MINOR;
        String version = versionSupplier.get();

        if (version == null || version.isBlank()) {
            ruleInfo.addVerificationsWithError("Read the version of Camunda", RuleStatus.FAILED, command,
                    "The version can't be read: the cluster does not answer. Check the connection to Zeebe.");
            ruleInfo.addError("The Camunda version is unknown");
            ruleInfo.setStatus(RuleStatus.FAILED);
            return ruleInfo;
        }
        Integer[] parts = parse(version);
        if (parts == null) {
            ruleInfo.addVerificationsWithError("Read the version of Camunda", RuleStatus.FAILED, command,
                    "The version [" + version + "] is not understood (expected 8.x.y).");
            ruleInfo.addError("The Camunda version is not understood");
            ruleInfo.setStatus(RuleStatus.FAILED);
            return ruleInfo;
        }

        if (isAtLeast(parts, MINIMUM_MAJOR, MINIMUM_MINOR)) {
            ruleInfo.addVerifications("Camunda version is " + version + ", " + minimum + " or above", RuleStatus.CORRECT, command);
            ruleInfo.addDetails("Camunda " + version);
            ruleInfo.setStatus(RuleStatus.CORRECT);
        } else {
            ruleInfo.addVerificationsWithError("Camunda version is " + version + ", it must be " + minimum + " or above", RuleStatus.FAILED, command,
                    "Blueberry uses the backup and restore REST API of Camunda " + minimum + " and above. Upgrade the cluster.");
            ruleInfo.addError("Camunda " + version + " is older than " + minimum);
            ruleInfo.setStatus(RuleStatus.FAILED);
        }
        return ruleInfo;
    }

    @Override
    public RuleInfo configure() {
        return check();
    }
}
