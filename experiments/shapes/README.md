# shapes — exact structural collapse

Implementation of `backend/mat/SHAPES.md`, in Kotlin. The analyzer operates on
a compact in-memory graph; the supported MAT extractor produces that graph
from real heap dumps. The historical `experiments/shapes` path is retained
while the implementation is being hardened, to keep existing build and
diagnostic workflows compatible.
Spec section references below are to SHAPES.md.

## What is implemented

- **Definitions** — `ObjectGraph` (objects + outbound refs). Class diagnostics
  use roots = full instance id list and an explicitly selected retained or
  reachable graph. The backend's heap-wide mode selects every non-class object
  and intentionally has no class-root counts.
- **Equality rules** — leaf / acyclic / SCC keys exactly as specified.
  Multi-edges preserved (`Object[]{x,x}` ≠ `Object[]{x,y}` when x,y differ
  structurally). SCC: color refinement over `(class, internal-neighbor
  colors, child-shape colors)` and merge on the WL-fixpoint signature.
  Self-loops take the same SCC path. All components use this path; there is
  no separate unique-SCC mode. **shallow is NOT part of the key**.
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
  tests 1–4; determinism is covered separately by tests.

## Deferred / current limitations

- Per-object retained-size annotation (`retainedTotal` column). MAT extraction
  itself is implemented and used by the backend.
- All parallelism (frontier-split BFS, level-synchronous striped cons) —
  v1 here is single-threaded; the spec presents these as scale
  optimizations, not semantics. Sequential Tarjan stands as spec'd.
- `IDS_LIMIT` paging (open point 5), per-instance drill-down, strings/values.
- Per-class drill-down from the heap-wide artifact requires a persisted
  object→shape mapping; the current UI serves the aggregate graph only.
- WL refinement is always performed, including for very large SCCs. Their
  per-member working memory remains a known scale limitation.

## Layout

- `graph/` — `ObjectGraph`, its domain API and builder. `graph/storage/`
  hides CSR references, compact unsigned-long values and primitive stacks.
- `input/heap/` — `HeapObjectSource`, the random-access heap contract used
  by extraction; it has no MAT or analysis dependency.
- `extraction/` — typed scope selection, reference filtering, structural
  projection, dense-id remapping, boundary stubs and graph materialization.
- `analysis/` — `ShapesAnalyzer`, typed `AnalysisScope` and the public result.
  `analysis/internal/` contains SCC finding, canonical shapes, WL refinement,
  structural interning and aggregation.
- `relaxation/` — typed structural/field rules, TSV parser and exact counters.
- `artifact/` — SHP1/SCC1 writer.
- `verification/` — invariant and full edge-accounting verifiers.
- `mat/` — MAT snapshot loading, `HeapObjectSource` adapter and MAT queries.
- `cli/` — Clikt commands, reports and the shared analysis workflow.
- `synthetic/` — synthetic graphs used by demos and tests.

## Run

```bash
./gradlew build                                          # compile + all tests
./gradlew run --args="--help"                            # commands and typed options
./gradlew run --args="demo --modules 3000 --verify"      # in-memory demo
./gradlew fatJar   # build/libs/shapes-experiment-all.jar — what the heap-report
                   # backend runs (SHAPES_JAR; mat-analyze)
```

Headline result on the synthetic 3000-module heap: 18001 objects collapse
to 6 shapes, roots histogram is a single row `(synth.Module, 3000)` —
exactly the spec's target. 300k modules (1.8M objects) analyze in ~2 s
single-threaded.

## Real heap dumps (MAT extractor)

`MatHeapObjectSource` adapts a MAT snapshot to the source-independent
`HeapObjectSource` contract. `HeapObjectGraphExtractor` selects a
retained/reachable/whole-heap scope, then builds the dense in-memory
`ObjectGraph` in one materialization pass. `mat-analyze` runs collapse
immediately in the same JVM, without a serialized intermediate or reparsing step.

Scope selection is necessarily separate: retained membership requires MAT's
retained-set computation (and filtered-scope restriction), while reachable
membership requires BFS. Only after membership is known can dense target ids
be assigned during the single materialization pass.

Opening bypasses the OSGi-dependent `SnapshotFactory` (deserializes
`daemon.index` + private `SnapshotImpl` ctor via reflection; everything used
is index-backed except named field reads, which use a standalone
`HprofHeapObjectReader` subclass that skips OSGi preferences).
`java.lang.ref.Reference.discovered` edges are filtered during traversal and
materialization — VM bookkeeping, not program structure (SHAPES.md
"Definitions"). Diagnostic commands:
`mat-fields` (per-instance outbound refs with field names) and
`mat-ref-targets` (histogram of reference-typed field targets),
`mat-isclass` (class-filter sanity: finds java.lang.Class instances
missing from the class table — the 9 primitive class objects),
`mat-reachback` (reverse BFS over the inbound index — quantifies which
classes carry back-paths into the mega-SCC).

