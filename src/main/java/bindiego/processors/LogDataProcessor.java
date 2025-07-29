package bindiego.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

public class LogDataProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(LogDataProcessor.class);

    public LogDataProcessor() {
    }

    public void processLogData(ObjectNode jsonRoot) throws Exception {
        addElasticsearchTimestamp(jsonRoot);
        extractUrlMetadata(jsonRoot);
        extractResourceType(jsonRoot);
        extractLatencyMetrics(jsonRoot);
        extractCacheIdMetrics(jsonRoot);
    }

    private void addElasticsearchTimestamp(ObjectNode jsonRoot) {
        JsonNode timestampNode = jsonRoot.get("timestamp");
        if (timestampNode != null) {
            jsonRoot.put("@timestamp", timestampNode.asText());
            jsonRoot.remove("timestamp");
        }
    }

    private void extractUrlMetadata(ObjectNode jsonRoot) throws Exception {
        JsonNode httpRequestNode = jsonRoot.get("httpRequest");
        if (httpRequestNode != null) {
            JsonNode requestUrlNode = httpRequestNode.get("requestUrl");
            if (requestUrlNode != null) {
                URL url = new URL(requestUrlNode.asText());
                ((ObjectNode) httpRequestNode).put("requestDomain", url.getHost());
                ((ObjectNode) httpRequestNode).put("requestProtocol", url.getProtocol());
            }
        }
    }

    private void extractResourceType(ObjectNode jsonRoot) {
        JsonNode httpRequestNode = jsonRoot.get("httpRequest");
        if (httpRequestNode != null) {
            JsonNode requestUrlNode = httpRequestNode.get("requestUrl");
            if (requestUrlNode != null) {
                int cutoffLength = 6;
                String urlStr = requestUrlNode.asText();
                int urlLength = urlStr.length();
                if (cutoffLength < urlLength) {
                    String partialUrl = urlStr.substring(urlLength - cutoffLength);

                    if (partialUrl.contains(".")) {
                        ((ObjectNode) httpRequestNode)
                            .put("resourceType", FilenameUtils.getExtension(partialUrl));
                    }
                }
            }
        }
    }

    private void extractLatencyMetrics(ObjectNode jsonRoot) {
        JsonNode httpRequestNode = jsonRoot.get("httpRequest");
        JsonNode jsonPayloadNode = jsonRoot.get("jsonPayload");

        String latency = extractLatencyString(httpRequestNode, jsonPayloadNode);
        Double backendLatency = processBackendLatency(latency, httpRequestNode);
        Double backendLatency2 = processBackendLatency2(jsonPayloadNode, httpRequestNode, backendLatency);
        processFrontendSrtt(jsonPayloadNode, httpRequestNode);
    }

    private String extractLatencyString(JsonNode httpRequestNode, JsonNode jsonPayloadNode) {
        String latency = null;
        
        if (httpRequestNode != null) {
            JsonNode latencyNode = httpRequestNode.get("latency");
            if (latencyNode != null) {
                latency = latencyNode.asText();
                ((ObjectNode) httpRequestNode).remove("latency");
            }
        }
        
        if (latency == null && jsonPayloadNode != null) {
            JsonNode latencySecondsNode = jsonPayloadNode.get("latencySeconds");
            if (latencySecondsNode != null) {
                latency = latencySecondsNode.asText();
                ((ObjectNode) jsonPayloadNode).remove("latencySeconds");
            }
        }
        
        return latency;
    }

    private Double processBackendLatency(String latency, JsonNode httpRequestNode) {
        Double backendLatency = null;
        if (latency != null && latency.length() > 0 && httpRequestNode != null) {
            backendLatency = Double.valueOf(latency.substring(0, latency.length() - 1));
            ((ObjectNode) httpRequestNode).put("backendLatency", backendLatency);
        }
        return backendLatency;
    }

    private Double processBackendLatency2(JsonNode jsonPayloadNode, JsonNode httpRequestNode, Double backendLatency) {
        Double backendLatency2 = null;
        if (jsonPayloadNode != null && httpRequestNode != null) {
            JsonNode backendLatencyNode = jsonPayloadNode.get("backendLatency");
            if (backendLatencyNode != null) {
                String latency2 = backendLatencyNode.asText();
                if (latency2.length() > 0) {
                    backendLatency2 = Double.valueOf(latency2.substring(0, latency2.length() - 1));
                    ((ObjectNode) httpRequestNode).put("backendLatency2", backendLatency2);
                    ((ObjectNode) jsonPayloadNode).remove("backendLatency");

                    if (backendLatency != null) {
                        ((ObjectNode) httpRequestNode)
                            .put("gfeLatency", (backendLatency - backendLatency2));
                    }
                }
            }
        }
        return backendLatency2;
    }

    private void processFrontendSrtt(JsonNode jsonPayloadNode, JsonNode httpRequestNode) {
        if (jsonPayloadNode != null && httpRequestNode != null) {
            JsonNode frontendSrttNode = jsonPayloadNode.get("frontendSrtt");
            if (frontendSrttNode != null) {
                String feSrttStr = frontendSrttNode.asText();
                if (feSrttStr.length() > 0) {
                    Double feSrtt = Double.valueOf(feSrttStr.substring(0, feSrttStr.length() - 1));
                    ((ObjectNode) httpRequestNode).put("frontendSrtt", feSrtt);
                    ((ObjectNode) jsonPayloadNode).remove("frontendSrtt");
                }
            }
        }
    }

    private void extractCacheIdMetrics(ObjectNode jsonRoot) {
        JsonNode jsonPayloadNode = jsonRoot.get("jsonPayload");
        if (jsonPayloadNode != null) {
            JsonNode cacheIdNode = jsonPayloadNode.get("cacheId");
            if (cacheIdNode != null) {
                String cacheIdStr = cacheIdNode.asText();
                if (cacheIdStr.length() >= 3) {
                    String cachedIdCityCode = cacheIdStr.substring(0, 3);
                    ((ObjectNode) jsonPayloadNode).put("cacheIdCityCode", cachedIdCityCode);
                }
            }
        }
    }
}