package com.vitrina.lambda;

import java.util.Map;

public interface OrchestrationStore {
  void recordStart(String requestId, Map<String, String> serviceStatuses);
}
