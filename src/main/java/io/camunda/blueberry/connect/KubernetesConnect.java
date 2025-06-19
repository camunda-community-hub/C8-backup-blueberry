package io.camunda.blueberry.connect;


import io.camunda.blueberry.config.BlueberryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class KubernetesConnect {
    private static final boolean NOT_IMPLEMENTED = true;
    Logger logger = LoggerFactory.getLogger(KubernetesConnect.class);
    BlueberryConfig blueberryConfig;

    KubernetesConnect(BlueberryConfig blueberryConfig) {
        this.blueberryConfig = blueberryConfig;
    }

    /**
     * Connect to the Kubernetes cluster
     *
     * @return connection to the application
     */
    public OperationResult connection() {


        OperationResult result = new OperationResult();

        return result;

        /*
        try {
           // connectExplicitaly();

            if (blueberryConfig.getKubeConfig() != null) {
                logger.info("Connection to Kubernetes via KubeConfig[{}]", blueberryConfig.getKubeConfig());
                Config config = Config.fromKubeconfig(new java.io.File(blueberryConfig.getKubeConfig()));
                client = new KubernetesClientBuilder().withConfig(config).build();
            } else {
                logger.info("Connection to Kubernetes via Default Client");
                client = new DefaultKubernetesClient();
            }
            result.success = true;
        } catch (Exception e) {
            logger.error(e.getMessage());
            result.success = false;
            result.details = "Can't connect to Kubernetes";
        }
        return result;
         */
    }


    public boolean isConnected() {
        return false; /* client != null; */
    }

    /**
     * return true tif the component exist in the cluster
     *
     * @param component to check
     * @param nameSpace namespace
     * @return the result. operationResult.resultBoolean is true if the component exist
     */
    public OperationResult existComponent(CamundaApplicationInt.COMPONENT component, String nameSpace) {
        OperationResult operationResult = new OperationResult();
        operationResult.success = true;
        operationResult.command = "kubectl get pods | grep " + component.toString();
        operationResult.resultBoolean = true;
        return operationResult;
    }

    /**
     * Explore the component to retrieve the repository. For example, Operate defined a variable CAMUNDA_OPERATE_BACKUP_REPOSITORY_NAME
     *
     * @param component component to explore
     * @param nameSpace namespace
     * @return the result
     */
    public OperationResult getRepositoryName(CamundaApplicationInt.COMPONENT component, String nameSpace) {
        // Soon: ask the pod informatoin and read it from it
        OperationResult operationResult = new OperationResult();
        switch (component) {
            case OPERATE:
                operationResult.resultSt = blueberryConfig.getOperateRepository();
                operationResult.success = true;
                operationResult.command = "kubectl describe pod operate";
                break;

            case TASKLIST:
                operationResult.resultSt = blueberryConfig.getTasklistRepository();
                operationResult.success = true;
                operationResult.command = "kubectl describe pod tasklist";
                break;

            case OPTIMIZE:
                operationResult.resultSt = blueberryConfig.getOptimizeRepository();
                operationResult.success = true;
                operationResult.command = "kubectl describe pod optimize";
                break;


            default:
                operationResult.success = false;
                operationResult.details = "Unknown component";
                break;
        }
        return operationResult;
    }


    /**
     * If the application is deployed in a Pod, then it is deploy in a current namespace. Return this namespace
     */
    public String getCurrentNamespace() {
        // TODO
        return "camunda";
    }


    /**
     * @param component
     * @param nameSpace
     * @return
     */
    /**
     public OperationResult getRepositoryNameV2(CamundaApplicationInt.COMPONENT component, String nameSpace) {
     OperationResult operationResult = new OperationResult();
     List<Pod> pods = null;
     try {
     pods = getContainerInformation(component.name(), nameSpace);
     } catch (KubernetesException e) {

     operationResult.success = false;
     return operationResult;
     }

     for (Pod pod : pods) {
     logger.info("Name{}", pod.getMetadata().getName());
     }
     return operationResult;
     }
     **/

    /* List all pods containing the name <podName> in a given namespace.
     * @param podName filter pods by the name (contains: a deployment add ID after the name)
     * @param namespace namespace to search
     * @return list of pods
     * @throws KubernetesException
     */

    /**
     private List<Pod> getContainerInformation(String podName, String nameSpace) throws KubernetesException {

     try {
     // Namespace where the pods are running

     // Get all pods in the namespace
     List<Pod> pods = client.pods().inNamespace(nameSpace).list().getItems();

     // Print pod names
     for (Pod pod : pods) {
     logger.info("Pods {}", pod.getMetadata().getName());
     }
     return pods;
     } catch (Exception e) {
     logger.error(e.getMessage());
     throw new KubernetesException(OperationException.BLUEBERRYERRORCODE.CHECK, 400, "Can't list pods", "Pods can't be listed in nameSpace[" + nameSpace + "] " + e.getMessage());
     }
     }

     private void connectExplicitaly() {
     String token = getAuthToken();

     Config config = new ConfigBuilder()
     .withMasterUrl("https://34.139.199.129")
     .withOauthToken(token)
     .build();

     try {
     client = new KubernetesClientBuilder().withConfig(config).build();
     System.out.println("Connected to Kubernetes: " + client.getMasterUrl());
     } catch (Exception e) {
     e.printStackTrace();
     }
     }

     private static String getAuthToken() {
     try {
     Process process = new ProcessBuilder("c/atelier/Gcloud/google-cloud-sdk/bin/gcloud", "auth", "print-access-token").start();
     BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
     return reader.readLine();
     } catch (Exception e) {
     throw new RuntimeException("Failed to retrieve token", e);
     }
     }
     **/
}