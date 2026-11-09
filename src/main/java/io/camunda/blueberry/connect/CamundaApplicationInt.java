package io.camunda.blueberry.connect;

/**
 * Multiples component (Operate, TaskList, Optimize) react as the same way. To Simplify the management, they are mark as a "component"
 */
public interface CamundaApplicationInt extends BackupComponentInt {
    boolean exist();

    /**
     * Return the component behind this application
     *
     * @return
     */
    COMPONENT getComponent();

    enum COMPONENT {TASKLIST, OPERATE, OPTIMIZE, ZEEBERECORD, ZEEBE}


    class ConnectionInfo {
        public boolean isConnected;
        public String explanations;

        public ConnectionInfo(boolean connected, String explanations) {
            this.isConnected = connected;
            this.explanations = explanations;
        }
    }
}
