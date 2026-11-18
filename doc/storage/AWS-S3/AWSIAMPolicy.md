# Connection via IAM Policy (IRSA)

This guide documents how to configure IAM policies for Camunda 8 backups on AWS using IRSA (IAM Roles for Service Accounts) with an OpenSearch domain in a VPC.

---

## Prerequisites

- EKS cluster with IRSA enabled
- OpenSearch domain deployed in a VPC
- S3 bucket for backups
- Camunda 8.8 deployed via Helm
- `kubectl` and `helm` CLI tools installed
- AWS CLI installed and configured

---

## Overview

Because the OpenSearch domain is deployed in a VPC, it is not reachable from outside the cluster. All API calls must be made from inside the cluster using SigV4-signed requests. Plain `curl` will not work — you must use a pod with IRSA credentials.

The following IAM changes are required:

1. Add an **S3 backup policy** to the OpenSearch IAM role
2. Add a **PassRole policy** to the OpenSearch IAM role
3. Update the **Trust policy** to allow `es.amazonaws.com` to assume the role
4. Update the **OpenSearch domain access policy**
5. Set the **IAM role as master user** in OpenSearch fine-grained access control

---

## Step 1 — Add S3 Backup Policy to the IAM Role

This policy allows the OpenSearch role to read and write backup data to your S3 bucket.

1. Go to **AWS Console → IAM → Roles**
2. Search for and click your OpenSearch role (e.g. `OpenSearchRole-<cluster-name>`)
3. Click **Add permissions → Create inline policy**
4. Click the **JSON** tab and paste the following:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::<your-backup-bucket>",
        "arn:aws:s3:::<your-backup-bucket>/*"
      ]
    }
  ]
}
```

5. Replace `<your-backup-bucket>` with your actual S3 bucket name
6. Click **Next**, enter the policy name: `OpenSearchS3BackupPolicy`
7. Click **Create policy**

---

## Step 2 — Add PassRole Policy to the IAM Role

This policy allows the role to pass itself to OpenSearch when registering the snapshot repository.

1. Stay on the same IAM role page
2. Click **Add permissions → Create inline policy**
3. Click the **JSON** tab and paste the following:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::<account-id>:role/<your-opensearch-role>"
    }
  ]
}
```

4. Replace `<account-id>` and `<your-opensearch-role>` with your actual values
5. Click **Next**, enter the policy name: `OpenSearchPassRolePolicy`
6. Click **Create policy**

After both policies are added, you should see 3 policies attached to the role:
- `OpenSearchS3BackupPolicy` (Customer inline)
- `OpenSearchPassRolePolicy` (Customer inline)
- `<your-existing-access-policy>` (Customer managed)

---

## Step 3 — Update the Trust Policy

The role needs to trust both the EKS OIDC provider (for IRSA) and the OpenSearch service (`es.amazonaws.com`).

1. On the same IAM role page, click the **Trust relationships** tab
2. Click **Edit trust policy**
3. You will see an existing statement for IRSA — keep it and add the `es.amazonaws.com` statement. Replace the entire policy with:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<account-id>:oidc-provider/oidc.eks.<region>.amazonaws.com/id/<oidc-id>"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "oidc.eks.<region>.amazonaws.com/id/<oidc-id>:sub": [
            "system:serviceaccount:<namespace>:zeebe-sa",
            "system:serviceaccount:<namespace>:optimize-sa"
          ]
        }
      }
    },
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "es.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

4. Replace the placeholder values with your actual OIDC provider ID, region, account ID, and namespace
5. Click **Update policy**

> **Note:** The first statement is your existing IRSA trust — do not remove it. Only add the second `es.amazonaws.com` statement.

---

## Step 4 — Update the OpenSearch Domain Access Policy

This resource-based policy allows the IAM role to access the OpenSearch domain.

1. Go to **AWS Console → Amazon OpenSearch Service → Domains**
2. Click your domain (e.g. `ariv-irsa-test-os`)
3. Click **Actions → Edit security configuration**
4. Scroll down to **Access policy**
5. Select **Custom access policy** and paste the following:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::<account-id>:role/<your-opensearch-role>"
      },
      "Action": "es:*",
      "Resource": "arn:aws:es:<region>:<account-id>:domain/<domain-name>/*"
    }
  ]
}
```

6. Replace the placeholder values with your actual account ID, role name, region, and domain name
7. Click **Save changes**
8. Wait for the domain to finish updating — this can take a few minutes

To verify the access policy was applied and the domain is done updating:

```bash
# Check access policy
aws opensearch describe-domain --domain-name <domain-name> --region <region> \
  --query 'DomainStatus.AccessPolicies'

