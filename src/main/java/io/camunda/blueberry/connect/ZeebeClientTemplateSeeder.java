package io.camunda.blueberry.connect;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.operation.restoration.ZeebeTemplateSeeder;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Produce records in Zeebe, so the Zeebe exporter creates the index templates in Elasticsearch.
 * <p>
 * Deploy a process "start event, signal catch event, end event", create one instance and send the signal.
 * The process id and the signal name are random and prefixed by "blueberry_", like blueberry_434243535434.
 */
@Component
public class ZeebeClientTemplateSeeder implements ZeebeTemplateSeeder {

    private final ZeebeConnect zeebeConnect;

    public ZeebeClientTemplateSeeder(ZeebeConnect zeebeConnect) {
        this.zeebeConnect = zeebeConnect;
    }

    @Override
    public void seed(OperationLog log) throws OperationException {
        // 12 digits
        long random = ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L);
        String processId = "blueberry_" + random;
        String signalName = "blueberry_" + random;

        ZeebeClient client = zeebeConnect.getConnectedZeebeClient();
        try {
            BpmnModelInstance model = Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .intermediateCatchEvent("waitSignal")
                    .signal(signalName)
                    .endEvent()
                    .done();

            log.info(null, "Deploy the process " + processId);
            client.newDeployResourceCommand()
                    .addProcessModel(model, processId + ".bpmn")
                    .send()
                    .join();

            log.info(null, "Create an instance of " + processId);
            client.newCreateInstanceCommand()
                    .bpmnProcessId(processId)
                    .latestVersion()
                    .send()
                    .join();

            // The instance must reach the signal catch event first: a signal is not kept for a later instance
            Thread.sleep(1000);

            log.info(null, "Send the signal " + signalName);
            client.newBroadcastSignalCommand()
                    .signalName(signalName)
                    .send()
                    .join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.NO_ZEEBE_CONNECTION,
                    "Can't create the Zeebe templates", "Interrupted while sending the signal " + signalName);
        } catch (Exception e) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.NO_ZEEBE_CONNECTION,
                    "Can't create the Zeebe templates",
                    "Can't deploy or start the process " + processId + " in Zeebe: " + e.getMessage());
        }
    }
}
