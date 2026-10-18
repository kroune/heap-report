# Diagnostic: structural shapes versus the sampled flow

Measured on `run-jfr-38`, querying
`org.gradle.api.internal.artifacts.configurations.DefaultLegacyConfiguration_Decorated`.
These are diagnostic comparisons, not a decision to change the product's scope
or grouping semantics. Do not interpret a smaller node count as a correctness
proof.

## Controlled same-input comparison

The stored 32-root anatomy extraction has 11,870 objects in 243 classes.
Reconstructed its **exact flow adjacency** using `db.anat_src` and
`reach._build_adj`, not by approximating the field parser:

| Input / grouping | Objects reached | Edges | Output nodes |
| --- | ---: | ---: | ---: |
| Flow's retained sample, named instance refs / indexed array refs | 11,870 | 18,440 | 243 classes |
| Same objects, same roots, same adjacency; shapes, SCC merge cap 1M | 11,870 | 18,440 | 416 shapes |
| Same objects and roots; indexed refs restricted to sample | 11,870 | 17,932 | 415 shapes |

Both shape runs passed `--verify-edges`. The difference between named and
indexed refs is measured, not assumed harmless. The MAT outbound index
suppresses repeated references to the same target object, whereas named fields
can retain those repetitions.

An earlier scratch exporter incorrectly kept only the first in-set edge for
some objects (`if oid not in edges` inside the per-edge loop). Its results
(66 reachable objects; 586 shapes with every object seeded) are **invalid and
superseded** by the controlled comparison above.

## Grouping is not the same operation

Flow's backend uses node key `class` and link key
`(source class, field name, target class)` in `payloads._anatomy_build`.
Shapes use recursive child-shape equality; SCC members, including members of
different classes, share a component shape. Thus 416 shapes are not simply
416 subdivisions of 243 class nodes.

On the controlled input:

- 4,844 objects belong to multi-member SCCs; 88 classes have such members.
- Counting `(object class, shape)` memberships yields 921 distinct pairs.
- All 32 root objects are in SCCs, in 16 root-shape variants.
- The 44 `DefaultProperty` objects occupy six shapes. One representative,
  MAT id 11874259, references `ValueState$NonFinalizedValue` shape 256 and
  `Providers$FixedValueProvider` shape 74. MAT id 1794545 references the same
  state shape 256 but supplier shape 234. Shape 74 has no in-set children;
  shape 234 references `DefaultMutableAttributeContainer`. Flow aggregates
  both into the same `DefaultProperty` class node.
- 456 sampled `HashMap` objects have no outgoing refs **inside this retained
  set**. This does not prove that they are physically empty maps.

Re-aggregating this exact sample adjacency by flow's keys reproduced the
stored flow payload **exactly**: 243 class nodes with identical object counts,
438 `(source class, field, target class)` links with identical counts, and
18,440 references. This demonstrates that class aggregation can retain exact
totals without requiring structurally identical members.

A separate diagnostic refines object groups on the original cyclic sample
graph, starting with class labels and splitting by outgoing child-group sets.
No SCCs are contracted. The previous group ID is included in each next key,
so refinement is monotone; tuple equality, not probabilistic digests, decides
groups. Ignoring multiplicity entirely gives this stable group-count trace:

```text
243 -> 342 -> 530 -> 634 -> 719 -> 812 -> 1066 -> 1345 -> 1591
    -> 1788 -> 1907 -> 1969 -> 1989 -> 1992 -> 1994 -> 1995 -> 1995
```

Retaining multiplicity stabilizes at 2,066 groups. The set-only result has
561 `LinkedHashMap$Entry` groups, 165 `HashMap$Node[]` groups, 158
`LinkedHashMap` groups, and 156 `LinkedHashSet` groups. Thus even the sample
is not structurally uniform after ignoring multiplicity. This is **not** a
replacement algorithm proposal, nor directly comparable to 416 SCC shapes:
the former retains one class per group and follows cycles, whereas the latter
contracts entire SCCs, including members of different classes.

Two concrete array witnesses from the same sample:

