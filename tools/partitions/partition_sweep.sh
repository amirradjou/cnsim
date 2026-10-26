#!/bin/bash
# Runs examples/partitions/partition.properties with 1 to 5 of the 10 nodes cut off and prints
# which side's branch survived next to the Poisson race (see examples/partitions/README.md).
#
#   tools/partitions/partition_sweep.sh [JAR] [SIMS]
#
# JAR defaults to target/cnsim-0.0.1-SNAPSHOT.jar, SIMS (per side size) to 400.
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
jar=${1:-$repo/target/cnsim-0.0.1-SNAPSHOT.jar}
sims=${2:-400}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
config=$repo/examples/partitions/partition.properties

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$repo"

for nodes in "{1}" "{1,2}" "{1,2,3}" "{1,2,3,4}" "{1,2,3,4,5}"; do
    out=$work/${#nodes}
    "$java_bin" -jar "$jar" -c "$config" --sims "$sims" --out "$out/" --set "net.partition.nodes=$nodes" \
        > "$work/${#nodes}.log" 2>&1
    python3 "$repo/tools/partitions/partition_outcome.py" --quiet "$(find "$out" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
done
