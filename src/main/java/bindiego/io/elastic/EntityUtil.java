package bindiego.io.elastic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.apache.http.HttpEntity;

final class EntityUtil {
  private static final ObjectMapper mapper = new ObjectMapper();

  private EntityUtil() {}

  static JsonNode parseResponse(HttpEntity responseEntity) throws IOException {
    return mapper.readValue(responseEntity.getContent(), JsonNode.class);
  }
}
