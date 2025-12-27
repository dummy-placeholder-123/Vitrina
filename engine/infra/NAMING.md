# CDK Resource Naming

This stack uses deterministic, descriptive resource names. The single source of
truth is `engine/infra/src/stack.ts` via the `resourceNames(...)` helper.

## Prefix rules
- Use `CDK_MADE-` for resources that allow uppercase/underscore.
- Use `cdk-made-` for S3 buckets and ECR repos (AWS requires lowercase).

## Dynamic parts
- S3 bucket names include `<account>` and `<region>` for global uniqueness.

## Resource map
- Lambda artifact bucket: `cdk-made-vitrina-<account>-<region>-lambda-artifacts`
- Service A payload bucket: `cdk-made-vitrina-<account>-<region>-service-a-payload`
- Service B payload bucket: `cdk-made-vitrina-<account>-<region>-service-b-payload`
- Orchestrated bucket: `cdk-made-vitrina-<account>-<region>-orchestrated`
- Service A ECR repo: `cdk-made-vitrina-service-a`
- Service B ECR repo: `cdk-made-vitrina-service-b`
- Merge service ECR repo: `cdk-made-vitrina-merge`
- Service A queue: `CDK_MADE-Vitrina-ServiceA-Queue`
- Service A DLQ: `CDK_MADE-Vitrina-ServiceA-DLQ`
- Service B queue: `CDK_MADE-Vitrina-ServiceB-Queue`
- Service B DLQ: `CDK_MADE-Vitrina-ServiceB-DLQ`
- Merge queue: `CDK_MADE-Vitrina-Merge-Queue`
- Merge DLQ: `CDK_MADE-Vitrina-Merge-DLQ`
- Status table: `CDK_MADE-Vitrina-OrchestrationStatus`
- Lambda role: `CDK_MADE-Vitrina-Lambda-Role`
- Lambda function: `CDK_MADE-Vitrina-PushToSqs`
- Orchestration API: `CDK_MADE-Vitrina-OrchestrationApi`
- VPC: `CDK_MADE-Vitrina-ServiceVpc`
- ECS cluster: `CDK_MADE-Vitrina-ServiceCluster`
- Service A task role: `CDK_MADE-Vitrina-ServiceA-TaskRole`
- Service B task role: `CDK_MADE-Vitrina-ServiceB-TaskRole`
- Merge task role: `CDK_MADE-Vitrina-Merge-TaskRole`
- Service A execution role: `CDK_MADE-Vitrina-ServiceA-ExecutionRole`
- Service B execution role: `CDK_MADE-Vitrina-ServiceB-ExecutionRole`
- Merge execution role: `CDK_MADE-Vitrina-Merge-ExecutionRole`
- Service A log group: `CDK_MADE-Vitrina-ServiceA-Logs`
- Service B log group: `CDK_MADE-Vitrina-ServiceB-Logs`
- Merge log group: `CDK_MADE-Vitrina-Merge-Logs`
- Service A task definition family: `CDK_MADE-Vitrina-ServiceA-TaskDef`
- Service B task definition family: `CDK_MADE-Vitrina-ServiceB-TaskDef`
- Merge task definition family: `CDK_MADE-Vitrina-Merge-TaskDef`
- Service A ECS service: `CDK_MADE-Vitrina-ServiceA`
- Service B ECS service: `CDK_MADE-Vitrina-ServiceB`
- Merge ECS service: `CDK_MADE-Vitrina-MergeService`
