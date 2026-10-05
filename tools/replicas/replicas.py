"""Are the simulations of a run independent replicas?

The finality estimators treat the K simulations of a run as K independent trials. When the
simulations share random draws (in the thesis configs the node sampler never leaves its common
seed), a whole run can be lucky or unlucky, and its K simulations are worth fewer than K
independent ones. This script measures that from several runs of the same config that differ
only in their base seed:

    summarise RUN --label L --seed S   one JSON line per simulation and sample transaction:
                                       time to finality and blocks mined (BlockLog needed)
    analyse FILE [FILE ...]            per-run table, then for each measure the intraclass
                                       correlation of the simulations of a run, the design effect
                                       1 + (K - 1) * ICC, and a permutation p-value

With independent replicas the run means vary only as much as their within-run standard errors
predict: ICC near 0, design effect near 1. tools/replicas/seed_sweep.sh produces the input.

Run with the finality tool's environment:

    uv run --project tools/finality python tools/replicas/replicas.py analyse replicas.jsonl
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import statistics as st
import sys
from collections import Counter
from pathlib import Path

from cnsim_finality import logs, metrics


def summarise(args) -> int:
    run = logs.resolve_run_dir(Path(args.run))
    series = logs.read_beliefs(run, args.tx)
    transactions = sorted({tx for _, tx in series})
    arrivals = logs.read_arrivals(run, transactions)
    if arrivals is None:
        sys.exit("summarise: the run has no Input log (reporter.reportTransactions = false)")
    blocks: Counter[int] | None = None
    block_log = logs.find_log(run, "BlockLog")
    if block_log is not None:
        blocks = Counter()
        with block_log.open(newline="") as f:
            rows = csv.reader(f)
            next(rows, None)
            for row in rows:
                if len(row) > 8 and row[8].strip() == "Node Completes Validation":
                    blocks[int(row[0])] += 1
        if not blocks:
            blocks = None  # block events were not reported
    horizon = args.horizon * 1000
    for (sim, tx), s in sorted(series.items()):
        t0 = arrivals[(sim, tx)]
        ttf = metrics.time_to_finality(s, args.threshold, t0, t0 + horizon)
        line = {
            "label": args.label,
            "seed": args.seed,
            "sim": sim,
            "tx": tx,
            "ttf": None if ttf is None else ttf / 1000,
            "blocks": None if blocks is None else blocks[sim],
        }
        print(json.dumps(line))
    return 0


def anova(groups: list[list[float]]) -> tuple[float, float]:
    """One-way ANOVA of values grouped by run: F = MSB / MSW and the intraclass correlation."""
    k = len(groups)
    n = sum(len(g) for g in groups)
    grand = st.mean(x for g in groups for x in g)
    ssb = sum(len(g) * (st.mean(g) - grand) ** 2 for g in groups)
    ssw = sum((x - st.mean(g)) ** 2 for g in groups for x in g)
    msb, msw = ssb / (k - 1), ssw / (n - k)
    n0 = (n - sum(len(g) ** 2 for g in groups) / n) / (k - 1)
    return msb / msw, (msb - msw) / (msb + (n0 - 1) * msw)


def permutation_p(groups: list[list[float]], statistic, reps: int, rng: random.Random) -> float:
    """Share of random regroupings (same group sizes) with a statistic at least as large."""
    observed = statistic(groups)
    pool = [x for g in groups for x in g]
    sizes = [len(g) for g in groups]
    hits = 0
    for _ in range(reps):
        rng.shuffle(pool)
        it = iter(pool)
        hits += statistic([[next(it) for _ in range(size)] for size in sizes]) >= observed
    return (hits + 1) / (reps + 1)


HEADER = f"  {'measure (per simulation)':<22} {'run min':>8} {'run max':>8}" + "".join(
    f" {name:>7}" for name in ("ICC", "deff", "F", "p")
)


def clustering_row(name: str, groups: list[list[float]], reps: int, rng: random.Random) -> str:
    f, icc = anova(groups)
    deff = 1 + (st.mean(len(g) for g in groups) - 1) * icc
    p = permutation_p(groups, lambda g: anova(g)[0], reps, rng)
    means = [st.mean(g) for g in groups]
    return f"  {name:<22} {min(means):8.1f} {max(means):8.1f} {icc:+7.3f} {deff:7.2f} {f:7.2f} {p:7.3f}"


def mean_or_nan(values: list[float]) -> float:
    return st.mean(values) if values else float("nan")


def analyse(args) -> int:
    rows = [json.loads(line) for path in args.files for line in Path(path).read_text().splitlines() if line]
    rng = random.Random(args.random_seed)
    for label in sorted({r["label"] for r in rows}):
        mine = [r for r in rows if r["label"] == label]
        seeds = sorted({r["seed"] for r in mine})
        transactions = sorted({r["tx"] for r in mine})
        # Per run: blocks mined per simulation, and each transaction's time to finality per
        # simulation (None where it is not final within the horizon).
        blocks = {
            s: {r["sim"]: r["blocks"] for r in mine if r["seed"] == s and r["blocks"] is not None}
            for s in seeds
        }
        ttf = {
            (s, tx): [r["ttf"] for r in mine if r["seed"] == s and r["tx"] == tx]
            for s in seeds
            for tx in transactions
        }

        print(f"\n## {label}: {len(seeds)} runs\n")
        print("  seed   sims  blocks/sim  " + "  ".join(f"final {tx:>6}  mean ttf" for tx in transactions))
        for s in seeds:
            cells = []
            for tx in transactions:
                final = [t for t in ttf[(s, tx)] if t is not None]
                cells.append(f"{len(final) / len(ttf[(s, tx)]):12.3f}  {mean_or_nan(final):8.0f}")
            sims = len({r["sim"] for r in mine if r["seed"] == s})
            block_cell = f"{st.mean(blocks[s].values()):10.1f}" if blocks[s] else f"{'-':>10}"
            print(f"  {s:<6} {sims:4d}  {block_cell}  " + "  ".join(cells))
        pooled = []
        for tx in transactions:
            all_ttf = [t for s in seeds for t in ttf[(s, tx)]]
            final = [t for t in all_ttf if t is not None]
            est = metrics.proportion(len(final), len(all_ttf))
            interval = f"[{est.low:.3f}, {est.high:.3f}]"
            pooled.append(f"tx {tx} finality {est.value:.3f} {interval}, mean ttf {mean_or_nan(final):.0f} s")
        print("  pooled: " + "; ".join(pooled))

        if len(seeds) < 2:
            print("\n  (one run: nothing to compare it with)")
            continue
        print("\n" + HEADER)
        if all(len(blocks[s]) > 1 for s in seeds):
            print(clustering_row("blocks mined", [list(blocks[s].values()) for s in seeds], args.reps, rng))
        for tx in transactions:
            groups = [[t for t in ttf[(s, tx)] if t is not None] for s in seeds]
            if all(len(g) > 1 for g in groups):
                print(clustering_row(f"time to finality {tx}", groups, args.reps, rng))
        for tx in transactions:
            # Non-final simulations per run: does a run's luck decide finality as well?
            groups = [[float(t is None) for t in ttf[(s, tx)]] for s in seeds]
            counts = [int(sum(g)) for g in groups]
            if sum(counts) == 0:
                continue
            p = permutation_p(groups, lambda g: st.pvariance([sum(x) for x in g]), args.reps, rng)
            print(f"  not final {tx}: {counts} per run, {sum(counts)} in all, clustering p {p:.3f}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Are the simulations of a run independent replicas?")
    sub = parser.add_subparsers(dest="command", required=True)

    s = sub.add_parser("summarise", help="one JSON line per simulation and sample transaction")
    s.add_argument("run", help="run directory, or an output directory holding one")
    s.add_argument("--label", required=True, help="name of the seeding variant")
    s.add_argument("--seed", type=int, required=True, help="the run's base seed")
    s.add_argument("--tx", type=lambda t: [int(x) for x in t.split(",")], help="transactions (default: all)")
    s.add_argument("--horizon", type=float, default=3600, help="seconds after arrival (default 3600)")
    s.add_argument("--threshold", type=float, default=0.9, help="belief threshold d (default 0.9)")
    s.set_defaults(func=summarise)

    a = sub.add_parser("analyse", help="compare runs that differ only in their base seed")
    a.add_argument("files", nargs="+", help="JSON lines written by summarise")
    a.add_argument("--reps", type=int, default=4000, help="permutations per test (default 4000)")
    a.add_argument("--random-seed", type=int, default=1)
    a.set_defaults(func=analyse)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
