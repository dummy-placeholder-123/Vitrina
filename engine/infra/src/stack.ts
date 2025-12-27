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
const NAME_PREFIX = 'CDK_MADE-Vitrina';
const LOWER_NAME_PREFIX = 'cdk-made-vitrina';

const resourceNames = (stack: Stack) => {
  const account = stack.account;
  const region = stack.region;

  return {
    lambdaArtifactBucket: `${LOWER_NAME_PREFIX}-${account}-${region}-lambda-artifacts`,
    serviceAPayloadBucket: `${LOWER_NAME_PREFIX}-${account}-${region}-service-a-payload`,
    serviceBPayloadBucket: `${LOWER_NAME_PREFIX}-${account}-${region}-service-b-payload`,
    orchestratedDetectionBucket: `${LOWER_NAME_PREFIX}-${account}-${region}-orchestrated`,
    serviceARepo: `${LOWER_NAME_PREFIX}-service-a`,
    serviceBRepo: `${LOWER_NAME_PREFIX}-service-b`,
    mergeRepo: `${LOWER_NAME_PREFIX}-merge`,
    pushDlq: `${NAME_PREFIX}-ServiceA-DLQ`,
    pushQueue: `${NAME_PREFIX}-ServiceA-Queue`,
    serviceBDlq: `${NAME_PREFIX}-ServiceB-DLQ`,
    serviceBQueue: `${NAME_PREFIX}-ServiceB-Queue`,
    mergeDlq: `${NAME_PREFIX}-Merge-DLQ`,
    mergeQueue: `${NAME_PREFIX}-Merge-Queue`,
    statusTable: `${NAME_PREFIX}-OrchestrationStatus`,
    lambdaRole: `${NAME_PREFIX}-Lambda-Role`,
    lambdaFunction: `${NAME_PREFIX}-PushToSqs`,
    orchestrationApi: `${NAME_PREFIX}-OrchestrationApi`,
    vpc: `${NAME_PREFIX}-ServiceVpc`,
    cluster: `${NAME_PREFIX}-ServiceCluster`,
    serviceATaskRole: `${NAME_PREFIX}-ServiceA-TaskRole`,
    serviceBTaskRole: `${NAME_PREFIX}-ServiceB-TaskRole`,
    mergeTaskRole: `${NAME_PREFIX}-Merge-TaskRole`,
    serviceAExecutionRole: `${NAME_PREFIX}-ServiceA-ExecutionRole`,
    serviceBExecutionRole: `${NAME_PREFIX}-ServiceB-ExecutionRole`,
    mergeExecutionRole: `${NAME_PREFIX}-Merge-ExecutionRole`,
    serviceALogGroup: `${NAME_PREFIX}-ServiceA-Logs`,
    serviceBLogGroup: `${NAME_PREFIX}-ServiceB-Logs`,
    mergeLogGroup: `${NAME_PREFIX}-Merge-Logs`,
    serviceATaskFamily: `${NAME_PREFIX}-ServiceA-TaskDef`,
    serviceBTaskFamily: `${NAME_PREFIX}-ServiceB-TaskDef`,
    mergeTaskFamily: `${NAME_PREFIX}-Merge-TaskDef`,
    serviceAService: `${NAME_PREFIX}-ServiceA`,
    serviceBService: `${NAME_PREFIX}-ServiceB`,
    mergeService: `${NAME_PREFIX}-MergeService`,
  };
};

