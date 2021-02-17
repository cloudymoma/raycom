package bindiego.io.elastic;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoValue
public abstract class ConnectionConf implements Serializable {
  // Instantiate Logger
  private static final Logger logger = LoggerFactory.getLogger(ElasticsearchIO.class);

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

  @Nullable
  public abstract String getKeystorePath();

  @Nullable
  public abstract String getKeystorePassword();

  public abstract boolean isIgnoreInsecureSSL();

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
    return new AutoValue_ConnectionConf.Builder()
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

  RestClient createClient() throws IOException {
    return createClientBuilder().build();
  }
}
