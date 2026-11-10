// -----------------------------------------------------------
//
// Content
//
// List of all jar file uploaded
//
// -----------------------------------------------------------

import React from 'react';
import {Accordion, AccordionItem, Button, InlineNotification, Link, Tag} from "carbon-components-react";
import {ArrowRepeat, QuestionCircle, Terminal} from "react-bootstrap-icons";
import OverlayTrigger from "react-bootstrap/OverlayTrigger";
import Tooltip from "react-bootstrap/Tooltip";
import RestCallService from "../services/RestCallService";
import {Alert, Modal} from 'react-bootstrap';
import ControllerPage from "../component/ControllerPage";


class Configuration extends React.Component {


    constructor(_props) {
        super();

        this.state = {
            display: {loading: false},
            resultCall: {status: 200, error: "", message: "", component: ""},
            listChecks: [],
            openCommands: {},
            showConfirm: false
        };
    }

    /* Open or close the section showing the command of a verification */
    toggleCommand(key) {
        const openCommands = {...this.state.openCommands};
        openCommands[key] = !openCommands[key];
        this.setState({openCommands: openCommands});
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
                <div className="row" style={{width: "100%"}}>
                    <div className="col-md-12">
                        <ControllerPage
                            error={this.state.resultCall.error}
                            errorMessage={this.state.resultCall.errorMessage}
                            loading={this.state.display.loading}/>
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
                        <Button className="btn btn-primary btn-sm"
                                onClick={() => {
                                    this.setState({showConfirm: true})
                                }}
                                disabled={this.state.display.loading}>
                            Configure
                        </Button>
                    </div>
                </div>

                <Modal show={this.state.showConfirm} onHide={() => this.setState({showConfirm: false})}>
                    <Modal.Header closeButton>
                        <Modal.Title>Confirm the configuration</Modal.Title>
                    </Modal.Header>
                    <Modal.Body>
                        <p>This operation will update the system: it creates the missing Elasticsearch repositories
                            and applies the configuration needed for the backup.</p>
                        <p>Do you want to continue?</p>
                    </Modal.Body>
                    <Modal.Footer>
                        <Button kind="secondary" onClick={() => this.setState({showConfirm: false})}>Cancel</Button>
                        <Button kind="danger"
                                onClick={() => {
                                    this.setState({showConfirm: false});
                                    this.configure();
                                }}>
                            Configure
                        </Button>
                    </Modal.Footer>
                </Modal>
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
                                <td style={{fontWeight: "bold", fontStyle: "italic", fontSize: "0.8rem"}}>{content.name}</td>
                                <td>
                                    {this.renderConnectionTag(content.status)}
                                </td>
                                <td>
                                    {/* the actions executed by the rule, if any */}
                                    {content.actions &&
                                        <div style={{whiteSpace: "pre-wrap"}}>
                                            action: {content.actions}
                                        </div>
                                    }
                                    {/* the details, and the verifications */}
                                    {content.details}

                                    {content.errors && (
                                        <Alert variant="danger">
                                            {content.errors}
                                        </Alert>
                                    )}
                                    For debug
                                    {content.errors}
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
                                        <tr>
                                            <th className="border p-2" style={{background: "#e0e0e0", color: "#161616", fontWeight: "bold"}}>Steps</th>
                                            <th className="border p-2" style={{background: "#e0e0e0", color: "#161616", fontWeight: "bold"}}>Status</th>
                                        </tr>
                                        </thead>
                                        <tbody>
                                        {content.verifications.map((verification, index) => (
                                            <React.Fragment key={index}>
                                                <tr className="border">
                                                    <td className="border p-2">
                                                        {verification.action}
                                                        {verification.activity &&
                                                            <span>activity: <b><i>{verification.activity}</i></b></span>
                                                        }
                                                        &nbsp;&nbsp;
                                                        {verification.command &&
                                                            <span className="d-inline-block"
                                                                  role="button"
                                                                  title="Show / hide the command"
                                                                  style={{cursor: "pointer"}}
                                                                  onClick={() => this.toggleCommand(content.name + "-" + index)}>
                                                                <Terminal size={20}
                                                                          className={this.state.openCommands[content.name + "-" + index] ? "text-primary" : "text-muted"}/>
                                                            </span>
                                                        }
                                                        {verification.command && this.state.openCommands[content.name + "-" + index] &&
                                                            <div style={{marginTop: "4px"}}>
                                                                <div className="text-muted" style={{fontSize: "0.8rem"}}>Command</div>
                                                                <pre style={{
                                                                    background: "#f4f4f4",
                                                                    border: "1px solid #d0d0d0",
                                                                    borderRadius: "4px",
                                                                    padding: "10px",
                                                                    margin: 0,
                                                                    fontSize: "0.85rem",
                                                                    whiteSpace: "pre-wrap"
                                                                }}><code>{verification.command}</code></pre>
                                                            </div>
                                                        }
                                                    </td>
                                                    <td className="border p-2">
                                                        {this.renderConnectionTag(verification.actionStatus)}
                                                    </td>
                                                </tr>
                                                {verification.error &&
                                                    <tr className="border">
                                                        <td colSpan="2"
                                                            className={(verification.actionStatus === "INFO" || verification.actionStatus === "CORRECT") ?"border p-2 text-muted" : "border p-2 text-danger"}
                                                            style={{whiteSpace: "pre-wrap"}}>
                                                            {verification.error}
                                                        </td>
                                                    </tr>
                                                }
                                                {verification.configuration &&
                                                    <tr className="border">
                                                        <td colSpan="2" className="border p-2">
                                                            <div className="text-muted" style={{fontSize: "0.8rem"}}>Configuration</div>
                                                            <pre style={{
                                                                background: "#f4f4f4",
                                                                border: "1px solid #d0d0d0",
                                                                borderRadius: "4px",
                                                                padding: "10px",
                                                                margin: 0,
                                                                fontSize: "0.85rem",
                                                                whiteSpace: "pre"
                                                            }}><code>{verification.configuration}</code></pre>
                                                        </td>
                                                    </tr>
                                                }
                                            </React.Fragment>
                                        ))}
                                        </tbody>
                                    </table>
                                    <p/>
                                    {/* Documentation Links */}
                                    {content.urldocumentation && content.urldocumentation.length > 0 &&
                                    <Accordion className="mt-4" defaultActiveKey="0">
                                        <AccordionItem title="Documentation" eventKey="0">
                                            {content.urldocumentation.map((url, index) => (
                                                <p key={index}>
                                                    <Link href={url} target="_blank">
                                                        {url}
                                                    </Link>
                                                </p>
                                            ))}
                                        </AccordionItem>
                                    </Accordion>}
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
        let uri = '/blueberry/api/configuration/check?';
        console.log("platform.checkup http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.getJson(uri, this, this.refreshPlatformCallback);
    }

    configure() {
        let uri = '/blueberry/api/configuration/configure?';
        console.log("platform.configure http[" + uri + "]");

        this.setDisplayProperty("loading", true);
        this.setState({status: ""});
        var restCallService = RestCallService.getInstance();
        restCallService.postJson(uri, {"configure": "yes"}, this, this.refreshPlatformCallback);
    }

    refreshPlatformCallback(httpPayload) {
        this.setDisplayProperty("loading", false);
        if (httpPayload.isError()) {
            console.log("Configuration.startBackupCallback: error " + httpPayload.getError());
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


    renderConnectionTag(status) {
        if (!status)
            return <Tag type="gray">Unknown</Tag>;
        switch (status) {
            case "FAILED":
                return <Tag type="red">Failed</Tag>

            case "FAILEDBUTWILLBEFIXED":
                return <Tag type="blue">To be configured</Tag>

            case "CORRECT":
                return <Tag type="green">Correct</Tag>

            case "DEACTIVATED":
                return <Tag type="gray">Deactivated</Tag>

            case "INPROGRESS":
                return <Tag type="blue">In progress</Tag>

            case "NOT_CONNECTED":
                return <Tag type="magenta">Not connected</Tag>

            case "INFO":
                return <Tag type="cyan">Info</Tag>


            default:
                return <Tag type="gray">Unknown</Tag>;
        }
    }
}

export default Configuration;