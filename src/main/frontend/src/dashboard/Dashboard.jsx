// -----------------------------------------------------------
//
// Dashboard
//
// Manage the dashboard. Root component
//
// -----------------------------------------------------------

import React from 'react';

import {Button, InlineNotification, Tag} from "carbon-components-react";
import {Card} from 'react-bootstrap';
import Chart from "../component/Chart";
import OverlayTrigger from "react-bootstrap/OverlayTrigger";
import RestCallService from "../services/RestCallService";
import {ArrowRepeat, Terminal} from "react-bootstrap-icons";
import Tooltip from "react-bootstrap/Tooltip";


class Dashboard extends React.Component {


    constructor(_props) {
        super();
        this.state = {
            dashboard: {
                cluster: {
                    clusterSize: "",
                    partitionsCount: "",
                    replicationfactor: "",
                    statusCluster: "ACTIVE"
                },
                backup: {
                    history: [],
                    statusBackup: "",
                    step: "2/7 backup Operate",
                    backups: ""
                },
                configuration: {
                    statusConfiguration: "CORRECT",
                },
                scheduler: {
                    statusScheduler: "INACTIF",
                    cron: "",
                    next: "",
                    delay: ""
                }

            },
            connection: {
                zeebe: {connection: "VERIFICATION_IN_PROGRESS"},
                elasticsearch: {connection: "VERIFICATION_IN_PROGRESS"},
                operate: {connection: "VERIFICATION_IN_PROGRESS"},
                tasklist: {connection: "VERIFICATION_IN_PROGRESS"},
                optimize: {connection: "VERIFICATION_IN_PROGRESS"},
                status: ""
            },
            display: {
                loading: true
            },


        };
        this.schedule = this.schedule.bind(this);
        this.setDisplayProperty = this.setDisplayProperty.bind(this);
    }

    componentDidMount() {
        this.refreshDashboard();
        this.checkConnection();

        // Set up the interval to call schedule() every 30 seconds
        this.intervalId = setInterval(this.schedule, 120000);
        this.intervalConnectionId = setInterval(() => {
            this.checkConnection();
        }, 60000); // 60 seconds
    }

    // Cleanup to clear the interval when the component unmounts
    componentWillUnmount() {
        console.log("Dashboard: componentWillUnmount");
        clearInterval(this.intervalId);
        clearInterval(this.intervalConnectionId);
    }

