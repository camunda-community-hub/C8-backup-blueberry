// -----------------------------------------------------------
//
// BlueberryApps
//
// Manage the main application
//
// -----------------------------------------------------------

import React from 'react';
import './index.scss';

import 'bootstrap/dist/css/bootstrap.min.css';

import {Container, Form, Nav, Navbar} from 'react-bootstrap';
import Dashboard from "./dashboard/Dashboard";
import Scheduler from "./scheduler/Scheduler";
import Backup from "./backup/Backup"
import Restore from "./restore/Restore"
import Configuration from "./configuration/Configuration"
import Parameters from "./parameters/Parameters";
import HeaderMessage from "./HeaderMessage/HeaderMessage";
import RestCallService from "./services/RestCallService";

const FRAME_NAME = {
  DASHBOARD: "Dashboard",
  BACKUP: "Backup",
  SCHEDULER: "Scheduler",
  RESTORE: "Restore",
  CONFIGURATION: "Configuration",
  PARAMETERS: "Parameters"

}

const VERSION_STORAGE_KEY = "blueberry.camunda.minorVersion";

class BlueberryApp extends React.Component {


  constructor(_props) {
    super();
    this.state = {
      frameContent: FRAME_NAME.DASHBOARD,
      camundaVersion: "8.7",
      supportedMinors: ["8.7", "8.8", "8.9", "8.10"],
      docsBackupUrl: "",
      docsRestoreUrl: ""
    };
    this.clickMenu = this.clickMenu.bind(this);
    this.onVersionChange = this.onVersionChange.bind(this);
  }

  componentDidMount() {
    const stored = localStorage.getItem(VERSION_STORAGE_KEY);
    if (stored) {
      this.setState({camundaVersion: stored});
    }
    RestCallService.getInstance().getJson("blueberry/api/version?", this, (httpPayload) => {
      if (!httpPayload.isError()) {
        const data = httpPayload.getData();
        const minor = stored || data.minorVersion || "8.7";
        this.setState({
          camundaVersion: minor,
          supportedMinors: data.supportedMinors || this.state.supportedMinors,
          docsBackupUrl: data.docsBackupUrl,
          docsRestoreUrl: data.docsRestoreUrl
        });
        if (stored && stored !== data.minorVersion) {
          this.syncVersionToServer(stored);
        }
      }
    });
  }

  syncVersionToServer(minor) {
    RestCallService.getInstance().putJson(
      "blueberry/api/version?",
      {minorVersion: minor},
      this,
      (httpPayload) => {
        if (!httpPayload.isError()) {
          const data = httpPayload.getData();
          this.setState({
            docsBackupUrl: data.docsBackupUrl,
            docsRestoreUrl: data.docsRestoreUrl
          });
        }
      }
    );
  }

  onVersionChange(event) {
    const minor = event.target.value;
    localStorage.setItem(VERSION_STORAGE_KEY, minor);
    this.setState({camundaVersion: minor});
    this.syncVersionToServer(minor);
  }


  render() {
    return (
      <div>

        <Navbar bg="light" variant="light">
          <Container>
            <Nav className="mr-auto">
              <Navbar.Brand href="#home">
                <img src="/img/blueberry.png" width="28" height="28" alt="Blueberry"/>
                &nbsp;Blueberry Backup <span style={{fontSize: "0.7rem"}}>v1.4.0</span>
              </Navbar.Brand>

              <Nav.Link onClick={() => {
                this.clickMenu(FRAME_NAME.DASHBOARD)
              }}>{FRAME_NAME.DASHBOARD}</Nav.Link>

              <Nav.Link onClick={() => {
                this.clickMenu(FRAME_NAME.BACKUP)
              }}>{FRAME_NAME.BACKUP}</Nav.Link>

              <Nav.Link onClick={() => {
                this.clickMenu(FRAME_NAME.RESTORE)
              }}>{FRAME_NAME.RESTORE}</Nav.Link>

              <Nav.Link onClick={() => {
                this.clickMenu(FRAME_NAME.SCHEDULER)
              }}>{FRAME_NAME.SCHEDULER}</Nav.Link>


              <Nav.Link onClick={() => {
                this.clickMenu(FRAME_NAME.CONFIGURATION)
              }}>{FRAME_NAME.CONFIGURATION}</Nav.Link>

              <Nav.Link onClick={() => {
                this.clickMenu(FRAME_NAME.PARAMETERS)
              }}>{FRAME_NAME.PARAMETERS}</Nav.Link>

            </Nav>
            <Form.Select
              size="sm"
              style={{width: "110px"}}
              aria-label="Camunda version"
              value={this.state.camundaVersion}
              onChange={this.onVersionChange}>
              {this.state.supportedMinors.map(v =>
                <option key={v} value={v}>{v}</option>)}
            </Form.Select>
          </Container>
        </Navbar>
        {this.state.docsBackupUrl &&
          <div className="container" style={{marginTop: "4px", fontSize: "0.85rem"}}>
            Docs for Camunda {this.state.camundaVersion}:{" "}
            <a href={this.state.docsBackupUrl} target="_blank" rel="noreferrer">Backup</a>
            {" · "}
            <a href={this.state.docsRestoreUrl} target="_blank" rel="noreferrer">Restore</a>
          </div>}
        <HeaderMessage/>
        {this.state.frameContent === FRAME_NAME.DASHBOARD && <Dashboard/>}
        {this.state.frameContent === FRAME_NAME.BACKUP && <Backup/>}
        {this.state.frameContent === FRAME_NAME.SCHEDULER && <Scheduler/>}
        {this.state.frameContent === FRAME_NAME.RESTORE && <Restore/>}
        {this.state.frameContent === FRAME_NAME.CONFIGURATION && <Configuration/>}
        {this.state.frameContent === FRAME_NAME.PARAMETERS && <Parameters/>}


      </div>);
  }


  clickMenu(menu) {
    console.log("ClickMenu " + menu);
    this.setState({frameContent: menu});

  }

}

export default BlueberryApp;

