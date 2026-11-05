# Restore

This procedure explains the restoration procedure for Zeebe.

https://docs.camunda.io/docs/8.5/self-managed/operational-guides/backup-restore/restore/

The method to restore Operate, Tasklist and Optimize are based on restoring Elastic Search.

This documentation focuses on how to restore Zeebe.



# Configure restore via an init container

> This configuration is needed for a version under 8.6. on 8.6 and upper, the helm chart already contains this initcontainer  
 
**If you are on 8.6 or up, skip this part**


According to the documentation https://docs.camunda.io/docs/8.5/self-managed/operational-guides/backup-restore/restore/, prepare the following init container

Note that the init container purges the path `/usr/local/zeebe/data` before restoring.

The restore needs to find
* the ZEEBE_BROKER_CLUSTER_NODEID. It is calculated from the pod name in the Statefulset
* the cluster size: PARTITIONSCOUNT, CLUSTERSIZE, REPLICATIONFACTOR
* the information to access the storage.



```yaml
zeebe:
  initContainers:
    - name: zeebe-restore
      image: camunda/zeebe
      command: ["/bin/bash"]
      args:
        - -c
        - |
          if [ ${ZEEBE_RESTORE} = "true" ];
          then
            env;
            export ZEEBE_BROKER_CLUSTER_NODEID=${ZEEBE_BROKER_CLUSTER_NODEID:-${K8S_NAME##*-}};
            echo ClusterId is $ZEEBE_BROKER_CLUSTER_NODEID;
            rm -rf /usr/local/zeebe/data/*
            rm -rf /usr/local/zeebe/data/lost+found;
            echo "After cleanup"          
            exec /usr/local/zeebe/bin/restore --backupId=$BACKUP_ID;
          fi
      env:
        - name: K8S_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: ZEEBE_BROKER_CLUSTER_PARTITIONSCOUNT
          value: "3"
        - name: ZEEBE_BROKER_CLUSTER_CLUSTERSIZE
          value: "3"
        - name: ZEEBE_BROKER_CLUSTER_REPLICATIONFACTOR
          value: "3"
          
        - name: ZEEBE_BROKER_DATA_BACKUP_STORE
          value: "..."
        - name: BACKUP_ID
          value: "$BACKUP_ID" # Change the $BACKUP_ID to your actual value
        - name: ZEEBE_RESTORE
          value: "false"
      volumeMounts:
        - name: data
          mountPath: /usr/local/zeebe/data

```
.
To run a restore, change the value.yaml to turn `ZEEBE_RESTORE` to true and run a helm ugrade.
Do not forget to turn off `ZEEBE_RESTORE` to false.


# Restore



## 1. Get the configuration

> Attention: what is important is the values when the backup was created. Assuming values does not change.

1.1 Run a get pods to inspect the current situation

```shell
kubectl get pods

NAME                                    READY   STATUS    RESTARTS   AGE
camunda-elasticsearch-master-0          1/1     Running   0          3m19s
camunda-elasticsearch-master-1          1/1     Running   0          3m19s
camunda-elasticsearch-master-2          1/1     Running   0          3m19s
camunda-operate-76bb6d896c-2zm7b        1/1     Running   0          3m20s
camunda-optimize-7f4cd5f9b6-vf7ms       1/1     Running   0          3m20s
camunda-tasklist-59474455b4-2fzfl       1/1     Running   0          3m20s
camunda-zeebe-0                         1/1     Running   0          3m19s
camunda-zeebe-1                         1/1     Running   0          3m19s
camunda-zeebe-2                         1/1     Running   0          3m19s
camunda-zeebe-gateway-d7c97f64c-wkdfq   1/1     Running   0          3m20s
```

And check how many instances are on each component.

| Component    | Number of instances |
|--------------|--------------------:| 
| zeebe        |                   3 |
| zeebeGateway |                   1 |
| Operate      |                   1 |
| TaskList     |                   1 |


1.2 Check if all repositories exist in Elasticsearch. This configuration must be executed if you start from an empty database.

```shell
curl -X GET "http://localhost:9200/_snapshot/_all?pretty"
```

### 2. scale down components
The idea is to stop all components but keep ElasticSearch.

2.1 Execute these commands:

You can scale down the components via these commands

