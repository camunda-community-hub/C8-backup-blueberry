// -----------------------------------------------------------
//
// BackupListTable
//
// The list of backups, shared by the Backup page and the Restore page.
// Columns: Backup ID, Date, Status, Components, Missing, and Action.
// The Action column is displayed only if the page gives a renderAction function (Restore page).
// The Backup ID column can be sorted: the biggest ID at the end (default), or the newest on the top.
//
// -----------------------------------------------------------

import React from 'react';
import {Tag} from "carbon-components-react";
import {OverlayTrigger, Tooltip} from "react-bootstrap";
import {SortNumericDown, SortNumericUpAlt} from "react-bootstrap-icons";

class BackupListTable extends React.Component {

    constructor(props) {
        super(props);
        // false: the biggest backup ID at the end (the order of the API). true: the newest on the top
        this.state = {newestFirst: false};
    }

    /**
     * The rows sorted by backup ID (a number), according to the choice of the user
     */
    sortedRows() {
        const rows = this.props.rows;
        if (!rows) {
            return rows;
        }
        const direction = this.state.newestFirst ? -1 : 1;
        return [...rows].sort((a, b) => direction * (Number(a.backupId) - Number(b.backupId)));
    }

    renderSortButton() {
        const newestFirst = this.state.newestFirst;
        const tip = newestFirst
            ? "Newest backup on the top. Click to show the biggest Backup ID at the end"
            : "Biggest Backup ID at the end. Click to show the newest backup on the top";
        return (
            <OverlayTrigger placement="top" overlay={<Tooltip id="tooltip-sort-backup-id">{tip}</Tooltip>}>
                <button type="button" className="btn btn-link btn-sm p-0 ms-1" aria-label={tip}
                        onClick={() => this.setState({newestFirst: !newestFirst})}>
                    {newestFirst ? <SortNumericUpAlt/> : <SortNumericDown/>}
                </button>
            </OverlayTrigger>
        );
    }

    render() {
        const rows = this.sortedRows();
        const renderAction = this.props.renderAction;
        return (
            <table id="runnersTable" className="table is-hoverable is-fullwidth">
                <thead>
                <tr>
                    <th>Backup ID {this.renderSortButton()}</th>
                    <th>Date</th>
                    <th>Status</th>
                    <th>Components</th>
                    <th>Missing</th>
                    {renderAction && <th>Action</th>}
                </tr>
                </thead>
                <tbody>
                {rows ? rows.map((row) =>
                    <tr key={row.backupId}>
                        <td>{row.backupId}</td>
                        <td>{row.backupTime}</td>
                        <td>{this.renderStatusTag(row)}</td>
                        <td>{this.renderComponents(row)}</td>
                        <td>{this.renderMissing(row)}</td>
                        {renderAction && <td>{renderAction(row)}</td>}
                    </tr>
                ) : null}
                </tbody>
            </table>
        );
    }

    renderStatusTag(row) {
        const status = row.backupStatus;
        if (status === "PARTIALBACKUP" || row.complete === false) {
            return <Tag type="red">Partial</Tag>;
        }
        if (status === "COMPLETED" || row.complete === true) {
            return <Tag type="green">Complete</Tag>;
        }
        if (status === "INPROGRESS") {
            return <Tag type="blue">In progress</Tag>;
        }
        if (status === "FAILED") {
            return <Tag type="red">Failed</Tag>;
        }
        return <Tag type="gray">{status || "Unknown"}</Tag>;
    }

    renderComponents(row) {
        const components = row.presentComponents && row.presentComponents.length > 0
            ? row.presentComponents
            : row.components;
        return components && components.map((component, index) =>
            <Tag key={index} type="blue">{component}</Tag>);
    }

    renderMissing(row) {
        if (row.missingComponents && row.missingComponents.length > 0) {
            return row.missingComponents.map((component, index) =>
                <Tag key={"m" + index} type="red">missing {component}</Tag>);
        }
        return <span className="text-muted">None</span>;
    }
}

export default BackupListTable;
