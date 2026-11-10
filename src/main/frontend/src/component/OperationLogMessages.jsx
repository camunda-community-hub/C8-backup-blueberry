import React from "react";

/**
 * Renders operation log entries: one combined INFO alert, separate boxes per ERROR (and other types).
 */
export function renderOperationLogMessages(messages) {
    if (!messages || messages.length === 0) {
        return null;
    }

    const infoMessages = messages.filter((m) => m.type === "INFO");
    const otherMessages = messages.filter((m) => m.type !== "INFO");
    const infoTimestamp = infoMessages.length > 0 ? infoMessages[infoMessages.length - 1].date : null;

    return (
        <div>
            {infoMessages.length > 0 && (
                <div className="alert alert-info" role="alert">
                    <strong>INFO</strong>
                    {infoTimestamp ? ` – ${new Date(infoTimestamp).toLocaleString()}` : ""}
                    <br/>
                    {infoMessages.map((entry, index) => (
                        <React.Fragment key={index}>
                            {index > 0 && <br/>}
                            {entry.message}
                        </React.Fragment>
                    ))}
                </div>
            )}
            {otherMessages.map((entry, index) => (
                <div key={`other-${index}`}
                     className={`alert ${entry.type === "ERROR" ? "alert-danger" : "alert-info"}`}
                     role="alert">
                    <strong>{entry.type}</strong>
                    {entry.component ? ` — (${entry.component})` : ""}
                    {entry.date ? ` – ${new Date(entry.date).toLocaleString()}` : ""}
                    <br/>
                    {entry.message}
                </div>
            ))}
        </div>
    );
}
