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

Replace `StaxNetexTransformer` with netex-tools' write-pass handlers. Sibling
injection is already expressible with the three current hooks
(`startElement` / `characters` / `endElement`) by controlling the order of
`writer` calls inside a callback — the same pattern ashur's `CodespacesHandler`
uses to append `<FrameDefaults>` after `</codespaces>`:

```kotlin
override fun endElement(...) {
    writer.endElement(uri, localName, qName)  // close original
    writer.startElement(...)                  // emit new sibling
    ...
}
```

Equivalently, a handler registered on the element that should come *after*
the injection can emit the new element before forwarding its own
`startElement` call ("before-start" behavior).

| Injection operation          | XMLElementHandler approach                     |
|------------------------------|-----------------------------------------------|
| Inject `<serviceLinks>` section | Handler on the element immediately preceding the insertion point (schema-ordered cascade: after `<scheduledStopPoints>` if present, else before `<journeyPatterns>`); emits section inside its `endElement` / `startElement` callback |
| Append to existing `<serviceLinks>` | Handler on `serviceLinks` — emits new children, then forwards the `</serviceLinks>` close tag |
| Inject stub stops            | Same pattern, for `<scheduledStopPoints>` |
| Replace `<linksInSequence>`  | Exclude existing SLIJP entities from the selection + `elementsRequiredChildren` drops the empty wrapper; handler on `JourneyPattern` emits merged replacement in `endElement` |

The `InjectionPlan` data model can be reused as the communication layer
between Pass 1 and Pass 2 regardless of framework.

### Phase 3: Integration

- ~~Unzip to `Map<String, ByteArray>` and run Pass 1 via
  `NetexProcessor.buildEntityModel(documents)` with servicelinker's plugins.~~
  Done.
- ~~Remove DOM extraction code once plugins are validated.~~ Done.
- Pass 2 still uses `StaxNetexTransformer` for injection. Migrating it to
  `NetexProcessor.exportToByteArrays()` with `XMLElementHandler`s is not
  blocked on any new netex-tools API; the remaining items below are
  ergonomic improvements.

## Remaining changes needed in netex-tools

### Done

1. ~~**Stream/byte-array API.**~~ Done (`3e249a4`).
2. ~~**Hook between passes.**~~ Done (`3e249a4`).
3. ~~**Nested character data in plugins.**~~ Done (`fd531ab`) via scoped
   `Ancestor/Element` registration.

### Not blocking

4. **Per-file context in `XMLElementHandler`.** Servicelinker's ZIP flow
   already processes one document at a time (`ServiceLinkProducerService`
   lines 164-198), building a per-document `InjectionPlan` and applying it
   individually. Fresh handler instances are constructed with the right
   plan per document — no need for handlers to discover which file they're
   in at runtime.

5. **Full-path handler keys.** `customElementHandlers` keys are matched
   against `Element.path()` — the full ancestor chain from the document
   root (e.g. `/PublicationDelivery/dataObjects/CompositeFrame/frames/
   ServiceFrame/serviceLinks`). NeTEx files vary in structure, but
   `buildEntityModel` runs first, so servicelinker can discover the actual
   `ServiceFrame` path from the entity model and construct handler
   registrations using the correct prefix. No API change needed.

6. **Subtree skipping for `linksInSequence`.** Solved by combining entity
   selection with `elementsRequiredChildren`. For JPs with planned
   replacements, remove their existing `ServiceLinkInJourneyPattern`
   entities from the entity selection after `selectEntities()`. Children
   of excluded entities are automatically excluded via ancestor-checking
   in `InclusionPolicy`. Configure `elementsRequiredChildren` with
   `"linksInSequence" -> listOf("ServiceLinkInJourneyPattern")` so the
   empty `linksInSequence` wrapper is dropped by the deferred-events
   rule. The `JourneyPatternHandler` then emits the merged replacement
   in `endElement`. JPs not in the plan keep their original
   `linksInSequence` intact — their SLIJP entities are still in the
   selection. Works with today's API.

### Nice-to-have

