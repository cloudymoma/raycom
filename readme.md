## Apache Beam example

You can use this master branch as a skeleton java project

### Proposed streaming pipeline

#### IMPORTANT: in the code example, assume the pubsub message is csv text encoded in utf-8

pubsub -> dataflow -> gcs(avro), bq(table), gcs(csv, deadletter)
