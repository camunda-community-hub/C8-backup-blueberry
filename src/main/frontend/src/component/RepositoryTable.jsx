// -----------------------------------------------------------
//
// RepositoryTable
//
// The Elasticsearch repositories of the components, in a simple table:
// * name: the component
// * configuration: the repository in the blueberry configuration. This is the one used by the backup and by the restore
// * cluster: the repository read in the cluster (actuator /env of the component). When it can't be read,
//   the reason is displayed (env not exposed, values hidden...)
//
// Shared by the Backup page, the Restore page and the Parameters page.
//
// -----------------------------------------------------------

import React from 'react';
import RestCallService from "../services/RestCallService";

class RepositoryTable extends React.Component {

    constructor(_props) {
        super();
        this.state = {repositories: []};
        this.refresh = this.refresh.bind(this);
    }

    componentDidMount() {
        this.refresh();
        this.interval = setInterval(this.refresh, 30000);
    }

    componentWillUnmount() {
        clearInterval(this.interval);
    }

    refresh() {
        RestCallService.getInstance().getJson('blueberry/api/configuration/repositories?', this, (httpPayload) => {
            if (!httpPayload.isError()) {
                this.setState({repositories: httpPayload.getData()});
            }
        });
    }

    render() {
        return (
            <table className="table is-hoverable" style={{width: "auto"}}>
                <thead>
                <tr>
                    <th>Repository</th>
                    <th>Configuration</th>
                    <th>Cluster</th>
                </tr>
                </thead>
                <tbody>
                {this.state.repositories.map((repository) =>
                    <tr key={repository.name}>
                        <td>{repository.name}</td>
                        <td>{repository.configuration}</td>
                        <td>{this.renderCluster(repository)}</td>
                    </tr>
                )}
                </tbody>
            </table>
        );
    }

    /**
     * The repository name read in the cluster. When it can't be read, the reason is displayed
     */
    renderCluster(repository) {
        if (repository.name === "zeeberecord") {
            // Created by blueberry: no component declares it
            return <span className="text-muted">-</span>;
        }
        if (!repository.cluster) {
            return <span className="text-muted">{repository.comment || "env not exposed"}</span>;
        }
        if (repository.cluster !== repository.configuration) {
            // Only a color: the name read in the cluster is the information
            return <span className="text-danger" title="Different from the configuration">{repository.cluster}</span>;
        }
        return <span>{repository.cluster}</span>;
    }
}

export default RepositoryTable;
