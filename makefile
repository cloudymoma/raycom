pwd := $(shell pwd)
ipaddr := $(shell hostname -I | cut -d ' ' -f 1)
jdbcuri := jdbc:mysql://10.140.0.3:3306/gcp
jdbcusr := gcp
jdbcpwd := gcp2020
region := asia-east1
workerType := e2-standard-2
workerZone := b
job := raycom-streaming
eshost := https://k8es.ingest.bindiego.com
esuser := elastic
espass := changeme
esindex := raycom-dataflow-ingest
esBatchSize := 2000
esBatchBytes := 10485760
esNumThread := 2
esIsIgnoreInsecureSSL := false

dfup:
	@mvn -Pdataflow-runner compile exec:java \
        -Dexec.mainClass=bindiego.BindiegoStreaming \
        -Dexec.cleanupDaemonThreads=false \
        -Dexec.args="--project=google.com:bin-wus-learning-center \
        --streaming=true \
        --autoscalingAlgorithm=THROUGHPUT_BASED \
        --maxNumWorkers=20 \
        --workerMachineType=$(workerType) \
        --diskSizeGb=64 \
        --numWorkers=3 \
        --tempLocation=gs://bindiego/tmp/ \
        --gcpTempLocation=gs://bindiego/tmp/gcp/ \
        --gcsTempLocation=gs://bindiego/tmp/gcs/ \
        --stagingLocation=gs://bindiego/staging/ \
        --runner=DataflowRunner \
        --topic=projects/google.com:bin-wus-learning-center/topics/dingoactions \
        --subscription=projects/google.com:bin-wus-learning-center/subscriptions/dingoactions2avro \
        --numShards=1 \
        --windowSize=6s \
        --allowedLateness=8s \
        --earlyFiringPeriod=2s \
        --lateFiringCount=1 \
        --filenamePrefix=raycom. \
        --outputDir=gs://bindiego/raycom/out/ \
        --errOutputDir=gs://bindiego/raycom/out/err/ \
        --bqSchema=gs://bindiego/raycom/schemas/dingoactions.json \
        --bqOutputTable=google.com:bin-wus-learning-center:raycom.dingoactions \
        --avroSchema=gs://bindiego/raycom/schemas/dingoactions.avsc \
        --btInstanceId=bigbase \
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
        --workerZone=$(region)-$(workerZone)"

df:
	@mvn -Pdataflow-runner compile exec:java \
        -Dexec.mainClass=bindiego.BindiegoStreaming \
        -Dexec.cleanupDaemonThreads=false \
        -Dexec.args="--project=google.com:bin-wus-learning-center \
        --streaming=true \
        --autoscalingAlgorithm=THROUGHPUT_BASED \
        --maxNumWorkers=20 \
        --workerMachineType=$(workerType) \
        --diskSizeGb=64 \
        --numWorkers=3 \
        --tempLocation=gs://bindiego/tmp/ \
        --gcpTempLocation=gs://bindiego/tmp/gcp/ \
        --gcsTempLocation=gs://bindiego/tmp/gcs/ \
        --stagingLocation=gs://bindiego/staging/ \
        --runner=DataflowRunner \
        --topic=projects/google.com:bin-wus-learning-center/topics/dingoactions \
        --subscription=projects/google.com:bin-wus-learning-center/subscriptions/dingoactions2avro \
        --numShards=1 \
        --windowSize=6s \
        --allowedLateness=8s \
        --earlyFiringPeriod=2s \
        --lateFiringCount=1 \
        --filenamePrefix=raycom. \
        --outputDir=gs://bindiego/raycom/out/ \
        --errOutputDir=gs://bindiego/raycom/out/err/ \
        --bqSchema=gs://bindiego/raycom/schemas/dingoactions.json \
        --bqOutputTable=google.com:bin-wus-learning-center:raycom.dingoactions \
        --avroSchema=gs://bindiego/raycom/schemas/dingoactions.avsc \
        --btInstanceId=bigbase \
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
        --workerZone=$(region)-$(workerZone)"

cancel:
	@gcloud dataflow jobs cancel $(job) --region=$(region)

drain:
	@gcloud dataflow jobs drain $(job) --region=$(region)

btcluster:
	@cbt createinstance bigbase "Bigbase" bigbaby $(region)-a 1 SSD

btrelease:
	@cbt deleteinstance bigbase

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

.PHONY: df dfup cancel drain btcluster btinit btdata btclear btrelease
