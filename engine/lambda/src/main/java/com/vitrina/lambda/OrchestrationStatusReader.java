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
    return readRecord(requestId).getEngine();
  }

  public OrchestrationRecord readRecord(String requestId) {
    GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
        .tableName(tableName)
        .key(Map.of("requestId", AttributeValue.builder().s(requestId).build()))
        .consistentRead(true)
        .build());

    if (response.item() == null || response.item().isEmpty()) {
      throw new NotFoundException("requestId not found");
    }

    Map<String, AttributeValue> item = response.item();
    Map<String, String> engine = readStringMap(item.get("engine"));
    Map<String, String> outputs = readStringMap(item.get("outputs"));
    String finalStatus = readString(item.get("finalStatus"));
    String mergedKey = readString(item.get("mergedKey"));

    return new OrchestrationRecord(requestId, engine, outputs, finalStatus, mergedKey);
  }

  private Map<String, String> readStringMap(AttributeValue attr) {
    if (attr == null || attr.m() == null) {
      return Map.of();
    }
    Map<String, String> values = new HashMap<>();
    for (Map.Entry<String, AttributeValue> entry : attr.m().entrySet()) {
      values.put(entry.getKey(), entry.getValue().s());
    }
    return values;
  }

  private String readString(AttributeValue attr) {
    if (attr == null) {
      return null;
    }
    return attr.s();
  }
}
