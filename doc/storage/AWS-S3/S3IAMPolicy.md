# Connection via IAM Policy

To grant S3 permissions to an IAM role, follow these steps:

Navigate to IAM Roles
Go to the IAM service in the AWS console.

Click on Roles to manage existing roles.

![img.png](images/IAMRole.png)

Use the search bar to locate an existing IAM role to which you want to add S3 permissions.

![img.png](images/IAMPermissionPolicies.png)

On the right-hand side, navigate to:

"Add permission" → "Create inline policy" (to create a new policy).

"Attach policies" (to attach an existing policy).

![img.png](images/IAMCreateInlinePolicy.png)

# Configure the incline Policy
### Step 1: Select a Service

From the Services dropdown, choose a service to apply permissions to.

![img_1.png](images/IAMCreateInlinePolicystep1.png)

### Step 2: Choose S3
Select S3 as the target service.
![img.png](images/IAMCreateInlinePolicystep2.png)

### Step 3: Define Actions and Resources
Choose the specific S3 actions and resources that the policy should allow access to.

![img.png](images/IAMCreateInlinePolicyStep3.png)

### Step 4: Review and Create
Review the policy settings and click Create to finalize the configuration.

![img_1.png](images/IAMCreateInlinePolicyStep4.png)