```shell
kubectl scale sts/camunda-zeebe --replicas=0
kubectl scale deploy/camunda-zeebe-gateway --replicas=0
kubectl scale deploy/camunda-operate --replicas=0
kubectl scale deploy/camunda-tasklist --replicas=0
kubectl scale deploy/camunda-optimize --replicas=0
```

Or in the Helm chart, disable each component

```yaml
zeebe:
  enabled: false
operate:
  enabled: false
tasklist:
  enabled: false
optimize:
  enabled: false


```

2.2 Only Elasticsearch is up and running now.

```shell
kubectl get pods

NAME                                    READY   STATUS    RESTARTS   AGE
camunda-elasticsearch-master-0          1/1     Running   0          3m19s
camunda-elasticsearch-master-1          1/1     Running   0          3m19s
camunda-elasticsearch-master-2          1/1     Running   0          3m19s
```


## 3. Delete all indexes in Elastic search
When Operate, Tasklist, and Optimize start, they create indexes. It must be purged.

In the k8 folder, an `es-delete-all-indices.yaml` file is present. This Kubernetes file creates a pod that executes this deletion.

The script is present under `doc/restore/k8`, so if needed, do a `cd` in that folder.

3.1 Purge

```shell
kubectl apply -f k8/es-delete-all-indices.yaml
```
When the deletion is performed, the pod will stop and move to the state "Completed"

3.2 Wait for the status:

```shell
Kubernetes get pods
es-delete-all-indices-job-bs2h9   0/1     Completed   0          18s
```

3.3 Delete the deletion pod

```shell
kubectl delete -f es-delete-all-indices.yaml
```

3.4 Run the command to verify that all indexes are deleted

```shell
curl -X GET "http://localhost:9200/_cat/indices?v"
health status index uuid pri rep docs.count docs.deleted store.size pri.store.size dataset.size
```
The list must be empty.

## 4. Restore Elasticsearch backup

4.1 Create the secret with the correct ID

```shell
kubectl create secret generic backup-timeid --from-literal=backupTimeId=$BACKUP_ID 
``` 

Check the es-snapshoot-restore.yaml file
```yaml
        - name: BACKUP_TIME_ID
            valueFrom:
              secretKeyRef:
                name: backup-timeid
                key: backupTimeId

```

4.2 Run the pod; the script is present in `doc/restore/k8`

```shell
kubectl apply -f es-snapshot-restore.yaml
```

4.3 Monitor the execution. At the end, the pod status changed to `Completed`.

```shell
kubernetes get pods
es-snapshot-restore-job-pdvzz   0/1     Completed   0          18s
```

4.4 Remove the restore pod

```shell
kubectl delete -f es-snapshot-restore.yaml
```

4.5 Check indexes are restored 

