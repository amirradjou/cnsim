#!/bin/bash
# Smoke test: runs a shortened copy of every shipped configuration (1
# simulation, 10 simulated minutes, at most 5000 transactions) and fails if
# any run exits non-zero or writes no belief entries.
#
#   tools/smoke.sh [JAR]
#
# JAR defaults to target/cnsim-0.0.1-SNAPSHOT.jar. Runs from the repository
# root because the shipped configs name their input files relative to it.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
jar=${1:-$repo/target/cnsim-0.0.1-SNAPSHOT.jar}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [ ! -f "$jar" ]; then
    echo "smoke: jar '$jar' not found; build it with 'mvn package' first" >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$repo"

failures=0
count=0
for cfg in src/main/resources/bitcoin-config/*.properties \
           src/main/resources/new-config/*.properties \
           examples/*/*.properties; do
    [ -f "$cfg" ] || continue
    count=$((count + 1))
    name=$(echo "$cfg" | tr '/' '_')
    short=$work/$name
    sed -e 's/^sim.numSimulations *=.*/sim.numSimulations = 1/' \
        -e 's/^sim.terminate.atTime *=.*/sim.terminate.atTime = 600000/' \
        -e 's/^sim.reporting.beliefReportOffset *=.*/sim.reporting.beliefReportOffset = 0/' \
        "$cfg" > "$short"
    # Cap the workload without raising it for configs that already use fewer.
    ntx=$(sed -n 's/^workload.numTransactions *= *\([0-9]*\).*/\1/p' "$short")
    if [ -n "$ntx" ] && [ "$ntx" -gt 5000 ]; then
        sed -i 's/^workload.numTransactions *=.*/workload.numTransactions = 5000/' "$short"
    fi

    out=$work/out-$name
    if ! "$java_bin" -jar "$jar" -c "$short" --out "$out/" > "$work/$name.log" 2>&1; then
        echo "FAIL $cfg (exit code $?)"
        tail -n 5 "$work/$name.log" | sed 's/^/    /'
        failures=$((failures + 1))
        continue
    fi
    belief=$(find "$out" -name 'BeliefLog - *.csv' | head -n 1)
    if [ -z "$belief" ] || [ "$(wc -l < "$belief")" -lt 2 ]; then
        echo "FAIL $cfg (no belief entries)"
        failures=$((failures + 1))
        continue
    fi
    echo "ok   $cfg"
done

echo "smoke: $((count - failures))/$count configs ran"
[ "$failures" -eq 0 ]
