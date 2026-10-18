# Container topology controls

Standalone diagnostic controls for [the flow/shapes investigation](../../FLOW-COMPARISON.md).
They create actual JDK maps and export their physical reference structure into
`ObjectGraph`. They do not read or modify heap dumps or production artifacts.
No new dependencies; require the experiment's prebuilt fat jar and a JDK.

Run from `experiments/shapes` (build the jar once first with `./gradlew fatJar`):

```sh
mkdir -p /tmp/shapes-container-controls
javac -cp build/libs/shapes-experiment-all.jar \
  -d /tmp/shapes-container-controls diagnostics/container-topology/*.java
java --add-opens java.base/java.util=ALL-UNNAMED -Xmx1g \
  -cp /tmp/shapes-container-controls:build/libs/shapes-experiment-all.jar \
  HashBucketEntropy
java --add-opens java.base/java.util=ALL-UNNAMED -Xmx1g \
  -cp /tmp/shapes-container-controls:build/libs/shapes-experiment-all.jar \
  LinkedMapFactorial
```

`HashBucketEntropy` uses String keys with seed 42. Every map in a size cohort
has the same entry count, key shape (`String -> byte[]`), and value shape.
The raw projection retains real table heads and bucket chains; the normalized
projection flattens chains into unordered entries. Neither uses String contents
in shape keys. The control isolates how ignored values still affect hashes
and physical topology.

`LinkedMapFactorial` creates 3,000 maps per dataset, twenty entries each:
fixed repeated ABCD order, shuffled ABCD order, and all-A keys. Within each
dataset, all projections use the **same actual map objects**:

- `raw`: actual bucket and insertion-order links;
- `order_only`: before/after plus map head/tail, table enumerates all entries;
- `buckets_only`: actual bucket heads/next links, no insertion-order links;
- `logical`: unordered entries with only key/value children.

All projected node counts are checked via the reported reachability counts.
Default Object identity hashes depend on JVM execution, so precise variant
counts may vary between runs/JDKs; do not turn recorded counts into fixed test
expectations. The deterministic shuffled seed controls only insertion order.
The important controls are identical logical contents within a cohort,
unchanged object population, and whether the structural partition splits.

These graphs retain the distinct outgoing object IDs used by the MAT outbound
index and intentionally ignore field names, as the current shapes analyzer
does. They are **not** proposals for production map semantics. In particular,
ignoring insertion order is an explicit semantic choice, and normalizing a map
must not silently discard raw reference statistics in a real implementation.