    render() {
        // console.log("dashboard.render display="+JSON.stringify(this.state.display));
        return (<div className={"container"}>

                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-10">
                        <h1 className="title">Dashboard</h1>
                        <InlineNotification kind="info" hideCloseButton="true" lowContrast="false">
                            Give the last backup time and status, if a next backup is scheduled.
                        </InlineNotification>
                    </div>

                    <div className="col-md-2">
                        <Button className="btn btn-success btn-sm"
                                onClick={() => {
                                    this.refreshDashboard();
                                    this.checkConnection()
                                }}
                                disabled={this.state.display.loading}>
                            <ArrowRepeat/> Refresh
                        </Button>
                    </div>

                </div>

                <div className="row" style={{width: "100%", marginTop: "10px"}}>
                    <div className="col-md-12">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Backup timeline</Card.Header>
                            <Card.Body>
                                <Chart type="HorizontalBar" dataList={this.state.dashboard.backup.history}
                                       oneColor={true}
                                       options={{
                                           title: this.state.title,
                                           showXLabel: false,
                                           showYLabel: true,
                                           width: 200,
                                           height: 100,
                                           showGrid: false
                                       }}
                                       title="Backup timeline"/>

                            </Card.Body>
                        </Card>
                    </div>
                </div>
                <div className="row" style={{width: "100%", marginTop: "10px"}}>
                    <div className="col-md-4">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Backup</Card.Header>
                            <Card.Body>
                                <table style={{borderCollapse: "separate", borderSpacing: "10px"}}>
                                    <tr>
                                        <td style={{verticalAlign: "middle"}}>Status</td>
                                        <td style={{textAlign: "center"}}>
                                            {this.renderConnectionTag(this.state.dashboard.backup.statusBackup)}
                                            <br/>
                                            {this.state.dashboard.backup.step}
                                        </td>
                                    </tr>
                                    <tr>
                                        <td>Total number of backups</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard.backup.backupsCount}</td>
                                    </tr>
                                    <tr>
                                        <td>backup complete</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard.backup.backupsComplete}</td>
                                    </tr>
                                    <tr>
                                        <td>backup failed</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard.backup.backupsFailed}</td>
                                    </tr>
                                    <tr>
                                        <td>Next execution</td>
                                        <td>
                                            {this.state.dashboard.scheduler.next}<br/>
                                            in&nbsp;
                                            {this.state.dashboard.scheduler.delay}
                                        </td>
                                    </tr>
                                </table>
                            </Card.Body>
                        </Card>
                    </div>
                    <div className="col-md-4">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Configuration</Card.Header>
                            <Card.Body>
                                <table style={{borderCollapse: "separate", borderSpacing: "10px"}}>
                                    <tr>
                                        <td>Status</td>
                                        <td style={{textAlign: "center"}}>
                                            {this.renderConnectionTag(this.state.dashboard.configuration.statusConfiguration)}

                                        </td>
                                    </tr>
                                    <tr>
                                        <td>Scheduler</td>
                                        <td style={{textAlign: "center"}}>
                                            {this.state.dashboard.scheduler.statusScheduler === "ACTIVE" &&
                                                <Tag type="green">Active</Tag>}
                                            {this.state.dashboard.scheduler.statusScheduler !== "ACTIVE" &&
                                                <Tag type="gray">Inactive</Tag>}
                                        </td>
                                    </tr>
                                    <tr>
                                        <td>Scheduler Cron</td>
                                        <td>
                                            {this.state.dashboard.scheduler.cron}
                                        </td>
                                    </tr>
                                </table>
                            </Card.Body>
                        </Card>
                    </div>
                    <div className="col-md-4">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Cluster
                                parameters</Card.Header>
                            <Card.Body>
                                <table style={{borderCollapse: "separate", borderSpacing: "10px"}}>
                                    <tr>
                                        <td style={{verticalAlign: "middle"}}>Status</td>
                                        <td style={{textAlign: "center"}}>
                                            {this.renderConnectionTag(this.state.dashboard.cluster.statusCluster)}

                                        </td>
                                    </tr>
                                    <tr>
                                        <td>Cluster Size</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard.cluster.clusterSize}</td>
                                    </tr>
                                    <tr>
                                        <td>Partitions</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard.cluster.partitionsCount}</td>
                                    </tr>
                                    <tr>
                                        <td>Replication Factor</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard.cluster.replicationfactor}</td>
                                    </tr>
                                </table>
                            </Card.Body>
                        </Card>
                    </div>
                </div>

                <div className="row" style={{width: "100%", marginTop: "10px"}}>
                    <div className="col-md-12">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Connection</Card.Header>
                            <Card.Body>
                                <table style={{
                                    borderCollapse: "separate",
                                    borderSpacing: "10px",
                                    tableLayout: "fixed",
                                    width: "100%"
                                }}>
                                    <tr>
                                        <td style={{textAlign: "center"}}>
                                            {this.renderConnection("Zeebe", this.state.connection?.zeebe)}
                                        </td>
                                        <td style={{textAlign: "center"}}>
                                            {this.renderConnection("Zeebe Actuator", this.state.connection?.zeebeActuator)}
                                        </td>
                                        <td>
                                            {this.renderConnection("ElasticSearch", this.state.connection?.elasticsearch)}
                                        </td>
                                        <td>
                                            {this.renderConnection("Operate", this.state.connection?.operate)}
                                        </td>
                                        <td>
                                            {this.renderConnection("TaskList", this.state.connection?.tasklist)}
                                        </td>
                                        <td>
                                            {this.renderConnection("Optimize", this.state.connection?.optimize)}
                                        </td>
                                    </tr>
                                    <tr>
                                        <td colSpan="4">
                                            {this.state.connection.status}
                                        </td>
                                    </tr>
                                </table>
                            </Card.Body>
                        </Card>
                    </div>
                </div>
            </div>
        )

    }


    refreshDashboard = () => {
        let uri = 'blueberry/api/dashboard/all?forceRefresh=true';
        console.log("DashBoard.refreshDashboard http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.refreshDashboardCallback);
    }

    refreshDashboardCallback = (httpPayload) => {
        console.log("DashBoard.refreshDashboardCallback");

        this.setDisplayProperty("loading", false);
        if (httpPayload.isError()) {
            console.log("Dashboard.refreshDashboardCallback: error " + httpPayload.getError());
            this.setState({status: "Error"});
        } else {

            this.setState({dashboard: httpPayload.getData()});

        }
    }

    /**
     * Set the display property
     * @param propertyName name of the property
     * @param propertyValue the value
     */
    setDisplayProperty = (propertyName, propertyValue) => {
        let displayObject = this.state.display;
        displayObject[propertyName] = propertyValue;
        this.setState({display: displayObject});
    }

    schedule() {
        let uri = 'blueberry/api/dashboard/all?forceRefresh=false';
        console.log("DashBoard.schedule Schedule http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.refreshDashboardCallback);
    }


    checkConnection() {
        let uri = 'blueberry/api/dashboard/checkConnection?';
        console.log("DashBoard.checkConnection http[" + uri + "]");

        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.checkConnectionCallback);
    }

    checkConnectionCallback = (httpPayload) => {
        console.log("DashBoard.checkConnectionCallback");

        if (httpPayload.isError()) {
            console.log("Dashboard.refreshDashboardCallback: error " + httpPayload.getError());
            this.setState({status: "Error"});
        } else {
            this.setState({connection: httpPayload.getData()});
        }
    }


    renderConnectionTag(status) {
        if (!status)
            return <Tag type="gray">Unknown</Tag>;
        switch (status) {
            case "READY":
                return <Tag type="green">Connected</Tag>;

            case "NOT_CONNECTED":
                return <Tag type="purple">Not connected</Tag>;

            case "VERIFICATION_IN_PROGRESS":
                return <Tag type="blue">Verification in progress</Tag>;

            case "INPROGRESS":
                return <Tag type="blue">In progress</Tag>

            case "FAILED":
                return <Tag type="red">Failed</Tag>

            case "CORRECT":
                return <Tag type="green">Correct</Tag>

            case "ACTIF":
                return <Tag type="green">Started</Tag>

            case "INACTIF":
                return <Tag type="gray">Pause</Tag>

            default:
                return <Tag type="gray">Unknown</Tag>;
        }
    }

    renderConnection(name, connectionInfo) {
        if (connectionInfo == null) {
            return <div>{name}</div>
        }
        return <div>
            <table>
                <tr>
                    <td>{name}&nbsp;</td>
                    <td>{connectionInfo?.explanation &&
                        <OverlayTrigger
                            placement="top"
                            overlay={<Tooltip
                                id="tooltip">{connectionInfo.explanation}</Tooltip>}>
                                                      <span className="d-inline-block">
                                                        <Terminal size={20} className="text-muted"/>
                                                      </span>
                        </OverlayTrigger>}
                    </td>
                </tr>
                <tr>
                    <td colSpan="2">
                        {this.renderConnectionTag(connectionInfo.connection)}</td>
                </tr>
            </table>
        </div>
    }

}

export default Dashboard;
