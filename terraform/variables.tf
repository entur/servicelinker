variable "gcs_bucket_project" {
  description = "The GCP project hosting the project resources"
}

variable "pubsub_topic_project" {
  description = "The GCP project hosting Pub/Sub topics"
}

variable "servicelinker_project" {
  description = "The GCP project hosting the Servicelinker application"
}

variable "service_account" {
  description = "Service account for Servicelinker to use for IAM roles."
}

variable "servicelinker_exchange_storage_bucket" {
  description = "The name of the Servicelinker exchange bucket in GCP"
}

variable "marduk_service_account" {
  description = "The service account of the marduk application"
}

variable "marduk_service_account_exchange_bucket_role" {
  description = "Role of the Service Account - more about roles https://cloud.google.com/storage/docs/access-control/iam-roles"
  default     = "roles/storage.objectViewer"
}

variable "labels" {
  description = "Labels used in all resources"
  type        = map(string)
  default = {
    manager = "terraform"
    team    = "ror"
    slack   = "talk-ror"
    app     = "servicelinker"
  }
}

variable "location" {
  description = "GCP bucket location"
  default     = "europe-west1"
}

variable "bucket_instance_suffix" {
  description = "A suffix for the bucket instance, may be changed if environment is destroyed and then needed again (name collision workaround) - also bucket names must be globally unique"
}

variable "force_destroy" {
  description = "(Optional, Default: false) When deleting a bucket, this boolean option will delete all contained objects. If you try to delete a bucket that contains objects, Terraform will fail that run"
  default     = false
}

variable "storage_class" {
  description = "GCP storage class"
  default     = "STANDARD"
}

variable "versioning" {
  description = "The bucket's Versioning configuration."
  default     = "false"
}

variable "log_bucket" {
  description = "The bucket's Access & Storage Logs configuration"
  default     = "false"
}
