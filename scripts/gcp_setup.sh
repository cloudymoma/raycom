#!/bin/bash

project=google.com:bin-wus-learning-center
topic=gclb-topic
subscription=gclb-sub
sink=gclb-sink-pubsub

# create a pubsub topic
gcloud pubsub topics create $topic

# create subscription
gcloud pubsub subscriptions create $subscription --topic=$topic --topic-project=$project

# create a stackdriver sink (pubsub)
gcloud logging sinks create $sink pubsub.googleapis.com/projects/$project/topics/$topic \
    --log-filter='resource.type="http_load_balancer" AND NOT httpRequest.requestUrl:ingest'
    # --log-filter='resource.type="http_load_balancer"'

# add service account used by stackdriver for pubsub topic
logging_sa=`gcloud logging sinks describe $sink | grep "writerIdentity" | cut -d ' ' -f 2`
gcloud pubsub topics add-iam-policy-binding $topic \
    --member $logging_sa \
    --role roles/pubsub.publisher
