package bindiego;

import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryOptions;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.NestedValueProvider;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.runners.dataflow.options.DataflowWorkerLoggingOptions;

public interface BindiegoStreamingOptions 
        extends PipelineOptions, StreamingOptions, BigQueryOptions,
                DataflowPipelineOptions, DataflowWorkerLoggingOptions {
    @Description("Topic of pubsub")
    @Default.String("projects/google.com:bin-wus-learning-center/topics/dingoactions")
    ValueProvider<String> getTopic();
    void setTopic(ValueProvider<String> value);

    @Description("Subcriptions of pubsub")
    @Required
    ValueProvider<String> getSubscription();
    void setSubscription(ValueProvider<String> value);

    @Description("The directory to output files to. Must end with a slash.")
    @Required
    ValueProvider<String> getOutputDir();
    void setOutputDir(ValueProvider<String> value);

    @Description("The directory to output error files to. Must end with a slash.")
    @Required
    ValueProvider<String> getErrOutputDir();
    void setErrOutputDir(ValueProvider<String> value);

    @Description("File name prefix.")
    @Default.String("bindiego")
    ValueProvider<String> getFilenamePrefix();
    void setFilenamePrefix(ValueProvider<String> value);

    @Description("CSV File name suffix.")
    @Default.String(".csv")
    ValueProvider<String> getCsvFilenameSuffix();
    void setCsvFilenameSuffix(ValueProvider<String> value);

    @Description("Avro File name suffix.")
    @Default.String(".avro")
    ValueProvider<String> getAvroFilenameSuffix();
    void setAvroFilenameSuffix(ValueProvider<String> value);

    @Default.String("W-P-SS-of-NN")
    ValueProvider<String> getOutputShardTemplate();
    void setOutputShardTemplate(ValueProvider<String> value);

    @Description("The maximum number of output shards produced when writing.")
    @Default.Integer(1)
    Integer getNumShards();
    void setNumShards(Integer value);

    @Description("Output window size.")
    @Default.String("5m")
    String getWindowSize();
    void setWindowSize(String value);

    @Description("Allowed late data for a window")
    @Default.String("5m")
    String getAllowedLateness();
    void setAllowedLateness(String value);

    @Description("Early firing period")
    @Default.String("1m")
    String getEarlyFiringPeriod();
    void setEarlyFiringPeriod(String value);

    @Description("Late firing count")
    @Default.String("1")
    Integer getLateFiringCount();
    void setLateFiringCount(Integer value);

    @Description("CSV file delimiter.")
    @Default.String(",")
    String getCsvDelimiter();
    void setCsvDelimiter(String value);

    @Description("JSON file with BigQuery Schema description, GCS path")
    @Required
    ValueProvider<String> getBqSchema();
    void setBqSchema(ValueProvider<String> value);

    @Description("BigQuery output table to write to")
    @Required
    ValueProvider<String> getBqOutputTable();
    void setBqOutputTable(ValueProvider<String> value);

    @Description("GCS temp location for BigQuery")
    @Required
    ValueProvider<String> getGcsTempLocation();
    void setGcsTempLocation(ValueProvider<String> value);

    @Description("Avro schema file")
    @Required
    ValueProvider<String> getAvroSchema();
    void setAvroSchema(ValueProvider<String> value);

    @Description("PubsubMessage ID attribute.")
    @Default.String("id")
    String getMessageIdAttr();
    void setMessageIdAttr(String value);

    @Description("PubsubMessage timestamp attribute.")
    @Default.String("timestamp")
    String getMessageTsAttr();
    void setMessageTsAttr(String value);

    @Description("Bigtable Instance Id")
    @Required
    String getBtInstanceId();
    void setBtInstanceId(String value);

    @Description("Bigtable Table Id to write realtime data to, tall table")
    @Required
    String getBtTableIdTall();
    void setBtTableIdTall(String value);

    @Description("Bigtable Table Id to write realtime data to, wide table")
    @Required
    String getBtTableIdWide();
    void setBtTableIdWide(String value);

    @Description("JDBC class")
    @Required
    String getJdbcClass();
    void setJdbcClass(String value);

    @Description("JDBC connection string")
    @Required
    String getJdbcConn();
    void setJdbcConn(String value);

    @Description("JDBC connections username")
    @Required
    String getJdbcUsername();
    void setJdbcUsername(String value);

    @Description("JDBC connection password")
    @Required
    String getJdbcPassword();
    void setJdbcPassword(String value);

    @Description("Elasticsearch host, usually a LB for coordinating nodes. e.g. https://es.ingest.abc.com")
    @Required
    String getEsHost();
    void setEsHost(String value);

    @Description("Elasticsearch user")
    @Required
    String getEsUser();
    void setEsUser(String value);

    @Description("Elasticsearch password")
    @Required
    String getEsPass();
    void setEsPass(String value);

    @Description("Elasticsearch index, usually an alias for index lifecycle management")
    @Required
    String getEsIndex();
    void setEsIndex(String value);

    @Description("Elasticsearch Rest client max batch size")
    @Default.Long(1000L)
    Long getEsMaxBatchSize();
    void setEsMaxBatchSize(Long value);

    @Description("Elasticsearch Rest client max batch bytes")
    @Default.Long(5L * 1024L * 1024L)
    Long getEsMaxBatchBytes();
    void setEsMaxBatchBytes(Long value);

    @Description("Elasticsearch Rest client threads")
    @Default.Integer(1)
    Integer getEsNumThread();
    void setEsNumThread(Integer value);

    @Description("Elasticsearch Rest client threads")
    @Default.Boolean(false)
    Boolean getEsIsIgnoreInsecureSSL();
    void setEsIsIgnoreInsecureSSL(Boolean value);

    @Description("Basic mode, will let you run this Firebase fundamental pipeline in one shot with real time events data ingestion to BQ only")
    @Default.Boolean(false)
    Boolean getIsBasic();
    void setIsBasic(Boolean value);
}
