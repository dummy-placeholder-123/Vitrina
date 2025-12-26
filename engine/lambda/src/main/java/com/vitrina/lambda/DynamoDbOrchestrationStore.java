package com.vitrina.lambda;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class DynamoDbOrchestrationStore implements OrchestrationStore {
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public DynamoDbOrchestrationStore(DynamoDbClient dynamoDbClient, String tableName) {
    this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient, "dynamoDbClient");
    if (tableName == null || tableName.isBlank()) {
      throw new IllegalStateException("DynamoDB table name is required");
    }
    this.tableName = tableName;
  }

  @Override
  public void recordStart(String requestId, Map<String, String> serviceStatuses) {
    Map<String, AttributeValue> engineMap = new HashMap<>();
    for (Map.Entry<String, String> entry : serviceStatuses.entrySet()) {
      engineMap.put(entry.getKey(), AttributeValue.builder().s(entry.getValue()).build());
    }

    Map<String, AttributeValue> item = new HashMap<>();
    item.put("requestId", AttributeValue.builder().s(requestId).build());
    item.put("engine", AttributeValue.builder().m(engineMap).build());

    dynamoDbClient.putItem(PutItemRequest.builder()
        .tableName(tableName)
        .item(item)
        .build());
  }
}
