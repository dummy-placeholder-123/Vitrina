package com.vitrina.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class OrchestrationApiHandler {
  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final String APPLICATION_JSON = "application/json";

  private final PushService pushService;
  private final OrchestrationStatusReader statusReader;
  private final FindingsReader findingsReader;
  private final ObjectMapper objectMapper;

  public OrchestrationApiHandler(PushService pushService,
      OrchestrationStatusReader statusReader,
      FindingsReader findingsReader,
      ObjectMapper objectMapper) {
    this.pushService = Objects.requireNonNull(pushService, "pushService");
    this.statusReader = Objects.requireNonNull(statusReader, "statusReader");
    this.findingsReader = Objects.requireNonNull(findingsReader, "findingsReader");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public Map<String, Object> handle(Map<String, Object> event) {
    if (!isHttpEvent(event)) {
      return pushService.push(event);
    }

    try {
      String method = resolveMethod(event);
      String path = resolvePath(event);
      if (method == null) {
        return errorResponse(400, "httpMethod is required");
      }

      if ("GET".equalsIgnoreCase(method)) {
        if (path != null && path.contains("/status")) {
          return handleStatus(event);
        }
        if (path != null && path.contains("/findings")) {
          return handleFindings(event);
        }
        return errorResponse(404, "Unknown endpoint");
      }

      if (path != null && !path.contains("/scan")) {
        return errorResponse(404, "Unknown endpoint");
      }

      Map<String, Object> body = parseBody(event);
      if (body == null || body.isEmpty()) {
        return errorResponse(400, "payload is required");
      }
      Map<String, Object> response = pushService.push(body);
      return jsonResponse(202, response);
    } catch (IllegalArgumentException ex) {
      return errorResponse(400, ex.getMessage());
    } catch (Exception ex) {
      return errorResponse(500, "Internal server error");
    }
  }

  private Map<String, Object> handleStatus(Map<String, Object> event) {
    String requestId = extractRequestId(event);
    if (requestId == null || requestId.isBlank()) {
      return errorResponse(400, "requestId is required");
    }
    try {
      Map<String, String> engineStatus = statusReader.readEngineStatus(requestId);
      Map<String, Object> payload = new HashMap<>();
      payload.put("requestId", requestId);
      payload.put("engine", engineStatus);
      return jsonResponse(200, payload);
    } catch (NotFoundException ex) {
      return errorResponse(404, ex.getMessage());
    }
  }

  private Map<String, Object> handleFindings(Map<String, Object> event) {
    String requestId = extractRequestId(event);
    if (requestId == null || requestId.isBlank()) {
      return errorResponse(400, "requestId is required");
    }
    String objectKey = extractQueryParam(event, "key");
    if (objectKey == null || objectKey.isBlank()) {
      objectKey = requestId + ".json";
    }

    try {
      String findingsJson = findingsReader.readFindings(objectKey);
      return rawJsonResponse(200, findingsJson);
    } catch (NotFoundException ex) {
      return errorResponse(404, ex.getMessage());
    }
  }

  private boolean isHttpEvent(Map<String, Object> event) {
    if (event == null) {
      return false;
    }
    return event.containsKey("httpMethod") || event.containsKey("requestContext");
  }

  private Map<String, Object> parseBody(Map<String, Object> event) {
    Object rawBody = event.get("body");
    if (rawBody == null) {
      return null;
    }
    String body = rawBody.toString();
    if (isBase64Encoded(event)) {
      byte[] decoded = Base64.getDecoder().decode(body);
      body = new String(decoded, StandardCharsets.UTF_8);
    }
    if (body.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(body, Map.class);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid JSON body", ex);
    }
  }

  private String extractRequestId(Map<String, Object> event) {
    String requestId = extractPathParam(event, "requestId");
    if (requestId != null && !requestId.isBlank()) {
      return requestId;
    }
    requestId = extractQueryParam(event, "requestId");
    if (requestId != null && !requestId.isBlank()) {
      return requestId;
    }
    String path = resolvePath(event);
    if (path != null && path.contains("/")) {
      String[] parts = path.split("/");
      if (parts.length > 0) {
        String last = parts[parts.length - 1];
        if (!last.equalsIgnoreCase("status") && !last.equalsIgnoreCase("findings")) {
          return last;
        }
      }
    }
    return null;
  }

  private String extractPathParam(Map<String, Object> event, String name) {
    Object paramsObj = event.get("pathParameters");
    if (paramsObj instanceof Map<?, ?> params) {
      Object value = params.get(name);
      return value == null ? null : value.toString();
    }
    return null;
  }

  private String extractQueryParam(Map<String, Object> event, String name) {
    Object paramsObj = event.get("queryStringParameters");
    if (paramsObj instanceof Map<?, ?> params) {
      Object value = params.get(name);
      return value == null ? null : value.toString();
    }
    return null;
  }

  private Map<String, Object> jsonResponse(int statusCode, Object body) {
    try {
      return rawJsonResponse(statusCode, objectMapper.writeValueAsString(body));
    } catch (Exception ex) {
      return errorResponse(500, "Failed to serialize response");
    }
  }

  private Map<String, Object> rawJsonResponse(int statusCode, String body) {
    Map<String, Object> response = new HashMap<>();
    response.put("statusCode", statusCode);
    response.put("headers", Map.of(CONTENT_TYPE_HEADER, APPLICATION_JSON));
    response.put("body", body);
    response.put("isBase64Encoded", false);
    return response;
  }

  private Map<String, Object> errorResponse(int statusCode, String message) {
    Map<String, Object> payload = Map.of("error", message);
    return jsonResponse(statusCode, payload);
  }

  private boolean isBase64Encoded(Map<String, Object> event) {
    Object encoded = event.get("isBase64Encoded");
    return encoded instanceof Boolean && (Boolean) encoded;
  }

  private String resolveMethod(Map<String, Object> event) {
    String method = stringValue(event.get("httpMethod"));
    if (method != null) {
      return method;
    }
    Object contextObj = event.get("requestContext");
    if (contextObj instanceof Map<?, ?> context) {
      Object httpObj = context.get("http");
      if (httpObj instanceof Map<?, ?> http) {
        return stringValue(http.get("method"));
      }
    }
    return null;
  }

  private String resolvePath(Map<String, Object> event) {
    String path = stringValue(event.get("path"));
    if (path != null) {
      return path;
    }
    path = stringValue(event.get("rawPath"));
    if (path != null) {
      return path;
    }
    Object contextObj = event.get("requestContext");
    if (contextObj instanceof Map<?, ?> context) {
      Object httpObj = context.get("http");
      if (httpObj instanceof Map<?, ?> http) {
        return stringValue(http.get("path"));
      }
    }
    return null;
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString();
    return text.isBlank() ? null : text;
  }
}
