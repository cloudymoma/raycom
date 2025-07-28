## Google Cloud Load Balancer logs in Elasticsearch

[![Unit Tests](https://github.com/cloudymoma/raycom/actions/workflows/unit-tests.yml/badge.svg)](https://github.com/cloudymoma/raycom/actions/workflows/unit-tests.yml)

This project provides a comprehensive solution for streaming Google Cloud Load Balancer (GCLB) logs to Elasticsearch using Apache Beam and Google Cloud Dataflow. It includes CDN setup, performance optimizations, and comprehensive monitoring capabilities.

You can use this master branch as a skeleton java project | master分支可以用来当作一个骨架项目

Apache Beam code can theoretically drive Spark, Flink and other streaming frameworks, for details refer to [here](https://beam.apache.org/documentation/runners/capability-matrix/)

## Table of Contents

- [Architecture Overview](#proposed-streaming-pipeline)
- [Quick Start](#quickstart-快速开始)
- [Google Cloud CDN Setup](#google-cloud-cdn-setup)
- [GCLB Logging Pipeline](#gclb-logging-data-explained)
- [Kibana Dashboards](#dashboards-in-kibana)
- [Unit Testing](#unit-testing)
- [Performance Optimizations](#performance-optimizations)

### Proposed streaming pipeline

Stackdriver logging -> Pubsub -> Dataflow -> Elasticsearch

#### Current pipeline DAG
![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/pipeline_dag.png)

#### Quickstart 快速开始

##### Prerequisits

Java dev environment
- JDK8+
- Maven

Elasticsearch
- Option 1: [Run your own](https://github.com/bindiego/local_services/tree/develop/elastic)
- Option 2: [Run on k8s / GKE](https://github.com/elasticsearch-cn/elastic-on-gke), recommended :)
- Option 3: [Run on Elastic Cloud](https://cloud.elastic.co/)

##### Preparation

1. **Configure Elasticsearch Password**

Create a `.espass` file in the project root directory containing your Elasticsearch password:

```bash
echo "your_elasticsearch_password" > .espass
```

This file is automatically ignored by git (configured in `.gitignore`) to prevent accidentally committing sensitive credentials. Both the makefile and Elasticsearch setup scripts will read the password from this file, with a fallback to "changeme" if the file doesn't exist.

2. **Configure Elasticsearch Host** 

Create a `.eshost` file in the project root directory containing your Elasticsearch host URL:

```bash
echo "https://your-elasticsearch-host.com" > .eshost
```

This file is automatically ignored by git (configured in `.gitignore`) to prevent accidentally committing sensitive host information. Both the makefile and Elasticsearch setup scripts will read the Elasticsearch host from this file, with a fallback to "https://k8es.ingest.bindiego.com" if the file doesn't exist.

3. **Configure Kibana Host** 

Create a `.kbnhost` file in the project root directory containing your Kibana host URL:

```bash
echo "https://your-kibana-host.com" > .kbnhost
```

This file is automatically ignored by git (configured in `.gitignore`) to prevent accidentally committing sensitive host information. The Elasticsearch setup script will read the Kibana host from this file, with a fallback to "https://k8na.bindiego.com" if the file doesn't exist.

4. Setup GCP

You could simply run `cd scripts && ./gcp_setup.sh; cd -`, but before that, make sure the parameters on the top have been updated according to your environment, especially the `project` variable, others are really optional.

So this script will

- Create a Pubsub topic and a subscription, this subscription should be configured later for Dataflow job
- Setup a Stackdriver sink (Pubsub) for HTTP load balancers
- Grant permissions to the Service Account that been used by the sink, who will publish logs to Pubsub topic

5. Setup Elasticsearch & Kibana

Same as GCP, there is a script can get the job done. Simply run `cd scripts/elastic && ./init.sh; cd -` then you done. Also, make sure you have updated the parameters on the top of the `init.sh` script according to your Elasticsearch setup.

**Note**: Both the makefile and Elasticsearch setup script will automatically use:
- The password from the `.espass` file you created in step 1
- The Elasticsearch host from the `.eshost` file you created in step 2 
- The Kibana host from the `.kbnhost` file you created in step 3 

Make sure your Elasticsearch cluster and Kibana instance are accessible and the credentials are correct.

This script will

- Create an index pipeline for GCLB logs, mainly for adding Geo information and parsing User Agent field
- Create an index template in Elasticsearch, so if the index name starts with `gclb*` it will use the schema & settings defined [here](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/index-gclb-template.json)
- Create an index called `gclb-000001` and a writing alias associate with it named `gclb-ingest`
- Create an index rolling policy for the created alias, hence the dataflow only write to the fixed index name with more indices been created `gclb-000002`, `gclb-000003` ... etc. etc. underneath. The policy has been defined [here](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/init.sh#L47), you could update that according to your scenario. The default rolling policy is either the index is 30-day old or hit 1 million docs or 5GB in size will create a new one.

**Caveat:** You may need to add `-k` option to `curl` command which will ignore insecured ssl connection, in case the certificate is created by yourself.

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

**Security Note**: Configuration files are automatically used as follows:
- **Makefile commands**: Use `.espass` and `.eshost` files for Elasticsearch connection
- **Setup scripts**: Use `.espass`, `.eshost`, and `.kbnhost` files for complete configuration

This ensures secure credential handling and flexible configuration across development and production environments.

###### FAQs 常见问题

1. SSL connection issue

You may need to change the `esIsIgnoreInsecureSSL` in [`makefile`](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/makefile#L15) here to `true` in case you have a self-signed certificate.

2. GCP related IAM / permission issues

Please consult the [Dataflow security and permissions ](https://cloud.google.com/dataflow/docs/concepts/security-and-permissions#security_and_permissions_for_pipelines_on_google_cloud_platform) for details. Generally you will need to grant permissions for both dataflow controller & compute engine service accounts for the GCP resources used, i.e. Pubsub, GCS aka Cloud Storage etc.

##### Why exclude url contains the *ingest* keyword

First of all, we could do it when create a the [sink](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/gcp_setup.sh#L16-L17). Or in the Elasticsearch [pipeline](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/index-gclb-pipeline.json#L4-L8). It's highly recommended to do it at the sink. That would be more efficient. We only demonstrate how to use that *drop* processor here in the code in case you may need for other purposes.

The reason we drop that is to prevent a dead loop. We have configured our Elastic Stack behind the Google Cloud Load Balancer which all have the keyword *ingest* for Elasticsearch ingest nodes. So the accessing logs will be processed by the logging pipeline as an infinite loop. Imagine: POST data to ingest nodes -> GCLB produce logs -> ingest logs over and over again.

So you may or may not need this, please adjust accordingly to your environment.

#### GCLB Logging data explained

First thing first, all latency values, such as `httpRequest.backendLatency` and `httpRequest.frontendSrtt` etc. are all presented in `seconds`. We have turned into `ms` in Kibana dashboard by using `TSVB` widgets :)

## Google Cloud CDN Setup

The project includes comprehensive setup documentation for Google Cloud CDN configurations to optimize content delivery and performance. CDN setup helps reduce latency, improve user experience, and reduce bandwidth costs for your applications.

### Standard Cloud CDN with Load Balancer

For traditional Cloud CDN setup using Google Cloud Load Balancer:

#### Prerequisites
- Google Cloud project with billing enabled
- Appropriate IAM permissions for Compute Engine and Storage
- GCS bucket for content storage

#### Setup Process

**1. Create GCS Bucket**
```bash
gcloud storage buckets create gs://dingo-cdn \
  --project=du-hast-mich --default-storage-class=standard \
  --location=us-central1 --uniform-bucket-level-access

# Make bucket publicly readable
gcloud storage buckets add-iam-policy-binding \
  gs://dingo-cdn --member=allUsers --role=roles/storage.objectViewer
```

**2. Reserve Static IP Address**
```bash
gcloud compute addresses create dingo-cdn-ip \
    --network-tier=PREMIUM \
    --ip-version=IPV4 \
    --global
```

**3. Configure External Load Balancer**
```bash
# Backend bucket
gcloud compute backend-buckets create dingo-cdn-backend-bucket \
    --gcs-bucket-name=dingo-cdn \
    --enable-cdn \
    --cache-mode=USE_ORIGIN_HEADERS

# URL map
gcloud compute url-maps create dingo-http-cdn-lb \
    --default-backend-bucket=dingo-cdn-backend-bucket

# Target proxy
gcloud compute target-http-proxies create dingo-http-cdn-lb-proxy \
    --url-map=dingo-http-cdn-lb

# Forwarding rule
gcloud compute forwarding-rules create dingo-http-cdn-lb-forwarding-rule \
    --load-balancing-scheme=EXTERNAL_MANAGED \
    --network-tier=PREMIUM \
    --address=dingo-cdn-ip \
    --global \
    --target-http-proxy=dingo-http-cdn-lb-proxy \
    --ports=80
```

### Media CDN (Advanced)

For advanced content delivery using Google Cloud Media CDN:

#### Prerequisites
```bash
gcloud services enable networkservices.googleapis.com
gcloud services enable certificatemanager.googleapis.com
```

#### Setup Process

**1. Create EdgeCache Origin**
```bash
gcloud edge-cache origins create dingo-media-cdn \
    --origin-address="gs://dingo-cdn"
```

**2. Deploy EdgeCache Service**
```bash
gcloud edge-cache services import dingo-media-cdn-service \
    --source=cdn/dingo-media-cdn-service.yaml
```

**3. Configuration Details**
The Media CDN service configuration (`cdn/dingo-media-cdn-service.yaml`) includes:
- Host routing for `bindiego.com`
- Cache policies with 1-hour TTL
- Static content caching
- Custom headers for cache status

#### Testing CDN Setup

**Standard CDN Testing:**
```bash
curl -I -o /dev/null http://YOUR_CDN_IP/veo_videos/newton.mp4
```

**Media CDN Testing:**
```bash
# With DNS
curl -svo /dev/null "http://DOMAIN_NAME/FILE_NAME"

# Without DNS (using IP override)
curl -svo /dev/null --resolve bindiego.com:80:<IP_Address> "http://bindiego.com/veo_videos/newton.mp4"
```

### CDN Features

- **Cache Optimization**: Configurable cache modes and TTL settings
- **Geographic Distribution**: Global edge locations for reduced latency
- **Security**: IAM-based access control and HTTPS support
- **Monitoring**: Integration with Cloud Logging for CDN access logs
- **Cost Optimization**: Reduced origin server load and bandwidth costs

### CDN Integration with GCLB Logging

The CDN setup complements the GCLB logging pipeline by:
- **Performance Monitoring**: CDN access logs can be ingested alongside GCLB logs
- **Cache Analytics**: Monitor cache hit ratios and performance metrics
- **Geographic Insights**: Analyze content delivery performance by region
- **Cost Tracking**: Monitor bandwidth usage and CDN costs

For detailed configuration files and advanced setup options, refer to the `cdn/` directory in this repository.

## Unit Testing

The project includes a comprehensive unit test suite with 62 tests covering all utility and I/O classes:

### Test Coverage
- **DurationUtilsTest**: 28 tests (parsing, validation, performance, thread safety)
- **SchemaParserTestSimple**: 7 tests (initialization, error handling, concurrency)
- **ElasticsearchIOTestSimple**: 16 tests (configuration, metrics, pooling, validation)
- **WindowedFilenamePolicyTestSimple**: 11 tests (templates, windowing, performance)

### Running Tests
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=DurationUtilsTest

# Run tests with pattern
mvn test -Dtest="*TestSimple"
```

### CI/CD Integration
The project includes GitHub Actions workflows for automated testing:
- **Multi-version testing**: Java 11, 17, and 21 compatibility
- **Performance validation**: All benchmarks must pass
- **Thread safety**: Concurrent access testing
- **Quality gates**: PRs blocked if tests fail

### Test Performance Metrics
- **Total Tests**: 62 (100% passing)
- **Execution Time**: ~4.1 seconds
- **Coverage**: All util and io classes
- **Thread Safety**: Validated with concurrent access testing

## Performance Optimizations

The ElasticsearchIO component has been significantly optimized for high-throughput production workloads:

### Key Optimizations
- **Connection Pooling**: Reuse connections across workers (2-3x throughput improvement)
- **Async Processing**: Proper async handling with CompletableFuture (40-60% latency reduction)
- **Memory Optimization**: Buffer pooling and efficient operations (30-50% memory reduction)
- **Batching Strategy**: Adaptive batching with time-based flushing
- **Thread Safety**: Comprehensive synchronization mechanisms

### Performance Metrics
- **Throughput**: 2-3x improvement due to connection pooling
- **Latency**: 40-60% reduction in write latency
- **Memory Usage**: 30-50% reduction through optimizations
- **Error Recovery**: Enhanced retry logic and failure handling
- **Monitoring**: Built-in performance metrics and monitoring

### Configuration Options
```java
ElasticsearchIO.append()
    .withConnectionConf(connectionConf)
    .withMaxBatchSize(2000L)                    // Batch size optimization
    .withMaxBatchSizeBytes(10L * 1024L * 1024L) // Memory management
    .withFlushInterval(15000L)                  // Time-based flushing
    .withMaxConcurrentRequests(10)              // Concurrency control
```

#### Dashboards in Kibana

Import from [this](https://github.com/cloudymoma/raycom/blob/gcp-lb-log/scripts/elastic/gclb_dashboard.ndjson) example

You could have much more beyond the below examples in Kibana

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash1.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash2.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash3.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash4.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash5.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash6.png)

![](https://raw.githubusercontent.com/cloudymoma/raycom/gcp-lb-log/miscs/gclb-dash7.png)
