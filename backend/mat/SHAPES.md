# SHAPES.md — exact structural collapse for the heap graph

Status: **supported heap-wide implementation; pipeline v7.** When this
document and the code disagree, one of them is wrong — update both in the
same commit.

The Shapes tab collapses the complete live non-class object graph into a
shape-DAG, where each node groups structurally identical objects. Everything
served is exact: counts, shallow sums, edge multiplicities. The analysis tool
(`experiments/shapes`, run as a prebuilt fat jar) reads the HPROF file
directly in one small-heap JVM — no shared indexes, no serial analysis queue,
only the dump file. Class-scoped extraction remains available in the
standalone CLI for diagnostics, but is no longer part of the web UI or HTTP
API.

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

The shapes UI runs the prebuilt fat jar; Gradle is never invoked by the
server. One analysis selects the **whole heap** — every live non-class
object. There is no class or scope selector. Re-analysis atomically replaces
`data/shapes/heap.shp1`; old per-class artifacts are not served by the web
API. `backend/mat/shapes.py` owns job orchestration, publication metadata and
validation; `SHAPES_XMX_ANALYZE` sizes the JVM and defaults to 2g (the graph
lives off-heap in file-backed mappings). Heap-wide artifacts have no
queried-class roots: root counts and the roots histogram are zero and the UI
does not expose root metrics.

Class-scoped CLI extraction (`hprof-analyze --class X`) selects the reachable
closure of the class's instances over the filtered source references.
Reference mechanics and `conceal-field` rules participate in this traversal.
Map projection never adds or removes objects; the analyzer keeps every
selected object even if projection removes its path from a root.

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
extraction mode, pipeline version, object/root/shallow totals, the raw
non-null reference count and the projected reference count, plus the full
extraction statistics for diagnostics. Displayed edges are explicitly
**projected** edges; do not compare them as raw heap edges. Before atomic
publication the backend checks all object, root, shallow and expanded
projected-edge totals against the extractor's independent counters
(`validate_projection`). The served `reachableCount` includes
`setChildrenConcealedObjects`: it is the real scope population, not merely
the sum of visible quotient-SCC counts.

Pipeline v5 appended an optional `SCC1` section (after the roots histogram,
before `SMD1`) containing each stored SCC member's class and exact internal
`(targetMember, multiplicity)` connections. The shapes canvas uses it to
render the member graph inside an SCC box instead of treating the cycle as an
opaque circle. The `membersComplete` flag says whether all members and
connections are present; SCCs beyond the payload cap (2^20 members) store
only their most frequent classes.

## Definitions

- **Object graph** G: live heap objects + outbound references, EXCEPT:
  - class objects (`java.lang.Class` instances — they carry statics, which
    would drag in most of the heap). This includes the 9 primitive class
    objects (`int.class`..`void.class`). Class-static referents stay live
    through the class **loader**, not through per-class roots.
  - `java.lang.ref.Reference.discovered` — the GC's discovered-list
    linkage. That list is VM bookkeeping captured mid-flight, not program
    state (a dump catches ~100k references linked in one chain regardless
    of their types), and it poisons the exact collapse: a list of length k
    is a different shape than k−1, so the chain alone produced ~30k
    shapes/layers for 1 MB of data (evidence: run-jfr-38,
    105,329 × `discovered -> WeakReference`).
- **Roots** (class-scoped CLI only): every instance of the queried class. The
  heap-wide UI has no class roots.
- **Scope** R: the web pipeline selects every live non-class object in G. The
  CLI can instead select objects reachable from queried-class roots.
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
kept; subtyping is computed from the dump's class hierarchy, not names.
Disable with `--raw-references`.

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
them. The rule set is validated in `experiments/shapes/FLOW-COMPARISON.md`.

### Level 3: field concealment (extraction)

`conceal-field:<name>` rules drop **one named field** of a class and all its
subclasses at extraction time (the analyzer's graph has no field names, so
this level lives in the extractor's per-class plans; `hprof-analyze` applies
both rule kinds in one JVM). The edge never enters the graph — its target may
leave the scope entirely. This is the surgical choice for a lazy/cache field
whose *presence* is state, not structure, e.g.:

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
(counted in the extraction stats). Rules are resolved through the dump's
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
  - components above 1024 members intern **lazily**: a streaming
    isomorphism-invariant signature (member count, internal edge count,
    class histogram, external child-shape totals) decides interning, and
    WL refinement runs only to adjudicate signature collisions — merge on
    canonical-form match, distinct shapes otherwise. Colliding pairs
    beyond 2^21 members stay distinct (conservative) with a logged
    warning.
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
  encoding — class by **name** (object ids are per-dump), children as
  sorted 128-bit digests. Stable across runs, JVMs and dumps; this is what
  the compare tab diffs. Hash function: XXH3-128 with a fixed zero seed,
  streamed through hash4j's `HashStream128` using little-endian primitives
  and length-delimited UTF-8 strings; never `Object.hashCode`.

## Pipeline (one SHAPES job, one JVM)

Extraction reads the HPROF file directly: a parallel directory scan, a
parallel heap-index walk (class layouts, GC roots, object addresses), a
parallel record walk applying filtering/concealment/projection from raw
field bytes, then assembly — CSR scatter into unlinked file-backed mappings,
GC-root liveness with a class-statics fixpoint, and leaf folding
(zero-out-degree objects pre-collapse to per-class leaf shapes with exact
aggregates). The analyzer then runs reach → iterative Tarjan → bottom-up
cons → aggregates, and emits the artifact. Shape ids are assigned post-merge
by digest sort → deterministic (two runs produce byte-identical artifacts).
Stage-by-stage detail, measured timings and the memory model live in
`experiments/shapes/README.md` ("HPROF pipeline").

Measured envelope on a 15.6 GB / 260M-object dump: ~150–240 s end-to-end,
≤4 GB physical footprint at `-Xmx1500m` (CSR pages are evictable).

## Correctness invariants (smoke tests)

1. Σ `shallowTotal` over shapes == Σ shallow over R (vs the extractor's
   independent counters).
2. Σ `rootCount` == instance count of the queried class (class scopes only).
3. Σ edge multiplicities (expanded) + concealed-edge counters == total
   projected reference count within R.
4. Every object in R has exactly one shape-id.
5. Determinism: two runs, different thread counts → identical digests.

These run as `--verify` / `--verify-edges` in the CLI; the backend
re-derives invariants 1–3 from the artifact at publication time.

## Open points (iterate here)

1. **Scope**: resolved for the web UI — one whole-heap pass. Reachable class
   scopes remain CLI diagnostics only.
2. **Global vs per-class pass**: resolved — one whole-heap artifact per dump.
   Per-class drill-down from it still needs a persisted object→shape mapping.
3. **Shallow in label**: resolved — OUT (author). `byte[16]` and
   `byte[1MB]` are one shape; size resolution lives in aggregates
   (sum/min/max per shape). Refinements if the loss hurts: array length
   back into the key for array classes only, or log2 size buckets. Each
   re-split multiplies shape count — measure collapse ratio before/after
   on a real dump before adopting.
4. **SCC canonicalization**: color-refinement merged on the WL-fixpoint
   signature (above) — symmetric SCCs merge; large components go through
   the lazy signature. Alternative (never merge SCCs) rejected:
   back-pointer SCCs (child→parent) are common in Gradle heaps and would
   block collapse of everything above them.
5. **Per-instance drill-down** needs a persisted shape-id array
   (zstd, ~800 MB → ~100–200 MB/dump) — v2. Strings/values: attach one
   representative instance per shape later (bounded by unique shapes).
