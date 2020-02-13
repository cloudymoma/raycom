package bindiego;

import  bindiego.BindiegoStreamingOptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.sql.*;

// Import SLF4J packages.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import org.apache.beam.sdk.io.jdbc.JdbcIO;
import org.apache.beam.sdk.io.GenerateSequence;
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
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.Min;
import org.apache.beam.sdk.transforms.Max;
import org.apache.beam.sdk.transforms.Mean;
import org.apache.beam.sdk.transforms.Latest;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.DoFn.MultiOutputReceiver;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.AfterEach;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.Window.ClosingBehavior;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.ToString;
import org.apache.beam.sdk.transforms.WithTimestamps;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import org.joda.time.Duration;
import org.joda.time.Instant;

import bindiego.io.WindowedFilenamePolicy;
import bindiego.utils.DurationUtils;
import bindiego.utils.SchemaParser;

public class BindiegoStreaming {
    /* extract the csv payload from message */
    public static class ExtractPayload extends DoFn<PubsubMessage, String> {

        public ExtractPayload(final PCollectionView<Map<String, String>> lookupTable) {
            this.lookupTable = lookupTable;
        }

        @ProcessElement
        public void processElement(ProcessContext ctx, MultiOutputReceiver r) 
                throws IllegalArgumentException {

            String str = null;

            Map<String, String> dimTable = ctx.sideInput(lookupTable);

            // TODO: data validation here to prevent later various outputs inconsistency
            try {
                PubsubMessage psmsg = ctx.element();
                // CSV format
                // raw:   "event_ts,thread_id,thread_name,seq,dim1,metrics1"
                // after: "event_ts,thread_id,thread_name,seq,dim1,metrics1,process_ts,dim1_val"
                str = new String(psmsg.getPayload(), StandardCharsets.UTF_8)
                    + "," // hardcoded csv delimiter
                    + Long.valueOf(System.currentTimeMillis()).toString(); // append process timestamp

                // dimemsion table lookup to complement dim value
                String dimVal = dimTable.get(str.split(",")[4]);
                dimVal = dimVal == null ? "Not Found" : dimVal;

                str += ("," + dimVal);

                logger.debug("Extracted raw message: " + str);

                r.get(STR_OUT).output(str);

                // use this only if the element doesn't have an event timestamp attached to it
                // e.g. extract 'extractedTs' from psmsg.split(",")[0] from a CSV payload
                // r.get(STR_OUT).outputWithTimestamp(str, extractedTs);
            } catch (Exception ex) {
                if (null == str)
                    str = "AUTO_MSG failed to extract message";
                r.get(STR_FAILURE_OUT).output(str);

                logger.error("Failed extract pubsub message", ex);
            }
        }

        private final PCollectionView<Map<String, String>> lookupTable;
    }

