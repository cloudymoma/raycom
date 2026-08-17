package bindiego.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import com.google.auto.value.AutoValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Queue;
import java.util.function.Predicate;
import java.security.cert.X509Certificate;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkState;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Strings;

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
 * A high-performance Elasticsearch Sink with optimized batching, connection pooling,
 * and async processing for Apache Beam pipelines.
 *
 * Features:
 * - Connection pooling and reuse
 * - Adaptive batching with time-based flushing
 * - Proper async handling with backpressure
 * - Memory-optimized operations
 * - Comprehensive error handling and retry logic
 * - Thread-safe operations
 * - Optional compression support
 *
 * For Elasticsearch Reading and Upsert (by ID), you may need
 * Option 1: @see org.apache.beam.sdk.io.elasticsearch.ElasticsearchIO
 * Option 2: @see org.elasticsearch.client.RestClient
 */
public class ElasticsearchIO {

    private ElasticsearchIO() {} // disable new

    private static final ObjectMapper mapper = new ObjectMapper();

    // Pre-computed constants for bulk request building (avoid per-batch allocation)
    private static final byte[] INDEX_ACTION_BYTES = "{\"index\":{}}\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE_BYTES = "\n".getBytes(StandardCharsets.UTF_8);

    /** Exposes the internal buffer to avoid the copy in {@link ByteArrayOutputStream#toByteArray()}. */
    static class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        ExposedByteArrayOutputStream(int size) { super(size); }
        /** Returns the internal buffer — valid data is from index 0 to {@link #size()} - 1. */
        byte[] getRawBuffer() { return buf; }
    }

    // Connection pool for reusing clients across instances
    private static final ConcurrentHashMap<String, RestClient> clientPool = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> versionCache = new ConcurrentHashMap<>();

    // Note: scheduler is now instance-scoped in AppendFn (see @Setup/@Teardown)
    // to avoid cross-pipeline interference when multiple pipelines share the JVM.

    // Performance metrics — LongAdder avoids false sharing under high contention
    // (adjacent AtomicLong fields would share CPU cache lines)
    private static final LongAdder totalDocuments = new LongAdder();
    private static final LongAdder totalBatches = new LongAdder();
    private static final LongAdder totalErrors = new LongAdder();

    public static Append append() {
        return new AutoValue_ElasticsearchIO_Append.Builder()
            .setMaxBatchSize(1000L)
            .setMaxBatchSizeBytes(5L * 1024L * 1024L)
            .setFlushIntervalMillis(30000L) // 30 seconds default flush interval
            .setEnableCompression(false)
            .setMaxConcurrentRequests(5)
            .setPendingTimeoutSeconds(60L)
            .build();
    }

    @AutoValue
    public abstract static class ConnectionConf implements Serializable {
        public abstract String getAddress();

        @Nullable
        public abstract String getUsername();

        @Nullable
        public abstract String getPassword();

        public abstract @Nullable String getApiKey();

        @Nullable
        public abstract Integer getSocketTimeout();

        @Nullable
        public abstract Integer getConnectTimeout();

        public abstract boolean isTrustSelfSignedCerts();

        public abstract String getIndex();

        @Nullable
        public abstract Integer getNumThread();

        @Nullable
        public abstract String getKeystorePath();

        @Nullable
        public abstract String getKeystorePassword();

        // added for ignore self-signed certs
        public abstract boolean isIgnoreInsecureSSL();

        /** Redact sensitive fields to prevent credential leaks via logging or exceptions. */
        @Override
        public String toString() {
            return "ConnectionConf{address=" + getAddress()
                + ", index=" + getIndex()
                + ", username=" + getUsername()
                + ", password=" + (getPassword() != null ? "***" : "null")
                + ", apiKey=" + (getApiKey() != null ? "***" : "null")
                + ", keystorePassword=" + (getKeystorePassword() != null ? "***" : "null")
                + ", keystorePath=" + getKeystorePath()
                + ", ignoreInsecureSSL=" + isIgnoreInsecureSSL()
                + ", trustSelfSignedCerts=" + isTrustSelfSignedCerts()
                + "}";
        }

        /**
         * Connection pooling key for client reuse. Includes all security-relevant fields
         * so that connections with different credentials, SSL settings, or keystores
         * are never incorrectly shared.
         */
        public String getPoolKey() {
            StringBuilder key = new StringBuilder();
            key.append(getAddress()).append('|')
               .append(getIndex()).append('|')
               .append(getUsername() != null ? getUsername() : "noauth").append('|')
               .append(getPassword() != null ? getPassword().hashCode() : 0).append('|')
               .append(getApiKey() != null ? getApiKey().hashCode() : 0).append('|')
               .append(isIgnoreInsecureSSL()).append('|')
               .append(isTrustSelfSignedCerts()).append('|')
               .append(getKeystorePath() != null ? getKeystorePath() : "nokeys");
            return key.toString();
        }

        /** Returns a log-safe version of the pool key with username redacted. */
        public String getPoolKeyForLogging() {
            return getAddress() + "|" + getIndex() + "|***";
        }

        abstract Builder builder();

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder setAddress(String address);

            abstract Builder setUsername(String username);

            abstract Builder setPassword(String password);

            abstract Builder setApiKey(String apiKey);

            abstract Builder setSocketTimeout(Integer maxRetryTimeout);

            abstract Builder setConnectTimeout(Integer connectTimeout);

            abstract Builder setTrustSelfSignedCerts(boolean trustSelfSignedCerts);

            abstract Builder setIndex(String index);

            abstract Builder setNumThread(Integer numThread);

            abstract Builder setKeystorePath(String keystorePath);

            abstract Builder setKeystorePassword(String password);

            abstract Builder setIgnoreInsecureSSL(boolean ignoreInsecureSSL);

            abstract ConnectionConf build();
        }

        public static ConnectionConf create(String address, String index) {
            checkArgument(null != address, "address can not be null");
            checkArgument(index != null, "index can not be null");
            return new AutoValue_ElasticsearchIO_ConnectionConf.Builder()
                .setAddress(address)
                .setIndex(index)
                .setTrustSelfSignedCerts(false)
                .setIgnoreInsecureSSL(false)
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

        public ConnectionConf withApiKey(String apiKey) {
            checkArgument(!Strings.isNullOrEmpty(apiKey), "apiKey can not be null or empty");
            return builder().setApiKey(apiKey).build();
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
                numThread <= 1 ? Integer.valueOf(1) : numThread
            ).build();
        }

        public ConnectionConf withIgnoreInsecureSSL(boolean ignoreInsecureSSL) {
            return builder().setIgnoreInsecureSSL(ignoreInsecureSSL).build();
        }

        /** @deprecated Use {@link #withIgnoreInsecureSSL(boolean)} instead. */
        @Deprecated
        public ConnectionConf withIngnoreInsecureSSL(boolean ignoreInsecureSSL) {
            return withIgnoreInsecureSSL(ignoreInsecureSSL);
        }

        private RestClientBuilder createClientBuilder() throws IOException {
            HttpHost[] esHosts = new HttpHost[1];
            URL url = new URL(getAddress());
            esHosts[0] = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());

            RestClientBuilder restClientBuilder = RestClient.builder(esHosts);

            // Pre-load keystore SSL context if configured (done outside callback to fail fast)
            final SSLContext keystoreSslContext;
            final SSLIOSessionStrategy keystoreSessionStrategy;
            if (getKeystorePath() != null && !getKeystorePath().isEmpty()) {
                try {
                    KeyStore keyStore = KeyStore.getInstance("jks");
                    try (InputStream is = new FileInputStream(new File(getKeystorePath()))) {
                        String keystorePassword = getKeystorePassword();
                        keyStore.load(is, (keystorePassword == null) ? null : keystorePassword.toCharArray());
                    }
                    final TrustStrategy trustStrategy =
                        isTrustSelfSignedCerts() ? new TrustSelfSignedStrategy() : null;
                    keystoreSslContext =
                        SSLContexts.custom().loadTrustMaterial(keyStore, trustStrategy).build();
                    keystoreSessionStrategy = new SSLIOSessionStrategy(keystoreSslContext);
                } catch (Exception e) {
                    throw new IOException("Can't load the client certificate from the keystore", e);
                }
            } else {
                keystoreSslContext = null;
                keystoreSessionStrategy = null;
            }

            // Single consolidated HttpClientConfigCallback — prevents the old bug where
            // the keystore block silently overwrote credentials, thread config, and SSL settings
            boolean needsCallback = null != getUsername() || null != getNumThread()
                || isIgnoreInsecureSSL() || keystoreSslContext != null;

            if (needsCallback) {
                final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                if (null != getUsername())
                    credentialsProvider.setCredentials(
                        AuthScope.ANY, new UsernamePasswordCredentials(getUsername(), getPassword()));

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
                                            .setTcpNoDelay(true)
                                            .setSoKeepAlive(true)
                                            .build());
                                }

                                // Keystore-based SSL (takes priority over insecure SSL)
                                if (keystoreSslContext != null) {
                                    httpAsyncClientBuilder.setSSLContext(keystoreSslContext)
                                        .setSSLStrategy(keystoreSessionStrategy);
                                } else if (isIgnoreInsecureSSL()) {
                                    // SECURITY WARNING: This disables ALL TLS certificate verification.
                                    // An attacker in network position can intercept all traffic including
                                    // credentials and documents (MITM attack).
                                    String env = System.getenv("ENVIRONMENT");
                                    if ("production".equalsIgnoreCase(env) || "prod".equalsIgnoreCase(env)) {
                                        throw new IllegalStateException(
                                            "isIgnoreInsecureSSL is FORBIDDEN in production environments. " +
                                            "Configure proper TLS certificates via keystorePath instead.");
                                    }
                                    logger.warn("*** INSECURE SSL IS ENABLED — ALL CERTIFICATE VERIFICATION DISABLED ***");
                                    logger.warn("*** DO NOT USE IN PRODUCTION — VULNERABLE TO MITM ATTACKS ***");
                                    try {
                                        SSLContext context = SSLContext.getInstance("TLS");

                                        context.init(null, new TrustManager[] {
                                            new X509TrustManager() {
                                                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                                                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                                                // return empty array per X509TrustManager contract
                                                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                                            }
                                        }, null);

                                        httpAsyncClientBuilder.setSSLContext(context)
                                            .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                                    } catch (NoSuchAlgorithmException ex) {
                                        logger.error("Error when setup dummy SSLContext", ex);
                                    } catch (KeyManagementException ex) {
                                        logger.error("Error when setup dummy SSLContext", ex);
                                    } catch (Exception ex) {
                                        logger.error("Error when setup dummy SSLContext", ex);
                                    }
                                }

                                return httpAsyncClientBuilder;
                            }
                    }
                );
            }

            if (getApiKey() != null) {
                restClientBuilder.setDefaultHeaders(
                new Header[] {new BasicHeader("Authorization", "ApiKey " + getApiKey())});
            }

            restClientBuilder.setRequestConfigCallback(
                new RestClientBuilder.RequestConfigCallback() {
                    @Override
                    public RequestConfig.Builder customizeRequestConfig(
                            RequestConfig.Builder requestConfigBuilder) {
                        // Default 30s connect timeout to prevent indefinite hangs against unresponsive nodes
                        requestConfigBuilder.setConnectTimeout(
                            getConnectTimeout() != null ? getConnectTimeout() : 30000);
                        // Default 120s socket timeout for bulk operations
                        requestConfigBuilder.setSocketTimeout(
                            getSocketTimeout() != null ? getSocketTimeout() : 120000);

                        return requestConfigBuilder;
                    }
                }
            );

            return restClientBuilder;
        }

        // Double-check pattern: avoids holding ConcurrentHashMap bucket lock during
        // network IO (DNS, TCP handshake, TLS negotiation). The tradeoff is occasional
        // redundant client creation under race, which is far cheaper than blocking threads.
        private RestClient createClient() throws IOException {
            String poolKey = getPoolKey();

            // Fast path: return existing running client without any locking
            RestClient existing = clientPool.get(poolKey);
            if (existing != null && existing.isRunning()) {
                return existing;
            }

            // Slow path: build new client OUTSIDE the lock (expensive network operation)
            RestClient newClient = createClientBuilder().build();

            // Atomic merge: handle race where another thread may have inserted first
            RestClient winner = clientPool.merge(poolKey, newClient, (oldClient, createdClient) -> {
                if (oldClient != null && oldClient.isRunning()) {
                    // Another thread beat us — close the one we just created
                    try {
                        createdClient.close();
                    } catch (IOException e) {
                        logger.warn("Error closing redundant RestClient", e);
                    }
                    return oldClient;
                }
                // Old client is stale — close it
                if (oldClient != null) {
                    try {
                        oldClient.close();
                    } catch (IOException e) {
                        logger.warn("Error closing stale RestClient", e);
                    }
                }
                return createdClient;
            });

            if (winner == newClient) {
                logger.info("Created new RestClient for pool key: {}", getPoolKeyForLogging());
            }
            return winner;
        }

        // Get cached client from pool with validation
        public RestClient getPooledClient() throws IOException {
            return createClient();
        }
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

        // Retryable HTTP status codes: throttling + transient server errors
        private static final Set<Integer> DEFAULT_RETRYABLE_CODES =
            new HashSet<>(Arrays.asList(429, 500, 502, 503, 504));

        private final Set<Integer> retryableCodes;

        DefaultRetryPredicate(int code) {
            this.retryableCodes = new HashSet<>(Arrays.asList(code));
        }

        // Default: retry on TOO_MANY_REQUESTS(429), Internal Server Error(500),
        // Bad Gateway(502), Service Unavailable(503), Gateway Timeout(504)
        DefaultRetryPredicate() {
            this.retryableCodes = DEFAULT_RETRYABLE_CODES;
        }

        /** Returns true if the response has any retryable error code for any mutation. */
        private static boolean retryableErrorPresent(HttpEntity responseEntity, Set<Integer> retryableCodes) {
            if (responseEntity == null) {
                logger.warn("Response entity is null, cannot check for error codes");
                return false;
            }
            try {
                JsonNode json = parseResponse(responseEntity);
                if (json.path("errors").asBoolean()) {
                    for (JsonNode item : json.path("items")) {
                        JsonNode statusNode = item.findValue("status");
                        if (statusNode != null && retryableCodes.contains(statusNode.asInt())) {
                            return true;
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not extract error codes from responseEntity {}", responseEntity, e);
            }
            return false;
        }

        @Override
        public boolean test(HttpEntity responseEntity) {
            return retryableErrorPresent(responseEntity, retryableCodes);
        }
    }

    /**
     * Structured result of a bulk API call. Separates per-item successes from failures,
     * and classifies failures as retryable vs non-retryable for targeted retry.
     */
    static class BulkResult {
        final int successCount;
        final List<FailedDoc> retryableFailures;
        final List<FailedDoc> nonRetryableFailures;

        BulkResult(int successCount, List<FailedDoc> retryableFailures,
                   List<FailedDoc> nonRetryableFailures) {
            this.successCount = successCount;
            this.retryableFailures = retryableFailures;
            this.nonRetryableFailures = nonRetryableFailures;
        }

        boolean hasFailures() {
            return !retryableFailures.isEmpty() || !nonRetryableFailures.isEmpty();
        }

        static class FailedDoc {
            final int index;         // positional index in the original List<byte[]>
            final int statusCode;
            final String errorType;
            final String errorReason;

            FailedDoc(int index, int statusCode, String errorType, String errorReason) {
                this.index = index;
                this.statusCode = statusCode;
                this.errorType = errorType;
                this.errorReason = errorReason;
            }
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

        abstract long getFlushIntervalMillis();

        abstract boolean getEnableCompression();

        abstract int getMaxConcurrentRequests();

        abstract long getPendingTimeoutSeconds();

        abstract Builder builder();

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder setConnectionConf(ConnectionConf connectionConf);

            abstract Builder setRetryConf(RetryConf retryConf);

            abstract Builder setMaxBatchSize(long maxBatchSize);

            abstract Builder setMaxBatchSizeBytes(long maxBatchSizeBytes);

            abstract Builder setFlushIntervalMillis(long flushIntervalMillis);

            abstract Builder setEnableCompression(boolean enableCompression);

            abstract Builder setMaxConcurrentRequests(int maxConcurrentRequests);

            abstract Builder setPendingTimeoutSeconds(long pendingTimeoutSeconds);

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

        public Append withFlushInterval(long flushIntervalMillis) {
            checkArgument(flushIntervalMillis > 0, "flushIntervalMillis must be > 0, but was %s", flushIntervalMillis);
            return builder().setFlushIntervalMillis(flushIntervalMillis).build();
        }

        public Append withCompression(boolean enableCompression) {
            return builder().setEnableCompression(enableCompression).build();
        }

        public Append withMaxConcurrentRequests(int maxConcurrentRequests) {
            checkArgument(maxConcurrentRequests > 0, "maxConcurrentRequests must be > 0, but was %s", maxConcurrentRequests);
            return builder().setMaxConcurrentRequests(maxConcurrentRequests).build();
        }

        public Append withPendingTimeout(long pendingTimeoutSeconds) {
            checkArgument(pendingTimeoutSeconds > 0, "pendingTimeoutSeconds must be > 0, but was %s", pendingTimeoutSeconds);
            return builder().setPendingTimeoutSeconds(pendingTimeoutSeconds).build();
        }

        @Override
        public PDone expand(PCollection<String> input) {
            ConnectionConf connectionConf = getConnectionConf();
            checkState(null != connectionConf, "withConnectionConf() is required");

            input.apply(ParDo.of(new AppendFn(this)));
            return PDone.in(input.getPipeline());
        }

        static class AppendFn extends DoFn<String, Void> {
            private static final int DEFAULT_RETRY_ON_CONFLICT = 5;
            private static final Duration RETRY_INITIAL_BACKOFF = Duration.standardSeconds(5);
            static final String RETRY_ATTEMPT_LOG = "Error writing to Elasticsearch. Retry attempt[%d]";
            static final String RETRY_FAILED_LOG = "Error writing to ES after %d attempt(s). No more attempts allowed";

            private transient FluentBackoff retryBackoff;
            private final Append spec;
            private transient RestClient restClient;

            // transient lock — ReentrantLock is not Serializable
            private transient Object batchLock;
            private transient List<byte[]> batch;  // Store pre-encoded UTF-8 bytes to avoid double encoding
            private transient long currentBatchSizeBytes; // plain long — always accessed inside synchronized(batchLock)
            private volatile long lastFlushTime;

            // Connection pooling and version caching
            private int esVersion;
            private transient String poolKey;
            private transient String poolKeyForLog; // redacted version for logging

            // Performance optimization: reuse byte buffers (exposed subclass avoids toByteArray copy)
            private transient ThreadLocal<ExposedByteArrayOutputStream> byteBufferPool;

            // Instance-scoped scheduler for time-based flushing (not static — avoids cross-pipeline interference)
            private transient ScheduledExecutorService scheduler;

            // Store ScheduledFuture to cancel on bundle finish
            private transient ScheduledFuture<?> flushTask;

            // Dedicated IO executor — avoids starving ForkJoinPool.commonPool() with blocking HTTP calls
            private transient ExecutorService ioExecutor;

            // Backpressure: limits concurrent in-flight ES requests
            private transient Semaphore concurrencySemaphore;

            // transient ConcurrentLinkedQueue — lock-free, no serialization issue
            private transient Queue<CompletableFuture<Void>> pendingOperations;

            AppendFn(Append spec) {
                this.spec = spec;
            }

            @Setup
            public void setup() throws IOException {
                ConnectionConf connectionConf = spec.getConnectionConf();
                poolKey = connectionConf.getPoolKey();
                poolKeyForLog = connectionConf.getPoolKeyForLogging();

                // Initialize lock after deserialization
                batchLock = new Object();

                // Initialize lock-free queue after deserialization
                pendingOperations = new ConcurrentLinkedQueue<>();

                // Initialize ThreadLocal after deserialization
                byteBufferPool = ThreadLocal.withInitial(() -> new ExposedByteArrayOutputStream(8192));

                // Instance-scoped scheduler — each DoFn instance gets its own, avoiding
                // cross-pipeline interference when cleanup() is called
                scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("es-flush-" + poolKey));

                // Dedicated IO thread pool for ES bulk requests — never starves ForkJoinPool.commonPool()
                int maxConcurrent = spec.getMaxConcurrentRequests();
                ioExecutor = Executors.newFixedThreadPool(maxConcurrent, daemonThreadFactory("es-io-" + poolKey));

                // Semaphore enforces the configured maxConcurrentRequests as actual backpressure
                concurrencySemaphore = new Semaphore(maxConcurrent);

                // Use cached version or get from server
                esVersion = versionCache.computeIfAbsent(poolKey, k -> {
                    try {
                        return getEsVersion(connectionConf);
                    } catch (Exception e) {
                        logger.warn("Failed to get ES version from {}, defaulting to 8. Error: {}",
                            connectionConf.getAddress(), e.getMessage());
                        return 8;
                    }
                });

                // Get pooled client with retry on failure
                try {
                    restClient = connectionConf.getPooledClient();
                } catch (IOException e) {
                    logger.error("Failed to create Elasticsearch client for {}: {}",
                        connectionConf.getAddress(), e.getMessage());
                    throw new RuntimeException("Cannot connect to Elasticsearch cluster at " +
                        connectionConf.getAddress() + ". Please check: \n" +
                        "1. Elasticsearch cluster is running and accessible\n" +
                        "2. Host/DNS resolution is working: " + connectionConf.getAddress() + "\n" +
                        "3. Network connectivity and firewall settings\n" +
                        "4. SSL/authentication configuration", e);
                }

                retryBackoff = FluentBackoff.DEFAULT
                    .withMaxRetries(0)
                    .withInitialBackoff(RETRY_INITIAL_BACKOFF);

                if (spec.getRetryConf() != null) {
                    retryBackoff = FluentBackoff.DEFAULT
                        .withInitialBackoff(RETRY_INITIAL_BACKOFF)
                        .withMaxRetries(spec.getRetryConf().getMaxAttempts() - 1)
                        .withMaxCumulativeBackoff(spec.getRetryConf().getMaxDuration());
                }

                logger.info("Setup completed for ES version {} with pool key: {}", esVersion, poolKeyForLog);
            }

            @StartBundle
            public void startBundle(StartBundleContext context) {
                // Pre-allocate with estimated capacity for better performance
                int estimatedCapacity = (int) Math.min(spec.getMaxBatchSize(), 1000);
                batch = new ArrayList<byte[]>(estimatedCapacity);
                currentBatchSizeBytes = 0;
                lastFlushTime = System.currentTimeMillis();

                // Schedule periodic flush and store the future for cancellation.
                // IMPORTANT: scheduleAtFixedRate silently suppresses exceptions — if the
                // task throws, it stops running with no notification. We wrap in try-catch
                // to log and continue, preventing silent flush death.
                if (spec.getFlushIntervalMillis() > 0) {
                    flushTask = scheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                timeBasedFlush();
                            } catch (Throwable t) {
                                totalErrors.increment();
                                logger.error("Scheduled flush task failed — flush will continue on next interval", t);
                            }
                        },
                        spec.getFlushIntervalMillis(),
                        spec.getFlushIntervalMillis(),
                        TimeUnit.MILLISECONDS
                    );
                }
            }

            @ProcessElement
            public void processElement(ProcessContext context) throws Exception {
                String doc = context.element();

                // Encode to UTF-8 once — stored as byte[] in the batch to avoid
                // double encoding (was: encode here for size, encode again in buildBulkRequest)
                byte[] docUtf8 = doc.getBytes(StandardCharsets.UTF_8);

                // Guard against oversized documents that could cause OOM via memory amplification
                // (byte[] in batch + bulk request buffer + optional gzip = up to 4x amplification)
                if (docUtf8.length > spec.getMaxBatchSizeBytes()) {
                    totalErrors.increment();
                    logger.error("Document exceeds max batch size ({} bytes > {} bytes limit). Skipping.",
                        docUtf8.length, spec.getMaxBatchSizeBytes());
                    return;
                }

                synchronized (batchLock) {
                    batch.add(docUtf8);
                    currentBatchSizeBytes += docUtf8.length;

                    // Check flush conditions
                    if (shouldFlush()) {
                        flushBatchAsync();
                    }
                }
            }

            private boolean shouldFlush() {
                return batch.size() >= spec.getMaxBatchSize() ||
                       currentBatchSizeBytes >= spec.getMaxBatchSizeBytes() ||
                       (spec.getFlushIntervalMillis() > 0 &&
                        System.currentTimeMillis() - lastFlushTime >= spec.getFlushIntervalMillis());
            }

            @FinishBundle
            public void finishBundle(FinishBundleContext context)
                    throws IOException, InterruptedException {
                // Cancel the scheduled flush task for this bundle
                if (flushTask != null) {
                    flushTask.cancel(false);
                    flushTask = null;
                }

                // Final flush and wait for all pending operations
                flushBatchAsync();
                waitForPendingOperations();
            }

            // Propagate errors instead of silently dropping data
            private void waitForPendingOperations() throws IOException, InterruptedException {
                CompletableFuture<Void>[] futures = pendingOperations.toArray(new CompletableFuture[0]);
                if (futures.length == 0) {
                    return;
                }

                long timeoutSecs = spec.getPendingTimeoutSeconds();
                try {
                    CompletableFuture.allOf(futures).get(timeoutSecs, TimeUnit.SECONDS);
                    logger.debug("All {} pending operations completed successfully", futures.length);
                } catch (TimeoutException e) {
                    totalErrors.increment();
                    throw new IOException(
                        "Elasticsearch operations timed out after " + timeoutSecs + "s — potential data loss for "
                        + futures.length + " pending batches", e);
                } catch (ExecutionException e) {
                    totalErrors.increment();
                    throw new IOException("Elasticsearch batch operation failed", e.getCause());
                } finally {
                    pendingOperations.clear();
                }
            }

            @Teardown
            public void closeClient() throws IOException {
                // Ensure scheduled task is cancelled on teardown
                if (flushTask != null) {
                    flushTask.cancel(false);
                    flushTask = null;
                }

                // Shutdown instance-scoped scheduler
                if (scheduler != null) {
                    scheduler.shutdown();
                    try {
                        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                            scheduler.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                    scheduler = null;
                }

                // Shutdown dedicated IO executor
                if (ioExecutor != null) {
                    ioExecutor.shutdown();
                    try {
                        if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                            ioExecutor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        ioExecutor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                    ioExecutor = null;
                }

                // Don't close pooled clients - they're shared
                if (pendingOperations != null) {
                    pendingOperations.clear();
                }
                logger.debug("Teardown completed for pool key: {}", poolKeyForLog);
            }

            private void flushBatchAsync() {
                List<byte[]> currentBatch;
                long batchBytes;

                synchronized (batchLock) {
                    if (batch.isEmpty()) {
                        return;
                    }

                    // Swap batch reference (O(1)) instead of copying (O(n))
                    currentBatch = batch;
                    batchBytes = currentBatchSizeBytes;

                    // Allocate fresh batch for next accumulation
                    int estimatedCapacity = (int) Math.min(spec.getMaxBatchSize(), 1000);
                    batch = new ArrayList<byte[]>(estimatedCapacity);
                    currentBatchSizeBytes = 0;
                    lastFlushTime = System.currentTimeMillis();
                }

                // Enforce backpressure: block if too many batches are in-flight
                try {
                    concurrencySemaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // The swapped-out batch is the only reference to these documents.
                    // Restore it and fail loud — returning silently here would let the
                    // bundle commit (and Pub/Sub ack) without the data ever being sent.
                    restoreBatch(currentBatch, batchBytes);
                    throw new RuntimeException(
                        "Interrupted while waiting for Elasticsearch backpressure permit; "
                        + currentBatch.size() + " documents restored to the pending batch", e);
                }

                // Process batch on dedicated IO executor — never starves ForkJoinPool.commonPool()
                boolean submitted = false;
                try {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            processBatch(currentBatch, batchBytes);
                        } catch (Exception e) {
                            logger.error("Failed to process batch of {} documents", currentBatch.size(), e);
                            totalErrors.increment();
                            throw new RuntimeException("Batch processing failed", e);
                        } finally {
                            concurrencySemaphore.release();
                        }
                    }, ioExecutor);
                    submitted = true;
                    pendingOperations.add(future);
                } finally {
                    // runAsync throws synchronously (RejectedExecutionException) if the
                    // executor was shut down — the task body never runs, so the permit
                    // acquired above would leak and the batch would vanish untracked.
                    if (!submitted) {
                        concurrencySemaphore.release();
                        restoreBatch(currentBatch, batchBytes);
                    }
                }
            }

            /**
             * Puts a swapped-out batch back at the head of the pending batch so no
             * documents are lost when a flush could not be handed off to the IO executor.
             */
            private void restoreBatch(List<byte[]> docs, long docBytes) {
                synchronized (batchLock) {
                    // docs are older than anything accumulated since the swap — keep order
                    docs.addAll(batch);
                    batch = docs;
                    currentBatchSizeBytes += docBytes;
                }
            }

            private void processBatch(List<byte[]> batchToProcess, long batchSizeBytes)
                    throws IOException, InterruptedException {
                if (batchToProcess.isEmpty()) {
                    return;
                }
                int maxRetries = spec.getRetryConf() != null ? spec.getRetryConf().getMaxAttempts() : 1;
                totalBatches.increment();
                processBatchWithRetry(batchToProcess, batchSizeBytes, maxRetries);
            }

            /**
             * Sends docs to ES. On partial failure, extracts only the failed documents
             * and retries them in a smaller batch. Non-retryable failures (400, 409) are
             * logged and dropped — retrying them would fail again AND cause duplicate
             * indexing of the docs that already succeeded.
             */
            private void processBatchWithRetry(List<byte[]> docs, long sizeBytes, int retriesLeft)
                    throws IOException, InterruptedException {
                // Build bulk request
                ExposedByteArrayOutputStream baos = byteBufferPool.get();
                if (baos.size() > 1024 * 1024) {
                    baos = new ExposedByteArrayOutputStream(8192);
                    byteBufferPool.set(baos);
                }
                baos.reset();
                buildBulkRequest(docs, baos);
                int requestLen = baos.size();

                // Compress if needed
                byte[] requestBody;
                boolean compressed;
                if (spec.getEnableCompression() && requestLen > 102400) {
                    ByteArrayOutputStream gzipBaos = new ByteArrayOutputStream(requestLen / 4);
                    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBaos)) {
                        gzip.write(baos.getRawBuffer(), 0, requestLen);
                    }
                    requestBody = gzipBaos.toByteArray();
                    compressed = true;
                    logger.debug("Compressed batch from {} to {} bytes ({} ratio)",
                        requestLen, requestBody.length,
                        String.format("%.1f%%", 100.0 * requestBody.length / requestLen));
                } else {
                    requestBody = new byte[requestLen];
                    System.arraycopy(baos.getRawBuffer(), 0, requestBody, 0, requestLen);
                    compressed = false;
                }

                // Send to ES — returns structured result, does NOT throw on per-item errors
                BulkResult result = executeWithRetry(requestBody, docs.size(), sizeBytes, compressed);

                // Account for successes
                totalDocuments.add(result.successCount);

                // Handle non-retryable failures: log and drop
                for (BulkResult.FailedDoc f : result.nonRetryableFailures) {
                    logger.error("Non-retryable error on doc index {}: status={}, type={}, reason={}",
                        f.index, f.statusCode, f.errorType, f.errorReason);
                    totalErrors.increment();
                }

                // Handle retryable failures: extract failed docs and retry
                if (!result.retryableFailures.isEmpty()) {
                    if (retriesLeft > 0) {
                        List<byte[]> retryDocs = new ArrayList<>(result.retryableFailures.size());
                        long retryBytes = 0;
                        for (BulkResult.FailedDoc f : result.retryableFailures) {
                            byte[] doc = docs.get(f.index);
                            retryDocs.add(doc);
                            retryBytes += doc.length;
                        }
                        logger.warn("Retrying {} failed docs (out of {}), {} retries remaining",
                            retryDocs.size(), docs.size(), retriesLeft - 1);

                        // Backoff before retry
                        try {
                            Sleeper.DEFAULT.sleep(
                                Duration.standardSeconds(5).getMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }

                        processBatchWithRetry(retryDocs, retryBytes, retriesLeft - 1);
                    } else {
                        // Exhausted retries — log all remaining failures
                        for (BulkResult.FailedDoc f : result.retryableFailures) {
                            logger.error("Retryable error exhausted retries, doc index {}: status={}, type={}, reason={}",
                                f.index, f.statusCode, f.errorType, f.errorReason);
                        }
                        totalErrors.add(result.retryableFailures.size());
                        throw new IOException("Failed to index " + result.retryableFailures.size() +
                            " documents after exhausting retries");
                    }
                }
            }

            // Docs are pre-encoded byte[] — no per-doc String.getBytes() allocation here
            private void buildBulkRequest(List<byte[]> docs, java.io.OutputStream out) throws IOException {
                for (byte[] doc : docs) {
                    out.write(INDEX_ACTION_BYTES);
                    out.write(doc);
                    out.write(NEWLINE_BYTES);
                }
            }

            /**
             * Sends the bulk request to ES with HTTP-level retry (connection failures,
             * client state issues). Returns a structured {@link BulkResult} for the caller
             * to handle per-item failures. Does NOT throw on per-item errors.
             *
             * @throws IOException on unrecoverable HTTP-level failures (exhausted retries)
             */
            private BulkResult executeWithRetry(byte[] requestBody, int docCount,
                    long batchSizeBytes, boolean compressed)
                    throws IOException, InterruptedException {
                String endpoint = "/" + spec.getConnectionConf().getIndex() + "/_bulk";

                BackOff backoff = retryBackoff.backoff();
                int attempt = 0;
                int maxAttempts = spec.getRetryConf() != null ? spec.getRetryConf().getMaxAttempts() : 1;

                while (true) {
                    HttpEntity rawResponseEntity = null;
                    try {
                        // Validate client state before making request
                        if (!restClient.isRunning()) {
                            logger.warn("RestClient is not running, attempting to recreate...");
                            restClient = spec.getConnectionConf().getPooledClient();
                        }

                        Request request = new Request("POST", endpoint);
                        request.setEntity(new ByteArrayEntity(requestBody, ContentType.APPLICATION_JSON));
                        if (compressed) {
                            request.setOptions(request.getOptions().toBuilder()
                                .addHeader("Content-Encoding", "gzip")
                                .build());
                        }

                        Response response = restClient.performRequest(request);
                        rawResponseEntity = response.getEntity();

                        // Buffer response so the HTTP connection is freed immediately
                        byte[] responseBytes = EntityUtils.toByteArray(rawResponseEntity);
                        HttpEntity bufferedEntity = new ByteArrayEntity(responseBytes, ContentType.APPLICATION_JSON);

                        // Parse per-item results — does NOT throw on per-item failures
                        BulkResult result = parseBulkResponse(bufferedEntity, esVersion, false);

                        logger.debug("Bulk response: {} succeeded, {} retryable failures, {} non-retryable failures",
                            result.successCount, result.retryableFailures.size(),
                            result.nonRetryableFailures.size());

                        return result;

                    } catch (IOException | IllegalStateException e) {
                        // HTTP-level failure — connection refused, timeout, stale client
                        attempt++;
                        totalErrors.increment();

                        // Handle client state issues specifically
                        if (e instanceof IllegalStateException
                                || (e.getCause() != null && e.getCause() instanceof IllegalStateException)) {
                            logger.warn("IllegalStateException detected - client may be closed. Recreating client...");
                            try {
                                String currentPoolKey = spec.getConnectionConf().getPoolKey();
                                clientPool.remove(currentPoolKey);
                                restClient = spec.getConnectionConf().getPooledClient();
                                logger.info("Successfully recreated RestClient after IllegalStateException");
                                attempt = 0;
                                backoff = retryBackoff.backoff();
                            } catch (Exception recreateEx) {
                                logger.error("Failed to recreate RestClient", recreateEx);
                            }
                        }

                        if (attempt >= maxAttempts) {
                            logger.error(RETRY_FAILED_LOG, attempt);
                            throw new IOException("Failed to send bulk request after " + attempt + " attempts", e);
                        }

                        logger.warn(RETRY_ATTEMPT_LOG, attempt);

                        long backoffMillis = backoff.nextBackOffMillis();
                        if (backoffMillis == BackOff.STOP) {
                            throw new IOException("Backoff exhausted after " + attempt + " attempts", e);
                        }
                        Sleeper.DEFAULT.sleep(backoffMillis);

                    } finally {
                        if (rawResponseEntity != null) {
                            EntityUtils.consumeQuietly(rawResponseEntity);
                        }
                    }
                }
            }

            private void timeBasedFlush() {
                try {
                    if (System.currentTimeMillis() - lastFlushTime >= spec.getFlushIntervalMillis()) {
                        flushBatchAsync();
                    }
                } catch (Exception e) {
                    logger.warn("Error during time-based flush", e);
                }
            }

            /** Creates a daemon ThreadFactory with the given name prefix. */
            private static ThreadFactory daemonThreadFactory(String prefix) {
                AtomicInteger counter = new AtomicInteger(0);
                return r -> {
                    Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                };
            }
        }
    }

    static JsonNode parseResponse(HttpEntity responseEntity) throws IOException {
        if (responseEntity == null) {
            throw new IOException("Response entity is null");
        }
        return mapper.readValue(responseEntity.getContent(), JsonNode.class);
    }

    // Use StringBuilder.append() chains instead of String.format in loops
    static void checkForErrors(HttpEntity responseEntity, int esVersion, boolean partialUpdate)
        throws IOException {
        JsonNode searchResult = parseResponse(responseEntity);
        boolean errors = searchResult.path("errors").asBoolean();
        if (errors) {
            StringBuilder errorMessages =
                new StringBuilder(256);
            errorMessages.append("Error writing to Elasticsearch, some elements could not be inserted:");
            JsonNode items = searchResult.path("items");
            for (JsonNode item : items) {

                String errorRootName = "";
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
                    errorMessages.append("\nDocument id ").append(docId)
                        .append(": ").append(reason).append(" (").append(type).append(")");
                    JsonNode causedBy = error.get("caused_by");
                    if (causedBy != null) {
                        String cbReason = causedBy.path("reason").asText();
                        String cbType = causedBy.path("type").asText();
                        errorMessages.append("\nCaused by: ").append(cbReason)
                            .append(" (").append(cbType).append(")");
                    }
                }
            }
            throw new IOException(errorMessages.toString());
        }
    }

    // Retryable status codes — shared between DefaultRetryPredicate and parseBulkResponse
    private static final Set<Integer> RETRYABLE_STATUS_CODES =
        new HashSet<>(Arrays.asList(429, 500, 502, 503, 504));

    /**
     * Parses a bulk API response into a structured {@link BulkResult} with per-item
     * success/failure classification. Unlike {@link #checkForErrors}, this method
     * does NOT throw on per-item errors — it returns them for the caller to handle.
     *
     * @throws IOException only if the response JSON is malformed or unreadable
     */
    static BulkResult parseBulkResponse(HttpEntity responseEntity, int esVersion,
            boolean partialUpdate) throws IOException {
        JsonNode searchResult = parseResponse(responseEntity);
        JsonNode items = searchResult.path("items");
        int totalItems = items.size();

        // Fast path: no errors at all
        if (!searchResult.path("errors").asBoolean()) {
            return new BulkResult(totalItems,
                new ArrayList<>(0), new ArrayList<>(0));
        }

        // Slow path: classify each failed item
        int successCount = 0;
        List<BulkResult.FailedDoc> retryable = new ArrayList<>();
        List<BulkResult.FailedDoc> nonRetryable = new ArrayList<>();

        int index = 0;
        for (JsonNode item : items) {
            // Determine the operation root name
            String opName;
            if (partialUpdate) {
                opName = "update";
            } else {
                opName = (esVersion == 2) ? "create" : "index";
            }

            JsonNode opResult = item.path(opName);
            int status = opResult.path("status").asInt(0);
            JsonNode error = opResult.get("error");

            if (error == null && status >= 200 && status < 300) {
                // Success
                successCount++;
            } else if (error != null) {
                String errorType = error.path("type").asText("");
                String errorReason = error.path("reason").asText("");

                if (RETRYABLE_STATUS_CODES.contains(status)) {
                    retryable.add(new BulkResult.FailedDoc(index, status, errorType, errorReason));
                } else {
                    nonRetryable.add(new BulkResult.FailedDoc(index, status, errorType, errorReason));
                }
            } else {
                // No error object but not a 2xx status — count as success
                // (some ES versions return status without error for certain operations)
                successCount++;
            }
            index++;
        }

        return new BulkResult(successCount, retryable, nonRetryable);
    }

    private static void maybeLogVersionDeprecationWarning(int clusterVersion) {
        if (DEPRECATED_CLUSTER_VERSIONS.contains(clusterVersion)) {
            logger.warn(
                "Support for Elasticsearch cluster version {} will be dropped in a future release of "
                    + "the Apache Beam SDK & this ElasticsearchIO implementation",
                clusterVersion);
        }
    }

    static int getEsVersion(RestClient restClient) {
        try {
            Request request = new Request("GET", "");
            Response response = restClient.performRequest(request);
            JsonNode jsonNode = parseResponse(response.getEntity());
            String versionStr = jsonNode.path("version").path("number").asText();
            if (versionStr == null || versionStr.isEmpty()) {
                throw new IOException("Could not determine Elasticsearch version — empty version string in response");
            }
            int esVersion = Integer.parseInt(versionStr.substring(0, 1));
            checkArgument(
                VALID_CLUSTER_VERSIONS.contains(esVersion),
                "The Elasticsearch version to connect to is %s.x. "
                    + "This version of the ElasticsearchIO is only compatible with "
                    + "Elasticsearch "
                    + VALID_CLUSTER_VERSIONS,
                esVersion);

            maybeLogVersionDeprecationWarning(esVersion);

            return esVersion;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot get Elasticsearch version", ex);
        }
    }

    // Don't close pooled client — use getPooledClient() without try-with-resources
    static int getEsVersion(ConnectionConf connectionConf) {
        try {
            RestClient restClient = connectionConf.getPooledClient();
            return getEsVersion(restClient);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot get Elasticsearch version from " +
                connectionConf.getAddress() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Cleanup method for releasing shared resources (client pool and version cache).
     * Should be called when the application shuts down.
     *
     * Note: The scheduler and IO executor are instance-scoped and cleaned up
     * in AppendFn.closeClient() (@Teardown), so they are NOT managed here.
     * This prevents one pipeline's cleanup from killing another pipeline's resources.
     */
    public static void cleanup() {
        logger.info("Cleaning up ElasticsearchIO shared resources...");

        // Close all pooled clients
        clientPool.forEach((key, client) -> {
            try {
                client.close();
            } catch (IOException e) {
                logger.warn("Error closing pooled RestClient for key: {}", key, e);
            }
        });
        clientPool.clear();
        versionCache.clear();

        logger.info("ElasticsearchIO cleanup completed. Total documents: {}, batches: {}, errors: {}",
            totalDocuments.sum(), totalBatches.sum(), totalErrors.sum());
    }

    /**
     * Get performance metrics for monitoring.
     */
    public static Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("totalDocuments", totalDocuments.sum());
        metrics.put("totalBatches", totalBatches.sum());
        metrics.put("totalErrors", totalErrors.sum());
        metrics.put("activeConnections", (long) clientPool.size());
        long batches = totalBatches.sum();
        metrics.put("avgBatchSize", batches > 0 ? totalDocuments.sum() / batches : 0L);
        return metrics;
    }

    // Instantiate Logger
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchIO.class);

    private static final List<Integer> VALID_CLUSTER_VERSIONS = Arrays.asList(7, 8);
    private static final Set<Integer> DEPRECATED_CLUSTER_VERSIONS =
      new HashSet<>(Arrays.asList(5, 6));
}
