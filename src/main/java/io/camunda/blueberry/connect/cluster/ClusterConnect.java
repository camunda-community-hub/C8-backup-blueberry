package io.camunda.blueberry.connect.cluster;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.List;

/**
 * The platform where the components of Camunda run (Kubernetes, Docker). Blueberry uses it to stop and start what the
 * REST API of Camunda can't: Optimize, during the restore.
 * The implementation used is given by blueberry.cluster.type, see {@link ClusterFactory}.
 */
public interface ClusterConnect {

    /**
     * @return "kubernetes" or "docker"
     */
    String getName();

    /**
     * Check that Blueberry can reach the platform and act on Optimize. Nothing is changed.
     *
     * @return the verifications, in order
     */
    List<Step> checkAccess();

    /**
     * Is the cluster reachable and usable? The same verifications as the check of the rule (see {@link #checkAccess()}): true when none fails.
     */
    default boolean ping() {
        return checkAccess().stream().noneMatch(step -> step.level() == Level.FAILED);
    }

    /**
     * Stop Optimize (Kubernetes: scale down to 0, Docker: stop the container) and wait until it is stopped.
     *
     * @return the number of replicas before the stop (0 if Optimize was already stopped), to give to {@link #startOptimize(int, OperationLog)}
     */
    int stopOptimize(OperationLog log) throws OperationException;

    /**
     * Start Optimize again (Kubernetes: scale up to the replicas it had, Docker: start the container). Nothing is done if replicas is 0.
     */
    void startOptimize(int replicas, OperationLog log) throws OperationException;

    enum Level {CORRECT, FAILED, INFO}

    /**
     * One verification of {@link #checkAccess()}
     *
     * @param error         the explanation when the verification fails, or the information
     * @param configuration an example of configuration to fix the problem, may be null
     */
    record Step(Level level, String action, String command, String error, String configuration) {
        public static Step correct(String action, String command) {
            return new Step(Level.CORRECT, action, command, null, null);
        }

        public static Step failed(String action, String command, String error, String configuration) {
            return new Step(Level.FAILED, action, command, error, configuration);
        }

        public static Step info(String action, String command, String message) {
            return new Step(Level.INFO, action, command, message, null);
        }
    }
}
