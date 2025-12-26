package com.vitrina.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class FunctionConfigTest {

  @Test
  void pushToSqsReturnsMessageIdsAndCorrelationId() {
    Map<String, MessagePublisher> publishers = Map.of(
        "service-a", payload -> "msg-123",
        "service-b", payload -> "msg-456");
    FunctionConfig config = new FunctionConfig();

    Function<Map<String, Object>, Map<String, Object>> fn = config.pushToSqs(publishers);
    Map<String, Object> response = fn.apply(Map.of("message", "hello", "correlationId", "corr-1"));
    @SuppressWarnings("unchecked")
    Map<String, String> messageIds = (Map<String, String>) response.get("messageIds");

    assertEquals("msg-123", messageIds.get("service-a"));
    assertEquals("msg-456", messageIds.get("service-b"));
    assertEquals("corr-1", response.get("correlationId"));
  }

  @Test
  void pushToSqsRequiresMessage() {
    Map<String, MessagePublisher> publishers = Map.of(
        "service-a", payload -> "msg-123",
        "service-b", payload -> "msg-456");
    FunctionConfig config = new FunctionConfig();

    Function<Map<String, Object>, Map<String, Object>> fn = config.pushToSqs(publishers);

    assertThrows(IllegalArgumentException.class, () -> fn.apply(Map.of()));
  }
}
