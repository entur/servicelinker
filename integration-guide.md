# Integrating Servicelinker into the Marduk Pipeline

This document describes the Marduk pipeline and how Servicelinker fits in. It covers the full data flow, resource ownership, Terraform configuration, IAM permissions, and message contracts.

## Pipeline Overview

Marduk orchestrates NeTEx dataset processing. After Antu pre-validation succeeds, Marduk triggers Servicelinker to add ServiceLinks to the validated dataset. Servicelinker writes the enriched result to its exchange bucket and publishes a status message back to Marduk. Marduk then triggers Ashur to filter the enriched dataset. After Ashur completes, Marduk triggers Antu post-validation.

**Pipeline order:** Antu pre-validation → **Servicelinker** → Ashur filtering → Antu post-validation

In marduk, the insertion point is `AntuNetexValidationStatusRouteBuilder.java`: the `direct:antuNetexValidationComplete` route currently flows directly to `direct:ashurNetexFilterAfterPreValidation`. Servicelinker is inserted between these two.

```
Marduk                    Servicelinker                   Ashur
  │                            │                            │
  │  (Antu pre-validation OK)  │                            │
  │                            │                            │
  │  1. Publish to             │                            │
  │     ServicelinkerInboundQ  │                            │
  ├───────────────────────────►│                            │
  │                            │                            │
  │                    ┌───────┤                            │
  │                    │ 2. Read pre-validated              │
  │                    │    ZIP from marduk-exchange        │
  │                    │                                    │
  │                    │ 3. Add ServiceLinks                │
  │                    │                                    │
  │                    │ 4. Upload enriched ZIP to          │
  │                    │    servicelinker-exchange          │
  │                    └───────┤                            │
  │                            │                            │
  │  5. Consume SUCCESS from   │  5. Publish SUCCESS to     │
  │     ServicelinkerStatusQ   │     ServicelinkerStatusQ   │
  │◄───────────────────────────┤                            │
  │                            │                            │
  │  6. Copy result from       │                            │
  │     servicelinker-exchange │                            │
  │     to marduk-exchange     │                            │
  │                            │                            │
  │  7. Publish to             │                            │
  │     FilterNetexFileQueue   │                            │
  ├────────────────────────────┼───────────────────────────►│
  │                            │                            │
  │                            │    8. Read enriched ZIP    │
  │                            │       from marduk-exchange │
  │                            │                            │
  │                            │    9. Filter NeTEx data    │
  │                            │                            │
  │                            │   10. Upload filtered ZIP  │
  │                            │       to ashur-exchange    │
  │                            │                            │
  │  11. Consume SUCCESS from  │   11. Publish SUCCESS to   │
  │      FilterNetexFileStatusQ│       FilterNetexFileStatusQ
  │◄───────────────────────────┼────────────────────────────┤
  │                            │                            │
  │  12. Copy filtered result, │                            │
  │      trigger Antu          │                            │
  │      post-validation       │                            │
```

## Existing Resources: What Already Exists

### A — Input Pub/Sub Topic: `FilterNetexFileQueue`

| Property | Value |
|---|---|
| **GCP project** | `ent-marduk-{dev,tst,prd}` |
| **Created by** | Marduk Terraform (`entur/marduk`, `terraform/pubsub.tf`) |
| **Publisher** | Marduk, after Servicelinker completes (only when experimental import is enabled) |
| **Subscriber** | Ashur (subscription created in `entur/ashur`, `terraform/pubsub.tf`) |
| **Relevance to Servicelinker** | Marduk publishes to this topic after Servicelinker completes |

**Message attributes set by Marduk:**

| Attribute | Description | Example |
|---|---|---|
| `RutebankenCorrelationId` | Correlation ID for tracking | UUID |
| `RutebankenTargetFileHandle` | Path to NeTEx ZIP in marduk-exchange bucket | `outbound/netex/rb_avi/rb_avi-aggregated-netex.zip` |
| `EnturDatasetReferential` | Codespace identifier (prefixed `rb_`) | `rb_avi` |
| `EnturFilteringProfile` | Filter profile | `StandardImportFilter` |
| `NetexSource` | Source system | `marduk` |
| `FileCreatedTimestamp` | When the file was created | ISO timestamp |

