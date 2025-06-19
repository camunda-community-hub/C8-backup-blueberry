# Connection via the Account Name / key


IN PROGRESS TO ADAPT THIS DOCUMENTATION TO S3


# 1. Zeebe

## Configuration

1.1 In the Helm chart , in the Zeebe section, add this information
```yaml
zeebe:
  env:
    - name: ZEEBE_BROKER_DATA_BACKUP_STORE
      value: "AZURE"
    - name: ZEEBE_BROKER_DATA_BACKUP_AZURE_CONNECTIONSTRING
      value: ”DefaultEndpointsProtocol=.........”
    - name: ZEEBE_BROKER_DATA_BACKUP_AZURE_BASEPATH
      value: zeebecontainer
````      

> Note: the access key is created in the [Azure Prerequisite.md](S3Prerequisite)  document

 
The azure `basepath` is the container name. Zeebe will create snapshots on that repository. It is not possible to give a path to the value, and the data will be stored at the root level.

1.2 Restart the cluster

Run the command
```shell
helm upgrade --namespace camunda camunda camunda/camunda-platform -f <MyValue.yaml> 
```


## Test

Run a backup on Zeebe directly. See the documentation
https://docs.camunda.io/docs/self-managed/zeebe-deployment/operations/management-api/

1.3 Port-forward the server
```shell
kubectl port-forward svc/camunda-zeebe-gateway 9600:9600 -n camunda
```
1.4 Pause the exporting

```shell
curl -X POST "http://localhost:9600/actuator/exporting/pause"   -H 'Content-Type: application/json'    -d '{}'
```

1.5 Execute a backup

```shell
curl -X POST "http://localhost:9600/actuator/backups"  -H 'Content-Type: application/json'  -d "{\"backupId\": \"8\"}"
```
1.6 Monitor the backup
```shell
curl -s "http://localhost:9600/actuator/backups/8"
```

1.7 Resume Zeebe

```shell
curl -X POST "http://localhost:9600/actuator/exporting/resume"  -H 'Content-Type: application/json'    -d '{}'
```


1.8 Check the container

Some files must be visible on the storage

![Container after Zeebe backup](images/ZeebeContainerContent.png)

Under folder 1, a folder 8 is visible (8 is the backup ID)

![Detail of the container after Zeebe backup](images/ZeebeContainerDetail.png)



# 2. Elasticsearch

2.1 create the configuration

The configuration is
```yaml
elasticsearch:
  initScripts:
    init-keystore.sh: |
      #!/bin/bash
      set -e
      echo "Adding Azure access keys to Elasticsearch keystore..."
      echo "$AZURE_ACCOUNT" | elasticsearch-keystore add -f -x azure.client.default.account
      echo "$AZURE_KEY" | elasticsearch-keystore add -f -x azure.client.default.key
  extraVolumeMounts:
    - name: empty-dir
      mountPath: /bitnami/elasticsearch
      subPath: app-volume-dir
  extraConfig:
    azure.client.default.endpoint: "${AZURE_END_POINT}"

  extraEnvVars:
    - name: AZURE_ACCOUNT
      value: pierreyvesstorageaccount
    - name: AZURE_KEY
      value: eFw.......==
    - name: AZURE_END_POINT
      value: core.windows.net
```      

By doing that, you connect Elastic search to Azure.

2.2 Reference a repository for **Operate**, **Tasklist**, and **Optimize**
```yaml
operate:
  enabled: true
  env:
    - name: CAMUNDA_OPERATE_BACKUP_REPOSITORY_NAME
      value: "operaterepository"
tasklist:
  enabled: true
  env:
    - name: CAMUNDA_TASKLIST_BACKUP_REPOSITORY_NAME
      value: "tasklistrepository"
optimize:
  enabled: true
  env:
    - name: CAMUNDA_OPTIMIZE_BACKUP_REPOSITORY_NAME 
      value: "optimizerepository"
```

2.3 Restart the cluster.

Run the command
```shell
helm upgrade --namespace camunda camunda camunda/camunda-platform -f <MyValue.yaml> 
```


2.4 Create Zeebe repository in Elasticsearch

Access Elasticsearch, via a `port-forward` for example

```shell
kubectl port-forward svc/camunda-elasticsearch 9200:9200 -n camunda
```

Create a repository `zeeberecordrepository` and register the Azure container. it's possible to register a `base_path`, to save the content in a subfolder on the container 

```shell
curl -X PUT "http://localhost:9200/_snapshot/zeeberecordrepository" -H "Content-Type: application/json" \
-d '{
  "type": "azure",
  "settings": {
    "container": "elasticsearchcontainer",
    "base_path": "zeeberecordbackup"
  }
}'
```


2.5 Create a repository `operaterepository`
```shell
curl -X PUT "http://localhost:9200/_snapshot/operaterepository" -H "Content-Type: application/json" \
-d '{
  "type": "azure",
  "settings": {
    "container": "elasticsearchcontainer",
    "base_path": "operatebackup"
  }
}'
```

2.6 Create a repository `tasklistrepository`

```shell
curl -X PUT "http://localhost:9200/_snapshot/tasklistrepository" -H "Content-Type: application/json" \
-d '{
  "type": "azure",
  "settings": {
    "container": "elasticsearchcontainer",
    "base_path": "tasklistbackup"
  }
}'
```

2.7 Create a repository `optimizerepository`

```shell
curl -X PUT "http://localhost:9200/_snapshot/optimizerepository" -H "Content-Type: application/json" \
-d '{
  "type": "azure",
  "settings": {
    "container": "elasticsearchcontainer",
    "base_path": "optimizebackup"
  }
}'
```

2.8 Check creation

Get all repositories, to verify the creation

```shell
curl -X GET "http://localhost:9200/_snapshot/_all?pretty"
```

##	Test the zeebe Record backup
2.9 Run a backup on Zeebe Record

```shell
curl -X PUT http://localhost:9200/_snapshot/zeeberecordrepository/backup_1 -H 'Content-Type: application/json'   \
-d '{ "indices": "zeebe-record*", "feature_states": ["none"]}'
```

An answer {“accepted”:true}, and a folder is created on the container

![Container after zeebe record backup](images/ElasticSearchZeebeRecordBackup.png)


** Restore**

2.10 Check the existence of all zeebe-record indexes

```shell
curl -X GET http://localhost:9200/_cat/indices/zeebe-record*?v
```

2.11 Delete all indices

```shell
curl -X DELETE http://localhost:9200/zeebe-record*?
```

> ***Note***: the deletion may need to delete index per index

2.12 Restore

```shell
curl -X POST http://localhost:9200/_snapshot/zeeberecordrepository/backup_1/_restore -H "Content-Type: application/json" -d '{ "indices": "*", "ignore_unavailable": true, "include_global_state": true }'
```

## Test the backup on Operate

This backup is run on Operate. Operate will contact Elasticsearch to run the backup.

2.13 Port forward the port number 9600 on operate

```shell
kubectl port-forward svc/camunda-operate 9600:9600 -n camunda
```

> **Note** on 8.5, the port to run the backup is 80, not 9600.
 

2.14	Backup Operate
 
Run the backup

```shell
curl -X POST http://localhost:9600/actuator/backups -H 'Content-Type: application/json' -d '{ "backupId": 6}'
```

2.15 Check the container

![Container after Operate backup.png](images/ElasticSearchOperateBackup.png)

Get all snapshot on the repository

```shell
curl -X GET "http://localhost:9200/_snapshot/operaterepository/_all?pretty"
```

