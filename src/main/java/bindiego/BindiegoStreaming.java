package bindiego;

import  bindiego.BindiegoStreamingOptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.sql.*;
import java.net.URL;

// Import SLF4J packages.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import org.apache.beam.sdk.transforms.windowing.AfterEach;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.Window.ClosingBehavior;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
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
// import org.codehaus.jackson.map.ObjectMapper;

import org.apache.commons.io.FilenameUtils;

import bindiego.io.WindowedFilenamePolicy;
import bindiego.utils.DurationUtils;
import bindiego.utils.SchemaParser;
import bindiego.io.ElasticsearchIO;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class BindiegoStreaming {
    /* extract the csv payload from message */
    public static class ExtractPayload extends DoFn<PubsubMessage, String> {

        public ExtractPayload() {}

        @Setup 
        public void setup() {
            mapper = new ObjectMapper();
        }

        @ProcessElement
        public void processElement(ProcessContext ctx, MultiOutputReceiver r) 
                throws IllegalArgumentException {

            String payload = null;

            // TODO: data validation here to prevent later various outputs inconsistency
            try {
                PubsubMessage psmsg = ctx.element();
                // GLB json data
                payload = new String(psmsg.getPayload(), StandardCharsets.UTF_8);

                logger.debug("Extracted raw message: " + payload);

                // JsonNode json = mapper.readValue(payload, JsonNode.class);
                JsonNode json = mapper.readTree(payload);
                ObjectNode jsonRoot = (ObjectNode) json;

                // Optional: add timestamp in Elasticsearch's native tongue
                jsonRoot.put("@timestamp", jsonRoot.get("timestamp").asText());
                jsonRoot.remove("timestamp");

                // Extract host & protocol from URL
                URL url = new URL(jsonRoot.get("httpRequest").get("requestUrl").asText());
                ((ObjectNode) jsonRoot.get("httpRequest")).put("requestDomain", url.getHost());
                ((ObjectNode) jsonRoot.get("httpRequest")).put("requestProtocol", url.getProtocol());

                // REVISIT: quick impl, not ideal but works
                // Extract resource type from URL, e.g. .txt .m4s, .m3u8, .ts, .tar.gz, .js, .html etc.
                int cutoffLength = 6;
                String urlStr = jsonRoot.get("httpRequest").get("requestUrl").asText();
                int urlLength = urlStr.length();
                if (cutoffLength < urlLength) {
                    String partialUrl = urlStr.substring(urlLength - cutoffLength);

                    if (partialUrl.contains(".")) {
                        ((ObjectNode) jsonRoot.get("httpRequest"))
                            .put("resourceType", FilenameUtils.getExtension(partialUrl));
                    }
                }

                // Extract the backend latency, latency between GFE_layer1 and origin
                String latency = new String(jsonRoot.get("httpRequest").get("latency").asText());
                Double backendLatency = Double.valueOf(latency.substring(0, latency.length() - 1));
                ((ObjectNode) jsonRoot.get("httpRequest"))
                    .put("backendLatency", backendLatency);
                ((ObjectNode) jsonRoot.get("httpRequest")).remove("latency");

                // backednLatency2, latency between GFE_layer2 and origin
                Double backendLatency2 = null;
                if (null != jsonRoot.get("jsonPayload").get("backendLatency")) {
                    String latency2 = new String(jsonRoot.get("jsonPayload").get("backendLatency").asText());
                    backendLatency2 = Double.valueOf(latency2.substring(0, latency2.length() - 1));
                    ((ObjectNode) jsonRoot.get("httpRequest"))
                        .put("backendLatency2", backendLatency2);
                    ((ObjectNode) jsonRoot.get("jsonPayload")).remove("backendLatency");

                    // calculate latency between GFE_layer1 and GFE_layer2
                    ((ObjectNode) jsonRoot.get("httpRequest"))
                        .put("gfeLatency", (backendLatency - backendLatency2));
                }

                // Frontend SRTT, latency between client and GFE_layer1
                Double feSrtt = null;
                if (null != jsonRoot.get("jsonPayload").get("frontendSrtt")) {
                    String feSrttStr = new String(jsonRoot.get("jsonPayload").get("frontendSrtt").asText());
                    feSrtt = Double.valueOf(feSrttStr.substring(0, feSrttStr.length() - 1));
                    ((ObjectNode) jsonRoot.get("httpRequest"))
                        .put("frontendSrtt", feSrtt);
                    ((ObjectNode) jsonRoot.get("jsonPayload")).remove("frontendSrtt");
                }

                // Get CachedID / Pop location ISO3166-1 3-letter city code
                if (null != jsonRoot.get("jsonPayload").get("cacheId")) {
                    // REVISIT: test string length
                    String cachedIdCityCode = new String(jsonRoot.get("jsonPayload").get("cacheId").asText())
                        .substring(0, 3);
                    ((ObjectNode) jsonRoot.get("jsonPayload"))
                        .put("cacheIdCityCode", cachedIdCityCode);
                }

                r.get(STR_OUT).output(mapper.writeValueAsString(json));

                // use this only if the element doesn't have an event timestamp attached to it
                // e.g. extract 'extractedTs' from psmsg.split(",")[0] from a CSV payload
                // r.get(STR_OUT).outputWithTimestamp(str, extractedTs);
            } catch (Exception ex) {
                if (null == payload)
                    payload = "Failed to extract pubsub payload";

                r.get(STR_FAILURE_OUT).output(payload);

                logger.error("Failed extract pubsub message", ex);
            }
        }

        private ObjectMapper mapper;
    }

    static void run(BindiegoStreamingOptions options) throws Exception {
        // FileSystems.setDefaultPipelineOptions(options);

        Pipeline p = Pipeline.create(options);

        /* Raw data processing */
        PCollection<PubsubMessage> messages = p.apply("Read Pubsub Events", 
            PubsubIO.readMessages()
                .fromSubscription(options.getSubscription()));

        PCollectionTuple processedData = messages.apply("GCLB logs ETL",
            ParDo.of(new ExtractPayload())
                .withOutputTags(STR_OUT, TupleTagList.of(STR_FAILURE_OUT)));

        // REVISIT: we may apply differnet window for error data?
        PCollection<String> errData = processedData.get(STR_FAILURE_OUT)
            .apply(options.getWindowSize() + " window for error data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize()))));

        /* Elasticsearch */
        processedData.get(STR_OUT)
            .apply(options.getWindowSize() + " window for healthy data",
                Window.<String>into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize())))
                    .triggering(
                        AfterWatermark.pastEndOfWindow()
                            .withEarlyFirings(
                                AfterProcessingTime
                                    .pastFirstElementInPane() 
                                    .plusDelayOf(DurationUtils.parseDuration(options.getEarlyFiringPeriod())))
                            .withLateFirings(
                                AfterPane.elementCountAtLeast(
                                    options.getLateFiringCount().intValue()))
                    )
                    .discardingFiredPanes() // e.g. .accumulatingFiredPanes() etc.
                    .withAllowedLateness(DurationUtils.parseDuration(options.getAllowedLateness()),
                        ClosingBehavior.FIRE_IF_NON_EMPTY))
            .apply("Append data to Elasticsearch",
                ElasticsearchIO.append()
                    .withMaxBatchSize(options.getEsMaxBatchSize())
                    .withMaxBatchSizeBytes(options.getEsMaxBatchBytes())
                    .withConnectionConf(
                        ElasticsearchIO.ConnectionConf.create(
                            options.getEsHost(),
                            options.getEsIndex())
                                .withUsername(options.getEsUser())
                                .withPassword(options.getEsPass())
                                .withNumThread(options.getEsNumThread()))
                                //.withTrustSelfSignedCerts(true)) // false by default
                    .withRetryConf(
                        ElasticsearchIO.RetryConf.create(6, Duration.standardSeconds(60))));
        /* END - Elasticsearch */