- MAT object IDs 76897977 / 26319137 / 30734153 are `Object[]` shapes
  343 / 179 / 158, with respectively 1 / 3 / 5 references to the same
  `MapCollectors$SingleEntry` shape 329.
- MAT IDs 228081295 / 218306971 / 259506498 are `Object[]` shapes
  147 / 295 / 224, with 1 / 2 / 3 references to the same
  `VariantResolvingArtifactSet` shape 0.

These witnesses establish specific count-only splits; they do not justify
attributing every other split to collection size.

The flow layout additionally defaults to top 140 classes. With no pins or
minimum-retained filter, its measured input/output is 243 input class nodes /
438 field links -> 140 layout nodes / 221 rendered edges. Setting top=243
retains 243 layout nodes / 381 rendered edges. Nesting and reverse-edge
pairing also differ from the shape-DAG renderer.

## Scope changes dwarf sampling count changes

On the clean reachable GRPH, selecting 32 evenly spaced root instances still
reaches **all 256,892,895 objects**, exactly as selecting all 327,035 roots.
That run yielded 85,303 shapes with the default SCC merge cap 64.
The cap-1M full-root run yields 62,414 shapes.

All 327,035 roots belong to one SCC of 139,827,424 objects. Diagnostic
inspection counted 3,249 distinct classes inside that single SCC. Its members
include 7,404,720 `ValueState$NonFinalizedValue`, 3,486,701 `DefaultProperty`,
1,644,487 `DefaultCollectionEventRegister` and 402,087
`DefaultDependencySet` objects. Their class structure is not visible in the
SHP1 representation as separate nodes. In particular, projecting SHP1 labels
back to classes **cannot recover** the full class-level flow graph: the SCC
class composition and internal class/field edges have already been discarded.

The existing all-root MAT retained-set histogram reports **65,348,238 objects,
2,261,575,128 shallow bytes, 410 classes**. A controlled all-root retained
extraction/analysis is the next experiment. Its result must not be inferred
from the 32-root sample.

The first standalone retained extraction failed before marking: MAT's default
`ObjectMarkerOld` initializes `Platform.inDebugMode()`, which requires an
OSGi bundle context. This is not a memory failure. The retry uses MAT's own
factory switch
`-Dorg.eclipse.mat.parser.internal.snapshot.ObjectMarkerFactory.useOldMarker=false`
to select `ObjectMarker`. Factory initialization was checked separately.
Retained scope must still be validated against the existing histogram before
accepting the comparison. No custom retained-set implementation is substituted.

The retry successfully extracted the complete set:

- MAT `getRetainedSet`: 65,348,238 objects (matches stored histogram).
- Excluding class objects: 65,348,236 objects, 2,261,575,128 shallow bytes.
- Distinct class names: 406. The histogram has 410 rows but only 407 names:
  three Kotlin class names each occur twice (different loader identities).
  Its `java.lang.Class` row contains the two excluded objects, zero shallow
  bytes. Grouping by name and removing this row explains the class-count gap.
- In-scope edges: 88,997,412; non-class references to outside the retained
  set: 74,303,947 (counted and omitted from this scope, not capped).
- GRPH file: 1,401,591,117 bytes. Full retained collapse is measured separately.

## Array multiplicity ablation on the full cap-1M shape DAG

This is a diagnostic re-interning pass, not an alternative production mode.
SCC shapes stay distinct opaque atoms; no cyclic equality is inferred.
Baseline re-interning reproduced all 62,414 shapes and found no duplicate
emitted LEAF/NODE keys.

| NODE key change | Total shapes | NODE shapes |
| --- | ---: | ---: |
| None | 62,414 | 59,390 |
| Ignore counts only for arrays with one normalized child shape | 36,520 | 33,496 |
| Divide array counts by their GCD (preserve proportions) | 36,422 | 33,398 |
| Ignore all array multiplicities, retain set of child shapes | 22,298 | 19,274 |

This proves sensitivity to multiplicities/composition, not physical collection
sizes. In particular, erasing all multiplicities also erases heterogeneous
composition proportions. It is not valid to call the entire reduction a
length-only effect.

