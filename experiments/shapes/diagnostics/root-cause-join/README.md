# root-cause-join — why 977 root shapes for 122 configuration roles

Standalone diagnostics behind the root-cause conclusions in
[`experiments/shapes/FLOW-COMPARISON.md`](../../FLOW-COMPARISON.md). They answer the *origin* question
that the SHP1 ablations cannot: **what exactly differs between two
configurations of the same role, and where does the difference come from.**

Measured on `run-jfr-38`, normalized retained graph
(7,821 shapes / 977 root shapes).

## Result (summary)

Across all 122 roles there is no unexplained variant among the 2,989
non-special modules. The residual decomposes exactly into:

1. **Logical content** — the generator's deliberate per-module dependency
   diversity, via two encodings:
   - declared deps → root-SCC member count (`implementation` SCC =
     423 + 7·ndirect exactly);
   - resolved test classpaths → resolution-result structure, functionally
     determined by `(tier, ndirect, transitive closure size)`
     (196 sigs → 181 shapes, zero sig splits; DAG-internal wiring is
     provably invisible: 151 sigs contain up to 32 distinct sharing
     patterns, none splits a shape).
2. **IDE-model boundary sharing (6 modules)** — `:api:f0-api`,
   `:common:common-0`, `:core:core-1`, `:foundation:foundation-0`,
   `:impl:f0-impl`, `:ui:f0-ui` (plus the already-unique app). The sync
   OOM'd after building `GradleProject` tooling models for exactly the
   alphabetically-first 8 modules; the model's
   `org.jetbrains.plugins.gradle.model.DefaultGradleConfiguration` objects
   share the configurations' `description` Strings, which are therefore
   non-dominated and cut from the retained set → the api-family SCC loses
   `String×5`, the `implementation` SCC `String×6` (only origin difference
   in both diffs).
3. **Cold-cache HTTP state (~10 modules)** — 55 of 81,020 retained
   `MavenMetadataLoader` chains contain an instantiated
   `InternalHttpClient`; 80,965 have it lazily null. Only the first
   resolutions of the cold sync fetched metadata over HTTP.
   Splits `:impl:f1-impl..f4-impl` in resolved-classpath roles and
   5 more modules in `kotlinBuildToolsApiClasspath`.
4. **GC/cache state (`kotlinBuildToolsApiClasspath`, control role with
   identical content)** — 2,289 modules with the soft-referenced cached
   resolution result alive vs 606 with it **cleared by the pre-OOM GC**;
   100 with the resolution-cache id present vs computed form.

Per-role accounting: 192 = 181+6+4+1 (8 roles), 21 = 11+5+4+1 (10),
17 = 11+5+0+1 (16), 3 = 1+1+0+1 (8 api-family), plus identified
cache/HTTP-state variants in the Kotlin/compiler detached configurations.

## Files

- `module_signatures.py [project-root] [out.tsv]` — parses
  `settings.gradle.kts` + all build files → per-module
  `(tier, ndirect, closureSize, libs, WL pattern)`. Reproduces the
  source-graph diagnostic's 1,254 patterns exactly.
- `join_roles.py --module-sigs ... --root-shapes ... --root-names ...
  --root-paths ...` — the join + functional-determinism test + per-role
  decomposition. Prints any ambiguous `(tier,ndirect,closure)` sigs
  (answer: only `kotlinBuildToolsApiClasspath` and
  `kotlinCompilerPluginClasspathDebug`, both cache/HTTP state).
- `diff_shapes.py <shp1> <shapeIdA> <shapeIdB>` — parallel DAG walk of two
  shapes; prints the origin divergences (label/multiplicity differences)
  vs pure propagation. Used for the String×5/×6, InternalHttpClient and
  SoftReference diffs.
- `RootPaths.java` — MAT field read of `identityPath` for all 327,035
  roots (→ `root-paths.tsv`). Run like `RootNames` (see
  `/tmp/run_root_names.py` pattern; needs the shapes fat jar on the
  classpath).
- `SharedDesc.java` — scans every root's `description`; reports those with
  an inbound referrer other than the configuration (→ the 562
  `DefaultGradleConfiguration`-shared descriptions over 8 module models,
  the 3,002 uniform `androidApis`↔`Object[]` sharings, and the 39,003
  uniform intra-module config↔config sharings).
