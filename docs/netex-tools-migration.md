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
| Replace `<linksInSequence>`  | Handler on `JourneyPattern` — buffers and replaces `linksInSequence` child |

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

### Required for Phase 2

4. **Per-file context in `XMLElementHandler`.** For ZIP processing, each
   document in the NeTEx archive carries its own `InjectionPlan`: the set of
   stub stops, new `ServiceLink`s, and `linksInSequence` replacements varies
   per line file. Servicelinker holds a `Map<String, InjectionPlan>` keyed
   by source filename. A handler triggered inside `exportToByteArrays(...)`
   has no way to tell which document it's rewriting, so it can't look up the
   right plan — the three existing callbacks receive only `(uri, localName,
   qName, attributes, writer)`.

   `NetexPlugin` already solves the analogous problem on the read side via
   `endDocument(documentName: String)` (added in `3e249a4`). Mirroring that
   shape on the write side is the minimal change. Three possible surfaces,
   roughly in order of preference:

   1. **Lifecycle hooks on `XMLElementHandler`.** Add
      `startDocument(documentName)` / `endDocument(documentName)` default
      methods so a handler can cache the current document name as
      per-instance state and read it back inside element callbacks. Keeps a
      single handler instance across the batch, matches the plugin model,
      zero churn on `FilterConfig`.
   2. **Handler factory on `FilterConfig`.** Let
      `withCustomElementHandlers(...)` accept a factory
      `(documentName) -> Map<Path, XMLElementHandler>` so each document gets
      fresh handler instances constructed with the right plan baked in.
      Cleaner separation at the cost of handler allocation per file.
   3. **Filename parameter on every callback.** Add `documentName: String`
      to `startElement` / `characters` / `endElement`. Mechanical but breaks
      every existing handler (including ashur's).

   Option 1 is lowest-friction and symmetric with the existing plugin API.
   Without any of these, the only workaround is calling
   `exportToByteArrays` once per document with a per-document
   `FilterConfig`, which defeats batching and complicates the servicelinker
   call site.

### Nice-to-have

5. **Ergonomic injection hooks (`beforeEndElement` / `afterStartElement`).**
   Currently proposed on branch `henrik/element-injection-hooks` (netex-tools
   `b7ed529`). Not a capability gap — sibling injection already works via the
   three existing hooks — but the split avoids the "remember to forward the
   original event" footgun and removes the need for handlers to interleave
   their own writes with pass-through of the triggering event. Treat as an
   API-clarity contribution rather than a blocker.

6. **Event buffering API.** netex-tools has `EventBuffer` and
   `ParentsWithRequiredChildrenDeferEventsRule` for deferring events. Exposing
   a public "buffer-and-replace" pattern would simplify the `linksInSequence`
   merge (buffer existing, discard, write replacement) so servicelinker
   doesn't reimplement event buffering inside a handler.

7. **Schema-ordered insertion helper.** The hard part of `<serviceLinks>`
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
5. Migrate Pass 2 to `XMLElementHandler`s. Depends on per-file context
   (item 4); the other items in "Nice-to-have" can land incrementally
   after the initial cutover.

Each phase can be done independently and rolled back without affecting the other.
