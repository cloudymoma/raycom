package bindiego;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.Clustering;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TimePartitioning;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.InsertRetryPolicy;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.FileBasedSink;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.NestedValueProvider;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.DoFn.MultiOutputReceiver;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import org.joda.time.Duration;

import bindiego.io.WindowedFilenamePolicy;
import bindiego.utils.DurationUtils;
import bindiego.utils.SchemaParser;

public class BindiegoStreaming {
    /* extract the csv payload from message */
    public static class ExtractPayload extends DoFn<PubsubMessage, String> {
        @ProcessElement
        public void processElement(ProcessContext ctx, MultiOutputReceiver r) 
                throws IllegalArgumentException {

            String str = null;

            // TODO: data validation here to prevent later various outputs inconsistent
            try {
                PubsubMessage psmsg = ctx.element();
                str = new String(psmsg.getPayload(), StandardCharsets.UTF_8);

                r.get(STR_OUT).output(str);
            } catch (Exception ex) {
                if (null == str)
                    str = "AUTO_MSG failed to extract message";
                r.get(STR_FAILURE_OUT).output(str);
            }
        }
    }

    /* Convert Csv to Avro */
    public static class ConvertCsvToAvro extends DoFn<String, GenericRecord> {
        public ConvertCsvToAvro(String schemaJson, String delimiter) {
            this.schemaJson = schemaJson;
            this.delimiter = delimiter;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx) throws IllegalArgumentException {
            String[] csvData = ctx.element().split(delimiter);
           
            Schema schema = new Schema.Parser().parse(schemaJson);

            // Create Avro Generic Record
            GenericRecord genericRecord = new GenericData.Record(schema);
            List<Schema.Field> fields = schema.getFields();

            for (int index = 0; index < fields.size(); ++index) {
                Schema.Field field = fields.get(index);
                String fieldType = field.schema().getType().getName().toLowerCase();

                // REVISIT: suprise, java can switch string :)
                switch (fieldType) {
                    case "string":
                        genericRecord.put(field.name(), csvData[index]);
                        break;
                    case "boolean":
                        genericRecord.put(field.name(), Boolean.valueOf(csvData[index]));
                        break;
                    case "int":
                        genericRecord.put(field.name(), Integer.valueOf(csvData[index]));
                        break;
                    case "long":
                        genericRecord.put(field.name(), Long.valueOf(csvData[index]));
                        break;
                    case "float":
                        genericRecord.put(field.name(), Float.valueOf(csvData[index]));
                        break;
                    case "double":
                        genericRecord.put(field.name(), Double.valueOf(csvData[index]));
                        break;
                    default:
                        throw new IllegalArgumentException("Field type " 
                            + fieldType + " is not supported.");
                }
            }

            ctx.output(genericRecord);
        }

        private String schemaJson;
        private String delimiter;
    }

