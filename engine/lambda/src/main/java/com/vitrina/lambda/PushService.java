package com.vitrina.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PushService {
  private static final Logger logger = LoggerFactory.getLogger(PushService.class);

  private final Map<String, MessagePublisher> publishers;
  private final OrchestrationStore orchestrationStore;
  private final ObjectMapper objectMapper;

  public PushService(Map<String, MessagePublisher> publishers,
      OrchestrationStore orchestrationStore,
      ObjectMapper objectMapper) {
    this.publishers = publishers;
    this.orchestrationStore = orchestrationStore;
    this.objectMapper = objectMapper;
  }

  public Map<String, Object> push(Map<String, Object> input) {
    Map<String, Object> safeInput = input == null ? Map.of() : input;
    Object payload = safeInput.containsKey("payload") ? safeInput.get("payload") : safeInput;
    if (payload == null
        || (payload instanceof Map<?, ?> map && map.isEmpty())
        || (payload instanceof String value && value.trim().isEmpty())) {
      throw new IllegalArgumentException("payload is required");
    }

    String requestId = UUID.randomUUID().toString();
    Map<String, Object> envelope = new HashMap<>();
    envelope.put("requestId", requestId);
    envelope.put("payload", payload);

    String message;
    try {
      message = objectMapper.writeValueAsString(envelope);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Failed to serialize payload", ex);
    }

    logger.info("Sending message to SQS. requestId={}", requestId);

    Map<String, String> messageIds = new HashMap<>();
    Map<String, String> serviceStatuses = new HashMap<>();
    try {
      for (String serviceName : publishers.keySet()) {
        serviceStatuses.put(serviceName, "IN_PROGRESS");
      }
      orchestrationStore.recordStart(requestId, serviceStatuses);
      for (Map.Entry<String, MessagePublisher> entry : publishers.entrySet()) {
        messageIds.put(entry.getKey(), entry.getValue().publish(message));
      }
    } catch (RuntimeException ex) {
      logger.error("Failed to publish message to SQS. requestId={}", requestId, ex);
      throw ex;
    }

    Map<String, Object> response = new HashMap<>();
    response.put("requestId", requestId);
    response.put("messageIds", messageIds);
    return response;
  }
}
