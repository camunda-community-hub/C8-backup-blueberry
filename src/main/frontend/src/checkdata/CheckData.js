// -----------------------------------------------------------
//
// CheckData
//
// Verify that the data is consistent
//
// -----------------------------------------------------------

import React from 'react';

import {Button, InlineNotification, Tag} from "carbon-components-react";
import {Card} from 'react-bootstrap';
import RestCallService from "../services/RestCallService";
import {DatabaseCheck} from "react-bootstrap-icons";
import ControllerPage from "../component/ControllerPage";


class CheckData extends React.Component {


    constructor(_props) {
        super();
        this.state = {
            checkData: {},
            resultCall: {status: 200, error: "", message: "", component: ""},
            display: {
                loading: false
            },


        };
        this.setDisplayProperty = this.setDisplayProperty.bind(this);
    }

    componentDidMount() {
    }

    // Cleanup to clear the interval when the component unmounts
    componentWillUnmount() {
    }

    render() {
        // console.log("dashboard.render display="+JSON.stringify(this.state.display));




        return (<div className={"container"}>

                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-10">
                        <h1 className="title">Check Data</h1>
                        <InlineNotification kind="info" hideCloseButton="true" lowContrast="false">
                            Check if data is consistent between Zeebe, Operate, Tasklist, Optimize
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

                <div className="row" style={{width: "100%", marginTop: "10px"}}>
                    <div className="col-md-2">
                        <Button className="btn btn-success btn-sm"
                                onClick={() => {
                                    this.checkData();
                                }}
                                disabled={this.state.display.loading}>
                            <DatabaseCheck/> Check Data
                        </Button>
                    </div>
                </div>


                <div className="row" style={{width: "100%", marginTop: "10px"}}>

                    <div className="col-md-8">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Message</Card.Header>
                            <Card.Body>
                                <table id="Check" className="table is-hoverable is-fullwidth">
                                    <thead>
                                    <tr>
                                        <th>Partition</th>
                                        <th>Type</th>
                                        <th>Message</th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    {this.state.dashboard?.checks?.allMessages?.map((entry, index) => (
                                        <tr>
                                            <td>{entry.partitionId}</td>
                                            <td>{this.renderConnectionTag(entry.level, entry.type)}</td>
                                            <td>{entry.message}</td>
                                        </tr>
                                    ))}
                                    </tbody>
                                </table>

                            </Card.Body>
                        </Card>
                    </div>

                    <div className="col-md-4">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Cluster information Data</Card.Header>
                            <Card.Body>
                                <table style={{borderCollapse: "separate", borderSpacing: "10px"}}>
                                    <tr>
                                        <td>Cluster Size</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard?.clusterInformation?.clusterSize}</td>
                                    </tr>
                                    <tr>
                                        <td>Partitions</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard?.clusterInformation?.partitionsCount}</td>
                                    </tr>
                                    <tr>
                                        <td>Replication Factor</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard?.clusterInformation?.replicationFactor}</td>
                                    </tr>
                                    <tr>
                                        <td>Version</td>
                                        <td style={{textAlign: "right"}}>{this.state.dashboard?.clusterInformation?.camundaVersion}</td>
                                    </tr>
                                </table>
                            </Card.Body>
                        </Card>
                    </div>

                </div>


                <div className="row" style={{width: "100%", marginTop: "10px"}}>
                    <div className="col-md-12">
                            <Card>
                                <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>Detail</Card.Header>
                                <Card.Body>


                                    <table id="runnersTable" className="table is-hoverable is-fullwidth">
                                        <thead>
                                        <tr>
                                            <th>Partition</th>
                                            <th>Zeebe position</th>
                                            <th>Zeebe Record</th>
                                            <th>Operate</th>
                                            <th>Tasklist</th>
                                            <th>Optimize</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {this.state.dashboard2?.synthesis?.map((entry, index) => (
                                            <tr>
                                                <td>{entry.partitionInfo.partitionId}</td>
                                                <td>{entry.zeebeRecordSequence}</td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>

                                </Card.Body>
                            </Card>
                    </div>

                </div>

            </div>
        )

    }


    checkData = () => {
        let uri = 'blueberry/api/checkdata?';
        console.log("CheckData.checkData http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.checkDataCallback);
    }

    checkDataCallback = (httpPayload) => {
        console.log("checkData.checkDataCallback");

        this.setDisplayProperty("loading", false);
        if (httpPayload.isError()) {
            console.log("checkData.checkDataCallback: error " + httpPayload.getError());
            this.setState({status: "Error"});
        } else {
            this.setState(
                {
                    resultCall: {
                        status: httpPayload.getData().status
                    },
                    dashboard: httpPayload.getData()
                })

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

    renderConnectionTag(level, status) {
        console.log("level=["+level+"] status=["+status+"]");
        if (!level)
            return <Tag type="gray">{status}</Tag>;

        switch (level) {
            case "ERROR":
                return <Tag type="red">{status}</Tag>;
            case "INFO":
                return <Tag type="blue">{status}</Tag>;
            case "DEBUG":
                return <Tag type="gray">{status}</Tag>;
            default:
                return <Tag type="gray">{status}</Tag>;

        }
    }

}

export default CheckData;
