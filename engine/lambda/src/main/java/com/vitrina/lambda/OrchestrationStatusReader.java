package com.vitrina.lambda;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

public class OrchestrationStatusReader {
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public OrchestrationStatusReader(DynamoDbClient dynamoDbClient, String tableName) {
    this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient, "dynamoDbClient");
    if (tableName == null || tableName.isBlank()) {
      throw new IllegalStateException("DynamoDB table name is required");
    }
    this.tableName = tableName;
  }

  public Map<String, String> readEngineStatus(String requestId) {
    GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .consistentRead(true)
        .build());

    if (response.item() == null || response.item().isEmpty()) {
      throw new NotFoundException("requestId not found");
    }

    AttributeValue engineAttr = response.item().get("engine");
    if (engineAttr == null || engineAttr.m() == null) {
      return Map.of();
    }

    Map<String, String> statuses = new HashMap<>();
    for (Map.Entry<String, AttributeValue> entry : engineAttr.m().entrySet()) {
      statuses.put(entry.getKey(), entry.getValue().s());
    }
    return statuses;
  }
}
