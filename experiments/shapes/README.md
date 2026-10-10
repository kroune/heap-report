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

## Known limitations / notes for review

- `Edge`/`Shape` graphs are interned and acyclic (DAG + SCC payloads), so
  structural equality via interned identity is exact and O(1).
- `SCC_UNIQUE` digests are per-input deterministic (salt = min member id)
  but not cross-dump stable — acceptable: they never merge, so cross-dump
  diff has nothing to compare anyway.
- Text graph loader requires dense 0-based ids.