# Check if domain is still updating (wait until false)
aws opensearch describe-domain --domain-name <domain-name> --region <region> \
  --query 'DomainStatus.Processing'
```

---

## Step 5 — Set IAM Role as Master User in OpenSearch

This step maps the IAM role as the master user in OpenSearch's fine-grained access control so it has full permissions.

1. Go to **AWS Console → Amazon OpenSearch Service → Domains**
2. Click your domain
3. Click **Actions → Edit security configuration**
4. Under **Fine-grained access control → Master user**, select **Set IAM ARN as master user**
5. Enter your role ARN: `arn:aws:iam::<account-id>:role/<your-opensearch-role>`
6. Click **Save changes**
7. Wait for the domain to finish updating

---

## Step 6 — Register the OpenSearch Snapshot Repository

Since the OpenSearch domain is in a VPC, you must register the snapshot repository from inside the cluster using a SigV4-signed request.

### 6.1 — Spin up a debug pod with IRSA credentials

```bash
kubectl -n <namespace> run curl-test --rm -it \
  --image=amazonlinux:2 \
  --restart=Never \
  --overrides='{"spec":{"serviceAccountName":"zeebe-sa"}}' \
  -- /bin/bash
```

### 6.2 — Install dependencies inside the pod

```bash
yum install -y awscli curl python3-pip
pip3 install boto3 botocore requests
```

### 6.3 — Verify IRSA credentials are working

```bash
aws sts get-caller-identity
```

The output should show your OpenSearch role ARN in the `Arn` field, e.g.:
```json
{
  "Account": "655394773887",
  "UserId": "AROAZRGESTN7XPBEHPSWD:botocore-session-xxx",
  "Arn": "arn:aws:sts::655394773887:assumed-role/OpenSearchRole-<cluster-name>/botocore-session-xxx"
}
```

### 6.4 — Register the snapshot repository

Run the following Python script inside the pod:

```bash
python3 << 'EOF'
import boto3
import os
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials
import urllib.request
import json

role_arn = os.environ['AWS_ROLE_ARN']
token_file = os.environ['AWS_WEB_IDENTITY_TOKEN_FILE']

with open(token_file) as f:
    token = f.read()

sts = boto3.client('sts', region_name='us-east-1')
resp = sts.assume_role_with_web_identity(
    RoleArn=role_arn,
    RoleSessionName='opensearch-session',
    WebIdentityToken=token
)
creds = resp['Credentials']

credentials = Credentials(
    access_key=creds['AccessKeyId'],
    secret_key=creds['SecretAccessKey'],
    token=creds['SessionToken']
)

region = '<region>'
url = 'https://<opensearch-endpoint>/_snapshot/<repository-name>'
body = json.dumps({
    "type": "s3",
    "settings": {
        "bucket": "<your-backup-bucket>",
        "base_path": "opensearch",
        "region": "<region>",
        "role_arn": "arn:aws:iam::<account-id>:role/<your-opensearch-role>"
    }
})

request = AWSRequest(method='PUT', url=url, data=body, headers={'Content-Type': 'application/json'})
SigV4Auth(credentials, 'es', region).add_auth(request)

req = urllib.request.Request(url, data=body.encode(), headers=dict(request.headers), method='PUT')
try:
    response = urllib.request.urlopen(req)
    print(response.read().decode())
except urllib.error.HTTPError as e:
    print("Status:", e.code)
    print("Body:", e.read().decode())
EOF
```

A successful response looks like:
```json
{"acknowledged": true}
```

### 6.5 — Verify the repository was registered

```bash
python3 << 'EOF'
import boto3
import os
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials
import urllib.request
import json

role_arn = os.environ['AWS_ROLE_ARN']
token_file = os.environ['AWS_WEB_IDENTITY_TOKEN_FILE']

with open(token_file) as f:
    token = f.read()

