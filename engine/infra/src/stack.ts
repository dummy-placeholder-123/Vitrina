import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as s3deploy from 'aws-cdk-lib/aws-s3-deployment';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
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

    const serviceAPayloadBucket = new s3.Bucket(this, 'ServiceAPayloadBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const serviceBPayloadBucket = new s3.Bucket(this, 'ServiceBPayloadBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const orchestratedDetectionBucket = new s3.Bucket(this, 'OrchestratedDetectionBucket', {
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
      visibilityTimeout: Duration.minutes(7),
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
      visibilityTimeout: Duration.minutes(7),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: serviceBDlq,
        maxReceiveCount: 3,
      },
    });

    const mergeDlq = new sqs.Queue(this, 'MergeDlq', {
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
    });

    const mergeQueue = new sqs.Queue(this, 'MergeQueue', {
      visibilityTimeout: Duration.minutes(2),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: mergeDlq,
        maxReceiveCount: 3,
      },
    });

    const statusTable = new dynamodb.Table(this, 'OrchestrationStatusTable', {
      partitionKey: { name: 'requestId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.RETAIN,
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
        STATUS_TABLE_NAME: statusTable.tableName,
        ORCHESTRATED_BUCKET_NAME: orchestratedDetectionBucket.bucketName,
      },
    });

    // Ensure the placeholder object exists before Lambda is created.
    fn.node.addDependency(seedDeployment);

    // Allow the Lambda to send messages to both queues.
    serviceAQueue.grantSendMessages(fn);
    serviceBQueue.grantSendMessages(fn);
    statusTable.grantReadWriteData(fn);
    orchestratedDetectionBucket.grantRead(fn);

    const api = new apigateway.RestApi(this, 'OrchestrationApi', {
      deployOptions: {
        stageName: 'prod',
      },
    });
    const lambdaIntegration = new apigateway.LambdaIntegration(fn);

    const scanResource = api.root.addResource('scan');
    scanResource.addMethod('POST', lambdaIntegration);

    const statusResource = api.root.addResource('status');
    statusResource.addMethod('GET', lambdaIntegration);
    statusResource.addResource('{requestId}').addMethod('GET', lambdaIntegration);

    const findingsResource = api.root.addResource('findings');
    findingsResource.addMethod('GET', lambdaIntegration);
    findingsResource.addResource('{requestId}').addMethod('GET', lambdaIntegration);

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
    const mergeServiceRepo = new ecr.Repository(this, 'MergeServiceRepo');

    const serviceATaskRole = new iam.Role(this, 'ServiceATaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    const serviceBTaskRole = new iam.Role(this, 'ServiceBTaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    const mergeTaskRole = new iam.Role(this, 'MergeTaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });

    serviceAQueue.grantConsumeMessages(serviceATaskRole);
    serviceBQueue.grantConsumeMessages(serviceBTaskRole);
    mergeQueue.grantSendMessages(serviceATaskRole);
    mergeQueue.grantSendMessages(serviceBTaskRole);
    mergeQueue.grantConsumeMessages(mergeTaskRole);
    serviceAPayloadBucket.grantPut(serviceATaskRole);
    serviceBPayloadBucket.grantPut(serviceBTaskRole);
    serviceAPayloadBucket.grantRead(mergeTaskRole);
    serviceBPayloadBucket.grantRead(mergeTaskRole);
    orchestratedDetectionBucket.grantPut(mergeTaskRole);
    statusTable.grantReadWriteData(serviceATaskRole);
    statusTable.grantReadWriteData(serviceBTaskRole);
    statusTable.grantReadWriteData(mergeTaskRole);

    const serviceALogGroup = new logs.LogGroup(this, 'ServiceALogGroup', {
      retention: logs.RetentionDays.ONE_MONTH,
    });
    const serviceBLogGroup = new logs.LogGroup(this, 'ServiceBLogGroup', {
      retention: logs.RetentionDays.ONE_MONTH,
    });
    const mergeLogGroup = new logs.LogGroup(this, 'MergeLogGroup', {
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
    const mergeTaskDef = new ecs.FargateTaskDefinition(this, 'MergeTaskDef', {
      cpu: 256,
      memoryLimitMiB: 512,
      taskRole: mergeTaskRole,
    });

    serviceATaskDef.addContainer('ServiceAContainer', {
      image: ecs.ContainerImage.fromEcrRepository(serviceARepo, 'latest'),
      logging: ecs.LogDriver.awsLogs({
        logGroup: serviceALogGroup,
        streamPrefix: 'service-a',
      }),
      environment: {
        SQS_QUEUE_URL: serviceAQueue.queueUrl,
        PAYLOAD_BUCKET_NAME: serviceAPayloadBucket.bucketName,
        SERVICE_NAME: 'serviceA',
        STATUS_TABLE_NAME: statusTable.tableName,
        MERGE_QUEUE_URL: mergeQueue.queueUrl,
        EXPECTED_SERVICES: 'serviceA,serviceB',
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
        PAYLOAD_BUCKET_NAME: serviceBPayloadBucket.bucketName,
        SERVICE_NAME: 'serviceB',
        STATUS_TABLE_NAME: statusTable.tableName,
        MERGE_QUEUE_URL: mergeQueue.queueUrl,
        EXPECTED_SERVICES: 'serviceA,serviceB',
      },
    });

    mergeTaskDef.addContainer('MergeContainer', {
      image: ecs.ContainerImage.fromEcrRepository(mergeServiceRepo, 'latest'),
      logging: ecs.LogDriver.awsLogs({
        logGroup: mergeLogGroup,
        streamPrefix: 'merge',
      }),
      environment: {
        SQS_QUEUE_URL: mergeQueue.queueUrl,
        SERVICE_A_BUCKET_NAME: serviceAPayloadBucket.bucketName,
        SERVICE_B_BUCKET_NAME: serviceBPayloadBucket.bucketName,
        ORCHESTRATED_BUCKET_NAME: orchestratedDetectionBucket.bucketName,
        STATUS_TABLE_NAME: statusTable.tableName,
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

    const mergeService = new ecs.FargateService(this, 'MergeService', {
      cluster,
      taskDefinition: mergeTaskDef,
      desiredCount: 0,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    // Outputs are used by the Lambda deployment workflow.
    new CfnOutput(this, 'ArtifactBucketName', { value: artifactBucket.bucketName });
    new CfnOutput(this, 'ArtifactKey', { value: ARTIFACT_KEY });
    new CfnOutput(this, 'LambdaFunctionName', { value: fn.functionName });
    new CfnOutput(this, 'ServiceAQueueUrl', { value: serviceAQueue.queueUrl });
    new CfnOutput(this, 'ServiceBQueueUrl', { value: serviceBQueue.queueUrl });
    new CfnOutput(this, 'ServiceAPayloadBucketName', { value: serviceAPayloadBucket.bucketName });
    new CfnOutput(this, 'ServiceBPayloadBucketName', { value: serviceBPayloadBucket.bucketName });
    new CfnOutput(this, 'OrchestratedDetectionBucketName', {
      value: orchestratedDetectionBucket.bucketName,
    });
    new CfnOutput(this, 'ServiceARepoName', { value: serviceARepo.repositoryName });
    new CfnOutput(this, 'ServiceBRepoName', { value: serviceBRepo.repositoryName });
    new CfnOutput(this, 'MergeServiceRepoName', { value: mergeServiceRepo.repositoryName });
    new CfnOutput(this, 'ServiceClusterName', { value: cluster.clusterName });
    new CfnOutput(this, 'ServiceAName', { value: serviceA.serviceName });
    new CfnOutput(this, 'ServiceBName', { value: serviceB.serviceName });
    new CfnOutput(this, 'MergeServiceName', { value: mergeService.serviceName });
    new CfnOutput(this, 'MergeQueueUrl', { value: mergeQueue.queueUrl });
    new CfnOutput(this, 'StatusTableName', { value: statusTable.tableName });
    new CfnOutput(this, 'OrchestrationApiUrl', { value: api.url });
  }
}
