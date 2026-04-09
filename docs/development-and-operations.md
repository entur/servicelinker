# Servicelinker: Development, Testing & Operations

## Overview

Servicelinker enriches Nordic profile NeTEx files with ServiceLink elements (route geometries and distances) using OSRM routing. It sits
in the marduk pipeline after antu pre-validation and before ashur filtering. This means servicelinker processes the validated but
unfiltered NeTEx data, adding ServiceLinks before ashur applies its filtering.

**Pipeline flow (orchestrated by marduk):** antu pre-validation → **servicelinker** → ashur filtering → antu post-validation

Input and output are NeTEx datasets (ZIP archives or single XML files) stored in GCS buckets. Pipeline orchestration happens via PubSub
messages.

---

## Local Development

### Prerequisites

- JDK 21+
- Docker (for docker-compose and testcontainers)
- Gradle (wrapper included)

### Running Tests

```bash
# All tests (includes testcontainers-based integration tests, requires Docker)
./gradlew test

# Just unit tests (no Docker needed)
./gradlew test --tests "no.entur.servicelinker.service.*"
./gradlew test --tests "no.entur.servicelinker.util.*"

# Specific test class
./gradlew test --tests "*OSRMServiceTest*"
./gradlew test --tests "*ZipHandlingTest*"
./gradlew test --tests "*NetexProcessorTest*"
```

### Running with docker-compose

docker-compose starts the full local stack: PubSub emulator, fake GCS server, and the servicelinker app.

```bash
# Build the fat JAR first
./gradlew bootJar

# Start everything
docker-compose up --build

# In another terminal, test the REST API:
curl -X POST http://localhost:8080/api/servicelink/process-xml \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/sample-netex.xml
```

The docker-compose setup includes an init container that creates PubSub topics/subscriptions and GCS buckets automatically:

| Resource | Name |
|----------|------|
| Inbound topic | `ServicelinkerInboundQueue` |
| Status topic | `ServicelinkerStatusQueue` |
| Inbound subscription | `ServicelinkerInboundQueue` |
| Status subscription | `ServicelinkerStatusQueue` |
| Input bucket | `marduk-exchange` |
| Output bucket | `servicelinker-exchange` |

### Triggering a full pipeline run locally

A `trigger` profile service automates the manual upload-and-publish steps. Place a NeTEx ZIP in `test-data/` and run:

```bash
./gradlew bootJar && docker-compose up --build
docker compose --profile trigger run --rm trigger
```

Or point at a specific file:

```bash
docker compose --profile trigger run --rm \
  -e NETEX_FILE=/data/Havila_NeTEx_20260219.zip trigger
```

The trigger uploads the ZIP to `marduk-exchange` in fake-gcs, then publishes a PubSub message to `ServicelinkerInboundQueue` with `RutebankenTargetFileHandle`, a random `correlationId`, and referential `LOCAL`. Watch the output with `docker compose logs -f servicelinker`.

The processed file is written to `servicelinker-exchange` at the same object path:

```bash
# List output bucket
curl -s http://localhost:4443/storage/v1/b/servicelinker-exchange/o | jq '.items[].name'

# Download processed file
curl -s "http://localhost:4443/storage/v1/b/servicelinker-exchange/o/Havila_NeTEx_20260219.zip?alt=media" \
  -o processed.zip

# Verify ServiceLinks were added
unzip -p processed.zip | grep -c "ServiceLink"

# Visualize the result after processing:
scripts/view-servicelinks.mjs processed.zip

```

### Using k8s OSRM instances locally

By default, docker-compose and `bootRun` use the public OSRM (`router.project-osrm.org`), which only does car routing and produces inland routes for ferry/water services. Use the port-forward script to route traffic to the real k8s OSRM instances instead:

```bash
# Terminal 1 — forwards k8s OSRM services to localhost, writes .env, cleans up on Ctrl+C
./scripts/port-forward-osrm.sh          # defaults to dev context
./scripts/port-forward-osrm.sh tst      # use tst context

# Terminal 2 — docker-compose and bootRun both pick up .env automatically
docker compose up
# or
./gradlew bootRun
```

