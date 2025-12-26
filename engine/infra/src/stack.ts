import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as appautoscaling from 'aws-cdk-lib/aws-applicationautoscaling';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as s3deploy from 'aws-cdk-lib/aws-s3-deployment';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as path from 'path';

// S3 object key used by the Lambda deployment workflow.
const ARTIFACT_KEY = 'lambda/latest.zip';

export class VitrinaInfraStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    // Bucket stores the Lambda deployment artifact, retained across stack deletes.
    const artifactBucket = new s3.Bucket(this, 'LambdaArtifactBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    // Seed a placeholder artifact so the stack can create the Lambda on first deploy.
    const seedDeployment = new s3deploy.BucketDeployment(this, 'LambdaArtifactSeed', {
      sources: [s3deploy.Source.asset(path.join(__dirname, '..', 'assets', 'seed'))],
      destinationBucket: artifactBucket,
      destinationKeyPrefix: 'lambda',
      prune: false,
    });

    const payloadBucket = new s3.Bucket(this, 'PayloadBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    // DLQ keeps failed messages for inspection during debugging.
    const serviceADlq = new sqs.Queue(this, 'PushDlq', {
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
    });

    // Main queue for service A.
    const serviceAQueue = new sqs.Queue(this, 'PushQueue', {
      visibilityTimeout: Duration.seconds(30),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: serviceADlq,
        maxReceiveCount: 3,
      },
    });

    const serviceBDlq = new sqs.Queue(this, 'ServiceBDlq', {
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
    });

    // Fan-out queue for service B.
    const serviceBQueue = new sqs.Queue(this, 'ServiceBQueue', {
      visibilityTimeout: Duration.seconds(30),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: serviceBDlq,
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
        MAIN_CLASS: 'com.vitrina.lambda.Application',
        SQS_QUEUE_URL_A: serviceAQueue.queueUrl,
        SQS_QUEUE_URL_B: serviceBQueue.queueUrl,
      },
    });

    // Ensure the placeholder object exists before Lambda is created.
    fn.node.addDependency(seedDeployment);

    // Allow the Lambda to send messages to both queues.
    serviceAQueue.grantSendMessages(fn);
    serviceBQueue.grantSendMessages(fn);

    const vpc = new ec2.Vpc(this, 'ServiceVpc', {
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
        },
      ],
    });

    const cluster = new ecs.Cluster(this, 'ServiceCluster', { vpc });

    const serviceARepo = new ecr.Repository(this, 'ServiceARepo');
    const serviceBRepo = new ecr.Repository(this, 'ServiceBRepo');

    const serviceATaskRole = new iam.Role(this, 'ServiceATaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    const serviceBTaskRole = new iam.Role(this, 'ServiceBTaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });

    serviceAQueue.grantConsumeMessages(serviceATaskRole);
    serviceBQueue.grantConsumeMessages(serviceBTaskRole);
    payloadBucket.grantPut(serviceATaskRole);
    payloadBucket.grantPut(serviceBTaskRole);

    const serviceALogGroup = new logs.LogGroup(this, 'ServiceALogGroup', {
      retention: logs.RetentionDays.ONE_MONTH,
    });
    const serviceBLogGroup = new logs.LogGroup(this, 'ServiceBLogGroup', {
      retention: logs.RetentionDays.ONE_MONTH,
    });

    const serviceATaskDef = new ecs.FargateTaskDefinition(this, 'ServiceATaskDef', {
      cpu: 256,
      memoryLimitMiB: 512,
      taskRole: serviceATaskRole,
    });
    const serviceBTaskDef = new ecs.FargateTaskDefinition(this, 'ServiceBTaskDef', {
      cpu: 512,
      memoryLimitMiB: 1024,
      taskRole: serviceBTaskRole,
    });

    serviceATaskDef.addContainer('ServiceAContainer', {
      image: ecs.ContainerImage.fromEcrRepository(serviceARepo, 'latest'),
      logging: ecs.LogDriver.awsLogs({
        logGroup: serviceALogGroup,
        streamPrefix: 'service-a',
      }),
      environment: {
        SQS_QUEUE_URL: serviceAQueue.queueUrl,
        PAYLOAD_BUCKET_NAME: payloadBucket.bucketName,
        SERVICE_NAME: 'service-a',
      },
    });

    serviceBTaskDef.addContainer('ServiceBContainer', {
      image: ecs.ContainerImage.fromEcrRepository(serviceBRepo, 'latest'),
      logging: ecs.LogDriver.awsLogs({
        logGroup: serviceBLogGroup,
        streamPrefix: 'service-b',
      }),
      environment: {
        SQS_QUEUE_URL: serviceBQueue.queueUrl,
        PAYLOAD_BUCKET_NAME: payloadBucket.bucketName,
        SERVICE_NAME: 'service-b',
      },
    });

    const serviceA = new ecs.FargateService(this, 'ServiceA', {
      cluster,
      taskDefinition: serviceATaskDef,
      desiredCount: 0,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    const serviceB = new ecs.FargateService(this, 'ServiceB', {
      cluster,
      taskDefinition: serviceBTaskDef,
      desiredCount: 0,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    const serviceAScaling = serviceA.autoScaleTaskCount({ minCapacity: 0, maxCapacity: 2 });
    serviceAScaling.scaleOnMetric('ServiceAQueueDepth', {
      metric: serviceAQueue.metricApproximateNumberOfMessagesVisible(),
      scalingSteps: [
        { upper: 0, change: 0 },
        { lower: 1, change: 1 },
        { lower: 25, change: 2 },
      ],
      adjustmentType: appautoscaling.AdjustmentType.CHANGE_IN_CAPACITY,
      cooldown: Duration.seconds(60),
    });

    const serviceBScaling = serviceB.autoScaleTaskCount({ minCapacity: 0, maxCapacity: 5 });
    serviceBScaling.scaleOnMetric('ServiceBQueueDepth', {
      metric: serviceBQueue.metricApproximateNumberOfMessagesVisible(),
      scalingSteps: [
        { upper: 0, change: 0 },
        { lower: 1, change: 1 },
        { lower: 50, change: 3 },
      ],
      adjustmentType: appautoscaling.AdjustmentType.CHANGE_IN_CAPACITY,
      cooldown: Duration.seconds(60),
    });

    // Outputs are used by the Lambda deployment workflow.
    new CfnOutput(this, 'ArtifactBucketName', { value: artifactBucket.bucketName });
    new CfnOutput(this, 'ArtifactKey', { value: ARTIFACT_KEY });
    new CfnOutput(this, 'LambdaFunctionName', { value: fn.functionName });
    new CfnOutput(this, 'ServiceAQueueUrl', { value: serviceAQueue.queueUrl });
    new CfnOutput(this, 'ServiceBQueueUrl', { value: serviceBQueue.queueUrl });
    new CfnOutput(this, 'PayloadBucketName', { value: payloadBucket.bucketName });
    new CfnOutput(this, 'ServiceARepoName', { value: serviceARepo.repositoryName });
    new CfnOutput(this, 'ServiceBRepoName', { value: serviceBRepo.repositoryName });
    new CfnOutput(this, 'ServiceClusterName', { value: cluster.clusterName });
    new CfnOutput(this, 'ServiceAName', { value: serviceA.serviceName });
    new CfnOutput(this, 'ServiceBName', { value: serviceB.serviceName });
  }
}
