# HPROF vs MAT source benchmark

## Method

- Dump: `run-jfr-38/daemon.hprof`, 15.8 GB, 260,925,589 physical objects.
- MAT's cleaned snapshot: 259,806,476 objects (1,119,114 GC-unreachable removed).
- Scope: all `java.lang.String` instances, reachable closure.
- Result: 8,001,125 roots, 15,831,198 objects, 8,001,125 references, two shapes.
- JVM: 2 GB heap, JFR `settings=profile`, one analysis per fresh JVM.
- Variants: preserved legacy-MAT fat jar, current jar with `--heap-source mat`,
  and the same current jar with `--heap-source hprof`.
- Warm comparisons repeat direct HPROF immediately. The MAT control is also
  reversed to expose page-cache bias. Cold first-touch runs are not used in the
  median warm comparison.

The shared rewrite is not just a source swap: it caches `classId` once per
filter operation, uses primitive-int class lookup maps/sets, and adds the
liveness contract dispatch. HPROF-only changes include sequential parsing,
address indexing, compact class scans and reference-list sizing.

## Correctness gates

- Direct GC liveness removes exactly 1,119,114 objects, matching MAT. The
  remaining one-object difference in final object counts is MAT's synthetic
  object, not an HPROF object.
- String extraction statistics are identical between MAT and HPROF.
- String SHP1 artifacts are byte-identical:
  `3943c92f23bfdb67bd070825cb2071bc666dd39947c20e631a99e67bbe5b7c26`.
- Retained `java.io.File` scope opens MAT only at the retained-set boundary.
  Both sources produce 2,907,654 objects, 1,011,074 roots and 1,898,187 refs.
- Retained File extraction statistics and SHP1 artifacts are also identical:
  `7f9c0d20aa3c3e5867b3d73e57e0b24d435e7ad6e50a14f595ae08d66d4f469a`.
- `./gradlew test` passes, including binary HPROF fixtures for inheritance,
  compressed references, unsorted addresses, reverse address lookup, liveness,
  shallow sizes and cache reopening.

## Warm results

Each cell is the median of two JFR-recorded runs. Extraction includes scope
selection and graph materialization; analysis is the subsequent structural
collapse. Times are seconds.

| variant | extraction | analysis | total wall |
|---|---:|---:|---:|
| legacy MAT | 28.497 | 1.328 | 31.407 |
| current MAT (shared rewrite control) | 26.607 | 1.315 | 29.455 |
| current direct HPROF | 45.734 | 1.503 | 48.209 |

The two direct-HPROF wall times were 43.682 and 52.736, showing substantial
I/O variability. Even the better run was 14.227 s (48.3%) slower than the
current-MAT median. The first-touch direct run took 123.542 s and is excluded
from warm medians.

### Rewrite vs source decomposition

- **Shared rewrite, legacy MAT → current MAT:** 1.952 s faster, **6.2%**
  (31.407 → 29.455). The reverse ordering differed by only 0.847 s; the larger
  first-order difference was mostly MAT page-cache warmth.
- **MAT → direct HPROF, on the same current pipeline:** 18.754 s slower,
  **63.7%** (29.455 → 48.209; 1.637× wall time).
- **Net legacy MAT → direct HPROF:** 16.802 s slower, **53.5%**. The shared
  rewrite offsets 1.952 s (10.4%) of the source regression; it is not the cause
  of the slowdown.

Extraction alone is 19.127 s (71.9%) slower after the source change. Analysis
is sub-second-scale variation by comparison and does not change the conclusion.

## JFR findings

For the better warm HPROF recording, execution samples include:

- `HprofHeapObjectSource.resolveObjectAddress`: **38.06%**;
- `IntArrayList.grow`: **14.39%**;
- `HprofHeapObjectSource.readReferences`: **9.16%**;
- `BitSet.get`: **7.85%**.

Current MAT's leading entries are `HashMapIntObject.containsKey` (17.35%),
`HeapReferenceFilter.read` (7.50%) and MAT index page lookup (6.02%). Direct
HPROF does reduce MAT class/index resolution, but replaces it with more
expensive address→source-id resolution and HPROF field decoding.

JFR file-read events over its threshold show 11.6 GB from MAT's outbound index
and 2.6 GB from `o2c.index`, versus only 3.8 MB in the warm HPROF recording.
That does **not** make HPROF faster: many HPROF positional reads fall below the
JFR event threshold, while address interpolation and field parsing consume CPU.
Direct wall time and variability are dominated by the selected access pattern.

Two attempted class-root sizing changes were reverted: pre-counting per query
and caching per-class counts both moved work elsewhere without improving total
time. They are not present in the final implementation.

## Cold costs and cache size

- HPROF sequential scans: 144.4 s + 149.4 s.
- Direct GC liveness: 540.4 s.
- Valid cold extraction and analysis: 112.9 s + 1.5 s.
- Approximate complete first-use total including address sorting: 16 minutes,
  comparable to MAT's logged 15 m 19 s index build. It is not a cold-start win.
- Direct cache: about 5.9 GiB including addresses, record offsets, class ids,
  GC roots and liveness. MAT's uncompressed indexes occupy about 18 GiB.

## Conclusion

The direct implementation is functionally correct and keeps MAT only for late
retained-set queries and diagnostics, but the original performance hypothesis
is **not supported for this random reachable-class workload**. Shared rewrite
improvements are small and beneficial; the MAT→HPROF source change accounts for
the regression. Whole-heap sequential extraction may have a different profile,
but it was not measured here and should not be inferred from these results.
