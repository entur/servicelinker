# Servicelinker: Development, Testing & Operations

## Overview

Servicelinker enriches Nordic profile NeTEx files with ServiceLink elements (route geometries and distances) using OSRM routing. It sits in the marduk pipeline after ashur (NeTEx filtering/normalization) and before post-validation.

**Pipeline flow:** marduk -> ashur -> **servicelinker** -> post-validation

Input and output are NeTEx datasets (ZIP archives or single XML files) stored in GCS buckets. Pipeline orchestration happens via PubSub messages.

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
| Inbound topic | `servicelinker-inbound` |
| Outbound topic | `servicelinker-outbound` |
| Inbound subscription | `servicelinker-inbound-sub` |
| Outbound subscription | `servicelinker-outbound-sub` |
| Input bucket | `marduk-exchange` |
| Output bucket | `servicelinker-exchange` |

### Running the app directly (outside Docker)

For faster iteration you can run the app directly with emulators running in Docker:

```bash
# Start only the emulators (init creates topics, subscriptions, and buckets)
docker-compose up pubsub-emulator fake-gcs emulator-init

# Run the app pointing at the emulators
SPRING_CLOUD_GCP_PUBSUB_EMULATOR_HOST=localhost:8085 \
STORAGE_EMULATOR_HOST=http://localhost:4443 \
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
curl -s "http://localhost:4443/storage/v1/b/marduk-exchange/o" | python3 -m json.tool
```

**Step 3: Publish a message to the inbound topic.**

This triggers servicelinker to download the file from GCS, process it, upload the result, and publish a completion message:

```bash
curl -s -X POST \
  "http://localhost:8085/v1/projects/local-project/topics/servicelinker-inbound:publish" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [{
      "attributes": {
        "objectName": "test/sample-netex.xml",
        "bucketName": "marduk-exchange",
        "correlationId": "manual-test-001"
      },
      "data": ""
    }]
  }'
```

**Step 4: Watch the docker-compose logs.**

In the docker-compose terminal you should see log lines like:

```
Received PubSub message: bucket=marduk-exchange, object=test/sample-netex.xml, correlationId=manual-test-001
Processing dataset: gs://marduk-exchange/test/sample-netex.xml
Processing complete. Added N ServiceLinks. Publishing to outbound topic.
Message acknowledged for correlationId=manual-test-001
```

**Step 5: Verify the processed output in GCS.**

The processed file is written to the output bucket at the same object path:

```bash
curl -s "http://localhost:4443/storage/v1/b/servicelinker-exchange/o" | python3 -m json.tool
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

**Step 6: Check the outbound PubSub message.**

The monitoring script from step 1 should have printed the completion message with these attributes:

| Attribute | Expected value |
|-----------|----------------|
| `objectName` | `test/sample-netex.xml` |
| `bucketName` | `servicelinker-exchange` |
| `correlationId` | `manual-test-001` |
| `addedServiceLinksCount` | Number of ServiceLinks added |

If you didn't start the monitoring script, you can pull manually:

```bash
curl -s -X POST \
  "http://localhost:8085/v1/projects/local-project/subscriptions/servicelinker-outbound-sub:pull" \
  -H "Content-Type: application/json" \
  -d '{"maxMessages": 10}' | python3 -m json.tool
```

### REST API (for manual processing/debugging)

These endpoints are always available regardless of PubSub:

```bash
# Process raw XML directly (returns processed XML)
curl -X POST http://localhost:8080/api/servicelink/process-xml \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/sample-netex.xml \
  -o processed.xml

# Cache stats
curl http://localhost:8080/api/servicelink/cache/stats

# Clear route cache
curl -X POST http://localhost:8080/api/servicelink/cache/clear

