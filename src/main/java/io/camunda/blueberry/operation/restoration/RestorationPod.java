package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * PwC 8.7 runbook section 3 step 8: set {@code ZEEBE_RESTORE} on the Zeebe StatefulSet and scale it up.
 * <p>
 * With ZEEBE_RESTORE=true, the main container of each broker runs the restore, then exits. Kubernetes starts it again: the
 * restore runs again, finds a data directory which is not empty, and aborts, again and again, until ZEEBE_RESTORE is removed.
 * The log of the first run is lost after a restart, so two messages prove the restore ran:
 * <ul>
 *   <li>{@link #SUCCESS_LOG}: the first run was seen</li>
 *   <li>{@link #NOT_EMPTY_LOG}: the container restarted, the data directory is not empty: the first run already wrote the
 *   data, the restore is considered successful</li>
 * </ul>
 */
@Component
public final class RestorationPod {

    public static final String SUCCESS_LOG = "Successfully restored broker from backup";

    /**
     * Log of Zeebe: "Brokers's data directory /usr/local/zeebe/data is not empty. Aborting restore to avoid overwriting data."
     */
    public static final String NOT_EMPTY_LOG = "is not empty. Aborting restore to avoid overwriting data";

    public void restoreBrokers(KubernetesWorkload kubernetes,
                               RestoreLayout layout,
                               int replicas,
                               long backupId,
                               OperationLog log) throws OperationException {
        kubernetes.setStatefulSetEnv(layout.zeebe().name(), "ZEEBE_RESTORE", "true");
        kubernetes.setStatefulSetEnv(layout.zeebe().name(), "ZEEBE_RESTORE_FROM_BACKUP_ID", Long.toString(backupId));
        kubernetes.scale(layout.zeebe(), replicas);

        log.info(null, "Wait for the restore of the " + replicas + " brokers, one detection per broker at the same time. "
                + "Each pod is expected to log \"" + SUCCESS_LOG + "\", or \"" + NOT_EMPTY_LOG + "\" "
                + "(the container restarted after a first restore).");

        List<String> texts = List.of(SUCCESS_LOG, NOT_EMPTY_LOG);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, replicas));
        try {
            List<Future<String>> detections = new ArrayList<>();
            for (int ordinal = 0; ordinal < replicas; ordinal++) {
                String podName = layout.zeebePodName(ordinal);
                detections.add(executor.submit(() ->
                        kubernetes.waitForPodLogAny(podName, texts, layout.zeebeRestoreTimeoutSeconds())));
            }

            // The messages are written by this thread only, in the order of the brokers
            List<String> notDetected = new ArrayList<>();
            for (int ordinal = 0; ordinal < replicas; ordinal++) {
                String podName = layout.zeebePodName(ordinal);
                try {
                    report(log, podName, detections.get(ordinal).get());
                } catch (ExecutionException e) {
                    notDetected.add(podName);
                    log.error(null, "Pod " + podName + ": the restore was not detected. "
                            + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
                }
            }
            if (!notDetected.isEmpty()) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 500,
                        "Zeebe restore not detected",
                        "The restore of Zeebe was not detected on " + notDetected + ": neither \"" + SUCCESS_LOG
                                + "\" nor \"" + NOT_EMPTY_LOG + "\" was logged in the time allowed. Check the log of the pods: "
                                + "kubectl -n " + layout.namespace() + " logs <pod> --previous");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.KUBERNETES_CLIENT, 500,
                    "Restore interrupted", "Interrupted while waiting for the restore of Zeebe");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Tell the user which message was detected: the first run was seen, or only the next one
     */
    private static void report(OperationLog log, String podName, String detected) {
        if (SUCCESS_LOG.equals(detected)) {
            log.info(null, "Pod " + podName + ": restore succeeded. Detected \"" + SUCCESS_LOG + "\".");
        } else {
            log.info(null, "Pod " + podName + ": detected \"" + NOT_EMPTY_LOG + "\". The container restarted after a first restore "
                    + "(its log is lost), which wrote the data: the restore of this broker is considered successful.");
        }
    }
}
