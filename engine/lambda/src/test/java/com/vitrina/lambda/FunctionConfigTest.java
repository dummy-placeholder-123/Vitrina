package com.vitrina.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class FunctionConfigTest {

  @Test
  void pushToSqsReturnsMessageIdAndCorrelationId() {
    MessagePublisher publisher = payload -> "msg-123";
    FunctionConfig config = new FunctionConfig();

    Function<Map<String, Object>, Map<String, String>> fn = config.pushToSqs(publisher);
    Map<String, String> response = fn.apply(Map.of("message", "hello", "correlationId", "corr-1"));

    assertEquals("msg-123", response.get("messageId"));
    assertEquals("corr-1", response.get("correlationId"));
  }

  @Test
  void pushToSqsRequiresMessage() {
    MessagePublisher publisher = payload -> "msg-123";
    FunctionConfig config = new FunctionConfig();

    Function<Map<String, Object>, Map<String, String>> fn = config.pushToSqs(publisher);

    assertThrows(IllegalArgumentException.class, () -> fn.apply(Map.of()));
  }
}
