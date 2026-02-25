# ServiceLink Producer

A Spring Boot application that reads Nordic Profile NeTEx files and adds missing ServiceLinks based on coordinate pairs and transport mode using OSRM routing. Part of the marduk pipeline, positioned after antu pre-validation and before ashur filtering.

## Features

- Reads NeTEx datasets (ZIP archives or single XML files) from Google Cloud Storage
- Identifies missing ServiceLinks for consecutive stops within JourneyPatterns
- Adds ServiceLinkInJourneyPattern references (linksInSequence) to each JourneyPattern
- Uses transport mode-specific OSRM instances for accurate routing (bus, rail, ferry, water)
- Adds ServiceLinks with route geometry (GML LineString) and distances
- Preserves existing ServiceLinks (no overwrites)
- Handles ZIP archives: processes all XML files, preserves non-XML entries
- Event-driven via PubSub (inbound trigger, outbound completion)
- REST API for manual processing and debugging

## Architecture

- **PubSubMessageHandler**: Receives PubSub messages, orchestrates processing, publishes completion
- **ServiceLinkProducerService**: Main orchestration: ZIP/XML detection, processing, GCS I/O
- **NetexProcessor**: NeTEx XML parsing, stop point extraction, ServiceLink injection
- **OSRMService**: Per-mode OSRM routing with in-memory caching
- **GcsService**: Google Cloud Storage operations

## Quick Start

```bash
# Run tests
./gradlew test

# Run locally with docker-compose (PubSub emulator + fake GCS + app)
./gradlew bootJar
docker-compose up --build

# Process a NeTEx file via REST API
curl -X POST http://localhost:8080/api/servicelink/process-xml \
  -H "Content-Type: application/xml" \
  --data-binary @src/test/resources/sample-netex.xml

# Visualize ServiceLinks from a processed NeTEx file or ZIP
scripts/view-servicelinks.mjs processed.zip
```

## Configuration

All configuration is via environment variables. Key settings:

| Variable | Default | Description |
|----------|---------|-------------|
| `GCP_PROJECT_ID` | `entur-project` | GCP project ID |
| `PUBSUB_INBOUND_SUBSCRIPTION` | `ServicelinkerInboundQueue` | PubSub subscription to listen on |
| `PUBSUB_OUTBOUND_TOPIC` | `ServicelinkerStatusQueue` | PubSub topic for status messages |
| `GCS_INPUT_BUCKET` | `marduk-exchange` | Input bucket |
| `GCS_OUTPUT_BUCKET` | `servicelinker-exchange` | Output bucket |
| `OSRM_URL_BUS` | `https://router.project-osrm.org` | OSRM instance for BUS/COACH |
| `OSRM_URL_RAIL` | `https://router.project-osrm.org` | OSRM instance for RAIL/TRAM/METRO |
| `OSRM_URL_FERRY` | `https://router.project-osrm.org` | OSRM instance for FERRY |
| `OSRM_URL_WATER` | `https://router.project-osrm.org` | OSRM instance for WATER |
| `OSRM_URL_DEFAULT` | `https://router.project-osrm.org` | Fallback OSRM instance |

See [docs/development-and-operations.md](docs/development-and-operations.md) for full configuration reference, local development setup, marduk integration details, and k8s deployment instructions.

## Transport Mode Routing

| Transport modes | OSRM instance |
|----------------|---------------|
| BUS, COACH | osrm-bus |
| RAIL, TRAM, METRO | osrm-rail |
| FERRY | osrm-ferry |
| WATER | osrm-water |
| Unknown/null | osrm-car (default) |

All OSRM instances expose `/route/v1/driving/` -- the correct routing comes from the network data loaded in each instance.

## Dependencies

- Spring Boot 4.0.2
- Spring Cloud GCP 7.4.3 (Storage, PubSub)
- Kotlin 2.3.0 with Coroutines
- Jackson for XML/JSON processing
- WebFlux for OSRM HTTP client
- Testcontainers for integration tests
- WireMock + mockk for unit tests
