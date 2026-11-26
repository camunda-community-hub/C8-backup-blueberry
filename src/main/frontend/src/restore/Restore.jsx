// -----------------------------------------------------------
//
// Restore
//
// List backups, preflight, start restore plan, monitor progress
//
// -----------------------------------------------------------

import React from 'react';
import RestCallService from "../services/RestCallService";
import BackupListTable from "../component/BackupListTable";
import RepositoryTable from "../component/RepositoryTable";
import {renderOperationLogMessages} from "../component/OperationLogMessages";
import {Button, InlineNotification, Tag} from "carbon-components-react";
import {ArrowRepeat, DatabaseDown} from "react-bootstrap-icons";
import OverlayTrigger from "react-bootstrap/OverlayTrigger";
import Tooltip from "react-bootstrap/Tooltip";
import {Card, Modal} from "react-bootstrap";


class Restore extends React.Component {

    constructor(_props) {
        super();
        this.state = {
            listBackups: [],
            steps: [],
            executionEnabled: false,
            executionConfigured: false,
            procedure: "",
            readiness: null,
            readinessLoading: true,
            display: {loading: false},
            status: "",
            selectedBackup: null,
            showConfirm: false,
            preflight: null,
            restoreMonitor: {statusRestore: "READY"},
            preflightLoading: false
        };
        this.refreshListBackup = this.refreshListBackup.bind(this);
        this.refreshListBackupCallback = this.refreshListBackupCallback.bind(this);
        this.monitorRestore = this.monitorRestore.bind(this);
        this.loadReadiness = this.loadReadiness.bind(this);
    }

    componentDidMount() {
        this.refreshListBackup();
        this.loadSteps();
        this.loadExecution();
        this.loadReadiness();
        this.monitorRestore();
        this.monitorInterval = setInterval(this.monitorRestore, 5000);
    }

    componentWillUnmount() {
        clearInterval(this.monitorInterval);
    }

    loadSteps() {
        RestCallService.getInstance().getJson('blueberry/api/restore/steps?', this, (httpPayload) => {
            if (!httpPayload.isError()) {
                this.setState({steps: httpPayload.getData().steps || []});
            }
        });
    }

    loadExecution() {
        RestCallService.getInstance().getJson('blueberry/api/restore/execution?', this, (httpPayload) => {
            if (!httpPayload.isError()) {
                const data = httpPayload.getData();
                this.setState({
                    // one source for the tag and the buttons: blueberry.restore.execution
                    executionEnabled: data.executionConfigured === true,
                    executionConfigured: data.executionConfigured === true,
                    procedure: data.procedure || ""
                });
            }
        });
    }

    loadReadiness() {
        this.setState({readinessLoading: true});
        RestCallService.getInstance().getJson('blueberry/api/restore/readiness?', this, (httpPayload) => {
            if (httpPayload.isError()) {
                this.setState({
                    readinessLoading: false,
                    readiness: {
                        ready: false,
                        blockers: ["Could not load restore readiness: " + httpPayload.getError()]
                    }
                });
            } else {
                this.setState({readinessLoading: false, readiness: httpPayload.getData()});
            }
        });
    }

    /**
     * Why the live restore is off: the configuration (blueberry.restore.execution)
     */
    executionOffReason() {
        return "Live restore is off. Set blueberry.restore.execution to true in application.yaml (or your profile).";
    }

    /**
     * The Restore button of a complete backup is available when the configuration allows the live restore (blueberry.restore.execution).
     * The checks of the cluster are done when the restore starts (preflight, then the first steps).
     */
    restoreActionsAllowed() {
        return this.state.executionEnabled === true;
    }

