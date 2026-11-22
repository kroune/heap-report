# shapes — exact structural collapse

Implementation of `backend/mat/SHAPES.md`, in Kotlin. The analyzer operates on
a compact graph behind the `AnalysisGraph` interface; real dumps are read by
`hprof-analyze`, a standalone parallel HPROF reader. On the
15.6 GB / 260.8M-object `run-jfr-38` dump, the measured local run completed in
**350.8 s end-to-end at Xmx 2g**, with byte-identical output to the original
implementation (measurement details below).

Spec section references below are to SHAPES.md.

## What is implemented

- **Definitions** — object graph of live non-class objects + outbound
  references. The backend's heap-wide mode selects every live non-class object
  and intentionally has no class-root counts; `--class X` selects the
  reachable closure of a class's instances for diagnostics.
- **Equality rules** — leaf / acyclic / SCC keys exactly as specified.
  Multi-edges preserved (`Object[]{x,x}` ≠ `Object[]{x,y}` when x,y differ
  structurally). SCC: color refinement over `(class, internal-neighbor
  colors, child-shape colors)` and merge on the WL-fixpoint signature.
  Self-loops take the same SCC path. Large components intern lazily by an
  isomorphism-invariant signature (below). **shallow is NOT part of the key**.
- **Hashing discipline** — cons-map key is the full structural tuple
  (`LeafLk`/`NodeLk`/`SccLk`), equality is exact via interned child shape
  identity, never via the digest. Separate 128-bit cross-dump digest over a
  canonical binary encoding (class by name, children as sorted 128-bit
  digests) — XXH3-128 with a fixed zero seed, streamed through hash4j's
  `HashStream128` (little-endian primitives, length-delimited UTF-8), verified
  against `xxhsum`.
- **Pipeline** — reach → iterative Tarjan (components come out in reverse
  topo order, so bottom-up cons = plain component-id order) → cons →
  aggregates. Shape ids assigned post-merge by digest sort → deterministic.
- **Aggregates** — `instanceCount`, `shallowTotal/Min/Max`, `rootCount`,
  complete edge table `(shapeId, childShapeId, multiplicity)`. Edge
  multiplicity is **per member** of the source shape (expanded totals =
  mult × instanceCount). Non-uniform merged SCCs emit per-collapsed-instance
  totals instead (`edgesPerMember = false`).
- **Phase 5 emit** — binary artifact (`SHP1` magic plus `SCC1` member-graph
  section; layout documented in `artifact/Shp1ArtifactWriter.kt`). SCC1
  preserves canonical member classes and every internal target/multiplicity
  for the nested shapes UI.
- **Correctness invariants** — `ShapeInvariantVerifier` implements smoke
  tests 1–4; `ShapeEdgeAccountingVerifier` (`--verify-edges`) re-derives
  every reference against the artifact; determinism is covered by tests.

## Deferred / current limitations

- Per-object retained-size annotation (`retainedTotal` column).
- Per-class drill-down from the heap-wide artifact requires a persisted
  object→shape mapping; the current UI serves the aggregate graph only.
- The analysis phases after extraction (liveness, Tarjan, cons) are
  single-threaded; extraction walks are parallel.

## Layout

The program is one driver per command (`Drivers.kt`), reading top to bottom.
Extraction is a pipeline — the dump changes representation at every stage —
and analysis is a dataflow over one graph, where every stage takes the graph
plus the facts it needs and returns one new fact:

```kotlin
val references = indexHprof(path, log).use { dump -> dump.extractReferences(projection, log) }
references.select(selection, log).use { graph ->
    val components  = graph.findComponents(log)
    val relaxations = graph.relax(components, shapeRules, log)
    val shapes      = graph.collapse(components, relaxations, log)
    val result      = graph.aggregate(components, shapes, log)
    // sinks: artifact, stats, verifiers, console
}
```

Each stage is an extension function on its primary input that builds a stage
class and invokes it; a value gets a name and a type only when more than one
later consumer needs it, and types are named for what they contain.

- `Drivers.kt`, `Configs.kt`, `Log.kt` — the drivers, their resolved
  configurations, and the progress sink with the `phase` timer.
- `graph/` — `AnalysisGraph` (the analysis input contract: every node is in
  scope, `roots` are the analysis roots), `ObjectGraph` (compact in-memory
  implementation for tests and demos, with `select(roots)` for the reachable
  closure; `graph/storage/` hides its CSR references and packed values).