    static void run(BindiegoStreamingOptions options) throws Exception {
        // FileSystems.setDefaultPipelineOptions(options);

        Pipeline p = Pipeline.create(options);

        PCollection<PubsubMessage> messages = p.apply("Read Pubsub Events", 
            PubsubIO.readMessagesWithAttributesAndMessageId()
                .fromSubscription(options.getSubscription()));

        PCollectionTuple processedData = messages.apply("Extract CSV payload from pubsub message",
            ParDo.of(new ExtractPayload())
                .withOutputTags(STR_OUT, TupleTagList.of(STR_FAILURE_OUT)));

        PCollection<String> healthData = processedData.get(STR_OUT)
            .apply(options.getWindowSize() + " window for healthy data",
                Window.into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize()))));

        // REVISIT: we may apply differnet window for error data?
        PCollection<String> errData = processedData.get(STR_FAILURE_OUT)
            .apply(options.getWindowSize() + " window for error data",
                Window.into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize()))));

        healthData.apply("Write windowed healthy CSV files", 
            TextIO.write()
                .withNumShards(options.getNumShards())
                .withWindowedWrites()
                .to(
                    new WindowedFilenamePolicy(
                        options.getOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getCsvFilenameSuffix()
                    ))
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())));

        healthData.apply("Prepare table data for BigQuery",
            ParDo.of(
                new DoFn<String, TableRow>() {
                    @ProcessElement
                    public void processElement(ProcessContext ctx) {
                        String dataStr = ctx.element();

                        // REVISIT: damn ugly here, hard coded table schema
                        String headers = "ts,thread_id,thread_name,seq";
                        String[] cols = headers.split(",");

                        // REFISIT: options is NOT serializable, make a class for this transform
                        // String[] csvData = dataStr.split(options.getCsvDelimiter()); 
                        String[] csvData = dataStr.split(","); 

                        TableRow row = new TableRow();

                        // for god sake safety purpose
                        int loopCtr = 
                            cols.length <= csvData.length ? cols.length : csvData.length;
                        for (int i = 0; i < loopCtr; ++i) {
                            switch (i) {
                                case 0:
                                    row.set(cols[i], Long.parseLong(csvData[i])/1000);
                                    // row.set(cols[i], Integer.parseInt(csvData[i]));
                                    break;
                                case 3:
                                    row.set(cols[i], Integer.parseInt(csvData[i]));
                                    break;
                                default:
                                    row.set(cols[i], csvData[i]);
                            }
                        } // End of dirty code

                        ctx.output(row);
                    }
                }
            ))
            .apply("Insert into BigQuery",
                BigQueryIO.writeTableRows()
                    .withSchema(
                        NestedValueProvider.of(
                            options.getBqSchema(),
                            new SerializableFunction<String, TableSchema>() {
                                @Override
                                public TableSchema apply(String jsonPath) {
                                    TableSchema tableSchema = new TableSchema();
                                    List<TableFieldSchema> fields = new ArrayList<>();
                                    SchemaParser schemaParser = new SchemaParser();
                                    JSONObject jsonSchema;

                                    try {
                                        jsonSchema = schemaParser.parseSchema(jsonPath);

                                        JSONArray bqSchemaJsonArray =
                                            jsonSchema.getJSONArray(BIGQUERY_SCHEMA);

                                        for (int i = 0; i < bqSchemaJsonArray.length(); i++) {
                                            JSONObject inputField = bqSchemaJsonArray.getJSONObject(i);
                                            TableFieldSchema field =
                                                new TableFieldSchema()
                                                    .setName(inputField.getString(NAME))
                                                    .setType(inputField.getString(TYPE));
                                            if (inputField.has(MODE)) {
                                                field.setMode(inputField.getString(MODE));
                                            }

                                            fields.add(field);
                                        }
                                        tableSchema.setFields(fields);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                    return tableSchema;
                                }
                            }))
                        .withTimePartitioning(
                            new TimePartitioning().setField("ts")
                                .setType("DAY")
                                .setExpirationMs(null)
                        )
                        .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
                        .withWriteDisposition(WriteDisposition.WRITE_APPEND)
                        .to(options.getBqOutputTable())
                        .withMethod(BigQueryIO.Write.Method.STREAMING_INSERTS)
                        .withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors())
                        .withCustomGcsTempLocation(options.getGcsTempLocation()));

        // Assume dealing with CSV payload, so basically convert CSV to Avro
        SchemaParser schemaParser = new SchemaParser();
        String avroSchemaJson = schemaParser.getAvroSchema(options.getAvroSchema().get());
        Schema avroSchema = new Schema.Parser().parse(avroSchemaJson);

        healthData.apply("Prepare Avro data",
                ParDo.of(new ConvertCsvToAvro(avroSchemaJson, options.getCsvDelimiter())))
            .setCoder(AvroCoder.of(GenericRecord.class, avroSchema))
            // .apply("Write Avro formatted data", AvroIO.writeGenericRecords(avroSchemaJson)
            .apply("Write Avro formatted data", AvroIO.writeGenericRecords(avroSchema)
                .to(
                    new WindowedFilenamePolicy(
                        options.getOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getAvroFilenameSuffix()
                    ))
                .withWindowedWrites()
                .withNumShards(options.getNumShards())
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())
                )
                .withCodec(CodecFactory.snappyCodec()));
        /*
                .withTempDirectory(NestedValueProvider.of(
                    options.getGcsTempLocation(),
                    (SerializableFunction<String, ResourceId>) input ->
                        FileBasedSink.convertToFileResourceIfPossible(input)
                ))
        */

        errData.apply("Write windowed error data in CSV format", 
            TextIO.write()
                .withNumShards(options.getNumShards())
                .withWindowedWrites()
                .to(
                    new WindowedFilenamePolicy(
                        options.getErrOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getCsvFilenameSuffix()
                    ))
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())));

        p.run();
        //p.run().waitUntilFinish();
    }

    public static interface BindiegoStreamingOptions 
            extends PipelineOptions, StreamingOptions {
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

        @Description("Output file's window size.")
        @Default.String("5m")
        String getWindowSize();
        void setWindowSize(String value);

        @Description("CSV file delimiter.")
        @Default.String(",")
        String getCsvDelimiter();
        void setCsvDelimiter(String value);

        @Description("JSON file with BigQuery Schema description")
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
    }

    public static void main(String... args) {
        BindiegoStreamingOptions options = PipelineOptionsFactory
            .fromArgs(args).withValidation().as(BindiegoStreamingOptions.class);
        options.setStreaming(true);

        try {
            run(options);
        } catch (Exception ex) {
            System.err.println(ex);
            ex.printStackTrace();
        }
    }

    /* tag for main output when extracting pubsub message payload*/
    private static final TupleTag<String> STR_OUT = 
        new TupleTag<String>() {};
    /* tag for failure output from the UDF */
    private static final TupleTag<String> STR_FAILURE_OUT = 
        new TupleTag<String>() {};

    private static final String BIGQUERY_SCHEMA = "BigQuery Schema";
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String MODE = "mode";
}