The immediate layer above actual LEAF shapes contains 729 NODE shapes in
324 classes. Removing multiplicities only reduces it to 686. Examples of
non-size splits include codec wrappers targeting different leaf codec classes
and `Constructor` instances targeting different generated accessor classes.
These explain that layer; they do **not** by themselves explain the entire
full-heap shape count.


## Full retained result (completed)

The full retained extraction and collapse are now verified against MAT:

- retained set: **65,348,238** objects; after excluding the two
  `java.lang.Class` objects: **65,348,236** objects;
- shallow total: **2,261,575,128 bytes**, exact match to MAT;
- in-scope references: **88,997,412**, exact class-projection and edge
  accounting match;
- full retained exact collapse with `MAX_SCC_MERGE=1,000,000`:
  **234,834 shapes** (`170,715 NODE`, `63,983 SCC`, `136 LEAF`);
- no duplicate emitted plain `(kind,class,child-shape multiset)` keys;
- the largest SCC is only **1,286** objects; the 139.8M reachable mega-SCC
  is absent because the retained scope cuts the shared owner/service closure.

Full retained exact is **234,834 shapes**, not a few hundred. This refutes
scope mismatch as a sufficient explanation for the shape explosion. Sampling does not explain the difference either: running
exact on 32 roots in this same retained GRPH reaches **57,248 objects** and
produces **980 shapes**. The flow payload's 11,870 objects are a different
MAT retained extraction with an object set that is not the full retained set
used by this diagnostic; its 243 class nodes are a class quotient, not a
structural partition.

The root-shape evidence shows why the exact number explodes even after SCC
merge. The retained GRPH has **33,806 distinct root SCC shapes** for the
327,035 queried roots. The 33,806 root shapes reduce to only **100** groups
when child shape IDs are replaced by child class labels (and to 83 by only
child kind/edge-count signatures). The largest group contains 11,909 exact
root shapes, all SCCs of 34 members with the same member-class multiset and
18 outgoing slots. Their exact child labels are almost identical; the
separation comes from different child *shapes*, e.g. alternate
`LinkedHashSet`, `DefaultProperty`, `DefaultCalculatedModelValue`, and other
object-shape IDs at otherwise identical class-level slots.

This identifies the propagation mechanism, **not the originating cause** of
the flow/exact gap. Flow's key `(source class, field, target class)` merges
these roots; exact propagates downstream distinctions to the root SCC.
Explaining why downstream distinctions occur so widely requires tracing
actual unequal shape pairs and controlled ablations, not declaring all
non-duplicate keys meaningful diversity.

A diagnostic re-interning pass over the verified SHP1 confirms the scale:
replacing child shape IDs by child class labels reduces 234,834 to **6,690**
classes/shape-signatures; replacing them by child kind/multiplicity reduces
it to **6,203**. This is not proposed as the production equality rule—it
measures exactly how much of the 234,834 is downstream shape identity.

A different diagnostic still produces **171,448** groups after discarding
SCC internal topology and retaining only member-class composition plus
recursively mapped external edges. This is not an array-length-only
experiment and does not establish a specific cause. In particular, calling
the result "high-entropy content" without identifying the origins would be
circular.

## Independent check: are the generated modules structurally identical?

Parsed all 3,000 included project build files in the adjacent
`feature-module-3000` checkout. Every include resolved to a build file, and
all 3,000 nodes of the literal inter-project dependency graph topologically
processed (no cycles). This is a source-graph diagnostic, not the heap graph.
Each module gets key `sorted multiset(child pattern IDs)`; deliberately ignore
module names, plugin types, dependency configuration names, and JVM container
representations. The exact name-agnostic structural patterns are:

| Group | Modules | Dependency patterns |
| --- | ---: | ---: |
| foundation | 50 | 1 |
| common | 36 | 2 |
| core | 36 | 14 |
| API | 959 | 19 |
| implementation | 959 | 958 |
| UI | 959 | 275 |
| Whole project (including app) | 3,000 | 1,254 |

