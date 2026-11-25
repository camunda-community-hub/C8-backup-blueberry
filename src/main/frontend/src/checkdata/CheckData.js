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
                        <h1 className="title"><DatabaseCheck/> Check Data</h1>
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
                                            <th>Role</th>
                                            <th>Processed position</th>
                                            <th>Exported position</th>
                                            <th>Zeebe record: last position</th>
                                            <th>Zeebe record: last sequence</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {this.partitions().map((entry) => (
                                            <React.Fragment key={entry.partitionInfo?.partitionId}>
                                                <tr>
                                                    <td><b>{entry.partitionInfo?.partitionId}</b></td>
                                                    <td>{entry.partitionInfo?.role}</td>
                                                    <td>{entry.partitionInfo?.processedPosition}</td>
                                                    <td>{entry.partitionInfo?.exportedPosition}</td>
                                                    <td>{entry.zeebeRecordPosition}</td>
                                                    <td>{entry.zeebeRecordSequence}</td>
                                                </tr>
                                                {Object.values(entry.sequenceInfo || {}).map((sequence) => (
                                                    <tr key={entry.partitionInfo?.partitionId + "-" + sequence.typeSequence} className="text-muted">
                                                        <td></td>
                                                        <td colSpan="3" style={{paddingLeft: "2em"}}>{sequence.typeSequence}</td>
                                                        <td>{sequence.zeebeRecordPosition}</td>
                                                        <td>{sequence.zeebeRecordSequence}</td>
                                                    </tr>
                                                ))}
                                            </React.Fragment>
                                        ))}
                                        </tbody>
                                    </table>

                                </Card.Body>
                            </Card>
                    </div>

                </div>

                <div className="row" style={{width: "100%", marginTop: "10px"}}>
                    <div className="col-md-12">
                        <Card>
                            <Card.Header style={{backgroundColor: "rgba(0,0,0,.03)"}}>How to read these positions</Card.Header>
                            <Card.Body className="text-muted" style={{fontSize: "0.85rem"}}>
                                <p><b>Processed position</b> is the last position of the log of the partition that the broker has finished processing.
                                    <b> Exported position</b> is the last position the exporters have acknowledged. The two do not have to be equal:
                                    some records are written to the log and exported without being processed as a command (for example the events
                                    produced by a timer or a boundary event), so the exported position can be a little higher than the processed one.</p>
                                <p><b>Zeebe record: last position</b> is the highest position found in the zeebe-record indices of Elasticsearch.
                                    It only counts the records that were really indexed. The exporter does not write every record of the log to
                                    Elasticsearch, only the types of records it is configured to export, and it writes them in batches. The last position
                                    acknowledged by the exporters can be a record that is not in the index, so the exported position can be higher than
                                    the last position found in Elasticsearch.</p>
                                <p><b>Last sequence</b> is the sequence number of the last record indexed for a type of record. The partition number
                                    is stored in the high bits of the number (the value is partition * 2^51 + a counter), which is why the number looks
                                    so large: for the partition 1, 2251799813685738 means 490 records of this type.</p>
                            </Card.Body>
                        </Card>
                    </div>
                </div>

            </div>
        )

    }


    /**
     * The partitions of the answer: the API gives an object, the key is the partition id
     */
    partitions() {
        const synthesis = this.state.dashboard?.synthesis;
        return synthesis ? Object.values(synthesis) : [];
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
            this.setState({
                status: "Error",
                dashboard: {},
                resultCall: {status: 500, error: "Error", errorMessage: httpPayload.getError()}
            });
        } else {
            this.setState(
                {
                    // The API answers {error, errorMessage} when a check failed: they are displayed in a red box (ControllerPage)
                    resultCall: {
                        status: httpPayload.getData().status,
                        error: httpPayload.getData().error || "",
                        errorMessage: httpPayload.getData().errorMessage || ""
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
