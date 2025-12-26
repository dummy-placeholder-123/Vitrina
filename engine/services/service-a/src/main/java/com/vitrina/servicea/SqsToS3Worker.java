package com.vitrina.servicea;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
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
  private final String queueUrl;
  private final String bucketName;
  private final String serviceName;

  public SqsToS3Worker(SqsClient sqsClient,
      S3Client s3Client,
      @Value("${app.sqs.queue-url}") String queueUrl,
      @Value("${app.s3.bucket-name}") String bucketName,
      @Value("${app.service.name}") String serviceName) {
    this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    if (queueUrl == null || queueUrl.isBlank()) {
      throw new IllegalStateException("SQS queue URL is required");
    }
    if (bucketName == null || bucketName.isBlank()) {
      throw new IllegalStateException("S3 bucket name is required");
    }
    if (serviceName == null || serviceName.isBlank()) {
      throw new IllegalStateException("Service name is required");
    }
    this.queueUrl = queueUrl;
    this.bucketName = bucketName;
    this.serviceName = serviceName;
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
    String key = serviceName + "/" + PATH_FORMATTER.format(Instant.now())
        + "/" + message.messageId() + ".json";
    PutObjectRequest putRequest = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .contentType("application/json")
        .build();
    s3Client.putObject(putRequest, RequestBody.fromBytes(message.body().getBytes(StandardCharsets.UTF_8)));

    sqsClient.deleteMessage(DeleteMessageRequest.builder()
        .queueUrl(queueUrl)
        .receiptHandle(message.receiptHandle())
        .build());

    logger.info("Stored message in S3. key={}, messageId={}", key, message.messageId());
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
