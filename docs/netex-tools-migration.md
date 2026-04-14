# netex-tools migration plan

This document outlines the migration of servicelinker's NeTEx processing to
use [netex-tools](https://github.com/entur/netex-tools), and what changes
netex-tools itself needed to support this use case.

## Current state

Both passes now use netex-tools:

- **Pass 1 (data collection):** netex-tools SAX plugins extract stop points,
  assignments, journey pattern sequences, existing service links, and existing
  SLIJP entries. `Pass1PluginExtractor` (servicelinker) runs all plugins in a
  single `NetexProcessor.buildEntityModel` pass. NSR enrichment and OSRM
  routing happen between passes.

- **Pass 2 (injection):** `NetexProcessor.applyPlan()` uses netex-tools'
  `exportToByteArrays()` with `XMLElementHandler`s registered at
  schema-correct paths. Handlers append to existing sections or emit new
  ones. Existing `linksInSequence` subtrees for JPs with planned
  replacements are dropped via entity selection exclusion +
  `elementsRequiredChildren`; the `JourneyPatternHandler` emits the merged
  replacement. A post-processing step rewrites any `gml:` prefix to `gis:`.

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

## netex-tools API used

The following netex-tools features were contributed for this migration:

### Stream/byte-array API

`NetexProcessor` accepts `Map<String, ByteArray>` at both input and output
stages:

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

### Between-pass hooks

The pipeline is decomposed into `buildEntityModel()`, `selectEntities()`,
and `export*()` as separate public methods. The `model` property is publicly
accessible after `buildEntityModel()` completes, allowing callers to:

- Inspect entity/reference graph between passes
- Run external enrichment (NSR coordinate lookups)
- Compute routing (OSRM) using collected entity data
- Modify selection criteria based on external data

### Plugin endDocument(String)

Plugins receive `endDocument(documentName: String)` in stream mode (vs.
`endDocument(file: File)` in filesystem mode), enabling correct per-document
finalization without filesystem dependencies.

### Scoped element registration

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

### Comment bug fix (0.0.46)

`NetexFileWriter.writeComments` was writing directly to the `StringWriter`,
bypassing the SAX serializer. When a handler was registered on an element,
comments could land inside a buffered start tag, producing malformed XML
like `<element<!-- comment -->>`. Fixed by routing through
`LexicalHandler.comment()` which flushes the pending start tag first.

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

### Phase 2: Pass 2 XMLElementHandlers (injection) — done

`StaxNetexTransformer` has been replaced by netex-tools `XMLElementHandler`
implementations in `no.entur.servicelinker.handlers.InjectionHandlers`:

| Injection operation          | XMLElementHandler approach                     |
|------------------------------|-----------------------------------------------|
| Inject `<serviceLinks>` section | `InsertBeforeHandler` on schema-ordered ServiceFrame children emits the section before forwarding its own start tag |
| Append to existing `<serviceLinks>` | `ServiceLinksHandler` on `serviceLinks` — appends new children in `endElement`, then forwards the close tag |
| Inject stub stops            | Same pattern via `ScheduledStopPointsHandler` / `InsertBeforeHandler` |
| Replace `<linksInSequence>`  | Exclude existing SLIJP entities from the selection + `elementsRequiredChildren` drops the empty wrapper; `JourneyPatternHandler` emits merged replacement in `endElement` |
| Fallback (empty ServiceFrame) | `ServiceFrameHandler` emits any remaining sections before the ServiceFrame close tag |

All handlers share an `InjectionContext` for coordination. The
`InjectionPlan` data model is unchanged from the StAX implementation.

Handlers are registered at both common ServiceFrame path variants
(`CompositeFrame/frames/ServiceFrame` and direct `dataObjects/ServiceFrame`)
— only the one matching the actual document structure fires.

### Phase 3: Integration — done

- Pass 1 runs via `Pass1PluginExtractor` using
  `NetexProcessor.buildEntityModel(documents)` with servicelinker's plugins.
- Pass 2 runs via `NetexProcessor.applyPlan()` which creates a
  `NetexToolsProcessor` with the handler-bearing `FilterConfig`, builds the
  entity model, excludes SLIJP entities for targeted JPs, and exports via
  `exportToByteArrays()`.
- A post-processing step rewrites any `gml:` prefix to the Entur convention
  `gis:`.

## Changes contributed to netex-tools

1. ~~**Stream/byte-array API.**~~ Done in 0.0.41 (`3e249a4`).
2. ~~**Hook between passes.**~~ Done in 0.0.41 (`3e249a4`).
3. ~~**Nested character data in plugins.**~~ Done in 0.0.42 (`fd531ab`)
   via scoped `Ancestor/Element` registration.
4. ~~**Comment bug fix.**~~ Done in 0.0.46. `writeComments` routes
   through `LexicalHandler.comment()` instead of writing directly to
   `StringWriter`.

### Nice-to-have

5. **Ergonomic injection hooks (`beforeEndElement` / `afterStartElement`).**
   Currently proposed on branch `henrik/element-injection-hooks` (netex-tools
   `b7ed529`). Not a capability gap — sibling injection already works via the
   three existing hooks — but the split avoids the "remember to forward the
   original event" footgun and removes the need for handlers to interleave
   their own writes with pass-through of the triggering event.

6. **Event buffering API.** netex-tools has `EventBuffer` and
   `ParentsWithRequiredChildrenDeferEventsRule` for deferring events. Exposing
   a public "buffer-and-replace" pattern would simplify the `linksInSequence`
   merge so servicelinker doesn't reimplement event buffering inside a handler.

7. **Schema-ordered insertion helper.** A shared NeTEx-schema-aware
   "insert-at-position" utility would benefit all netex-tools consumers and
   remove hand-coded sibling-ordering logic from servicelinker's handlers.

## Design decisions and known limitations

- **Handler path registration.** `customElementHandlers` keys are matched
  against `Element.path()` — the full ancestor chain from the document root.
  Servicelinker registers handlers at two known prefixes (CompositeFrame and
  direct ServiceFrame). If a provider submits data with a different nesting
  (e.g. GeneralFrame), handlers would not fire and the ServiceFrame fallback
  would emit sections at the end — wrong schema order but still valid XML.

- **`elementsRequiredChildren` matches by element name, not path.** The
  config `"linksInSequence" -> listOf("ServiceLinkInJourneyPattern")` applies
  globally, including `linksInSequence` inside `Route` elements (which use
  `RouteLinkInSequence`). Norwegian NeTEx data does not use `linksInSequence`
  inside Routes, so this is acceptable but documented.

- **GML prefix rewriting.** The SAX pipeline passes through whatever prefix
  the input uses. A single-pass regex rewrites any `gml:` to `gis:` after
  export. This is a string-level operation, not namespace-aware.

- **Performance.** The handler-based approach requires two SAX passes per
  document (buildEntityModel + export) vs the former single-pass StAX
  transformer. Both are streaming with flat memory. For most files (< 5MB)
  the difference is negligible.

- **netex-tools is at 0.0.x.** API stability is not guaranteed. Breaking
  changes in the plugin or handler interfaces would require servicelinker
  updates. Since both projects are within Entur/ROR, this is manageable.

## Timeline

1. ~~Contribute stream/byte-array API and between-pass hooks to netex-tools.~~
   Done in 0.0.41.
2. ~~Contribute nested character data support to netex-tools plugins.~~
   Done in 0.0.42 (`fd531ab`).
3. ~~Implement Pass 1 plugins and validate via DOM parity.~~ Done.
4. ~~Remove DOM extraction code.~~ Done.
5. ~~Migrate Pass 2 to `XMLElementHandler`s and remove `StaxNetexTransformer`.~~
   Done. Requires netex-tools 0.0.46 (comment bug fix).