Port assignments written to `.env`:

| localhost port | k8s service | Modes |
|----------------|-------------|-------|
| 5001 | `osrm-bus` | BUS, COACH |
| 5002 | `osrm-rail` | RAIL, TRAM, METRO |
| 5003 | `osrm-water` | WATER, FERRY (no separate ferry service exists) |
| 5004 | `osrm-car` | default/fallback |

`.env` is gitignored and deleted automatically when the script exits (Ctrl+C).

### Running the app directly (outside Docker)

For faster iteration you can run the app directly with emulators running in Docker:

```bash
# Start only the emulators (init creates topics, subscriptions, and buckets)
docker-compose up pubsub-emulator fake-gcs emulator-init

# Run the app — emulator URLs are set automatically by the bootRun task
./gradlew bootRun
```

### End-to-end test via PubSub (local)

This walks through the full pipeline: upload a NeTEx file to fake GCS, publish a PubSub message, and verify the processed output.

With docker-compose running (`docker-compose up --build`), open a second terminal.

**Step 1: Start monitoring the outbound subscription.**

In a separate terminal, start the monitoring script. It polls the outbound subscription every 2 seconds and prints messages as they arrive:

```bash
./scripts/monitor-outbound.sh
```

**Step 2: Upload a NeTEx file to the fake GCS input bucket.**

fake-gcs-server implements the Google Cloud Storage JSON API. Upload using the `uploadType=media` endpoint:

```bash
curl -s -X POST \
  "http://localhost:4443/upload/storage/v1/b/marduk-exchange/o?uploadType=media&name=test/sample-netex.xml" \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/sample-netex.xml
```

Verify the file was uploaded:

```bash
curl -s "http://localhost:4443/storage/v1/b/marduk-exchange/o" | jq .
```

**Step 3: Publish a message to the inbound topic.**

This triggers servicelinker to download the file from GCS, process it, upload the result, and publish a completion message:

```bash
curl -s -X POST \
  "http://localhost:8085/v1/projects/local-project/topics/ServicelinkerInboundQueue:publish" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{
      "attributes": {
        "RutebankenTargetFileHandle": "test/sample-netex.xml",
        "RutebankenCorrelationId": "manual-test-001",
        "EnturDatasetReferential": "rb_test"
      },
      "data": ""
    }]
  }'
```

**Step 4: Watch the docker-compose logs.**

In the docker-compose terminal you should see log lines like:

```
Received PubSub message: file=test/sample-netex.xml, correlationId=manual-test-001, referential=rb_test
Processing dataset: gs://marduk-exchange/test/sample-netex.xml
Processing complete. Added N ServiceLinks. Publishing status.
Message acknowledged for correlationId=manual-test-001
```

**Step 5: Verify the processed output in GCS.**

The processed file is written to the output bucket at the same object path:

```bash
curl -s "http://localhost:4443/storage/v1/b/servicelinker-exchange/o" | jq .
```

Download and inspect the processed file:

```bash
curl -s "http://localhost:4443/storage/v1/b/servicelinker-exchange/o/test%2Fsample-netex.xml?alt=media" \
  -o processed.xml
```

Verify ServiceLinks were added:

```bash
grep -c "ServiceLink" processed.xml
```

Visualize on a map:

```bash
scripts/view-servicelinks.mjs processed.xml
```

**Step 6: Check the status PubSub message.**

The monitoring script from step 1 should have printed the status message with these attributes:

| Attribute | Expected value |
|-----------|----------------|
| `RutebankenCorrelationId` | `manual-test-001` |
| `EnturDatasetReferential` | `rb_test` |
| `LinkingStatus` | `SUCCESS` |
| `LinkedNetexFilePath` | `test/sample-netex.xml` |
| `addedServiceLinksCount` | Number of ServiceLinks added |

If you didn't start the monitoring script, you can pull manually:

```bash
curl -s -X POST \
  "http://localhost:8085/v1/projects/local-project/subscriptions/ServicelinkerStatusQueue:pull" \
  -H "Content-Type: application/json" \
  -d '{"maxMessages": 10}' | jq .
```

