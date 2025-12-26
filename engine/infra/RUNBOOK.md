# Vitrina Engine Runbook


## AWS
aws configure sso --profile AdministratorAccess-289956717103
aws sso login --profile AdministratorAccess-289956717103
export AWS_PROFILE=AdministratorAccess-289956717103
npx cdk bootstrap aws://289956717103/us-east-1


## Scope
This runbook covers infra (CDK) and Lambda deployments for the engine package.

## One-time setup
1. Create an AWS OIDC role for GitHub Actions.
   - Trust: GitHub OIDC provider for this repo.
   - Permissions: CloudFormation, S3 (artifact bucket), Lambda, SQS, CloudWatch Logs, X-Ray, IAM PassRole for Lambda.
2. Set repo secrets/vars (repo settings -> Actions):
   - Secret: `AWS_ROLE_ARN`
   - Variable: `AWS_REGION` (example: `us-east-1`)
   - Optional Variable: `CDK_STACK_NAME` (default `VitrinaInfraStack`)
3. Bootstrap CDK once per account/region (note the qualifier used by this repo):
   - `cd engine/infra`
   - `npm ci`
   - `npx cdk bootstrap --qualifier engine --profile dev-profile --region us-east-1`

## Deploy infra (CDK)
1. Make changes in `engine/infra`.
2. Push to the default branch.
3. The workflow `engine-infra-deploy` runs automatically and deploys the stack.
4. Confirm outputs in CloudFormation:
   - `ArtifactBucketName`
   - `ArtifactKey`
   - `LambdaFunctionName`
   - `ServiceAQueueUrl`
   - `ServiceBQueueUrl`
   - `PayloadBucketName`
   - `ServiceARepoName`
   - `ServiceBRepoName`
   - `ServiceClusterName`
   - `ServiceAName`
   - `ServiceBName`

## Deploy Lambda code
1. Make changes in `engine/lambda`.
2. Push to the default branch.
3. The workflow `engine-lambda-deploy` builds the jar, uploads to S3, and updates Lambda.
4. Verify the Lambda update completes (the workflow waits for function update).

## Manual deploy (break glass)
Infra:
1. `cd engine/infra`
2. `npm ci`
3. `npm run build`
4. `npx cdk deploy --require-approval never --profile dev-profile --region us-east-1`

Lambda:
1. `cd engine/lambda`
2. `mvn -B package`
3. Resolve stack outputs:
   - `aws cloudformation describe-stacks --stack-name VitrinaInfraStack --query "Stacks[0].Outputs" --output table --profile dev-profile --region us-east-1`
4. Upload and update:
   - `aws s3 cp target/lambda.zip s3://<ArtifactBucketName>/<ArtifactKey> --profile dev-profile --region us-east-1`
   - `aws lambda update-function-code --function-name <LambdaFunctionName> --s3-bucket <ArtifactBucketName> --s3-key <ArtifactKey> --profile dev-profile --region us-east-1`
   - `aws lambda wait function-updated --function-name <LambdaFunctionName> --profile dev-profile --region us-east-1`

## Deploy services (manual)
Service A:
1. `cd engine/services/service-a`
2. `mvn -B package`
3. `aws ecr get-login-password --region us-east-1 --profile dev-profile | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com`
4. `docker build -t service-a .`
5. `docker tag service-a:latest <account-id>.dkr.ecr.us-east-1.amazonaws.com/<ServiceARepoName>:latest`
6. `docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/<ServiceARepoName>:latest`
7. `aws ecs update-service --cluster <ServiceClusterName> --service <ServiceAName> --force-new-deployment --profile dev-profile --region us-east-1`

Service B:
1. `cd engine/services/service-b`
2. `mvn -B package`
3. `aws ecr get-login-password --region us-east-1 --profile dev-profile | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com`
4. `docker build -t service-b .`
5. `docker tag service-b:latest <account-id>.dkr.ecr.us-east-1.amazonaws.com/<ServiceBRepoName>:latest`
6. `docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/<ServiceBRepoName>:latest`
7. `aws ecs update-service --cluster <ServiceClusterName> --service <ServiceBName> --force-new-deployment --profile dev-profile --region us-east-1`

## Debugging Lambda issues
1. CloudWatch logs: check `/aws/lambda/<LambdaFunctionName>` for errors and correlation IDs.
2. X-Ray traces: enabled in the stack to pinpoint slow or failing segments.
3. DLQ: inspect `PushDlq` for failed messages.
4. Reproduce with a minimal event:
   - `{"message":"hello","correlationId":"test-123"}`

## Common failure causes
- Missing `SQS_QUEUE_URL_A` or `SQS_QUEUE_URL_B` environment variables (ensure infra is deployed).
- IAM permissions on the Lambda role (SQS send permissions required).
- Large payloads (SQS message max 256 KB).
