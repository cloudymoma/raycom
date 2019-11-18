## Apache Beam example

You can use this master branch as a skeleton java project

### Proposed streaming pipeline

#### IMPORTANT: in the code example, assume the pubsub message is csv text encoded in utf-8

pubsub -> dataflow -> GCS(avro, csv for both data & deadleter) + BigQuery + BigTable

#### Current pipeline DAG
![](https://raw.githubusercontent.com/bindiego/raycom/streaming/pipeline_dag.png)
