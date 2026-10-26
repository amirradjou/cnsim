"""Forks and stale blocks: the blocks that were mined but did not end up in the chain.

A node's final structure (StructureLog) is a tree of the blocks it placed. Its main chain is the
path from its highest block back to the root; when several blocks share the top height the node
has not decided yet, so only the part of the chain common to all of them counts. The *settled
chain* of a simulation is the part every node agrees on: the blocks on every node's main chain.
Up to the settled tip, every other block is *stale*. Blocks above the settled tip are still in
play when the simulation ends and are left out.

A height is *forked* when more than one block was placed at it. In the classic model of Decker
and Wattenhofer (2013), a fork happens when another miner finds a block at the same height
before the first one reaches it. With a fixed delay D for a block to reach every node, mean
block interval T and hash-power shares s_a, the first block at a height comes from miner a with
probability s_a, and the others keep mining on the old tip for D more, so

    P(fork) = 1 - sum_a s_a * exp(-(1 - s_a) * D / T)

This is exact when all nodes have the parent before the race starts; otherwise (the parent was
itself found within D of the previous block) it overestimates slightly, by O((D/T)^2).
"""

from __future__ import annotations

import math
from collections import Counter
from collections.abc import Iterable, Mapping
from dataclasses import dataclass

# block -> (parent, height) for the blocks one node placed in its blockchain
Tree = Mapping[int, tuple[int, int]]


def path_to_root(tree: Tree, block: int) -> list[int]:
    """The block and its ancestors in tree, tip first."""
    path = []
    while block in tree:
        path.append(block)
        block = tree[block][0]
    return path


def main_chain(tree: Tree) -> set[int]:
    """The blocks on the node's main chain whichever of its highest blocks it settles on."""
    if not tree:
        return set()
    top = max(height for _, height in tree.values())
    chains = [set(path_to_root(tree, b)) for b, (_, height) in tree.items() if height == top]
    return set.intersection(*chains)


def settled_chain(trees: Iterable[Tree]) -> set[int]:
    """The blocks on every node's main chain."""
    chains = [main_chain(t) for t in trees]
    return set.intersection(*chains) if chains else set()


@dataclass(frozen=True)
class SimForks:
    """Forks and stale blocks of one simulation."""

    mined: int  # blocks mined (BlockLog), or placed anywhere when there is no BlockLog
    settled: int  # blocks on the settled chain = its height
    stale: int  # blocks placed by some node at or below the settled tip, not on the settled chain
    pending: int  # blocks placed above the settled tip: undecided when the simulation ended
    unplaced: int | None  # mined blocks no node placed (withheld, orphaned, rejected); None w/o BlockLog
    forked_heights: int  # settled heights at which more than one block was placed
    longest_stale_branch: int  # most stale blocks in a row on one branch (0 if none)

    @property
    def stale_rate(self) -> float:
        """Stale blocks per decided block (settled + stale)."""
        decided = self.settled + self.stale
        return self.stale / decided if decided else math.nan


def analyse(trees: Mapping[int, Tree], mined: Iterable[int] | None = None) -> SimForks:
    """Fork statistics of one simulation from each node's final tree and the mined blocks."""
    placed: dict[int, tuple[int, int]] = {}
    for tree in trees.values():
        placed.update(tree)
    settled = settled_chain(trees.values())
    tip = max((placed[b][1] for b in settled), default=0)
    stale = {b for b, (_, h) in placed.items() if h <= tip and b not in settled}
    per_height = Counter(h for _, h in placed.values() if h <= tip)
    forked = sum(1 for b in settled if per_height[placed[b][1]] > 1)

    depth: dict[int, int] = {}

    def branch_depth(b: int) -> int:
        # stale branches are short in practice, but walk iteratively to stay safe
        chain = []
        while b in stale and b not in depth:
            chain.append(b)
            b = placed[b][0]
        d = depth.get(b, 0)
        for x in reversed(chain):
            d += 1
            depth[x] = d
        return d

    longest = max((branch_depth(b) for b in stale), default=0)
    mined_set = set(mined) if mined is not None else None
    return SimForks(
        mined=len(mined_set) if mined_set is not None else len(placed),
        settled=len(settled),
        stale=len(stale),
        pending=sum(1 for _, h in placed.values() if h > tip),
        unplaced=len(mined_set - placed.keys()) if mined_set is not None else None,
        forked_heights=forked,
        longest_stale_branch=longest,
    )


def predicted_fork_rate(shares: Iterable[float], delay: float, interval: float) -> float:
    """1 - sum_a s_a exp(-(1 - s_a) delay / interval), for shares that need not sum to 1."""
    s = list(shares)
    total = sum(s)
    if total <= 0 or interval <= 0:
        return math.nan
    return 1 - sum((x / total) * math.exp(-(1 - x / total) * delay / interval) for x in s)