    renderReadinessBanner() {
        if (this.state.readinessLoading) {
            return (
                <InlineNotification kind="info" hideCloseButton="true" lowContrast="true" style={{marginTop: "8px"}}>
                    Checking restore readiness (REST API of the cluster, secondary storage)...
                </InlineNotification>
            );
        }
        const r = this.state.readiness;
        if (!r) {
            return null;
        }
        if (r.ready === true) {
            return (
                <InlineNotification kind="success" hideCloseButton="true" lowContrast="true" style={{marginTop: "8px"}}>
                    Environment is ready for live restore. Select a complete backup below to continue.
                </InlineNotification>
            );
        }
        // The blockers are not displayed in a warning: the Restore button explains why it is disabled
        return null;
    }

    render() {
        return (
            <div className={"container"}>
                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-10">
                        <h1 className="title"><DatabaseDown/> Restore</h1>
                        <p className="text-muted" style={{fontSize: "0.85rem"}}>
                            Latest documentation:{" "}
                            <a href="https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/backup-and-restore/" target="_blank" rel="noreferrer">Backup and restore</a>
                            {" · "}
                            <a href="https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-restore-api/" target="_blank" rel="noreferrer">Restore with Elasticsearch (Restore API)</a>
                        </p>
                        <InlineNotification kind="warning" hideCloseButton="true" lowContrast="false">
                            Camunda 8.10 restore uses the Restore API (REST): the cluster goes in recovery mode, the secondary storage is deleted and restored, then Zeebe is restored. The cluster is out of service. Live restore is off until blueberry.restore.execution is true.
                        </InlineNotification>
                        {this.renderReadinessBanner()}
                        {this.state.status &&
                            <InlineNotification kind="error" hideCloseButton="true" lowContrast="false">
                                {this.state.status}
                            </InlineNotification>}
                    </div>
                </div>
                <div className="row" style={{marginTop: "10px"}}>
                    <div className="col-md-12">
                        {this.renderCurrentRestoreCard()}
                    </div>
                </div>

                <div className="row" style={{marginTop: "10px"}}>
                    <Button className="btn btn-success btn-sm"
                            onClick={() => {
                                this.refreshListBackup();
                                this.loadReadiness();
                                this.loadSteps();
                                this.loadExecution();
                                this.monitorRestore();
                            }}
                            disabled={this.state.display.loading}>
                        <ArrowRepeat/> Refresh
                    </Button>
                </div>

                <BackupListTable rows={this.state.listBackups}
                                 renderAction={(row) => this.renderRestoreButton(row)}/>

                {this.state.steps.length > 0 &&
                    <Card style={{marginTop: "16px"}}>
                        <Card.Header>Restore steps</Card.Header>
                        <Card.Body>
                            <ul style={{listStyle: "none", paddingLeft: 0}}>
                                {this.state.steps.map((step) =>
                                    <li key={step.number}>&bull; {step.name}</li>)}
                            </ul>
                            <p className="text-muted" style={{marginTop: "8px"}}>
                                The restore must be validated manually: open Operate, Tasklist and Optimize,
                                check the data from the backup time, and start one test process.
                                Do not treat the job log as proof.
                            </p>
                        </Card.Body>
                    </Card>}

                <Modal show={this.state.showConfirm} onHide={() => this.setState({showConfirm: false})}>
                    <Modal.Header closeButton>
                        <Modal.Title>Confirm restore for backup {this.state.selectedBackup?.backupId}</Modal.Title>
                    </Modal.Header>
                    <Modal.Body>
                        <p>This restore deletes Camunda indices and Zeebe volumes. Data written after the backup time is lost. The platform is unavailable during the restore.</p>
                        {!this.state.executionEnabled &&
                            <InlineNotification kind="warning" hideCloseButton="true">
                                {this.executionOffReason()}
                            </InlineNotification>}
                        {this.state.preflightLoading && <p>Checking preflight...</p>}
                        {this.state.preflight && !this.state.preflight.allowed &&
                            <InlineNotification kind="error" hideCloseButton="true">
                                {this.state.preflight.message}
                            </InlineNotification>}
                        {this.state.preflight && this.state.preflight.allowed &&
                            <p>{this.state.preflight.message}</p>}
                        {this.state.preflight?.docsRestoreUrl &&
                            <p><a href={this.state.preflight.docsRestoreUrl} target="_blank" rel="noreferrer">Official restore guide</a></p>}
                    </Modal.Body>
                    <Modal.Footer>
                        <Button className="btn btn-secondary btn-sm"
                                onClick={() => this.setState({showConfirm: false})}>Cancel</Button>
                        <Button className="btn btn-danger btn-sm"
                                disabled={!this.state.preflight?.allowed || this.state.preflightLoading || !this.restoreActionsAllowed()}
                                onClick={() => this.startRestore()}>
                            Start restore
                        </Button>
                    </Modal.Footer>
                </Modal>
            </div>
        )
    }

