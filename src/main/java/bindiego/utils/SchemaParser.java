package bindiego.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.util.StreamUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A helper object to parse a JSON on GCS. Usage is to provide A GCS URL and it will return
 * a JSONObject of the file
 */
public class SchemaParser {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaParser.class);

  /**
   * Parses a JSON file and Returns a JSONObject containing the necessary source, sink, and schema
   * information.
   *
   * @param pathToJSON the JSON file location so we can download and parse it
   * @return the parsed JSONObject
   */
  public JSONObject parseSchema(String pathToJSON) throws Exception {

    try {
      ReadableByteChannel readableByteChannel =
          FileSystems.open(FileSystems.matchNewResource(pathToJSON, false));

      String json = new String(
          StreamUtils.getBytesWithoutClosing(Channels.newInputStream(readableByteChannel)));

      return new JSONObject(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Helper function to get a Avro schema from GCS
   */
  public String getAvroSchema(String schemaPath) throws IOException {
      ReadableByteChannel chan = 
          FileSystems.open(FileSystems.matchNewResource(schemaPath, false));

      try (InputStream stream = Channels.newInputStream(chan)) {
          BufferedReader streamReader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
          StringBuilder dataBuilder = new StringBuilder();

          String line;
          while ((line = streamReader.readLine()) != null) {
            dataBuilder.append(line);
          }

          return dataBuilder.toString();
      }
  }
}
