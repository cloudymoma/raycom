## Apache Beam Sample

[![Build Status](https://jenkins.bindiego.com/buildStatus/icon?job=raycom-streaming)](https://jenkins.bindiego.com/job/raycom-streaming/)

You can use this master branch as a skeleton java project

### Proposed streaming pipeline

#### IMPORTANT: in the sample code, assume the pubsub message is csv text encoded in utf-8

pubsub -> dataflow -> GCS(avro, csv for both data & deadleter) + BigQuery

#### Current pipeline DAG
![](https://raw.githubusercontent.com/bindiego/raycom/streaming/miscs/pipeline_dag.png)

#### Quick start

##### Prerequisits
Java dev environment
- JDK8+
- Maven

This branch is focusing on streaming, so the sample subscribes messages from Pubsub. It's easy to switch to KafkaIO in beam. But the quickest way to produce some dummy data then send to Pubsub for fun is by using [this](https://github.com/bindiego/gcpplayground) project.

If you use the [GCP Play Ground](https://github.com/bindiego/gcpplayground) to produce the pubsub message, there isn't much to do. Simply update the `run` shell script, make sure you have the corresponding permissions to manipulate the GCP resources. Then

```
./run df
```

#### FAQ
1. Do I need to setup the BigQuery table in advance?

A: No. The application will create for you, and append to existing table by default.

2. How to control the permissions?

A: This project is currently relying on the service account specified by the `GOOGLE_APPLICATION_CREDENTIALS` environment variable. Consult [here](https://cloud.google.com/docs/authentication/getting-started) for details.
