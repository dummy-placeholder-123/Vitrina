import { App } from 'aws-cdk-lib';
import { VitrinaInfraStack } from './stack';

const app = new App();

// Use CLI-provided account/region when available; otherwise fall back to env-less deploys.
const env = process.env.CDK_DEFAULT_ACCOUNT && process.env.CDK_DEFAULT_REGION
  ? { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION }
  : undefined;

new VitrinaInfraStack(app, 'VitrinaInfraStack', { env });
