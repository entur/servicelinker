# AGENTS.md

## Entur Standards

Read and follow the Entur platform standards at:
https://github.com/entur/ai/blob/main/AGENTS.md

When working on a specific task, also read the relevant docs
linked from that file (e.g. java.md, helm.md, docker.md).

## Project Overview

Servicelinker enriches NeTEx public transit files with ServiceLink elements (route geometries/distances) using OSRM routing. It sits in the marduk pipeline: antu pre-validation -> **servicelinker** -> ashur filtering -> antu post-validation.

Kotlin/Spring Boot 4 app on GCP. Input/output via PubSub messages and GCS buckets.

## Build & Test

```bash
./gradlew build          # build + all tests
./gradlew test           # tests only (integration tests need Docker)
./gradlew bootJar        # fat JAR
docker-compose up --build # full local stack with emulators
```

## Project Structure

- `src/main/kotlin/no/entur/servicelinker/` - application code (config, controller, model, service, util)
- `src/test/` - JUnit 5 tests with MockK, WireMock, Testcontainers
- `helm/servicelinker/` - Helm chart (depends on entur/common)
- `terraform/` - GCP infrastructure (PubSub, GCS, IAM)
- `docs/` - integration guide and operations docs

## Key Conventions

- Spring profiles activated by `COMMON_ENV` env var: `dev`, `tst`, `prd`
- PubSub topics/subscriptions are in Marduk's GCP project (`ent-marduk-*`), subscriptions in Servicelinker's project (`ent-servicelnk-*`)
- Outbound topic config must use full path (`projects/ent-marduk-{env}/topics/...`) for cross-project publishing
- CI: GitHub Actions for build/deploy (`.github/workflows/build.yml`) and Terraform (`.github/workflows/terraform.yml`)