7. **Ergonomic injection hooks (`beforeEndElement` / `afterStartElement`).**
   Currently proposed on branch `henrik/element-injection-hooks` (netex-tools
   `b7ed529`). Not a capability gap — sibling injection already works via the
   three existing hooks — but the split avoids the "remember to forward the
   original event" footgun and removes the need for handlers to interleave
   their own writes with pass-through of the triggering event. Treat as an
   API-clarity contribution rather than a blocker.

8. **Event buffering API.** netex-tools has `EventBuffer` and
   `ParentsWithRequiredChildrenDeferEventsRule` for deferring events. Exposing
   a public "buffer-and-replace" pattern would simplify the `linksInSequence`
   merge (buffer existing, discard, write replacement) so servicelinker
   doesn't reimplement event buffering inside a handler.

9. **Schema-ordered insertion helper.** The hard part of `<serviceLinks>`
   injection isn't emitting the element — it's choosing *where*: after
   `<scheduledStopPoints>` if it exists, else after an existing
   `<serviceLinks>`, else before `<journeyPatterns>` (the
   `AFTER_SCHEDULED_STOP_POINTS` / `AFTER_SERVICE_LINKS` cascade in
   `StaxNetexTransformer`). A shared NeTEx-schema-aware "insert-at-position"
   utility would benefit all netex-tools consumers and remove hand-coded
   sibling-ordering logic from servicelinker's handlers.

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
5. Migrate Pass 2 to `XMLElementHandler`s. No new netex-tools API needed
   — see items 4-6 above. Nice-to-have items can land incrementally.

   **5a. Implement XMLElementHandler classes in servicelinker.** Three
   handlers, all constructed with the current document's `InjectionPlan`:

   | Handler | Registered path | Behavior |
   |---|---|---|
   | `ServiceLinksHandler` | `…/ServiceFrame/serviceLinks` | `endElement`: append new `ServiceLink` elements, then forward close tag. If section doesn't exist, registered on `ServiceFrame` instead and emits the new section in `endElement` before forwarding the close tag. |
   | `ScheduledStopPointsHandler` | `…/ServiceFrame/scheduledStopPoints` | `endElement`: append stub `ScheduledStopPoint` elements, then forward close tag. Same `ServiceFrame`-level fallback. |
   | `JourneyPatternHandler` | `…/journeyPatterns/JourneyPattern` | `startElement`: read `id` attribute. `endElement`: if JP has planned links, emit merged `<linksInSequence>` before forwarding close tag. |

   Handler paths are full paths discovered during `buildEntityModel` (the
   entity model records which paths exist). The `linksInSequence`
   replacement uses entity selection + `elementsRequiredChildren` (see
   item 6): exclude existing SLIJP entities for targeted JPs from the
   selection so the subtree is dropped, then have the
   `JourneyPatternHandler` emit the merged replacement.

   For schema-ordered "insert new section" (no existing `<serviceLinks>`
   or `<scheduledStopPoints>` in the file), the `ServiceFrame`-level
   fallback handler emits new sections before the close tag, after all
   existing children have passed through.

   **5b. Wire up handlers in `NetexProcessor.applyPlan()`.**
   Replace the `StaxNetexTransformer.transform()` call with:
   ```
   1. buildEntityModel(mapOf(name to bytes))
      — registers all entities so the inclusion policy keeps everything
   2. selectEntities()
      — returns all entities with default config
   3. Remove SLIJP entities for JPs with planned replacements from
      the entity selection
   4. Build FilterConfig with customElementHandlers from InjectionPlan
      and elementsRequiredChildren for linksInSequence
   5. exportToByteArrays(mapOf(name to bytes), entitySelection, refSelection)
   ```
   Validate with the existing `StaxNetexTransformerTest` suite — the
   tests verify output structure and content, not implementation details.

   **5c. Remove `StaxNetexTransformer`.**
   Once all tests pass against the handler-based implementation, delete
   `StaxNetexTransformer.kt` and its test class.

Each phase can be done independently and rolled back without affecting the other.
