# backup

# Preparation
The preparation step must be followed. Check the [README.md](../../README.md)

# Backup
According to the documentation https://docs.camunda.io/docs/8.5/self-managed/operational-guides/backup-restore/backup-and-restore/, the serie of command to run are

In this example, the backup ID is `12`

## Optimize

1.1 Port forward the service
````shell
kubectl port-forward svc/camunda-optimize 8092:8092 -n camunda
````
1.2 Start the Optimize backup

````shell
curl -X POST "http://localhost:8092/actuator/backups" \
-H 'Content-Type: application/json' \
-d '{ "backupId": 12 }'
````

1.3 Check the status:
````shell
curl -X GET "http://localhost:8092/actuator/backups/12"
````

Wait for an answer COMPLETE
```json
{"backupId":12,"failureReason":null,"state":"COMPLETED"}
```


## Operate

2.1 Port forward the service

````shell
kubectl port-forward svc/camunda-operate 9600:9600 -n camunda
````

2.2 Start the backup:

````shell
curl -X POST 'http://localhost:9600/actuator/backups' \
-H 'Content-Type: application/json' \
-d '{ "backupId": 12 }'
````

2.3 Check the status:

````shell
curl -X GET "http://localhost:9600/actuator/backups/12"
````

Wait for the status COMPLETED

```json
{"backupId":12,"state":"COMPLETED"}
```

# Tasklist

3.1 Port forward the service

````shell
kubectl port-forward svc/camunda-tasklist 9600:9600 -n camunda
````

3.2 Start the backup:
````shell
curl -X POST 'http://localhost:9600/actuator/backups' \
-H 'Content-Type: application/json' \
-d '{ "backupId": 12 }'
````


3.3 Check the status:
````shell
curl -X GET "http://localhost:9600/actuator/backups/12"
````

Wait for the status COMPLETED

```json
{"backupId":12,"state":"COMPLETED"}
```


# Zeebe

There are two operations in Zeebe: zeeberecord (on ElasticSearch) and Zeebe itself.

4.1 Port-forward the service:
```shell
kubectl port-forward svc/camunda-zeebe-gateway 9600:9600 -n camunda
kubectl port-forward svc/camunda-elasticsearch 9200:9200 -n camunda
```

4.2 Pause the exporting

```shell
curl -X POST "http://localhost:9600/actuator/exporting/pause"   -H 'Content-Type: application/json'    -d '{}'
```

4.3 Backup zeebe record

```shell
curl -X PUT http://localhost:9200/_snapshot/zeeberecordrepository/12 -H 'Content-Type: application/json'   \
-d '{ "indices": "zeebe-record*", "feature_states": ["none"]}'
```

4.4 Backup Zeebe 
```shell
curl -X POST "http://localhost:9600/actuator/backups"  -H 'Content-Type: application/json'  -d "{\"backupId\": \"12\"}"
```

4.5 Monitor the backup
```shell
curl -s "http://localhost:9600/actuator/backups/12"
```
wait for the status
```json
{"backupId":12,"state":"COMPLETED"}
```

4.6 Resume Zeebe

```shell
curl -X POST "http://localhost:9600/actuator/exporting/resume"  -H 'Content-Type: application/json'    -d '{}'
```

