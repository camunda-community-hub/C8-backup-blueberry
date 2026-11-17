// -----------------------------------------------------------
//
// BackupListTable
//
// The list of backups, shared by the Backup page and the Restore page.
// Columns: Backup ID, Date, Status, Components, Missing, and Action.
// The Action column is displayed only if the page gives a renderAction function (Restore page).
//
// -----------------------------------------------------------

import React from 'react';
import {Tag} from "carbon-components-react";

class BackupListTable extends React.Component {

    render() {
        const rows = this.props.rows;
        const renderAction = this.props.renderAction;
        return (
            <table id="runnersTable" className="table is-hoverable is-fullwidth">
                <thead>
                <tr>
                    <th>Backup ID</th>
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
