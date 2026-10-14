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
- `docs/` - development docs
- Helm chart, Terraform, deploy workflows and the operations guide are in the internal
  `entur/servicelinker-deployment-config`

## Key Conventions

- NeTEx files must always follow the Nordic NeTEx profile
- ServiceLink geometry: always request `overview=full` from OSRM and simplify with Ramer-Douglas-Peucker
  at `max(1 m, route length / 20000)`. OSRM's own `overview=simplified` targets a near-constant point
  count, so a 485 km leg came back as 25 points up to 2.7 km off the road. Simplify after snapping the
  endpoints to the stop coordinates, never before, or the tolerance no longer bounds what is emitted.
- Spring profiles activated by `COMMON_ENV` env var: `dev`, `tst`, `prd`
- PubSub topics/subscriptions are in Marduk's GCP project (`ent-marduk-*`), subscriptions in Servicelinker's project (`ent-servicelnk-*`)
- Outbound topic config must use full path (`projects/ent-marduk-{env}/topics/...`) for cross-project publishing
- CI: `.github/workflows/build.yml` lints, builds and tests; a push to `main` dispatches the deploy in
  `entur/servicelinker-deployment-config`
- Always remember to update tests and documentation

## Debugging Incorrect ServiceLink Routes

Wrong geometry (e.g. a massive detour) is almost always in the OSRM routing layer or its OSM data, not in servicelinker
code. Compare the route on public OSRM (`https://router.project-osrm.org/route/v1/driving/{lon1},{lat1};{lon2},{lat2}`)
with the internal instance. The full playbook is in `entur/servicelinker-deployment-config` `docs/operations.md`.