### REST API (for manual processing/debugging)

These endpoints are always available regardless of PubSub:

```bash
# Process raw XML directly (returns processed XML)
curl -X POST http://localhost:8080/api/servicelink/process-xml \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/sample-netex.xml \
  -o processed.xml

# Filter to specific transport modes (bracket notation, URL-encoded)
curl -X POST "http://localhost:8080/api/servicelink/process-xml?modes=%5BBUS%2CTRAM%5D" \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/sample-netex.xml \
  -o processed.xml

# Same for ZIP
curl -X POST "http://localhost:8080/api/servicelink/process-zip?modes=%5BBUS%2CTRAM%5D" \
  -H "Content-Type: application/zip" \
  --data-binary @file.zip \
  -o processed.zip
```

The `modes` parameter controls which transport modes are processed:

| Value | Behaviour |
|-------|-----------|
| _(absent)_ | process all modes |
| `[]` | skip processing, return input unchanged |
| `[BUS,TRAM]` | process only BUS and TRAM |

Values are case-insensitive. Comma-separated without brackets (`BUS,TRAM`) also works.

```bash
# Cache stats
curl http://localhost:8080/api/servicelink/cache/stats

# Clear route cache
curl -X POST http://localhost:8080/api/servicelink/cache/clear

# Health check
curl http://localhost:8080/actuator/health
```

---

## Configuration

Configuration uses Spring profiles activated by the `COMMON_ENV` environment variable set by k8s. When `COMMON_ENV` is not set (local
development), no profile is active and the defaults in `application.properties` apply.

### Profiles

| `COMMON_ENV` | Profile file | GCP project | OSRM | Logging |
|--------------|-------------|-------------|------|---------|
| (unset) | `application.properties` | `local-project` | Public OSRM | plain text, DEBUG |
| `dev` | `application-dev.properties` | `ent-servicelnk-dev` | Internal OSRM | JSON (GCL), DEBUG |
| `tst` | `application-tst.properties` | `ent-servicelnk-tst` | Internal OSRM | JSON (GCL), DEBUG |
| `prd` | `application-prd.properties` | `ent-servicelnk-prd` | Internal OSRM | JSON (GCL), INFO |

### Shared defaults (application.properties)

| Property | Default | Description |
|----------|---------|-------------|
| `servicelinker.pubsub.inbound.subscription` | `ServicelinkerInboundQueue` | PubSub subscription to listen on |
| `servicelinker.pubsub.outbound.topic` | `ServicelinkerStatusQueue` | PubSub topic for status messages |
| `gcs.bucket.input` | `marduk-exchange` | GCS bucket for input datasets |
| `gcs.bucket.output` | `servicelinker-exchange` | GCS bucket for processed output |
| `osrm.timeout` | `30000` | OSRM HTTP timeout in milliseconds |
| `osrm.cache-routes` | `true` | Cache OSRM route results in memory |

### OSRM per-mode routing

Separate OSRM instances per transport mode. Each instance has its own network data but all expose the `/route/v1/driving/` endpoint. The
base defaults (public OSRM) are overridden per environment profile with internal service URLs.

| Property | Transport modes |
|----------|-----------------|
| `osrm.urls.bus` | BUS, COACH |
| `osrm.urls.rail` | RAIL, TRAM, METRO |
| `osrm.urls.ferry` | FERRY |
| `osrm.urls.water` | WATER |
| `osrm.urls.default` | Fallback for unknown modes |

Note: there is no dedicated `osrm-ferry` k8s service — `osrm.urls.ferry` is configured to share `osrm-water` in the port-forward setup.

### Emulator configuration

For local development (docker-compose or running directly), these environment variables activate emulator mode:

| Variable | Description |
|----------|-------------|
| `SPRING_CLOUD_GCP_PUBSUB_EMULATOR_HOST` | PubSub emulator endpoint (e.g. `localhost:8085`). Routes PubSub traffic to emulator. |
| `STORAGE_EMULATOR_HOST` | GCS emulator endpoint (e.g. `http://localhost:4443`). Also activates NoCredentials mode. |

