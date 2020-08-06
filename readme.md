## Google Cloud Load Balancer logs in Elasticsearch

[![Build Status](https://jenkins.bindiego.com/buildStatus/icon?job=raycom-gclb-log)](https://jenkins.bindiego.com/job/raycom-gclb-log/)

You can use this master branch as a skeleton java project

master分支可以用来当作一个骨架项目

beam代码理论上可以驱动spark，flink等等流式框架，详情参考[这里](https://beam.apache.org/documentation/runners/capability-matrix/)

### Proposed streaming pipeline

Stackdriver logging -> Pubsub -> Dataflow -> Elasticsearch

#### Current pipeline DAG
![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/pipeline_dag.png)

#### Quick start 快速开始

##### Prerequisits

Java dev environment
- JDK8+
- Maven

Elasticsearch
- Option 1: [Run your own](https://github.com/bindiego/local_services/tree/develop/elastic)
- Option 2: [Run on k8s / GKE](https://github.com/bindiego/local_services/tree/develop/k8s/gke/elastic), recommended :)
- Option 3: [Run on Elastic Cloud](https://cloud.elastic.co/)

##### Preparation

1. Setup GCP

You could simply run `cd scripts && ./gcp_setup.sh; cd -`, but before that, make sure the parameters on the top have been updated according to your environment, especially the `project` variable, others are really optional.

So this script will

- Create a Pubsub topic and a subscription, this subscription should be configured later for Dataflow job
- Setup a Stackdriver sink (Pubsub) for HTTP load balancers
- Grant permissions to the Service Account that been used by the sink, who will publish logs to Pubsub topic

2. Sethup Elasticsearch & Kibana

Same as GCP, there is a script can get the job done. Simply run `cd scripts/elastic && ./init.sh; cd -` then you done. Also, make sure you have updated the parameters on the top of the `init.sh` script according to your Elasticsearch setup.

This script will

- Create an index pipeline for GCLB logs, mainly for adding Geo information and parsing User Agent field
- Create an index template in Elasticsearch, so if the index name starts with `gclb*` it will use the schema & settings defined [here](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/index-gclb-template.json)
- Create an index called `gclb-000001` and a writing alias associate with it named `gclb-ingest`
- Create an index rolling policy for the created alias, hence the dataflow only write to the fixed index name with more indices been created `gclb-000002`, `gclb-000003` ... etc. etc. underneath. The policy has been defined [here](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/init.sh#L47), you could update that according to your scenario. The default rolling policy is either the index is 30-day old or hit 1 million docs or 5GB in size will create a new one.

More information about index management - highly recommended for logging senarios

- [Index Lifecycle Management](https://www.elastic.co/guide/en/elasticsearch/reference/current/index-lifecycle-management.html)
- [Index Rollerver](https://www.elastic.co/blog/managing-time-based-indices-efficiently)
- [Curator](https://www.elastic.co/guide/en/elasticsearch/client/curator/current/index.html)

##### Run the pipeline

Now you good to go.

Double check the paramters passed to the job trigger in `makefile`, then,

```
make df
```

##### Why exclude url contains the *ingest* keyword

First of all, we could do it when create a the [sink](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/gcp_setup.sh#L16-L17). Or in the Elasticsearch [pipeline](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/index-gclb-pipeline.json#L4-L8). It's highly recommended to do it at the sink. That would be more efficient. We only demonstrate how to use that *drop* processor here in the code in case you may need for other purposes.

The reason we drop that is to prevent a dead loop. We have configured our Elastic Stack behind the Google Cloud Load Balancer which all have the keyword *ingest* for Elasticsearch ingest nodes. So the accessing logs will be processed by the logging pipeline as an infinite loop. Imagine: POST data to ingest nodes -> GCLB produce logs -> ingest logs over and over again. 

So you may or may not need this, please adjust accordingly to your environment.

#### Dashboards in Kibana

Import from [this](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/gclb_dashboard.ndjson) example

You could have much more beyond the below examples in Kibana

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash1.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash2.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash3.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash4.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash5.png)
