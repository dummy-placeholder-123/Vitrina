package com.vitrina.lambda;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class FindingsReader {
  private final S3Client s3Client;
  private final String bucketName;

  public FindingsReader(S3Client s3Client, String bucketName) {
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    if (bucketName == null || bucketName.isBlank()) {
      throw new IllegalStateException("Orchestrated bucket name is required");
    }
    this.bucketName = bucketName;
  }

  public String readFindings(String objectKey) {
    try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
        .bucket(bucketName)
        .key(objectKey)
        .build())) {
      return new String(response.readAllBytes(), StandardCharsets.UTF_8);
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) {
        throw new NotFoundException("findings not found");
      }
      throw ex;
    } catch (IOException ex) {
      throw new RuntimeException("Failed to read findings", ex);
    }
  }
}