# Health check
curl http://localhost:8080/actuator/health
```

---

## Configuration

Configuration uses Spring profiles activated by the `COMMON_ENV` environment variable set by k8s. When `COMMON_ENV` is not set (local development), no profile is active and the defaults in `application.properties` apply.

### Profiles

| `COMMON_ENV` | Profile file | GCP project | OSRM | Logging |
|--------------|-------------|-------------|------|---------|
| (unset) | `application.properties` | `local-project` | Public OSRM | DEBUG |
| `dev` | `application-dev.properties` | `ent-servicelinker-dev` | Internal OSRM | DEBUG |
| `tst` | `application-tst.properties` | `ent-servicelinker-tst` | Internal OSRM | DEBUG |
| `prd` | `application-prd.properties` | `ent-servicelinker-prd` | Internal OSRM | INFO |

### Shared defaults (application.properties)

| Property | Default | Description |
|----------|---------|-------------|
| `servicelinker.pubsub.inbound.subscription` | `servicelinker-inbound-sub` | PubSub subscription to listen on |
| `servicelinker.pubsub.outbound.topic` | `servicelinker-outbound` | PubSub topic for completion messages |
| `gcs.bucket.input` | `marduk-exchange` | GCS bucket for input datasets |
| `gcs.bucket.output` | `servicelinker-exchange` | GCS bucket for processed output |
| `osrm.timeout` | `30000` | OSRM HTTP timeout in milliseconds |
| `osrm.cache-routes` | `true` | Cache OSRM route results in memory |

### OSRM per-mode routing

Separate OSRM instances per transport mode. Each instance has its own network data but all expose the `/route/v1/driving/` endpoint. The base defaults (public OSRM) are overridden per environment profile with internal service URLs.

| Property | Transport modes |
|----------|-----------------|
| `osrm.urls.bus` | BUS, COACH |
| `osrm.urls.rail` | RAIL, TRAM, METRO |
| `osrm.urls.ferry` | FERRY |
| `osrm.urls.water` | WATER |
| `osrm.urls.default` | Fallback for unknown modes |

### Emulator configuration

For local development (docker-compose or running directly), these environment variables activate emulator mode:

| Variable | Description |
|----------|-------------|
| `SPRING_CLOUD_GCP_PUBSUB_EMULATOR_HOST` | PubSub emulator endpoint (e.g. `localhost:8085`). Routes PubSub traffic to emulator. |
| `STORAGE_EMULATOR_HOST` | GCS emulator endpoint (e.g. `http://localhost:4443`). Also activates NoCredentials mode. |

---

## PubSub Message Format

### Inbound message (from marduk)

The message body is ignored. All routing info is in **message attributes**:

| Attribute | Required | Description |
|-----------|----------|-------------|
| `objectName` | Yes | GCS object name (e.g. `inbound/netex/rb_nsb-20240115.zip`) |
| `bucketName` | No | Override input bucket (defaults to `GCS_INPUT_BUCKET`) |
| `correlationId` | No | Correlation ID for pipeline tracking |

### Outbound message (to marduk)

Published to `PUBSUB_OUTBOUND_TOPIC` on success. Attributes:

| Attribute | Description |
|-----------|-------------|
| `objectName` | Same object name as input (written to output bucket at same path) |
| `bucketName` | The output bucket name |
| `correlationId` | Passed through from inbound message |
| `addedServiceLinksCount` | Number of ServiceLinks added |

On failure the inbound message is nacked for redelivery (no outbound message is published).

---

## Marduk Integration

Servicelinker integrates into the marduk pipeline via PubSub. The changes needed on the marduk side (separate repo/PR):

1. **New Camel route step** between "ashur complete" and "post-validation"
2. **Publish** to `servicelinker-inbound` topic with file reference after ashur completes
3. **Subscribe** to `servicelinker-outbound` topic to continue the pipeline
4. **IAM:** Grant servicelinker's service account read access to marduk's exchange bucket, and grant marduk's service account subscribe access to servicelinker's outbound topic
5. **Nabu:** Register a new pipeline step for status tracking

### Data flow

```
marduk                     servicelinker               marduk
  |                            |                          |
  |-- publish to               |                          |
  |   servicelinker-inbound -->|                          |
  |   (objectName, bucketName, |                          |
  |    correlationId)          |                          |
  |                            |-- download from          |
  |                            |   GCS input bucket       |
  |                            |                          |
  |                            |-- process NeTEx          |
  |                            |   (add ServiceLinks)     |
  |                            |                          |
  |                            |-- upload to              |
  |                            |   GCS output bucket      |
  |                            |                          |
  |                            |-- publish to             |
  |                            |   servicelinker-outbound-|->
  |                            |   (objectName, bucketName,  |
  |                            |    correlationId)           |
  |                                                       |
  |                                                       |
  |<-- subscribe to servicelinker-outbound ---------------|
  |    continue pipeline                                  |
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

Pushes to `main` trigger the GitHub Actions workflow (`.github/workflows/build.yml`):

1. **gradle-build** — builds and tests with `./gradlew build`, uploads JAR artifact
2. **docker-build** — builds Docker image via `entur/gha-docker`
3. **docker-push** — pushes image to registry (skipped on PRs)
4. **scan** — security scan via `entur/gha-security`
5. **deploy** — deploys to dev via Helm (`helm/servicelinker`) using `entur/gha-helm`

Environment-specific config is handled by Spring profiles activated via `COMMON_ENV` (set in the Helm chart values per environment).

### IAM setup

The servicelinker service account needs:
- `roles/pubsub.subscriber` on the inbound subscription
- `roles/pubsub.publisher` on the outbound topic
- `roles/storage.objectViewer` on the input bucket
- `roles/storage.objectCreator` on the output bucket

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

Before running the full pipeline, verify that each OSRM instance is reachable from the servicelinker pod. Use a coordinate pair in Norway (Oslo area):

```bash
kubectl exec -it deploy/servicelinker -n servicelinker -- wget -qO- \
  "http://osrm-bus.dev.entur.internal/route/v1/driving/10.75,59.91;11.05,59.96?overview=false"

