#!/bin/bash
# Runs examples/attacks/selfish-mining.properties for a range of selfish hash-power shares and
# prints the measured revenue share next to Eyal and Sirer's closed form.
#
#   tools/attacks/selfish_sweep.sh [JAR] [SIMS]
#
# JAR defaults to target/cnsim-0.0.1-SNAPSHOT.jar, SIMS (per share) to 10.
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
jar=${1:-$repo/target/cnsim-0.0.1-SNAPSHOT.jar}
sims=${2:-10}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
config=$repo/examples/attacks/selfish-mining.properties

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$repo"

for ratio in 0.10 0.20 0.25 0.30 0.33 0.35 0.40 0.45; do
    out=$work/$ratio
    "$java_bin" -jar "$jar" -c "$config" --sims "$sims" --out "$out/" --set "node.selfishRatio=$ratio" \
        > "$work/$ratio.log" 2>&1
    python3 "$repo/tools/attacks/selfish_revenue.py" --quiet "$(find "$out" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
done
