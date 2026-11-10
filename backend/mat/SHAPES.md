# SHAPES.md — exact structural collapse for the heap graph

Status: **supported heap-wide implementation; pipeline v5.** When this
document and the code disagree, one of them is wrong — update both in the
same commit.

The Shapes tab collapses the complete non-class object graph into a shape-DAG,
where each node groups structurally identical objects. Everything served is
exact: counts, shallow sums, edge multiplicities. Class-scoped extraction
remains available in the standalone CLI for diagnostics, but is no longer part
of the web UI or HTTP API.

## Why (design rationale)

- Sampling error is unbounded on skewed heaps — 8 instances of 3000 modules
  tell you nothing about the tail. Exactness must be structural, not
  statistical.
- The collapse ratio does the scaling work: on repetitive heaps (3000
  modules) unique shapes ≪ objects, so one exact heap-wide analysis is much
  smaller to serve and explore than the original object graph.
- The collapse target is the **object graph**, not the dominator tree: the
  view must match class dependencies in code (diamond `A→B→D, A→C→D` keeps
  `B→D`, `C→D`; the dom tree would hang D directly under A and destroy
  exactly the edges we want to show).

## UI/backend pipeline

The shapes UI runs the prebuilt fat jar; Gradle is never invoked by the server.
One analysis selects the **whole heap**. There is no class or
retained/reachable scope selector. Re-analysis atomically replaces
`data/shapes/heap.shp1`; old per-class artifacts are not served by the web API.