export class VitrinaInfraStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    const names = resourceNames(this);

    // Bucket stores the Lambda deployment artifact, retained across stack deletes.
    const artifactBucket = new s3.Bucket(this, 'LambdaArtifactBucket', {
      bucketName: names.lambdaArtifactBucket,
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
      bucketName: names.serviceAPayloadBucket,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const serviceBPayloadBucket = new s3.Bucket(this, 'ServiceBPayloadBucket', {
      bucketName: names.serviceBPayloadBucket,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const orchestratedDetectionBucket = new s3.Bucket(this, 'OrchestratedDetectionBucket', {
      bucketName: names.orchestratedDetectionBucket,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    // DLQ keeps failed messages for inspection during debugging.
    const serviceADlq = new sqs.Queue(this, 'PushDlq', {
      queueName: names.pushDlq,
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
    });

    // Main queue for service A.
    const serviceAQueue = new sqs.Queue(this, 'PushQueue', {
      queueName: names.pushQueue,
      visibilityTimeout: Duration.minutes(7),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: serviceADlq,
        maxReceiveCount: 3,
      },
    });

    const serviceBDlq = new sqs.Queue(this, 'ServiceBDlq', {
      queueName: names.serviceBDlq,
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
    });

    // Fan-out queue for service B.
    const serviceBQueue = new sqs.Queue(this, 'ServiceBQueue', {
      queueName: names.serviceBQueue,
      visibilityTimeout: Duration.minutes(7),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: serviceBDlq,
        maxReceiveCount: 3,
      },
    });

    const mergeDlq = new sqs.Queue(this, 'MergeDlq', {
      queueName: names.mergeDlq,
      retentionPeriod: Duration.days(14),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
    });

    const mergeQueue = new sqs.Queue(this, 'MergeQueue', {
      queueName: names.mergeQueue,
      visibilityTimeout: Duration.minutes(2),
      encryption: sqs.QueueEncryption.KMS_MANAGED,
      deadLetterQueue: {
        queue: mergeDlq,
        maxReceiveCount: 3,
      },
    });

    const statusTable = new dynamodb.Table(this, 'OrchestrationStatusTable', {
      tableName: names.statusTable,
      partitionKey: { name: 'requestId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const lambdaRole = new iam.Role(this, 'LambdaExecutionRole', {
      roleName: names.lambdaRole,
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('AWSXRayDaemonWriteAccess'),
      ],
    });

    // Lambda reads code from the artifact bucket and writes logs + traces.
    const fn = new lambda.Function(this, 'PushToSqsFunction', {
      functionName: names.lambdaFunction,
      runtime: lambda.Runtime.JAVA_17,
      handler: 'com.vitrina.lambda.Handler',
      code: lambda.Code.fromBucket(artifactBucket, ARTIFACT_KEY),
      memorySize: 512,
      timeout: Duration.seconds(20),
      tracing: lambda.Tracing.ACTIVE,
      logRetention: logs.RetentionDays.ONE_MONTH,
      role: lambdaRole,
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
      restApiName: names.orchestrationApi,
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
      vpcName: names.vpc,
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
        },
      ],
    });

    const cluster = new ecs.Cluster(this, 'ServiceCluster', {
      vpc,
      clusterName: names.cluster,
    });

    const serviceARepo = new ecr.Repository(this, 'ServiceARepo', {
      repositoryName: names.serviceARepo,
    });
    const serviceBRepo = new ecr.Repository(this, 'ServiceBRepo', {
      repositoryName: names.serviceBRepo,
    });
    const mergeServiceRepo = new ecr.Repository(this, 'MergeServiceRepo', {
      repositoryName: names.mergeRepo,
    });

    const serviceATaskRole = new iam.Role(this, 'ServiceATaskRole', {
      roleName: names.serviceATaskRole,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    const serviceBTaskRole = new iam.Role(this, 'ServiceBTaskRole', {
      roleName: names.serviceBTaskRole,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    const mergeTaskRole = new iam.Role(this, 'MergeTaskRole', {
      roleName: names.mergeTaskRole,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });

    const serviceAExecutionRole = new iam.Role(this, 'ServiceAExecutionRole', {
      roleName: names.serviceAExecutionRole,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });
    const serviceBExecutionRole = new iam.Role(this, 'ServiceBExecutionRole', {
      roleName: names.serviceBExecutionRole,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });
    const mergeExecutionRole = new iam.Role(this, 'MergeExecutionRole', {
      roleName: names.mergeExecutionRole,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
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
      logGroupName: names.serviceALogGroup,
      retention: logs.RetentionDays.ONE_MONTH,
    });
    const serviceBLogGroup = new logs.LogGroup(this, 'ServiceBLogGroup', {
      logGroupName: names.serviceBLogGroup,
      retention: logs.RetentionDays.ONE_MONTH,
    });
    const mergeLogGroup = new logs.LogGroup(this, 'MergeLogGroup', {
      logGroupName: names.mergeLogGroup,
      retention: logs.RetentionDays.ONE_MONTH,
    });

    const serviceATaskDef = new ecs.FargateTaskDefinition(this, 'ServiceATaskDef', {
      family: names.serviceATaskFamily,
      cpu: 256,
      memoryLimitMiB: 512,
      taskRole: serviceATaskRole,
      executionRole: serviceAExecutionRole,
    });
    const serviceBTaskDef = new ecs.FargateTaskDefinition(this, 'ServiceBTaskDef', {
      family: names.serviceBTaskFamily,
      cpu: 512,
      memoryLimitMiB: 1024,
      taskRole: serviceBTaskRole,
      executionRole: serviceBExecutionRole,
    });
    const mergeTaskDef = new ecs.FargateTaskDefinition(this, 'MergeTaskDef', {
      family: names.mergeTaskFamily,
      cpu: 256,
      memoryLimitMiB: 512,
      taskRole: mergeTaskRole,
      executionRole: mergeExecutionRole,
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
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      serviceName: names.serviceAService,
    });

    const serviceB = new ecs.FargateService(this, 'ServiceB', {
      cluster,
      taskDefinition: serviceBTaskDef,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      serviceName: names.serviceBService,
    });

    const mergeService = new ecs.FargateService(this, 'MergeService', {
      cluster,
      taskDefinition: mergeTaskDef,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      serviceName: names.mergeService,
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
