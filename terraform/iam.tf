resource "google_storage_bucket_iam_member" "servicelinker_exchange_storage_iam_member" {
  bucket = google_storage_bucket.exchange_bucket.name
  role   = var.marduk_service_account_exchange_bucket_role
  member = var.marduk_service_account
}
