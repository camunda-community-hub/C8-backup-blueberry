package io.camunda.blueberry.platform.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule
 */
public interface Rule {

    boolean validRule();

    String getName();

    /* Explain what the rule is doing, what it check */
    String getExplanations();

    List<String> getUrlDocumentation();

    /* Check the rule */
    RuleInfo check();

    /**
     * Configure : run the configuration to create the environnement to run backups
     *
     * @return
     */
    RuleInfo configure();


    enum RuleStatus {DEACTIVATED, CORRECT, FAILED, INPROGRESS, FAILEDBUTWILLBEFIXED}


    /**
     * A rule is executed, and this is the information returned for this execution
     */
    class RuleInfo {
        private final Rule rule;
        private final StringBuilder details = new StringBuilder();
        private final StringBuilder errors = new StringBuilder();
        private final List<Tuple> listVerifications = new ArrayList();
        private boolean valid;
        private RuleStatus status = RuleStatus.INPROGRESS;

        public RuleInfo(Rule rule) {
            this.rule = rule;
        }

        public String getName() {
            return rule.getName();
        }

        public void addDetails(String details) {
            this.details.append(details + ";");
        }

        public void addError(String error) {
            this.errors.append(error + ";");
        }

        public String getDetails() {
            if (status == RuleStatus.DEACTIVATED) {
                return "The component is deactivated, check is not necessary.";
            }
            return details.toString();
        }

        public String getErrors() {
            return errors.toString();
        }

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public RuleStatus getStatus() {
            return status;
        }

        public void setStatus(RuleStatus status) {
            this.status = status;
        }

        public boolean inProgress() {
            return status == RuleStatus.INPROGRESS;
        }

        public List<Tuple> getListVerifications() {
            return listVerifications;
        }

        public void addVerifications(String action, RuleStatus actionStatus, String command) {
            this.listVerifications.add(new Tuple(action, actionStatus, command));
        }

        public void addVerificationsAssertBoolean(String action, boolean status, String command) {
            this.listVerifications.add(new Tuple(action, status ? RuleStatus.CORRECT : RuleStatus.FAILED, command));
        }

        public void addVerificationsButWillBeFixed(String action, RuleStatus actionStatus, String command) {
            if (actionStatus.equals(RuleStatus.FAILED)) {
                actionStatus = RuleStatus.FAILEDBUTWILLBEFIXED;
            }
            this.listVerifications.add(new Tuple(action, actionStatus, command));
        }

        public Rule getRule() {
            return this.rule;
        }

        public record Tuple(String action, RuleStatus actionStatus, String command) {
        }
    }
}