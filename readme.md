## Apache Beam Sample

[![Build Status](https://jenkins.bindiego.com/buildStatus/icon?job=raycom-streaming)](https://jenkins.bindiego.com/job/raycom-streaming/)

You can use this master branch as a skeleton java project

### Proposed streaming pipeline

#### IMPORTANT: in the sample code, assume the pubsub message is csv text encoded in utf-8

pubsub/kafka -> dataflow/flink -> GCS(avro, csv for both data & deadleter) + BigQuery + HBase/Bigtable (realtime analysis)

#### Current pipeline DAG
![](https://raw.githubusercontent.com/bindiego/raycom/streaming/miscs/pipeline_dag.png)

#### Quick start

##### Prerequisits

Java dev environment
- JDK8+
- Maven

##### Dimension table in MySQL

You could use [this](https://github.com/bindiego/raycom/blob/streaming/scripts/dim1.sql) script to init the MySQL if you use [gcpplayground](https://github.com/bindiego/gcpplayground) to generate your messages. Also, you could simply use [this init script](https://github.com/bindiego/local_services/tree/develop/mysql) to run a MySQL instance in [Docker](https://github.com/bindiego/local_services/tree/develop/docker). 

##### Bigtable init

You could use `make` to initialize the Bigtable enviroment. Adjust the parameters in `makefile` accordingly, e.g. cluster name, region etc.

Create Bigtable cluster, run it once

`make btcluster`

Setup Bigtable tables, both tall and wide

`make btinit`

This branch is focusing on streaming, so the sample subscribes messages from Pubsub. It's easy to switch to KafkaIO in beam. But the quickest way to produce some dummy data then send to Pubsub for fun is by using [this](https://github.com/bindiego/gcpplayground) project.

If you use the [GCP Play Ground](https://github.com/bindiego/gcpplayground) to produce the pubsub message, there isn't much to do. Simply update the `run` shell script, make sure you have the corresponding permissions to manipulate the GCP resources. Then

```
make df
```

##### Caveats

The purpose of this project is only to show you how to quickly run a streaming pipeline in Dataflow and the concepts about windowing, triggers & watermark. Even though the running cluster is elastic, you'd better break this big DAG into smaller pipelines and use Pubsub(or Kafka) as a 'communication bus' for better computing resources utilization and easy/faster recovery. Also, there are ways you could improve the performance, i.e. csv data handling etc. It's not the purpose of this example.

#### FAQ
1. Do I need to setup the BigQuery table in advance?

A: No. The application will create for you, and append to existing table by default.

2. How to control the permissions?

A: This project is currently relying on the service account specified by the `GOOGLE_APPLICATION_CREDENTIALS` environment variable. Consult [here](https://cloud.google.com/docs/authentication/getting-started) for details.

3. More details for triggers?

A: Hope [this](https://gist.github.com/bindiego/3814cfbd3b8d47216fe74686b0ae4339) example explained triggers well.