Thus repeated implementation scaffolding does not imply identical recursive
dependency contents even before entering the JVM. The source metrics record
seed 42 and variable feature dependencies (`fsImplApis=3..5`,
`fsImplCommons=3..6`). These results cannot be directly multiplied into heap
shape counts; a heap same-role comparison is still necessary. They do show
that nearly-unique module dependency patterns are not necessarily a collapse
implementation bug: already-collapsed child types can combine into distinct
multisets. The foundation control does collapse 50 nodes into one pattern.

Holding dependency count fixed does not eliminate the source-graph variety:
252 implementation modules each have exactly ten literal project dependencies
but yield 252 patterns; 248 modules each have nine dependencies yield 247.
Starting with every module the same color, bounded-depth signatures are:

| Depth | API patterns | Implementation patterns | UI patterns |
| ---: | ---: | ---: | ---: |
| 0 (all modules identical) | 1 | 1 | 1 |
| 1 (dependency count) | 3 | 6 | 2 |
| 2 (multiset of child dependency counts) | 19 | 438 | 28 |
| 3 | 19 | 958 | 275 |
| 4 | 19 | 958 | 275 |

This identifies a source of *logical* variation without relying on names,
hash collisions, or collection implementation: differing combinations of
small child-degree patterns become almost unique at depth three. It is an
independent source-graph observation and must not be used to explain the
234,834 raw heap shapes; the map-topology ablation, not this project-graph
count, measures the dominant heap effect.

## Tracing the origin (ongoing)

Root shapes 0 and 29 in `/tmp/dlc-retained.shp1` have identical SCC labels,
34 members, 56 internal edges, and identical external edges except one.
Re-analysis of the GRPH reproduced these final shape IDs and located actual
root-class representatives at dense retained IDs 20,545,772 and 23,483,187,
respectively (these are **not MAT IDs**). The field-level follow-up remaps
them through the same sorted MAT retained ID list before reading the dump.
This recovered MAT IDs **98,697,886** (shape 0) and **112,481,841** (shape 29):

```text
root SCC 0/29
  -> LinkedHashSet 216412/46150
  -> LinkedHashMap 166795/170359
  -> 2-entry SCC 98058/167666
  -> RealizedExtendedConfiguration wrappers
  -> other configuration SCCs
```

`ExtendedConfigurations.java` in the checked-out Gradle source confirms that
these wrappers hold `configuration` and live in a `LinkedHashSet`; the class
does not override equals/hashCode. The wrapper leads into another root's
configuration structure. Thus this pair does not terminate in a leaf-size
variation; it follows configuration inheritance.

Field reads of the recovered objects show that this particular pair compares
**different configuration roles**, not equivalent modules: shape 0's object is
`debugUnitTestLintChecksClasspath`, while shape 29's is
`releaseLintChecksClasspath`. Both have two immediate parents, but those are
respectively test compile/runtime and release compile/runtime configurations.
This is a genuine source of local variation but does **not** explain the
number of variants within one repeated role. A population join of all root
shape IDs with the real configuration `name` field is now complete; names
are metadata for this diagnostic, not a new equality-key field.

All 327,035 roots were read by sorted MAT ID and joined to sorted dense
retained root IDs. Both earlier MAT-ID witnesses match as cross-checks.
There are 122 configuration names and 90,102 `(name,shape)` memberships
(the same SCC shape can contain several root names). In particular:

| Same actual configuration name | Objects | Raw shape variants | SCC member count | Number of observed state patterns |
| --- | ---: | ---: | ---: | ---: |
| `releaseCompileClasspath` | 3,000 | 3,000 | 33 | 1 |
| `releaseLintChecksClasspath` | 3,000 | 3,000 | 34 | 1 |
| `debugUnitTestLintChecksClasspath` | 3,000 | 3,000 | 34 | 1 |
| `debugUnitTestRuntimeClasspath` | 3,000 | 3,000 | 120 | 1 |
| `debugUnitTestCompileClasspath` | 3,000 | 2,967 | 243 | 1 |
| `debugCompileClasspath` | 3,000 | 2,930 | 110 | 1 |
| `archives` | 3,001 | 1 | 21 | 1 |
| `androidLintTool` | 3,000 | 1 | 42 | 1 |

