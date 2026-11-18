#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <legacy-mat.jar> <heap.hprof> <output-dir> [class] [scope]" >&2
    exit 2
fi

legacy_jar=$(realpath "$1")
hprof=$(realpath "$2")
output_dir=$3
class_name=${4:-java.lang.String}
scope=${5:-reachable}
new_jar=$(realpath build/libs/shapes-experiment-all.jar)
xmx=${SHAPES_BENCHMARK_XMX:-2g}
mkdir -p "$output_dir"
output_dir=$(realpath "$output_dir")

run_variant() {
    local variant=$1
    local jar=$2
    local source=${3:-}
    local prefix="$output_dir/$variant"
    local -a source_option=()
    if [[ -n "$source" ]]; then
        source_option=(--heap-source "$source")
    fi

    echo "[$variant] $prefix.log"
    {
        time java "-Xmx$xmx" \
            "-XX:StartFlightRecording=filename=$prefix.jfr,settings=profile,dumponexit=true" \
            -jar "$jar" mat-analyze \
            --hprof "$hprof" \
            --class "$class_name" \
            --scope "$scope" \
            "${source_option[@]}" \
            --stats "$prefix-stats.json" \
            --out "$prefix.shp"
    } >"$prefix.log" 2>&1
}

run_variant legacy-mat "$legacy_jar"
run_variant new-mat "$new_jar" mat
run_variant new-hprof "$new_jar" hprof

sha256sum "$output_dir"/*.shp >"$output_dir/sha256.txt"
echo "JFR, logs, statistics and artifacts: $output_dir"