- `RetainedIds.java` — recomputes the retained MAT id array
  (`/tmp/retained-ids.bin`, dense GRPH id ↔ MAT id). Needs
  `-Dorg.eclipse.mat.parser.internal.snapshot.ObjectMarkerFactory.useOldMarker=false`.
- `SccInspect.java <grph> <retained-ids.bin> <denseId...>` — raw-GRPH
  Tarjan SCC dump for a root: member classes and member→`java.lang.String`
  outbound edges (confirmed majority SCC String×5 vs deviant ×0 in the
  raw graph, and the referrers = the 5 api-family configurations).

## Inputs produced outside this directory

- `normalized-root-population.tsv` — 4th argument of `NormalizeAllMaps`
  (dense → final shape id per root).
- `root-names.tsv` — `RootNames.java` (already used by earlier audits).
- `dlc-retained.grph` — `ExtractRetained` (full retained extraction).

These diagnostics do not modify any artifact.

## Relaxation validation (reference filtering + relaxed classes)

The `relax.tsv` rules and the extraction reference filter (see
`backend/mat/SHAPES.md` and `experiments/shapes/FLOW-COMPARISON.md`) were
validated on this dataset as follows, cumulative on the strong graph:

| step | shapes | root shapes |
| --- | ---: | ---: |
| raw + map normalization | 7,821 | 977 |
| + reference filtering (Soft/Weak/Phantom edges cut; 980,280 soft-reachable-only objects dropped) | 7,776 | 976 |
| + `ApacheCommonsHttpClient` class-only | 7,684 | 975 |
| + `cachedArtifactResolver` conceal-field | 7,573 | 971 |
| + `LocalCache$LocalManualCache` class-only | 7,259 | 947 |
| + `DefaultConfiguration.description` conceal-field | 6,953 | 877 |
| + `DefaultLegacyConfiguration_Decorated` set-children (SCC quotient) | 6,880 | 858 |
| + `DeduplicatingComponentSelectorSerializer` class-only (traced owner) | 3,168 | 268 |
| + `DefaultVisitedArtifactResults` class-only (deliberate closure exclusion) | 1,713 | 81 |
| + `DefaultResolverResults` + `DefaultSelectedArtifactSet` class-only (remaining cached-resolution paths) | **1,656** | **78** |

Reference point: global set semantics (`SHAPES_SET_SEMANTICS=1`, no relax
file) on the same v4 graph gives 1,598 shapes / 113 root shapes. The 268
result preserved resolution-content cardinality; the subsequent targeted
`DefaultVisitedArtifactResults` rule deliberately conceals the whole
`artifactsById` subtree (composition and cardinality) from referrer keys
after the lint-classpath SCC investigation (102 visually identical SCCs →
10; each affected test-lint role goes 48 → 3, with no split of any previous
root shape). This is a semantic relaxation, not noise removal. The broader
`ExtendedConfigurations class-only` alternative was rejected because it
also hides declared `extendsFrom` structure and leaves other closure
variants intact (162 root shapes). Two JDK-wide rules (`HashMap$Node[]`,
`Object[]` set-children) remain rejected as over-broad; the HashMap
divergence belonged entirely to the serializer's `writeIndex` dedup cache.
The two later owner rules cover `currentResolveState` and the selected-artifact
delegate without hiding `Optional`/delegate presence. Their exact root merges
are `:classpath` 5 → 4, `incomingCatalogForLibs0` 2 → 1, and
`kotlinBuildToolsApiClasspath` 3 → 2. They do not erase registered collection
actions, dependency kinds, or Kotlin/action SCC members.

Surgical-merge evidence: after all rules, every f1..f4-impl AND every
IDE-model (DEV6) module shares **exactly** its `(tier,ndirect,closure)`
sig-mates' shape in every checked role (50/50, re-verified after each
rule), 0 (tier,closure) groups split, the implementation family sits at 3
shapes (population + build-logic + app), and the emitted-vs-projected edge
and object accounting matches to delta 0 when the concealed counters
(edges + quotient-concealed objects) are subtracted.

Reproduction: `FilterRefs.java` (GRPH post-transform equivalent of the
extraction filter: cut refs of the three JDK reference classes, BFS-restrict
to the queried roots) and the `NormalizeAllMapsRelax` harness (map
normalization + `ShapesAnalyzer` with `--relax`), then `join_roles.py`
against the produced population TSVs. The extraction-side filter is
implemented in `GraphExtractor` (default on, `--raw-references` to disable) and
re-extraction reproduces the same dropped set.