Backend/UI uses `--whole-heap --scope heap`. The dedicated Shapes tab
supports re-analysis of the single heap artifact.
Class-scoped retained / reachable extraction remains available in the CLI for
diagnostics. `--stats <json>` preserves source/projected reference counts.
The direct pipeline always retains all selected objects without turning them
into roots. `SHAPES_XMX_ANALYZE` sizes the combined extraction + analysis JVM
and defaults to 8g. The server requires a built fat jar and never runs Gradle.
See `backend/mat/SHAPES.md` for projection and artifact metadata.

## Reference-kind filtering and relaxed classes (v3)

Two noise-elimination mechanisms, both measured and opt-out:

- **Reference filtering** (extraction, default on; `--raw-references`
  disables): `java.lang.ref.Reference` fields `referent`/`queue`/`next`/
  `discovered` are VM/library mechanics — a soft/weak/phantom reference does
  not retain its referent, so those edges never enter the scope and GC state
  (cleared-vs-alive soft references) never splits shapes. Subclass-declared
  payload (e.g. a weak map entry's strong `value`) is kept. Retained scope
  is then restricted to objects reachable over the filtered graph, so totals
  intentionally diverge from MAT's retained histogram (both numbers are in
  the extraction stats).
- **Relaxed classes** (`--relax <tsv>`, backend defaults to `relax.tsv`):
  per-class rules that weaken the structural key — `class-only` (only the
  class matters; children concealed from the key, stay in scope) or
  `set-children` (child multiplicities → 1). Every rule must state which
  merge it unblocks; the concealed edges are counted exactly
  (`--relax-stats`) and verified (`--verify-edges` exempts relaxed
  sources). See `relax.tsv` for the validated starter rules.

Findings on `run-jfr-38` (15.8 GB hprof, 259.8M objects, Gradle daemon,
2 GB analyzer heap):

1. **MAT's outbound index includes the pseudo-reference to the object's
   `java.lang.Class`.** Following it pulls statics and (via the class
   loader) most of the heap: 8M Strings reached 38M objects instead of
   15.8M. The extractor filters `isClass(w)` — SHAPES.md should pin this
   down ("outbound references (MAT index)" is ambiguous).
2. **Reachable scope explodes for service-graph classes** (spec open
   point 1): from ONE `EventDirectoryStream` (retained 63 MB) reachable is
   65M+ objects; `DefaultDirectoryVar` (885k instances) → 70M+. Retained
   heap is NOT a proxy for reachable-set size. Classes with naturally
   bounded reachable sets are leaf-ish DTOs (String, File, Path).
3. **BitSet.clear(i) is a trap**: `recalculateWordsInUse()` is O(words) —
   it was 98.6% of Tarjan time (JFR profile). ByteArray instead:
   170 s → 1.5 s on 15.8M objects.

Results (2 GB heap, single-threaded, idle machine):

| class                                   | roots   | R objects | shapes | analyze |
|-----------------------------------------|---------|-----------|--------|---------|
| java.lang.String                        | 8.0M    | 15.8M     | 2      | 1.1 s   |
| org.gradle.util.Path                    | 1.59M   | 6.1M      | 13     | 0.6 s   |
| java.io.File                            | 1.01M   | 3.0M      | 17     | 0.4 s   |
| ...catalog.DependencyModel              | 105k    | 1.37M     | 9      | 0.3 s   |
| ...util.PatternSet                      | 114k    | 256.9M    | —      | extract only |
| ...DefaultLegacyConfiguration_Decorated | 201k    | 256.9M    | —      | extract only |

`Path` roots histogram is the spec's headline view working on real data:
927k root paths `(Path → String[]{String×3})`, 654k nested `(Path →
parent + segments)`, plus a long tail of deeper chains — 1.59M instances
in 7 root shapes, exact. `File` surfaces a real merged SCC:
`scc[LinuxFileSystem, LinuxFileSystemProvider, UnixPath]` — 3 members,
4 internal edges per instance (the parent↔child cycle of spec open
point 4, discretized by class and merged exactly).

Scale findings:
- **Extraction** works at full-heap scale: the historical file-based pipeline's BFS
  over 257M objects took 10–14 min (~0.5M obj/s), followed by a 12–32 s class
  pass, a 77–140 s write pass, and a 6.5 GB serialized graph read. The current pipeline
  replaces all three post-BFS walks with one in-memory materialization pass,
  using the same popcount-rank O(1) id remap. No new timing is claimed here.
- **The reachable closure is one giant blob**: PatternSet (114k instances)
  and DefaultLegacyConfiguration (201k) close over the *identical* set of
  256,893,323 objects — 98.9% of the daemon heap. "Reachable scope" for
  any service-graph anchor ≈ whole heap; only value/data classes give
  bounded scopes. This shapes how the feature must be used (findings below).
