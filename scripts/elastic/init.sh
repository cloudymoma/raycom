#!/bin/bash

pwd=`pwd`

es_client=https://k8es.client.bindiego.com
kbn_host=https://k8na.bindiego.com
es_user=elastic
es_pass=changeme

# Create an ES pipeline for GCLB logs
__create_index_pipeline() {
    curl -X PUT \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_ingest/pipeline/gclb" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-gclb-pipeline.json"
}

# create an ES template
__create_index_template() {
    curl -X PUT \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_template/gclb" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-gclb-template.json"

    # veryfy
    curl -X GET \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_template/gclb"
}

__create_index_and_setup() {
    # create a lifecycle pocily, edit the json data file according to your needs
    curl -X PUT \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_ilm/policy/gclb-policy" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-gclb-policy.json"

    # create an index and assign an alias for writing
    curl -X PUT \
        -u "${es_user}:${es_pass}" \
        "${es_client}/gclb-000001" \
        -H "Content-Type: application/json" \
        -d '{"aliases": {"gclb-ingest": { "is_write_index": true }}}'

    # veryfy
    curl -X GET \
        -u "${es_user}:${es_pass}" \
        "${es_client}/gclb*/_ilm/explain"
}

# create a Kibana index pattern
__create_index_pattern() {
    curl -X POST \
        -u "${es_user}:${es_pass}" \
        "${kbn_host}/api/saved_objects/index-pattern" \
        -H "kbn-xsrf: true" \
        -H "Content-Type: application/json" \
        -d '{"attributes":{"title":"gclb*","timeFieldName":"@timestamp","fields":"[]"}}'
}

__create_index_pipeline
__create_index_template
__create_index_and_setup
__create_index_pattern
