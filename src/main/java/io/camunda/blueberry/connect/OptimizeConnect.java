package io.camunda.blueberry.connect;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.cluster.ClusterConnect;
import io.camunda.blueberry.connect.cluster.ClusterFactory;
import io.camunda.blueberry.connect.toolbox.WebActuator;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class OptimizeConnect implements BackupComponentInt {

    /**
     * The result of the question to the actuator is kept some seconds: isActive() is asked often
     */
    private static final long CACHE_MILLIS = 10_000;
    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;
    private final WebActuator webActuator;
    private final ClusterFactory clusterFactory;
    Logger logger = LoggerFactory.getLogger(OptimizeConnect.class.getName());
    private volatile boolean reachable;
    private volatile long reachableTime;

    public OptimizeConnect(BlueberryConfig blueberryConfig, RestTemplate restTemplate, ClusterFactory clusterFactory) {
        webActuator = new WebActuator(restTemplate);
        this.clusterFactory = clusterFactory;

        this.blueberryConfig = blueberryConfig;
        this.restTemplate = restTemplate;
    }

    public void connection() {
    }

    public boolean isConnected() {
        return webActuator.isConnected(COMPONENT.OPTIMIZE, blueberryConfig.getOptimizeActuatorUrl() + "/actuator");
    }

    /**
     * @return true if Blueberry can ask Optimize: an URL is set (blueberry.optimizeActuatorUrl), and it is not the URL of Zeebe
     * (Optimize has no actuator of its own at the URL of Zeebe)
     */
    public boolean hasActuatorUrl() {
        String url = blueberryConfig.getOptimizeActuatorUrl();
        if (url == null || url.isBlank() || "@null".equals(url)) {
            return false;
        }
        String zeebeUrl = blueberryConfig.getZeebeActuatorUrl();
        return zeebeUrl == null || !url.trim().replaceAll("/+$", "").equalsIgnoreCase(zeebeUrl.trim().replaceAll("/+$", ""));
    }

    /**
     * Ask the cluster: does Optimize answer on its actuator?
     */
    public boolean isReachable() {
        if (!hasActuatorUrl()) {
            return false;
        }
        if (System.currentTimeMillis() - reachableTime < CACHE_MILLIS) {
            return reachable;
        }
        boolean answer;
        try {
            answer = isConnected();
        } catch (Exception e) {
            answer = false;
        }
        reachable = answer;
        reachableTime = System.currentTimeMillis();
        return answer;
    }

    /**
     * Is Optimize part of the cluster? The cluster first: if Optimize answers on its actuator, it is there, even if
     * blueberry.optimize.enabled is false. If it does not answer, the configuration (blueberry.optimize.enabled) decides.
     */
    @Override
    public boolean isActive() {
        return hasActuatorUrl() && (isReachable() || blueberryConfig.isOptimizeEnabled());
    }

    /**
     * @return where the answer of isActive() comes from: actuator (Optimize answers), else config
     */
    public String getPresenceOrigin() {
        return isReachable() ? "actuator" : "config";
    }

    /**
     * Return the connection information plus information on the way to connect, in order to give back more feedback
     *
     * @return
     */
    public ConnectionInfo isConnectedInformation() {
        return new ConnectionInfo(isConnected(), "Url Connection [" + blueberryConfig.getOptimizeActuatorUrl() + "/actuator]");
    }

    public COMPONENT getComponent() {
        return COMPONENT.OPTIMIZE;
    }

    /**
     * Stop Optimize (the restore needs it): Kubernetes scales its Deployment down, Docker stops its container,
     * see blueberry.cluster.type. The REST API of Camunda can't do it.
     *
     * @return the number of replicas before the stop (0 if Optimize was already stopped), to give to {@link #start(int, OperationLog)}
     */
    public int stop(OperationLog operationLog) throws OperationException {
        return cluster().stopOptimize(operationLog);
    }

    /**
     * Start Optimize again, after {@link #stop(OperationLog)}. Nothing is done if replicas is 0.
     */
    public void start(int replicas, OperationLog operationLog) throws OperationException {
        cluster().startOptimize(replicas, operationLog);
    }

    private ClusterConnect cluster() throws OperationException {
        ClusterConnect cluster = clusterFactory == null ? null : clusterFactory.getCluster();
        if (cluster == null) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400, "Cluster type unknown",
                    "blueberry.cluster.type is [" + (clusterFactory == null ? "" : clusterFactory.getConfiguredType()) + "]. Supported: "
                            + (clusterFactory == null ? "" : String.join(", ", clusterFactory.getSupportedNames())));
        }
        return cluster;
    }


    public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        BackupOperation started = webActuator.startBackup(BackupComponentInt.COMPONENT.OPTIMIZE, backupId, blueberryConfig.getOptimizeActuatorUrl(), operationLog);
        if (!started.isOk()) {
            return started;
        }
        // Optimize accepts the request and takes its snapshots in the background: if they fail, finish here
        return webActuator.confirmBackupCreated(BackupComponentInt.COMPONENT.OPTIMIZE, backupId, blueberryConfig.getOptimizeActuatorUrl(), operationLog, started);
    }

    public BackupComponentInt.BackupOperation waitBackup(Long backupId, OperationLog operationLog) {
        return webActuator.waitBackup(BackupComponentInt.COMPONENT.OPTIMIZE, backupId, blueberryConfig.getOptimizeActuatorUrl(), operationLog);
    }

    @Override
    public List<BackupInfo> getListBackups() throws OperationException {
        return webActuator.getListBackups(COMPONENT.OPTIMIZE, getUrlListBackup());
    }

    @Override
    public String getUrlListBackup() {
        return blueberryConfig.getOptimizeActuatorUrl() + "/actuator/backups";
    }
}

