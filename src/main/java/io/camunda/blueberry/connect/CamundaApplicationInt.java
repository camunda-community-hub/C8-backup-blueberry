package io.camunda.blueberry.connect;

/**
 * Optimize and Zeebe react the same way. To simplify the management, they are marked as a "component".
 * Operate and Tasklist are no longer separate applications since 8.10: their data is in the RDBMS, backed up with Zeebe.
 */
public interface CamundaApplicationInt extends BackupComponentInt {
    /**
     * @return true if the application is part of the cluster (configured, so Blueberry must back it up)
     */
    boolean exist();


    /**
     * Return the component behind this application
     *
     * @return
     */
    COMPONENT getComponent();

    /**
     * SECONDARYSTORAGE is the secondary storage of the cluster (Elasticsearch, OpenSearch, a database): it is not an
     * application, its implementation is given by the SecondaryStorageFactory.
     */
    enum COMPONENT {OPTIMIZE, ZEEBE, SECONDARYSTORAGE}


    class ConnectionInfo {
        public boolean isConnected;
        public String explanations;

        public ConnectionInfo(boolean connected, String explanations) {
            this.isConnected = connected;
            this.explanations = explanations;
        }
    }
}
