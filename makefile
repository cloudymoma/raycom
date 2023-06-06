pwd := $(shell pwd)
ipaddr := $(shell hostname -I | cut -d ' ' -f 1)
gcp_project := du-hast-mich
region := asia-east1
workerType := e2-standard-2
workerZone := b
job := raycom-streaming
pubsub_topic := firebase-rt-topic
pubsub_sub := firebase-rt-sub
bq_firebase_schema := bq_firebase.json
bq_storageWriteApiTriggeringFrequencySec := 60
gcs_bucket := bindiego
jdbcuri := jdbc:mysql://10.140.0.3:3306/gcp
jdbcusr := gcp
jdbcpwd := gcp2020
bigtable_instance := bigbase
eshost := https://k8es.ingest.bindiego.com
esuser := elastic
espass := changeme
esindex := raycom-dataflow-ingest
esBatchSize := 2000
esBatchBytes := 10485760
esNumThread := 2
esIsIgnoreInsecureSSL := false
isBasic := true

clean:
	@mvn clean

build:
	@mvn compile

dfup: build
	@mvn -Pdataflow-runner compile exec:java \
        -Dexec.mainClass=bindiego.BindiegoStreaming \
        -Dexec.cleanupDaemonThreads=false \
        -Dexec.args="--project=$(gcp_project) \
        --streaming=true \
        --autoscalingAlgorithm=THROUGHPUT_BASED \
        --maxNumWorkers=20 \
        --workerMachineType=$(workerType) \
        --diskSizeGb=64 \
        --numWorkers=3 \
        --tempLocation=gs://$(gcs_bucket)/tmp/ \
        --gcpTempLocation=gs://$(gcs_bucket)/tmp/gcp/ \
        --gcsTempLocation=gs://$(gcs_bucket)/tmp/gcs/ \
        --stagingLocation=gs://$(gcs_bucket)/staging/ \
        --runner=DataflowRunner \
        --topic=projects/$(gcp_project)/topics/$(pubsub_topic) \
        --subscription=projects/$(gcp_project)/subscriptions/$(pubsub_sub) \
        --numShards=1 \
        --windowSize=6s \
        --allowedLateness=8s \
        --earlyFiringPeriod=2s \
        --lateFiringCount=1 \
        --filenamePrefix=raycom. \
        --outputDir=gs://$(gcs_bucket)/raycom/out/ \
        --errOutputDir=gs://$(gcs_bucket)/raycom/out/err/ \
        --bqSchema=gs://$(gcs_bucket)/raycom/schemas/$(bq_firebase_schema) \
        --bqOutputTable=$(gcp_project):raycom.firebase_rt \
        --storageWriteApiTriggeringFrequencySec=$(bq_storageWriteApiTriggeringFrequencySec) \
        --avroSchema=gs://$(gcs_bucket)/raycom/schemas/dingoactions.avsc \
        --btInstanceId=$(bigtable_instance) \
        --btTableIdTall=bttall \
        --btTableIdWide=btwide \
        --jdbcClass=com.mysql.cj.jdbc.Driver \
        --jdbcConn=$(jdbcuri) \
        --jdbcUsername=$(jdbcusr) \
        --jdbcPassword=$(jdbcpwd) \
        --esHost=$(eshost) \
        --esUser=$(esuser) \
        --esPass=$(espass) \
        --esIndex=$(esindex) \
        --esMaxBatchSize=$(esBatchSize) \
        --esMaxBatchBytes=$(esBatchBytes) \
        --esNumThread=$(esNumThread) \
        --esIsIgnoreInsecureSSL=$(esIsIgnoreInsecureSSL) \
        --defaultWorkerLogLevel=INFO \
        --jobName=$(job) \
        --update \
        --region=$(region) \
        --workerZone=$(region)-$(workerZone) \
        --isBasic=$(isBasic)"

