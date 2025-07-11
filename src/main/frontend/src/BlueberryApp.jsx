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
import Configuration from "./configuration/Configuration"
import Parameters from "./parameters/Parameters";
import OperationLog from "./operationlog/OperationLog"
import HeaderMessage from "./HeaderMessage/HeaderMessage";

const FRAME_NAME = {
  DASHBOARD: "Dashboard",
  BACKUP: "Backup",
  SCHEDULER: "Scheduler",
  RESTORE: "Restore",
  CONFIGURATION: "Configuration",
  PARAMETERS: "Parameters"

}

class BlueberryApp extends React.Component {


  constructor(_props) {
    super();
    this.state = {frameContent: FRAME_NAME.DASHBOARD};
    this.clickMenu = this.clickMenu.bind(this);
  }


  render() {
    return (
      <div>

        <Navbar bg="light" variant="light">
          <Container>
            <Nav className="mr-auto">
              <Navbar.Brand href="#home">
                <img src="/img/blueberry.png" width="28" height="28" alt="Blueberry"/>
                &nbsp;Blueberry Backup <span style={{fontSize: "0.7rem"}}>v1.3.0</span>
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
          </Container>
        </Navbar>
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