State patterns here mean only the five read flags: canBeResolved,
canBeConsumed, resolutionStrategy non-null, allDependencies non-null,
allArtifacts non-null. They are not proof that every field agrees.

A same-role `releaseLintChecksClasspath` pair (MAT IDs 1,101,345 and
1,203,333, shapes 163487/91867) has the same SCC class composition, 34
members and 56 internal edges, with just one differing external child (the
extendsFrom set). Its two-entry linked-map table references **one versus
two distinct bucket heads**. This is a direct actual-heap collision witness
at fixed logical entry count, although its downstream configuration targets
also differ and require the full normalization control. A second same-role
`debugCompileClasspath` pair differs only through
`DefaultCalculatedModelValue -> Optional -> ResolverResults -> ... ->
DeduplicatingComponentSelectorSerializer`, leading into resolution maps.
The same-role population after normalization was then measured by re-running
the transform with an object-to-final-shape mapping. Sorted dense root IDs
match the original population exactly (327,035 roots); names are metadata
only and never enter the merge key.

| Same role (3,000 objects each) | Raw variants | Normalized map variants |
| --- | ---: | ---: |
| `releaseCompileClasspath` | 3,000 | **17** |
| `releaseLintChecksClasspath` | 3,000 | **17** |
| `debugUnitTestLintChecksClasspath` | 3,000 | **192** |
| `debugUnitTestRuntimeClasspath` | 3,000 | **192** |
| `debugUnitTestCompileClasspath` | 2,967 | **21** |
| `debugAndroidTestCompileClasspath` | 2,959 | **21** |
| `debugCompileClasspath` | 2,930 | **17** |
| `implementation` | 1,441 | **17** |
| `api` | 18 | **3** |

Across all 122 names, distinct `(name,shape)` memberships fall from 90,102
to 2,181. Thirty-two names have exactly one normalized pattern; 219,022
objects belong to names with at most three patterns. These memberships are
not unique shape counts: an SCC may contain roots of several names.
The result isolates the map-topology contribution **within the same roles**,
not merely a change in the role mix. Residual 17/21/192 variants require
further tracing and are not automatically explained by module diversity,
array size, or lazy state.

A first residual inspection finds that all 3,000 normalized
`releaseLintChecksClasspath` objects still have the same 34-member SCC class
composition and 56 internal edges; their 17 variants differ through the
`extendsFrom` set. The corresponding release compile role has identical
variant population sizes (815, 460, 370, 341, ...), and the main
`implementation` role shares those populations but has multiple SCC sizes.
This suggests propagation of a small set of configuration/dependency
patterns; it is not proof of the originating fields.

The normalized test-lint residual (192 variants) also has constant root SCC
size/composition. In one selected pair, its two `extendsFrom` children refer
to compile/runtime SCCs of sizes 239 and 116 with the assignments exchanged.
The 239-member component contains two extra configuration objects plus
eleven extra dependency-handler lambdas, element-source elements, and related
objects. These are concrete residual component differences, not an automatic
proof of application-state diversity; mapping them to actual fields/roles is
needed before proposing further collapse changes.

A controlled cut of only
wrapper-to-configuration edges will quantify this channel.

A second candidate origin is physical map topology: bucket collisions are
visible in `HashMap$Node[]` and entry `next` links despite identical logical
entry contents. A standalone synthetic example with the same four entry
classes and insertion-order links, but an extra bucket `next` edge in one
map, produces separate Set/Map/Table/SCC shapes. This establishes a possible
mechanism, not its population impact. A diagnostic transformation measuring
that impact is run separately; it keeps node/class/shallow data while
removing entry-to-entry links and normalizing tables to unordered entries.
It intentionally changes graph edges and is not a production change.

