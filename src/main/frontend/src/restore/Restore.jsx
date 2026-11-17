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
import {ArrowRepeat} from "react-bootstrap-icons";
import OverlayTrigger from "react-bootstrap/OverlayTrigger";
import Tooltip from "react-bootstrap/Tooltip";
import {Card, Modal} from "react-bootstrap";


class Restore extends React.Component {

    constructor(_props) {
        super();
        this.state = {
            listBackups: [],
            phases: [],
            executionEnabled: false,
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
        this.loadPhases();
        this.loadReadiness();
        this.monitorRestore();
        this.monitorInterval = setInterval(this.monitorRestore, 5000);
    }

    componentWillUnmount() {
        clearInterval(this.monitorInterval);
    }

    loadPhases() {
        RestCallService.getInstance().getJson('blueberry/api/restore/phases?', this, (httpPayload) => {
            if (!httpPayload.isError()) {
                const data = httpPayload.getData();
                this.setState({
                    phases: data.phases || [],
                    executionEnabled: data.executionEnabled === true,
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

    restoreActionsAllowed() {
        const {executionEnabled, readiness, readinessLoading} = this.state;
        if (readinessLoading) {
            return false;
        }
        if (!executionEnabled) {
            return false;
        }
        return readiness && readiness.ready === true;
    }

    renderReadinessBanner() {
        if (this.state.readinessLoading) {
            return (
                <InlineNotification kind="info" hideCloseButton="true" lowContrast="true" style={{marginTop: "8px"}}>
                    Checking restore readiness (Kubernetes, Elasticsearch, workloads)...
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
        const blockers = r.blockers || [];
        const lines = [];
        if (!this.state.executionEnabled) {
            lines.push("Live restore is off. Set blueberry.restore.execution to true in application.yaml (or your profile).");
        }
        blockers.forEach(b => lines.push(b));
        return (
            <InlineNotification kind="warning" hideCloseButton="true" lowContrast="false" style={{marginTop: "8px"}}>
                <strong>Restore is not available yet.</strong>
                <ul style={{marginBottom: "4px", paddingLeft: "1.2rem"}}>
                    {lines.map((line, i) => <li key={i}>{line}</li>)}
                </ul>
                {typeof this.props.onOpenConfiguration === "function" &&
                    <Button className="btn btn-link btn-sm" onClick={() => this.props.onOpenConfiguration()}>
                        Open Configuration
                    </Button>}
                {!this.props.onOpenConfiguration &&
                    <span> Use <strong>Configuration</strong> in the top navigation to fix Kubernetes and actuator rules.</span>}
            </InlineNotification>
        );
    }

    render() {
        return (
            <div className={"container"}>
                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-10">
                        <h1 className="title">Restore</h1>
                        <InlineNotification kind="warning" hideCloseButton="true" lowContrast="false">
                            {this.state.procedure === "pwc-8.7"
                                ? "Camunda 8.7 restore stops the cluster, deletes Camunda indices, deletes Zeebe volumes, and restores the selected backup. Live restore is off until blueberry.restore.execution is true."
                                : "Automated restore is available for Camunda 8.7. Select 8.7 to use the PwC restore procedure."}
                        </InlineNotification>
                        {this.renderReadinessBanner()}
                        {this.state.status &&
                            <InlineNotification kind="error" hideCloseButton="true" lowContrast="false">
                                {this.state.status}
                            </InlineNotification>}
                    </div>
                </div>
                <div className="row" style={{marginTop: "10px"}}>
                    <div className="col-md-6">
                        {this.renderCurrentRestoreCard()}
                    </div>
                </div>

                <div className="row" style={{marginTop: "10px"}}>
                    <Button className="btn btn-success btn-sm"
                            onClick={() => {
                                this.refreshListBackup();
                                this.loadReadiness();
                                this.loadPhases();
                                this.monitorRestore();
                            }}
                            disabled={this.state.display.loading}>
                        <ArrowRepeat/> Refresh
                    </Button>
                </div>

                <BackupListTable rows={this.state.listBackups}
                                 renderAction={(row) => this.renderRestoreButton(row)}/>

                {this.state.phases.length > 0 &&
                    <Card style={{marginTop: "16px"}}>
                        <Card.Header>Restore phases (Camunda version profile)</Card.Header>
                        <Card.Body>
                            <ul style={{listStyle: "none", paddingLeft: 0}}>
                                {this.state.phases.map((p, index) =>
                                    <li key={p.id}>
                                        &bull; {index + 1}. {p.title} {p.kind === "MANUAL" && <Tag type="gray">Manual</Tag>}
                                    </li>)}
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
                                Live restore is off. Set blueberry.restore.execution to true before you start.
                            </InlineNotification>}
                        {this.state.preflightLoading && <p>Checking preflight...</p>}
                        {this.state.preflight && !this.state.preflight.allowed &&
                            <InlineNotification kind="error" hideCloseButton="true">
                                {this.state.preflight.message}
                            </InlineNotification>}
                        {this.state.preflight && this.state.preflight.allowed &&
                            <p>{this.state.preflight.message}</p>}
                        {this.state.preflight?.docsRestoreUrl &&
                            <p><a href={this.state.preflight.docsRestoreUrl} target="_blank" rel="noreferrer">Official restore guide</a> (Camunda {this.state.preflight.camundaMinorVersion})</p>}
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
                            <td>{this.renderRestoreStatusTag(m.statusRestore)}</td>
                        </tr>
                        <tr>
                            <td>Backup Id</td>
                            <td>{m.backupId != null ? m.backupId : "—"}</td>
                        </tr>
                        <tr>
                            <td>Advancement</td>
                            <td>{m.step != null ? m.step : "—"} / {m.totalNumberOfSteps != null ? m.totalNumberOfSteps : "—"}</td>
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
                    {m.statusRestore === "FAILED" &&
                        <InlineNotification kind="error" hideCloseButton="true" lowContrast="false" style={{marginTop: "8px"}}>
                            Restore failed. See ERROR messages below for the failing phase.
                        </InlineNotification>}
                    {m.docsRestoreUrl &&
                        <p style={{marginTop: "8px"}}>
                            <a href={m.docsRestoreUrl} target="_blank" rel="noreferrer">Restore documentation</a>
                        </p>}
                    {renderOperationLogMessages(m.messages)}
                </Card.Body>
            </Card>
        );
    }

    renderRestoreButton(row) {
        const backupOk = row.complete === true && row.backupStatus !== "INPROGRESS";
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
        } else if (this.state.readinessLoading) {
            reason = "Restore readiness is still loading.";
        } else if (!this.state.executionEnabled) {
            reason = "Live restore is off. Set blueberry.restore.execution=true.";
        } else if (this.state.readiness && this.state.readiness.blockers && this.state.readiness.blockers.length > 0) {
            reason = this.state.readiness.blockers[0];
        } else {
            reason = "Restore environment is not ready. Open Configuration or refresh readiness.";
        }

        return (
            <OverlayTrigger placement="top" overlay={<Tooltip id={"tt-" + row.backupId}>{reason}</Tooltip>}>
                <span className="d-inline-block">
                    <Button className="btn btn-warning btn-sm" disabled style={{pointerEvents: "none"}}>Restore</Button>
                </span>
            </OverlayTrigger>
        );
    }

    openConfirm(row) {
        this.loadPhases();
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
