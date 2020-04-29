#!/bin/bash

pwd=`pwd`

es_client=https://k8es.client.bindiego.com
kbn_host=https://k8na.bindiego.com
es_user=elastic
es_pass=<password>

# create an ES template
__create_index_template() {
    curl -X PUT \
        -u "elastic:${es_pass}" \
        "${es_client}/_template/raycom" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-raycom-template.json"

    # veryfy
    curl -X GET \
        -u "elastic:${es_pass}" \
        "${es_client}/_template/raycom"#
}

# create a Kibana index pattern
__create_index_pattern() {
    curl -X POST \
        -u "elastic:${es_pass}" \
        "${kbn_host}/api/saved_objects/index-pattern" \
        -H "kbn-xsrf: true" \
        -H "Content-Type: application/json" \
        -d '{"attributes":{"title":"raycom*","timeFieldName":"@timestamp","fields":"[]"}}'
}

__create_index_template
__create_index_pattern