
# Camunda 8 Backup and Restore using MinIO (S3-Compatible)

This guide describes how to set up backup and restore functionality for Camunda 8.4 using [MinIO](https://min.io/) as an S3-compatible object store.

Camunda 8.4 does not support Azure Blob Storage directly for backup. As a workaround, MinIO can be used to provide S3-compatible storage. This document includes Helm-based setup and API instructions to perform backups and restores for:

- **Zeebe**
- **Operate**
- **Tasklist**
- **Optimize**
- **Elasticsearch Snapshots**

---

# 🛠️ Setup and Configuration

## 1. Deploy MinIO via Helm

Create a `minio-values.yaml`:

```yaml
auth:
  rootUser: minioadmin
  rootPassword: minioadmin

defaultBuckets: zeebe-backup,operate-backup,tasklist-backup,optimize-backup
```

Install using Helm:

```bash
helm install minio oci://registry-1.docker.io/bitnamicharts/minio -f minio-values.yaml
```

---

## 2. Configure Camunda Helm Chart (`camunda-values.yaml`)

### Operate, Tasklist, Optimize:

```yaml
operate:
  env:
    - name: CAMUNDA_OPERATE_BACKUP_REPOSITORY_NAME
      value: "operate-backup"

tasklist:
  env:
    - name: CAMUNDA_TASKLIST_BACKUP_REPOSITORY_NAME
      value: "tasklist-backup"

optimize:
  env:
    - name: CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME
      value: "optimize-backup"
```

### Zeebe:

```yaml
zeebe:
  env:
    - name: ZEEBE_BROKER_DATA_BACKUP_STORE
      value: "S3"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_BUCKETNAME
      value: "zeebe-backup"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_FORCEPATHSTYLEACCESS
      value: "true"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_ENDPOINT
      value: "http://minio:9000"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_ACCESSKEY
      value: "minioadmin"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_SECRETKEY
      value: "minioadmin"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_REGION
      value: "us-east-1"
```

### Elasticsearch Keystore:

```yaml
elasticsearch:
  initScripts:
    init-keystore.sh: |
      #!/bin/bash
      set -e
      echo "Adding S3 access keys to Elasticsearch keystore..."
      echo "$S3_SECRET_KEY" | elasticsearch-keystore add -f -x s3.client.default.secret_key
      echo "$S3_ACCESS_KEY" | elasticsearch-keystore add -f -x s3.client.default.access_key
  extraEnvVars:
    - name: S3_SECRET_KEY
      value: minioadmin
    - name: S3_ACCESS_KEY
      value: minioadmin
```

---

## 3. Upgrade Camunda

```bash
helm upgrade camunda camunda/camunda-platform -f camunda-values.yaml --version <your-version>
```

---

## 4. Register Elasticsearch Snapshot Repositories

Run for each bucket:

```bash
curl -X PUT "http://<elasticsearch-host>:9200/_snapshot/<bucket>" -H 'Content-Type: application/json' -d '{
  "type": "s3",
  "settings": {
    "bucket": "<bucket>",
    "endpoint": "<minio-endpoint>",
    "protocol": "http",
    "compress": true,
    "client": "default",
    "path_style_access": true
  }
}'
```

---

# 💾 Performing Backups

## 1. Operate, Tasklist, Optimize

Port-forward each service’s actuator port and call the backup endpoint:

```bash
POST /actuator/backups
{
  "backupId": "<backup-id>"
}
```

## 2. Zeebe Backup

Port-forward Zeebe gateway actuator and Pause the Zeebe Exporter:

```bash
curl -X POST "http://localhost:9600/actuator/exporting/pause?soft=true" -H 'Content-Type: application/json' -d '{}'
```

Then create snapshot for Zeebe:

```bash
curl -X PUT "http://<elasticsearch-host>:9200/_snapshot/zeebe-backup/<backup-id>" -H 'Content-Type: application/json' -d '{
  "indices": "zeebe-record*",
  "feature_states": ["none"]
}'
```
(Optional) Resume the Zeebe Exporter:

```bash
curl -X POST "http://camunda-zeebe-gateway:9600/actuator/exporting/resume" \
            -H 'Content-Type: application/json' \
            -d '{}' 
```
---

# 🔁 Restore Procedure

## 1. Scale Down All Components

```bash
kubectl scale sts/camunda-zeebe --replicas=0
kubectl scale deploy/camunda-zeebe-gateway --replicas=0
kubectl scale deploy/camunda-operate --replicas=0
kubectl scale deploy/camunda-tasklist --replicas=0
kubectl scale deploy/camunda-optimize --replicas=0
```

## 2. Re-register Snapshot Repositories

Re-run the registration curl commands for each bucket.
```bash
curl -X PUT "http://camunda-elasticsearch:9200/_snapshot/$bucket" -H 'Content-Type: application/json' -d '
            {
              "type": "s3",
              "settings": {
                "bucket": "'"$bucket"'",
                "endpoint": "'"$endpoint"'",
                "protocol": "http",
                "compress": true,
                "client": "default",
                "path_style_access": true
              }
            } 
```
---

## 3. Delete Elasticsearch Indices

```bash
curl -X DELETE "<elasticsearch-endpoint>/_cat/indices?h=index"
```

---

## 4. Restore Snapshots

```bash
curl -X POST "<elasticsearch-endpoint>/_snapshot/<repository>/<snapshot>/_restore?wait_for_completion=true"
```

---

## 5. Restore Zeebe

Shell into each Zeebe broker pod:

```bash
rm -rf /usr/local/zeebe/data/{*,.*}
```

Then run:

```bash
/usr/local/zeebe/bin/restore --backupId="<backup-id>"
```

---

## 6. Scale Up Components

```bash
kubectl scale sts/camunda-zeebe --replicas=<replica-count>
kubectl scale deploy/camunda-zeebe-gateway --replicas=<replica-count>
kubectl scale deploy/camunda-operate --replicas=<replica-count>
kubectl scale deploy/camunda-tasklist --replicas=<replica-count>
kubectl scale deploy/camunda-optimize --replicas=<replica-count>
```

---

# 📁 Reference Files

- [camunda-values.yaml](https://github.com/camunda-consulting/c8-devops-workshop/blob/main/03%20-%20Lab%203%20-%20Backup%20and%20Restore/camunda-values.yaml)
- [minio-values.yaml](https://github.com/camunda-consulting/c8-devops-workshop/blob/main/03%20-%20Lab%203%20-%20Backup%20and%20Restore/minio-values.yaml)
- [es-snapshot-minio-job.yaml](https://github.com/camunda-consulting/c8-devops-workshop/blob/main/03%20-%20Lab%203%20-%20Backup%20and%20Restore/es-snapshot-minio-job.yaml)
- [es-snapshot-restore-job.yaml](https://github.com/camunda-consulting/c8-devops-workshop/blob/main/03%20-%20Lab%203%20-%20Backup%20and%20Restore/restore/es-snapshot-restore-job.yaml)
- [es-delete-all-indices.yaml](https://github.com/camunda-consulting/c8-devops-workshop/blob/main/03%20-%20Lab%203%20-%20Backup%20and%20Restore/restore/es-delete-all-indices.yaml)
- [zeebe-restore-job-0.yaml](https://github.com/camunda-consulting/c8-devops-workshop/blob/main/03%20-%20Lab%203%20-%20Backup%20and%20Restore/restore/zeebe-restore-job-0.yaml)
