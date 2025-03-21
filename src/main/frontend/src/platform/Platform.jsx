// -----------------------------------------------------------
//
// Content
//
// List of all jar file uploaded
//
// -----------------------------------------------------------

import React from 'react';
import {Accordion, AccordionItem, Button, InlineNotification, Link, Tag} from "carbon-components-react";
import {ArrowRepeat, QuestionCircle, Terminal } from "react-bootstrap-icons";
import OverlayTrigger from "react-bootstrap/OverlayTrigger";
import Tooltip from "react-bootstrap/Tooltip";
import RestCallService from "../services/RestCallService";

class Platform extends React.Component {


    constructor(_props) {
        super();

        this.state = {
            display: {loading: false},
            listChecks: []
        };
    }

    componentDidMount() {
    }

    /*           {JSON.stringify(this.state.runners, null, 2) } */
    render() {
        return (
            <div className={"container"}>
                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-10">
                        <h1 className="title">Checkup</h1>
                        <InlineNotification kind="info" hideCloseButton="true" lowContrast="false">
                            Verify the configuration, to detect any mis configuration.
                            <li> Does each component define correctly backup information</li>
                            <li> Does the configuration exist, i.e. Elasticsearch component are correctly defined?</li>
                        </InlineNotification>

                    </div>
                </div>
                <div className="row" style={{marginTop: "10px"}}>
                    <div className="col-md-2">
                        <Button className="btn btn-success btn-sm"
                                onClick={() => {
                                    this.checkup()
                                }}
                                disabled={this.state.display.loading}>
                            <ArrowRepeat/> Checkup
                        </Button>
                    </div>
                    <div className="col-md-2">
                        <Button className="btn btn-danger btn-sm"
                                onClick={() => {
                                    this.configure()
                                }}
                                disabled={this.state.display.loading}>
                            Configure
                        </Button>
                    </div>
                </div>
                <div className="row" style={{marginTop: "10px"}}>
                    <table className="table is-hoverable is-fullwidth">
                        <thead>
                        <tr>
                            <th>Name</th>
                            <th>Status</th>
                            <th>Details</th>
                        </tr>
                        </thead>
                        <tbody>
                        {this.state.listChecks ? this.state.listChecks.map((content, _index) =>
                            <tr>
                                <td>{content.name}</td>
                                <td>
                                    {content.status === "FAILED" && <Tag type="red">Failed</Tag>}
                                    {content.status === "CORRECT" && <Tag type="green">Correct</Tag>}
                                    {content.status === "DEACTIVATED" && <Tag type="gray">Deactivated</Tag>}
                                </td>
                                <td>{content.detail}
                                    &nbsp;&nbsp;
                                    <OverlayTrigger
                                        placement="top"
                                        overlay={<Tooltip id="tooltip">{content.explanations}</Tooltip>}
                                    >
                                      <span className="d-inline-block">
                                        <QuestionCircle size={20} className="text-muted"/>
                                      </span>
                                    </OverlayTrigger>
                                    <table className="w-full border-collapse border border-gray-300">
                                        <thead>
                                        <tr className="bg-gray-100">
                                            <th className="border p-2">Action</th>
                                            <th className="border p-2">Status</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {content.verifications.map((verification, index) => (
                                            <tr key={index} className="border">
                                                <td className="border p-2">{verification.action}
                                                    <OverlayTrigger
                                                        placement="top"
                                                        overlay={<Tooltip id="tooltip">{verification.command}</Tooltip>}
                                                    >
                                                      <span className="d-inline-block">
                                                        <Terminal size={20} className="text-muted"/>
                                                      </span>
                                                    </OverlayTrigger>
                                                </td>
                                                <td className="border p-2">
                                                    {verification.actionStatus === "FAILED" &&
                                                        <Tag type="red">Failed</Tag>}
                                                    {verification.actionStatus === "CORRECT" &&
                                                        <Tag type="green">Correct</Tag>}
                                                    {verification.actionStatus === "DEACTIVATED" &&
                                                        <Tag type="gray">Deactivated</Tag>}
                                                </td>
                                            </tr>
                                        ))}
                                        </tbody>
                                    </table>
                                    <p/>
                                    {/* Documentation Links */}
                                    <Accordion className="mt-4">
                                        <AccordionItem title="Documentation">
                                            {content.urldocumentation.map((url, index) => (
                                                <p key={index}>
                                                    <Link href={url} target="_blank">
                                                        {url}
                                                    </Link>
                                                </p>
                                            ))}
                                        </AccordionItem>
                                    </Accordion>
                                </td>
                            </tr>
                        ) : <div/>}
                        </tbody>
                    </table>
                </div>
            </div>

        )
    }


    checkup() {
        let uri = '/blueberry/api/platform/check?';
        console.log("platform.checkup http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.refreshPlatformCallback);
    }

    configure() {
        let uri = '/blueberry/api/platform/configure?';
        console.log("platform.configure http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.postJson(uri, {"configure": "yes"}, this, this.refreshPlatformCallback);
    }

    refreshPlatformCallback(httpPayload) {
        this.setDisplayProperty("loading", false);
        if (httpPayload.isError()) {
            console.log("Platform.startBackupCallback: error " + httpPayload.getError());
            this.setState({status: "Error"});
        } else {
            this.setState({listChecks: httpPayload.getData()})
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
}

export default Platform;