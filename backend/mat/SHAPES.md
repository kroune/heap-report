# SHAPES.md — exact structural collapse for class anatomy

Status: **experimental implementation; pipeline v2 supports explicit scope and map projection.** When it and the code disagree, one of
them is wrong — update both in the same commit.

Replaces the sampling path in `engine._analyze_class` with exact
counting: collapse the object graph reachable from **all** instances of the
queried class into a shape-DAG, where each node groups structurally
identical objects. Everything served is exact: counts, shallow sums, edge
multiplicities.

## Why (design rationale)

- Sampling error is unbounded on skewed heaps — 8 instances of 3000 modules
  tell you nothing about the tail. Exactness must be structural, not
  statistical.
- The collapse ratio does the scaling work: on repetitive heaps (3000
  modules) unique shapes ≪ objects, so exact counting is *cheaper* to serve
  than per-sample extraction, and per-class queries become pure db reads.
- The collapse target is the **object graph**, not the dominator tree: the
  view must match class dependencies in code (diamond `A→B→D, A→C→D` keeps
  `B→D`, `C→D`; the dom tree would hang D directly under A and destroy
  exactly the edges we want to show).

## UI/backend pipeline v2

The shapes UI runs the prebuilt fat jar; Gradle is never invoked by the server.
New analyses default to **retained scope + unordered map entries**, the measured
flow-comparison configuration. Both choices are explicit controls in the UI:
`retained` / `reachable`, and normalized / raw maps. Re-analyze replaces the
per-class artifact atomically; old SHP1 artifacts remain readable but show a
legacy label and must be re-analyzed to obtain the new semantics.