For the heap-wide pass, scope is every non-class object in the snapshot.
Class-scoped CLI extraction selects its scope on the original filtered object
graph **before** map projection. Retained scope uses MAT `getRetainedSet` for all queried instances
(with MAT's standalone-compatible ObjectMarker factory switch), then restricts
that set to objects reachable over the filtered source references. Reference
mechanics and `conceal-field` rules participate in this traversal for both
retained and reachable scope. Map projection never adds or removes objects.
The analyzer keeps every preselected object even if projection removes its path
from a root. Heap-wide artifacts have no queried-class roots: root counts and
the roots histogram are zero and the UI does not expose root metrics.

Projection is field-aware and supports the exact JDK classes `HashMap$Node`,
`LinkedHashMap$Entry`, `LinkedHashMap`, and `HashMap$Node[]`. It removes only
`next`, `before`, `after`, `head`, `tail` as appropriate and makes tables point
to all entries, independent of buckets/insertion order. Key/value targets
survive, including an alias also referenced through a removed technical field.
TreeNode, custom map classes and other collections are not guessed/normalized.
No object, count, shallow bytes or root is dropped; the projection changes
**reference semantics**, not the population. Ordering is intentionally absent
in this projection.

An optional `SMD1` length-prefixed JSON trailer in the same SHP1 file records
extraction mode, pipeline version, object/root/shallow totals, original
in-scope reference count, boundary reference count and projected reference
count. Source-reference counts are MAT-index targets (deduplicated by target
ID), not field/array-slot multiplicities. Displayed edges are explicitly
**projected** edges; do not compare them as raw heap edges. Before atomic
publication the backend checks all object, root,
shallow and expanded projected-edge totals against extraction statistics.
The served `reachableCount` includes `setChildrenConcealedObjects`: it is the
real scope population, not merely the sum of visible quotient-SCC counts.
Pipeline v4 also makes extraction reference filtering side-effect free, so
repeated projection reads cannot inflate concealed-field statistics.

Pipeline v5 appends an optional `SCC1` section (after the roots histogram,
before `SMD1`) containing each stored SCC member's class and exact internal
`(targetMember, multiplicity)` connections. The shapes canvas uses it to
render the member graph inside an SCC box instead of treating the cycle as an
opaque circle. Old SHP1 artifacts without SCC1 remain readable and retain the
legacy circle until re-analyzed. Mega `SCC_UNIQUE` payloads still store only
the first 8 members; an explicit `membersComplete` flag marks the graph as
incomplete (even with a custom merge cap below 8), so the UI never pretends to
show missing members or connections.

Backend SCC refinement uses cap 1,000,000 (`SHAPES_MAX_SCC_MERGE` override)
to avoid the old 64-member artifact in container graphs. Larger components
still take the lean unique path. The CLI's default SCC merge cap is 64 unless
overridden. See the measured investigation in
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
- **Roots** (class-scoped CLI only): every instance of the queried class (full
  `INSTANCEOF` id list, no sampling). The heap-wide UI has no class roots.
- **Scope** R: the web pipeline selects every non-class object in G. The CLI can
  instead select objects retained by or reachable from queried-class roots.
- **SCC**: maximal strongly connected component. The condensation of R is a
  DAG. Cycles are rare and mostly collections/back-pointers.
- **Shape**: structural identity of a condensation-DAG node, computed
  bottom-up. Every object belongs to exactly one shape — **shapes partition
  R** (per-node sums are exact and sum to the exact total; sharing distorts
  nothing at node level, only DAG rollups, which are occurrence-counts).
- **Shape-DAG**: nodes = shapes; edge S→T with multiplicity m = each member
  of S holds m references to members of T. In-degree > 1 = sharing.

## Scope: reference-kind filtering (GC semantics)

`java.lang.ref.Reference` fields `referent` / `queue` / `next` / `discovered`
are VM/library mechanics, not program structure. A soft/weak/phantom
reference does **not** retain its referent — the GC clears it under
pressure — so those edges never pull objects into the scope and never
split shapes with GC state (observed on run-jfr-38: the pre-OOM GC had
cleared some `SoftReference`s, splitting 606 otherwise identical
configurations from 2,289 untouched ones). Fields **declared by Reference
subclasses** (e.g. a weak map entry's strong `value`) are payload and are
kept; subtyping is computed from the MAT class hierarchy, not names. For
retained scope the set is then restricted to objects still reachable from
the roots over the filtered graph — totals therefore intentionally diverge
from MAT's retained histogram (which counts soft/weak edges as strong);
both numbers are recorded in the stats. Disable with `--raw-references`.

Future note: if a consumer ever needs MAT-histogram parity instead of GC
semantics, fork MAT's retained computation (`ObjectMarker`) and mark over
the same filtered edge set — a small, well-bounded change; until then the
divergence is intentional and reported, not hidden.

## Relaxed classes (analyzer)

The exact key can be deliberately weakened for listed classes via a
versioned rules file (`experiments/shapes/relax.tsv`, `--relax <file>`,
backend default; every rule must state which merge it unblocks):

- **class-only**: only the class matters. The object conses as a LEAF of
  its class; its children stay in scope and keep their counts but are not
  part of the key. For objects whose children are deliberately irrelevant
  to the owner graph: lazy infrastructure state (e.g. an HTTP client that
  may or may not have been instantiated yet), or resolved-artifact contents
  when the requested class-anatomy view is closure-insensitive.
- **set-children**: class + SET of child shapes; multiplicities collapse
  to 1 for that class's outgoing edges. On an SCC member the rule
  quotients the **whole SCC**: the SCC is one strongly-connected object,
  and the multiplicity of identical members inside it (e.g. 7
  ElementShadows per direct project dependency of a configuration) is a
  children-list fact — the SCC is keyed by its WL-color quotient, and
  instance accounting counts quotient-size per SCC instance so
  `instanceCount / sccInstanceSize` stays the instance count. The dropped
  members are real objects concealed from the artifact's `count` —
  accounted exactly in `--relax-stats` as `setChildrenConcealedObjects`
  and added back by the backend's publication check (like concealed edges).

Rules apply inside SCC members as well (external edges of relaxed members
are concealed / set-collapsed; internal SCC wiring stays exact for
class-only). Relaxed edges are **concealed, not removed**: the analyzer
counts them exactly (`--relax-stats`), `--verify-edges` exempts relaxed
sources (and every member of a quotiented SCC), and the backend's
publication check subtracts the concealed counters instead of assuming
them. The rule set is validated against the run-jfr-38 retained set in
`experiments/shapes/FLOW-COMPARISON.md`.

### Level 3: field concealment (extraction)

`conceal-field:<name>` rules drop **one named field** of a class and all its
subclasses at extraction time (the analyzer's GRPH has no field names, so
this level lives in `mat-extract`; the same `relax.tsv` is passed to both
steps). The edge never enters the graph — its target may leave the scope
entirely. This is the surgical choice for a lazy/cache field whose
*presence* is state, not structure, e.g.:

- `ExternalResourceResolver.cachedArtifactResolver` — lazy create-on-first-
  download field (null in cache-served resolutions). Class-only relaxation
  of `MavenResolver` was the broader alternative; the field rule hides
  exactly this edge and keeps the rest of the resolver exact.
- `DefaultConfiguration.description` — UI text. Shared
  description Strings (e.g. captured by the IDE tooling model mid-sync) get
  cut from the retained set and split otherwise-identical SCCs by a missing
  `String×k`; concealing the field removes the split *and* the uniform
  noise, identically for every module.

Like the reference filter, field concealment participates in the scope
restriction: objects reachable only through concealed edges are dropped
(counted in the extraction stats). Rules are resolved through the exact MAT
class hierarchy — a rule on `DefaultConfiguration` covers the runtime
`_Decorated` subclasses.

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
  the compare tab diffs. Hash function: XXH3-128 with a fixed zero seed,
  streamed through hash4j's `HashStream128` using little-endian primitives
  and length-delimited UTF-8 strings; never `Object.hashCode`.

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
   histogram `(shapeId, count)` over class instances; `SCC1` member labels
   and exact internal target/multiplicity tables for nested rendering.
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

1. **Scope**: resolved for the web UI — one whole-heap pass. Retained and
   reachable class scopes remain CLI diagnostics only.
2. **Global vs per-class pass**: resolved — one whole-heap artifact per dump.
   Per-class drill-down from it still needs a persisted object→shape mapping.
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