    /* add timestamp for PCollection<T> data
     *
     * this implementation suppose CSV and stampstamp as 1st column
     */
    private static class SetTimestamp implements SerializableFunction<String, Instant> {
        @Override
        public Instant apply(String input) {
            String[] components = input.split(","); // assume CSV
            try {
                return new Instant(Long.parseLong(components[0].trim())); // assume 1st column is timestamp
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                return Instant.now();
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

    // Read JDBC lookup table
    // Why: Above service will return a PCollection, result the caller to produce a PCollection<PCollection<...>>
    //      when do p.apply(...)
    //
    // REVISIT: cheap & nasty only for demo purpose
    public static class BindiegoJdbcServiceExternal {
        public static Map<String, String> read(final String jdbcClass, final String jdbcConn,
                final String jdbcUsername, final String jdbcPassword) {
            Map<String, String> dict = new HashMap<>();
            Connection conn = null;

            try {
                Class.forName(jdbcClass);
                conn = DriverManager.getConnection(
                    jdbcConn, jdbcUsername, jdbcPassword);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("select dim1, dim1_val from t_dim1;");

                while(rs.next()) {
                    dict.put(rs.getString(1), rs.getString(2));
                }

                dict.forEach((k, v) ->
                    logger.debug("bindiego from mysql: key = " + k + " value = " + v));
            } catch (Exception ex) {
                logger.error(ex.getMessage(), ex);
            } finally {
                // if (conn != null)
                try {
                    conn.close();
                } catch (Exception ex) {
                    logger.warn(ex.getMessage(), ex);
                }
            }

            /*
            Instant now = Instant.now();
            org.joda.time.format.DateTimeFormatter dtf = 
                org.joda.time.format.DateTimeFormat.forPattern("HH:MM:SS");

            dict.put("bindiego_A", now.minus(Duration.standardSeconds(30)).toString(dtf));
            dict.put("bindiego_B", now.minus(Duration.standardSeconds(30)).toString());
            */

            return dict;
        }
    }

    static void run(BindiegoStreamingOptions options) throws Exception {
        // FileSystems.setDefaultPipelineOptions(options);

        Pipeline p = Pipeline.create(options);

        // Create a side input as a lookup table in order to enrich the input data
        // Long is NOT infinite, but should be fine mostly :-)
        /* REVISIT: This does NOT work, when updated, consumer's corresponding window will
         * receive another view, so asSingleton() will not work. Multimap may double the RAM
         * usage which is not a viable solution IMO. I would recommend wait for retraction or
         * simply update the pipeline.
         */
        /*
        PCollectionView<Map<String, String>> lookupTable =
            p.apply("Trigger for updating the side input (lookup table)", 
                GenerateSequence.from(0).withRate(1, Duration.standardSeconds(20L)))// FIXME: hardcoded
            .apply(
                Window.<Long>into(new GlobalWindows())
                    .triggering(Repeatedly.forever(AfterProcessingTime.pastFirstElementInPane()))
                    .discardingFiredPanes())
                    // .accumulatingAndRetractingFiredPanes())
            .apply(
                ParDo.of(
                    new DoFn<Long, Map<String, String>>() {
                        @ProcessElement
                        public void process(
                            @Element Long input,
                            PipelineOptions po, 
                            OutputReceiver<Map<String, String>> o) {
                                BindiegoStreamingOptions op = po.as(BindiegoStreamingOptions.class);
                                o.output(BindiegoJdbcServiceExternal.read(
                                    op.getJdbcClass(),
                                    op.getJdbcConn(),
                                    op.getJdbcUsername(),
                                    op.getJdbcPassword()
                                ));
                        }
                    })
            )
            .apply(Latest.<Map<String, String>>globally())
            .apply(View.asSingleton());
        */

        /*
         * Debug code for JDBC data refresh
         */
        /*
        p.apply(GenerateSequence.from(0).withRate(1, Duration.standardSeconds(2L)))
            .apply(Window.into(FixedWindows.of(Duration.standardSeconds(10))))
            .apply(Min.longsGlobally().withoutDefaults())
            .apply(
                ParDo.of(
                    new DoFn<Long, KV<Long, Long>>() {
                        @ProcessElement
                        public void process(ProcessContext c) {
                            Map<String, String> kv = c.sideInput(lookupTable);
                            c.outputWithTimestamp(KV.of(1L, c.element()), Instant.now());

                            logger.debug("bindiego consumer - lookup table size: " + kv.size());
                            kv.forEach((k, v) ->
                                logger.debug("bindiego cnsumer - Key = " + k + ", Value = " + v));
                        }
                    }
                ).withSideInputs(lookupTable)
            );
        */

        // Read JDBC lookup table
        final PCollectionView<Map<String, String>> lookupTable = 
            p.apply("Read lookup table from JDBC data source", 
                    JdbcIO.<KV<String, String>>read()
                // .withDataSourceConfiguration(
                .withDataSourceProviderFn(JdbcIO.PoolableDataSourceProvider.of(
                    JdbcIO.DataSourceConfiguration.create(
                        options.getJdbcClass(), options.getJdbcConn())
                    .withUsername(options.getJdbcUsername())
                    .withPassword(options.getJdbcPassword())))
                .withQuery("select dim1, dim1_val from t_dim1;") // FIXME: hardcoded
                .withCoder(KvCoder.of(StringUtf8Coder.of(),
                    StringUtf8Coder.of())) // e.g. BigEndianIntegerCoder.of() for Integer
                .withRowMapper(new JdbcIO.RowMapper<KV<String, String>>() {
                    public KV<String, String> mapRow(ResultSet resultSet) throws Exception {
                        return KV.of(resultSet.getString(1), resultSet.getString(2)); // e.g. resultSet.getInt(1)
                    }
                })
            )
            .apply(View.<String, String>asMap());

        PCollection<PubsubMessage> messages = p.apply("Read Pubsub Events", 
            PubsubIO.readMessagesWithAttributesAndMessageId()
                .withIdAttribute(options.getMessageIdAttr())
                // set event time from a message attribute, milliseconds since the Unix epoch
                .withTimestampAttribute(options.getMessageTsAttr())
                .fromSubscription(options.getSubscription()));

        PCollectionTuple processedData = messages.apply("Extract CSV payload from pubsub message",
            ParDo.of(new ExtractPayload(lookupTable))
                .withOutputTags(STR_OUT, TupleTagList.of(STR_FAILURE_OUT))
                .withSideInputs(lookupTable));
            // this usually used with TextIO 
            // .apply("Set event timestamp value", WithTimestamps.of(new SetTimestamp())); 

        /* A terse approach */
        PCollection<String> healthData = processedData.get(STR_OUT)
            .apply(options.getWindowSize() + " window for healthy data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        // Repeatedly.forever(AfterWatermark.pastEndOfWindow()
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(AfterProcessingTime.pastFirstElementInPane() 
                                .plusDelayOf(DurationUtils.parseDuration(options.getEarlyFiringPeriod())))
                            .withLateFirings(AfterPane.elementCountAtLeast(
                                options.getLateFiringCount().intValue()))
                        // )
                    )
                    .discardingFiredPanes() // e.g. .accumulatingAndRetractingFiredPanes() etc.
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY));

        /* 
         * @desc Use a composite trigger
         * - triggering every early firing period of processing time
         * - util watermark passes
         * - then triggering any time a late datum arrives
         * - up to a garbage collection horizon of allowed lateness of event time
         * - all with accumulation strategy turned on that specified in code
         */
        /*
        PCollection<String> healthData = processedData.get(STR_OUT)
            .apply(options.getWindowSize() + " window for healthy data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        AfterEach.inOrder(
                            Repeatedly.forever( 
                                AfterProcessingTime.pastFirstElementInPane() 
                                    .alignedTo(DurationUtils.parseDuration(
                                        options.getEarlyFiringPeriod())))
                                .orFinally(AfterWatermark.pastEndOfWindow()),
                            Repeatedly.forever(
                                AfterPane.elementCountAtLeast(
                                    options.getLateFiringCount().intValue()))
                        )
                        //.orFinally(AfterWatermark.pastEndOfWindow()
                        //    .plusDelayOf(DurationUtils.parseDuration(options.getAllowedLateness())))
                    )
                    .discardingFiredPanes()
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY));
        */

        // REVISIT: we may apply differnet window for error data?
        PCollection<String> errData = processedData.get(STR_FAILURE_OUT)
            .apply(options.getWindowSize() + " window for error data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize()))));

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
                        String headers = "event_ts,thread_id,thread_name,seq,dim1,metrics1,process_ts,dim1_val";
                        String[] cols = headers.split(",");

                        // REFISIT: options is NOT serializable, make a class for this transform
                        // String[] csvData = dataStr.split(options.getCsvDelimiter()); 
                        String[] csvData = dataStr.split(","); 

                        TableRow row = new TableRow();

                        // for god sake safety purpose
                        int loopCtr = 
                            cols.length <= csvData.length ? cols.length : csvData.length;

                        for (int i = 0; i < loopCtr; ++i) {
                            // deal with non-string field in BQ
                            switch (i) {
                                case 0:
                                    row.set(cols[i], Long.parseLong(csvData[i])/1000);
                                    // row.set(cols[i], Integer.parseInt(csvData[i]));
                                    break;
                                case 3:
                                    row.set(cols[i], Integer.parseInt(csvData[i]));
                                    break;
                                case 5:
                                    row.set(cols[i], Integer.parseInt(csvData[i]));
                                    break;
                                case 6:
                                    row.set(cols[i], Long.parseLong(csvData[i])/1000);
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
                            new TimePartitioning().setField("event_ts")
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


    public static void main(String... args) {
        BindiegoStreamingOptions options = PipelineOptionsFactory
            .fromArgs(args).withValidation().as(BindiegoStreamingOptions.class);
        options.setStreaming(true);

        try {
            run(options);
        } catch (Exception ex) {
            //System.err.println(ex);
            //ex.printStackTrace();
            logger.error(ex.getMessage(), ex);
        }
    }

    // Instantiate Logger
    private static final Logger logger = LoggerFactory.getLogger(BindiegoStreaming.class);

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
