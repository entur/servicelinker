# Integrating Servicelinker After Ashur in the Marduk Pipeline

This document describes the existing Marduk-Ashur pipeline and how Servicelinker fits in. It covers the full data flow, resource ownership, Terraform configuration, IAM permissions, and message contracts.

## Pipeline Overview

Marduk orchestrates NeTEx dataset processing. After Antu pre-validation succeeds, Marduk triggers Ashur to filter the dataset. Ashur writes the filtered result back to the marduk-exchange bucket and publishes a status message to Marduk. Marduk then triggers Servicelinker, which adds service links and publishes a status message back to Marduk. Finally, Marduk triggers Antu post-validation.

```
Marduk                        Ashur                         Servicelinker
  │                             │                               │
  │  1. Publish to              │                               │
  │     FilterNetexFileQueue    │                               │
  ├────────────────────────────►│                               │
  │                             │  2. Read NeTEx ZIP from       │
  │                             │     marduk-exchange-* bucket  │
  │                             │                               │
  │                             │  3. Filter NeTEx data         │
  │                             │                               │
  │                             │  4. Upload filtered ZIP to    │
  │                             │     marduk-exchange-* bucket  │
  │                             │                               │
  │  5. Consume SUCCESS from    │  5. Publish SUCCESS to        │
  │     FilterNetexFileStatusQ  │     FilterNetexFileStatusQueue│
  │◄────────────────────────────┤                               │
  │                             │                               │
  │  6. Publish to              │                               │
  │     ServicelinkerInboundQ   │                               │
  ├─────────────────────────────┼──────────────────────────────►│
  │                             │                               │
  │                             │           ┌───────────────────┤
  │                             │           │ 7. Read filtered  │
  │                             │           │    ZIP from       │
  │                             │           │    marduk-exchange│
  │                             │           │                   │
  │                             │           │ 8. Add service    │
  │                             │           │    links          │
  │                             │           │                   │
  │                             │           │ 9. Upload result  │
  │                             │           │    to servicelinker│
  │                             │           │    exchange bucket│
  │                             │           └───────────────────┤
  │                             │                               │
  │  10. Consume status from    │          10. Publish status   │
  │      ServicelinkerStatusQ   │              to Marduk        │
  │◄────────────────────────────┼───────────────────────────────┤
  │                             │                               │
  │  11. Copy result from       │                               │
  │      servicelinker exchange │                               │
  │      bucket, trigger Antu   │                               │
  │      post-validation        │                               │
```

## Existing Resources: What Already Exists

### A — Input Pub/Sub Topic: `FilterNetexFileQueue`

| Property | Value |
|---|---|
| **GCP project** | `ent-marduk-{dev,tst,prd}` |
| **Created by** | Marduk Terraform (`entur/marduk`, `terraform/pubsub.tf`) |
| **Publisher** | Marduk, after Antu pre-validation succeeds (only when experimental import is enabled) |
| **Subscriber** | Ashur (subscription created in `entur/ashur`, `terraform/pubsub.tf`) |
| **Relevance to Servicelinker** | None directly — Servicelinker does not interact with this topic |

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
| **Relevance to Servicelinker** | None directly — Ashur reads this, not Servicelinker |

### C — Source GCS Bucket: `marduk-exchange-*`