---

## PubSub Message Format

### Inbound message (from marduk via `ServicelinkerInboundQueue`)

The message body is ignored. All routing info is in **message attributes** (Marduk conventions):

| Attribute | Required | Description |
|-----------|----------|-------------|
| `RutebankenTargetFileHandle` | Yes | Path to NeTEx ZIP in marduk-exchange bucket (e.g. `outbound/netex/rb_avi/rb_avi-aggregated-netex.zip`) |
| `RutebankenCorrelationId` | No | UUID for end-to-end pipeline tracking |
| `EnturDatasetReferential` | No | Dataset codespace (e.g. `rb_avi`) |

### Status message (to marduk via `ServicelinkerStatusQueue`)

Published on completion. Attributes:

| Attribute | Description |
|-----------|-------------|
| `RutebankenCorrelationId` | Passed through from inbound message |
| `EnturDatasetReferential` | Passed through from inbound message |
| `LinkingStatus` | `SUCCESS` or `FAILED` |
| `LinkedNetexFilePath` | Path to enriched file in servicelinker-exchange bucket |
| `addedServiceLinksCount` | Number of ServiceLinks added |

On failure the inbound message is acked (not redelivered) after max retries.

---

## Marduk Integration

Servicelinker integrates into the marduk pipeline via PubSub, positioned between antu pre-validation and ashur filtering.

In marduk, the insertion point is in `AntuNetexValidationStatusRouteBuilder.java`: the `direct:antuNetexValidationComplete` route currently
flows directly to `direct:ashurNetexFilterAfterPreValidation`. Servicelinker is inserted between these two steps.

The changes needed on the marduk side (separate repo/PR):

1. **New Camel route step** between "antu pre-validation complete" and "ashur filtering trigger"
2. **Publish** to `ServicelinkerInboundQueue` with file reference after pre-validation succeeds
3. **Subscribe** to `ServicelinkerStatusQueue`, then continue to ashur filtering
4. ~~**IAM:** Grant servicelinker's service account read access to marduk's exchange bucket, and grant marduk's service account read access
   to servicelinker's exchange bucket~~ (done — Terraform applied)
5. **Nabu:** Register a new pipeline step for status tracking

### Data flow

```
marduk                        servicelinker               marduk
  |                                |                          |
  | (antu pre-validation OK)       |                          |
  |                                |                          |
  |-- publish to                   |                          |
  |   ServicelinkerInboundQueue -->|                          |
  |   (RutebankenTargetFileHandle, |                          |
  |    RutebankenCorrelationId,    |                          |
  |    EnturDatasetReferential)    |                          |
  |                                |-- download from          |
  |                                |   marduk-exchange bucket |
  |                                |                          |
  |                                |-- process NeTEx          |
  |                                |   (add ServiceLinks)     |
  |                                |                          |
  |                                |-- upload to              |
  |                                |   servicelinker-exchange |
  |                                |                          |
  |                                |-- publish to             |
  |                                |   ServicelinkerStatusQ --|->
  |                                |   (LinkingStatus=SUCCESS,    |
  |                                |    LinkedNetexFilePath)     |
  |                                                           |
  |<-- subscribe to ServicelinkerStatusQueue -----------------|
  |    copy result, trigger ashur filtering                   |
```

---

## Building & Deploying

### Build

```bash
# Build fat JAR
./gradlew bootJar

# Build Docker image locally
docker build -t servicelinker .

# Or use Gradle's built-in image builder
./gradlew bootBuildImage
```

### CI/CD

**Application** — Pushes to `main` trigger `.github/workflows/build.yml`:

1. **gradle-build** — builds and tests with `./gradlew build`, uploads JAR artifact
2. **docker-build** — builds Docker image via `entur/gha-docker`
3. **docker-push** — pushes image to registry (skipped on PRs)
4. **scan** — security scan via `entur/gha-security`
5. **deploy-dev → deploy-tst → deploy-prd** — sequential Helm deploys to all three environments (main branch only, using `helm/servicelinker`)

