package bindiego;

import java.nio.charset.StandardCharsets;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
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
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;

import org.joda.time.Duration;

import bindiego.utils.DurationUtils;

public class BindiegoStreaming {
    /* extract the csv payload from message */
    public static class ExtractPayload extends DoFn<PubsubMessage, String> {
        @ProcessElement
        public void processElement(ProcessContext ctx) throws IllegalArgumentException {
            PubsubMessage psmsg = ctx.element();
            
            ctx.output(new String(psmsg.getPayload(), StandardCharsets.UTF_8));
        }
    }

    static void run(BindiegoStreamingOptions options) {
        Pipeline p = Pipeline.create(options);

        p.apply("Read Pubsub Events", 
                PubsubIO.readMessagesWithAttributesAndMessageId()
                    .fromSubscription(options.getSubscription()))
            .apply("Extract CSV payload from pubsub message",
                ParDo.of(new ExtractPayload()))
            .apply(Window.into(FixedWindows.of(DurationUtils.parseDuration(options.getWindowSize()))))
            .apply("Write windowd CSV files", 
                TextIO.write()
                    .to(options.getOutputDir())
                    .withNumShards(options.getNumShards())
                    .withWindowedWrites());

        p.run();
        //p.run().waitUntilFinish();
    }

    public static interface BindiegoStreamingOptions extends PipelineOptions, StreamingOptions {
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
}
