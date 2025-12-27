package com.vitrina.servicemerge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
public class SqsMergeWorker implements CommandLineRunner, DisposableBean {
  private static final Logger logger = LoggerFactory.getLogger(SqsMergeWorker.class);
  private static final int WAIT_TIME_SECONDS = 20;
  private static final long IDLE_SLEEP_MILLIS = 40_000;

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final SqsClient sqsClient;
  private final S3Client s3Client;
  private final DynamoDbClient dynamoDbClient;
  private final ObjectMapper objectMapper;
  private final String queueUrl;
  private final String serviceABucket;
  private final String serviceBBucket;
  private final String orchestratedBucket;
  private final String tableName;

  public SqsMergeWorker(SqsClient sqsClient,
      S3Client s3Client,
      DynamoDbClient dynamoDbClient,
      ObjectMapper objectMapper,
      @Value("${app.sqs.queue-url}") String queueUrl,
      @Value("${app.s3.service-a-bucket}") String serviceABucket,
      @Value("${app.s3.service-b-bucket}") String serviceBBucket,
      @Value("${app.s3.orchestrated-bucket}") String orchestratedBucket,
      @Value("${app.dynamo.table-name}") String tableName) {
    this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient, "dynamoDbClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    if (queueUrl == null || queueUrl.isBlank()) {
      throw new IllegalStateException("SQS queue URL is required");
    }
    if (serviceABucket == null || serviceABucket.isBlank()) {
      throw new IllegalStateException("Service A bucket is required");
    }
    if (serviceBBucket == null || serviceBBucket.isBlank()) {
      throw new IllegalStateException("Service B bucket is required");
    }
    if (orchestratedBucket == null || orchestratedBucket.isBlank()) {
      throw new IllegalStateException("Orchestrated bucket is required");
    }
    if (tableName == null || tableName.isBlank()) {
      throw new IllegalStateException("DynamoDB table name is required");
    }
    this.queueUrl = queueUrl;
    this.serviceABucket = serviceABucket;
    this.serviceBBucket = serviceBBucket;
    this.orchestratedBucket = orchestratedBucket;
    this.tableName = tableName;
  }

  @Override
  public void run(String... args) {
    logger.info("Started merge worker. queueUrl={}, orchestratedBucket={}", queueUrl, orchestratedBucket);
    pollLoop();
  }

  private void pollLoop() {
    ReceiveMessageRequest request = ReceiveMessageRequest.builder()
        .queueUrl(queueUrl)
        .maxNumberOfMessages(5)
        .waitTimeSeconds(WAIT_TIME_SECONDS)
        .build();

    while (running.get()) {
      try {
        List<Message> messages = sqsClient.receiveMessage(request).messages();
        if (messages.isEmpty()) {
          sleepQuietly(IDLE_SLEEP_MILLIS);
          continue;
        }
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
      Map<String, Object> payload = objectMapper.readValue(
          message.body(), new TypeReference<>() {});
      String requestId = Objects.toString(payload.get("requestId"), "").trim();
      if (requestId.isEmpty()) {
        throw new IllegalArgumentException("requestId is required in payload");
      }

      Map<String, String> outputs = readOutputs(requestId);
      String serviceAKey = outputs.get("serviceA");
      String serviceBKey = outputs.get("serviceB");
      if (serviceAKey == null || serviceAKey.isBlank()) {
        throw new IllegalStateException("Missing output key for serviceA");
      }
      if (serviceBKey == null || serviceBKey.isBlank()) {
        throw new IllegalStateException("Missing output key for serviceB");
      }

      Map<String, Object> serviceAOutput = readOutput(serviceABucket, serviceAKey);
      Map<String, Object> serviceBOutput = readOutput(serviceBBucket, serviceBKey);

      List<Object> items = new ArrayList<>();
      items.add(serviceAOutput);
      items.add(serviceBOutput);

      Map<String, Object> merged = new LinkedHashMap<>();
      merged.put("requestId", requestId);
      merged.put("mergedAt", Instant.now().toString());
      merged.put("items", items);

      String mergedKey = requestId + ".json";
      s3Client.putObject(PutObjectRequest.builder()
              .bucket(orchestratedBucket)
              .key(mergedKey)
              .contentType("application/json")
              .build(),
          RequestBody.fromBytes(objectMapper.writeValueAsBytes(merged)));

      updateFinalStatus(requestId, mergedKey);

      sqsClient.deleteMessage(DeleteMessageRequest.builder()
          .queueUrl(queueUrl)
          .receiptHandle(message.receiptHandle())
          .build());

      logger.info("Merged findings stored. requestId={}, key={}", requestId, mergedKey);
    } catch (Exception ex) {
      logger.error("Failed to merge findings. messageId={}", message.messageId(), ex);
      throw new RuntimeException(ex);
    }
  }

  private Map<String, String> readOutputs(String requestId) {
    GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .consistentRead(true)
        .build());

    if (response.item() == null || response.item().isEmpty()) {
      throw new IllegalStateException("requestId not found");
    }

    AttributeValue outputsAttr = response.item().get("outputs");
    if (outputsAttr == null || outputsAttr.m() == null) {
      throw new IllegalStateException("outputs not found in DynamoDB");
    }
    Map<String, String> outputs = new LinkedHashMap<>();
    for (Map.Entry<String, AttributeValue> entry : outputsAttr.m().entrySet()) {
      outputs.put(entry.getKey(), entry.getValue().s());
    }
    return outputs;
  }

  private Map<String, Object> readOutput(String bucketName, String key) throws Exception {
    byte[] data = s3Client.getObjectAsBytes(
        software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build())
        .asByteArray();
    return objectMapper.readValue(data, new TypeReference<>() {});
  }

  private void updateFinalStatus(String requestId, String mergedKey) {
    UpdateItemRequest request = UpdateItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .updateExpression("SET #finalStatus = :done, #mergedKey = :mergedKey, #mergedAt = :mergedAt")
        .conditionExpression("#finalStatus = :merging")
        .expressionAttributeNames(Map.of(
            "#finalStatus", "finalStatus",
            "#mergedKey", "mergedKey",
            "#mergedAt", "mergedAt"))
        .expressionAttributeValues(Map.of(
            ":done", AttributeValue.builder().s("DONE").build(),
            ":merging", AttributeValue.builder().s("MERGING").build(),
            ":mergedKey", AttributeValue.builder().s(mergedKey).build(),
            ":mergedAt", AttributeValue.builder().s(Instant.now().toString()).build()))
        .build();
    try {
      dynamoDbClient.updateItem(request);
    } catch (ConditionalCheckFailedException ex) {
      logger.warn("Final status was not MERGING. requestId={}", requestId);
    }
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
