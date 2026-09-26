#!/usr/bin/env python3
"""Double-spend outcomes from a CNSim run directory, next to the exact probability of the race.

Usage: double_spend_outcome.py RUN_DIR [--min N] [--max N] [--node N]

For each simulation: whether the attack started, was revealed or abandoned, and whether the
target transaction is on node N's final main chain (default node 1). A success is a revealed
attack whose target is not on the main chain at the end.

The exact success probability treats every new block as the attacker's with probability q (its
hash-power share, from the Nodes log) and the honest network's otherwise, starting from the
state right after the target's block (hidden chain 0, public growth 1), and applies the
attacker's rules: reveal when hidden > growth and growth > min, or when growth > max; give up
when growth >= max and hidden <= growth. It ignores propagation delay. Standard library only.
"""
import argparse
import csv
import glob
import math
import os
import sys
from collections import defaultdict
from functools import lru_cache


def p_success(q, lo, hi):
    """Probability that the hidden chain overtakes (see module doc)."""
    p = 1 - q

    @lru_cache(maxsize=None)
    def f(h, g):
        if (h > g and g > lo) or g > hi:
            return 1.0 if h > g else 0.0
        if g >= hi and h <= g:
            return 0.0
        if g <= lo and h >= lo + 2:
            return 1.0  # already ahead of min + 1 confirmations: reveals as soon as they exist
        return q * f(h + 1, g) + p * f(h, g + 1)

    sys.setrecursionlimit(10000)
    return f(0, 1)


def one_log(run, name):
    files = glob.glob(os.path.join(run, name + " - *.csv"))
    if not files:
        sys.exit(f"no {name} log in {run}")
    return files[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("run")
    ap.add_argument("--min", type=int, default=2, help="bitcoin.attack.minChainLength (default 2)")
    ap.add_argument("--max", type=int, default=15, help="bitcoin.attack.maxChainLength (default 15)")
    ap.add_argument("--node", type=int, default=1, help="node whose final chain counts (default 1)")
    ap.add_argument("--target", type=int, default=None, help="target transaction (default: from Config log)")
    ap.add_argument("--quiet", action="store_true", help="print only the summary line")
    args = ap.parse_args()

    target = args.target
    if target is None:
        with open(one_log(args.run, "Config")) as f:
            for r in csv.reader(f):
                if r and r[0].strip() == "workload.targetTransaction":
                    target = int(r[1])
    if target is None:
        sys.exit("no workload.targetTransaction in the Config log; pass --target")

    started, revealed, abandoned, attacker = set(), set(), set(), {}
    with open(one_log(args.run, "BlockLog")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            sim, event = r[0].strip(), r[8].strip()
            if event.startswith("Target Transaction Appeared"):
                started.add(sim)
            elif event.startswith("Reveal of hidden chain"):
                revealed.add(sim)
                attacker[sim] = int(r[3])
            elif event.startswith("Attack abandoned"):
                abandoned.add(sim)
                attacker[sim] = int(r[3])

    power = defaultdict(dict)
    with open(one_log(args.run, "Nodes")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            power[r[0].strip()][int(r[1])] = float(r[2])

    chains = defaultdict(dict)
    with open(one_log(args.run, "StructureLog")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            if int(r[3]) == args.node and r[8].strip() == "blockchain":
                txs = {t for t in r[7].strip().strip("{}").split(";") if t}
                chains[r[0].strip()][int(r[4])] = (int(r[5]), int(r[6]), txs)

    # The attacker is the last node created: the highest ID.
    qs = []
    successes = n_started = 0
    for sim in sorted(chains, key=int):
        blocks = chains[sim]
        tip = max(blocks, key=lambda b: (blocks[b][1], -b))
        on_main = False
        while tip in blocks:
            on_main = on_main or str(target) in blocks[tip][2]
            tip = blocks[tip][0]
        nodes = power[sim]
        q = nodes[max(nodes)] / sum(nodes.values())
        qs.append(q)
        success = sim in revealed and not on_main
        successes += success
        n_started += sim in started
        if not args.quiet:
            state = "revealed" if sim in revealed else "abandoned" if sim in abandoned else \
                "running at end" if sim in started else "not started"
            print(f"sim {sim}: q {q:.3f}, attack {state}, target on main chain: {on_main}, success: {success}")

    q = sum(qs) / len(qs)
    rate = successes / n_started if n_started else float("nan")
    half = 1.96 * math.sqrt(rate * (1 - rate) / n_started) if n_started else float("nan")
    print(f"q {q:.3f}  attacks {n_started}  success {rate:.3f} +- {half:.3f} (95%)  "
          f"exact race probability {p_success(q, args.min, args.max):.3f}  (min {args.min}, max {args.max})")


if __name__ == "__main__":
    main()
