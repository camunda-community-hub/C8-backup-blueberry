# AWS S3 

This documentation is based on 8.6.x version


# Pre requisite

Check the [AWSPrerequisite.md](AWSPrerequisite.md)

# Principle

Zeebe is connected directly to the Azure bucket. It will save the backup at the root folder in the bucket.


![Principle Zeebe](images/PrincipleZeebeS3.png)

Operate, TaskList, Optimize run the backup on Elastic search. They ask Elastic search to back up the correct index on a repository (this repository is a parameter on Operate).
The repository is configured in Elasticsearch, pointing to the bucket in Azure. This configuration (create the repository) must be done in advance in Elasticsearch


![Principal Operate](images/PrincipalOperateS3.png)

# Connection via an account name / account key

Connect your storage using an [AWSAccountName.md](AWSAccountName.md)

# Connection via an IAM policy

connect your storage using an [AWSIAMPolicy.md](AWSIAMPolicy.md)



