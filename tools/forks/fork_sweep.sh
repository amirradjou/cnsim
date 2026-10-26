#!/bin/bash
# Runs examples/forks/forks.properties for a range of block delays D and prints the measured fork
# rate next to the closed form 1 - sum_a s_a exp(-(1 - s_a) D / T) (see examples/forks/README.md).
#
#   tools/forks/fork_sweep.sh [JAR] [SIMS]
#
# JAR defaults to target/cnsim-0.0.1-SNAPSHOT.jar, SIMS (per delay) to 10. Needs uv for the
# analysis tool (tools/finality).
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
jar=${1:-$repo/target/cnsim-0.0.1-SNAPSHOT.jar}
sims=${2:-10}
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
config=$repo/examples/forks/forks.properties

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$repo"

printf '%8s %6s %8s %-18s %9s %8s %7s\n' "D (s)" "D/T" "forks" "95% interval" "predicted" "stale" "heights"
for delay_ms in 600 1200 3000 6000 12000 18000 30000; do
    out=$work/$delay_ms
    "$java_bin" -jar "$jar" -c "$config" --sims "$sims" --out "$out/" --set "net.latencyMean=$delay_ms" \
        > "$work/$delay_ms.log" 2>&1
    delay_s=$(awk "BEGIN { print $delay_ms / 1000 }")
    uv run --quiet --project "$repo/tools/finality" cnsim-finality forks "$out" --delay "$delay_s" --json |
        python3 -c '
import json, sys
r = json.load(sys.stdin)["runs"][0]
d = float(sys.argv[1])
ci = "[%.2f%%, %.2f%%]" % (100 * r["fork_ci"][0], 100 * r["fork_ci"][1])
print("%8g %6.2f %7.2f%% %-18s %8.2f%% %7.2f%% %7d" % (
    d, d / r["mean_interval_s"], 100 * r["fork_rate"], ci, 100 * r["predicted_fork_rate"],
    100 * r["stale_rate"], r["settled"]))
' "$delay_s"
done
