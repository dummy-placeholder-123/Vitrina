"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.VitrinaInfraStack = void 0;
const aws_cdk_lib_1 = require("aws-cdk-lib");
const lambda = __importStar(require("aws-cdk-lib/aws-lambda"));
const logs = __importStar(require("aws-cdk-lib/aws-logs"));
const s3 = __importStar(require("aws-cdk-lib/aws-s3"));
const s3deploy = __importStar(require("aws-cdk-lib/aws-s3-deployment"));
const sqs = __importStar(require("aws-cdk-lib/aws-sqs"));
const path = __importStar(require("path"));
// S3 object key used by the Lambda deployment workflow.
const ARTIFACT_KEY = 'lambda/latest.jar';
class VitrinaInfraStack extends aws_cdk_lib_1.Stack {
    constructor(scope, id, props) {
        super(scope, id, props);
        // Bucket stores the Lambda jar, retained across stack deletes.
        const artifactBucket = new s3.Bucket(this, 'LambdaArtifactBucket', {
            blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
            encryption: s3.BucketEncryption.S3_MANAGED,
            enforceSSL: true,
            versioned: true,
            removalPolicy: aws_cdk_lib_1.RemovalPolicy.RETAIN,
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
            retentionPeriod: aws_cdk_lib_1.Duration.days(14),
            encryption: sqs.QueueEncryption.KMS_MANAGED,
        });
        // Main queue that the Lambda pushes to.
        const queue = new sqs.Queue(this, 'PushQueue', {
            visibilityTimeout: aws_cdk_lib_1.Duration.seconds(30),
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
            timeout: aws_cdk_lib_1.Duration.seconds(20),
            tracing: lambda.Tracing.ACTIVE,
            logRetention: logs.RetentionDays.ONE_MONTH,
            environment: {
                SQS_QUEUE_URL: queue.queueUrl,
            },
        });
        // Allow the Lambda to send messages to the queue.
        queue.grantSendMessages(fn);
        // Outputs are used by the Lambda deployment workflow.
        new aws_cdk_lib_1.CfnOutput(this, 'ArtifactBucketName', { value: artifactBucket.bucketName });
        new aws_cdk_lib_1.CfnOutput(this, 'ArtifactKey', { value: ARTIFACT_KEY });
        new aws_cdk_lib_1.CfnOutput(this, 'LambdaFunctionName', { value: fn.functionName });
        new aws_cdk_lib_1.CfnOutput(this, 'SqsQueueUrl', { value: queue.queueUrl });
    }
}
exports.VitrinaInfraStack = VitrinaInfraStack;
