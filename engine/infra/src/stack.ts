import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as s3deploy from 'aws-cdk-lib/aws-s3-deployment';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as path from 'path';

// S3 object key used by the Lambda deployment workflow.
const ARTIFACT_KEY = 'lambda/latest.jar';

export class VitrinaInfraStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    // Bucket stores the Lambda jar, retained across stack deletes.
    const artifactBucket = new s3.Bucket(this, 'LambdaArtifactBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    // Seed a placeholder object so the stack can create the Lambda on first deploy.
    new s3deploy.BucketDeployment(this, 'LambdaArtifactSeed', {
      sources: [s3deploy.Source.asset(path.join(__dirname, '..', 'assets', 'latest.jar'))],
      destinationBucket: artifactBucket,
      destinationKeyPrefix: 'lambda',
      prune: false,
    });

    // DLQ keeps failed messages for inspection during debugging.
    const dlq = new sqs.Queue(this, 'PushDlq', {
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
    });

    // Main queue that the Lambda pushes to.
    const queue = new sqs.Queue(this, 'PushQueue', {
      visibilityTimeout: Duration.seconds(30),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: dlq,
        maxReceiveCount: 3,
      },
    });

    // Lambda reads code from the artifact bucket and writes logs + traces.
    const fn = new lambda.Function(this, 'PushToSqsFunction', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'com.vitrina.lambda.Handler',
      code: lambda.Code.fromBucket(artifactBucket, ARTIFACT_KEY),
      memorySize: 512,
      timeout: Duration.seconds(20),
      tracing: lambda.Tracing.ACTIVE,
      logRetention: logs.RetentionDays.ONE_MONTH,
      environment: {
        SQS_QUEUE_URL: queue.queueUrl,
      },
    });

    // Allow the Lambda to send messages to the queue.
    queue.grantSendMessages(fn);

    // Outputs are used by the Lambda deployment workflow.
    new CfnOutput(this, 'ArtifactBucketName', { value: artifactBucket.bucketName });
    new CfnOutput(this, 'ArtifactKey', { value: ARTIFACT_KEY });
    new CfnOutput(this, 'LambdaFunctionName', { value: fn.functionName });
    new CfnOutput(this, 'SqsQueueUrl', { value: queue.queueUrl });
  }
}