- `hprof/` — the extraction pipeline: `indexHprof` → `HprofDump` (classes,
  dense ids, roots), `extractReferences` → `ObjectReferences` (projected
  CSR over every object, dead included), `select` → `HeapGraph` (live,
  in-scope, leaves folded). `ReferenceExtractor` owns the parallel record
  walk, chain expansion and scatter; `ObjectSelector` owns liveness, scope,
  folding and compaction. See "HPROF pipeline" below.
- `analysis/` — the four analysis stages (`AnalysisStages.kt`) and the public
  result. `analysis/internal/` holds the stage classes and their facts:
  `ComponentFinder` → `StronglyConnectedComponents`, `RelaxationResolver` →
  `ObjectRelaxations`, `ShapeCollapser` → `CanonicalShapes` (with the
  acyclic interner, WL refinement and the cyclic collapser behind it),
  `ShapeAggregator` → `ShapeAnalysisResult`.
- `relaxation/` — typed structural/field rules, TSV parser and exact counters.
- `artifact/` — SHP1/SCC1 writer.
- `verification/` — invariant and full edge-accounting verifiers; both take
  the facts they audit as plain arguments.
- `report/` — sinks: console summary, JSON reports, extraction stats, edge
  histogram.
- `cli/` — Clikt commands that parse flags into configs and call a driver.
  `SccClassMatrixReport.kt` (SCC investigation probes) is parked in a block
  comment until per-stage persistence lets it run over cached components.
- `synthetic/` — synthetic graphs used by demos and tests.

## Run

```bash
./gradlew build                                          # compile + all tests
./gradlew run --args="--help"                            # commands and typed options
./gradlew run --args="demo --modules 3000 --verify"      # in-memory demo
./gradlew fatJar   # build/libs/shapes-experiment-all.jar — what the heap-report
                   # backend runs (SHAPES_JAR)

# whole heap straight from HPROF:
java -Xmx1500m -jar build/libs/shapes-experiment-all.jar hprof-analyze \
  --hprof dump.hprof --whole-heap --relax relax.tsv --out heap.shp1 --stats s.json
```

Headline result on the synthetic 3000-module heap: 18001 objects collapse
to 6 shapes, roots histogram is a single row `(synth.Module, 3000)` —
exactly the spec's target.

## Reference-kind filtering and relaxed classes

Two noise-elimination mechanisms, both measured and opt-out:

