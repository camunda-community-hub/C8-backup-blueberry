package io.camunda.blueberry.operation.checkdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.connect.ElasticSearchConnect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ResultCheckOperation {

    public static final String PARTITIONID = "partitionId";
    public static final String TYPE = "type";
    public static final String MESSAGE = "message";
    public static final String LEVEL = "level";

    public enum LEVELMSG {ERROR, INFO, DEBUG}
    private List<Message> listMessages = new ArrayList<>();

    private Map<ElasticSearchConnect.COMPONENT_IMPORTER, Boolean> importerIsLate = new HashMap<>();
    private Boolean zeebeExporterIsLate = null;

    public void addInconsistentMessage(int partitionId, String message) {
        addMessage(LEVELMSG.ERROR, "INCONSISTENT", partitionId, message);
    }

    public void addDebugMessage(int partitionId, String message) {
        addMessage(LEVELMSG.DEBUG, "DEBUG", partitionId, message);
    }
    public void addMessage(LEVELMSG level, String type, int partitionId, String message) {
        Message msg = new Message();
        msg.partitionId = partitionId;
        msg.message = message;
        msg.level = level;
        msg.type=type;
        listMessages.add(msg);

    }

    public void add(ResultCheckOperation resultCheck) {
        listMessages.addAll(resultCheck.listMessages);
        // Overwrite only defined value, if the importer is late
        for (Map.Entry<ElasticSearchConnect.COMPONENT_IMPORTER, Boolean> entry : resultCheck.importerIsLate.entrySet()) {
            if (Boolean.FALSE.equals(entry.getValue()))
                importerIsLate.put(entry.getKey(), entry.getValue());
        }
        if (resultCheck.zeebeExporterIsLate != null)
            zeebeExporterIsLate = resultCheck.zeebeExporterIsLate;
    }


    public Boolean isImporterIsLate(ElasticSearchConnect.COMPONENT_IMPORTER component) {
        return importerIsLate.getOrDefault(component, Boolean.FALSE);
    }
    public Boolean isOneImporterIsLate() {
        return importerIsLate.values().stream().anyMatch(v -> Boolean.FALSE.equals(v));
    }

    public Boolean isZeebeExporterIsLate() {
        return zeebeExporterIsLate == null ? false : zeebeExporterIsLate;
    }

    public void setImporterIsLate(ElasticSearchConnect.COMPONENT_IMPORTER component, boolean importerIsLate) {
        this.importerIsLate.put(component, importerIsLate);
    }

    public void setZeebeExporterIsLate(boolean zeebeExporterIsLate) {
        this.zeebeExporterIsLate = zeebeExporterIsLate;
    }


    public List<Message> getInconsistentMessage() {
        return listMessages.stream()
                .filter(m -> "INCONSISTENT".equals(m.type)) // safe equals to avoid NPE
                .collect(Collectors.toList());
    }

    public List<Message> getDebugMessage() {
        return listMessages.stream()
                .filter(m -> "DEBUG".equals(m.type)) // safe equals to avoid NPE
                .collect(Collectors.toList());
    }

    public List<Message> getAllMessages() {
        return listMessages;
    }


    public Map<String, Object> toMap() {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.convertValue(this, Map.class);
    }

    public class Message {
        public LEVELMSG level;
        public String type;
        public int partitionId;
        public String message;

        public Map<String, Object> toMap() {
            Map<String,Object> map = new HashMap<>();
            map.put(PARTITIONID, partitionId);
            map.put(MESSAGE,  message);
            map.put(LEVEL, level);
            map.put(TYPE, type);
            return map;
        }
    }
}