Environment-specific config is handled by Spring profiles activated via `COMMON_ENV` (set in the Helm chart values per environment).

**Terraform** — Changes to `terraform/**` trigger `.github/workflows/terraform.yml` via `entur/gha-terraform@v2`:

- **On PRs:** lint (fmt, tflint, validate) + plan for dev
- **On push to main:** lint + plan + apply for dev → tst → prd

Terraform state is stored in `gs://ent-gcs-tfa-servicelinker` (platform-managed bucket, workspaces per environment). Per-environment config is in `terraform/env/{dev,tst,prd}.tfvars`. Each environment's GHA service account (`gh-servicelinker-5cdd-{env}@ent-github-shr.iam.gserviceaccount.com`) requires `roles/storage.objectAdmin` on this bucket.

### IAM setup

Managed via Terraform across two repos:

**In Servicelinker's Terraform** (`terraform/iam.tf`):
- Marduk SA gets `roles/storage.objectViewer` on the exchange bucket

**In Marduk's Terraform** (`terraform/iam.tf`):
- Servicelinker SA gets `roles/storage.objectViewer` on `marduk-exchange-*`
- Servicelinker SA gets `roles/pubsub.publisher` on `ServicelinkerStatusQueue`
- Servicelinker Terraform SA (`gh-servicelinker-5cdd-{env}@ent-github-shr.iam.gserviceaccount.com`) gets `roles/pubsub.subscriber` on `ServicelinkerInboundQueue` and `ServicelinkerStatusQueue` (needed for cross-project subscription creation)

**Workload identity** (not managed by app Terraform):
- Servicelinker SA needs `roles/pubsub.subscriber` on the inbound subscription
- Servicelinker SA needs `roles/pubsub.publisher` on the outbound topic
- Servicelinker SA needs `roles/storage.objectViewer` on the input bucket
- Servicelinker SA needs `roles/storage.objectCreator` on the output bucket

---

## Testing in k8s dev

### Verify the pod is running

```bash
kubectl get pods -n servicelinker
kubectl logs -l app=servicelinker -n servicelinker --tail=50
```

Look for the log line `started bean 'inboundChannelAdapter'` to confirm PubSub is connected.

### Health check

```bash
kubectl port-forward svc/servicelinker -n servicelinker 8080:8080
curl http://localhost:8080/actuator/health
```

### Smoke test with the REST API

This bypasses PubSub and GCS entirely — useful for verifying that NeTEx processing and OSRM routing work:

```bash
kubectl port-forward svc/servicelinker -n servicelinker 8080:8080

curl -X POST http://localhost:8080/api/servicelink/process-xml \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/sample-netex.xml \
  -o processed.xml

# Verify ServiceLinks were added
grep -c "ServiceLink" processed.xml
```

### Verifying OSRM connectivity

Before running the full pipeline, verify that each OSRM instance is reachable from the servicelinker pod. Use a coordinate pair in
Norway (Oslo area):

```bash
kubectl exec -it deploy/servicelinker -n servicelinker -- wget -qO- \
  "http://osrm-bus.dev.entur.internal/route/v1/driving/10.75,59.91;11.05,59.96?overview=false"

kubectl exec -it deploy/servicelinker -n servicelinker -- wget -qO- \
  "http://osrm-rail.dev.entur.internal/route/v1/driving/10.75,59.91;11.05,59.96?overview=false"

kubectl exec -it deploy/servicelinker -n servicelinker -- wget -qO- \
  "http://osrm-water.dev.entur.internal/route/v1/driving/10.75,59.91;11.05,59.96?overview=false"
```

A successful response contains `"code":"Ok"` and a non-empty `routes` array.

### End-to-end test via PubSub (dev)

**Step 1: Start monitoring the outbound subscription and app logs.**

Open two terminals:

```bash
# Terminal 1: stream servicelinker logs
kubectl logs -l app=servicelinker -n servicelinker --tail=0 -f
```

Terminal 2 is used in step 6 to pull the status message after processing completes.

**Step 2: Upload a test NeTEx file to the input bucket.**

