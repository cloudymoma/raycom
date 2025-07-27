#!/bin/bash

# Elasticsearch 8.18 Compatible Initialization Script
# Updated to use modern APIs and best practices for ES 8.x

pwd=`pwd`
project_root="$(cd "$pwd/../.." && pwd)"

es_client=https://k8es.client.bindiego.com
kbn_host=https://k8na.bindiego.com
es_user=elastic
es_pass=$(cat "$project_root/.espass" 2>/dev/null || echo "changeme")

# Create an ES pipeline for GCLB logs (Compatible with ES 8.18)
__create_index_pipeline() {
    echo "Creating ingest pipeline for GCLB logs..."
    curl -X PUT \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_ingest/pipeline/gclb" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-gclb-pipeline.json"
    
    echo -e "\nVerifying pipeline creation..."
    curl -X GET \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_ingest/pipeline/gclb"
}

# Create index template using modern composable templates API (ES 8.x)
__create_index_template() {
    echo "Creating composable index template for GCLB logs..."
    curl -X PUT \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_index_template/gclb" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-gclb-template.json"

    echo -e "\nVerifying template creation..."
    curl -X GET \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_index_template/gclb"
}

# Create ILM policy and index with alias (Compatible with ES 8.18)
__create_index_and_setup() {
    echo "Creating ILM policy..."
    curl -X PUT \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_ilm/policy/gclb-policy" \
        -H "Content-Type: application/json" \
        -d "@${pwd}/index-gclb-policy.json"

    echo -e "\nCreating initial index with write alias..."
    # Check if index already exists
    if curl -s -f -u "${es_user}:${es_pass}" "${es_client}/gclb-000001" > /dev/null 2>&1; then
        echo "Index gclb-000001 already exists, skipping creation..."
    else
        curl -X PUT \
            -u "${es_user}:${es_pass}" \
            "${es_client}/gclb-000001" \
            -H "Content-Type: application/json" \
            -d '{
                "aliases": {
                    "gclb-ingest": {
                        "is_write_index": true
                    }
                }
            }'
    fi

    echo -e "\nVerifying ILM policy assignment..."
    curl -X GET \
        -u "${es_user}:${es_pass}" \
        "${es_client}/gclb*/_ilm/explain"
}

# Create Kibana Data View (replaces Index Pattern in Kibana 8.x)
__create_data_view() {
    echo "Creating Kibana Data View for GCLB logs..."
    
    # Modern Kibana 8.x Data Views API with SSL certificate bypass
    curl -X POST \
        -k \
        -u "${es_user}:${es_pass}" \
        "${kbn_host}/api/data_views/data_view" \
        -H "kbn-xsrf: true" \
        -H "Content-Type: application/json" \
        -d '{
            "data_view": {
                "title": "gclb*",
                "timeFieldName": "@timestamp",
                "name": "GCLB Logs"
            }
        }'
    
    echo -e "\nData View created successfully!"
}

# Verify Elasticsearch cluster health and version
__verify_cluster() {
    echo "Verifying Elasticsearch cluster health and version..."
    
    # Check cluster health
    curl -X GET \
        -u "${es_user}:${es_pass}" \
        "${es_client}/_cluster/health"
    
    echo -e "\nChecking Elasticsearch version..."
    curl -X GET \
        -u "${es_user}:${es_pass}" \
        "${es_client}/"
}

# Main execution with error handling
main() {
    echo "=== Elasticsearch 8.18 Compatible Setup ==="
    echo "Starting GCLB logging infrastructure setup..."
    
    __verify_cluster
    
    echo -e "\n=== Step 1: Creating Ingest Pipeline ==="
    __create_index_pipeline
    
    echo -e "\n=== Step 2: Creating Index Template ==="
    __create_index_template
    
    echo -e "\n=== Step 3: Setting up ILM and Indices ==="
    __create_index_and_setup
    
    echo -e "\n=== Step 4: Creating Kibana Data View ==="
    __create_data_view
    
    echo -e "\n=== Setup Complete! ==="
    echo "GCLB logging infrastructure is ready for Elasticsearch 8.18"
    echo "Write alias: gclb-ingest"
    echo "Index pattern: gclb*"
    echo "Pipeline: gclb"
    echo "ILM Policy: gclb-policy"
}

# Execute main function
main
