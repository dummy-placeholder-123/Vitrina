package com.vitrina.servicea;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

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
  private final String mergeQueueUrl;
  private final List<String> expectedServices;

  public SqsToS3Worker(SqsClient sqsClient,
      S3Client s3Client,
      DynamoDbClient dynamoDbClient,
      ObjectMapper objectMapper,
      @Value("${app.sqs.queue-url}") String queueUrl,
      @Value("${app.s3.bucket-name}") String bucketName,
      @Value("${app.service.name}") String serviceName,
      @Value("${app.dynamo.table-name}") String tableName,
      @Value("${app.sqs.merge-queue-url}") String mergeQueueUrl,
      @Value("${app.expected-services}") String expectedServices) {
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
    if (mergeQueueUrl == null || mergeQueueUrl.isBlank()) {
      throw new IllegalStateException("Merge queue URL is required");
    }
    this.queueUrl = queueUrl;
    this.bucketName = bucketName;
    this.serviceName = serviceName;
    this.tableName = tableName;
    this.mergeQueueUrl = mergeQueueUrl;
    this.expectedServices = parseExpectedServices(expectedServices);
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

      updateStatus(requestId, "DONE", key);
      tryTriggerMerge(requestId);

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

  private void updateStatus(String requestId, String status, String outputKey) {
    UpdateItemRequest request = UpdateItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .updateExpression("SET #engine.#service = :status, #outputs.#service = :outputKey")
        .expressionAttributeNames(Map.of(
            "#engine", "engine",
            "#outputs", "outputs",
            "#service", serviceName))
        .expressionAttributeValues(Map.of(
            ":status", AttributeValue.builder().s(status).build(),
            ":outputKey", AttributeValue.builder().s(outputKey).build()))
        .build();
    dynamoDbClient.updateItem(request);
  }

  private void tryTriggerMerge(String requestId) {
    if (expectedServices.isEmpty()) {
      logger.warn("Expected services list is empty. Skipping merge trigger. requestId={}", requestId);
      return;
    }
    if (!markMergeInProgress(requestId)) {
      return;
    }
    try {
      String body = objectMapper.writeValueAsString(Map.of("requestId", requestId));
      sqsClient.sendMessage(SendMessageRequest.builder()
          .queueUrl(mergeQueueUrl)
          .messageBody(body)
          .build());
      logger.info("Triggered merge. requestId={}", requestId);
    } catch (Exception ex) {
      logger.error("Failed to send merge event. requestId={}", requestId, ex);
      resetMergeStatus(requestId);
      throw new RuntimeException(ex);
    }
  }

  private boolean markMergeInProgress(String requestId) {
    Map<String, String> attributeNames = new LinkedHashMap<>();
    attributeNames.put("#engine", "engine");
    attributeNames.put("#finalStatus", "finalStatus");

    Map<String, AttributeValue> attributeValues = new LinkedHashMap<>();
    attributeValues.put(":pending", AttributeValue.builder().s("PENDING").build());
    attributeValues.put(":merging", AttributeValue.builder().s("MERGING").build());
    attributeValues.put(":done", AttributeValue.builder().s("DONE").build());

    StringBuilder condition = new StringBuilder("#finalStatus = :pending");
    int index = 0;
    for (String service : expectedServices) {
      String key = "#svc" + index++;
      attributeNames.put(key, service);
      condition.append(" AND #engine.").append(key).append(" = :done");
    }

    UpdateItemRequest request = UpdateItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .updateExpression("SET #finalStatus = :merging")
        .conditionExpression(condition.toString())
        .expressionAttributeNames(attributeNames)
        .expressionAttributeValues(attributeValues)
        .build();
    try {
      dynamoDbClient.updateItem(request);
      return true;
    } catch (ConditionalCheckFailedException ex) {
      return false;
    }
  }

  private void resetMergeStatus(String requestId) {
    UpdateItemRequest request = UpdateItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .updateExpression("SET #finalStatus = :pending")
        .conditionExpression("#finalStatus = :merging")
        .expressionAttributeNames(Map.of("#finalStatus", "finalStatus"))
        .expressionAttributeValues(Map.of(
            ":pending", AttributeValue.builder().s("PENDING").build(),
            ":merging", AttributeValue.builder().s("MERGING").build()))
        .build();
    try {
      dynamoDbClient.updateItem(request);
    } catch (Exception ex) {
      logger.warn("Failed to reset merge status. requestId={}", requestId, ex);
    }
  }

  private List<String> parseExpectedServices(String expectedServices) {
    if (expectedServices == null || expectedServices.isBlank()) {
      return List.of();
    }
    return Arrays.stream(expectedServices.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .distinct()
        .collect(Collectors.toList());
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
