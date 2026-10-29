#!/usr/bin/env python3
"""Which side's branch survives a network partition, from a CNSim run, next to the Poisson race.

Usage: partition_outcome.py RUN_DIR [--quiet]

Reads the partition (net.partition.*) and the block interval (pow.targetBlockInterval) from the
Config log, hash powers from the Nodes log, who mined each block and when from BlockLog, and each
node's final blockchain from StructureLog. Side A is the nodes listed in net.partition.nodes,
side B the others. For each simulation it counts the blocks each side mined while the partition
held, the length of the branch each side grew, which side's branch is on the chain every node
agrees on at the end (the settled chain), and how many partition blocks were orphaned.

Theory: during a partition of length L each side mines on its own, so side A mines
N_A ~ Poisson(p L / T) blocks and side B N_B ~ Poisson((1 - p) L / T), where p is A's share of
the hash power. When it heals the longer branch wins; on a tie each node keeps its own branch
(first-seen) and the side that finds the next block wins, A with probability p. So

    P(A wins) = P(N_A > N_B) + p P(N_A = N_B >= 1)

and the losing branch's blocks are orphaned. The engine check is exact: in every simulation the
branch that grew longer must be the one that survives. Standard library only.
"""

import argparse
import csv
import glob
import math
import os
import sys
from collections import defaultdict
from statistics import NormalDist


def one_log(run, name):
    files = glob.glob(os.path.join(run, name + " - *.csv"))
    if not files:
        sys.exit(f"no {name} log in {run}")
    return files[0]


def read_config(run):
    config = {}
    with open(one_log(run, "Config")) as f:
        next(f)
        for line in f:
            if "," in line:
                key, value = line.rstrip("\n").split(",", 1)
                config[key.strip()] = value.strip()
    return config


def poisson(k, mu):
    return math.exp(k * math.log(mu) - mu - math.lgamma(k + 1)) if mu > 0 else float(k == 0)


def theory(p, mean_a, mean_b):
    """P(A wins), P(no block on either side) and the expected number of orphaned blocks."""
    top = int(mean_a + mean_b + 12 * math.sqrt(mean_a + mean_b) + 20)
    pa = [poisson(k, mean_a) for k in range(top)]
    pb = [poisson(k, mean_b) for k in range(top)]
    win = orphaned = 0.0
    for a in range(top):
        for b in range(top):
            w = pa[a] * pb[b]
            if a > b:
                win += w
                orphaned += w * b
            elif a < b:
                orphaned += w * a
            elif a > 0:  # tie: the next block decides
                win += w * p
                orphaned += w * a  # either way one branch of a blocks is lost
    return win, pa[0] * pb[0], orphaned


def wilson(k, n, z=NormalDist().inv_cdf(0.975)):
    if n == 0:
        return math.nan, math.nan
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (0.0 if k == 0 else max(0.0, c - h)), (1.0 if k == n else min(1.0, c + h))


def main_chain(tree):
    """Blocks on a node's main chain, whichever of its highest blocks it settles on."""
    if not tree:
        return set()
    top = max(h for _, h in tree.values())
    chains = []
    for b, (_, h) in tree.items():
        if h == top:
            path = set()
            while b in tree:
                path.add(b)
                b = tree[b][0]
            chains.append(path)
    return set.intersection(*chains)


def branch_length(blocks, parent):
    """Longest run of parent links within blocks."""
    depth = {}

    def d(b):
        chain = []
        while b in blocks and b not in depth:
            chain.append(b)
            b = parent.get(b)
        n = depth.get(b, 0)
        for x in reversed(chain):
            n += 1
            depth[x] = n
        return n

    return max((d(b) for b in blocks), default=0)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("run")
    ap.add_argument("--quiet", action="store_true", help="print only the summary line")
    args = ap.parse_args()

    config = read_config(args.run)
    if "net.partition.nodes" not in config:
        sys.exit(f"{args.run} has no partition (net.partition.nodes)")
    side_a = {int(x) for x in config["net.partition.nodes"].strip("{} ").split(",") if x.strip()}
    start, end = int(config["net.partition.start"]), int(config["net.partition.end"])
    interval = float(config["pow.targetBlockInterval"])

    power = defaultdict(dict)
    with open(one_log(args.run, "Nodes")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            power[int(r[0])][int(r[1])] = float(r[2])

    mined = defaultdict(dict)  # sim -> block -> (miner, time)
    with open(one_log(args.run, "BlockLog")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            if r[8].strip() == "Node Completes Validation":
                mined[int(r[0])][int(r[4])] = (int(r[3]), int(r[1]))

    trees = defaultdict(lambda: defaultdict(dict))  # sim -> node -> block -> (parent, height)
    with open(one_log(args.run, "StructureLog")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            if len(r) >= 9 and r[8].strip() == "blockchain":
                trees[int(r[0])][int(r[3])][int(r[4])] = (int(r[5]), int(r[6]))

    sims = sorted(trees)
    a_wins = decided = longer_won = unequal = ties = ties_a = no_blocks = orphaned = 0
    count_a = count_b = 0
    shares = []
    for sim in sims:
        total = sum(power[sim].values())
        shares.append(sum(v for n, v in power[sim].items() if n in side_a) / total)
        parent = {}
        for tree in trees[sim].values():
            parent.update({b: pb for b, (pb, _) in tree.items()})
        during = {b: m for b, (m, t) in mined[sim].items() if start <= t < end and b in parent}
        blocks_a = {b for b, m in during.items() if m in side_a}
        blocks_b = set(during) - blocks_a
        count_a += len(blocks_a)
        count_b += len(blocks_b)
        settled = set.intersection(*(main_chain(t) for t in trees[sim].values()))
        on_a, on_b = bool(blocks_a & settled), bool(blocks_b & settled)
        orphaned += len(set(during) - settled)
        if not blocks_a and not blocks_b:
            no_blocks += 1
            continue
        if on_a == on_b:
            print(f"sim {sim}: {'both' if on_a else 'neither'} side's branch settled; skipped", file=sys.stderr)
            continue
        decided += 1
        a_wins += on_a
        len_a, len_b = branch_length(blocks_a, parent), branch_length(blocks_b, parent)
        if len_a == len_b:
            ties += 1
            ties_a += on_a
        else:
            unequal += 1
            longer_won += (len_a > len_b) == on_a
        if not args.quiet:
            print(f"sim {sim}: A mined {len(blocks_a)} (branch {len_a}), B {len(blocks_b)} (branch {len_b});"
                  f" {'A' if on_a else 'B'} won")

    p = sum(shares) / len(shares)
    length = (end - start) / interval
    win, none, expected_orphaned = theory(p, p * length, (1 - p) * length)
    lo, hi = wilson(a_wins, decided)
    n = len(sims)
    print(f"p={p:.2f} L/T={length:g}: A won {a_wins}/{decided} = {a_wins / decided:.3f} [{lo:.3f}, {hi:.3f}]"
          f" vs {win / (1 - none):.3f};"
          f" longer branch won {longer_won}/{unequal}; ties {ties} (A won {ties_a});"
          f" orphaned/sim {orphaned / n:.2f} vs {expected_orphaned:.2f};"
          f" blocks/sim A {count_a / n:.2f} vs {p * length:.2f}, B {count_b / n:.2f} vs {(1 - p) * length:.2f}")


if __name__ == "__main__":
    main()