```bash
gsutil cp src/test/resources/sample-netex.xml \
  gs://marduk-exchange-dev/test/sample-netex.xml
```

Verify the upload:

```bash
gsutil ls -l gs://marduk-exchange-dev/test/sample-netex.xml
```

**Step 3: Publish a message to the inbound topic.**

```bash
gcloud pubsub topics publish ServicelinkerInboundQueue \
  --project=ent-marduk-dev \
  --attribute="RutebankenTargetFileHandle=test/sample-netex.xml,RutebankenCorrelationId=manual-dev-test-001,EnturDatasetReferential=rb_test" \
  --message=""
```

**Step 4: Watch the logs (terminal 1).**

You should see:

```
Received PubSub message: file=test/sample-netex.xml, correlationId=manual-dev-test-001, referential=rb_test
Processing dataset: gs://marduk-exchange-dev/test/sample-netex.xml
Processing complete. Added N ServiceLinks. Publishing status.
Message acknowledged for correlationId=manual-dev-test-001
```

If processing fails, the message is acked after max retries. Check logs for error details.

**Step 5: Verify the processed output in GCS.**

The output file is written to the output bucket at the same object path:

```bash
gsutil ls -l gs://ror-servicelinker-exchange-gcp-dev/test/sample-netex.xml
```

Download and inspect:

```bash
gsutil cp gs://ror-servicelinker-exchange-gcp-dev/test/sample-netex.xml processed.xml
grep -c "ServiceLink" processed.xml
```

**Step 6: Check the status PubSub message (terminal 2).**

In terminal 2, pull from the status subscription:

```bash
gcloud pubsub subscriptions pull ServicelinkerStatusQueue \
  --project=ent-servicelnk-dev \
  --auto-ack --limit=1
```

The output should show a message with these attributes:

| Attribute | Expected value |
|-----------|----------------|
| `RutebankenCorrelationId` | `manual-dev-test-001` |
| `EnturDatasetReferential` | `rb_test` |
| `LinkingStatus` | `SUCCESS` |
| `LinkedNetexFilePath` | `test/sample-netex.xml` |
| `addedServiceLinksCount` | Number of ServiceLinks added |

**Step 7: Clean up test data.**

```bash
gsutil rm gs://marduk-exchange-dev/test/sample-netex.xml
gsutil rm gs://ror-servicelinker-exchange-gcp-dev/test/sample-netex.xml
```

---

## Test Suite

| Test class | What it tests | Docker required |
|------------|---------------|-----------------|
| `NetexProcessorTest` | NeTEx XML parsing, ServiceLink generation, distance filtering | No |
| `OSRMServiceTest` | Per-mode URL routing, caching, WireMock-based OSRM responses | No |
| `ZipHandlingTest` | ZIP archive processing, non-XML preservation, magic-byte detection | No |
| `PolylineEncodingTest` | Polyline encode/decode round-trip accuracy | No |
| `ServicelinkerApplicationTests` | Spring context loads with all beans wired | No |
| `PubSubIntegrationTest` | PubSub emulator topic/subscription creation, message publish/pull | Yes (testcontainers) |

---

## Troubleshooting

**App fails to start with PubSub errors:** If `PUBSUB_EMULATOR_HOST` is not set and no real GCP credentials are available, the PubSub
client will fail to connect. For local dev, either use docker-compose (sets the emulator host) or set
`PUBSUB_EMULATOR_HOST=localhost:8085` with the emulator running.

**OSRM returns null routes:** Check that the OSRM instance for the transport mode is reachable and has loaded the correct network data.
A `null` route means either the OSRM instance is down, timed out, or the coordinates are outside its loaded area. Routes over 100km
are also filtered out.

**ZIP processing produces empty output:** Ensure the input ZIP contains `.xml` files (case-insensitive). Non-XML files are passed
through unchanged. Empty ZIPs produce empty output ZIPs.

**Messages not being processed:** Check that the inbound subscription name matches what the app is configured to listen on. Verify with
`kubectl logs` that the `PubSubInboundChannelAdapter` started successfully. Look for the log line: `started bean 'inboundChannelAdapter'`.
