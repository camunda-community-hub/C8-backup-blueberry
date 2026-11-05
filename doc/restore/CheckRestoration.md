# Check restoration

# Principle
The restoration set back multiple data source:
* Zeebe PVC. Each broker host its own PVC
* Zeebe record (until 8.7)
* Operate data
* Tasklist data
* Optimize

Connecting to Operate is a easy move: check that process definition and process instance are presents. But how to verify that Zeebe contains data? Zeebe data is consistent with exporter and Operate/Tasklist/Optimize?

The method consist of monitoring the position. Each partition maintains a position.

When the cluster is quiet, then the current position for a partition in Zeebe must be egal to the position in zeebe Record, then in Operate.
On a running system, Zeebe can be in advance on zeebe record, which can be in advance on Operate.

For example

| Component    |   Position (partition 1) |
|--------------|-------------------------:| 
| Zeebe        |                     1430 |
| Zeebe record |                     1354 |
| Operate      |                     1265 | 

But after a delay, Zeebe record must pass zeebe, and operate must pass zeeberecord. So after 10 seconds, the situation must be something like

| Component    | Position (partition 1) | Comments                      |
|--------------|-----------------------:|-------------------------------| 
| Zeebe        |                   1830 | New position, Zeebe advance   | 
| Zeebe record |                   1754 | Over zeebe position at time=0 |
| Operate      |                   1665 | Over Zeebe record, at time=0  | 


# Zeebe


## Number of brokers

Collect the number of brokers

```shell
kubectl port-forward svc/camunda-zeebe-gateway 8080:8080 -n camunda
curl http://localhost:8080/v2/topology
```
Result is 
```json
{
  "brokers": [
    {
      "nodeId": 0,
      "host": "camunda-zeebe-0.camunda-zeebe",
      "port": 26501,
      "partitions": [
        {
          "partitionId": 1,
          "role": "leader",
          "health": "healthy"
        },
        {
          "partitionId": 3,
          "role": "follower",
          "health": "healthy"
        },
        {
          "partitionId": 2,
          "role": "follower",
          "health": "healthy"
        }
      ],
      "version": "8.7.10"
    },
    {
      "nodeId": 2,
      "host": "camunda-zeebe-2.camunda-zeebe",
      "port": 26501,
      "partitions": [
        {
          "partitionId": 1,
          "role": "follower",
          "health": "healthy"
        },
        {
          "partitionId": 3,
          "role": "leader",
          "health": "healthy"
        },
        {
          "partitionId": 2,
          "role": "follower",
          "health": "healthy"
        }
      ],
      "version": "8.7.10"
    },
    {
      "nodeId": 1,
      "host": "camunda-zeebe-1.camunda-zeebe",
      "port": 26501,
      "partitions": [
        {
          "partitionId": 1,
          "role": "follower",
          "health": "healthy"
        },
        {
          "partitionId": 3,
          "role": "follower",
          "health": "healthy"
        },
        {
          "partitionId": 2,
          "role": "leader",
          "health": "healthy"
        }
      ],
      "version": "8.7.10"
    }
  ],
  "clusterSize": 3,
  "partitionsCount": 3,
  "replicationFactor": 3,
  "gatewayVersion": "8.7.10"
}
```

ClusterSize 3 means there is 3 brokers, 0 to 2

## Stream position
To get the position at zeebe, use this REST APO

```shell
curl http://{zeebe-broker}:{monitoringApiPort}/actuator/partitions
```
The default port is 9600. Zeebe broker the zeebe service (not the zeebe gateway service) 


Example on a cluster 
```shell
kubectl port-forward pod/camunda-zeebe-0 9600:9600 -n camunda
curl http://localhost:9600/actuator/partitions
```

The answer is
```json
{
  "1": {
    "role": "LEADER",
    "processedPosition": 48,
    "snapshotId": "18-1-48-52-0",
    "processedPositionInSnapshot": 48,
    "streamProcessorPhase": "PROCESSING",
    "exporterPhase": "EXPORTING",
    "exportedPosition": 52,
    "clock": {
      "instant": "2025-08-26T23:42:37.486Z",
      "modificationType": "None",
      "modification": {

      }
    }
  },
  "2": {
    "role": "FOLLOWER",
    "processedPosition": 8,
    "snapshotId": "7-1-8-25-0",
    "processedPositionInSnapshot": 8,
    "streamProcessorPhase": "REPLAY",
    "exporterPhase": "EXPORTING",
    "exportedPosition": 25,
    "clock": {
      "instant": "2025-08-26T23:42:37.484Z",
      "modificationType": "None",
      "modification": {

      }
    }
  },
  "3": {
    "role": "FOLLOWER",
    "processedPosition": 6,
    "snapshotId": "5-1-6-7-0",
    "processedPositionInSnapshot": 6,
    "streamProcessorPhase": "REPLAY",
    "exporterPhase": "EXPORTING",
    "exportedPosition": 7,
    "clock": {
      "instant": "2025-08-26T23:42:37.486Z",
      "modificationType": "None",
      "modification": {

      }
    }
  }
}
```

Only positions on LEADER are interresting.
The same operation must be perform on each broker / pod to get the value for each partition. 

The position for each partition are:

| Partition | Processed Position | processedPositionInSnapshot | exportedPosition | 
|-----------|-------------------:|----------------------------:|-----------------:|
| 1         |                 48 |                          48 |               52 | 
| 2         |                  8 |                           4 |               25 |
| 3         |                  6 |                           6 |                7 | 

> the position on LEADER and FOLLOWER are interresting, but not accurate. Restoration restore only the LEADER node, which start immediatly to synchronize follower. So, the existance of the follower means the Zeebe cluster is healthy. Value may be late on follower due to the data to synchronize.

## Zeebe Record

Run in ElasticSearch the command:

```shell
kubectl port-forward svc/camunda-elasticsearch 9200:9200 -n camunda

curl -X GET "http://localhost:9200/zeebe-record*/_search?pretty&size=1"   -H 'Content-Type: application/json'   -d '{
    "query": {
      "term": {
        "partitionId": 1
      }
    },
    "sort": [
      { "sourceRecordPosition": { "order": "desc" } }
    ]
  }'
```
In the answer, search for the value of sourceRecordPosition
```
      "sourceRecordPosition" : 48,
```

| Partition | sourceRecordPosition | 
|-----------|---------------------:|
| 1         |                   48 | 
| 2         |                    8 |
| 3         |                    6 |

# Operate

```shell
curl -X GET "http://localhost:9200/operate-import-position*/_search?pretty&size=1"   -H 'Content-Type: application/json'   -d '{
    "query": {
      "term": {
        "partitionId": 1
      }
    },
    "sort": [
      { "sequence": { "order": "desc" } }
    ]
  }'
```