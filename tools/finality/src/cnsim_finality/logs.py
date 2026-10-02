"""Reading a CNSim run directory (the folder holding 'BeliefLog - <run id>.csv' and friends)."""

from __future__ import annotations

import csv
from collections import defaultdict
from collections.abc import Iterable
from pathlib import Path

# (simulation, transaction) -> [(time in ms, degree of belief)], sorted by time
BeliefSeries = dict[tuple[int, int], list[tuple[float, float]]]


def find_log(run_dir: Path, name: str) -> Path | None:
    """The '<name> - <run id>.csv' file in run_dir, or None."""
    matches = sorted(run_dir.glob(f"{name} - *.csv"))
    return matches[0] if matches else None


def resolve_run_dir(path: Path) -> Path:
    """Accept either a run directory or an output directory holding exactly one run."""
    if find_log(path, "BeliefLog"):
        return path
    runs = (
        [p for p in sorted(path.iterdir()) if p.is_dir() and find_log(p, "BeliefLog")]
        if path.is_dir()
        else []
    )
    if len(runs) == 1:
        return runs[0]
    if not runs:
        raise FileNotFoundError(f"no BeliefLog in {path} or its subdirectories")
    raise ValueError(f"{path} holds {len(runs)} runs; pass one of them: " + ", ".join(p.name for p in runs))


def read_beliefs(run_dir: Path, transactions: Iterable[int] | None = None) -> BeliefSeries:
    """Degree of belief (share of nodes believing) per simulation, transaction and report time."""
    path = find_log(run_dir, "BeliefLog")
    if path is None:
        raise FileNotFoundError(f"no BeliefLog in {run_dir}")
    wanted = set(transactions) if transactions is not None else None
    counts: dict[tuple[int, int], dict[float, list[int]]] = defaultdict(dict)
    with path.open(newline="") as f:
        rows = csv.reader(f)
        next(rows, None)
        for row in rows:
            if len(row) < 5:
                continue
            tx = int(row[2])
            if wanted is not None and tx not in wanted:
                continue
            cell = counts[(int(row[0]), tx)].setdefault(float(row[4]), [0, 0])
            cell[0] += row[3].strip() == "true"
            cell[1] += 1
    return {key: [(t, b / n) for t, (b, n) in sorted(times.items())] for key, times in counts.items()}


def read_arrivals(run_dir: Path, transactions: Iterable[int]) -> dict[tuple[int, int], float] | None:
    """Arrival time (ms) of each wanted transaction per simulation, from the Input log.

    Returns None when the run has no Input log (reporter.reportTransactions = false).
    """
    path = find_log(run_dir, "Input")
    if path is None:
        return None
    wanted = set(transactions)
    arrivals: dict[tuple[int, int], float] = {}
    with path.open(newline="") as f:
        rows = csv.reader(f)
        next(rows, None)
        for row in rows:
            if len(row) >= 5 and int(row[1]) in wanted:
                arrivals[(int(row[0]), int(row[1]))] = float(row[4])
    return arrivals
