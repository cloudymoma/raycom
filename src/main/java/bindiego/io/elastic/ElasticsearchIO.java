package bindiego.io.elastic;

import com.google.auto.value.AutoValue;

import javax.annotation.Nullable;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkState;

import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;

/**
 * A very simple Elasticsearch Sink Append only wrapper.
 *
 * For Elasticsearch Reading and Upsert (by ID), you may need
 * Option 1: @see org.apache.beam.sdk.io.elasticsearch.ElasticsearchIO
 * Option 2: @see org.elasticsearch.client.RestClient or
 *           @see org.elasticsearch.client.RestHighLevelClient
 */
@Experimental(Kind.SOURCE_SINK)
public class ElasticsearchIO {
    private ElasticsearchIO() {} // disable new

    public static Append append() {
        return new AutoValue_ElasticsearchIO_Append.Builder()
            .setMaxBatchSize(1000L)
            .setMaxBatchSizeBytes(5L * 1024L * 1024L)
            .build();
    }

    @AutoValue
    public abstract static class Append extends PTransform<PCollection<String>, PDone> {

        @Nullable
        abstract ConnectionConf getConnectionConf();

        @Nullable
        abstract RetryConf getRetryConf();

        abstract long getMaxBatchSize();

        abstract long getMaxBatchSizeBytes();

        abstract Builder builder();

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder setConnectionConf(ConnectionConf connectionConf);

            abstract Builder setRetryConf(RetryConf retryConf);

            abstract Builder setMaxBatchSize(long maxBatchSize);

            abstract Builder setMaxBatchSizeBytes(long maxBatchSizeBytes);

            abstract Append build();
        }

        public Append withConnectionConf(ConnectionConf connectionConf) {
            checkArgument(connectionConf != null, "connectionConf can not be null");
            return builder().setConnectionConf(connectionConf).build();
        }

        public Append withRetryConf(RetryConf retryConf) {
            checkArgument(retryConf != null, "retryConf is required");
            return builder().setRetryConf(retryConf).build();
        }

        public Append withMaxBatchSize(long batchSize) {
            checkArgument(batchSize > 0, "batchSize must be > 0, but was %s", batchSize);

            return builder().setMaxBatchSize(batchSize).build();
        }

        public Append withMaxBatchSizeBytes(long batchSizeBytes) {
            checkArgument(batchSizeBytes > 0, "batchSizeBytes must be > 0, but was %s", batchSizeBytes);
            return builder().setMaxBatchSizeBytes(batchSizeBytes).build();
        }

        @Override
        public PDone expand(PCollection<String> input) {
            ConnectionConf connectionConf = getConnectionConf();
            checkState(null != connectionConf, "withConnectionConf() is required");

            input.apply(ParDo.of(new AppendFn(this)));
            return PDone.in(input.getPipeline());
        }
    }
}