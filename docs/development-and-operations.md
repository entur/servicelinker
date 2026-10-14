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
| `nsr.max-concurrent-requests` | `4` | Concurrent NSR quay lookups, **per pod, process-wide** (not per dataset) |
| `nsr.max-retries` | `6` | Attempts per quay before the lookup is recorded as a transient failure |
| `servicelinker.retry.throttled-delay-ms` | `30000` | Base backoff before re-running a dataset that failed because NSR throttled us |

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

`.github/workflows/build.yml` lints, builds and tests every push and PR. PRs also build and scan the Docker image.
A push to `main` dispatches the deploy in the internal `entur/servicelinker-deployment-config`, which holds the Helm
chart, Terraform and the operations guide.

---

## Test Suite

| Test class | What it tests | Docker required |
|------------|---------------|-----------------|
| `NetexProcessorTest` | NeTEx XML parsing, ServiceLink generation, distance filtering | No |
| `OSRMServiceTest` | Per-mode URL routing (incl. WATER never hitting the ferry instance), caching, WireMock-based OSRM responses | No |
| `NsrServiceTest` | Quay lookup, caching, retry/backoff, transient-vs-definitive failure classification | No |
| `PubSubMessageHandlerTest` | Status message contract, retry rules (throttled retried, deterministic not) | No |
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
A `null` route means either the OSRM instance is down, timed out, or the coordinates are outside its loaded area. Routes longer than
`servicelinker.max-route-distance-meters` (default 5 000 km) are also rejected as implausible.

Every straight-line fallback is counted in `servicelinker_servicelink_straight_line_total{mode,reason,codespace}`, and all but
`reason=mode-filtered` (DEBUG only) are logged at WARN with the mode. The instance URL appears on the `OSRMService` warning for the
underlying HTTP failure, not on the fallback line itself, so match the two up by stop ids.

On a 4xx the OSRM response body is logged too, because that is where the useful part is: `NoSegment` means a coordinate is off the
loaded network (wrong graph, or a bad quay coordinate), `NoRoute` means the graph is disconnected between the two points. A burst of
`NoSegment` for one mode usually means that mode's instance is serving the wrong graph -- check that the k8s Service endpoints point
at the pods you expect:

```bash
kubectl -n osrm get svc,endpoints
```

`NoRoute` on a WATER or FERRY leg usually is not a wiring problem: there is one shared ferry-profile graph and it only contains mapped
OSM ferry ways.

**Dataset FAILED with `IncompleteJourneyPatternException`:** Consecutive stop pairs in a JourneyPattern had no ServiceLink, which is
almost always unresolved stop coordinates. The error message separates the two causes:

- *Quays absent from NSR* -- the operator's data references stops that don't exist. Deterministic, not retried; the operator has to fix it.
- *Transient NSR failures (429/408/5xx/401/403/timeout)* -- the message says `this run is retryable` and the dataset is retried
  automatically with a 30s/60s backoff. Watch `servicelinker_nsr_throttled_total` and `servicelinker_nsr_lookup_failed_total{reason}`.
  `reason=auth` means NSR rejected us outright (quota exhausted, or the `entur-servicelinker` ET-Client-Name is no longer registered) --
  no amount of retrying fixes that one.

Sustained 429s mean our own burst is the problem: marduk starts several codespaces within seconds, and `nsr.max-concurrent-requests`
is per pod, so the cluster-wide ceiling is that times the replica count. Lower it (`NSR_MAX_CONCURRENT_REQUESTS`), stagger the
codespace kickoff in marduk, or ask for a quota bump on the `entur-servicelinker` ET-Client-Name.

**Metrics:** exposed on `/actuator/prometheus`, all tagged with `codespace`:

| Metric | Meaning |
|--------|---------|
| `servicelinker_nsr_throttled_total` | NSR answered 429 |
| `servicelinker_nsr_lookup_failed_total{reason}` | Quay unresolved: `absent`, `exhausted`, `deadline`, `pool`, `auth`, `parse`, `aborted` |
| `servicelinker_servicelink_straight_line_total{mode,reason}` | ServiceLink emitted as a straight line instead of a routed geometry |
| `servicelinker_dataset_outcome_total{status}` | Terminal, one per dataset: `success`, `failed` |
| `servicelinker_dataset_retry_total` | A processing attempt that will be retried (kept out of the outcome metric so it stays a partition) |

**ZIP processing produces empty output:** Ensure the input ZIP contains `.xml` files (case-insensitive). Non-XML files are passed
through unchanged. Empty ZIPs produce empty output ZIPs.

**Messages not being processed:** Check that the inbound subscription name matches what the app is configured to listen on. Verify with
`kubectl logs` that the `PubSubInboundChannelAdapter` started successfully. Look for the log line: `started bean 'inboundChannelAdapter'`.
