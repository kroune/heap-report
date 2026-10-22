# Flow vs. exact structural shapes

Context for anyone (human or AI) working on the shapes pipeline: what the
comparison with the product's flow view established, and which design
decisions it justifies. Measured on `run-jfr-38` (15.8 GB Gradle daemon
hprof, 259.8M objects; queried class
`org.gradle.api.internal.artifacts.configurations.DefaultLegacyConfiguration_Decorated`,
327,035 instances).

This is a conclusions file, not a measurement log. Full history: git.
Re-runnable diagnostics: `diagnostics/`.

## The two groupings

- **Flow** (product backend): objects grouped **by class**; links keyed
  `(source class, field, target class)`; input is a sampled retained set;
  the UI then renders only the top-N classes (243 nodes → 140 by default).
- **Exact shapes** (this experiment): two objects are equal iff same class +
  same multiset of child shapes, recursively; SCCs collapse as units, even
  across classes.

On identical input (the flow's own 32-root sample, 11,870 objects) shapes
give **416** groups vs the flow's **243** class nodes — a small, explainable
delta, not an explosion: SCCs merge members of different classes (921
distinct `(class, shape)` pairs exist), and shapes preserve reference
multiplicities that MAT's outbound index deduplicates. Re-aggregating the
same adjacency by flow keys reproduces the stored flow payload exactly, so
both views are internally consistent.

## Headline numbers

Full retained set: 65,348,238 objects / 2,261,575,128 shallow bytes —
verified to match MAT's histogram exactly.

| Analysis | Shapes | Root shapes (of 327,035) |
| --- | ---: | ---: |
| raw exact (SCC merge cap 1M) | 234,834 | 33,806 |
| + linked-map normalization | 176,244 | 16,602 |
| + all JDK map normalization | **7,821** | **977** |
| + set semantics (multiplicities → 1) | 1,811 | 139 |

## Why raw exact shapes explode: physical map topology

Nearly every map instance is structurally unique because hash-bucket
collisions and insertion-order wiring are part of the graph. Controlled
proof: 3,000 real JDK `LinkedHashMap`s with identical logical contents give
3,000 distinct raw shapes — with homogeneous keys, bucket wiring alone gives
53 patterns and order wiring alone gives 1, yet combined they give 2,999.
The entropy is the **interaction** of the two wirings. For `HashMap`, the
variant count at fixed size equals the number of distinct bucket-chain
histograms: key values influence `hashCode` even when excluded from the
structural key, so ignoring String/primitive values does not make raw
collection graphs value-insensitive.

Normalizing JDK map topology on the real heap removes 97% of the shapes
(234,834 → 7,821) with every object, root and shallow byte retained. A
field-level audit confirmed only map wiring (`next`/`before`/`after`/
`head`/`tail`) was removed — zero `key`/`value` references.

⇒ This is why the pipeline defaults to `--normalize-maps`, and why the
normalizer is **field-aware** rather than topology-based: the audit property
is verified for this dataset, not guaranteed in general. Known hole: an
empty `ConcurrentHashMap` with a null `table` but a materialized `keySet`
view still differs from one without. No other container topology matters
after maps — TreeMap/LinkedList/LocalCache chains and lazy views together
account for <30 shapes.

Caveat: the normalization erases insertion order along with collisions, and
insertion order can be meaningful. Don't describe the map effect as "hash
collisions only".

## What the remaining 977 root shapes are

Across 122 configuration roles the residual decomposes exactly — real
content plus three dump-time artifacts, zero unexplained among 2,989
non-special modules:

- **Declared dependencies.** Each `project(...)` dependency adds exactly 7
  members to the configuration's root SCC (provider-lambda capture chain);
  the 11 distinct per-module dependency counts produce the 11 content
  shapes in `implementation`-family roles.
- **Resolved classpaths.** Test runtime classpaths retain resolution
  results; their shape is determined by `(tier, direct deps, transitive
  closure size)` — 181 content shapes. DAG-internal wiring beyond those
  counts is provably invisible there.
- **Dump-time artifacts** (the sync OOM'd mid-model-build): (1) the six
  alphabetically-first modules share configuration `description` strings
  with IDEA tooling-model objects, which cuts those strings from their
  retained sets; (2) 55 of 81,020 resolver chains hold an instantiated HTTP
  client — cold cache at first resolutions; (3) the pre-OOM GC cleared soft
  references to cached resolution results for 606 modules. Each artifact
  splits a handful of modules in otherwise uniform roles.

Typical per-role arithmetic: test runtime classpaths 192 = 181 content +
6 IDE + 4 HTTP + 1 app; main classpaths 17 = 11 + 5 + 0 + 1; 32 roles are
perfectly uniform even in the raw graph.

Cross-checks that the residual is genuine content, not a collapse bug: the
source project itself has 1,254 name-agnostic dependency patterns (958 of
959 implementation-tier modules are pairwise distinct), and the 1,059
immutable-attribute objects carry only 41 distinct `(name, value-class)`
combinations.

Lesson: variant counts this small are real content plus dump accidents.
Don't "fix" the collapse to make them go away.

## Multiplicity is the encoding channel, not the origin

92% of the residual shapes are class-level duplicates that differ only in
*which* same-class child shape they point at (853 class-level groups);
merging the bottom-level leaf groups saves only ~150 shapes. Differences
propagate upward through child multiplicities — but they **originate** in
the content and artifacts above.

⇒ Multiplicities stay in the shape key. Set semantics (all multiplicities
→ 1) collapses the graph to 1,811 shapes / 139 root shapes, but at the cost
of merging real composition differences (e.g. attribute payloads) — it is a
diagnostic view, not the default. Array-multiplicity ablations show the same
sensitivity pattern on the reachable scope.

(Side measurement: refs leaving the retained set create per-class LEAF/NODE
noise, but materializing them as boundary stubs adds as many distinctions as
it removes — not worth a feature.)

## Scope lessons

- **Reachable scope is useless for service-graph classes**: 32 roots and
  all 327,035 roots close over the same 256.9M objects (98.9% of the heap)
  through one 139.8M-object mega-SCC. ⇒ Retained scope is the default. (In
  retained scope the largest SCC is only 1,286 objects.)
- **Retained sets are not distributive**: 32 roots *inside* the all-roots
  retained graph reach 57,248 objects, but the union retained set of those
  same 32 roots is 11,870. Never compare a sampled-retained flow payload
  with a full-retained shape analysis as if they were the same scope.
- **A class-level quotient of SHP1 cannot reproduce the flow graph**: SCCs
  mix classes, and the collapse discards internal class/field edges.

## Pointers

- `diagnostics/container-topology/` — JDK HashMap/LinkedHashMap controls +
  the immutable-attribute audit.
- `diagnostics/root-cause-join/` — evidence behind the per-role
  decomposition (joins heap roots with module signatures).
- Full measurement log with all intermediate hypotheses: git history of
  this file.
