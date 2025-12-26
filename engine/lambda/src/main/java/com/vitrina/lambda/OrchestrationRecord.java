package com.vitrina.lambda;

import java.util.Map;

public class OrchestrationRecord {
  private final String requestId;
  private final Map<String, String> engine;
  private final Map<String, String> outputs;
  private final String finalStatus;
  private final String mergedKey;

  public OrchestrationRecord(String requestId,
      Map<String, String> engine,
      Map<String, String> outputs,
      String finalStatus,
      String mergedKey) {
    this.requestId = requestId;
    this.engine = engine;
    this.outputs = outputs;
    this.finalStatus = finalStatus;
    this.mergedKey = mergedKey;
  }

  public String getRequestId() {
    return requestId;
  }

  public Map<String, String> getEngine() {
    return engine;
  }

  public Map<String, String> getOutputs() {
    return outputs;
  }

  public String getFinalStatus() {
    return finalStatus;
  }

  public String getMergedKey() {
    return mergedKey;
  }
}
