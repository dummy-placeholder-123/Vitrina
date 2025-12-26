package com.vitrina.servicea;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class AwsClientConfig {
  @Bean
  public SqsClient sqsClient() {
    return SqsClient.builder().build();
  }

  @Bean
  public S3Client s3Client() {
    return S3Client.builder().build();
  }
}
