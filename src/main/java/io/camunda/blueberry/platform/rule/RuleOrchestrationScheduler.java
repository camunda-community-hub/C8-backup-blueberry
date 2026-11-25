package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The backup schedule of the orchestration cluster (Zeebe).
 * <ol>
 *     <li>Backup mode (CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_CONTINUOUS): an information, whatever the value</li>
 *     <li>CONTINUOUS: a scheduler (CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_SCHEDULE) must be set</li>
 *     <li>CONTINUOUS: a backup retention period (CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_RETENTION_WINDOW) must be set</li>
 * </ol>
 * In MANUAL mode, nobody starts or removes the backups but Blueberry (or the operator): the scheduler and the retention period
 * are not checked. Each verification gives the value in [] and its origin in ().
 */
@Component
public class RuleOrchestrationScheduler implements Rule {
    private final OrchestrationParameter orchestrationParameter;

    RuleOrchestrationScheduler(OrchestrationParameter orchestrationParameter) {
        this.orchestrationParameter = orchestrationParameter;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Orchestration Scheduler";
    }

    @Override
    public String getExplanations() {
        return "Zeebe can take its backups by itself (continuous mode: CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_CONTINUOUS=true). "
                + "In this mode a scheduler (CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_SCHEDULE) and a retention period "
                + "(CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_RETENTION_WINDOW) must be defined. "
                + "In manual mode, the backups are started by Blueberry (or the operator), and these two values are not used.";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of("https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/backup-and-restore/");
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);

        // 1. The mode: an information, whatever the result
        String mode = orchestrationParameter.getBackupMode();
        String modeOrigin = originOf(OrchestrationParameter.BACKUP_CONTINUOUS);
        String retention = orchestrationParameter.getParameterString(OrchestrationParameter.BACKUP_RETENTION_WINDOW);
        ruleInfo.addInfo("Backup mode: [" + mode + "] (" + modeOrigin + ")", null,
                mode.isEmpty() ? "The cluster does not define CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_CONTINUOUS"
                        : OrchestrationParameter.BACKUP_MODE_CONTINUOUS.equals(mode)
                        ? "Zeebe takes the backups by itself. Backup retention period: [" + retention + "] ("
                        + originOf(OrchestrationParameter.BACKUP_RETENTION_WINDOW) + ")"
                        : "The backups are started by Blueberry (or the operator)");

        // 2. The mode and the secondary storage: who backs up the secondary storage?
        checkSecondaryStorage(ruleInfo, mode, retention);

        if (OrchestrationParameter.BACKUP_MODE_CONTINUOUS.equals(mode)) {
            // 3. The scheduler
            checkDefined(ruleInfo, "Backup scheduler", OrchestrationParameter.BACKUP_SCHEDULE,
                    "The backup mode is CONTINUOUS: define a scheduler (CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_SCHEDULE, for example PT1H)");
            // 4. The retention period
            checkDefined(ruleInfo, "Backup retention period", OrchestrationParameter.BACKUP_RETENTION_WINDOW,
                    "The backup mode is CONTINUOUS: define a retention period (CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_RETENTION_WINDOW, for example P7D)");
        }

        if (ruleInfo.inProgress()) {
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }

    @Override
    public RuleInfo configure() {
        // Nothing to create: the values are defined in the environment of the cluster
        return check();
    }

    /**
     * The backup of the secondary storage according to the mode (there are two modes: the parameter is a boolean)
     * <ul>
     *     <li>Elasticsearch or OpenSearch: Zeebe takes the backup of the secondary storage (history backup), in both modes: CORRECT</li>
     *     <li>RDBMS, MANUAL: Zeebe must be paused while the database is backed up: INFO</li>
     *     <li>RDBMS, CONTINUOUS: the database can be backed up at any time, but a backup must exist in the retention period: INFO</li>
     * </ul>
     */
    private void checkSecondaryStorage(RuleInfo ruleInfo, String mode, String retention) {
        if (mode.isEmpty()) {
            // the mode is not known: nothing to say
            return;
        }
        boolean continuous = OrchestrationParameter.BACKUP_MODE_CONTINUOUS.equals(mode);
        String modeLabel = continuous ? "Continuous" : "Manual";
        String value = orchestrationParameter.getParameterString(OrchestrationParameter.SECONDARY_STORAGE);
        String origin = originOf(OrchestrationParameter.SECONDARY_STORAGE);
        String subject = modeLabel + " mode, secondary storage [" + value + "] (" + origin + ")";

        if (value.isEmpty()) {
            ruleInfo.addInfo(subject, null, "The secondary storage is not known: Blueberry can't say who backs it up");
            return;
        }

        String kind = orchestrationParameter.getSecondaryStorageKind();
        boolean search = "elasticsearch".equalsIgnoreCase(kind) || "opensearch".equalsIgnoreCase(kind);
        if (search) {
            ruleInfo.addVerifications(subject + ": Zeebe will execute the backup on the secondary storage", RuleStatus.CORRECT, null);
        } else if (!continuous) {
            ruleInfo.addInfo(subject, null, "Attention, with RDBMS it is crucial in MANUAL mode to pause Zeebe, back up the RDBMS at this moment, "
                    + "then unpause Zeebe");
        } else {
            ruleInfo.addInfo(subject, null, "An RDBMS backup can be taken at any time, independently of Zeebe, but a backup must be taken in the last ["
                    + retention + "] (backup retention period)");
        }
    }

    private void checkDefined(RuleInfo ruleInfo, String label, OrchestrationParameter.ParameterDefinition definition, String error) {
        String value = orchestrationParameter.getParameterString(definition);
        String action = label + ": [" + value + "] (" + originOf(definition) + ")";
        if (value.isEmpty()) {
            ruleInfo.addVerificationsWithError(action, RuleStatus.FAILED, null, error);
            ruleInfo.addError(error);
            ruleInfo.setStatus(RuleStatus.FAILED);
        } else {
            ruleInfo.addVerifications(action, RuleStatus.CORRECT, null);
        }
    }

    private String originOf(OrchestrationParameter.ParameterDefinition definition) {
        OrchestrationParameter.Parameter parameter = orchestrationParameter.getParameter(definition);
        return parameter == null ? "config" : parameter.getOriginLabel();
    }
}
