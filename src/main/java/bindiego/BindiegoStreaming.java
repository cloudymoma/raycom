package bindiego;

import java.nio.charset.StandardCharsets;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.FileBasedSink;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
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
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;

import org.joda.time.Duration;

import bindiego.io.WindowedFilenamePolicy;
import bindiego.utils.DurationUtils;

public class BindiegoStreaming {
    /* extract the csv payload from message */
    public static class ExtractPayload extends DoFn<PubsubMessage, String> {
        @ProcessElement
        public void processElement(ProcessContext ctx, MultiOutputReceiver r) 
                throws IllegalArgumentException {

            String str = null;

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

    static void run(BindiegoStreamingOptions options) {
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
    }

    public static void main(String... args) {
        BindiegoStreamingOptions options = PipelineOptionsFactory
            .fromArgs(args).withValidation().as(BindiegoStreamingOptions.class);
        options.setStreaming(true);

        run(options);
    }

    /* tag for main output when extracting pubsub message payload*/
    private static final TupleTag<String> STR_OUT = 
        new TupleTag<String>() {};
    /* tag for failure output from the UDF */
    private static final TupleTag<String> STR_FAILURE_OUT = 
        new TupleTag<String>() {};
}
