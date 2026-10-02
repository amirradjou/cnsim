#!/bin/bash
# Runs one config with several base seeds in two seeding variants and writes one JSON line per
# simulation and sample transaction (time to finality, blocks mined) for
# tools/replicas/replicas.py analyse:
#
#   configured   node.sampler.seed = {444 + 1000 j, 222}, everything else as in the config
#                (j = 0 is exactly the thesis seeding)
#   independent  node.sampler.seed = {444, 222 + 1000 j}, switched to at t = 0, so every
#                simulation draws from its own seed (222 + 1000 j + simulation ID)
#
#   tools/replicas/seed_sweep.sh [-c CONFIG] [-r RUNS] [-n SIMS] [-p PROCESSES] [-o OUT] [JAR]
#
# Defaults: the thesis base config, 10 runs per variant, 30 simulations, 6 processes, OUT =
# replicas.jsonl, JAR = target/cnsim-0.0.1-SNAPSHOT.jar. Each run's logs are deleted once
# summarised; with the defaults the sweep takes about 35 minutes on 6 cores.
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
config=$repo/src/main/resources/new-config/thesis.bitcoin.base.properties
runs=10
sims=30
processes=6
out=replicas.jsonl
while getopts c:r:n:p:o: opt; do
    case $opt in
        c) config=$OPTARG ;;
        r) runs=$OPTARG ;;
        n) sims=$OPTARG ;;
        p) processes=$OPTARG ;;
        o) out=$OPTARG ;;
        *) sed -n '2,15p' "$0" >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
jar=${1:-$repo/target/cnsim-0.0.1-SNAPSHOT.jar}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [ ! -f "$jar" ]; then
    echo "seed_sweep: jar '$jar' not found; build it with 'mvn package' first" >&2
    exit 1
fi
config=$(cd "$(dirname "$config")" && pwd)/$(basename "$config")
out=$(cd "$(dirname "$out")" && pwd)/$(basename "$out")

# Run logs are large (about 0.5 GB per 30-simulation run of the thesis base config): keep them
# out of a RAM-backed /tmp unless TMPDIR says otherwise.
work=$(mktemp -d "${TMPDIR:-$HOME/.cache}/cnsim-seed-sweep.XXXXXX")
trap 'rm -rf "$work"' EXIT

logs=(--set reporter.reportBlockEvents=true --set reporter.reportTransactions=true
    --set reporter.reportBeliefs=true --set reporter.reportEvents=false
    --set reporter.reportStructureEvents=false --set reporter.reportNetEvents=false)

: > "$out"
cd "$repo"
for ((j = 0; j < runs; j++)); do
    for variant in configured independent; do
        if [ "$variant" = configured ]; then
            seed=$((444 + 1000 * j))
            seeding=(--set "node.sampler.seed={$seed,222}")
        else
            seed=$((222 + 1000 * j))
            seeding=(--set "node.sampler.seed={444,$seed}" --set "node.sampler.updateSeedFlags={false,true}"
                --set "node.sampler.seedUpdateTimes={0}")
        fi
        rm -rf "$work/run"
        start=$SECONDS
        if ! "$java_bin" -jar "$jar" -c "$config" --sims "$sims" --parallel "$processes" --out "$work/run/" \
            "${logs[@]}" "${seeding[@]}" < /dev/null > "$work/console.txt" 2>&1; then
            echo "seed_sweep: $variant seed $seed failed:" >&2
            tail -n 20 "$work/console.txt" >&2
            exit 1
        fi
        uv run --quiet --project "$repo/tools/finality" python "$repo/tools/replicas/replicas.py" summarise \
            "$work/run" --label "$variant" --seed "$seed" >> "$out"
        echo "$variant seed $seed: $((SECONDS - start)) s"
    done
done
echo "seed_sweep: wrote $(wc -l < "$out") lines to $out"
