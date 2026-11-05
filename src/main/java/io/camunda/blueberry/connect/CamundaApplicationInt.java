package io.camunda.blueberry.connect;

/**
 * Optimize and Zeebe react the same way. To simplify the management, they are marked as a "component".
 * Operate and Tasklist are no longer separate applications since 8.10: their data is in the RDBMS, backed up with Zeebe.
 */
public interface CamundaApplicationInt extends BackupComponentInt {
    boolean exist();

    /**
     * Return the component behind this application
     *
     * @return
     */
    COMPONENT getComponent();

    /**
     * ZEEBERECORD is the Elasticsearch snapshot of the zeebe-record indices, needed by Optimize.
     */
    enum COMPONENT {OPTIMIZE, ZEEBERECORD, ZEEBE}


    class ConnectionInfo {
        public boolean isConnected;
        public String explanations;

        public ConnectionInfo(boolean connected, String explanations) {
            this.isConnected = connected;
            this.explanations = explanations;
        }
    }
}