- **Reference filtering** (extraction, default on; `--raw-references`
  disables): `java.lang.ref.Reference` fields `referent`/`queue`/`next`/
  `discovered` are VM/library mechanics — a soft/weak/phantom reference does
  not retain its referent, so those edges never enter the scope and GC state
  (cleared-vs-alive soft references) never splits shapes. Subclass-declared
  payload (e.g. a weak map entry's strong `value`) is kept; subtyping comes
  from the dump's class hierarchy, not names.
- **Relaxed classes** (`--relax <tsv>`, backend defaults to `relax.tsv`):
  per-class rules that weaken the structural key — `class-only` (only the
  class matters; children concealed from the key, stay in scope) or
  `set-children` (child multiplicities → 1). Every rule must state which
  merge it unblocks; the concealed edges are counted exactly
  (`--relax-stats`) and verified (`--verify-edges` exempts relaxed
  sources). See `relax.tsv` for the validated starter rules.

Product-level finding that shaped the feature (run-jfr-38, Gradle daemon):
the reachable closure of any service-graph anchor is one giant blob —
PatternSet (114k instances) and DefaultLegacyConfiguration (201k) close over
the *identical* set of 256.9M objects, 98.9% of the heap. Only value/data
classes (String, File, Path) give bounded class scopes; the web UI therefore
serves exactly one whole-heap artifact.

Class-scope reference results (reachable, exact):

| class                                   | roots   | R objects | shapes |
|-----------------------------------------|---------|-----------|--------|
| java.lang.String                        | 8.0M    | 15.8M     | 2      |
| org.gradle.util.Path                    | 1.59M   | 6.1M      | 13     |
| java.io.File                            | 1.01M   | 3.0M      | 17     |
| ...catalog.DependencyModel              | 105k    | 1.37M     | 9      |

`Path` roots histogram is the spec's headline view working on real data:
927k root paths `(Path → String[]{String×3})`, 654k nested `(Path →
parent + segments)`, plus a long tail of deeper chains — 1.59M instances
in 7 root shapes, exact. `File` surfaces a real merged SCC:
`scc[LinuxFileSystem, LinuxFileSystemProvider, UnixPath]` — 3 members,
4 internal edges per instance (the parent↔child cycle of spec open
point 4, discretized by class and merged exactly).

## HPROF pipeline (`hprof-analyze`, `shapes/hprof/`)

Reads the dump directly in one small-heap JVM. Large graph arrays live in
unlinked file-backed mappings, allowing the OS to reclaim resident pages under
pressure instead of growing the Java heap. Pipeline:

1. **Directory scan** — one pass over top-level records: UTF-8 strings,
   `LOAD_CLASS`, heap-segment extents (HotSpot writes ~1 MiB segments; they
   are grouped into ~64 MiB chunks for balanced parallel walks).
2. **Heap index (walk A, parallel)** — `CLASS_DUMP` layouts (reference-field
   byte offsets over the super chain, static/loader retained addresses), GC
   roots incl. per-thread locals, object addresses per chunk.
3. **Address table** — dense ids are address ranks; lookup is a span table
   (512-byte spans → u16 deltas) built in parallel from ordered chunks, with
   a counting-sort fallback for unordered heaps.
4. **Record walk (walk B, parallel)** — per-class extraction plans apply the
   reference-mechanics filter, conceal rules and all structural projections
   (map/table flattening per SHAPES.md "projection") from raw field bytes;
   per-object deduplicated main edges spill to per-chunk temp files, dropped
   edges are kept separately for liveness, hash-table chains and
   `next/before/after` links defer to assembly. Thread stack locals become
   thread-object edges.
5. **Assembly (pass C)** — chain expansion, CSR scatter (offsets + targets in
   unlinked file-backed mappings), GC-root liveness with a class-statics
   fixpoint, optional class-scope BFS, leaf folding (zero-out-degree objects
   collapse to per-class leaf pseudo-shapes with exact aggregates), in-place
   compaction to live non-leaf nodes.
6. The analyzer runs unchanged on the folded graph: Tarjan skips leaf targets,
   cons treats them as pre-collapsed children, and SCCs above 1024 members
   take a **lazy-WL** path: one streaming pass computes an isomorphism-
   invariant signature (member count, internal edge count, class histogram,
   external child-shape totals, relaxation rules applied). A unique signature
   — the common case, including the mega-SCC — interns the shape with no WL
   refinement at all; a collision materializes both components' exact WL
   canonical forms (the same machinery as small SCCs) and merges only on a
   canonical-digest match, otherwise the shapes stay distinct with an
   ordinal-discriminated digest. Colliding pairs beyond 2^21 members are kept
   distinct (the conservative direction) with a logged warning. Lazy shapes up
   to 2^20 members still carry the full per-member artifact payload
   (`membersComplete=true`, presentational member order); above that the
   payload is capped at the top 50 classes. Aggregate external edge totals
   replace per-member edge rows either way.

Semantics notes, pinned by measurement on run-jfr-38:

- Live set = GC-root liveness (roots + class-statics fixpoint) over the
  filtered graph; class objects are excluded from the graph, including the
  primitive classes (`int.class`..`void.class`).
- Per-object target dedup is uniform: each object's references form a target
  set; duplicate targets collapse before projection.
- Class-static referents are reached through the class's **loader only**
  (statics do not make every class a root).
- Shallow sizes use the compressed-oops layout model, auto-detected via max
  heap address < 32 GiB; arrays carry explicit overrides.
- Set-children members of lazy SCCs aggregate external totals over actual
  members (deduped per member); like all relaxed components they are exempt
  from edge-accounting verification.

### Whole-heap result (run-jfr-38, i7-13700H, four processors)

259,731,971 selected objects / 510,745,821 graph edges → 104,612 shapes
(collapse 2,483×). With JFR enabled and Xmx 2g: index 51.8 s, extraction
152.8 s, selection 44.8 s, Tarjan 13.9 s, cons 79.1 s, aggregate 4.1 s;
**total 350.8 s**. The final SHP1, extraction JSON and relaxation JSON were
byte-identical to the original implementation's output.

For comparison, the original took 532.6 s in its successful Xmx 4g run and
OOMed at Xmx 2g after 315.0 s. Those timings have different heap limits and
cache conditions, so they are not a perfectly controlled speedup ratio.
Optimized runs at Xmx 2g have reached 8.38–11.04 GiB peak RSS, including
memory-mapped input and file-backed graph pages; RSS varies with OS page
reclamation. SCC descriptor storage alone removes two 138.7M-entry Java int
arrays (~1.03 GiB) in favor of one file-backed mapping.
`--verify` passes inv1/2/4; inv3 differs by exactly the class-only concealed
edge count when relax rules are active (those edges are accounted by
`ShapeRelaxationStats`).

The mega-SCC is real and exact: 139,136,834 members / 3,057 distinct classes /
279,776,747 internal edges in one strongly connected component.

Diagnostics: `hprof-scan` (reader validation counts), `--edge-histogram`
(per-class instance/out-edge TSV for cross-run reconciliation), `--no-fold`
(verification mode; proven structurally identical to the folded run).
Class scopes (`--class X`, reachable semantics) disable folding because roots
may be leaves.

Validation history: before the MAT-based extractor was removed, the HPROF
pipeline was verified against it digest-for-digest on three class scopes
(String / Path / File — identical artifacts incl. shallow sizes), on the
whole-heap live set (equal modulo MAT's synthesized pseudo class-loader
object), and on the mega-SCC membership, class histogram and internal edge
count. Lazy-WL interning was verified against eager WL on all 31 large SCCs
of this dump (identical membership, counts, internal edges), and both
collision outcomes are unit-tested.

## Analyzer performance notes (JFR-driven)

1. `BitSet.clear(i)` is O(words) — was 98.6% of Tarjan → ByteArray
   (170 s → 1.5 s on 15.8M objects).
2. Boxed member lists and per-node `IntArray` shells → CSR storage
   throughout; 15.8M objects analyze in 1 g heap (~26 B/object live).
3. Per-lookup cons-key allocations → open-addressed cons-map over packed
   `(classIdx, kids)` keys, zero allocation on hits.
4. ConsMap hash mixed weakly in the low bits → fmix32 finalizer + double
   hashing + per-shape keyHash precheck (maxProbe 350k → 42).
5. WL refinement uses flat primitive scratch reused across rounds
   (~12 B/member + 4 B/internal edge) plus a uniform-regular fast path;
   large components avoid it entirely via the lazy signature.
6. Footprint pass: packed class indices/shallow sizes, sign-coded
   index/compOf, per-frame Tarjan stacks, singleton components inlined out
   of the members CSR, off-heap component assignment, per-phase arenas.
7. Correctness audit (`--verify-edges`): merged non-uniform SCCs must emit
   per-INSTANCE totals (`edgesPerMember=false`), not the first member's
   edges — found by exact edge re-derivation, 600M refs reconciled.

Backend note: `backend/mat/shapes.py` invokes `hprof-analyze` (pipeline
version 7); `_publication_metadata` maps `HprofExtractionStats` onto the
published SMD1 keys and `validate_projection` cross-checks artifact
aggregates against the extractor's independent counters.

## Flow comparison diagnostics

See [FLOW-COMPARISON.md](FLOW-COMPARISON.md) — conclusions of the
flow-vs-shapes investigation and the design decisions they justify.
Headline: the 32-root retained sample yields 416 shapes versus 243 flow
classes on identical adjacency; the full retained scope yields 234,834 raw
shapes versus 7,821 after diagnostic map normalization, and the residual is
fully explained by per-module dependency content plus three identified
dump-time artifacts. The diagnostic numbers are not a promise for every dump
or every collection implementation.

## Known limitations / notes for review

- `Edge`/`Shape` graphs are interned and acyclic (DAG + SCC payloads), so
  structural equality via interned identity is exact and O(1).
- WL-equivalence is a superset of isomorphism; for heap SCCs (lists, maps,
  trees) it is exact in practice, and counts/totals stay exact regardless
  because same-color members carry identical edges.
- Liveness, Tarjan and cons are single-threaded (~50 s combined on the
  260M-object dump) — the next headroom if the 5-minute budget ever
  tightens.