```shell
curl -X GET "http://localhost:9200/_cat/indices?v"
```
The result is
````
health status index                                     uuid                   pri rep docs.count docs.deleted store.size pri.store.size dataset.size
green  open   operate-message-8.5.0_                    kGn9TkwRRqGlRaoW1anvaQ   1   0          0            0       249b           249b         249b
green  open   operate-batch-operation-1.0.0_            QimDEIwKSwmUYqD8Bc1KRw   1   0          0            0       249b           249b         249b
green  open   operate-web-session-1.1.0_                CWDoCuBiTsO9i5xQwuduxg   1   0          0            0       249b           249b         249b
green  open   operate-flownode-instance-8.3.1_          gVKhEQLZR0WiXGfvQ5kIOw   1   0         12            0     23.6kb         23.6kb       23.6kb
green  open   operate-decision-8.3.0_                   VfEt4Nj5RnKSUtT08V-TFg   1   0          0            0       249b           249b         249b
green  open   operate-user-task-8.5.0_                  U_nEvmNSQauEmp915agkOQ   1   0          0            0       249b           249b         249b
green  open   operate-event-8.3.0_                      yZGw2XLERUSrw0wUsmof3Q   1   0         12            5     33.6kb         33.6kb       33.6kb
green  open   operate-job-8.6.0_                        A_OE0XWVQBKVHF2DjJtqXw   1   0          6            0     22.9kb         22.9kb       22.9kb
green  open   operate-variable-8.3.0_                   4xWHq0ZuQpS4Vi4atJ53jQ   1   0         28            0       23kb           23kb         23kb
green  open   operate-post-importer-queue-8.3.0_        1xWpxnqeQ16_Bx7SxK4H7w   1   0          0            0       249b           249b         249b
green  open   operate-list-view-8.3.0_                  F96hokUKTbG6NOzpWVsYtg   1   0         46            5    124.2kb        124.2kb      124.2kb
green  open   operate-decision-instance-8.3.0_          y4dEs61MSGqcPT8RKbMiOQ   1   0          0            0       249b           249b         249b
green  open   operate-migration-steps-repository-1.1.0_ fpLS4XtpQvuF9K2CmEPQYA   1   0         27            1     29.5kb         29.5kb       29.5kb
green  open   operate-user-1.2.0_                       YCSNPijSS5OQVhHTjaNKRQ   1   0          3            0      6.7kb          6.7kb        6.7kb
green  open   operate-sequence-flow-8.3.0_              WGxc-bbrQ8G-troFT0jEwA   1   0          6            0     13.6kb         13.6kb       13.6kb
green  open   operate-metric-8.3.0_                     G2oNlA6MQoK2DiZRZbhsyQ   1   0          6            0     11.7kb         11.7kb       11.7kb
green  open   operate-operation-8.4.1_                  AXbJWKmSTey0yfzHl_AanA   1   0          0            0       249b           249b         249b
green  open   operate-import-position-8.3.0_            9FgKNWwcQ6CleKdCQcSIeg   1   0         12            7     17.5kb         17.5kb       17.5kb
green  open   operate-incident-8.3.1_                   omR5v_KXRkGWagXYBKYkdw   1   0          0            0       249b           249b         249b
green  open   operate-decision-requirements-8.3.0_      21FcaK25TJuzpHrqk5aoNg   1   0          0            0       249b           249b         249b
green  open   operate-process-8.3.0_                    8hFENmo0TOCbtHWUweN-AA   1   0          2            2    181.2kb        181.2kb      181.2kb
````


> Note: in the yaml file, the list of repositories is given
> ```
> # Define the list of repository names
> repositories="operaterepository optimizebackup tasklistrepository zeeberecordrepository"
> ```
> This list may be adapted according to the repositories created in Elasticsearch. The current values are those created in the backup procedure.

## 5. BackupID

The value.yaml must be updated with the backupId

1.1 Clean the PVC

If you **don't** used the initcontainer, you don't need to clean the PVC

Check the official documentation:
https://docs.camunda.io/docs/8.5/self-managed/operational-guides/backup-restore/restore/#restore-zeebe-cluster



1.2 Update the backupID in the value.yaml

Update ZEEBE_RESTORE to true, give the backup id and then enabled zeebe to true

````
zeebe:
   enabled: true
   env:
   # Environment variables to overwrite the Zeebe startup behavior
   - name: ZEEBE_RESTORE
     value: "true"
   - name: ZEEBE_RESTORE_FROM_BACKUP_ID
     value: "$BACKUP_ID" # Change the $BACKUP_ID to your actual value             
````

> If you are under 8.6 and defined a initcontainer, then update the backupID in the initcontainer too

1.3 Execute the change

Run helm upgrade to update the value

The command may change according to the way you created the cluster


## 6. Restore Zeebe 

6.1 Enable Zeebe. The initContainer will run the restoration in each pod.

````
kubectl scale sts/camunda-zeebe --replicas=<ClusterSize>
````

## 7. Disable ZEEBE_RESULT

8.1 update the value.yaml

````
zeebe:
   enabled: true
   env:
   # Environment variables to overwrite the Zeebe startup behavior
   - name: ZEEBE_RESTORE
     value: "false"
````

8.2 Restart the system


## 8. Scale back the application

8.1 Uses the value saved during the exploration to restart all components with the correct value.

```shell
kubectl scale deploy/camunda-zeebe-gateway --replicas=1
kubectl scale deploy/camunda-operate --replicas=1
kubectl scale deploy/camunda-tasklist --replicas=1
kubectl scale deploy/camunda-optimize --replicas=1
```

Check the system.

# Check the restoration

See [CheckRestoration.md](CheckRestoration.md)