kubectl exec -it deploy/servicelinker -n servicelinker -- wget -qO- \
  "http://osrm-rail.dev.entur.internal/route/v1/driving/10.75,59.91;11.05,59.96?overview=false"

kubectl exec -it deploy/servicelinker -n servicelinker -- wget -qO- \
  "http://osrm-ferry.dev.entur.internal/route/v1/driving/10.75,59.91;11.05,59.96?overview=false"

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

```bash
# Terminal 2: poll the outbound subscription for the completion message
gcloud pubsub subscriptions pull servicelinker-outbound-sub \
  --project=YOUR_DEV_PROJECT \
  --auto-ack --limit=1 --wait
```

The `--wait` flag blocks until a message arrives (up to a timeout). If your `gcloud` version doesn't support `--wait`, run the pull command after step 3 instead.

**Step 2: Upload a test NeTEx file to the input bucket.**

```bash
gsutil cp src/test/resources/sample-netex.xml \
  gs://marduk-exchange/test/sample-netex.xml
```

Verify the upload:

```bash
gsutil ls -l gs://marduk-exchange/test/sample-netex.xml
```

**Step 3: Publish a message to the inbound topic.**

```bash
gcloud pubsub topics publish servicelinker-inbound \
  --project=ent-servicelnk-dev \
  --attribute=objectName=test/sample-netex.xml \
  --attribute=bucketName=marduk-exchange \
  --attribute=correlationId=manual-dev-test-001 \
  --message=""
```

**Step 4: Watch the logs (terminal 1).**

You should see:

```
Received PubSub message: bucket=marduk-exchange, object=test/sample-netex.xml, correlationId=manual-dev-test-001
Processing dataset: gs://marduk-exchange/test/sample-netex.xml
Processing complete. Added N ServiceLinks. Publishing to outbound topic.
Message acknowledged for correlationId=manual-dev-test-001
```

If processing fails, the message will be nacked and redelivered. Check logs for error details.

**Step 5: Verify the processed output in GCS.**

The output file is written to the output bucket at the same object path:

```bash
gsutil ls -l gs://servicelinker-exchange/test/sample-netex.xml
```

Download and inspect:

```bash
gsutil cp gs://servicelinker-exchange/test/sample-netex.xml processed.xml
grep -c "ServiceLink" processed.xml
```

**Step 6: Check the outbound PubSub message (terminal 2).**

The `gcloud pubsub subscriptions pull` command should show the completion message with these attributes:

| Attribute | Expected value |
|-----------|----------------|
| `objectName` | `test/sample-netex.xml` |
| `bucketName` | `servicelinker-exchange` |
| `correlationId` | `manual-dev-test-001` |
| `addedServiceLinksCount` | Number of ServiceLinks added |

**Step 7: Clean up test data.**

```bash
gsutil rm gs://marduk-exchange/test/sample-netex.xml
gsutil rm gs://servicelinker-exchange/test/sample-netex.xml
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

**App fails to start with PubSub errors:** If `PUBSUB_EMULATOR_HOST` is not set and no real GCP credentials are available, the PubSub client will fail to connect. For local dev, either use docker-compose (sets the emulator host) or set `PUBSUB_EMULATOR_HOST=localhost:8085` with the emulator running.

**OSRM returns null routes:** Check that the OSRM instance for the transport mode is reachable and has loaded the correct network data. A `null` route means either the OSRM instance is down, timed out, or the coordinates are outside its loaded area. Routes over 50km are also filtered out.

**ZIP processing produces empty output:** Ensure the input ZIP contains `.xml` files (case-insensitive). Non-XML files are passed through unchanged. Empty ZIPs produce empty output ZIPs.

**Messages not being processed:** Check that the inbound subscription name matches what the app is configured to listen on. Verify with `kubectl logs` that the `PubSubInboundChannelAdapter` started successfully. Look for the log line: `started bean 'inboundChannelAdapter'`.
