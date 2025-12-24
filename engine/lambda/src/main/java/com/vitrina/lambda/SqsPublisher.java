package com.vitrina.lambda;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

public class SqsPublisher implements MessagePublisher {
  private static final Logger logger = LoggerFactory.getLogger(SqsPublisher.class);

  private final SqsClient sqsClient;
  private final String queueUrl;

  public SqsPublisher(SqsClient sqsClient, String queueUrl) {
    this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
    if (queueUrl == null || queueUrl.isBlank()) {
      throw new IllegalStateException("SQS queue URL is required");
    }
    this.queueUrl = queueUrl;
  }

  @Override
  public String publish(String payload) {
    SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
        .queueUrl(queueUrl)
        .messageBody(payload)
        .build());
    logger.info("SQS message sent. messageId={}", response.messageId());
    return response.messageId();
  }
}
