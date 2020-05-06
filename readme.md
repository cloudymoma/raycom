## Apache Beam Sample

[![Build Status](https://jenkins.bindiego.com/buildStatus/icon?job=raycom-streaming)](https://jenkins.bindiego.com/job/raycom-streaming/)

You can use this master branch as a skeleton java project

master分支可以用来当作一个骨架项目

beam代码理论上可以驱动spark，flink等等流式框架，详情参考[这里](https://beam.apache.org/documentation/runners/capability-matrix/)

### Proposed streaming pipeline

#### IMPORTANT: in the sample code, assume the pubsub message is csv text encoded in utf-8

pubsub/kafka -> dataflow/flink -> join dimesion table -> data processing (realtime calculation + data warehouse ingestion + back files) -> GCS(avro, csv for both data & deadleter) + BigQuery + HBase/Bigtable (realtime analysis) + Elasticsearch

#### Current pipeline DAG
![](https://raw.githubusercontent.com/bindiego/raycom/streaming/miscs/pipeline_dag.png)

- Data consumption from message queue (Pubsub / Kafka)
- Raw data *join* dimension table, MySQL & fit in memroy
- Windowed data really time aggregation then ingest into Bigtable / Hbase
- Hot data ingest into Elasticsearch for realtime analysis
- Ingest into data warehouse (BigQuery) for big data analysis
- Data backup into files (Avro + CSV)

#### Quick start 快速开始

##### Prerequisits

Java dev environment
- JDK8+
- Maven

##### Dimension table in MySQL 维度表，这里用MySQL，假设可以全部加载到内存以分发到所有worker

项目里提供了初始化脚本 `scripts/dim1.sql` 维表更新的话直接`update`整个管道就可以了，如果维表需要LRU策略保留在内存，目前还没有办法。

You could use [this](https://github.com/bindiego/raycom/blob/streaming/scripts/dim1.sql) script to init the MySQL if you use [gcpplayground](https://github.com/bindiego/gcpplayground) to generate your messages. Also, you could simply use [this init script](https://github.com/bindiego/local_services/tree/develop/mysql) to run a MySQL instance in [Docker](https://github.com/bindiego/local_services/tree/develop/docker). 

##### Bigtable init 初始化Bigtable，可以用HBase代替

You could use `make` to initialize the Bigtable enviroment. Adjust the parameters in `makefile` accordingly, e.g. cluster name, region etc.

Create Bigtable cluster, run it once. 拉起一个Bigtable集群实例。

`make btcluster`

Setup Bigtable tables, both tall and wide. 这步会建立一个宽表和一个高表分别用来储存实时分析的数据。

`make btinit`

##### Elasticsearch index & kibana index pattern initialization, ES索引和Kibana的index pattern初始化

This step is **optional** but you may need to plan ahead for best practices, especially for streaming jobs.

- Create an ES [index template](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-templates.html), so created index will share the same attributes (settings, mappings etc.)
- Create a Kibana index pattern for query those indices

You could use the following scripts for above purposes, but remember to modify the `init.sh` accordingly for connection parameters.

跑初始化脚本前需要注意更新下面4个参数，分别是es的访问地址、kibana的访问地址、用户名和密码。

```
es_client=https://k8es.client.bindiego.com
kbn_host=https://k8na.bindiego.com
es_user=elastic
es_pass=<password>
```

Also, you should update `./scripts/elastic/index-raycom-template.json` accordingly to define the index schema and settings.

初始化ES模版的目的是在注入数据的时候可能会根据时间去建立新的索引，这样他们都具备相同的属性了。那么Kibana里也可以通过同一个索引pattern进行查询。请根据需要设置`./scripts/elastic/index-raycom-template.json`来设置索引属性，然后到`./scripts/elastic`目录下运行`./init.sh`来完成初始化。

then run,

```shell
cd scripts/elastic

./init.sh
```

Finally, you may want to ingest data into different indices on a time basis, like hourly, daily or month. This could be controlled by using [Index alias](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-add-alias.html). So your dataflow job can only specify the name of the alias on start. [Curator](https://www.elastic.co/guide/en/elasticsearch/client/curator/5.8/alias.html) is the tool can automate this process or schedule your own jobs.

Other out of scope topics on Elastic best practices,

- [Index Lifecycle Management](https://www.elastic.co/guide/en/elasticsearch/reference/current/index-lifecycle-management.html)
- [Snapshots & Restore](https://www.elastic.co/guide/en/elasticsearch/reference/current/snapshot-restore.html)
- Elastic stack deployment: [Auto scripts](https://github.com/bindiego/local_services/tree/develop/elastic) or [on k8s / GKE](https://github.com/bindiego/local_services/tree/develop/k8s/gke/elastic)

##### Run the pipeline

This branch is focusing on streaming, so the sample subscribes messages from Pubsub. It's easy to switch to KafkaIO in beam. But the quickest way to produce some dummy data then send to Pubsub for fun is by using [this](https://github.com/bindiego/gcpplayground) project.

If you use the [GCP Play Ground](https://github.com/bindiego/gcpplayground) to produce the pubsub message, there isn't much to do. Simply update the `run` shell script, make sure you have the corresponding permissions to manipulate the GCP resources.

Double check the paramters passed to the job trigger in `makefile`, then,

```
make df
```

##### Caveats

The purpose of this project is only to show you how to quickly run a streaming pipeline in Dataflow and the concepts about windowing, triggers & watermark. Even though the running cluster is elastic, you'd better break this big DAG into smaller pipelines and use Pubsub(or Kafka) as a 'communication bus' for better computing resources utilization and easy/faster recovery. Also, there are ways you could improve the performance, i.e. csv data handling etc. It's not the purpose of this example.

这里的代码示例主要为了说明beam的工作原理（触发器、窗口、水印等等）和一般实时+线下adhoc数据分析的一个大体框架。虽然Dataflow引擎可以动态伸缩，如果其他不能动态伸缩的引擎，就更需要把这个大的DAG拆分成一些小的管道，使用发布/订阅引擎作为数据交换媒介。这样维护起来比较清晰，更能提高计算资源的利用率，还在出错的时候相对快的恢复（暴力重跑啥的）。当然，数据处理的效率还有很多优化空间，大家要根据具体场景去做，因为没有唯一标准答案，也就不在这里下功夫了。

#### FAQ
1. Do I need to setup the BigQuery table in advance?

A: No. The application will create for you, and append to existing table by default.

2. How to control the permissions?

A: This project is currently relying on the service account specified by the `GOOGLE_APPLICATION_CREDENTIALS` environment variable. Consult [here](https://cloud.google.com/docs/authentication/getting-started) for details.

3. More details for triggers?

A: Hope [this](https://gist.github.com/bindiego/3814cfbd3b8d47216fe74686b0ae4339) example explained triggers well.

4. The DAG is too complicated?

A: You will need to comment out the code blocks in the [job code file](https://github.com/cloudymoma/raycom/blob/streaming/src/main/java/bindiego/BindiegoStreaming.java) to simplify it to get a really quick start. Or *master branch* could be another go :)

5. Elasticsearch index alias cannot guarantee all data in a particular window falls into corresponding index during rotation.

A: This actually doesn't matter since you query multiple indices / index pattern anyway.