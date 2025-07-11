// -----------------------------------------------------------
//
// Parameters
//
// List of all runners available
//
// -----------------------------------------------------------

import React from 'react';
import RestCallService from "../services/RestCallService";
import ControllerPage from "../component/ControllerPage";

import {Button, Checkbox, InlineNotification, Tag, TextInput} from "carbon-components-react";
import {ArrowRepeat} from "react-bootstrap-icons";
import {Card} from "react-bootstrap";


class Backup extends React.Component {


    constructor(_props) {
        super();

        this.state = {
            display: {loading: false},
            parameter: {explicit: false, backupId: ''},
            resultCall: {status: 200, error: "", message: "", component: ""},
            listBackups: [],
            listUrlsBackup: [],
            currentBackup: {statusBackup: ""}

        };

    }

    componentDidMount() {
        this.monitorBackup();

        // Set up the interval to call schedule() every 10 seconds
        this.intervalConnectionId = setInterval(() => {
            this.monitorBackup();
        }, 20000);

    }

    // Cleanup to clear the interval when the component unmounts
    componentWillUnmount() {
        console.log("Backup: componentWillUnmount");
        clearInterval(this.intervalId);
    }

    /*           {JSON.stringify(this.state.runners, null, 2) } */
    render() {
        return (
            <div className={"container"}>
                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-10">
                        <h1 className="title">Backup</h1>
                        <InlineNotification kind="info" hideCloseButton="true" lowContrast="false">
                            Last backup are listed.
                        </InlineNotification>

                    </div>
                </div>

                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-12">
                        <ControllerPage
                            error={this.state.resultCall.error}
                            errorMessage={this.state.resultCall.errorMessage}
                            loading={this.state.display.loading}/>
                    </div>
                </div>


                <div className="row" style={{marginTop: "10px"}}>
                    <div className="col-md-6">
                        <Card>
                            <Card.Header
                                style={{backgroundColor: "rgba(0,0,0,.03)", height: "50px"}}>Backup</Card.Header>
                            <Card.Body>

                                <Checkbox
                                    id="chooseBackup"
                                    labelText="Give explicit backupId"
                                    checked={this.state.parameter.explicit}
                                    onChange={(event) => {
                                        var parameter = this.state.parameter;
                                        console.log("Parameters=[" + parameter + "] checked=" + event.target.checked);
                                        parameter.explicit = event.target.checked;
                                        this.setState({"parameter": parameter})
                                    }}
                                />

                                <TextInput
                                    className="m-3"
                                    id="explicitId"
                                    labelText="Explicit ID (number only)"
                                    disabled={!this.state.parameter.explicit}
                                    value={this.state.parameter.backupId}
                                    type="number"
                                    onChange={(event) => {
                                        var parameter = this.state.parameter;
                                        parameter.backupId = event.target.value;
                                        this.setState({"parameter": parameter})
                                    }}
                                    placeholder="Enter some text"
                                />

                                <Button className="btn btn-warning btn-sm"
                                        onClick={() => {
                                            this.startBackup()
                                        }}
                                        disabled={this.state.display.loading}>
                                    Start a backup
                                </Button>
                            </Card.Body>
                        </Card>
                    </div>
                    <div className="col-md-6">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)", height: "50px"}}
                                         className="d-flex justify-content-between align-items-center h-150">Current
                                backup
                                <Button className="btn btn-light btn-sm"
                                        onClick={() => {
                                            this.monitorBackup();
                                        }}
                                        disabled={this.state.display.loading}>
                                    <ArrowRepeat/>
                                </Button>
                            </Card.Header>
                            <Card.Body>

                                <table style={{borderCollapse: "separate", borderSpacing: "10px"}}>
                                    <tr>
                                        <td style={{verticalAlign: "middle"}}>Status</td>
                                        <td style={{textAlign: "center"}}>
                                            {this.renderConnectionTag(this.state.currentBackup.statusBackup)}

                                        </td>
                                    </tr>
                                    <tr>
                                        <td>backup Id</td>
                                        <td>{this.state.currentBackup.backupId}</td>
                                    </tr>
                                    <tr>
                                        <td>Advancement</td>
                                        <td>{this.state.currentBackup.step} /
                                            {this.state.currentBackup.totalNumberOfSteps}
                                        </td>
                                    </tr>
                                    <tr>
                                        <td>Current operation</td>
                                        <td>{this.state.currentBackup.stepName}</td>
                                    </tr>
                                    <tr>
                                        <td>Date status</td>
                                        <td>{this.state.currentBackup.dateStatus}</td>
                                    </tr>
                                </table>


                                <div>
                                    {this.state.currentBackup?.messages?.map((entry, index) => (
                                        <div key={index}
                                             className={`alert ${entry.type === 'ERROR' ? 'alert-danger' : 'alert-info'}`}
                                             role="alert">
                                            <strong>{entry.type}</strong> - ({entry.component})
                                            – {new Date(entry.date).toLocaleString()}<br/>
                                            {entry.message}
                                        </div>
                                    ))}
                                </div>


                            </Card.Body>
                        </Card>
                    </div>
                </div>
                <div className="row" style={{marginTop: "10px"}}>
                    <Button className="btn btn-success btn-sm"
                            onClick={() => {
                                this.refreshListBackups();
                                this.monitorBackup();
                            }}
                            disabled={this.state.display.loading}>
                        <ArrowRepeat/> Refresh
                    </Button>
                </div>


                <table id="runnersTable" className="table is-hoverable is-fullwidth">
                    <thead>
                    <tr>
                        <th>ID</th>
                        <th>Status</th>
                        <th>Components</th>
                        <th>Date</th>
                    </tr>
                    </thead>
                    <tbody>
                    {this.state.listBackups ? this.state.listBackups.map((content, _index) =>
                        <tr>
                            <td>{content.backupId}</td>
                            <td>
                                {this.renderConnectionTag(content.backupStatus)}
                            </td>
                            <td>
                                {content.components && content.components.map((component, index) => (
                                    <Tag key={index} type="blue">{component}</Tag>
                                ))}
                            </td>
                            <td>{content.backupTime}</td>
                        </tr>
                    ) : <div/>
                    }
                    </tbody>
                </table>

                <div className="row" style={{marginTop: "10px"}}>
                    <div className="col-md-12">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)", height: "50px"}}>
                                List URLs
                            </Card.Header>
                            <Card.Body>
                                <table className="table is-hoverable is-fullwidth">
                                    <thead>
                                    <tr>
                                        <th>Component</th>
                                        <th>Url</th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    {this.state.listUrlsBackup ? this.state.listUrlsBackup.map((content, _index) =>
                                        <tr>
                                            <td>{content.name}</td>
                                            <td>{content.url}</td>
                                        </tr>
                                    ) : <div/>
                                    }
                                    </tbody>
                                </table>
                            </Card.Body>
                        </Card>
                    </div>
                </div>
            </div>

        )
    }


    renderConnectionTag(status) {
        if (!status)
            return <Tag type="gray">Unknown</Tag>;

        switch (status) {
            case "COMPLETED":
                return <Tag type="green">Complete</Tag>;
            case "FAILED":
                return <Tag type="red">Failed</Tag>;
            case "INPROGRESS":
                return <Tag type="blue">In progress</Tag>;
            case "READY":
                return <Tag type="green">Ready</Tag>;
            case "PARTIALBACKUP":
                return <Tag type="red">Partial backup</Tag>;
            default:
                return <Tag type="gray">Unknown</Tag>;

        }
    }

    /* Set the display property
 * @param propertyName name of the property
 * @param propertyValue the value
 */
    setDisplayProperty(propertyName, propertyValue) {
        let displayObject = this.state.display;
        displayObject[propertyName] = propertyValue;
        this.setState({display: displayObject});
    }

    monitorBackup() {
        let uri = '/blueberry/api/backup/monitor?';
        console.log("backup.monitorBackup http[" + uri + "]");

        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.monitorBackupCallback);
    }

    monitorBackupCallback(httpPayload) {
        console.log("Backup.monitorBackupCallback");
        if (httpPayload.isError()) {
            console.log("Backup.monitorBackupCallback: error " + httpPayload.getError());
            this.setState({
                error: {status: 500, error: httpPayload.getError()},
                statusOperation: ""
            });
        } else {
            this.setState({currentBackup: httpPayload.getData()})
        }
    }


    refreshListBackups() {
        let uri = '/blueberry/api/backup/list?';
        console.log("backup.refresh http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.refreshListBackupsCallback);
    }

    refreshListBackupsCallback(httpPayload) {
        this.setDisplayProperty("loading", false);
        if (httpPayload.isError()) {
            console.log("Backup.monitorBackupCallback: error " + httpPayload.getError());
            this.setState({statusOperation: "Error"});
        } else {
            this.setState(
                {
                    resultCall: {
                        status: httpPayload.getData().status,
                        error: httpPayload.getData().error,
                        errorMessage: httpPayload.getData().errorMessage,
                        message: httpPayload.getData().message
                    },
                    listBackups: httpPayload.getData().listBackups,
                    listUrlsBackup: httpPayload.getData().listUrlsBackup
                })
        }
    }


    startBackup() {
        let uri = '/blueberry/api/backup/start?';
        console.log("backup.startBackup http[" + uri + "]");
        var parameterBackup = {"nextId": !this.state.parameter.explicit, "backupId": this.state.parameter.backupId}
        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.postJson(uri, parameterBackup, this, this.startBackupCallback);
    }

    startBackupCallback(httpPayload) {
        this.setDisplayProperty("loading", false);
        if (httpPayload.isError()) {
            debugger;
            console.log("Backup.startBackupCallback: error " + httpPayload.getError());
            this.setState({statusOperation: "Error"});
        } else {
            this.setState(
                {
                    resultCall: {
                        status: httpPayload.getData().status,
                        error: httpPayload.getData().error,
                        message: httpPayload.getData().message,
                        component: httpPayload.getData().component
                    },
                    backup: httpPayload.getData().backupId,
                    statusOperation: httpPayload.getData().statusOperation
                });
            this.monitorBackup();
            this.refreshListBackups();
        }
    }


}

export default Backup;