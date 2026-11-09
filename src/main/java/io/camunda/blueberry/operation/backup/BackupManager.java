package io.camunda.blueberry.operation.backup;


import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * This class are in charge to start a backup,
 */
@Component
public class BackupManager {
    final OperateConnect operateConnect;
    final TaskListConnect taskListConnect;
    final OptimizeConnect optimizeConnect;
    final ZeebeConnect zeebeConnect;
    private final ElasticSearchConnect elasticSearchConnect;
    Logger logger = LoggerFactory.getLogger(BackupManager.class);
    List<BackupComponentInt> listBackupComponents;
    private BackupJob backupJob;

    public BackupManager(OperateConnect operateConnect, TaskListConnect taskListConnect, OptimizeConnect optimizeConnect,
                         ZeebeConnect zeebeConnect, ElasticSearchConnect elasticSearchConnect,
                         List<BackupComponentInt> listBackupComponents) {
        this.operateConnect = operateConnect;
        this.taskListConnect = taskListConnect;
        this.optimizeConnect = optimizeConnect;
        this.zeebeConnect = zeebeConnect;
        this.elasticSearchConnect = elasticSearchConnect;
        this.listBackupComponents = listBackupComponents;
    }

    public synchronized void startBackup(BackupParameter backupParameter) throws OperationException {
        // Verify first is there is not already a backup in progress
        if (backupJob != null && backupJob.getJobStatus() == BackupJob.JOBSTATUS.INPROGRESS)
            throw new BackupException(null, 400, "Job Already in progress", "In Progress[" + backupJob.getBackupId() + "]", backupJob.getBackupId());
        // start a backup, asynchrously
        backupJob = new BackupJob(operateConnect, taskListConnect, optimizeConnect, zeebeConnect, elasticSearchConnect, new OperationLog());
        Long backupId = backupParameter.backupId;
        if (backupParameter.nextId) {
            logger.info("No backup is provided, calculate the new Id");
            // calculate a new backup ID
            long maxId = 0;
            try {
                ListBackupResult listBackup = getListBackups();
                for (BackupInfo info : listBackup.listBackups) {
                    if (info.backupId > maxId)
                        maxId = info.backupId;
                }

            } catch (OperationException e) {
                logger.error("Error when accessing the list of Backup: {}", e);
                throw new BackupException(null, e.getStatus(), e.getError(), e.getMessage(), backupId);
            }
            backupId = maxId + 1;
            logger.info("No backupId is provided, calculate from the list +1 : {}", backupId);
        }
        // Start in a new thread
        startBackupAsynchronously(backupId);

    }

    private void startBackupAsynchronously(final long backupId) throws BackupException {
        new Thread(() -> {
            try {
                backupJob.backup(backupId);
            } catch (BackupException e) {
                logger.error("BackupId[" + backupId + "] failed", e);
            }
        }).start();
    }


    /**
     * If a job is started, then a backupJob exist.
     * If the backup is terminated, then the backypJob is still available, with a status "TERMINATED"
     *
     * @return
     */
    public BackupJob getBackupJob() {
        return backupJob;
    }

    public class ListBackupResult{
        public List<BackupInfo> listBackups = new ArrayList<>();
        public List<OperationException> listErrors = new ArrayList<>();
        public Map<String,String> listUrls = new HashMap<>();
    }
    /**
     * Return the list of backup
     * All components are asking its backup, to establish a complete list
     *
     * @return
     */
    public ListBackupResult getListBackups() throws OperationException {
        ListBackupResult listBackupResult = new ListBackupResult();
        for (BackupComponentInt backupComponent : listBackupComponents) {
            try {
                mergeListBackups(listBackupResult.listBackups, backupComponent.getListBackups());
                listBackupResult.listUrls.put(backupComponent.getComponent().name(),backupComponent.getUrlListBackup());
            } catch (OperationException e) {
                logger.error("Zeebe Error when accessing the list of Backups: {}", e);
                listBackupResult.listErrors.add(e);
            }
        }
        // Update the status on each backup according the list of components
        int totalActiveComponents = 0;
        for (BackupComponentInt backupComponent : listBackupComponents) {
            if (backupComponent.isActive())
                totalActiveComponents++;
        }
        for (BackupInfo backupInfo : listBackupResult.listBackups) {
            if (backupInfo.status.equals(BackupInfo.Status.COMPLETED) && backupInfo.components.size() != totalActiveComponents) {
                backupInfo.status = BackupInfo.Status.PARTIALBACKUP;
            }
        }

        listBackupResult.listBackups = listBackupResult.listBackups.stream()
                .sorted(Comparator.comparingLong(BackupInfo::getBackupId))
                .toList();
        return listBackupResult;
    }

    /**
     * Merge the two list
     *
     * @param referenceList reference list
     * @param newList       new list to merge
     * @return the reference list
     */
    private List<BackupInfo> mergeListBackups(List<BackupInfo> referenceList, List<BackupInfo> newList) {
        for (BackupInfo backupInfo : newList) {
            Optional<BackupInfo> result = referenceList.stream()
                    .filter(b -> b.backupId == backupInfo.backupId)
                    .findFirst();
            if (result.isPresent())
                result.get().components.addAll(backupInfo.components);
            else
                referenceList.add(backupInfo);
        }
        return referenceList;


    }

    public static class BackupParameter {
        public boolean nextId;
        public Long backupId;
    }
}
