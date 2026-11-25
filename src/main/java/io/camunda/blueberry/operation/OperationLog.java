package io.camunda.blueberry.operation;

import io.camunda.blueberry.connect.CamundaApplicationInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class OperationLog {
    private final List<Message> listMessages = new ArrayList<>();
    Logger logger = LoggerFactory.getLogger(OperationLog.class);
    Map<CamundaApplicationInt.COMPONENT, List<String>> snapshotPerComponents = new HashMap<>();
    private String operationName;
    private int totalNumberOfSteps;
    private int currentStep;
    private String stepName;
    private String documentationLink;
    private long operationBeginTime;

    private CamundaApplicationInt.COMPONENT component;

    public OperationLog() {
    }

    /**
     * Start an operation
     *
     * @param operationName      the operation name
     * @param totalNumberOfSteps total number of step expected
     */
    public void startOperation(String operationName, int totalNumberOfSteps) {
        this.operationName = operationName;
        this.totalNumberOfSteps = totalNumberOfSteps;
        this.currentStep = 0;
        this.operationBeginTime = System.currentTimeMillis();
        info(component, "Start operation [" + operationName + "] with " + totalNumberOfSteps + " steps");
    }
    public void endOperation() {
        info(null, "Operation[" + operationName + "] : Step finished in " + (System.currentTimeMillis() - operationBeginTime) + " ms");
    }
    /**
     * a new step is started
     *
     * @param stepName name of the step
     */
    public void startOperationStep(CamundaApplicationInt.COMPONENT component, String stepName,
                                   String stepLabel,
                                   String documentationLink) {
        this.currentStep++;
        this.stepName = stepName;
        this.documentationLink = documentationLink;
        info(null,  stepLabel);
    }





    public void info(CamundaApplicationInt.COMPONENT component, String message) {
        if (component==null) {
            logger.info( getBanner()+message);
        } else {
            logger.info("{} Component:{} : {}", getBanner(), component, message);
        }
        Message msg = new Message();
        msg.type = Type.INFO;
        msg.content.add( message);
        msg.component = component;
        msg.date = new Date();
        msg.step = currentStep;
        msg.stepName = stepName;
        msg.documentationLink = documentationLink;
        mergeMessage(msg);
    }

    public void warning(CamundaApplicationInt.COMPONENT component, String message) {
        logger.error(getBanner()+ message);
        Message msg = new Message();
        msg.type = Type.WARNING;
        msg.content.add( message);
        msg.component = component;
        msg.date = new Date();
        msg.step = currentStep;
        msg.stepName = stepName;
        mergeMessage(msg);
    }

    public void error(CamundaApplicationInt.COMPONENT component, String message) {
        logger.error(getBanner() + message);
        Message msg = new Message();
        msg.type = Type.ERROR;
        msg.content.add(message);
        msg.component = component;
        msg.date = new Date();
        msg.step = currentStep;
        msg.stepName = stepName;
        mergeMessage(msg);
    }

    public List<Message> getMessages() {
        return listMessages;
    }

    public void addSnapshotName(CamundaApplicationInt.COMPONENT component, String snapshotName) {
        List<String> listSnapshop = snapshotPerComponents.get(component);
        if (listSnapshop == null) {
            listSnapshop = new ArrayList();
        }
        listSnapshop.add(snapshotName);
        snapshotPerComponents.put(component, listSnapshop);
    }

    public String getOperationName() {
        return operationName;
    }

    public int getTotalNumberOfSteps() {
        return totalNumberOfSteps;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public String getStepName() {
        return stepName;
    }

    public String getComponent() {
        return component == null ? "" : component.name();
    }

    enum Type {INFO, WARNING, ERROR}

    public static class Message {
        public Type type;
        public List<String> content = new ArrayList<>();
        public CamundaApplicationInt.COMPONENT component;
        public Date date;
        public int step;
        public String stepName;
        public String documentationLink;
    }
/** if the message is on the same step as the previous step, then we merge them **/
    public void mergeMessage(Message message) {
        if (listMessages.isEmpty()) {
            listMessages.add(message);
            return;
        }
        Message previous = listMessages.getLast();
        if (previous.step != message.step) {
            listMessages.add(message);
            return;
        }
        previous.content.add( message.content.get(0));
        // update the previous.type: keep the high level. The enum is ordered by severity: INFO < WARNING < ERROR
        previous.type = highestType(previous.type, message.type);
    }

    /**
     * @return the more severe of the two types: max(ERROR, WARNING) is ERROR, max(WARNING, INFO) is WARNING. A null type is ignored
     */
    static Type highestType(Type first, Type second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.ordinal() > first.ordinal() ? second : first;
    }
    private String getBanner()
    { return currentStep+"/"+totalNumberOfSteps + "("+stepName+") :";
    }
}