df: build
	@mvn -Pdataflow-runner compile exec:java \
        -Dexec.mainClass=bindiego.BindiegoStreaming \
        -Dexec.cleanupDaemonThreads=false \
        -Dexec.args="--project=$(gcp_project) \
        --streaming=true \
        --autoscalingAlgorithm=THROUGHPUT_BASED \
        --maxNumWorkers=20 \
        --workerMachineType=$(workerType) \
        --diskSizeGb=64 \
        --numWorkers=3 \
        --tempLocation=gs://$(gcs_bucket)/tmp/ \
        --gcpTempLocation=gs://$(gcs_bucket)/tmp/gcp/ \
        --gcsTempLocation=gs://$(gcs_bucket)/tmp/gcs/ \
        --stagingLocation=gs://$(gcs_bucket)/staging/ \
        --runner=DataflowRunner \
        --topic=projects/$(gcp_project)/topics/$(pubsub_topic) \
        --subscription=projects/$(gcp_project)/subscriptions/$(pubsub_sub) \
        --numShards=1 \
        --windowSize=6s \
        --allowedLateness=8s \
        --earlyFiringPeriod=2s \
        --lateFiringCount=1 \
        --filenamePrefix=raycom. \
        --outputDir=gs://$(gcs_bucket)/raycom/out/ \
        --errOutputDir=gs://$(gcs_bucket)/raycom/out/err/ \
        --bqSchema=gs://$(gcs_bucket)/raycom/schemas/$(bq_firebase_schema) \
        --bqOutputTable=$(gcp_project):raycom.firebase_rt \
        --storageWriteApiTriggeringFrequencySec=$(bq_storageWriteApiTriggeringFrequencySec) \
        --avroSchema=gs://$(gcs_bucket)/raycom/schemas/dingoactions.avsc \
        --btInstanceId=$(bigtable_instance) \
        --btTableIdTall=bttall \
        --btTableIdWide=btwide \
        --jdbcClass=com.mysql.cj.jdbc.Driver \
        --jdbcConn=$(jdbcuri) \
        --jdbcUsername=$(jdbcusr) \
        --jdbcPassword=$(jdbcpwd) \
        --esHost=$(eshost) \
        --esUser=$(esuser) \
        --esPass=$(espass) \
        --esIndex=$(esindex) \
        --esMaxBatchSize=$(esBatchSize) \
        --esMaxBatchBytes=$(esBatchBytes) \
        --esNumThread=$(esNumThread) \
        --esIsIgnoreInsecureSSL=$(esIsIgnoreInsecureSSL) \
        --defaultWorkerLogLevel=INFO \
        --jobName=$(job) \
        --region=$(region) \
        --workerZone=$(region)-$(workerZone) \
        --isBasic=$(isBasic)"

cancel:
	@gcloud dataflow jobs cancel $(job) --region=$(region)

drain:
	@gcloud dataflow jobs drain $(job) --region=$(region)

btcluster:
	@cbt createinstance $(bigtable_instance) "Bigbase" bigbaby $(region)-a 1 SSD

btrelease:
	@cbt deleteinstance $(bigtable_instance)

btinit:
	@cbt createtable bttall && \
		cbt createfamily bttall stats && \
		cbt createfamily bttall window_info
	-cbt ls bttall
	@cbt createtable btwide && \
		cbt createfamily btwide stats
	-cbt ls btwide

btclear:
	@-cbt deletetable bttall
	@-cbt deletetable btwide

btdata:
	@-echo "============= Tall ================"; cbt read bttall count=10
	@-echo "============= Wide ================"; cbt read btwide count=10

pubsub_init:
	@-gcloud pubsub topics create $(pubsub_topic)
	@-gcloud pubsub subscriptions create $(pubsub_sub) --topic=$(pubsub_topic) --topic-project=$(gcp_project)

gcs_init:
	@-gcloud storage buckets create gs://$(gcs_bucket) --project=$(gcp_project) --default-storage-class=STANDARD --location=$(region)  --uniform-bucket-level-access
	@-gsutil cp schemas/$(bq_firebase_schema) gs://$(gcs_bucket)/raycom/schemas/$(bq_firebase_schema)
	@-gsutil cp schemas/dingoactions.avsc gs://$(gcs_bucket)/raycom/schemas/dingoactions.avsc

bq_init:
	@-bq --location=US mk --dataset raycom

.PHONY: df dfup cancel drain btcluster btinit btdata btclear btrelease pubsub_init gcs_init build clean
