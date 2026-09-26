#!/bin/bash
# Runs examples/attacks/double-spend.properties for a range of attacker hash-power shares and
# prints the measured success rate next to the exact probability of the block race.
#
#   tools/attacks/double_spend_sweep.sh [JAR] [SIMS]
#
# JAR defaults to target/cnsim-0.0.1-SNAPSHOT.jar, SIMS (per share) to 100.
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
jar=${1:-$repo/target/cnsim-0.0.1-SNAPSHOT.jar}
sims=${2:-100}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
config=$repo/examples/attacks/double-spend.properties

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$repo"

for ratio in 0.10 0.20 0.30 0.40 0.45; do
    out=$work/$ratio
    "$java_bin" -jar "$jar" -c "$config" --sims "$sims" --out "$out/" --set "node.maliciousRatio=$ratio" \
        > "$work/$ratio.log" 2>&1
    python3 "$repo/tools/attacks/double_spend_outcome.py" --quiet "$(find "$out" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
done