/*
        healthData.apply("Write windowed healthy CSV files", 
            TextIO.write()
                .withNumShards(options.getNumShards())
                .withWindowedWrites()
                .to(
                    new WindowedFilenamePolicy(
                        options.getOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getLogFilenameSuffix()
                    ))
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())));
*/

        errData.apply("Write windowed error data", 
            TextIO.write()
                .withNumShards(options.getNumShards())
                .withWindowedWrites()
                .to(
                    new WindowedFilenamePolicy(
                        options.getErrOutputDir(),
                        options.getFilenamePrefix(),
                        options.getOutputShardTemplate(),
                        options.getLogFilenameSuffix()
                    ))
                .withTempDirectory(
                    FileBasedSink.convertToFileResourceIfPossible(options.getTempLocation())));

        p.run();
        //p.run().waitUntilFinish();
    }


    public static void main(String... args) {
        PipelineOptionsFactory.register(BindiegoStreamingOptions.class);

        BindiegoStreamingOptions options = PipelineOptionsFactory
            .fromArgs(args)
            .withValidation()
            .as(BindiegoStreamingOptions.class);
        options.setStreaming(true);
        // options.setRunner(DataflowRunner.class);
        // options.setNumWorkers(2);
        // options.setUsePublicIps(true);
        
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
}
