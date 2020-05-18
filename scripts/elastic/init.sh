#!/bin/bash

pwd=`pwd`

es_client=https://k8es.client.bindiego.com
kbn_host=https://k8na.bindiego.com
es_user=elastic
es_pass=changeme

# create an ES template
__create_index_template() {
    curl -X PUT \
        -u "elastic:${es_pass}" \
        "${es_client}/_template/gclb" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-gclb-template.json"

    # veryfy
    curl -X GET \
        -u "elastic:${es_pass}" \
        "${es_client}/_template/gclb"#
}

__create_index_and_setup() {
    # create an index and assign an alias for writing
    curl -X PUT \
        -u "elastic:${es_pass}" \
        "${es_client}/gclb-000001" \
        -H "Content-Type: application/json" \
        -d '{"aliases": {"gclb-ingest": { "is_write_index": true }}}'

    # setup the writing alias rollup policy
    curl -X POST \
        -u "elastic:${es_pass}" \
        "${es_client}/gclb-ingest/_rollover" \
        -H "Content-Type: application/json" \
        -d '{"conditions": {"max_age": "30d", "max_docs": 1000000, "max_size": "5gb"}}'
}

# create a Kibana index pattern
__create_index_pattern() {
    curl -X POST \
        -u "elastic:${es_pass}" \
        "${kbn_host}/api/saved_objects/index-pattern" \
        -H "kbn-xsrf: true" \
        -H "Content-Type: application/json" \
        -d '{"attributes":{"title":"gclb*","timeFieldName":"@timestamp","fields":"[]"}}'
}

__create_index_template
__create_index_and_setup
__create_index_pattern