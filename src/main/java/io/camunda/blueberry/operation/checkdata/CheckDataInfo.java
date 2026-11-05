package io.camunda.blueberry.operation.checkdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.connect.ElasticSearchConnect;
import io.camunda.blueberry.connect.ZeebeConnect;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CheckDataInfo {


    public ResultCheckOperation checks = new ResultCheckOperation();
    public ZeebeConnect.ClusterInformation clusterInformation;


    /* ******************************************************************** */
    /*                                                                      */
    /*  Log data Synthesis                                                     */
    /*                                                                      */
    /* ******************************************************************** */

    /**
     * Key is partition info
     */
    public Map<Integer, PartitionSynthesis> synthesis = new HashMap<>();

    public void addPartitionInfo(ZeebeConnect.PartitionInfo partitionInfo) {
        PartitionSynthesis data = synthesis.computeIfAbsent(partitionInfo.partitionId, key -> {
            return new PartitionSynthesis();
        });
        data.partitionInfo = partitionInfo;
    }

    public Set<ZeebeConnect.PartitionInfo> allPartitionsInfo() {
        return synthesis.values().stream()
                .map(ds -> ds.partitionInfo)       // extract PartitionInfo
                .collect(Collectors.toSet());
    }

    public ZeebeConnect.PartitionInfo getPartitionInfo(int partitionId) {
        PartitionSynthesis data = synthesis.get(partitionId);
        if (data != null)
            return data.partitionInfo;
        return null;
    }

    public PartitionSynthesis getSynthesis(int partitionId) {
        return synthesis.get(partitionId);
    }


    public void logZeebeInfo(ElasticSearchConnect.PartitionSequencesInfo zeebeInfo) {
        for (ZeebeConnect.PartitionInfo partitionInfo : allPartitionsInfo()) {
            CheckDataInfo.PartitionSynthesis partitionSynthesis = getSynthesis(partitionInfo.partitionId);
            partitionSynthesis.zeebeRecordSequence = zeebeInfo.getMaxSequence(partitionInfo.partitionId);
            partitionSynthesis.zeebeRecordPosition = zeebeInfo.getMaxPosition(partitionInfo.partitionId);

            ElasticSearchConnect.SequencesPerType zeebeSequences = zeebeInfo.getPartitionSequence(partitionInfo.partitionId);
            for (Map.Entry<String, ElasticSearchConnect.SequenceInfo> entrySequence : zeebeSequences.getInfo().entrySet()) {
                CheckDataInfo.SequenceInfoSynthesis sequenceInfoSynthesis = partitionSynthesis.getSequenceInfoSynthesis(entrySequence.getKey());
                sequenceInfoSynthesis.zeebeRecordSequence = entrySequence.getValue().sequence();
                sequenceInfoSynthesis.zeebeRecordPosition = entrySequence.getValue().position();
            }
        }
    }

    public void logOperateInfo(ElasticSearchConnect.PartitionSequencesInfo operateInfo) {
        for (ZeebeConnect.PartitionInfo partitionInfo : allPartitionsInfo()) {
            CheckDataInfo.PartitionSynthesis partitionSynthesis = getSynthesis(partitionInfo.partitionId);
            ElasticSearchConnect.SequencesPerType operateSequence = operateInfo.getPartitionSequence(partitionInfo.partitionId);
            for (Map.Entry<String, ElasticSearchConnect.SequenceInfo> entrySequence : operateSequence.getInfo().entrySet()) {
                CheckDataInfo.SequenceInfoSynthesis sequenceInfoSynthesis = partitionSynthesis.getSequenceInfoSynthesis(entrySequence.getKey());
                sequenceInfoSynthesis.operateSequence = entrySequence.getValue().sequence();
                sequenceInfoSynthesis.operatePosition = entrySequence.getValue().position();
            }
        }

    }

    /**
     * PartitionSynthesis
     */
    public static class PartitionSynthesis {
        public ZeebeConnect.PartitionInfo partitionInfo;
        public Long zeebeRecordSequence;
        public Long zeebeRecordPosition;
        public Long zeebeRecordSequenceDelay;
        public Map<String, SequenceInfoSynthesis> sequenceInfo = new HashMap<>();

        public SequenceInfoSynthesis getSequenceInfoSynthesis(String typeSequence) {
            return sequenceInfo.computeIfAbsent(typeSequence, key -> {
                return new SequenceInfoSynthesis(typeSequence); // replace with your actual data creation
            });
        }

    }


    /**
     * SequenceInfoSynthesis
     */
    public static class SequenceInfoSynthesis {
        public String typeSequence;
        public Long zeebeRecordSequence;
        public Long zeebeRecordPosition;
        public Long operateSequence;
        public Long operatePosition;

        public SequenceInfoSynthesis(String typeSequence) {
            this.typeSequence = typeSequence;
        }
    }

    public Map<String, Object> toMap() {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(this, Map.class);
    }

}