Scope is selected on the original filtered object graph **before** map
projection. Retained scope uses MAT `getRetainedSet` for all queried instances
(with MAT's standalone-compatible ObjectMarker factory switch). The analyzer
keeps every preselected object even if projection removes its path from a root;
only actual queried instances count as roots.

Projection is field-aware and supports the exact JDK classes `HashMap$Node`,
`LinkedHashMap$Entry`, `LinkedHashMap`, and `HashMap$Node[]`. It removes only
`next`, `before`, `after`, `head`, `tail` as appropriate and makes tables point
to all entries, independent of buckets/insertion order. Key/value targets
survive, including an alias also referenced through a removed technical field.
TreeNode, custom map classes and other collections are not guessed/normalized.
No object, count, shallow bytes or root is dropped; the projection changes
**reference semantics**, not the population. Ordering is intentionally absent
in this optional projection.

An optional `SMD1` length-prefixed JSON trailer in the same SHP1 file records
scope, projection, pipeline version, object/root/shallow totals, original
in-scope reference count, boundary reference count and projected reference
count. Source-reference counts are MAT-index targets (deduplicated by target
ID), not field/array-slot multiplicities. Displayed edges are explicitly
**projected** edges when projection is enabled; do not compare them as raw
heap edges. Before atomic publication the backend checks all object, root,
shallow and expanded projected-edge totals against extraction statistics.

Backend SCC refinement uses cap 1,000,000 (`SHAPES_MAX_SCC_MERGE` override)
to avoid the old 64-member artifact in container graphs. Larger components
still take the lean unique path. CLI without backend flags remains raw /
reachable, cap 64 unless overridden. See the measured investigation in
`experiments/shapes/FLOW-COMPARISON.md`.

## Definitions

- **Object graph** G: heap objects + outbound references (MAT index),
  EXCEPT:
  - class objects (`java.lang.Class` instances — they carry statics, which
    would drag in most of the heap). NOTE: MAT's `isClass` misses the 9
    primitive class objects (`int.class`..`void.class` — no ClassImpl
    entries), so the filter also excludes class-table gaps explicitly
    (found on run-jfr-38: exactly those 9 leaked via `MethodType.rtype`
    before the fix); a post-BFS guard fails the extract if any class
    object is in R.
  - `java.lang.ref.Reference.discovered` — the GC's discovered-list
    linkage. That list is VM bookkeeping captured mid-flight, not program
    state (a dump catches ~100k references linked in one chain regardless
    of their types), and it poisons the exact collapse: a list of length k
    is a different shape than k−1, so the chain alone produced ~30k
    shapes/layers for 1 MB of data (evidence: run-jfr-38, field-level
    check via `mat-ref-targets`: 105,329 × `discovered -> WeakReference`).
- **Roots**: every instance of the queried class (full `INSTANCEOF` id list,
  no sampling).
- **Scope** R ⊆ G: objects reachable from roots. (Current impl uses
  `SELECT AS RETAINED SET`; switching to reachable is deliberate — it shows
  shared objects the roots reference but don't own, which is what
  "dependencies in code" means. See open point 1.)
- **SCC**: maximal strongly connected component. The condensation of R is a
  DAG. Cycles are rare and mostly collections/back-pointers.
- **Shape**: structural identity of a condensation-DAG node, computed
  bottom-up. Every object belongs to exactly one shape — **shapes partition
  R** (per-node sums are exact and sum to the exact total; sharing distorts
  nothing at node level, only DAG rollups, which are occurrence-counts).
- **Shape-DAG**: nodes = shapes; edge S→T with multiplicity m = each member
  of S holds m references to members of T. In-degree > 1 = sharing.

## Equality rules

- **Leaf** (no children within R): key = `(class)`.
- **Acyclic node**: key = `(class, sorted multiset of child shape keys)`.
  Multi-edges are preserved: `Object[]{x,x}` ≠ `Object[]{x,y}`.
- **SCC node**: canonical form via color refinement over members
  (colors from `(class, internal-neighbor colors, child-shape colors)`,
  iterate to fixpoint):
  - merge key = the WL-fixpoint signature: multiset of per-member
    `(class, internal-neighbor color ranks, external child shapes)`.
    Automorphism-invariant, so it is canonical **whether or not**
    refinement fully discretizes — symmetric SCCs (e.g. a 2-cycle of
    identical `LinkedHashMap` entries) merge too. WL-equivalence is a
    superset of isomorphism; for heap SCCs (lists, maps, trees) it is
    exact in practice, and counts/totals stay exact regardless because
    same-color members carry identical edges. (The earlier
    discretize-or-unique rule produced 201k duplicate shapes on
    run-jfr-38 — every symmetric small SCC became its own shape.)
  - |SCC| > 64 → the SCC is its own unique node, never merged (memory:
    refinement structures cost ~200 B/member — a 142M-member mega-SCC
    cannot afford them; mega-SCCs are 1–2 per heap anyway).
  - a self-loop object is a size-1 SCC with an internal edge → SCC path.
- **shallow is NOT part of the key** (author decision, open point 3):
  `byte[16]` = `byte[1MB]` = one shape. Correctness is unaffected —
  `shallowTotal` is an exact sum over actual members either way — what
  changes is *resolution*: size variants collapse into one shape, and size
  information lives in the per-shape aggregates (sum/min/max), not in the
  graph structure.

## Hashing discipline

- The cons-map key **is the structural tuple**; the 64-bit hash is only the
  bucket. Insert verifies full tuple equality on collision. Exactness is
  deterministic, never probabilistic. Memory cost ∝ unique shapes, not
  objects.
- Cross-dump identity: separate **128-bit digest** over a canonical binary
  encoding — class by **name** (MAT class ids are per-snapshot), children as
  sorted 128-bit digests. Stable across runs, JVMs and dumps; this is what
  the compare tab diffs. Hash function: fixed-seed xxHash64 ×2 (or SipHash),
  never `Object.hashCode`.

## Pipeline (one ANALYZE job, runs inside the MAT JVM via MatRunner)

0. **Roots** — full instance id list of the class (existing OQL path).
   Open: `IDS_LIMIT=1M` caps mega-classes; needs paging (open point 5).
1. **Reachability** — parallel frontier-split BFS from roots over outbound
   refs; visited = atomic bitmap over MAT id space (2·10⁸ bits = 25 MB).
   Bulk int APIs only — no per-object `IObject` wrappers in hot loops.
2. **SCC** — iterative Tarjan over R, **single-threaded** (the one
   sequential phase; est. ~1 min @ 2·10⁸ nodes). Output: component id per
   object, condensation DAG, member lists. Parallel upgrade path:
   Forward-Backward divide & conquer — only if profiling demands it.
3. **Cons** — bottom-up over the condensation DAG, level-synchronous:
   node level = longest path to a leaf (reverse topo); levels processed in
   order, nodes within a level are independent → K threads; cons-map
   striped by hash prefix (one stripe per thread, key determines stripe,
   no contention, no wait-graphs). Shape ids assigned **after** the merge
   by sorting unique shapes by 128-bit digest → deterministic, stable.
4. **Aggregate** — one parallel pass over R. Per shape: `instanceCount`,
   `shallowTotal` / `shallowMin` / `shallowMax` (exact sums over actual
   members — shallow ∉ key, so members vary in size; min/max keep the
   spread visible), `retainedTotal` (Σ MAT per-object retained —
   annotation, needs one bulk retained-size pass first), `rootCount`
   (members that are class instances). Edges come from the shape keys
   directly (child shape + multiplicity, complete — the MAX_EDGE /
   edgesfull truncation machinery dies here).
5. **Emit** — binary artifact (CSV is dead at this scale):
   class-name table; shape table `(digest128, kind: leaf|tree|scc, class |
   scc-member payload, count, shallowTotal, shallowMin, shallowMax,
   retainedTotal, rootCount)`; edge table `(shapeId, childShapeId, multiplicity)`; roots
   histogram `(shapeId, count)` over class instances.
   Ingested into `analysis.db` as part `shapes:<key>` with the usual
   marker-row resumability (per-phase markers 1–4 so a crash mid-pipeline
   doesn't redo Tarjan).

The roots histogram is the headline number: 3000 module instances → ideally
one row `(shape, 3000)`.

## Memory budget (@ 2·10⁸ objects, 10g MAT JVM, shared with the index)

| structure                    | size     | lifetime      |
|------------------------------|----------|---------------|
| visited bitmap               | 25 MB    | whole job     |
| Tarjan index+lowlink+stack   | ~2.4 GB  | phase 2 only  |
| shape-id per object (int32)  | 800 MB   | phases 3–4    |
| per-object retained (int64)  | 1.6 GB   | phases 4 only |
| cons-map + shape store       | ∝ unique shapes (expect ≪ 10⁶ → ~100 MB; alarm at 10⁷) |

Peak ≈ MAT index + ~3 GB. All arrays are dense over MAT id space — no id
remapping in v1.

## Threading model

- BFS: parallel frontier split, CAS bitmap.
- Tarjan: sequential (v1).
- Cons: level-synchronous + striped maps. Deterministic output regardless
  of thread count (ids assigned post-merge by digest sort).
- Aggregate: parallel over id ranges, per-stripe partial sums merged.

## Correctness invariants (smoke tests)

1. Σ `shallowTotal` over shapes == Σ shallow over R (vs MAT histogram of R).
2. Σ `rootCount` == instance count of the class.
3. Σ edge multiplicities (expanded) == total outbound ref count within R.
4. Every object in R has exactly one shape-id.
5. One mid-size class: collapsed graph matches the current sampling payload
   structure where samples overlap (modulo sampling error).
6. Determinism: two runs, different thread counts → identical digests.

## What this replaces / keeps

- **Deletes**: `sample_even`, `samples`/`available` escalation, the
  reach.py fixpoint and its blind spots (for tree/graph views), MAX_EDGE /
  EDGE_FULL_CAP / ANAT_DEPTH / ANAT_KIDS truncation.
- **Keeps**: the `rs` retained-set composition part (different view,
  already exact), per-dump job/lock/Xmx discipline via MatRunner.
- **Deferred**: per-instance drill-down needs a persisted shape-id array
  (zstd, ~800 MB → ~100–200 MB/dump) — v2. Strings/values: attach one
  representative instance per shape later (bounded by unique shapes).

## Open points (iterate here)

1. **Scope**: reachable (v1) vs retained. Retained scope is root-dependent
   → no cross-class reuse; reachable shapes are context-free (a node's
   shape depends only on its own subgraph).
2. **Global vs per-class pass**: because reachable shapes are context-free,
   one whole-heap pass per dump serves *all* classes (per-class query =
   aggregate over its instances' shape-ids). v1 stays per-(dump, class) to
   match the current job flow; global pass is the upgrade.
3. **Shallow in label**: resolved — OUT (author). `byte[16]` and
   `byte[1MB]` are one shape; size resolution lives in aggregates
   (sum/min/max per shape). Refinements if the loss hurts: array length
   back into the key for array classes only, or log2 size buckets. Each
   re-split multiplies shape count — measure collapse ratio before/after
   on a real dump before adopting.
4. **SCC canonicalization**: color-refinement merged on the WL-fixpoint
   signature (above) — symmetric SCCs merge. The previous
   discretize-or-unique fallback was measured on run-jfr-38: 201k
   duplicate shapes (16% of all shapes), dominated by 2–4-entry
   `LinkedHashMap` cycles. Alternative (never merge SCCs) rejected:
   back-pointer SCCs (child→parent) are common in Gradle heaps and would
   block collapse of everything above them.
5. **IDS_LIMIT=1M** on the roots query: needs paging for mega-classes.
6. **Artifact format**: binary vs sqlite-direct; binary preferred, decided
   at implementation time.
