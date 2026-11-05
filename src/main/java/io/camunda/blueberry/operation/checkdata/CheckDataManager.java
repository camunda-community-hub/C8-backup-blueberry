package io.camunda.blueberry.operation.checkdata;

import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.backup.BackupJob;
import io.camunda.blueberry.operation.backup.BackupManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class CheckDataManager {
    private final ElasticSearchConnect elasticSearchConnect;
    Logger logger = LoggerFactory.getLogger(BackupManager.class);
    final OptimizeConnect optimizeConnect;
    final ZeebeConnect zeebeConnect;
    private BackupJob backupJob;


    List<BackupComponentInt> listBackupComponents;

    private boolean inProgress = false;

    public CheckDataManager(OptimizeConnect optimizeConnect,
                            ZeebeConnect zeebeConnect, ElasticSearchConnect elasticSearchConnect,
                            List<BackupComponentInt> listBackupComponents) {
        this.optimizeConnect = optimizeConnect;
        this.zeebeConnect = zeebeConnect;
        this.elasticSearchConnect = elasticSearchConnect;
        this.listBackupComponents = listBackupComponents;
    }

    /**
     * Start to check the data
     * During the check, the thread is block. If another request come, it will be rejected (we don't want to check at a time
     *
     * @return
     * @throws OperationException
     */
    public CheckDataInfo checkData() throws OperationException {
        CheckDataInfo checkDataInfo = new CheckDataInfo();

        try {
            // https://camunda.slack.com/archives/C08MRKHJ0CD/p1756320736386839
            // Check backup integrity https://camunda.slack.com/archives/C08MRKHJ0CD/p1756460796531499
            // Last question https://camunda.slack.com/archives/C08MRKHJ0CD/p1757369822093199


            // Get the number of partitions
            checkDataInfo.clusterInformation = zeebeConnect.getClusterInformation();
            if (checkDataInfo.clusterInformation == null)
                throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.NO_ZEEBE_CONNECTION, "NoConnection", "No connection to Zeebe");

            for (int brokerId = 0; brokerId < checkDataInfo.clusterInformation.clusterSize; brokerId++) {
                List<ZeebeConnect.PartitionInfo> listPartitionsInfo = zeebeConnect.getPartitionInformation(brokerId);
                // Keep only Leader information
                for (ZeebeConnect.PartitionInfo partitionInfo : listPartitionsInfo) {
                    if ("LEADER".equals(partitionInfo.role))
                        checkDataInfo.addPartitionInfo(partitionInfo);
                }

            }


            //--------------------------------- ZeebeRecord
            ElasticSearchConnect.PartitionSequencesInfo zeebeInfo = elasticSearchConnect.getZeebeRecordsInfo();

            // Log this information
            checkDataInfo.logZeebeInfo(zeebeInfo);


            // Check
            checkDataInfo.checks.add(checkZeebeToZeebeRecord(checkDataInfo.allPartitionsInfo(), zeebeInfo));
            // Operate and Tasklist are no longer checked (they are in the RDBMS since 8.10): only the zeebe-record is checked

            if (checkDataInfo.checks.isZeebeExporterIsLate()) {
                // take a second picture
                Thread.sleep(2000);

                ElasticSearchConnect.PartitionSequencesInfo zeebePartitionsDelay = elasticSearchConnect.getZeebeRecordsInfo();
                checkDataInfo.checks.add(CheckAdvancement("ZeebeRecord", zeebeInfo, zeebePartitionsDelay));
            }

            return checkDataInfo;

        } catch (OperationException opException) {
            logger.error(opException.getMessage(), opException);
            throw opException;
        } catch (Exception e) {
            throw OperationException.getInstanceFromException(OperationException.BLUEBERRYERRORCODE.CHECK, e);
        }
    }

    // Check zeebe.lastExportedPosition <= zeebe-record.lastPosition)
    // (max by (namespace, partition) (zeebe_log_appender_last_committed_position)

    // Get     "processedPositionInSnapshot": 48, and     "exportedPosition": 52,

    private ResultCheckOperation checkZeebeToZeebeRecord(Set<ZeebeConnect.PartitionInfo> partitionInfoMap,
                                                         ElasticSearchConnect.PartitionSequencesInfo zeebeRecordInfo) {
        ResultCheckOperation resultCheckOperation = new ResultCheckOperation();

        // Check zeebe.lastExportedPosition <= zeebe-record.lastPosition)
        for (ZeebeConnect.PartitionInfo partitionInfo : partitionInfoMap) {
            if (partitionInfo.exportedPosition + 5000 < partitionInfo.processedPosition) {
                // this is not consistent: the exported must not be too far from the processedPosition
                // Note: the exported may be upper because not all exported positions are executed. When a boundary event is present, event are exported but not processed
                resultCheckOperation.addInconsistentMessage(partitionInfo.partitionId, "ExportedPosition[" + partitionInfo.exportedPosition + "] < far < ProcessedPosition[" + partitionInfo.processedPosition
                        + "] diff[" + (partitionInfo.processedPosition - partitionInfo.exportedPosition) + "]");
            }

            // second, check the exported position with maw zeebeInfo
            long maxPosition = zeebeRecordInfo.getMaxPosition(partitionInfo.partitionId);

            if (partitionInfo.exportedPosition < maxPosition) {
                resultCheckOperation.addInconsistentMessage(partitionInfo.partitionId, "ExportedPosition[" + partitionInfo.exportedPosition + "] < ZeebeRecord.max[" + partitionInfo.processedPosition
                        + "]");
            } else if (partitionInfo.exportedPosition > maxPosition + 5000) {
                resultCheckOperation.addInconsistentMessage(partitionInfo.partitionId, "ExportedPosition[" + partitionInfo.exportedPosition + "] > far > ZeebeRecord.max[" + maxPosition
                        + "] diff[" + (partitionInfo.exportedPosition - maxPosition) + "]");
                resultCheckOperation.setZeebeExporterIsLate(true);
            } else
                resultCheckOperation.addDebugMessage(partitionInfo.partitionId, "ExportedPosition[" + partitionInfo.exportedPosition + "] ZeebeRecord.max[" + maxPosition
                        + "] diff[" + (partitionInfo.exportedPosition - maxPosition) + "]");

        }
        logger.info("checkZeebeToZeebeRecord: {}",
                resultCheckOperation.getAllMessages()
                        .stream()
                        .map(t -> {
                            return "Part:" + t.partitionId + ": " + t.message;
                        })
                        .collect(Collectors.joining("; ")));

        return resultCheckOperation;

    }


    /**
     * @param label                        explanation on the origin of the comparison. Between firstInfo and secondInfo, sequences must advance
     * @param firstPartitionSequencesInfo  first info to get a picture
     * @param secondPartitionSequencesInfo second info
     * @return a resultCheckOperation
     */
    private ResultCheckOperation CheckAdvancement(String label,
                                                  ElasticSearchConnect.PartitionSequencesInfo firstPartitionSequencesInfo,
                                                  ElasticSearchConnect.PartitionSequencesInfo secondPartitionSequencesInfo) {
        ResultCheckOperation resultCheckOperation = new ResultCheckOperation();
        // Does something move in Zeebe?
        boolean somethingAdvance = false;
        for (Map.Entry<Integer, ElasticSearchConnect.SequencesPerType> infoPartition : firstPartitionSequencesInfo.getAllPartitions().entrySet()) {
            for (Map.Entry<String, ElasticSearchConnect.SequenceInfo> sequenceEntry : infoPartition.getValue().getInfo().entrySet()) {

                Long firstPosition = sequenceEntry.getValue().position();
                Long secondPosition = searchPosition(infoPartition.getKey(), sequenceEntry.getKey(), secondPartitionSequencesInfo);
                if (firstPosition == null) {
                    // Ok, this sequence does not change
                }
                if (secondPosition > firstPosition) {
                    somethingAdvance = true;
                } else if (secondPosition < firstPosition) {
                    // THis is not expected! Operate go down??
                    resultCheckOperation.addInconsistentMessage(infoPartition.getKey(), label
                            + "Sequence[" + sequenceEntry.getKey() + "] is inconsistent: position is DOWN: "
                            + "FirstValue [" + firstPosition + "] newValue [" + secondPosition + "]");
                } else {
                    // same value: operate does not import anything here.
                    resultCheckOperation.addDebugMessage(infoPartition.getKey(), label
                            + "Sequence[" + sequenceEntry.getKey() + "] no change");
                }
            }

        }
        if (!somethingAdvance) {
            resultCheckOperation.addInconsistentMessage(-1,
                    label + ": After 5 seconds, ZeebeImporter did not advance any time");

        }
        return resultCheckOperation;
    }

    /**
     * Search for a specific seqence
     *
     * @param partition    partition number
     * @param typeSequence as Zeebe name, like DEPLOYMENT
     * @param source       Component: search <partition>-lower(<typeSepquence>)
     * @return the position, or null if no index exist for this typeSequence (not all type sequence are in the component)
     */
    private Long searchPosition(Integer partition,
                                String typeSequence,
                                ElasticSearchConnect.PartitionSequencesInfo source) {
        if (!source.getAllPartitions().containsKey(partition)) {
            return null;
        }
        String keyComponent = partition + "-" + typeSequence.toLowerCase();
        if (source.getAllPartitions().get(partition).getInfo().containsKey(keyComponent)) {
            return source.getAllPartitions().get(partition).getInfo().get(keyComponent).position();
        }
        return null;
    }


}
