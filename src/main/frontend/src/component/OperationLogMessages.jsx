import React from "react";

/**
 * Class of the alert according to the type of the message
 */
function alertClass(type) {
    switch (type) {
        case "ERROR":
            return "alert-danger";
        case "WARNING":
            return "alert-warning";
        default:
            return "alert-info";
    }
}

/**
 * The lines of a message: the list "content" of the API (one line each), else the single "message" of the older API
 */
function messageLines(entry) {
    if (Array.isArray(entry.content)) {
        return entry.content;
    }
    return entry.message ? [entry.message] : [];
}

/**
 * Renders operation log entries: one box per message, in the order received. The color follows the type (INFO, WARNING, ERROR).
 * In a box:
 *  - on the top: the status (normal), the name of the step (bold), and on the right the advancement (step/total, for example 1/8)
 *  - second line, when the message has a documentationLink: a link to the documentation
 *  - next line: the date (italic)
 *  - then one line per content
 *
 * @param messages   the messages of the API
 * @param totalSteps the total number of steps of the operation (the field totalSteps of the API)
 */
export function renderOperationLogMessages(messages, totalSteps) {
    if (!messages || messages.length === 0) {
        return null;
    }

    return (
        <div>
            {messages.map((entry, index) => (
                <div key={index}
                     className={`alert ${alertClass(entry.type)}`}
                     role="alert">
                    <div className="d-flex justify-content-between">
                        <span>
                            {entry.type}
                            {entry.component ? ` (${entry.component})` : ""}
                            {entry.stepName ? <> <strong>{entry.stepName}</strong></> : null}
                        </span>
                        {totalSteps > 0 && entry.step != null ? <span>{entry.step}/{totalSteps}</span> : null}
                    </div>
                    {entry.documentationLink ? (
                        <div>
                            <a href={entry.documentationLink} target="_blank" rel="noreferrer">Documentation</a>
                        </div>
                    ) : null}
                    {entry.date ? <div><em>{new Date(entry.date).toLocaleString()}</em></div> : null}
                    {messageLines(entry).map((line, lineIndex) => (
                        <div key={lineIndex}>{line}</div>
                    ))}
                </div>
            ))}
        </div>
    );
}
