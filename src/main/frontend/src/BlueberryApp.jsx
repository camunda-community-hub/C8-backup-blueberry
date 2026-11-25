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

import {Container, Nav, Navbar} from 'react-bootstrap';
import Dashboard from "./dashboard/Dashboard";
import Scheduler from "./scheduler/Scheduler";
import Backup from "./backup/Backup"
import Restore from "./restore/Restore"
import CheckData from "./checkdata/CheckData"
import Configuration from "./configuration/Configuration"
import Parameters from "./parameters/Parameters";
import OperationLog from "./operationlog/OperationLog"
import HeaderMessage from "./HeaderMessage/HeaderMessage";
import RestCallService from "./services/RestCallService";

const FRAME_NAME = {
  DASHBOARD: "Dashboard",
  BACKUP: "Backup",
  OPERATIONLOG: "OperationLog",
  SCHEDULER: "Scheduler",
  RESTORE: "Restore",
  CHECKDATA: "CheckData",
  CONFIGURATION: "Configuration",
  PARAMETERS: "Parameters"

}

class BlueberryApp extends React.Component {


  constructor(_props) {
    super();
    this.state = {
      frameContent: FRAME_NAME.DASHBOARD,
      docsBackupUrl: "",
      docsRestoreUrl: "",
      // The version of Blueberry: given by the REST API blueberry/api/version (the pom.xml), not written here
      blueberryVersion: ""
    };
    this.clickMenu = this.clickMenu.bind(this);
  }

  componentDidMount() {
    RestCallService.getInstance().getJson("blueberry/api/version?", this, (httpPayload) => {
      if (!httpPayload.isError()) {
        const data = httpPayload.getData();
        this.setState({
          docsBackupUrl: data.docsBackupUrl,
          docsRestoreUrl: data.docsRestoreUrl,
          blueberryVersion: data.blueberryVersion || ""
        });
      }
    });
  }


  render() {
    return (
      <div>

        <Navbar bg="light" variant="light">
          <Container>
            <Nav className="mr-auto">
              <Navbar.Brand href="#home">
                <img src="/img/blueberry.png" width="28" height="28" alt="Blueberry"/>
                &nbsp;Blueberry Backup {this.state.blueberryVersion &&
                <span style={{fontSize: "0.7rem"}}>v{this.state.blueberryVersion}</span>}
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
                this.clickMenu(FRAME_NAME.OPERATIONLOG)
              }}>{FRAME_NAME.OPERATIONLOG}</Nav.Link>

              <Nav.Link onClick={() => {
                this.clickMenu(FRAME_NAME.CHECKDATA)
              }}>{FRAME_NAME.CHECKDATA}</Nav.Link>

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
          </Container>
        </Navbar>
        {this.state.docsBackupUrl &&
          <div className="container" style={{marginTop: "4px", fontSize: "0.85rem"}}>
            Camunda documentation:{" "}
            <a href={this.state.docsBackupUrl} target="_blank" rel="noreferrer">Backup</a>
            {" · "}
            <a href={this.state.docsRestoreUrl} target="_blank" rel="noreferrer">Restore</a>
          </div>}
        <HeaderMessage/>
        {this.state.frameContent === FRAME_NAME.DASHBOARD && <Dashboard/>}
        {this.state.frameContent === FRAME_NAME.BACKUP && <Backup/>}
        {this.state.frameContent === FRAME_NAME.SCHEDULER && <Scheduler/>}
        {this.state.frameContent === FRAME_NAME.OPERATIONLOG && <OperationLog/>}
        {this.state.frameContent === FRAME_NAME.CHECKDATA && <CheckData/>}
        {this.state.frameContent === FRAME_NAME.RESTORE &&
            <Restore onOpenConfiguration={() => this.clickMenu(FRAME_NAME.CONFIGURATION)}/>}
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


