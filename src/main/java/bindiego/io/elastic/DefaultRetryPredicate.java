package bindiego.io.elastic;

import static bindiego.io.elastic.EntityUtil.parseResponse;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.apache.http.HttpEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DefaultRetryPredicate implements RetryPredicate {
  /** The logger to output status messages to. */
  private static final Logger logger = LoggerFactory.getLogger(ElasticsearchIO.class);

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
