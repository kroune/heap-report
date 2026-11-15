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
| + all JDK map normalization | 7,821 | 977 |
| + reference filtering (GC semantics) | 7,776 | 976 |
| + relaxed classes (2 analyzer rules) | 7,259 | 947 |
| + field concealment (2 extraction rules) | 6,953 | 877 |
| + set-children rule (config SCC quotient) | 6,880 | 858 |
| + serializer cache concealment (traced owner) | 3,168 | 268 |
| + resolved-closure concealment (`DefaultVisitedArtifactResults`) | 1,713 | 81 |
| + cached resolution-result concealment (2 targeted owners) | **1,656** | **78** |

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

⇒ This is why the pipeline always applies map projection, and why the
normalizer is **field-aware** rather than topology-based: the audit property
is verified for this dataset, not guaranteed in general. Known hole: an
empty `ConcurrentHashMap` with a null `table` but a materialized `keySet`
view still differs from one without. No other container topology matters
after maps — TreeMap/LinkedList/LocalCache chains and lazy views together
account for <30 shapes.

Caveat: the normalization erases insertion order along with collisions, and
insertion order can be meaningful. Don't describe the map effect as "hash
collisions only".

## What the 977 root shapes were (877 after artifact removal, 268 after the children-list collapse, before closure relaxation)

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
The accidents are now removed by the mechanisms in the next section — each
only after its exact merge-blocker was traced. Don't "fix" the collapse to
make the real content go away. The later closure exclusion is instead an
explicit, deliberate loss of semantic resolution.

## Removing the dump-time artifacts: reference filtering + relaxed classes

Two pipeline changes eliminate the state noise above without touching real
content. Both are validated on the run-jfr-38 retained set; per-merge
evidence is in `diagnostics/root-cause-join/` and the code rules in
`experiments/shapes/relax.tsv`.

