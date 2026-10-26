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


def resolve_run_dir(path: Path, log: str = "BeliefLog") -> Path:
    """Accept either a run directory or an output directory holding exactly one run with that log."""
    if find_log(path, log):
        return path
    runs = [p for p in sorted(path.iterdir()) if p.is_dir() and find_log(p, log)] if path.is_dir() else []
    if len(runs) == 1:
        return runs[0]
    if not runs:
        raise FileNotFoundError(f"no {log} in {path} or its subdirectories")
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


def read_structures(
    run_dir: Path,
) -> tuple[dict[int, dict[int, dict[int, tuple[int, int]]]], dict[int, float]]:
    """Each node's final blockchain per simulation, and each simulation's end time (ms).

    Returns ({simulation: {node: {block: (parent, height)}}}, {simulation: end time}). Orphans
    (blocks whose parent the node never received) are not part of a node's blockchain and are
    left out.
    """
    path = find_log(run_dir, "StructureLog")
    if path is None:
        raise FileNotFoundError(f"no StructureLog in {run_dir} (reporter.reportStructureEvents = false?)")
    trees: dict[int, dict[int, dict[int, tuple[int, int]]]] = defaultdict(lambda: defaultdict(dict))
    ends: dict[int, float] = {}
    with path.open(newline="") as f:
        rows = csv.reader(f)
        next(rows, None)
        for row in rows:
            if len(row) < 9:
                continue
            sim = int(row[0])
            ends[sim] = max(ends.get(sim, 0.0), float(row[1]))
            if row[8].strip() == "blockchain":
                trees[sim][int(row[3])][int(row[4])] = (int(row[5]), int(row[6]))
    return {s: dict(nodes) for s, nodes in trees.items()}, ends


def read_mined(run_dir: Path) -> dict[int, set[int]] | None:
    """The blocks mined in each simulation, from BlockLog; None if the run has no BlockLog."""
    path = find_log(run_dir, "BlockLog")
    if path is None:
        return None
    mined: dict[int, set[int]] = defaultdict(set)
    with path.open(newline="") as f:
        rows = csv.reader(f)
        next(rows, None)
        for row in rows:
            if len(row) >= 9 and row[8].strip() == "Node Completes Validation":
                mined[int(row[0])].add(int(row[4]))
    return dict(mined)


def read_hash_power(run_dir: Path) -> dict[int, dict[int, float]] | None:
    """Hash power of each node per simulation, from the Nodes log; None if there is none."""
    path = find_log(run_dir, "Nodes")
    if path is None:
        return None
    power: dict[int, dict[int, float]] = defaultdict(dict)
    with path.open(newline="") as f:
        rows = csv.reader(f)
        next(rows, None)
        for row in rows:
            if len(row) >= 3:
                power[int(row[0])][int(row[1])] = float(row[2])
    return dict(power)
