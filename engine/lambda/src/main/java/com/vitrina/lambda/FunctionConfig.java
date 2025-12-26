package com.vitrina.lambda;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class FunctionConfig {
  private static final Logger logger = LoggerFactory.getLogger(FunctionConfig.class);

  @Bean
  public SqsClient sqsClient() {
    return SqsClient.builder().build();
  }

  @Bean
  public Map<String, MessagePublisher> messagePublishers(SqsClient sqsClient,
      @Value("${app.sqs.queue-url-a}") String queueUrlA,
      @Value("${app.sqs.queue-url-b}") String queueUrlB) {
    Map<String, MessagePublisher> publishers = new HashMap<>();
    publishers.put("service-a", new SqsPublisher(sqsClient, queueUrlA));
    publishers.put("service-b", new SqsPublisher(sqsClient, queueUrlB));
    return Map.copyOf(publishers);
  }

  @Bean
  public Function<Map<String, Object>, Map<String, Object>> pushToSqs(
      Map<String, MessagePublisher> publishers) {
    return input -> {
      Map<String, Object> safeInput = input == null ? Map.of() : input;
      String message = Objects.toString(safeInput.get("message"), "").trim();
      if (message.isEmpty()) {
        throw new IllegalArgumentException("message is required");
      }

      String correlationId = Objects.toString(safeInput.get("correlationId"), "").trim();
      if (!correlationId.isEmpty()) {
        logger.info("Sending message to SQS. correlationId={}, messageLength={}",
            correlationId, message.length());
      } else {
        logger.info("Sending message to SQS. messageLength={}", message.length());
      }

      Map<String, String> messageIds = new HashMap<>();
      try {
        for (Map.Entry<String, MessagePublisher> entry : publishers.entrySet()) {
          messageIds.put(entry.getKey(), entry.getValue().publish(message));
        }
      } catch (RuntimeException ex) {
        if (!correlationId.isEmpty()) {
          logger.error("Failed to publish message to SQS. correlationId={}", correlationId, ex);
        } else {
          logger.error("Failed to publish message to SQS.", ex);
        }
        throw ex;
      }

      Map<String, Object> response = new HashMap<>();
      response.put("messageIds", messageIds);
      if (!correlationId.isEmpty()) {
        response.put("correlationId", correlationId);
      }
      return response;
    };
  }
}
