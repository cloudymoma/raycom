# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Google Cloud Load Balancer (GCLB) logs streaming pipeline that processes logs from Stackdriver to Elasticsearch using Apache Beam and Google Cloud Dataflow. The pipeline processes HTTP load balancer logs, extracts metadata, and stores them in Elasticsearch for analysis and visualization.

## Build System & Common Commands

**Build Tools:**
- Maven-based Java project (Java 11+)
- Makefile for common operations (note: filename is lowercase `makefile`)

**Essential Commands:**
```bash
# Build the project
make build
mvn compile

# Clean build artifacts
make clean
mvn clean

# Package (creates fat jar for Dataflow)
mvn package

# Run Dataflow pipeline
make df        # Deploy new pipeline
make dfup      # Update existing pipeline

# Pipeline management
make cancel    # Cancel running pipeline
make drain     # Drain running pipeline
```

**Testing:**
- Uses JUnit 4 for testing (maven-surefire-plugin configured)
- Run tests: `mvn test`
- Test files: ElasticsearchIOTestSimple, WindowedFilenamePolicyTestSimple, DurationUtilsTest, SchemaParserTestSimple

## Architecture Overview

**Streaming Pipeline Flow:**
1. **Source**: Pub/Sub subscription reads GCLB logs from Stackdriver
2. **Processing**: `ExtractPayload` DoFn transforms log data:
   - Parses JSON log entries
   - Extracts latency metrics (backend, frontend SRTT, GFE latency)
   - Adds domain/protocol/resource type metadata
   - Converts timestamps to Elasticsearch format
3. **Windowing**: Fixed time windows with early/late firing triggers
4. **Sinks**: 
   - Primary: Elasticsearch via custom `ElasticsearchIO`
   - Error handling: Failed records written to GCS

**Key Components:**
- `BindiegoStreaming.java`: Main pipeline class with ETL logic
- `BindiegoStreamingOptions.java`: Pipeline configuration interface
- `ElasticsearchIO.java`: Custom Elasticsearch connector
- `WindowedFilenamePolicy.java`: File naming for windowed outputs
- `DurationUtils.java`: Duration parsing utilities
- `SchemaParser.java`: JSON schema parsing utility for GCS files

**Configuration:**
- Pipeline parameters configured via makefile variables
- Supports multiple Beam runners (Direct, Dataflow, Flink, Spark)
- Default Elasticsearch settings in makefile (host, credentials, batch sizes)

## Setup Requirements

**Prerequisites:**
- JDK 11+
- Maven
- Elasticsearch cluster (local, GKE, or Elastic Cloud)
- GCP project with Dataflow, Pub/Sub permissions

**Initial Setup:**
1. Update makefile variables (project, eshost, credentials)
2. Run `cd scripts && ./gcp_setup.sh` for GCP resources
3. Run `cd scripts/elastic && ./init.sh` for Elasticsearch setup

**Key Dependencies:**
- Apache Beam 2.66.0
- Elasticsearch Java client 8.18.3
- Google Cloud libraries via BOM
- Jackson for JSON processing

## Reference Documentation

- [Media CDN Logging Fields](https://cloud.google.com/media-cdn/docs/logging#cache-fields) - Explanation of cache fields in Media CDN logs