// -----------------------------------------------------------
//
// BackupTimeline
//
// X: one column per day, from the day of the oldest backup to the day of the newest one.
// Y: number of backups in the day. Each backup is one box (stacked), labelled with its ID.
//
// Props: timeline = [{backupId, status, backupTime}, ...]
// -----------------------------------------------------------

import React from 'react';
import {BarElement, CategoryScale, Chart as ChartJS, LinearScale, Tooltip} from 'chart.js';
import {Bar} from 'react-chartjs-2';

ChartJS.register(CategoryScale, LinearScale, BarElement, Tooltip);

const COLORS = {
    COMPLETED: {bg: 'rgba(36, 161, 72, 0.5)', border: 'rgba(36, 161, 72, 1)'},
    PARTIALBACKUP: {bg: 'rgba(218, 30, 218, 0.45)', border: 'rgba(218, 30, 218, 1)'},
    IN_PROGRESS: {bg: 'rgba(15, 98, 254, 0.45)', border: 'rgba(15, 98, 254, 1)'},
    FAILED: {bg: 'rgba(218, 30, 30, 0.45)', border: 'rgba(218, 30, 30, 1)'},
    UNKNOWN: {bg: 'rgba(141, 141, 141, 0.45)', border: 'rgba(141, 141, 141, 1)'},
};

const PIXELS_PER_DAY = 70;
const MAX_WIDTH = 30000; // a canvas cannot be larger than that
const CHART_HEIGHT = 300;

/** Write the ID of the backup in the box */
const backupIdLabelPlugin = {
    id: 'backupIdLabel',
    afterDatasetsDraw(chart) {
        const ctx = chart.ctx;
        chart.data.datasets.forEach((dataset, datasetIndex) => {
            chart.getDatasetMeta(datasetIndex).data.forEach((bar, index) => {
                const backup = dataset.backups[index];
                if (!backup) {
                    return;
                }
                ctx.save();
                ctx.fillStyle = '#161616';
                ctx.font = 'bold 11px sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText('ID ' + backup.backupId, bar.x, (bar.y + bar.base) / 2);
                ctx.restore();
            });
        });
    }
};

function dayKey(date) {
    return date.getFullYear() + '-' + (date.getMonth() + 1) + '-' + date.getDate();
}

class BackupTimeline extends React.Component {

    /**
     * Group the backups per day, from the day of the oldest to the day of the newest (days without backup included).
     * Returns {days: [{key, label: [weekday, "4 Nov"], backups: [...]}], maxPerDay, withoutDate}
     */
    buildDays() {
        const timeline = this.props.timeline || [];
        const dated = [];
        let withoutDate = 0;
        timeline.forEach((backup) => {
            const time = backup.backupTime ? new Date(backup.backupTime) : null;
            if (time && !isNaN(time.getTime())) {
                dated.push({...backup, time: time});
            } else {
                withoutDate++;
            }
        });
        if (dated.length === 0) {
            return {days: [], maxPerDay: 0, withoutDate: withoutDate};
        }

        const perDay = {};
        dated.forEach((backup) => {
            const key = dayKey(backup.time);
            perDay[key] = perDay[key] || [];
            perDay[key].push(backup);
        });
        Object.values(perDay).forEach((list) => list.sort((a, b) => a.backupId - b.backupId));

        let first = new Date(Math.min(...dated.map((b) => b.time.getTime())));
        const last = new Date(Math.max(...dated.map((b) => b.time.getTime())));
        let current = new Date(first.getFullYear(), first.getMonth(), first.getDate());
        const days = [];
        let maxPerDay = 0;
        while (current <= last) {
            const key = dayKey(current);
            const backups = perDay[key] || [];
            maxPerDay = Math.max(maxPerDay, backups.length);
            days.push({
                key: key,
                label: [
                    current.toLocaleDateString('en-GB', {weekday: 'short'}),
                    current.toLocaleDateString('en-GB', {day: 'numeric', month: 'short'})
                ],
                backups: backups
            });
            current = new Date(current.getFullYear(), current.getMonth(), current.getDate() + 1);
        }
        return {days: days, maxPerDay: maxPerDay, withoutDate: withoutDate};
    }

    render() {
        const {days, maxPerDay, withoutDate} = this.buildDays();
        if (days.length === 0) {
            return <p className="text-muted">
                {withoutDate > 0
                    ? withoutDate + " backup(s) without date: no time line."
                    : "No backup to display on the time line."}
            </p>;
        }

        // The dataset i holds the i-th backup of each day: stacked, it gives one box per backup
        const datasets = [];
        for (let i = 0; i < maxPerDay; i++) {
            const backups = days.map((day) => day.backups[i] || null);
            datasets.push({
                data: backups.map((backup) => backup ? 1 : 0),
                backups: backups,
                backgroundColor: backups.map((backup) => (COLORS[backup?.status] || COLORS.UNKNOWN).bg),
                borderColor: backups.map((backup) => (COLORS[backup?.status] || COLORS.UNKNOWN).border),
                borderWidth: 1,
                barPercentage: 0.8,
                categoryPercentage: 0.9
            });
        }
        const data = {labels: days.map((day) => day.label), datasets: datasets};

        const options = {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {display: false},
                tooltip: {
                    filter: (item) => !!item.dataset.backups[item.dataIndex],
                    callbacks: {
                        title: (items) => items.length ? items[0].label.toString().replace(',', ' ') : '',
                        label: (item) => {
                            const backup = item.dataset.backups[item.dataIndex];
                            if (!backup) {
                                return '';
                            }
                            const time = backup.time.toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'});
                            return 'ID ' + backup.backupId + ' - ' + backup.status + ' - ' + time;
                        }
                    }
                }
            },
            scales: {
                x: {stacked: true, grid: {display: false}},
                y: {
                    stacked: true,
                    beginAtZero: true,
                    ticks: {stepSize: 1, precision: 0},
                    title: {display: true, text: 'Backups per day'},
                    suggestedMax: Math.max(2, maxPerDay)
                }
            }
        };

        const width = Math.min(MAX_WIDTH, days.length * PIXELS_PER_DAY);
        return (
            <div>
                <div style={{overflowX: "auto"}}>
                    <div style={{width: "max(100%, " + width + "px)", height: CHART_HEIGHT + "px"}}>
                        <Bar data={data} options={options} plugins={[backupIdLabelPlugin]}/>
                    </div>
                </div>
                {withoutDate > 0 &&
                    <p className="text-muted" style={{fontSize: "0.85rem"}}>
                        {withoutDate} backup(s) without date are not on the time line.</p>}
            </div>
        );
    }
}

export default BackupTimeline;
