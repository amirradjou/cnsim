#!/bin/bash
# Golden-run check: runs tools/golden/golden.properties with the given jar and
# compares the SHA-256 of every output log (wall-clock columns removed) with
# the committed tools/golden/expected.sha256.
#
#   tools/golden/check.sh [--update] [--parallel N] [JAR]
#
# JAR defaults to target/cnsim-0.0.1-SNAPSHOT.jar. --update rewrites the
# expected hashes instead of comparing; only do that for an intended change in
# simulation semantics, and say so in the commit message. --parallel N runs
# the simulations in N processes; the merged logs must match the same hashes.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
update=false
parallel=()
while [ $# -gt 0 ]; do
    case "$1" in
        --update) update=true; shift ;;
        --parallel) parallel=(--parallel "$2"); shift 2 ;;
        *) break ;;
    esac
done
jar=${1:-$repo/target/cnsim-0.0.1-SNAPSHOT.jar}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
expected=$here/expected.sha256

if [ ! -f "$jar" ]; then
    echo "golden: jar '$jar' not found; build it with 'mvn package' first" >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

(cd "$repo" && "$java_bin" -jar "$jar" -c "$here/golden.properties" --out "$work/out/" "${parallel[@]}") \
    > "$work/stdout.txt" 2>&1 || {
    echo "golden: simulator exited with an error:" >&2
    tail -n 20 "$work/stdout.txt" >&2
    exit 1
}

# Each run writes "<Log> - <run id>.csv" into one timestamped directory.
run_dir=$(find "$work/out" -mindepth 1 -maxdepth 1 -type d | head -n 1)
if [ -z "$run_dir" ]; then
    echo "golden: no run directory under $work/out" >&2
    exit 1
fi

# Every row of a CSV log must have as many fields as its header, or CSV readers choke on it.
# Config is exempt: its values may contain commas (split its rows on the first comma).
for file in "$run_dir"/*.csv; do
    case $(basename "$file") in Config\ -\ *) continue ;; esac
    bad=$(awk -F, 'NR == 1 { n = NF; next } NF != n { print "line " NR " has " NF " fields, the header " n; exit }' "$file")
    if [ -n "$bad" ]; then
        echo "golden: $(basename "$file"): $bad" >&2
        exit 1
    fi
done

# Strip what legitimately differs between runs and machines: wall-clock
# columns, and the output directory, config path and process count in the
# Config dump.
normalize() {
    local log=$1 file=$2
    case "$log" in
        EventLog) awk -F, -v OFS=, '{$4 = ""; print}' "$file" ;;
        BlockLog | StructureLog) awk -F, -v OFS=, '{$3 = ""; print}' "$file" ;;
        Config) grep -v -e '^sim.output.directory,' -e '^config.file,' -e '^sim.parallelism,' "$file" | LC_ALL=C sort ;;
        *) cat "$file" ;;
    esac
}

actual=$work/actual.sha256
for file in "$run_dir"/*; do
    log=$(basename "$file")
    log=${log%% - *}
    # Provenance records wall-clock times and machine details: not part of the result.
    [ "$log" = Provenance ] && continue
    printf '%s  %s\n' "$(normalize "$log" "$file" | sha256sum | cut -d' ' -f1)" "$log"
done | LC_ALL=C sort -k2 > "$actual"

if $update; then
    cp "$actual" "$expected"
    echo "golden: wrote $(wc -l < "$expected") hashes to $expected"
    exit 0
fi

if diff -u "$expected" "$actual"; then
    echo "golden: OK ($(wc -l < "$expected") logs match${parallel[*]:+, ${parallel[*]}})"
else
    echo "golden: output differs from $expected (see diff above)." >&2
    echo "golden: if the change in simulation semantics is intended, rerun with --update." >&2
    exit 1
fi
