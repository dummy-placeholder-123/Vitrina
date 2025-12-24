"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const aws_cdk_lib_1 = require("aws-cdk-lib");
const stack_1 = require("./stack");
const app = new aws_cdk_lib_1.App();
// Use CLI-provided account/region when available; otherwise fall back to env-less deploys.
const env = process.env.CDK_DEFAULT_ACCOUNT && process.env.CDK_DEFAULT_REGION
    ? { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION }
    : undefined;
new stack_1.VitrinaInfraStack(app, 'VitrinaInfraStack', { env });