- **Analyzer memory**: measured ~26 B/object live (CSR graph: 2 B class idx
  + 4 B packed shallow + 4 B offsets + 4 B/ref; Tarjan: index+compOf 8 B,
  per-frame low/dfs stacks sized by depth not n, bitset on-stack) → min
  workable -Xmx on the 15.8M-object String scope is 544 MB (~34 B/object
  incl. JVM/GC overhead; was 768 MB before the footprint pass). The 257M
  scope needs ~8–9 GB — under the spec's planned 10 g MAT-JVM budget — or
  the off-heap structures from spec phase 4.
- **Analysis speed** is a non-issue wherever it fits: ≤1 s per phase at
  16M objects, ~70M objects/min.

Perf/memory, JFR-driven (in order of what profiles showed):
1. `BitSet.clear(i)` O(words) — 98.6% of Tarjan → ByteArray (170 s → 1.5 s).
2. Boxed `Int` member lists — OOM at 15.8M in 2 g → CSR comps + IntArrays.
3. Per-node `IntArray` ref shells — ~40 B/node → CSR graph storage (flat
   refs + offsets + class table); 15.8M objects now analyze in **1 g** heap.
4. Per-lookup cons-key allocations + `LinkedHashMap` kid grouping — top of
   cons → open-addressed cons-map over packed `(classIdx, kids)` keys,
   scratch-buffer kid building, zero allocation on hits (Path: 2.7 → 0.6 s).
5. WL refinement uses flat primitive scratch arrays reused across rounds,
   primitive member ordering, and a uniform-regular fast path. Its working
   storage is about 12 B/member + 4 B/internal edge (8 B/member when neighbor
   signatures are unnecessary), instead of roughly 200 B/member in the former
   object-key implementation. The former alternate mega-SCC path remains
   removed intentionally; there is no hidden behavioral fallback.
6. Footprint pass over the whole pipeline (driven by the 256.9M-object
   FlatSourceDirectoriesImpl scope — 98.9% of the heap, 105M components):
   CharArray class idx (2 B), packed shallow (4 B + overflow side-table),
   Tarjan `low[n]` → per-frame stack, index/compOf merged into one sign-coded
   array, dynamic 1.5× dfs stacks, identity iteration for whole-graph reach,
   singleton components inlined out of the members CSR, lean component
   storage (8 B/component).
   Result: the full pipeline (extract 16 min + load + analyze) completes on
   that scope at ~9 GB heap: reach 8 s, scc 23 s, cons 84 s, aggregate 15 s
   — 256.9M objects → 1.37M shapes (187.5×) in 130 s of analysis.
7. ConsMap: multiply-only hash mixed weakly in the LOW bits the probe uses →
   2M-slot probe walks (25+ min in `intern`, caught by jstack + a maxProbe
   counter). Fixed with an fmix32 finalizer + double hashing + a per-shape
   keyHash precheck (maxProbe 350k → 42).
8. Named-field projection now works with HPROF addresses and resolves dense MAT ids only for targets selected by projection (new map members, retained or removed fields), instead of resolving every named field up front. On run-jfr-38 whole-heap extraction this reduced the 8 GB JFR run from 862.5 s to 622.8 s with byte-identical extraction statistics; the map-rich retained scope went from 949.8 s to 740.6 s with identical shapes and edge multisets.
9. **Correctness (verify-edges audit):** merged SCCs emitted `first member`'s
   external edges for ALL members; a non-uniform SCC (LinkedHashMap ⇄ its
   entrySet: map→table, entrySet→entries) got mults inflated by the
   × instanceCount expansion (inv3 over-count 0.15%). Fix: non-uniform SCCs
   still merge (the isomorphism is real) but emit per-INSTANCE totals —
   `edgesPerMember=false` means "× collapsedInstances".
   `ShapeEdgeAccountingVerifier` (CLI `--verify-edges`) re-derives every ref
   against the artifact: 600,058,408 refs, exact.

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
- MAT extraction remaps ids to dense (SHAPES.md's "no id remapping" is an
  in-MAT-JVM trade-off; in a separate process, memory ∝ |R| wins).
- MAT extraction BFS is single-threaded and I/O-bound on random outbound
  index reads (~80–300k objects/s depending on page cache); materialization
  runs once in sorted source-id order. Parallelism + bulk index reads are the
  spec's frontier-split BFS upgrade.
- Analyzer footprint after the footprint pass: ~26 B per R object live
  (breakdown above); the 15.8M-object String scope analyzes with -Xmx544m
  (was -Xmx768m). Next step when it matters: off-heap graph storage (spec
  phase 4) — the remaining floor is Tarjan's per-node index/component
  arrays, which are algorithmic working set, not representation waste.
