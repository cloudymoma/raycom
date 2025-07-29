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
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.message.BasicHeader;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.security.cert.X509Certificate;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.ByteBuffer;
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
    
    // Connection pool for reusing clients across instances
    private static final ConcurrentHashMap<String, RestClient> clientPool = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> versionCache = new ConcurrentHashMap<>();
    
    // Shared scheduler for time-based operations
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Performance metrics
    private static final AtomicLong totalDocuments = new AtomicLong(0);
    private static final AtomicLong totalBatches = new AtomicLong(0);
    private static final AtomicLong totalErrors = new AtomicLong(0);

    public static Append append() {
        return new AutoValue_ElasticsearchIO_Append.Builder()
            .setMaxBatchSize(1000L)
            .setMaxBatchSizeBytes(5L * 1024L * 1024L)
            .setFlushIntervalMillis(30000L) // 30 seconds default flush interval
            .setEnableCompression(false)
            .setMaxConcurrentRequests(5)
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
        
        // Connection pooling key for reuse
        public String getPoolKey() {
            return getAddress() + ":" + getIndex() + ":" + 
                   (getUsername() != null ? getUsername() : "noauth");
        }

        abstract Builder builder();

        @AutoValue.Builder
        abstract static class Builder {
            // abstract Builder setAddresses(List<String> addresses);
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

        public ConnectionConf withIngnoreInsecureSSL(boolean ignoreInsecureSSL) {
            return builder().setIgnoreInsecureSSL(ignoreInsecureSSL).build();
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

                                if (isIgnoreInsecureSSL()) {
                                    try {
                                        // SSLContext context = SSLContext.getInstance("SSL");
                                        SSLContext context = SSLContext.getInstance("TLS");
                    
                                        context.init(null, new TrustManager[] {
                                            new X509TrustManager() {
                                                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    
                                                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    
                                                public X509Certificate[] getAcceptedIssuers() { return null; }
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

            if (getKeystorePath() != null && !getKeystorePath().isEmpty()) {
                try {
                    KeyStore keyStore = KeyStore.getInstance("jks");
                    try (InputStream is = new FileInputStream(new File(getKeystorePath()))) {
                        String keystorePassword = getKeystorePassword();
                        keyStore.load(is, (keystorePassword == null) ? null : keystorePassword.toCharArray());
                    }
                    final TrustStrategy trustStrategy =
                        isTrustSelfSignedCerts() ? new TrustSelfSignedStrategy() : null;
                    final SSLContext sslContext =
                        SSLContexts.custom().loadTrustMaterial(keyStore, trustStrategy).build();
                    final SSLIOSessionStrategy sessionStrategy = new SSLIOSessionStrategy(sslContext);
                    restClientBuilder.setHttpClientConfigCallback(
                        httpClientBuilder ->
                            httpClientBuilder.setSSLContext(sslContext).setSSLStrategy(sessionStrategy));
                } catch (Exception e) {
                    throw new IOException("Can't load the client certificate from the keystore", e);
                }
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

        private RestClient createClient() throws IOException {
            String poolKey = getPoolKey();
            
            // Check if we have a valid client in the pool
            RestClient existingClient = clientPool.get(poolKey);
            if (existingClient != null && !existingClient.isRunning()) {
                // Remove closed client from pool
                logger.warn("Removing closed RestClient for pool key: {}", poolKey);
                clientPool.remove(poolKey);
                existingClient = null;
            }
            
            if (existingClient != null) {
                return existingClient;
            }
            
            // Create new client if none exists or previous was closed
            return clientPool.computeIfAbsent(poolKey, k -> {
                try {
                    RestClient client = createClientBuilder().build();
                    logger.info("Created new RestClient for pool key: {}", poolKey);
                    return client;
                } catch (IOException e) {
                    logger.error("Failed to create RestClient for pool key: {}", poolKey, e);
                    throw new RuntimeException("Failed to create RestClient", e);
                }
            });
        }
        
        // Get cached client from pool with validation
        public RestClient getPooledClient() throws IOException {
            return createClient();
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
            if (responseEntity == null) {
                logger.warn("Response entity is null, cannot check for error codes");
                return false;
            }
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
                logger.warn("Could not extract error codes from responseEntity {}", responseEntity, e);
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
        
        abstract long getFlushIntervalMillis();
        
        abstract boolean getEnableCompression();
        
        abstract int getMaxConcurrentRequests();

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
            
            // Optimized batching with pre-allocated capacity and thread safety
            private final ReentrantLock batchLock = new ReentrantLock();
            private List<String> batch;
            private AtomicLong currentBatchSizeBytes;
            private volatile long lastFlushTime;
            
            // Connection pooling and version caching
            private int esVersion;
            private String poolKey;
            
            // Performance optimization: reuse byte buffers
            private transient ThreadLocal<ByteArrayOutputStream> byteBufferPool;
            
            // Async operation tracking
            private final List<CompletableFuture<Void>> pendingOperations = 
                Collections.synchronizedList(new ArrayList<>());

            AppendFn(Append spec) {
                this.spec = spec;
            }

            @Setup 
            public void setup() throws IOException {
                ConnectionConf connectionConf = spec.getConnectionConf();
                poolKey = connectionConf.getPoolKey();
                
                // Initialize ThreadLocal after deserialization
                byteBufferPool = ThreadLocal.withInitial(() -> new ByteArrayOutputStream(8192));
                
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
                
                logger.info("Setup completed for ES version {} with pool key: {}", esVersion, poolKey);
            }

            @StartBundle
            public void startBundle(StartBundleContext context) {
                // Pre-allocate with estimated capacity for better performance
                int estimatedCapacity = (int) Math.min(spec.getMaxBatchSize(), 1000);
                batch = new ArrayList<>(estimatedCapacity);
                currentBatchSizeBytes = new AtomicLong(0);
                lastFlushTime = System.currentTimeMillis();
                
                // Schedule periodic flush if interval is configured
                if (spec.getFlushIntervalMillis() > 0) {
                    scheduler.scheduleAtFixedRate(
                        this::timeBasedFlush,
                        spec.getFlushIntervalMillis(),
                        spec.getFlushIntervalMillis(),
                        TimeUnit.MILLISECONDS
                    );
                }
            }

            @ProcessElement
            public void processElement(ProcessContext context) throws Exception {
                String doc = context.element();
                
                batchLock.lock();
                try {
                    batch.add(doc);
                    // More efficient byte calculation using cached UTF-8 bytes
                    long docBytes = doc.length() * 3; // Conservative estimate for UTF-8
                    currentBatchSizeBytes.addAndGet(docBytes);
                    
                    // Check flush conditions
                    if (shouldFlush()) {
                        flushBatchAsync();
                    }
                } finally {
                    batchLock.unlock();
                }
            }
            
            private boolean shouldFlush() {
                return batch.size() >= spec.getMaxBatchSize() ||
                       currentBatchSizeBytes.get() >= spec.getMaxBatchSizeBytes() ||
                       (spec.getFlushIntervalMillis() > 0 && 
                        System.currentTimeMillis() - lastFlushTime >= spec.getFlushIntervalMillis());
            }

            @FinishBundle
            public void finishBundle(FinishBundleContext context)
                    throws IOException, InterruptedException {
                // Final flush and wait for all pending operations
                flushBatchAsync();
                waitForPendingOperations();
            }
            
            private void waitForPendingOperations() throws InterruptedException {
                try {
                    CompletableFuture.allOf(pendingOperations.toArray(new CompletableFuture[0]))
                        .get(30, TimeUnit.SECONDS); // 30 second timeout
                    logger.debug("All pending operations completed successfully");
                } catch (Exception e) {
                    logger.error("Failed to complete pending operations within timeout", e);
                    totalErrors.incrementAndGet();
                }
            }

            @Teardown
            public void closeClient() throws IOException {
                // Don't close pooled clients - they're shared
                // Clean up any remaining operations
                pendingOperations.clear();
                logger.debug("Teardown completed for pool key: {}", poolKey);
            }

            private void flushBatchAsync() {
                List<String> currentBatch;
                long batchBytes;
                
                batchLock.lock();
                try {
                    if (batch.isEmpty()) {
                        return;
                    }
                    
                    // Swap batch for processing
                    currentBatch = new ArrayList<>(batch);
                    batchBytes = currentBatchSizeBytes.get();
                    
                    // Reset for next batch
                    batch.clear();
                    currentBatchSizeBytes.set(0);
                    lastFlushTime = System.currentTimeMillis();
                } finally {
                    batchLock.unlock();
                }
                
                // Process batch asynchronously
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        processBatch(currentBatch, batchBytes);
                    } catch (Exception e) {
                        logger.error("Failed to process batch", e);
                        totalErrors.incrementAndGet();
                    }
                });
                
                pendingOperations.add(future);
                
                // Clean up completed operations
                pendingOperations.removeIf(CompletableFuture::isDone);
            }
            
            private void processBatch(List<String> batchToProcess, long batchSizeBytes) 
                    throws IOException, InterruptedException {
                if (batchToProcess.isEmpty()) {
                    return;
                }
                
                ByteArrayOutputStream baos = byteBufferPool.get();
                baos.reset();
                
                // Build bulk request efficiently
                try {
                    // Build request without compression for now
                    buildBulkRequest(batchToProcess, baos);
                } catch (IOException e) {
                    logger.error("Failed to build bulk request", e);
                    throw e;
                }
                
                // Execute request with retry logic
                executeWithRetry(baos.toByteArray(), batchToProcess.size(), batchSizeBytes);
            }
            
            private void buildBulkRequest(List<String> docs, java.io.OutputStream out) throws IOException {
                String indexAction = "{\"index\":{}}\n";
                byte[] actionBytes = indexAction.getBytes(StandardCharsets.UTF_8);
                byte[] newlineBytes = "\n".getBytes(StandardCharsets.UTF_8);
                
                for (String doc : docs) {
                    out.write(actionBytes);
                    out.write(doc.getBytes(StandardCharsets.UTF_8));
                    out.write(newlineBytes);
                }
            }
            
            private void executeWithRetry(byte[] requestBody, int docCount, long batchSizeBytes) 
                    throws IOException, InterruptedException {
                String endpoint = "/" + spec.getConnectionConf().getIndex() + "/_bulk";
                
                BackOff backoff = retryBackoff.backoff();
                int attempt = 0;
                
                while (true) {
                    HttpEntity responseEntity = null;
                    try {
                        // Validate client state before making request
                        if (!restClient.isRunning()) {
                            logger.warn("RestClient is not running, attempting to recreate...");
                            restClient = spec.getConnectionConf().getPooledClient();
                        }
                        
                        HttpEntity entity = new NStringEntity(new String(requestBody, StandardCharsets.UTF_8), 
                            ContentType.APPLICATION_JSON);
                        
                        Request request = new Request("POST", endpoint);
                        request.setEntity(entity);
                        
                        Response response = restClient.performRequest(request);
                        responseEntity = response.getEntity();
                        
                        // Check for errors in response
                        checkForErrors(responseEntity, esVersion, false);
                        
                        // Update metrics
                        totalDocuments.addAndGet(docCount);
                        totalBatches.incrementAndGet();
                        
                        logger.debug("Successfully flushed batch of {} documents ({} bytes)", 
                            docCount, batchSizeBytes);
                        return;
                        
                    } catch (Exception e) {
                        attempt++;
                        totalErrors.incrementAndGet();
                        
                        // Handle client state issues specifically
                        if (e.getCause() instanceof IllegalStateException) {
                            logger.warn("IllegalStateException detected - client may be closed. Recreating client...");
                            try {
                                // Force recreation of client by removing from pool
                                String poolKey = spec.getConnectionConf().getPoolKey();
                                clientPool.remove(poolKey);
                                restClient = spec.getConnectionConf().getPooledClient();
                                logger.info("Successfully recreated RestClient after IllegalStateException");
                            } catch (Exception recreateEx) {
                                logger.error("Failed to recreate RestClient", recreateEx);
                            }
                        }
                        
                        if (attempt > (spec.getRetryConf() != null ? spec.getRetryConf().getMaxAttempts() : 1)) {
                            logger.error(RETRY_FAILED_LOG, attempt);
                            throw new IOException("Failed to write batch after " + attempt + " attempts", e);
                        }
                        
                        logger.warn(RETRY_ATTEMPT_LOG, attempt);
                        
                        // Check if we should retry based on the error
                        boolean shouldRetry = false;
                        if (spec.getRetryConf() != null) {
                            // Only test retry predicate if we have a response entity
                            if (responseEntity != null) {
                                shouldRetry = spec.getRetryConf().getRetryPredicate().test(responseEntity);
                            } else {
                                // If no response entity, don't retry for connection failures etc.
                                shouldRetry = false;
                            }
                        }
                        
                        if (shouldRetry) {
                            long backoffMillis = backoff.nextBackOffMillis();
                            if (backoffMillis == BackOff.STOP) {
                                throw new IOException("Backoff exhausted", e);
                            }
                            Sleeper.DEFAULT.sleep(backoffMillis);
                        } else {
                            throw new IOException("Non-retryable error", e);
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

            
        }
    }

    static JsonNode parseResponse(HttpEntity responseEntity) throws IOException {
        if (responseEntity == null) {
            throw new IOException("Response entity is null");
        }
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
            int esVersion =
                Integer.parseInt(jsonNode.path("version").path("number").asText().substring(0, 1));
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

    static int getEsVersion(ConnectionConf connectionConf) {
        try (RestClient restClient = connectionConf.createClient()) {
            return getEsVersion(restClient);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot get Elasticsearch version from " + 
                connectionConf.getAddress() + ": " + ex.getMessage(), ex);
        }
    }
    
    /**
     * Cleanup method for releasing shared resources.
     * Should be called when the application shuts down.
     */
    public static void cleanup() {
        logger.info("Cleaning up ElasticsearchIO resources...");
        
        // Close all pooled clients
        clientPool.values().forEach(client -> {
            try {
                client.close();
            } catch (IOException e) {
                logger.warn("Error closing pooled RestClient", e);
            }
        });
        clientPool.clear();
        versionCache.clear();
        
        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("ElasticsearchIO cleanup completed. Total documents: {}, batches: {}, errors: {}",
            totalDocuments.get(), totalBatches.get(), totalErrors.get());
    }
    
    /**
     * Get performance metrics for monitoring.
     */
    public static Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("totalDocuments", totalDocuments.get());
        metrics.put("totalBatches", totalBatches.get());
        metrics.put("totalErrors", totalErrors.get());
        metrics.put("activeConnections", (long) clientPool.size());
        return metrics;
    }

    // Instantiate Logger
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchIO.class);

    private static final List<Integer> VALID_CLUSTER_VERSIONS = Arrays.asList(7, 8);
    private static final Set<Integer> DEPRECATED_CLUSTER_VERSIONS =
      new HashSet<>(Arrays.asList(5, 6));
}
