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

- NeTEx files must always follow the Nordic NeTEx profile
- Spring profiles activated by `COMMON_ENV` env var: `dev`, `tst`, `prd`
- PubSub topics/subscriptions are in Marduk's GCP project (`ent-marduk-*`), subscriptions in Servicelinker's project (`ent-servicelnk-*`)
- Outbound topic config must use full path (`projects/ent-marduk-{env}/topics/...`) for cross-project publishing
- CI: GitHub Actions for build/deploy (`.github/workflows/build.yml`) and Terraform (`.github/workflows/terraform.yml`)
- Always remember to update tests and documentation

## Debugging Incorrect ServiceLink Routes

When a ServiceLink has wrong geometry (e.g. massive detour instead of direct route), the
issue is almost always in the OSRM routing layer, not in servicelinker code. Follow this
playbook to diagnose.

### 1. Identify the problem ServiceLink

Find the ServiceLink in `_*_shared_data.xml` (where ServiceLinks are stored). Check:
- The `Distance` element — is it wildly larger than expected?
- The first/last coordinate pairs in `gis:posList` — these are the from/to coordinates sent to OSRM
- Compare with neighboring ServiceLinks (previous/next stop pairs) — are those reasonable?

If the coordinates look correct but the distance is wrong, the problem is OSRM routing.

### 2. Test the route on public OSRM vs internal OSRM

```bash
# Public OSRM (car profile, uses latest OSM data)
curl -s "https://router.project-osrm.org/route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=false&steps=true" | python3 -m json.tool

# Internal osrm-bus (port-forward first)
kubectl --context prd -n osrm port-forward svc/osrm-bus 5099:80
curl -s "http://localhost:5099/route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=false&steps=true" | python3 -m json.tool
```

If public OSRM routes correctly but internal doesn't, the problem is in the bus profile
or its OSM data. If both fail, the OSM data itself is likely wrong.

### 3. Binary search for the graph break

When OSRM detours, there's usually a specific point where the road graph is disconnected.
Test intermediate points along the expected route to find where connectivity breaks:

```bash
# Test short segments along the expected route
curl -s "http://localhost:5099/route/v1/driving/{lonA},{latA};{lonB},{latB}?overview=false"
```

Halve the distance until you find two adjacent points where the route suddenly jumps from
reasonable (~hundreds of meters) to absurd (~hundreds of km). That's where the graph break is.

### 4. Check the nearest road at the break point

```bash
curl -s "http://localhost:5099/nearest/v1/driving/{lon},{lat}?number=5" | python3 -m json.tool
```

Look for:
- Road names changing unexpectedly
- `nodes` arrays with `0` values (indicates dead-end/disconnected segments)
- `nodes` arrays with repeated values like `[1234, 1234]` (single-node segments)

### 5. Check the OSM data at the break point

Once you've identified the approximate location, look at the OSM ways:

```bash
# Find the OSM node from the nearest query, then check what ways use it
curl -s "https://www.openstreetmap.org/api/0.6/node/{nodeId}/ways"

# Check tags on the suspicious way
curl -s "https://www.openstreetmap.org/api/0.6/way/{wayId}"
```

Common causes of routing breaks in the bus profile:
- **`access=no`** without a `psv=yes`/`bus=yes` override — the bus profile respects
  access restrictions more strictly than the car profile
- **`construction=yes`** or `highway=construction` — marks road as under construction
- **`oneway=yes`** in unexpected direction — blocks one direction
- **Missing way** — road segment not yet added to OSM
- **Height/weight restrictions** — `maxheight`, `maxweight` tags blocking buses

### 6. OSRM architecture reference

OSRM instances live in k8s namespace `osrm` (context: `prd`/`tst`/`dev`):

| Service | Profile | Transport modes |
|---------|---------|-----------------|
| `osrm-bus` | `bus.lua` | BUS, COACH |
| `osrm-rail` | `rail.lua` | RAIL, TRAM, METRO |
| `osrm-water` | `ferry.lua` | WATER, FERRY |

Data pipeline (automated):
1. `osrm-monitor-osm-data` cronjob checks `gs://ror-osmdata-prd/osm-data/merged-latest.osm.pbf` every 15 min for MD5 changes
2. On change, triggers per-profile rebuild jobs: download PBF → `osrm-extract -p /opt/{profile}.lua` → `osrm-contract` → upload to `gs://ror-osrm-internal-prd/v6/osrm-{profile}/`
3. Redeploys the main deployment (init container downloads fresh data from GCS)

Profile code is in https://github.com/entur/osrm-backend-docker under `profiles/`.
The bus profile (`bus.lua`) key access logic:
- Access tag hierarchy: `psv` → `busway` → `bus` → `access`
- Blacklisted access values: `no`, `private`, `agricultural`, `forestry`, `emergency`, `delivery`
- A way with `access=no` is blocked unless it also has `psv=yes`, `bus=yes`, or `busway=yes`

### 7. Resolution

- **OSM data issue**: Fix in OSM, wait for data pipeline to pick up the change (merging
  + monitor cronjob). After OSM is updated, the `merged-latest.osm.pbf` needs to be
  regenerated and the OSRM rebuild triggered. Verify with the routing test above.
- **Profile issue**: Fix in `profiles/bus.lua` in the osrm-backend-docker repo, build
  new image, and trigger rebuild.
- **Workaround**: Servicelinker has no per-link override mechanism. The only short-term
  workaround is to manually fix the NeTEx output after processing.
