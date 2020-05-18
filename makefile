pwd := $(shell pwd)
ipaddr := $(shell hostname -I | cut -d ' ' -f 1)
region := asia-east1
project := google.com:bin-wus-learning-center
job := gclb
eshost := https://k8es.ingest.bindiego.com
esuser := elastic
espass := changeme
esindex := gclb-ingest

dfup:
	@mvn -Pdataflow-runner compile exec:java \
        -Dexec.mainClass=bindiego.BindiegoStreaming \
        -Dexec.cleanupDaemonThreads=false \
        -Dexec.args="--project=$(project) \
        --streaming=true \
        --autoscalingAlgorithm=THROUGHPUT_BASED \
        --maxNumWorkers=20 \
        --workerMachineType=n1-standard-2 \
        --diskSizeGb=64 \
        --numWorkers=3 \
        --tempLocation=gs://bindiego/tmp/ \
        --gcpTempLocation=gs://bindiego/tmp/gcp/ \
        --gcsTempLocation=gs://bindiego/tmp/gcs/ \
        --stagingLocation=gs://bindiego/staging/ \
        --runner=DataflowRunner \
        --topic=projects/$(project)/topics/gclb-topic \
        --subscription=projects/$(project)/subscriptions/gclb-sub \
        --numShards=1 \
        --windowSize=6s \
        --allowedLateness=8s \
        --earlyFiringPeriod=2s \
        --lateFiringCount=1 \
        --filenamePrefix=gclb. \
        --outputDir=gs://bindiego/gclb/out/ \
        --errOutputDir=gs://bindiego/gclb/out/err/ \
        --esHost=$(eshost) \
        --esUser=$(esuser) \
        --esPass=$(espass) \
        --esIndex=$(esindex) \
        --defaultWorkerLogLevel=INFO \
        --jobName=$(job) \
        --update \
        --region=$(region)"

df:
	@mvn -Pdataflow-runner compile exec:java \
        -Dexec.mainClass=bindiego.BindiegoStreaming \
        -Dexec.cleanupDaemonThreads=false \
        -Dexec.args="--project=$(project) \
        --streaming=true \
        --autoscalingAlgorithm=THROUGHPUT_BASED \
        --maxNumWorkers=20 \
        --workerMachineType=n1-standard-2 \
        --diskSizeGb=64 \
        --numWorkers=3 \
        --tempLocation=gs://bindiego/tmp/ \
        --gcpTempLocation=gs://bindiego/tmp/gcp/ \
        --gcsTempLocation=gs://bindiego/tmp/gcs/ \
        --stagingLocation=gs://bindiego/staging/ \
        --runner=DataflowRunner \
        --topic=projects/$(project)/topics/gclb-topic \
        --subscription=projects/$(project)/subscriptions/gclb-sub \
        --numShards=1 \
        --windowSize=6s \
        --allowedLateness=8s \
        --earlyFiringPeriod=2s \
        --lateFiringCount=1 \
        --filenamePrefix=gclb. \
        --outputDir=gs://bindiego/gclb/out/ \
        --errOutputDir=gs://bindiego/gclb/out/err/ \
        --esHost=$(eshost) \
        --esUser=$(esuser) \
        --esPass=$(espass) \
        --esIndex=$(esindex) \
        --defaultWorkerLogLevel=INFO \
        --jobName=$(job) \
        --region=$(region)"

cancel:
	@gcloud dataflow jobs cancel $(job) --region=$(region)

drain:
	@gcloud dataflow jobs drain $(job) --region=$(region)

.PHONY: df dfup cancel drain
