package com.vitrina.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class FunctionConfig {
  @Bean
  public SqsClient sqsClient() {
    return SqsClient.builder().build();
  }

  @Bean
  public DynamoDbClient dynamoDbClient() {
    return DynamoDbClient.builder().build();
  }

  @Bean
  public S3Client s3Client() {
    return S3Client.builder().build();
  }

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Bean
  public OrchestrationStore orchestrationStore(DynamoDbClient dynamoDbClient,
      @Value("${app.dynamo.table-name}") String tableName) {
    return new DynamoDbOrchestrationStore(dynamoDbClient, tableName);
  }

  @Bean
  public OrchestrationStatusReader orchestrationStatusReader(DynamoDbClient dynamoDbClient,
      @Value("${app.dynamo.table-name}") String tableName) {
    return new OrchestrationStatusReader(dynamoDbClient, tableName);
  }

  @Bean
  public FindingsReader findingsReader(S3Client s3Client,
      @Value("${app.s3.orchestrated-bucket-name}") String bucketName) {
    return new FindingsReader(s3Client, bucketName);
  }

  @Bean
  public Map<String, MessagePublisher> messagePublishers(SqsClient sqsClient,
      @Value("${app.sqs.queue-url-a}") String queueUrlA,
      @Value("${app.sqs.queue-url-b}") String queueUrlB) {
    Map<String, MessagePublisher> publishers = new HashMap<>();
    publishers.put("serviceA", new SqsPublisher(sqsClient, queueUrlA));
    publishers.put("serviceB", new SqsPublisher(sqsClient, queueUrlB));
    return Map.copyOf(publishers);
  }

  @Bean
  public PushService pushService(Map<String, MessagePublisher> publishers,
      OrchestrationStore orchestrationStore,
      ObjectMapper objectMapper) {
    return new PushService(publishers, orchestrationStore, objectMapper);
  }

  @Bean
  public OrchestrationApiHandler orchestrationApiHandler(PushService pushService,
      OrchestrationStatusReader orchestrationStatusReader,
      FindingsReader findingsReader,
      ObjectMapper objectMapper) {
    return new OrchestrationApiHandler(
        pushService, orchestrationStatusReader, findingsReader, objectMapper);
  }

  @Bean
  public Function<Map<String, Object>, Map<String, Object>> pushToSqs(PushService pushService) {
    return pushService::push;
  }

  @Bean
  public Function<Map<String, Object>, Map<String, Object>> orchestrationRouter(
      OrchestrationApiHandler orchestrationApiHandler) {
    return orchestrationApiHandler::handle;
  }

}