    renderRestoreStatusTag(status) {
        if (!status || status === "READY") {
            return <Tag type="green">Ready</Tag>;
        }
        if (status === "FAILED") {
            return <Tag type="red">Failed</Tag>;
        }
        if (status === "TERMINATED") {
            return <Tag type="green">Completed — manual validation required</Tag>;
        }
        if (status === "INPROGRESS") {
            return <Tag type="blue">In progress</Tag>;
        }
        if (status === "REQUIRES_MANUAL_STEPS") {
            return <Tag type="gray">Manual steps required</Tag>;
        }
        return <Tag type="blue">{status}</Tag>;
    }

    renderCurrentRestoreCard() {
        const m = this.state.restoreMonitor || {statusRestore: "READY"};
        return (
            <Card style={{marginTop: "0", marginBottom: "12px"}}>
                <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)", height: "50px"}}>
                    Current restore
                </Card.Header>
                <Card.Body>
                    <table style={{borderCollapse: "separate", borderSpacing: "10px"}}>
                        <tbody>
                        <tr>
                            <td style={{verticalAlign: "middle"}}>Status</td>
                            <td>{this.state.executionConfigured
                                ? this.renderRestoreStatusTag(m.statusRestore)
                                : <Tag type="gray">Disabled</Tag>}</td>
                        </tr>
                        <tr>
                            <td>Restore allowed</td>
                            <td>{this.state.executionConfigured
                                ? <Tag type="green">enabled</Tag>
                                : <Tag type="gray">disabled</Tag>}</td>
                        </tr>
                        <tr>
                            <td>Backup Id</td>
                            <td>{m.backupId != null ? m.backupId : "—"}</td>
                        </tr>
                        <tr>
                            <td>Advancement</td>
                            <td>{m.step != null ? m.step : "—"} / {m.totalSteps != null ? m.totalSteps : "—"}</td>
                        </tr>
                        <tr>
                            <td>Current operation</td>
                            <td>{m.stepName || "—"}</td>
                        </tr>
                        <tr>
                            <td>Date status</td>
                            <td>{m.dateStatus || "—"}</td>
                        </tr>
                        <tr>
                            <td style={{verticalAlign: "top"}}>Repository</td>
                            <td><RepositoryTable/></td>
                        </tr>
                        </tbody>
                    </table>
                    {m.statusRestore === "TERMINATED" &&
                        <InlineNotification kind="info" hideCloseButton="true" lowContrast="true" style={{marginTop: "8px"}}>
                            Automated restore steps finished. Validate Operate, Tasklist, and Optimize, then run a test process.
                        </InlineNotification>}
                    {m.statusRestore === "FAILED" && m.consistent === false &&
                        <InlineNotification kind="error" hideCloseButton="true" lowContrast="false" style={{marginTop: "8px"}}>
                            The platform is NOT consistent: the data was partly replaced. Optimize stays stopped and the cluster stays in recovery mode. Fix the cause, then start the restore again.
                        </InlineNotification>}
                    {m.statusRestore === "FAILED" &&
                        <InlineNotification kind="error" hideCloseButton="true" lowContrast="false" style={{marginTop: "8px"}}>
                            Restore failed. See ERROR messages below for the failing phase.
                        </InlineNotification>}
                    {m.docsRestoreUrl &&
                        <p style={{marginTop: "8px"}}>
                            <a href={m.docsRestoreUrl} target="_blank" rel="noreferrer">Restore documentation</a>
                        </p>}
                    {renderOperationLogMessages(m.messages, m.totalSteps)}
                </Card.Body>
            </Card>
        );
    }

    renderRestoreButton(row) {
        // a partial, failed or running backup can not be restored
        const backupOk = row.complete === true && row.backupStatus !== "INPROGRESS"
            && row.backupStatus !== "PARTIALBACKUP" && row.backupStatus !== "FAILED";
        const actionsAllowed = this.restoreActionsAllowed();

        if (backupOk && actionsAllowed) {
            return (
                <Button className="btn btn-warning btn-sm" onClick={() => this.openConfirm(row)}>
                    Restore
                </Button>
            );
        }

        let reason;
        if (!backupOk) {
            reason = row.complete === false
                ? "This backup is incomplete. Restore is disabled until all active components have a backup for this ID."
                : "Restore is not available for this backup state.";
        } else {
            reason = this.executionOffReason();
        }

        return (
            <OverlayTrigger placement="top" overlay={<Tooltip id={"tt-" + row.backupId}>{reason}</Tooltip>}>
                <span className="d-inline-block">
                    <Button className="btn btn-secondary btn-sm" disabled style={{pointerEvents: "none"}}>Restore</Button>
                </span>
            </OverlayTrigger>
        );
    }

    openConfirm(row) {
        this.loadSteps();
        this.loadExecution();
        this.loadReadiness();
        this.setState({
            selectedBackup: row,
            showConfirm: true,
            preflight: null,
            preflightLoading: true
        });
        RestCallService.getInstance().getJson(
            'blueberry/api/restore/preflight?backupId=' + row.backupId + '&',
            this,
            (httpPayload) => {
                if (httpPayload.isError()) {
                    this.setState({preflightLoading: false, preflight: {allowed: false, message: httpPayload.getError()}});
                } else {
                    this.setState({preflightLoading: false, preflight: httpPayload.getData()});
                }
            });
    }

    startRestore() {
        const backupId = this.state.selectedBackup?.backupId;
        RestCallService.getInstance().postJson(
            'blueberry/api/restore/start?',
            {backupId: backupId},
            this,
            (httpPayload) => {
                const data = httpPayload.isError() ? {} : (httpPayload.getData() || {});
                if (httpPayload.isError() || data.error) {
                    this.setState({
                        status: data.message || data.errorMessage || httpPayload.getError(),
                        showConfirm: false
                    });
                } else {
                    this.setState({showConfirm: false, status: ""});
                    this.monitorRestore();
                }
            });
    }

    monitorRestore() {
        const tz = new Date().getTimezoneOffset();
        RestCallService.getInstance().getJson('blueberry/api/restore/monitor?timezoneoffset=' + tz + '&', this, (httpPayload) => {
            if (!httpPayload.isError()) {
                this.setState({restoreMonitor: httpPayload.getData()});
            }
        });
    }

    refreshListBackup() {
        const tz = new Date().getTimezoneOffset();
        let uri = 'blueberry/api/backup/list?timezoneoffset=' + tz;
        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        RestCallService.getInstance().getJson(uri, this, this.refreshListBackupCallback);
    }

    refreshListBackupCallback(httpPayload) {
        this.setDisplayProperty("loading", false);
        if (httpPayload.isError()) {
            this.setState({status: "Error loading backups"});
        } else {
            this.setState({listBackups: httpPayload.getData().listBackups || []});
        }
    }

    setDisplayProperty(propertyName, propertyValue) {
        let displayObject = this.state.display;
        displayObject[propertyName] = propertyValue;
        this.setState({display: displayObject});
    }
}

export default Restore;
