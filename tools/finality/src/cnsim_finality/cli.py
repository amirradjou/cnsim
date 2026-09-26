"""cnsim-finality: finality estimates and belief curves from CNSim runs.

    cnsim-finality finality RUN [RUN ...] [--tx 20000,30000] [--threshold 0.9] [--horizon 3600]
    cnsim-finality belief RUN [RUN ...] [--tx 20000] [--step 60] [--horizon 3600]
                          [--csv out.csv] [--plot out.png]

RUN is a run directory (the one holding 'BeliefLog - <run id>.csv') or an output directory that
holds a single run. Several runs are reported side by side (name them with --labels). Times on
the command line and in the output are in seconds.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from statistics import mean, median

from . import logs, metrics


def _ints(text: str) -> list[int]:
    return [int(x) for x in text.split(",") if x.strip()]


def _floats(text: str) -> list[float]:
    return [float(x) for x in text.split(",") if x.strip()]


@dataclass
class Run:
    label: str
    path: Path
    series: logs.BeliefSeries
    t0: dict[tuple[int, int], float]  # (simulation, transaction) -> start of the clock, ms

    @property
    def transactions(self) -> list[int]:
        return sorted({tx for _, tx in self.series})


def _load(path: str, label: str, args) -> Run:
    run = logs.resolve_run_dir(Path(path))
    series = logs.read_beliefs(run, args.tx)
    if not series:
        sys.exit(f"no belief entries for the requested transactions in {run}")
    keys = sorted(series)
    if args.t0 == "arrival":
        arrivals = logs.read_arrivals(run, sorted({tx for _, tx in keys}))
        if not arrivals:
            sys.exit(
                f"{run} has no transaction arrivals (reporter.reportTransactions = false); use --t0 start"
            )
        missing = [k for k in keys if k not in arrivals]
        if missing:
            sys.exit(f"no arrival time for (simulation, transaction) {missing[:3]} in {run}")
        t0 = {k: arrivals[k] for k in keys}
    else:
        t0 = {k: 0.0 for k in keys}
    return Run(label, run, series, t0)


def _runs(args) -> list[Run]:
    labels = args.labels.split(",") if args.labels else [Path(p).name for p in args.runs]
    if len(labels) != len(args.runs):
        sys.exit(f"{len(labels)} labels for {len(args.runs)} runs")
    return [_load(p, label, args) for p, label in zip(args.runs, labels, strict=True)]


def finality_rows(run: Run, args) -> list[dict]:
    """Estimated finality and time to finality for each transaction and threshold of a run."""
    rows = []
    for tx in run.transactions:
        sims = sorted(s for s, t in run.series if t == tx)
        for d in args.threshold:
            finals, times, censored = 0, [], 0
            for s in sims:
                key = (s, tx)
                series, start = run.series[key], run.t0[key]
                end = start + args.horizon * 1000 if args.horizon else series[-1][0]
                if args.horizon and series[-1][0] < end:
                    censored += 1  # the simulation ended before the horizon: cannot tell
                    continue
                final = metrics.is_final(series, d, start, end)
                if final is None:
                    censored += 1
                    continue
                if final:
                    finals += 1
                    ttf = metrics.time_to_finality(series, d, start, end)
                    assert ttf is not None  # final implies a time to finality
                    times.append(ttf / 1000)
            est = metrics.proportion(finals, len(sims) - censored, args.confidence, args.interval)
            rows.append(
                {
                    "transaction": tx,
                    "threshold": d,
                    "simulations": est.trials,
                    "censored": censored,
                    "final": finals,
                    "finality": est.value,
                    "ci_low": est.low,
                    "ci_high": est.high,
                    "time_to_finality_median_s": median(times) if times else None,
                    "time_to_finality_mean_s": mean(times) if times else None,
                }
            )
    return rows


def cmd_finality(args) -> int:
    runs = _runs(args)
    results = [(run, finality_rows(run, args)) for run in runs]
    if args.json:
        doc = {
            "horizon_s": args.horizon,
            "confidence": args.confidence,
            "interval": args.interval,
            "t0": args.t0,
            "runs": [{"label": r.label, "run": str(r.path), "results": rows} for r, rows in results],
        }
        json.dump(doc, sys.stdout, indent=2)
        print()
        return 0
    horizon = (
        f"{args.horizon:g} s after {'arrival' if args.t0 == 'arrival' else 'the start'}"
        if args.horizon
        else "the end of each simulation"
    )
    print(f"horizon: {horizon}; {int(args.confidence * 100)}% {args.interval} intervals")
    width = max(len(r.label) for r in runs)
    print(
        f"{'run':<{width}} {'tx':>8} {'d':>5} {'K':>4} {'cens':>4} {'finality':>8}  {'interval':<15} "
        f"{'ttf median':>10} {'ttf mean':>9}"
    )
    for run, rows in results:
        for r in rows:
            ci = "" if math.isnan(r["ci_low"]) else f"[{r['ci_low']:.3f}, {r['ci_high']:.3f}]"
            fin = "       -" if math.isnan(r["finality"]) else f"{r['finality']:8.3f}"
            med = "-" if r["time_to_finality_median_s"] is None else f"{r['time_to_finality_median_s']:.0f} s"
            avg = "-" if r["time_to_finality_mean_s"] is None else f"{r['time_to_finality_mean_s']:.0f} s"
            print(
                f"{run.label:<{width}} {r['transaction']:>8} {r['threshold']:>5.2f} {r['simulations']:>4} "
                f"{r['censored']:>4} {fin}  {ci:<15} {med:>10} {avg:>9}"
            )
    return 0


def belief_rows(run: Run, args) -> list[tuple]:
    """(transaction, seconds since t0, mean, q05, median, q95, simulations) over time."""
    step = args.step * 1000
    table = []
    for tx in run.transactions:
        keys = [k for k in run.series if k[1] == tx]
        if args.horizon:
            horizon = args.horizon * 1000
        else:
            horizon = min(run.series[k][-1][0] - run.t0[k] for k in keys)
        tau = 0.0
        while tau <= horizon:
            values = []
            for k in keys:
                at = run.t0[k] + tau
                v = metrics.belief_at(run.series[k], at)
                if v is not None and at <= run.series[k][-1][0]:
                    values.append(v)
            if values:
                q05, q50, q95 = (metrics.quantile(values, q) for q in (0.05, 0.5, 0.95))
                table.append((tx, tau / 1000, mean(values), q05, q50, q95, len(values)))
            tau += step
    return table


def cmd_belief(args) -> int:
    runs = _runs(args)
    tables = [(run, belief_rows(run, args)) for run in runs]
    out = open(args.csv, "w") if args.csv else sys.stdout
    try:
        out.write("run,transaction,seconds_since_t0,agg_deg_belief,q05,median,q95,simulations\n")
        for run, table in tables:
            for row in table:
                out.write(
                    f"{run.label},{row[0]},{row[1]:g},{row[2]:.4f},{row[3]:.4f},{row[4]:.4f},{row[5]:.4f},{row[6]}\n"
                )
    finally:
        if args.csv:
            out.close()
    if args.plot:
        _plot(tables, args)
    return 0


def _plot(tables, args) -> None:
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        sys.exit("plotting needs matplotlib: uv sync --extra plot (or pip install matplotlib)")
    fig, ax = plt.subplots(figsize=(7, 4))
    several = len(tables) > 1
    for run, table in tables:
        for tx in run.transactions:
            rows = [r for r in table if r[0] == tx]
            x = [r[1] / 60 for r in rows]
            label = (
                f"{run.label}, tx {tx}"
                if several and len(run.transactions) > 1
                else (run.label if several else f"tx {tx}")
            )
            ax.plot(x, [r[2] for r in rows], label=label)
            if not several:  # with several runs the overlapping bands hide the means
                ax.fill_between(x, [r[3] for r in rows], [r[5] for r in rows], alpha=0.12)
    for d in args.threshold:
        ax.axhline(d, color="grey", linestyle="--", linewidth=0.8)
    ax.set_xlabel("minutes since " + ("arrival" if args.t0 == "arrival" else "start"))
    ax.set_ylabel("share of nodes believing" + (" (mean)" if several else " (mean, 5-95%)"))
    ax.set_ylim(-0.02, 1.02)
    ax.set_title(args.title or "Aggregate degree of belief")
    ax.legend(loc="best")
    fig.tight_layout()
    fig.savefig(args.plot, dpi=150)
    print(f"wrote {args.plot}", file=sys.stderr)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="cnsim-finality", description="Finality estimates and belief curves from CNSim runs."
    )
    sub = p.add_subparsers(dest="command", required=True)

    def common(sp):
        sp.add_argument(
            "runs", nargs="+", metavar="RUN", help="run directory, or an output directory holding one"
        )
        sp.add_argument("--labels", help="names for the runs, comma-separated (default: directory names)")
        sp.add_argument(
            "--tx", type=_ints, default=None, help="transactions, comma-separated (default: all in the log)"
        )
        sp.add_argument(
            "--t0",
            choices=["arrival", "start"],
            default="arrival",
            help="measure time from each transaction's arrival (default; needs the Input log) or from 0",
        )
        sp.add_argument(
            "--horizon", type=float, default=None, help="horizon in seconds after t0 (default: end of run)"
        )
        sp.add_argument(
            "--threshold", type=_floats, default=[0.9], help="belief threshold(s) d (default 0.9)"
        )

    f = sub.add_parser("finality", help="estimated finality and time to finality per transaction")
    common(f)
    f.add_argument("--confidence", type=float, default=0.95, help="confidence level of the intervals")
    f.add_argument("--interval", choices=["wilson", "wald"], default="wilson")
    f.add_argument("--json", action="store_true", help="print JSON instead of a table")
    f.set_defaults(func=cmd_finality)

    b = sub.add_parser("belief", help="aggregate degree of belief over time since t0")
    common(b)
    b.add_argument("--step", type=float, default=60, help="time step in seconds (default 60)")
    b.add_argument("--csv", help="write the table to this file instead of stdout")
    b.add_argument("--plot", help="also draw the curves to this image (needs matplotlib)")
    b.add_argument("--title", help="plot title")
    b.set_defaults(func=cmd_belief)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
