# Describes where Terraform stores state, using workspaces per environment (dev/tst/prd).
# The bucket is in https://console.cloud.google.com/storage/browser?project=ent-servicelnk-dev.
# Each environment's GHA service account (gh-servicelinker-5cdd-*@ent-github-shr.iam.gserviceaccount.com)
# must be granted roles/storage.objectAdmin on this bucket.
terraform {
  backend "gcs" {
    bucket = "ent-gcs-tfa-servicelinker"
  }
}
