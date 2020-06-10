package bindiego.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;


import com.google.auto.value.AutoValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkState;

import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.BackOff;
import org.apache.beam.sdk.util.BackOffUtils;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.Sleeper;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import org.joda.time.Duration;

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

    private static final ObjectMapper mapper = new ObjectMapper();

    public static Append append() {
        return new AutoValue_ElasticsearchIO_Append.Builder()
            .setMaxBatchSize(1000L)
            .setMaxBatchSizeBytes(5L * 1024L * 1024L)
            .build();
    }

    @AutoValue
    public abstract static class ConnectionConf implements Serializable {
        //public abstract List<String> getAddresses();
        public abstract String getAddress();

        @Nullable
        public abstract String getUsername();

        @Nullable
        public abstract String getPassword();

        @Nullable
        public abstract Integer getSocketTimeout();

        @Nullable
        public abstract Integer getConnectTimeout();

        public abstract boolean isTrustSelfSignedCerts();

        public abstract String getIndex();

        @Nullable
        public abstract Integer getNumThread();

        abstract Builder builder();

        @AutoValue.Builder
        abstract static class Builder {
            // abstract Builder setAddresses(List<String> addresses);
            abstract Builder setAddress(String address);

            abstract Builder setUsername(String username);

            abstract Builder setPassword(String password);

            abstract Builder setSocketTimeout(Integer maxRetryTimeout);

            abstract Builder setConnectTimeout(Integer connectTimeout);

            abstract Builder setTrustSelfSignedCerts(boolean trustSelfSignedCerts);

            abstract Builder setIndex(String index);

            abstract Builder setNumThread(Integer numThread);
      
            abstract ConnectionConf build();
        }

        // public static ConnectionConf create(String[] addresses, String index) {
        public static ConnectionConf create(String address, String index) {
            //checkArgument(addresses != null, "addresses can not be null");
            //checkArgument(addresses.length > 0, "addresses can not be empty");
            checkArgument(null != address, "address can not be null");
            checkArgument(index != null, "index can not be null");
            return new AutoValue_ElasticsearchIO_ConnectionConf.Builder()
                //.setAddresses(Arrays.asList(addresses))
                .setAddress(address)
                .setIndex(index)
                .setTrustSelfSignedCerts(false)
                .build();
        }

        public ConnectionConf withUsername(String username) {
            checkArgument(username != null, "username can not be null");
            checkArgument(!username.isEmpty(), "username can not be empty");
            return builder().setUsername(username).build();
        }

        public ConnectionConf withPassword(String password) {
            checkArgument(password != null, "password can not be null");
            checkArgument(!password.isEmpty(), "password can not be empty");
            return builder().setPassword(password).build();
        }

        public ConnectionConf withTrustSelfSignedCerts(boolean trustSelfSignedCerts) {
            return builder().setTrustSelfSignedCerts(trustSelfSignedCerts).build();
        }

        public ConnectionConf withSocketTimeout(Integer socketTimeout) {
            checkArgument(socketTimeout != null, "socketTimeout can not be null");
            return builder().setSocketTimeout(socketTimeout).build();
        }

        public ConnectionConf withConnectTimeout(Integer connectTimeout) {
            checkArgument(connectTimeout != null, "connectTimeout can not be null");
            return builder().setConnectTimeout(connectTimeout).build();
        }

        public ConnectionConf withNumThread(Integer numThread) {
            checkArgument(null != numThread, "numThread cannot be null");
            return builder().setNumThread(
                numThread <= 1 ? new Integer(1) : numThread
            ).build();
        }

        private RestClientBuilder createClientBuilder() throws IOException {
            /*
            HttpHost[] esHosts = new HttpHost[getAddresses().size()];
            int i = 0;
            for (String addr : getAddresses()) {
                URL url = new URL(addr);
                esHosts[i] = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
                ++i;
            }
            */
            HttpHost[] esHosts = new HttpHost[1];
            URL url = new URL(getAddress());
            esHosts[0] = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());

            RestClientBuilder restClientBuilder = RestClient.builder(esHosts);

            if (null != getUsername() || null != getNumThread()) {
                final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                if (null != getUsername()) 
                    credentialsProvider.setCredentials(
                        AuthScope.ANY, new UsernamePasswordCredentials(getUsername(), getPassword()));
                /*    
                restClientBuilder.setHttpClientConfigCallback(
                    httpAsyncClientBuilder ->
                        httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
                */
                restClientBuilder.setHttpClientConfigCallback(
                    new HttpClientConfigCallback() {
                        @Override
                        public HttpAsyncClientBuilder customizeHttpClient(
                            HttpAsyncClientBuilder httpAsyncClientBuilder) {
                                if (null != getUsername()) {
                                    httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                                }

                                if (null != getNumThread()) {
                                    httpAsyncClientBuilder.setDefaultIOReactorConfig(
                                        IOReactorConfig.custom()
                                            .setIoThreadCount(getNumThread().intValue())
                                            .build());
                                }

                                return httpAsyncClientBuilder;
                            }
                    }
                );
            }

            restClientBuilder.setRequestConfigCallback(
                new RestClientBuilder.RequestConfigCallback() {
                    @Override
                    public RequestConfig.Builder customizeRequestConfig(
                            RequestConfig.Builder requestConfigBuilder) {
                        if (null != getConnectTimeout()) {
                            requestConfigBuilder.setConnectTimeout(getConnectTimeout());
                        }
                        if (null != getSocketTimeout()) {
                            requestConfigBuilder.setSocketTimeout(getSocketTimeout());
                        }

                        return requestConfigBuilder;
                    }
                }
            );

            return restClientBuilder;
        }

        RestClient createClient() throws IOException {
            return createClientBuilder().build();
        }

        /*
        RestHighLevelClient createHighLevelClient() throws IOException {
            return new RestHighLevelClient(createClientBuilder().build());
        }
        */
    }

    @AutoValue
    public abstract static class RetryConf implements Serializable {
        static final RetryPredicate DEFAULT_RETRY_PREDICATE = new DefaultRetryPredicate();

        abstract int getMaxAttempts();

        abstract Duration getMaxDuration();

        abstract RetryPredicate getRetryPredicate();

        abstract Builder builder();

        @AutoValue.Builder
        abstract static class Builder {
            abstract ElasticsearchIO.RetryConf.Builder setMaxAttempts(int maxAttempts);

            abstract ElasticsearchIO.RetryConf.Builder setMaxDuration(Duration maxDuration);

            abstract ElasticsearchIO.RetryConf.Builder setRetryPredicate(
                RetryPredicate retryPredicate);

            abstract ElasticsearchIO.RetryConf build();
        }

        public static RetryConf create(int maxAttempts, Duration maxDuration) {
            checkArgument(maxAttempts > 0, "maxAttempts must be greater than 0");
            checkArgument(
                maxDuration != null && maxDuration.isLongerThan(Duration.ZERO),
                "maxDuration must be greater than 0");

            return new AutoValue_ElasticsearchIO_RetryConf.Builder()
                .setMaxAttempts(maxAttempts)
                .setMaxDuration(maxDuration)
                .setRetryPredicate(DEFAULT_RETRY_PREDICATE)
                .build();
        }

        RetryConf withRetryPredicate(RetryPredicate predicate) {
            checkArgument(predicate != null, "predicate must be provided");

            return builder().setRetryPredicate(predicate).build();
        }
    }

    @FunctionalInterface
    interface RetryPredicate extends Predicate<HttpEntity>, Serializable {}

    static class DefaultRetryPredicate implements RetryPredicate {

        private int errorCode;

        DefaultRetryPredicate(int code) {
            this.errorCode = code;
        }

        // TOO_MANY_REQUESTS(429)
        DefaultRetryPredicate() {
            this(429);
        }

        /** Returns true if the response has the error code for any mutation. */
        private static boolean errorCodePresent(HttpEntity responseEntity, int errorCode) {
            try {
                JsonNode json = parseResponse(responseEntity);
                if (json.path("errors").asBoolean()) {
                    for (JsonNode item : json.path("items")) {
                        if (item.findValue("status").asInt() == errorCode) {
                            return true;
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not extract error codes from responseEntity {}", responseEntity);
            }
            return false;
        }

        @Override
        public boolean test(HttpEntity responseEntity) {
            return errorCodePresent(responseEntity, errorCode);
        }
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

        static class AppendFn extends DoFn<String, Void> {
            private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
            private static final int DEFAULT_RETRY_ON_CONFLICT = 5; // race conditions on updates

            private static final Duration RETRY_INITIAL_BACKOFF = Duration.standardSeconds(5);

            static final String RETRY_ATTEMPT_LOG = "Error writing to Elasticsearch. Retry attempt[%d]";

            static final String RETRY_FAILED_LOG =
                "Error writing to ES after %d attempt(s). No more attempts allowed";

            private transient FluentBackoff retryBackoff;

            private int esVersion;
            private final Append spec;
            private transient RestClient restClient;
            private ArrayList<String> batch;
            private long currentBatchSizeBytes;

            private static class DocMeta implements Serializable {
                final String index;

                DocMeta(final String index) {
                    this.index = index;
                }
            }

            private class DocMetaSerializer extends StdSerializer<DocMeta> {
                private DocMetaSerializer() {
                    super(DocMeta.class);
                }

                @Override
                public void serialize (
                        DocMeta value, JsonGenerator gen, SerializerProvider provider)
                        throws IOException {
                    gen.writeStartObject();

                    if (null != value.index) {
                        gen.writeStringField("_index", value.index);
                    }

                    gen.writeEndObject();
                }
            }

            // { "index":{} }
            private String getDocMeta(String document) throws IOException {
                return "{}";
            }

            private static String lowerCaseOrNull(String input) {
                return input == null ? null : input.toLowerCase();
            }

            AppendFn(Append spec) {
                this.spec = spec;
            }

            @Setup 
            public void setup() throws IOException {
                ConnectionConf connectionConf = spec.getConnectionConf();
                esVersion = getEsVersion(connectionConf);
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
                    throws IOException, InterruptedException {
                flushBatch();
            }

            @Teardown
            public void closeClient() throws IOException {
                if (null != restClient) {
                    restClient.close();
                }
            }

            private void flushBatch() throws IOException, InterruptedException {
                if (batch.isEmpty()) {
                    return;
                }

                StringBuilder bulkRequest = new StringBuilder();
                for (String json : batch) {
                    bulkRequest.append(json);
                }

                batch.clear();
                currentBatchSizeBytes = 0;

                String endPoint = String.format(
                    "/%s/_bulk",
                    spec.getConnectionConf().getIndex());
                HttpEntity requestBody = new NStringEntity(
                    bulkRequest.toString(), ContentType.APPLICATION_JSON);
                Request request = new Request("POST", endPoint);
                request.addParameters(Collections.emptyMap());
                request.setEntity(requestBody);

                // Sync
                Response response = restClient.performRequest(request);
                HttpEntity responseEntity = new BufferedHttpEntity(response.getEntity());
                if (null != spec.getRetryConf() 
                        && spec.getRetryConf().getRetryPredicate().test(responseEntity)) {
                    responseEntity = handleRetry("POST", endPoint, Collections.emptyMap(), requestBody);
                }

                // Async
                /*
                restClient.performRequestAsync(request, new ResponseListener() {
                    @Override
                    public void onSuccess(Response response) {
                        try {
                            HttpEntity responseEntity = new BufferedHttpEntity(response.getEntity());
                            if (null != spec.getRetryConf() 
                                    && spec.getRetryConf().getRetryPredicate().test(responseEntity)) {
                                responseEntity = handleRetry("POST", endPoint, Collections.emptyMap(), requestBody);
                            }
                        } catch (Exception ex) {}
                    }

                    @Override // TODO: error handling. e.g. republish to message queue or write GCS etc.
                    public void onFailure(Exception ex) {
                        logger.error("Elasticsearch ingest failed", ex);
                    }
                });
                */
            }

            private HttpEntity handleRetry(
                String method, String endPoint, Map<String, String> params, HttpEntity requestBody)
                    throws IOException, InterruptedException {
                Response response;
                HttpEntity responseEntity;      

                Sleeper sleeper = Sleeper.DEFAULT;
                BackOff backoff = retryBackoff.backoff();
                int attempt = 0;

                while(BackOffUtils.next(sleeper, backoff)) {
                    logger.warn(String.format(RETRY_ATTEMPT_LOG, ++attempt));

                    Request request = new Request(method, endPoint);
                    request.addParameters(params);
                    request.setEntity(requestBody);

                    response = restClient.performRequest(request);
                    responseEntity = new BufferedHttpEntity(response.getEntity());

                    if (!spec.getRetryConf().getRetryPredicate().test(responseEntity)) {
                        return responseEntity;
                    }
                }

                throw new IOException(String.format(RETRY_FAILED_LOG, attempt));
            }
        }
    }

    static JsonNode parseResponse(HttpEntity responseEntity) throws IOException {
        return mapper.readValue(responseEntity.getContent(), JsonNode.class);
    }

    static void checkForErrors(HttpEntity responseEntity, int esVersion, boolean partialUpdate)
        throws IOException {
        JsonNode searchResult = parseResponse(responseEntity);
        boolean errors = searchResult.path("errors").asBoolean();
        if (errors) {
            StringBuilder errorMessages =
                new StringBuilder("Error writing to Elasticsearch, some elements could not be inserted:");
            JsonNode items = searchResult.path("items");
            // some items present in bulk might have errors, concatenate error messages
            for (JsonNode item : items) {

                String errorRootName = "";
                // when use partial update, the response items includes all the update.
                if (partialUpdate) {
                    errorRootName = "update";
                } else {
                    if (esVersion == 2) {
                        errorRootName = "create";
                    } else if (esVersion >= 5) {
                        errorRootName = "index";
                    }
                }
                JsonNode errorRoot = item.path(errorRootName);
                JsonNode error = errorRoot.get("error");
                if (null != error) {
                    String type = error.path("type").asText();
                    String reason = error.path("reason").asText();
                    String docId = errorRoot.path("_id").asText();
                    errorMessages.append(String.format("%nDocument id %s: %s (%s)", docId, reason, type));
                    JsonNode causedBy = error.get("caused_by");
                    if (causedBy != null) {
                        String cbReason = causedBy.path("reason").asText();
                        String cbType = causedBy.path("type").asText();
                        errorMessages.append(String.format("%nCaused by: %s (%s)", cbReason, cbType));
                    }
                }
            }
            throw new IOException(errorMessages.toString());
        }
    }

    static int getEsVersion(ConnectionConf connectionConf) {
        try (RestClient restClient = connectionConf.createClient()) {
            Request request = new Request("GET", "");
            Response response = restClient.performRequest(request);
            JsonNode jsonNode = parseResponse(response.getEntity());
            int esVersion =
                Integer.parseInt(jsonNode.path("version").path("number").asText().substring(0, 1));
            checkArgument(
                (esVersion == 7
                    || esVersion == 8),
                "The Elasticsearch version to connect to is %s.x. "
                    + "This version of the ElasticsearchIO is only compatible with "
                    + "Elasticsearch v8.x, v7.x",
                esVersion);
            return esVersion;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot get Elasticsearch version", ex);
        }
    }

    // Instantiate Logger
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchIO.class);
}