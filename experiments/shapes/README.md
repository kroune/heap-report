# shapes — exact structural collapse (experiment)

First implementation of `backend/mat/SHAPES.md`, in Kotlin, deliberately
**without MAT**: the object graph is an in-memory stand-in so the algorithm
can be exercised, measured and reviewed before wiring it into the MAT JVM.
Spec section references below are to SHAPES.md.

## What is implemented

- **Definitions** — `ObjectGraph` (objects + outbound refs), roots = full
  instance id list of a class, scope R = reachable closure (open point 1:
  reachable, not retained).
- **Equality rules** — leaf / acyclic / SCC keys exactly as specified.
  Multi-edges preserved (`Object[]{x,x}` ≠ `Object[]{x,y}` when x,y differ
  structurally). SCC: color refinement over `(class, internal-neighbor
  colors, child-shape colors)`, merge only on discretization, `|SCC| > 64`
  → unique fallback (`ShapesAnalyzer.MAX_SCC_MERGE`). Self-loops take the
  SCC path. **shallow is NOT part of the key** (open point 3: resolved OUT).
- **Hashing discipline** — cons-map key is the full structural tuple
  (`LeafLk`/`NodeLk`/`SccLk`), equality is exact via interned child shape
  identity, never via the digest. Separate 128-bit cross-dump digest over a
  canonical binary encoding (class by name, children as sorted 128-bit
  digests) — XXHash64 ×2 with fixed seeds, verified against `xxhsum`.
- **Pipeline** — reach → iterative Tarjan (components come out in reverse
  topo order, so bottom-up cons = plain component-id order) → cons →
  aggregates. Shape ids assigned post-merge by digest sort → deterministic.
- **Aggregates** — `instanceCount`, `shallowTotal/Min/Max`, `rootCount`,
  complete edge table `(shapeId, childShapeId, multiplicity)`. Edge
  multiplicity is **per member** of the source shape (expanded totals =
  mult × instanceCount), except `SCC_UNIQUE` where edges are totals
  (`edgesPerMember = false` on the entry).
- **Phase 5 emit** — binary artifact (`SHP1` magic; layout documented in
  `Emit.kt`). 18001 objects → 627 bytes.
- **Correctness invariants** — `verifyInvariants` implements smoke tests
  1–4; 6 (determinism) is a test. 5 (match vs sampling payload) needs MAT.

## Deferred (spec paths not in this experiment)

- MAT integration (bulk int APIs, `IObject`-free hot loops, retained-size
  pass → `retainedTotal` column).
- All parallelism (frontier-split BFS, level-synchronous striped cons) —
  v1 here is single-threaded; the spec presents these as scale
  optimizations, not semantics. Sequential Tarjan stands as spec'd.
- `IDS_LIMIT` paging (open point 5), per-instance drill-down, strings/values.
- Whole-heap global pass (open point 2) — analysis is per-(graph, class),
  matching the current job flow.

## Layout

- `Graph.kt` — `ObjectGraph` model, text format
  (`<id> <class> <shallow> [refs]`), synthetic "N modules" generator.
- `XxHash64.kt` / `Digest.kt` — hash + 128-bit canonical digests.
- `Shapes.kt` — the pipeline (this is the file to review).
- `Verify.kt` — invariant checker (`--verify` and tests).
- `Emit.kt` — binary artifact writer.
- `Main.kt` — CLI.

## Run

```bash
gradle build                                            # tests (14)
gradle run --args="demo --modules 3000 --verify"        # in-memory demo
gradle run --args="generate --modules 3000 --out heap.txt"
gradle run --args="analyze --graph heap.txt --class synth.Module --out shapes.bin --verify"
```

Headline result on the synthetic 3000-module heap: 18001 objects collapse
to 6 shapes, roots histogram is a single row `(synth.Module, 3000)` —
exactly the spec's target. 300k modules (1.8M objects) analyze in ~2 s
single-threaded.

## Real heap dumps (MAT extractor)

`shapes.mat.MatExtract` pulls the reachable graph for a class out of a MAT
snapshot into GRPH binary (dense ids, refs remapped — see "Known
limitations"), `analyze-grph` runs the pipeline on it. Opening bypasses the
OSGi-dependent `SnapshotFactory` (deserializes `daemon.index` + private
`SnapshotImpl` ctor via reflection + stub `IObjectReader`; everything used
is index-backed).

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

`Path` roots histogram is the spec's headline view working on real data:
927k root paths `(Path → String[]{String×3})`, 654k nested `(Path →
parent + segments)`, plus a long tail of deeper chains — 1.59M instances
in 7 root shapes, exact.

Perf/memory, JFR-driven (in order of what profiles showed):
1. `BitSet.clear(i)` O(words) — 98.6% of Tarjan → ByteArray (170 s → 1.5 s).
2. Boxed `Int` member lists — OOM at 15.8M in 2 g → CSR comps + IntArrays.
3. Per-node `IntArray` ref shells — ~40 B/node → CSR graph storage (flat
   refs + offsets + class table); 15.8M objects now analyze in **1 g** heap.
4. Per-lookup cons-key allocations + `LinkedHashMap` kid grouping — top of
   cons → open-addressed cons-map over packed `(classIdx, kids)` keys,
   scratch-buffer kid building, zero allocation on hits (Path: 2.7 → 0.6 s).
5. `DataInputStream` per-field reads dominated GRPH load → bulk reader.

## Known limitations / notes for review

- `Edge`/`Shape` graphs are interned and acyclic (DAG + SCC payloads), so
  structural equality via interned identity is exact and O(1).
- `SCC_UNIQUE` digests are per-input deterministic (salt = min member id)
  but not cross-dump stable — acceptable: they never merge, so cross-dump
  diff has nothing to compare anyway.
- Text graph loader requires dense 0-based ids.
- MAT extraction remaps ids to dense (SHAPES.md's "no id remapping" is an
  in-MAT-JVM trade-off; in a separate process, memory ∝ |R| wins).
- MAT extraction BFS is single-threaded and I/O-bound on random outbound
  index reads (~80–300k objects/s depending on page cache); pass 1/2 run
  in sorted id order and are fast. Parallelism + bulk index reads are the
  spec's frontier-split BFS upgrade.
- Analyzer memory ≈ 60–70 B per R object (dominated by per-node IntArray
  ref shells): ~25–30M objects fit in 2 GB. CSR graph storage (flat refs +
  offsets) is the obvious next step and roughly doubles capacity.
