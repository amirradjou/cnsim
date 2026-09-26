#!/bin/bash
# Demo: how a double-spend attacker's share of the hash power erodes the finality of its target.
# Builds the jar if needed, runs examples/attacks/double-spend.properties for three attacker
# shares (SIMS simulations each, default 100), then prints the estimated finality of the target
# and plots the network's belief in it over time (docs/img/double-spend-belief.png).
#
#   tools/demo.sh [SIMS]
#
# Needs JDK 21+, Maven, and uv (https://docs.astral.sh/uv/) for the analysis.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
sims=${1:-100}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
jar=$repo/target/cnsim-0.0.1-SNAPSHOT.jar
out=$repo/out/demo
cd "$repo"

if [ ! -f "$jar" ]; then
    mvn -q -B package -DskipTests
fi
rm -rf "$out"
mkdir -p "$out"
runs=()
for q in 0.10 0.30 0.45; do
    echo "running $sims simulations with a ${q} attacker ..."
    "$java_bin" -jar "$jar" -c examples/attacks/double-spend.properties --sims "$sims" \
        --out "$out/q$q/" --set "node.maliciousRatio=$q" > "$out/q$q.log" 2>&1
    runs+=("$out/q$q")
done

cd tools/finality
labels="q = 0.10,q = 0.30,q = 0.45"
uv run -q cnsim-finality finality "${runs[@]}" --labels "$labels" --horizon 10800 --threshold 0.9
uv run -q --extra plot cnsim-finality belief "${runs[@]}" --labels "$labels" --horizon 10800 --step 60 \
    --title "Belief in the target of a double spend (${sims} simulations each)" \
    --plot "$repo/docs/img/double-spend-belief.png" --csv "$out/belief.csv"