**Reference-kind filtering (extraction, default on).** `java.lang.ref.Reference`
fields `referent`/`queue`/`next`/`discovered` are VM/library mechanics, not
program structure: a soft/weak/phantom reference does not retain its
referent. Those edges are cut (subclass-declared payload like a weak map
entry's `value` is kept), and the retained scope is restricted to objects
still reachable over the filtered graph. Measured: 18,002 SoftReference
objects, only 2,389 still holding referents (the pre-OOM GC had cleared the
rest); 980,280 objects (31.0 MB shallow) drop out of the scope. Surgical
effect on shapes: the 606 configurations whose soft-referenced cached
resolution results were GC-cleared merge back into their 2,289-strong
population group — nothing else changes (7,821 → 7,776 shapes, 977 → 976
root shapes).

**Relaxed classes (analyzer) + field concealment (extraction), `relax.tsv`.**
Three levels: `class-only` (only the class matters; children stay in scope
but are concealed from the key), `set-children` (child multiplicities → 1),
and `conceal-field:<name>` (one named field of a class + subclasses is
dropped at extraction — the surgical choice for a lazy/cache field whose
*presence* is state). Every rule was added only after tracing the exact
object/field that blocked the merge:

| rule | lazy state concealed | evidence |
| --- | --- | --- |
| `ApacheCommonsHttpClient` class-only | `InternalHttpClient` instantiated only where a resolution really fetched metadata (55 of 81,020 chains) | SHP1 diffs terminate at the present-vs-null client child |
| `ExternalResourceResolver` `conceal-field:cachedArtifactResolver` | lazy create-on-first-download field (Gradle `ExternalResourceResolver.kt`:290), null in cache-served resolutions | field owner found by inbound walk; chains identical otherwise |
| `LocalCache$LocalManualCache` class-only | Guava segment tables/queues initialize on cache writes (12 singleton SCC shapes vs one 38,994-instance shape) | instance accounting in SHP1 |
| `DefaultConfiguration` `conceal-field:description` | UI text; shared descriptions (IDE tooling model) get cut from retained sets and split SCCs by a missing `String×k` | the six alphabetically-first modules' SCCs differ exactly by `String×5/6` |

Measured cumulative effect (map normalization already applied):

| analysis | shapes | root shapes |
| --- | ---: | ---: |
| baseline (raw exact + map normalization) | 7,821 | 977 |
| + reference filtering | 7,776 | 976 |
| + `ApacheCommonsHttpClient` | 7,684 | 975 |
| + `cachedArtifactResolver` concealment | 7,573 | 971 |
| + `LocalCache$LocalManualCache` | 7,259 | 947 |
| + `description` concealment | **6,953** | **877** |

Every step is verified surgical, not just smaller: all f1..f4-impl AND all
six IDE-model modules land **exactly** in their `(tier, deps, closure)`
group in every checked role (50/50 checks, re-verified after every rule),
the content signatures never split ambiguously, and
`kotlinBuildToolsApiClasspath` decomposes as predicted. The relaxed edges
are concealed, not removed: the analyzer counts them exactly
(`--relax-stats`), `--verify-edges` exempts relaxed sources, and the
backend's publication check subtracts the counters.

**The children-list collapse, done surgically.** The remaining differences
were multiplicity, not content — and each was collapsed only after a pair
diff named the exact blocking object and its owner chain was traced in the
heap:

| rule | what is concealed | traced evidence |
| --- | --- | --- |
| `DefaultLegacyConfiguration_Decorated` set-children | the configuration SCC carries 7 identical members per direct project dependency (416+7·ndirect, per-instance heap inspection) — set-children on any SCC member quotients the whole SCC (WL-color quotient) | `implementation` family 12 → 3; ndirect stays visible via the distinct dependency-set members |
| `DeduplicatingComponentSelectorSerializer` class-only | the `writeIndex` HashMap — a per-serialization-pass dedup index over ComponentSelectors (Gradle source: "should not be reused to serialize multiple graphs"); fill level = how the serialization walk proceeded, not what the configuration contains | pair diff `:api:f0-api` vs `:api:f939-api`: the single origin divergence (155+ndirect same-shaped entries); owner chain traced in the SHP1 |

Two JDK-wide shortcuts were REJECTED after tracing: `HashMap$Node[]`
set-children (the only real count-divergence lived in the serializer's
writeIndex — normalizing every HashMap in the heap was a lazy over-broad
shortcut) and `Object[]` set-children (traced to the
`DefaultVisitedArtifactResults` lists — the element count there is the
RESOLVED CLASSPATH CARDINALITY, i.e. resolution content, not dump noise).

Cumulative: 877 → 858 (config SCC quotient) → **268 root shapes / 3,168
shapes** (serializer concealment). Before the deliberate closure exclusion
below, 268 was the content floor: 0 (tier,closure) signature splits, 48
content groups per resolved role, the implementation family at 3
(population + build-logic + app), kotlin at 3 — and every remaining variant
was a module that genuinely resolved or depends-on something different.

## Deliberate resolved-closure exclusion

The 268-shape result preserves resolved content. A later inspection of the
24-member lint-classpath SCCs found 102 byte-identical SCC member graphs,
with identical internal wiring and immediate child classes/multiplicities.
Only the `extendsFrom` `LinkedHashSet` child differed. Same-role pairs led
downstream to `DefaultVisitedArtifactResults`: a closure-25 configuration
had a terminal `Object[]` holding `VariantResolvingArtifactSet ×26`, versus
×25 for closure 24.

For class anatomy, resolved closure cardinality is now deliberately excluded
at that owner with `DefaultVisitedArtifactResults class-only`. This is a
semantic relaxation, not a claim that the content is dump noise. On the same
63.9M-object graph it changes 3,168 → **1,713 shapes**, 268 → **81 root
shapes**, and the displayed SCC family 102 → **10**. Both 48-variant
test-lint roles become three (app, foundation, general population); the
other two lint roles stay at three. All 268 previous root shapes map into
the 81 without splitting, and the implementation family stays at three.
The 109 `DefaultVisitedArtifactResults` shapes become one 39,008-object leaf; its
children remain in scope. Since `class-only` conceals the whole
`artifactsById` subtree from referrer keys, this excludes resolved-artifact
composition as well as cardinality; it is not just list-length
normalization. Object, root, shallow and projected-edge accounting still
matches exactly.

That artifact-list rule leaves two other entrances to cached resolution state:
`DefaultConfiguration.currentResolveState → DefaultResolverResults`, and
`ResolutionResultProviderBackedSelectedArtifactSet.delegate →
DefaultSelectedArtifactSet`. The first carries graph results, the resolved
configuration and selection services; the second carries graph results and
the selected artifact composition. `class-only` on those two owners excludes
both paths without relaxing generic `DefaultCalculatedModelValue` (which
would also hide unresolved/resolved state). Children remain in scope, while
`Optional` and delegate absence/presence remain visible.

On the same graph, these two rules change 1,713 → **1,656 shapes** and 81 →
**78 root shapes**. The exact merges are `:classpath` 5 → 4,
`incomingCatalogForLibs0` 2 → 1, and `kotlinBuildToolsApiClasspath` 3 → 2
(the 2,900-module population and 100-module f0 cohort merge). No previous
root shape splits, and the per-role join still has zero ambiguous
`(tier,ndirect,closure)` signatures. Edge accounting is exact.

This still does not force visually similar SCCs together. In the inspected
five-SCC window (`#1703`, `#10`, `#904`, `#1579`, `#938` in the 1,713-shape
artifact), only `#10` and `#1579` have exactly isomorphic internal graphs.
They remain distinct after closure relaxation because heap field inspection
finds two registered filtered add-actions on `ownDependencies` in one and
none in the other. The other boxes differ by dependency kind and by
Kotlin/action members. Those non-closure distinctions are intentional.

The broader `ExtendedConfigurations class-only` alternative was measured and
rejected: it collapses this family 102 → 1 only by hiding declared
`extendsFrom` structure too, while leaving other closure variants untouched
(162 root shapes overall). Global `Object[] set-children` remains rejected
as over-broad; the exclusion is at the resolution-result owner.

## Multiplicity is the encoding channel, not the origin

92% of the residual shapes are class-level duplicates that differ only in
*which* same-class child shape they point at (853 class-level groups);
merging the bottom-level leaf groups saves only ~150 shapes. Differences
propagate upward through child multiplicities — but they **originate** in
the content and artifacts above.

⇒ Multiplicities stay in the shape key. Array-multiplicity ablations show
the same sensitivity pattern on the reachable scope.

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
