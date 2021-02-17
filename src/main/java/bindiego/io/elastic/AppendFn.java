package bindiego.io.elastic;

import bindiego.io.elastic.ElasticsearchIO.Append;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.util.BackOff;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.util.concurrent.Futures;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.util.concurrent.ListenableFuture;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.util.concurrent.SettableFuture;
import org.apache.http.HttpEntity;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.joda.time.Duration;

class AppendFn extends DoFn<String, Void> {
  private static final Duration RETRY_INITIAL_BACKOFF = Duration.standardSeconds(5);

  private static final ScheduledExecutorService SLEEP_EXECUTOR = Executors
      .newSingleThreadScheduledExecutor();

  static final String RETRY_FAILED_LOG =
      "Error writing to ES after %d attempt(s). No more attempts allowed";

  private transient FluentBackoff retryBackoff;

  private final Append spec;
  private transient RestClient restClient;

  private transient List<String> batch;
  private transient long currentBatchSizeBytes;
  private transient List<ListenableFuture<Void>> writeFutures;

  // { "index":{} }
  private String getDocMeta(String document) {
    return "{}";
  }

  AppendFn(Append spec) {
    this.spec = spec;
  }

  @Setup
  public void setup() throws IOException {
    ConnectionConf connectionConf = spec.getConnectionConf();
    restClient = connectionConf.createClient();

    retryBackoff =
        FluentBackoff.DEFAULT
            .withMaxRetries(0)
            .withInitialBackoff(RETRY_INITIAL_BACKOFF);

    if (spec.getRetryConf() != null) {
      retryBackoff =
          FluentBackoff.DEFAULT
              .withInitialBackoff(RETRY_INITIAL_BACKOFF)
              .withMaxRetries(spec.getRetryConf().getMaxAttempts() - 1)
              .withMaxCumulativeBackoff(spec.getRetryConf().getMaxDuration());
    }
  }

  @StartBundle
  public void startBundle(StartBundleContext context) {
    batch = new ArrayList<>();
    currentBatchSizeBytes = 0;
    writeFutures = new ArrayList<>();
  }

  @ProcessElement
  public void processElement(ProcessContext context) throws Exception {
    String doc = context.element();
    String docMeta = getDocMeta(doc);

    // { "index":{} }
    // { <doc json> }
    batch.add(String.format("{ \"index\" : %s }%n%s%n", docMeta, doc));

    currentBatchSizeBytes += doc.getBytes(StandardCharsets.UTF_8).length;
    if (batch.size() >= spec.getMaxBatchSize()
        || currentBatchSizeBytes >= spec.getMaxBatchSizeBytes()) {
      flushBatch();
    }
  }

  @FinishBundle
  public void finishBundle(FinishBundleContext context)
      throws ExecutionException, InterruptedException {
    flushBatch();
    Futures.allAsList(writeFutures).get();
  }

  @Teardown
  public void closeClient() throws IOException {
    if (null != restClient) {
      restClient.close();
    }
  }

  private String bulkEndpoint() {
    return String.format(
        "/%s/_bulk",
        spec.getConnectionConf().getIndex());
  }

  private void flushBatch() {
    if (batch.isEmpty()) return;

    StringBuilder bulkRequest = new StringBuilder();
    for (String json : batch) {
      bulkRequest.append(json);
    }

    batch.clear();
    currentBatchSizeBytes = 0;

    HttpEntity requestBody = new NStringEntity(
        bulkRequest.toString(), ContentType.APPLICATION_JSON);
    Request request = new Request("POST", bulkEndpoint());
    request.addParameters(Collections.emptyMap());
    request.setEntity(requestBody);

    writeFutures.add(performWithRetries(request));
  }

  private ListenableFuture<Void> performWithRetries(Request request) {
    SettableFuture<Void> writeFuture = SettableFuture.create();
    restClient.performRequestAsync(request, new ResponseListener() {
      private final BackOff backoff = retryBackoff.backoff();
      private int attempts = 0;

      @Override
      public void onSuccess(Response response) {
        try {
          BufferedHttpEntity responseEntity = new BufferedHttpEntity(response.getEntity());
          if (!spec.getRetryConf().getRetryPredicate().test(responseEntity)) {
            writeFuture.set(null);
            return;
          }
          ++attempts;
          long backOffMillis = backoff.nextBackOffMillis();
          if (backOffMillis == BackOff.STOP) {
            writeFuture.setException(new IOException(String.format(RETRY_FAILED_LOG, attempts)));
            return;
          }
          SLEEP_EXECUTOR.schedule(() -> restClient.performRequestAsync(request, this), backOffMillis, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
          writeFuture.setException(e);
        }
      }

      @Override
      public void onFailure(Exception e) {
        writeFuture.setException(e);
      }
    });
    return writeFuture;
  }
}
