package com.vitrina.serviceb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
public class SqsToS3Worker implements CommandLineRunner, DisposableBean {
  private static final Logger logger = LoggerFactory.getLogger(SqsToS3Worker.class);
  private static final DateTimeFormatter PATH_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final SqsClient sqsClient;
  private final S3Client s3Client;
  private final DynamoDbClient dynamoDbClient;
  private final ObjectMapper objectMapper;
  private final String queueUrl;
  private final String bucketName;
  private final String serviceName;
  private final String tableName;

  public SqsToS3Worker(SqsClient sqsClient,
      S3Client s3Client,
      DynamoDbClient dynamoDbClient,
      ObjectMapper objectMapper,
      @Value("${app.sqs.queue-url}") String queueUrl,
      @Value("${app.s3.bucket-name}") String bucketName,
      @Value("${app.service.name}") String serviceName,
      @Value("${app.dynamo.table-name}") String tableName) {
    this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient, "dynamoDbClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    if (queueUrl == null || queueUrl.isBlank()) {
      throw new IllegalStateException("SQS queue URL is required");
    }
    if (bucketName == null || bucketName.isBlank()) {
      throw new IllegalStateException("S3 bucket name is required");
    }
    if (serviceName == null || serviceName.isBlank()) {
      throw new IllegalStateException("Service name is required");
    }
    if (tableName == null || tableName.isBlank()) {
      throw new IllegalStateException("DynamoDB table name is required");
    }
    this.queueUrl = queueUrl;
    this.bucketName = bucketName;
    this.serviceName = serviceName;
    this.tableName = tableName;
  }

  @Override
  public void run(String... args) {
    logger.info("Started SQS worker. service={}, queueUrl={}, bucket={}",
        serviceName, queueUrl, bucketName);
    pollLoop();
  }

  private void pollLoop() {
    ReceiveMessageRequest request = ReceiveMessageRequest.builder()
        .queueUrl(queueUrl)
        .maxNumberOfMessages(10)
        .waitTimeSeconds(20)
        .build();

    while (running.get()) {
      try {
        List<Message> messages = sqsClient.receiveMessage(request).messages();
        for (Message message : messages) {
          handleMessage(message);
        }
      } catch (Exception ex) {
        logger.error("Failed to poll SQS", ex);
        sleepQuietly(5000);
      }
    }
  }

  private void handleMessage(Message message) {
    try {
      Map<String, Object> envelope = objectMapper.readValue(
          message.body(), new TypeReference<>() {});
      String requestId = Objects.toString(envelope.get("requestId"), "").trim();
      if (requestId.isEmpty()) {
        throw new IllegalArgumentException("requestId is required in payload");
      }

      Map<String, Object> payload = normalizePayload(envelope.get("payload"));
      payload.put("serviceName", serviceName);

      Map<String, Object> output = new LinkedHashMap<>();
      output.put("requestId", requestId);
      output.put("payload", payload);

      long delayMillis = ThreadLocalRandom.current().nextLong(180_000, 300_001);
      logger.info("Processing delay before upload. requestId={}, delayMs={}", requestId, delayMillis);
      sleepQuietly(delayMillis);

      String key = serviceName + "/" + PATH_FORMATTER.format(Instant.now())
          + "/" + requestId + "-" + message.messageId() + ".json";
      PutObjectRequest putRequest = PutObjectRequest.builder()
          .bucket(bucketName)
          .key(key)
          .contentType("application/json")
          .build();
      s3Client.putObject(putRequest,
          RequestBody.fromBytes(objectMapper.writeValueAsBytes(output)));

      updateStatus(requestId, "DONE");

      sqsClient.deleteMessage(DeleteMessageRequest.builder()
          .queueUrl(queueUrl)
          .receiptHandle(message.receiptHandle())
          .build());

      logger.info("Stored message in S3. key={}, messageId={}", key, message.messageId());
    } catch (Exception ex) {
      logger.error("Failed to process message. messageId={}", message.messageId(), ex);
      throw new RuntimeException(ex);
    }
  }

  private Map<String, Object> normalizePayload(Object rawPayload) {
    if (rawPayload instanceof Map<?, ?> rawMap) {
      Map<String, Object> payload = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        payload.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      return payload;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    if (rawPayload != null) {
      payload.put("payload", rawPayload);
    }
    return payload;
  }

  private void updateStatus(String requestId, String status) {
    UpdateItemRequest request = UpdateItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .updateExpression("SET #engine.#service = :status")
        .expressionAttributeNames(Map.of(
            "#engine", "engine",
            "#service", serviceName))
        .expressionAttributeValues(Map.of(
            ":status", AttributeValue.builder().s(status).build()))
        .build();
    dynamoDbClient.updateItem(request);
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void destroy() {
    running.set(false);
  }
}
