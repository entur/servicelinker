# netex-tools migration plan

This document outlines a plan for migrating servicelinker's NeTEx processing to
use [netex-tools](https://github.com/entur/netex-tools), and what changes
netex-tools itself would need to support this use case.

## Current state

Servicelinker uses a hybrid approach:

- **Pass 1 (data collection):** DOM-based parsing extracts stop points,
  assignments, journey pattern sequences, existing service links, and existing
  SLIJP entries. NSR enrichment and OSRM routing happen between passes.

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

## Migration approach

### Phase 1: Pass 1 plugins (data collection)

Replace DOM-based extraction in `NetexProcessor` with netex-tools plugins:

| Current method                        | netex-tools replacement                         |
|---------------------------------------|------------------------------------------------|
| `extractStopPoints()`                 | `NetexPlugin` for `ScheduledStopPoint`         |
| `extractStopAssignments()`            | `NetexPlugin` for `PassengerStopAssignment`    |
| `extractExistingServiceLinks()`       | `NetexPlugin` for `ServiceLink`                |
| `collectJpStopSequences()`            | `NetexPlugin` for `JourneyPattern`             |
| `collectExistingSlijpEntries()`       | `NetexPlugin` for `ServiceLinkInJourneyPattern`|
| `buildJpModeMap()`                    | `NetexPlugin` for `Line`, `Route`              |
| `extractScheduledStopPointIdsAndVersions()` | Available from `EntityModel`             |

Each plugin would implement `NetexPlugin`, register its supported element types,
and collect data in `startElement`/`characters`/`endElement` callbacks.

**Prerequisite:** netex-tools needs to provide character-data callbacks for
nested elements (e.g., `Location/Longitude` inside `ScheduledStopPoint`).
Currently plugins only see registered top-level elements.

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

- Replace ZIP handling with netex-tools' file-based processing (requires
  unzip-to-tempdir adapter) or contribute stream-based API to netex-tools.
- Replace `ServiceLinkProducerService` orchestration with netex-tools'
  `FilterNetexApp` or its lower-level components.
- Remove custom StAX transformer and DOM extraction code.

## Changes needed in netex-tools

### Must-have

1. **Stream/byte-array API.** netex-tools currently operates on
   `File(inputDir)` → `File(outputDir)`. Servicelinker works entirely
   in-memory (GCS → byte arrays → ZIP streams). Either:
   - Add `InputStream`/`OutputStream` overloads to `FilterNetexApp`, or
   - Add a `ByteArrayNetexProcessor` adapter that wraps the existing API.

2. **Hook between passes.** `FilterNetexApp.run()` goes straight from Pass 1
   to entity selection to Pass 2. Servicelinker needs to run external enrichment
   (NSR coordinate lookups) and OSRM routing between passes. Options:
   - Expose `buildEntityModel()` and `exportXmlFiles()` as separate public methods.
   - Add a callback/hook interface invoked between passes.

3. **XMLElementHandler for element injection.** Current handlers can modify or
   skip existing elements. Servicelinker needs to *inject new sibling elements*
   that don't exist in the source (e.g., create a `<serviceLinks>` section).
   This requires:
   - Handlers that can emit elements at specific points relative to other elements.
   - A "before-element" or "after-element" hook pattern, not just
     startElement/endElement on existing elements.

4. **Nested character data in plugins.** Plugins currently get callbacks for
   registered element types. Extracting coordinates requires reading
   `ScheduledStopPoint > Location > Longitude` — a nested path. The plugin
   needs either:
   - Character data forwarded for all descendants of registered elements, or
   - A richer path-based registration (e.g., `ScheduledStopPoint/Location/Longitude`).

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

- **SAX complexity for injection.** The current `StaxNetexTransformer` is ~250
  lines of focused streaming code. Rewriting it as netex-tools handlers would
  likely be similar in volume but spread across multiple handler classes. The
  win is not code reduction but framework consistency.

- **Performance parity.** netex-tools' SAX pipeline should match or exceed the
  current StAX transformer's performance, since both are streaming. Verify with
  the 69MB hafas_FLY1.xml benchmark.

## Suggested timeline

1. Contribute stream/byte-array API and between-pass hooks to netex-tools.
2. Implement Pass 1 plugins in a feature branch. Compare extraction results
   with the current DOM approach to validate correctness.
3. Implement Pass 2 handlers. Use the existing test suite to verify output
   equivalence.
4. Once validated, remove DOM extraction and StAX transformer code.

Each phase can be done independently and rolled back without affecting the other.
