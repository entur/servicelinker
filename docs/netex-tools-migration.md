# netex-tools migration plan

This document outlines a plan for migrating servicelinker's NeTEx processing to
use [netex-tools](https://github.com/entur/netex-tools), and what changes
netex-tools itself would need to support this use case.

## Current state

Servicelinker uses a hybrid approach:

- **Pass 1 (data collection):** netex-tools SAX plugins extract stop points,
  assignments, journey pattern sequences, existing service links, and existing
  SLIJP entries. `Pass1PluginExtractor` (servicelinker) runs all plugins in a
  single `NetexProcessor.buildEntityModel` pass. NSR enrichment and OSRM
  routing happen between passes. The former DOM extraction code was removed.

- **Pass 2 (injection):** A StAX streaming transformer (`StaxNetexTransformer`)
  takes an `InjectionPlan` data object and the original XML bytes, producing
  modified output in a single streaming pass. This avoids materializing a DOM
  tree for large files — the key performance optimization.

## Why migrate to netex-tools

1. **Pipeline consistency.** Ashur already uses netex-tools. Standardizing on
   one NeTEx processing framework reduces cognitive load across the pipeline.

2. **Shared entity/reference model.** netex-tools' `EntityModel` tracks NeTEx
   entities and references automatically. Cross-file reference management
   (stub stop injection, ServiceLink_KeyRef constraints) could leverage this.

3. **Built-in cleanup.** Orphan reference removal, empty collection pruning,
   and self-closing tag normalization come for free.

4. **Future features.** As netex-tools gains more capabilities (validation,
   geographic filtering), servicelinker gets them without custom code.

## netex-tools API available (as of 0.0.42-SNAPSHOT)

The primary prerequisites for this migration have been implemented in
netex-tools (`3e249a4`, `fd531ab`):

### Stream/byte-array API (done)

`NetexProcessor` (renamed from `NetexFilter`) accepts `Map<String, ByteArray>`
at both input and output stages:

```kotlin
val processor = NetexProcessor(cliConfig, filterConfig)

// Pass 1: build entity model from in-memory documents
processor.buildEntityModel(documents: Map<String, ByteArray>)

// Between passes: inspect model, run external enrichment
val entities = processor.model.listAllEntities()
// ... NSR lookups, OSRM routing ...

// Pass 2: select entities and export
val (entitySelection, refSelection) = processor.selectEntities()
val result: ExportResult = processor.exportToByteArrays(documents, entitySelection, refSelection)
// result.documents: Map<String, ByteArray>
// result.report: FilterReport
```

Or as a one-shot convenience: `processor.run(documents): ExportResult`.

### Between-pass hooks (done)

The pipeline is decomposed into `buildEntityModel()`, `selectEntities()`,
and `export*()` as separate public methods. The `model` property is publicly
accessible after `buildEntityModel()` completes, allowing callers to:

- Inspect entity/reference graph between passes
- Run external enrichment (NSR coordinate lookups)
- Compute routing (OSRM) using collected entity data
- Modify selection criteria based on external data

### Plugin endDocument(String) (done)

Plugins receive `endDocument(documentName: String)` in stream mode (vs.
`endDocument(file: File)` in filesystem mode), enabling correct per-document
finalization without filesystem dependencies.

### Scoped element registration (done)

Plugins can declare supported element types using `Ancestor/Element` syntax
in `getSupportedElementTypes()` — the element matches only when it appears
anywhere inside the given ancestor on the element stack. This avoids false
matches on generic names like `Longitude`, `Latitude`, `Date`, or `Name`
that appear in multiple NeTEx contexts.

```kotlin
override fun getSupportedElementTypes() = setOf(
    "ScheduledStopPoint",
    "ScheduledStopPoint/Longitude",  // only inside ScheduledStopPoint
    "ScheduledStopPoint/Latitude",
)
```

Unscoped element types continue to work as before.

## Migration approach

### Phase 1: Pass 1 plugins (data collection) — done

Former DOM extractors have been replaced by five `NetexPlugin` implementations
under `no.entur.servicelinker.plugins`, orchestrated by `Pass1PluginExtractor`:

| Former DOM method                       | Plugin replacement                                        |
|-----------------------------------------|----------------------------------------------------------|
| `extractStopPoints`                     | `StopPointPlugin` (SSP + StopPlace, Centroid/Location-guarded) |
| `extractScheduledStopPointIdsAndVersions` | `StopPointPlugin` (same pass, separate accumulator)    |
| `extractStopAssignments`                | `StopAssignmentPlugin`                                   |
| `extractExistingServiceLinks`           | `ServiceLinkPlugin`                                      |
| `collectJpStopSequences`                | `JourneyPatternPlugin`                                   |
| `collectExistingSlijpEntries`           | `JourneyPatternPlugin`                                   |
| `buildJpModeMap`                        | `JourneyPatternPlugin` + `LineRoutePlugin`, resolved by `Pass1PluginExtractor.resolveJpModeMap` |

Each plugin uses the scoped `Ancestor/Element` registration syntax introduced
in netex-tools 0.0.42 to disambiguate generic element names like `Longitude`,
`Name`, `LineRef`, and `ServiceLinkRef` across NeTEx contexts. The plugins are
fresh instances per `extract()` call so the facade is safe to reuse.

The ZIP flow in `ServiceLinkProducerService` runs the extractor once over all
`.xml` entries via `NetexProcessor.buildEntityModel(Map<String, ByteArray>)` and
reads per-document results off the returned `Pass1Results`.

### Phase 2: Pass 2 XMLElementHandlers (injection)

Replace `StaxNetexTransformer` with netex-tools' write-pass handlers:

| Injection operation          | XMLElementHandler approach                     |
|------------------------------|-----------------------------------------------|
| Inject `<serviceLinks>` section | Handler on `ServiceFrame` — emits section before `</ServiceFrame>`, or handler on first AFTER_SERVICE_LINKS child |
| Append to existing `<serviceLinks>` | Handler on `serviceLinks` — emits new elements before `</serviceLinks>` |
| Inject stub stops            | Same pattern as serviceLinks, for `scheduledStopPoints` |
| Replace `<linksInSequence>`  | Handler on `JourneyPattern` — buffers and replaces `linksInSequence` child |

The `InjectionPlan` data model can be reused as the communication layer
between Pass 1 and Pass 2 regardless of framework.

### Phase 3: Integration

- ~~Unzip to `Map<String, ByteArray>` and run Pass 1 via
  `NetexProcessor.buildEntityModel(documents)` with servicelinker's plugins.~~
  Done.
- ~~Remove DOM extraction code once plugins are validated.~~ Done.
- Pass 2 still uses `StaxNetexTransformer` for injection. Migrating it to
  `NetexProcessor.exportToByteArrays()` with `XMLElementHandler`s depends on
  the element-injection capability called out below.

## Remaining changes needed in netex-tools

### Must-have

1. ~~**Stream/byte-array API.**~~ Done (`3e249a4`).

2. ~~**Hook between passes.**~~ Done (`3e249a4`).

3. ~~**Nested character data in plugins.**~~ Done (`fd531ab`) via scoped
   `Ancestor/Element` registration.

4. **XMLElementHandler for element injection.** Current handlers can modify or
   skip existing elements. Servicelinker needs to *inject new sibling elements*
   that don't exist in the source (e.g., create a `<serviceLinks>` section).
   This requires:
   - Handlers that can emit elements at specific points relative to other elements.
   - A "before-element" or "after-element" hook pattern, not just
     startElement/endElement on existing elements.

### Nice-to-have

5. **Per-file context in XMLElementHandler.** For ZIP processing, different files
   get different injections. Handlers need to know which file is being processed
   to look up the correct `InjectionPlan`. Options:
   - Pass filename to handler callbacks.
   - Allow per-file handler instances via a factory.

6. **Event buffering API.** netex-tools has `EventBuffer` and
   `ParentsWithRequiredChildrenDeferEventsRule` for deferring events. Exposing
   a public "buffer-and-replace" pattern would simplify the linksInSequence
   merge (buffer existing, discard, write replacement).

7. **Element ordering utilities.** Servicelinker maintains NeTEx schema element
   ordering (e.g., `scheduledStopPoints` before `serviceLinks` before
   `journeyPatterns`). A shared utility for NeTEx element ordering in
   netex-tools would benefit all consumers.

## Risks and considerations

- **netex-tools is at 0.0.x.** API stability is not guaranteed. Breaking changes
  in the plugin or handler interfaces would require servicelinker updates. Since
  both projects are within Entur/ROR, this is manageable but should be tracked.
  The recent `NetexFilter` → `NetexProcessor` rename is an example of such churn.

- **SAX complexity for injection.** The current `StaxNetexTransformer` is ~250
  lines of focused streaming code. Rewriting it as netex-tools handlers would
  likely be similar in volume but spread across multiple handler classes. The
  win is not code reduction but framework consistency.

- **Performance parity.** netex-tools' SAX pipeline should match or exceed the
  current StAX transformer's performance, since both are streaming. Verify with
  the 69MB hafas_FLY1.xml benchmark.

- **Hybrid approach viable.** Phase 1 (plugins for extraction) and Phase 2
  (handlers for injection) are independent. It is feasible to adopt netex-tools
  for Pass 1 only and keep `StaxNetexTransformer` for Pass 2 until the
  XMLElementHandler injection capabilities mature.

## Suggested timeline

1. ~~Contribute stream/byte-array API and between-pass hooks to netex-tools.~~
   Done in 0.0.41.
2. ~~Contribute nested character data support to netex-tools plugins.~~
   Done in 0.0.42 (`fd531ab`).
3. ~~Implement Pass 1 plugins and validate via DOM parity.~~ Done.
4. ~~Remove DOM extraction code.~~ Done — `NetexProcessor` now owns routing,
   plan building, and StAX injection only.
5. Evaluate whether XMLElementHandlers can handle injection (item 4). If not,
   keep StaxNetexTransformer for Pass 2.

Each phase can be done independently and rolled back without affecting the other.