Actual heap witnesses already establish at least two physical-topology
splits: entry SCCs 39295/172733 have eight members, identical external child
shape totals, but 16/14 internal edges; 104276/116171 likewise have eight
members and identical external child totals but 14/17 internal edges.
Only six entry SCC shapes fall into same-size/same-external-total duplicate
groups before normalization. This alone does **not** explain the whole graph;
different entry order and recursively varying configuration targets must be
measured, not conflated with this narrow witness.

### Physical linked-map topology ablation

A diagnostic graph transform removes entry-to-entry refs and map head/tail
refs, and makes a table enumerate every entry in its insertion-list closure.
All retained objects and shallow bytes survive. This changes references and
is NOT an exact-reference-count preserving production mode. Same-class
entry-valued user data would also need field-level disambiguation before
considering such a transformation as a real semantic projection.

| Diagnostic | Shapes | Root shapes | Objects | Shallow bytes |
| --- | ---: | ---: | ---: | ---: |
| Original full retained | 234,834 | 33,806 | 65,348,236 | 2,261,575,128 |
| Normalize linked-map physical topology | 176,244 | 16,602 | 65,348,236 | 2,261,575,128 |
| Cut only realized-extends wrapper -> configuration | 160,131 | 40,508 | 65,348,236 | 2,261,575,128 |
| Normalize linked maps + cut realized-extends -> configuration | 131,009 | 14,573 | 65,348,236 | 2,261,575,128 |
| Normalize LinkedHashMap **and ordinary HashMap** (no inheritance cut) | **7,821** | **977** | 65,348,236 | 2,261,575,128 |

The map transform removes 58,590 shapes and 17,204 root variants. This is
causal evidence of physical container-topology sensitivity, not a claim that
every removed variant originated solely in hash collisions (entry order is
also erased). The inheritance-edge cut has a mixed result: total shapes
fall, but root variants **increase**, because it also changes SCC boundaries.
It does not establish that inheritance alone explains the original root
variant count. Do not summarize that experiment as a monotone reduction.

The successful linked-map diagnostic changed 3,158,083 objects' adjacency:
88,997,412 original refs -> 84,522,580 transformed refs (4,690,498 removed,
215,666 added). Reachability remained exactly 65,348,236 objects. Therefore
its count reduction is not caused by objects dropping out of the scope.
It removes before/after, next, map head/tail distinctions together and is a
physical-representation ablation, not a collision-only measurement.
The combined transform alters 3,380,076 objects' adjacency, emits 84,300,587
refs, and retains all 327,035 roots. Even in combination these changes leave
131,009 shapes; they are not a complete explanation. Ordinary `HashMap`
chains, untouched by this transform, are investigated separately.

### Controlled real JDK HashMap experiment: same size, same element shapes

Built 3,000 actual JDK `HashMap<String, Value>` instances at each fixed size,
seeded random String keys. Converted the real JDK table and `next` links into
`ObjectGraph`; String values are reduced to the same `String -> byte[]`
shape, and all values share the same leaf shape. Thus no shape-level key
content or collection-size difference can explain the map variants.

| Entries in **each** map | Exact map variants | Distinct chain-length histograms | Variants after flattening buckets |
| ---: | ---: | ---: | ---: |
| 2 | 2 | 2 | 1 |
| 10 | 19 | 19 | 1 |
| 30 | 68 | 68 | 1 |
| 100 | 213 | 213 | 1 |
| 200 | 450 | 450 | 1 |

The equality of variant count and bucket-chain-histogram count establishes
this specific origin: **key values, excluded from the structural key, still
influence `hashCode`, and hence physical topology, indirectly**. Ignoring
String/primitive values is not sufficient to make raw collection graphs
value-insensitive. This controlled result does not quantify the entire heap
contribution; ordinary `HashMap` normalization must be measured on the dump
separately from the preceding `LinkedHashMap` experiment.

That heap experiment completed: normalizing ordinary HashMap chains in
addition to linked maps reduces **234,834 -> 7,821 total shapes** and
**33,806 -> 977 root shapes**, with all 65,348,236 objects, 327,035 roots, and
2,261,575,128 shallow bytes retained. The inheritance links are not cut.
Relative to normalizing linked maps alone, this is **176,244 -> 7,821**
shapes. This is the largest measured effect; attributing that 176k residue
to application diversity was incorrect.

