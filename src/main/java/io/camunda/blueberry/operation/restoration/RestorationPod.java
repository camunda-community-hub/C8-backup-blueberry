package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.springframework.stereotype.Component;

/**
 * PwC 8.7 runbook section 3 step 8: set {@code ZEEBE_RESTORE} on the Zeebe StatefulSet and scale it up.
 * Charts before 12.1.0 ignore {@code ZEEBE_RESTORE}.
 */
@Component
public final class RestorationPod {

    public static final String SUCCESS_LOG = "Successfully restored broker from backup";

    public void restoreBrokers(KubernetesWorkload kubernetes,
                               RestoreLayout layout,
                               int replicas,
                               long backupId,
                               OperationLog log) throws OperationException {
        log.info(null, "Charts before 12.1.0 ignore ZEEBE_RESTORE. This step expects Helm chart 12.1.0 or later.");
        kubernetes.setStatefulSetEnv(layout.zeebe().name(), "ZEEBE_RESTORE", "true");
        kubernetes.setStatefulSetEnv(layout.zeebe().name(), "ZEEBE_RESTORE_FROM_BACKUP_ID", Long.toString(backupId));
        kubernetes.scale(layout.zeebe(), replicas);
        for (int ordinal = 0; ordinal < replicas; ordinal++) {
            String podName = layout.zeebePodName(ordinal);
            log.info(null, "Wait for restore log on " + podName);
            kubernetes.waitForPodLog(podName, SUCCESS_LOG, layout.zeebeRestoreTimeoutSeconds());
        }
    }
}
