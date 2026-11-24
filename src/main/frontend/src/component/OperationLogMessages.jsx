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
 * Renders operation log entries: one alert per message, in the order received (INFO, WARNING, ERROR...).
 */
export function renderOperationLogMessages(messages) {
    if (!messages || messages.length === 0) {
        return null;
    }

    return (
        <div>
            {messages.map((entry, index) => (
                <div key={index}
                     className={`alert ${alertClass(entry.type)}`}
                     role="alert">
                    <strong>{entry.type}</strong>
                    {entry.component ? ` — (${entry.component})` : ""}
                    {entry.date ? ` – ${new Date(entry.date).toLocaleString()}` : ""}
                    {messageLines(entry).map((line, lineIndex) => (
                        <React.Fragment key={lineIndex}>
                            <br/>
                            {line}
                        </React.Fragment>
                    ))}
                </div>
            ))}
        </div>
    );
}