The transform changes 4,513,023 objects' adjacency; 88,997,412 original refs
become 84,522,580 refs (6,020,196 removed, 1,545,364 added by the two-pass
per-object count delta). It removes ordinary same-class node links and
normalizes tables to their entry closure; exact numbers of original raw
references would need to be aggregated separately in a production semantic
projection. The exhaustive named-field check over the original retained set completed:
**9,832,688 map/entry objects**, **6,713,120 candidate named references**,
**zero wrongly removed named references**. Every candidate was one of:

| Field | Named references |
| --- | ---: |
| `HashMap$Node.next` | 1,329,698 |
| `LinkedHashMap$Entry.after` | 1,494,476 |
| `LinkedHashMap$Entry.before` | 1,494,476 |
| `LinkedHashMap$Entry.next` | 215,666 |
| `LinkedHashMap.head` | 1,089,402 |
| `LinkedHashMap.tail` | 1,089,402 |

No `key` or `value` reference matched the diagnostic removal criterion in
this retained set. The named-reference count is not the removed index-edge
count: MAT deduplicates targets and normalization also adds table-to-entry
refs. This resolves the same-class key/value caveat **for this dataset**;
a production normalizer must still use explicit field/container semantics
rather than assume the same property holds for other dumps.

### Controlled real JDK LinkedHashMap experiment

Created 3,000 actual `LinkedHashMap` instances, each containing exactly twenty
entries with leaf-key class histogram `A:5, B:5, C:5, D:5` and the same value
shape. The generated keys use default identity hash codes; insertion order is
shuffled with a fixed random seed. Reflection exports the actual JDK table,
head/tail, next, before/after links to `ObjectGraph`.

- Raw physical graph: **3,000 map shapes**, **9,005 total shapes**.
- Unordered entry representation: **1 map shape**, **11 total shapes**.

This demonstrates how all logical contents can match by shape and size,
yet physical container graphs split almost per instance. It does not isolate
insertion order from hashing: both change in this control. An additional
factorial control retains the same generated maps and separately exports
raw, order-only, buckets-only, and unordered-entry graphs. It compares fixed
key-class order, shuffled class order, and homogeneous keys. Each dataset has
3,000 maps of twenty entries and identical class histograms within its row.
Default identity hashes differ between key instances; datasets do not share
objects, but the four projections within a dataset do.

| Key-class sequence | Raw map variants | Order links only, all entries in table | Bucket links only | Unordered entries |
| --- | ---: | ---: | ---: | ---: |
| Fixed `ABCD` repeated five times | 2,995 | 1 | 2,864 | 1 |
| Shuffled `A:5 B:5 C:5 D:5` | 3,000 | 3,000 | 2,849 | 1 |
| Twenty keys of the **same leaf class A** | 2,999 | 1 | 53 | 1 |

All four projections in each dataset reach the same 126,001 objects.
The homogeneous-key row is especially informative: neither size, class
composition, nor key-class insertion order varies. Raw structure is still
almost per-instance unique, whereas order alone gives one pattern and
buckets alone give only 53 patterns. The combined graph ties each bucket
chain to particular positions on the insertion-order path, eliminating
symmetries that each projection has separately. Therefore physical entropy
is not merely the number of distinct bucket-length histograms; the
**interaction of bucket wiring and insertion-order wiring** is enough to
produce near-unique shapes even with identical leaf shapes everywhere.

The shuffled row additionally demonstrates that retaining insertion order
is itself a semantic choice: twenty entries with the same type histogram
are not ordered-structurally equal when their type sequence changes.
The physical normalization used in the heap ablation removes both factors;
its entire effect must not be labelled "hash collision only."

One important scope lesson: `SELECT AS RETAINED SET` is not distributive over
root subsets. Thirty-two roots in the all-root retained graph reach 57,248
objects, while the union retained set of those same sampled roots contains
11,870. Shared objects retained collectively by all roots need not be
retained by a sample subset. This difference must not be described merely
as sample-size error.