sts = boto3.client('sts', region_name='us-east-1')
resp = sts.assume_role_with_web_identity(
    RoleArn=role_arn,
    RoleSessionName='opensearch-session',
    WebIdentityToken=token
)
creds = resp['Credentials']

credentials = Credentials(
    access_key=creds['AccessKeyId'],
    secret_key=creds['SecretAccessKey'],
    token=creds['SessionToken']
)

url = 'https://<opensearch-endpoint>/_snapshot/<repository-name>'
request = AWSRequest(method='GET', url=url, headers={'Content-Type': 'application/json'})
SigV4Auth(credentials, 'es', '<region>').add_auth(request)

req = urllib.request.Request(url, headers=dict(request.headers), method='GET')
response = urllib.request.urlopen(req)
print(json.dumps(json.loads(response.read().decode()), indent=2))
EOF
```

---

## Step 7 — Configure Zeebe to Use IRSA for S3 (No Static Keys)

In your Helm values file, remove the static `ACCESSKEY` and `SECRETKEY` environment variables and let Zeebe use IRSA automatically:

```yaml
orchestration:
  serviceAccount:
    name: zeebe-sa
    annotations:
      eks.amazonaws.com/role-arn: arn:aws:iam::<account-id>:role/<your-opensearch-role>

  env:
    - name: ZEEBE_BROKER_DATA_BACKUP_STORE
      value: "S3"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_BUCKETNAME
      value: "<your-backup-bucket>"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_REGION
      value: "<region>"
    - name: ZEEBE_BROKER_DATA_BACKUP_S3_BASEPATH
      value: "zeebe"
    # Do NOT include ZEEBE_BROKER_DATA_BACKUP_S3_ACCESSKEY
    # Do NOT include ZEEBE_BROKER_DATA_BACKUP_S3_SECRETKEY
```

Then apply the changes:

```bash
helm upgrade camunda camunda/camunda-platform \
  --version <chart-version> \
  --namespace <namespace> \
  --values <your-values-file>.yml
```

---

## Step 8 — Trigger a Backup

Once all the above steps are complete and Zeebe pods have restarted, port-forward the management port and trigger a backup.

### 8.1 — Port-forward the Zeebe management port

```bash
kubectl -n <namespace> port-forward camunda-zeebe-0 9600:9600
```

### 8.2 — Pause Zeebe exporting

```bash
curl -XPOST "http://localhost:9600/<context-path>/actuator/exporting/pause"
```

Expected response: `{"body":null,"status":204,"contentType":null}`

### 8.3 — Generate a backup ID and trigger the backup

```bash
BACKUP_ID=$(date +%s)
echo "Using backup ID: $BACKUP_ID"

curl -XPOST "http://localhost:9600/<context-path>/actuator/backupRuntime" \
  -H "Content-Type: application/json" \
  -d "{\"backupId\": $BACKUP_ID}"
```

Expected response:
```json
{"message": "A backup with id <backupId> has been scheduled. Use GET actuator/backups/<backupId> to monitor the status."}
```

### 8.4 — Monitor backup status

```bash
curl "http://localhost:9600/<context-path>/actuator/backupRuntime/$BACKUP_ID"
```

### 8.5 — Resume Zeebe exporting

Once the backup is complete, resume exporting:

```bash
curl -XPOST "http://localhost:9600/<context-path>/actuator/exporting/resume"
```

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `anonymous is not authorized to perform: iam:PassRole` | Request is unsigned | Use SigV4-signed requests from inside the cluster |
| `Failed to connect... port 443` | OpenSearch is VPC-only | Run commands from inside the cluster |
| `HTTP 401 Unauthorized` | IAM role not set as master user | Set IAM ARN as master user in OpenSearch security config |
| `HTTP 403 Forbidden` | Domain access policy missing | Add resource-based access policy to OpenSearch domain |
| `settings.role_arn is needed` | Missing role_arn in snapshot settings | Include `role_arn` in the snapshot repository settings |
| `sts:AssumeRole not authorized` | Trust policy missing `es.amazonaws.com` | Add `es.amazonaws.com` to the role's trust policy |
| `s3:PutObject not authorized` | Missing S3 permissions | Add S3 backup policy to the IAM role |
| `AWS Access Key Id does not exist` | Static keys expired | Remove static keys and use IRSA instead |
