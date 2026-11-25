package io.camunda.blueberry.api;

import io.camunda.blueberry.operation.backup.BackupJob;
import io.camunda.blueberry.operation.restoration.RestoreJob;
import io.camunda.blueberry.platform.version.BlueberryVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("blueberry")
public class VersionRestController {

    private final BlueberryVersion blueberryVersion;

    public VersionRestController(BlueberryVersion blueberryVersion) {
        this.blueberryVersion = blueberryVersion;
    }

    /**
     * The version of Blueberry (pom.xml), displayed by the user interface, and the documentation of the backup and the restore
     */
    @GetMapping(value = "/api/version", produces = "application/json")
    public Map<String, Object> getVersion() {
        Map<String, Object> result = new HashMap<>();
        result.put("blueberryVersion", blueberryVersion.get());
        result.put("docsBackupUrl", BackupJob.BACKUP_DOC);
        result.put("docsRestoreUrl", RestoreJob.RESTORE_DOC);
        return result;
    }
}
