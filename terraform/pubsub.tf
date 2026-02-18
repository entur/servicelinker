# (see integration-guide.md — topics are owned by Marduk, these are cross-project subscriptions)

resource "google_pubsub_subscription" "ServicelinkerInboundQueue" {
  name                       = "ServicelinkerInboundQueue"
  topic                      = "projects/${var.pubsub_topic_project}/topics/ServicelinkerInboundQueue"
  project                    = var.servicelinker_project
  labels                     = var.labels
  message_retention_duration = "3600s"
  ack_deadline_seconds       = 600
  expiration_policy {
    ttl = ""
  }
  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }
}

resource "google_pubsub_subscription" "ServicelinkerStatusQueue" {
  name                       = "ServicelinkerStatusQueue"
  topic                      = "projects/${var.pubsub_topic_project}/topics/ServicelinkerStatusQueue"
  project                    = var.servicelinker_project
  labels                     = var.labels
  message_retention_duration = "3600s"
  ack_deadline_seconds       = 600
  expiration_policy {
    ttl = ""
  }
  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }
}
