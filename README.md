# ServiceLink Producer

A Spring Boot application that reads Nordic Profile NeTEx files and adds missing ServiceLinks based on coordinate pairs and transport mode using OSRM routing.

## Features

- Reads NeTEx files from Google Cloud Storage
- Identifies missing ServiceLinks between stop points
- Uses OSRM (Open Source Routing Machine) to calculate routes
- Adds ServiceLinks with stable IDs
- Preserves existing ServiceLinks (no overwrites)
- Supports PubSub triggers from Marduk
- Returns updated file references

## Architecture

- **ServiceLinkProducerService**: Main orchestration service
- **NetexProcessor**: Handles NeTEx XML parsing and ServiceLink injection
- **OSRMService**: Calculates routes between coordinate pairs with caching
- **GcsService**: Manages Google Cloud Storage operations
- **PubSubMessageHandler**: Processes incoming PubSub triggers

## Configuration

Set the following environment variables or application properties:

```properties
# Google Cloud
GCP_PROJECT_ID=your-project-id
GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json

# PubSub
PUBSUB_SUBSCRIPTION=servicelink-trigger
PUBSUB_TOPIC=servicelink-topic

# Google Cloud Storage
GCS_INPUT_BUCKET=netex-files
GCS_OUTPUT_BUCKET=netex-processed

# OSRM
OSRM_BASE_URL=http://router.project-osrm.org
OSRM_TIMEOUT=30000

# ServiceLink configuration
SERVICELINK_ID_PREFIX=SL_
SERVICELINK_CACHE_ROUTES=true
```

## API Endpoints

### Process NeTEx File
```http
POST /api/servicelink/process
Content-Type: application/json

{
  "gcsFilePath": "gs://bucket/path/to/netex-file.xml",
  "outputBucket": "processed-bucket"
}
```

### Cache Management
```http
GET /api/servicelink/cache/stats
POST /api/servicelink/cache/clear
```

### Health Check
```http
GET /api/servicelink/health
```

## Usage

### Manual Processing
```bash
curl -X POST http://localhost:8080/api/servicelink/process \
  -H "Content-Type: application/json" \
  -d '{
    "gcsFilePath": "gs://netex-files/sample.xml",
    "outputBucket": "processed-files"
  }'
```

### PubSub Message Format
```json
{
  "gcsFilePath": "gs://netex-files/sample.xml",
  "outputBucket": "processed-files"
}
```

## ServiceLink Generation

The application:

1. Parses NeTEx files to extract ScheduledStopPoints and StopPlaces
2. Identifies coordinate pairs within reasonable distance (≤50km)
3. Skips pairs that already have ServiceLinks
4. Calculates routes using OSRM with transport mode-specific profiles
5. Generates stable ServiceLink IDs: `{prefix}{fromStopId}_{toStopId}`
6. Adds ServiceLinks to the NeTEx file structure

## Transport Mode Mapping

- BUS, COACH → OSRM driving profile
- TRAM, METRO → OSRM driving profile (customizable)
- RAIL → OSRM driving profile (approximation)
- FERRY → OSRM driving profile (approximation)

## Building and Running

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Build Docker image
./gradlew bootBuildImage
```

## Testing

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests "*NetexProcessorTest*"
```

## Development

The application follows Spring Boot best practices and includes:

- Comprehensive error handling
- Structured logging with correlation IDs
- Route caching for performance
- Configurable OSRM profiles
- Mock-friendly service architecture

## Dependencies

- Spring Boot 4.0.0-M2
- Spring Cloud GCP for Storage and PubSub
- Kotlin with Coroutines
- Jackson for XML/JSON processing
- WebFlux for OSRM HTTP client
- JUnit 5 for testing