### B — Input NeTEx ZIP File

| Property | Value |
|---|---|
| **Path pattern** | `outbound/netex/{referential}/{referential}-aggregated-netex.zip` |
| **Bucket** | `marduk-exchange-*` (see C below) |
| **Content** | ZIP archive of NeTEx XML files (service journeys, stops, routes) |
| **Created by** | Marduk copies the file to the exchange bucket before publishing to topic A |
| **Relevance to Servicelinker** | Servicelinker reads the pre-validated NeTEx ZIP from this bucket |

### C — Source GCS Bucket: `marduk-exchange-*`

| Property | Value |
|---|---|
| **Bucket names** | `marduk-exchange-dev`, `marduk-exchange-test`, `marduk-exchange-production` |
| **GCP project** | `ent-marduk-{dev,tst,prd}` |
| **Created by** | External infrastructure (not in `entur/marduk` or `entur/ashur` Terraform — passed as variable) |
| **IAM** | Ashur SA granted `roles/storage.objectViewer` (in Marduk's `terraform/iam.tf`) |
| **Relevance to Servicelinker** | Servicelinker needs `roles/storage.objectViewer` on this bucket to read the pre-validated NeTEx file |

### D — Ashur Output Files

Ashur produces three artifacts per filtering run:

| File | Path Pattern | Destination | Content |
|---|---|---|---|
| Filtered NeTEx ZIP | `{codespace}/{correlationId}/{netexSource}/filtered_{filename}.zip` | Internal bucket + copied to exchange bucket | Filtered NeTEx XML data |
| Entities report | `{codespace}/{correlationId}/{netexSource}/entities.txt` | Internal bucket only | List of entity IDs retained after filtering |
| Filtering report | `reports/{codespace}/filtering-report-{correlationId}.json` | Internal bucket only | JSON: `correlationId`, `codespace`, `status`, `reason`, `created` |

The filtered ZIP is consumed by Marduk, which copies it and triggers post-validation.

### E — Ashur GCS Buckets

Two buckets, both owned by Ashur:

| Bucket | Names | Purpose |
|---|---|---|
| **Internal** | `ror-ashur-internal-gcp-{dev,tst,prd}` | All artifacts (ZIP, entities, report). Not accessed externally. |
| **Exchange** | `ror-ashur-exchange-gcp-{dev,tst,prd}` | Filtered ZIP only. Read by downstream consumers. |

| Property | Value |
|---|---|
| **GCP project** | `ent-ashur-{dev,tst,prd}` |
| **Created by** | Ashur Terraform (`entur/ashur`, `terraform/storage.tf`) |
| **Settings** | Uniform bucket-level access, public access prevention enforced, versioning off, logging enabled |
| **IAM on exchange bucket** | Marduk SA granted `roles/storage.objectViewer` (in Ashur's `terraform/iam.tf`) |


### F — Status Pub/Sub Topic: `FilterNetexFileStatusQueue`

| Property | Value |
|---|---|
| **GCP project** | `ent-marduk-{dev,tst,prd}` |
| **Created by** | Marduk Terraform (`entur/marduk`, `terraform/pubsub.tf`) |
| **Publisher** | Ashur (granted `roles/pubsub.publisher` in Marduk's `terraform/iam.tf`) |
| **Current subscriber** | Marduk, via `AshurFilteringStatusRouteBuilder.java` |

**Ashur publishes these messages:**

| Status | When | Key Attributes |
|---|---|---|
| `STARTED` | Immediately on receiving a filtering request | `correlationId`, `codespace` |
| `SUCCESS` | After filtering completes | `correlationId`, `codespace`, `Status=SUCCESS`, `FilteredNetexFilePath` |
| `FAILED` | On error | `correlationId`, `codespace`, `Status=FAILED`, `FilteringFailureReason` |

**What Marduk currently does on `SUCCESS`:**
1. Reads the filtered ZIP from `ror-ashur-exchange-gcp-*` using `FilteredNetexFilePath`
2. Copies it to Marduk's internal bucket at `filtered-netex/{ref}/netex-without-blocks/{corrId}/{ref}-aggregated-netex.zip`
3. Copies it to Antu's validation bucket
4. Triggers Antu post-validation

This flow remains unchanged — Ashur filtering now runs on the ServiceLink-enriched dataset (produced by Servicelinker), but the post-Ashur flow stays the same.

## Environment Matrix

| | Dev | Test | Production |
|---|---|---|---|
| **Marduk GCP project** | `ent-marduk-dev` | `ent-marduk-tst` | `ent-marduk-prd` |
| **Servicelinker GCP project** | `ent-servicelnk-dev` | `ent-servicelnk-tst` | `ent-servicelnk-prd` |
| **Ashur GCP project** | `ent-ashur-dev` | `ent-ashur-tst` | `ent-ashur-prd` |
| **Marduk exchange bucket** | `marduk-exchange-dev` | `marduk-exchange-test` | `marduk-exchange-production` |
| **Ashur internal bucket** | `ror-ashur-internal-gcp-dev` | `ror-ashur-internal-gcp-tst` | `ror-ashur-internal-gcp-prd` |
| **Ashur exchange bucket** | `ror-ashur-exchange-gcp-dev` | `ror-ashur-exchange-gcp-tst` | `ror-ashur-exchange-gcp-prd` |
| **Marduk SA** | `application@ent-marduk-dev.iam.gserviceaccount.com` | `application@ent-marduk-tst.iam.gserviceaccount.com` | `application@ent-marduk-prd.iam.gserviceaccount.com` |
| **Servicelinker SA** | `application@ent-servicelnk-dev.iam.gserviceaccount.com` | `application@ent-servicelnk-tst.iam.gserviceaccount.com` | `application@ent-servicelnk-prd.iam.gserviceaccount.com` |
| **Ashur SA** | `application@ent-ashur-dev.iam.gserviceaccount.com` | `application@ent-ashur-tst.iam.gserviceaccount.com` | `application@ent-ashur-prd.iam.gserviceaccount.com` |

## Service Accounts & IAM: How Cross-Project Access Works

Each service runs in its own GCP project with its own service account (`application@ent-{service}-{env}.iam.gserviceaccount.com`). Cross-project access is granted by the resource owner's Terraform:

| Grant | Resource Owner Repo | Terraform File | Role |
|---|---|---|---|
| Ashur SA → read `marduk-exchange-*` | `entur/marduk` | `terraform/iam.tf` | `roles/storage.objectViewer` |
| Ashur SA → publish to `FilterNetexFileStatusQueue` | `entur/marduk` | `terraform/iam.tf` | `roles/pubsub.publisher` |
| Marduk SA → read `ror-ashur-exchange-gcp-*` | `entur/ashur` | `terraform/iam.tf` | `roles/storage.objectViewer` |

**Pattern:** The repo that *owns* the resource grants access to external service accounts. This is the pattern Servicelinker should follow.

## Permissions Servicelinker Will Need

Based on the Ashur/Marduk pattern, Servicelinker will need:

### Servicelinker must be granted (by other repos' Terraform)

| Access | Granted by | What to add |
|---|---|---|
| Read `marduk-exchange-*` | `entur/marduk` `terraform/iam.tf` | `google_storage_bucket_iam_member` granting Servicelinker SA `roles/storage.objectViewer` on the exchange bucket |
| Publish to `ServicelinkerStatusQueue` in Marduk | `entur/marduk` `terraform/iam.tf` | `google_pubsub_topic_iam_member` granting Servicelinker SA `roles/pubsub.publisher` on the status topic |

### Servicelinker must grant (in its own Terraform)

| Access | To whom | What to add |
|---|---|---|
| Read Servicelinker's exchange bucket | Marduk SA | `google_storage_bucket_iam_member` granting Marduk SA `roles/storage.objectViewer` |

### New resources Servicelinker needs to create (in its own Terraform)

| Resource | Type | Notes |
|---|---|---|
| Internal GCS bucket | `google_storage_bucket` | For intermediate artifacts, following the `ror-servicelinker-internal-gcp-{suffix}` naming pattern |
| Exchange GCS bucket | `google_storage_bucket` | For output consumed by Marduk, following `ror-servicelinker-exchange-gcp-{suffix}` |
| Subscription to `ServicelinkerInboundQueue` | `google_pubsub_subscription` | Cross-project subscription in Servicelinker's project to the inbound topic in Marduk's project |

### New resources to create in Marduk's Terraform

| Resource | Type | Notes |
|---|---|---|
| Topic `ServicelinkerInboundQueue` | `google_pubsub_topic` | For Marduk to trigger Servicelinker after pre-validation |
| Topic `ServicelinkerStatusQueue` | `google_pubsub_topic` | For Servicelinker to publish status back to Marduk |
| IAM: Servicelinker SA → publisher on status topic | `google_pubsub_topic_iam_member` | `roles/pubsub.publisher` |
| Subscription to `ServicelinkerStatusQueue` | `google_pubsub_subscription` | For Marduk to consume Servicelinker status messages |

## Terraform Patterns to Follow

All three repos use the same conventions:

- **State backend:** GCS bucket (`ent-gcs-tfa-{service}`)
- **Provider:** `hashicorp/google`
- **Per-environment config:** `terraform/env/kub-ent-{dev,tst,prd}.tfvars`
- **Labels:** `manager=terraform`, `team=ror`, `slack=talk-ror`, `app={service}`
- **Bucket settings:** Uniform bucket-level access, public access prevention enforced, versioning off, logging enabled
- **Pub/Sub subscriptions:** `message_retention_duration=3600s`, `ack_deadline_seconds=600`, no expiry (`ttl=""`), retry backoff 10s–600s

## Message Contract

### Input: What Servicelinker Receives from Marduk (via `ServicelinkerInboundQueue`)

After Antu pre-validation succeeds, Marduk publishes a message to trigger Servicelinker. The message attributes are:

| Attribute | Key | Description |
|---|---|---|
| Correlation ID | `RutebankenCorrelationId` | UUID for end-to-end tracking |
| File path | `RutebankenTargetFileHandle` | Path to pre-validated NeTEx ZIP in `marduk-exchange-*` (e.g. `outbound/netex/rb_avi/rb_avi-aggregated-netex.zip`) |
| Codespace | `EnturDatasetReferential` | Dataset codespace (prefixed `rb_`) |

### Output: What Servicelinker Publishes Back to Marduk (via `ServicelinkerStatusQueue`)

Following the same pattern Ashur uses, Servicelinker publishes status messages with:

| Attribute | Description |
|---|---|
| `RutebankenCorrelationId` | Pass through from input |
| `EnturDatasetReferential` | Pass through from input |
| `Status` | `STARTED`, `SUCCESS`, or `FAILED` |
| `LinkedNetexFilePath` | Path to the enriched file in the Servicelinker exchange bucket |
| `LinkingFailureReason` | Error message (on `FAILED` only) |

## Key Source Files in Related Repos

### entur/marduk

| File | What it does |
|---|---|
| `terraform/pubsub.tf` | Creates Pub/Sub topics (`FilterNetexFileQueue`, `FilterNetexFileStatusQueue`). **New topics for Servicelinker go here.** |
| `terraform/iam.tf` | Grants Ashur SA publish access to status topic + read access to exchange bucket. **Servicelinker SA grants go here.** |
| `src/.../AntuNetexValidationStatusRouteBuilder.java` | Routes from `direct:antuNetexValidationComplete` to `direct:ashurNetexFilterAfterPreValidation`. **This is the file that needs modification to insert Servicelinker** (publish to `ServicelinkerInboundQueue` instead of going directly to Ashur). |
| `src/.../AshurFilteringStatusRouteBuilder.java` | Consumes from `FilterNetexFileStatusQueue`, copies result, triggers post-validation |
| `src/.../Constants.java` | Defines all header/attribute constants |

### entur/ashur

| File | What it does |
|---|---|
| `terraform/storage.tf` | Creates internal + exchange buckets |
| `terraform/pubsub.tf` | Creates subscriptions to both topics |
| `terraform/iam.tf` | Grants Marduk SA read access to exchange bucket |
| `src/.../Constants.kt` | Topic/subscription names, status constants |
| `src/.../NetexFilterRouteBuilder.kt` | Main Camel route: consume from topic A, filter, publish to topic F |
| `src/.../FilterService.kt` | Core filtering logic, file I/O |
| `src/.../Extensions.kt` | Message attribute extraction helpers |
| `helm/ashur/env/values-kub-ent-{dev,tst,prd}.yaml` | Per-environment bucket names and project IDs |