| Property | Value |
|---|---|
| **Bucket names** | `marduk-exchange-dev`, `marduk-exchange-test`, `marduk-exchange-production` |
| **GCP project** | `ent-marduk-{dev,tst,prd}` |
| **Created by** | External infrastructure (not in `entur/marduk` or `entur/ashur` Terraform — passed as variable) |
| **IAM** | Ashur SA granted `roles/storage.objectViewer` (in Marduk's `terraform/iam.tf`) |
| **Relevance to Servicelinker** | None — Servicelinker reads from the Ashur exchange bucket, not from Marduk's |

### D — Ashur Output Files

Ashur produces three artifacts per filtering run:

| File | Path Pattern | Destination | Content |
|---|---|---|---|
| Filtered NeTEx ZIP | `{codespace}/{correlationId}/{netexSource}/filtered_{filename}.zip` | Internal bucket + copied to exchange bucket | Filtered NeTEx XML data |
| Entities report | `{codespace}/{correlationId}/{netexSource}/entities.txt` | Internal bucket only | List of entity IDs retained after filtering |
| Filtering report | `reports/{codespace}/filtering-report-{correlationId}.json` | Internal bucket only | JSON: `correlationId`, `codespace`, `status`, `reason`, `created` |

**The filtered ZIP is the file Servicelinker will consume.** Its path is communicated via the `FilteredNetexFilePath` attribute in the status message on topic F.

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

**Servicelinker will need read access to the exchange bucket** — see [Permissions](#permissions-servicelinker-will-need) below.

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

**When Servicelinker is inserted, Marduk's SUCCESS handler must be modified** to wait for Servicelinker's output instead of immediately triggering post-validation.

## Environment Matrix

| | Dev | Test | Production |
|---|---|---|---|
| **Marduk GCP project** | `ent-marduk-dev` | `ent-marduk-tst` | `ent-marduk-prd` |
| **Ashur GCP project** | `ent-ashur-dev` | `ent-ashur-tst` | `ent-ashur-prd` |
| **Marduk exchange bucket** | `marduk-exchange-dev` | `marduk-exchange-test` | `marduk-exchange-production` |
| **Ashur internal bucket** | `ror-ashur-internal-gcp-dev` | `ror-ashur-internal-gcp-tst` | `ror-ashur-internal-gcp-prd` |
| **Ashur exchange bucket** | `ror-ashur-exchange-gcp-dev` | `ror-ashur-exchange-gcp-tst` | `ror-ashur-exchange-gcp-prd` |
| **Marduk SA** | `application@ent-marduk-dev.iam.gserviceaccount.com` | `application@ent-marduk-tst.iam.gserviceaccount.com` | `application@ent-marduk-prd.iam.gserviceaccount.com` |
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
| Read `ror-ashur-exchange-gcp-*` | `entur/ashur` `terraform/iam.tf` | `google_storage_bucket_iam_member` granting Servicelinker SA `roles/storage.objectViewer` on the exchange bucket |
| Publish to a status topic in Marduk | `entur/marduk` `terraform/iam.tf` | `google_pubsub_topic_iam_member` granting Servicelinker SA `roles/pubsub.publisher` on the new status topic |

### Servicelinker must grant (in its own Terraform)

| Access | To whom | What to add |
|---|---|---|
| Read Servicelinker's exchange bucket | Marduk SA | `google_storage_bucket_iam_member` granting Marduk SA `roles/storage.objectViewer` |

### New resources Servicelinker needs to create (in its own Terraform)

| Resource | Type | Notes |
|---|---|---|
| Internal GCS bucket | `google_storage_bucket` | For intermediate artifacts, following the `ror-servicelinker-internal-gcp-{suffix}` naming pattern |
| Exchange GCS bucket | `google_storage_bucket` | For output consumed by Marduk, following `ror-servicelinker-exchange-gcp-{suffix}` |
| Subscription to `FilterNetexFileStatusQueue` | `google_pubsub_subscription` | Cross-project subscription in Servicelinker's project to the topic in Marduk's project (or a new dedicated topic — see below) |

### New resources to create in Marduk's Terraform

| Resource | Type | Notes |
|---|---|---|
| Topic `ServicelinkerStatusQueue` (or similar) | `google_pubsub_topic` | For Servicelinker to publish status back to Marduk |
| IAM: Servicelinker SA → publisher on that topic | `google_pubsub_topic_iam_member` | `roles/pubsub.publisher` |

## Terraform Patterns to Follow

All three repos use the same conventions:

- **State backend:** GCS bucket (`ent-gcs-tfa-{service}`)
- **Provider:** `hashicorp/google`
- **Per-environment config:** `terraform/env/kub-ent-{dev,tst,prd}.tfvars`
- **Labels:** `manager=terraform`, `team=ror`, `slack=talk-ror`, `app={service}`
- **Bucket settings:** Uniform bucket-level access, public access prevention enforced, versioning off, logging enabled
- **Pub/Sub subscriptions:** `message_retention_duration=3600s`, `ack_deadline_seconds=600`, no expiry (`ttl=""`), retry backoff 10s–600s

## Message Contract

### Input: What Servicelinker Receives from Ashur (via `FilterNetexFileStatusQueue`)

On `SUCCESS`, the message attributes are:

| Attribute | Key | Description |
|---|---|---|
| Correlation ID | `RutebankenCorrelationId` | UUID for end-to-end tracking |
| Codespace | `EnturDatasetReferential` | Dataset codespace |
| Status | `Status` | `SUCCESS` / `FAILED` / `STARTED` |
| Filtered file path | `FilteredNetexFilePath` | Path to filtered ZIP in `ror-ashur-exchange-gcp-*` (e.g. `{codespace}/{corrId}/{source}/filtered_{file}.zip`) |

### Output: What Servicelinker Should Publish Back to Marduk

Following the same pattern Ashur uses, Servicelinker should publish status messages with:

| Attribute | Description |
|---|---|
| `RutebankenCorrelationId` | Pass through from input |
| `EnturDatasetReferential` | Pass through from input |
| `Status` | `STARTED`, `SUCCESS`, or `FAILED` |
| `LinkedNetexFilePath` (or similar) | Path to the output file in the Servicelinker exchange bucket |
| `LinkingFailureReason` | Error message (on `FAILED` only) |

## Key Source Files in Related Repos

### entur/marduk

| File | What it does |
|---|---|
| `terraform/pubsub.tf` | Creates both Pub/Sub topics (`FilterNetexFileQueue`, `FilterNetexFileStatusQueue`) |
| `terraform/iam.tf` | Grants Ashur SA publish access to status topic + read access to exchange bucket |
| `src/.../AntuNetexValidationStatusRouteBuilder.java` | Publishes to `FilterNetexFileQueue` after pre-validation |
| `src/.../AshurFilteringStatusRouteBuilder.java` | Consumes from `FilterNetexFileStatusQueue`, copies result, triggers post-validation. **This is the file that needs modification to insert Servicelinker.** |
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
