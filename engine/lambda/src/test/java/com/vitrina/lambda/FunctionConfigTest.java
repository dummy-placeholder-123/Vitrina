package com.vitrina.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class FunctionConfigTest {

  @Test
  void pushToSqsReturnsMessageIdsAndRequestId() throws Exception {
    AtomicReference<String> messageBody = new AtomicReference<>();
    Map<String, MessagePublisher> publishers = Map.of(
        "serviceA", payload -> {
          messageBody.set(payload);
          return "msg-123";
        },
        "serviceB", payload -> "msg-456");
    CapturingStore store = new CapturingStore();
    ObjectMapper objectMapper = new ObjectMapper();

    PushService pushService = new PushService(publishers, store, objectMapper);
    Function<Map<String, Object>, Map<String, Object>> fn = pushService::push;
    Map<String, Object> response = fn.apply(Map.of("payload", Map.of("message", "hello")));
    @SuppressWarnings("unchecked")
    Map<String, String> messageIds = (Map<String, String>) response.get("messageIds");

    assertEquals("msg-123", messageIds.get("serviceA"));
    assertEquals("msg-456", messageIds.get("serviceB"));
    assertNotNull(response.get("requestId"));
    assertNotNull(store.requestId);
    assertEquals("IN_PROGRESS", store.statuses.get("serviceA"));
    assertEquals("IN_PROGRESS", store.statuses.get("serviceB"));

    Map<String, Object> envelope = objectMapper.readValue(
        messageBody.get(), new TypeReference<>() {});
    assertNotNull(envelope.get("requestId"));
    assertEquals(Map.of("message", "hello"), envelope.get("payload"));
  }

  @Test
  void pushToSqsRequiresPayload() {
    Map<String, MessagePublisher> publishers = Map.of(
        "serviceA", payload -> "msg-123",
        "serviceB", payload -> "msg-456");
    CapturingStore store = new CapturingStore();
    ObjectMapper objectMapper = new ObjectMapper();

    PushService pushService = new PushService(publishers, store, objectMapper);
    Function<Map<String, Object>, Map<String, Object>> fn = pushService::push;

    assertThrows(IllegalArgumentException.class, () -> fn.apply(Map.of()));
  }

  private static final class CapturingStore implements OrchestrationStore {
    private String requestId;
    private Map<String, String> statuses = new HashMap<>();

    @Override
    public void recordStart(String requestId, Map<String, String> serviceStatuses) {
      this.requestId = requestId;
      this.statuses = new HashMap<>(serviceStatuses);
    }
  }
}
