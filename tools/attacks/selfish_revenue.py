#!/usr/bin/env python3
"""Selfish-mining revenue from a CNSim run directory, next to Eyal and Sirer's closed form.

Usage: selfish_revenue.py RUN_DIR [--node N]

Reads BlockLog (who validated each block) and StructureLog (each node's final chain), takes the
longest chain in node N's final structure (default: node 1, an honest node) and reports, per
simulation and pooled, the selfish pool's share of main-chain blocks (its revenue share), its
hash-power share alpha (from the Nodes log), and the theoretical revenue for gamma = 0 and 1.

It also estimates gamma, the share of honest mining that builds on the selfish block during a
race: a race is a published selfish block and an honest block with the same parent, and it is
counted when an honest miner finds the next block on one of the two. The theory is then also
evaluated at that gamma. Blocks still withheld when a run ends are not counted, which biases the
measured revenue down a little when the selfish pool is large. Standard library only.
"""
import argparse
import csv
import glob
import math
import os
import sys
from collections import defaultdict


def theory(alpha, gamma):
    """Relative revenue of a selfish pool (Eyal and Sirer 2014, eq. 8)."""
    a, g = alpha, gamma
    return (a * (1 - a) ** 2 * (4 * a + g * (1 - 2 * a)) - a ** 3) / (1 - a * (1 + (2 - a) * a))


def one_log(run, name):
    files = glob.glob(os.path.join(run, name + " - *.csv"))
    if not files:
        sys.exit(f"no {name} log in {run}")
    return files[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("run")
    ap.add_argument("--node", type=int, default=1, help="node whose final chain counts (default 1)")
    ap.add_argument("--quiet", action="store_true", help="print only the pooled summary line")
    args = ap.parse_args()

    validator = defaultdict(dict)  # sim -> block -> node that mined it
    selfish_nodes = defaultdict(set)
    parent_of = defaultdict(dict)  # sim -> block -> parent, as placed by its miner
    placed_at = defaultdict(dict)  # sim -> block -> time the miner placed or withheld it
    published = defaultdict(set)   # sim -> selfish blocks that were published
    with open(one_log(args.run, "BlockLog")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            sim, event, block = r[0].strip(), r[8].strip(), int(r[4])
            if event == "Node Completes Validation":
                validator[sim][block] = int(r[3])
            elif event.startswith("Selfish:"):
                selfish_nodes[sim].add(int(r[3]))
                if event == "Selfish: block withheld":
                    parent_of[sim][block] = int(r[5])
                    placed_at[sim][block] = int(r[1])
                elif event == "Selfish: block published":
                    published[sim].add(block)
            elif event == "Appended On Chain (parentless)" and int(r[3]) == validator[sim].get(block):
                parent_of[sim][block] = int(r[5])
                placed_at[sim][block] = int(r[1])

    power = defaultdict(dict)  # sim -> node -> hash power
    with open(one_log(args.run, "Nodes")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            power[r[0].strip()][int(r[1])] = float(r[2])

    chains = defaultdict(dict)  # sim -> block -> (parent, height)
    with open(one_log(args.run, "StructureLog")) as f:
        rows = csv.reader(f)
        next(rows)
        for r in rows:
            if int(r[3]) == args.node and r[8].strip() == "blockchain":
                chains[r[0].strip()][int(r[4])] = (int(r[5]), int(r[6]))

    races_on_selfish = races_counted = 0
    for sim in parent_of:
        pools = selfish_nodes.get(sim, set())
        children = defaultdict(list)
        for b, par in parent_of[sim].items():
            children[par].append(b)
        for s in published[sim]:
            siblings = [h for h in children[parent_of[sim][s]] if validator[sim].get(h) not in pools]
            if not siblings:
                continue
            h = min(siblings, key=lambda x: placed_at[sim][x])
            nxt = [c for c in children[s] + children[h]]
            if not nxt:
                continue
            first = min(nxt, key=lambda x: placed_at[sim][x])
            if validator[sim].get(first) in pools:
                continue  # the selfish pool decided the race itself
            races_counted += 1
            races_on_selfish += parent_of[sim][first] == s
    gamma = races_on_selfish / races_counted if races_counted else float("nan")

    total_blocks = total_selfish = 0
    alphas = []
    for sim in sorted(chains, key=int):
        blocks = chains[sim]
        tip = max(blocks, key=lambda b: (blocks[b][1], -b))
        main_chain = []
        while tip in blocks:
            main_chain.append(tip)
            tip = blocks[tip][0]
        pools = selfish_nodes.get(sim) or selfish_nodes.get("1") or set()
        mine = sum(1 for b in main_chain if validator[sim].get(b) in pools)
        alpha = sum(power[sim][n] for n in pools) / sum(power[sim].values()) if pools else float("nan")
        alphas.append(alpha)
        total_blocks += len(main_chain)
        total_selfish += mine
        stale = len(blocks) - len(main_chain)
        if not args.quiet:
            print(f"sim {sim}: alpha {alpha:.3f}, main chain {len(main_chain)} blocks, selfish {mine} "
                  f"({mine / len(main_chain):.3f}), stale in node {args.node}'s view {stale}")

    share = total_selfish / total_blocks
    se = math.sqrt(share * (1 - share) / total_blocks)
    alpha = sum(alphas) / len(alphas)
    print(f"alpha {alpha:.3f}  revenue {share:.3f} +- {1.96 * se:.3f} (95%, {total_blocks} blocks)  "
          f"theory gamma=0 {theory(alpha, 0):.3f}  gamma=1 {theory(alpha, 1):.3f}  "
          f"measured gamma {gamma:.2f} ({races_counted} races) -> theory {theory(alpha, gamma):.3f}")


if __name__ == "__main__":
    main()
