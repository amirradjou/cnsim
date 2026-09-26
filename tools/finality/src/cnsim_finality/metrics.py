"""The finality estimators of the CNSim method (CCS26, section 3).

A community of nodes believes a transaction f to a degree ``degBelief(f, t)`` in [0, 1]; in
CNSim it is the fraction of nodes whose main chain contains f. Transaction f is *final* within
horizon t_h at threshold d if, at some time before t_h, the degree of belief reaches d and does
not drop below it again (up to t_h). Over K independent simulations, each is a Bernoulli trial,
so the estimated finality is the share of simulations in which f is final, and the time to
finality is taken in each simulation where it is.

All functions here work on one simulation's belief series: a list of (time, degree) pairs in
increasing time order, as reported every ``sim.reporting.beliefReportInterval``.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass
from statistics import NormalDist

Series = Sequence[tuple[float, float]]


def window(series: Series, t0: float, th: float) -> list[tuple[float, float]]:
    """The reports with t0 <= time <= th."""
    return [(t, v) for t, v in series if t0 <= t <= th]


def is_final(series: Series, d: float, t0: float, th: float) -> bool | None:
    """Whether f is final at threshold d within [t0, th], or None if no report falls in it.

    With discrete reports, 'reaches d and stays there until th' holds exactly when the last
    report at or before th is at least d.
    """
    w = window(series, t0, th)
    if not w:
        return None
    return w[-1][1] >= d


def time_to_finality(series: Series, d: float, t0: float, th: float) -> float | None:
    """Time from t0 to the start of the final stretch of reports >= d, or None if not final."""
    w = window(series, t0, th)
    if not w or w[-1][1] < d:
        return None
    start = w[-1][0]
    for t, v in reversed(w):
        if v < d:
            break
        start = t
    return start - t0


@dataclass(frozen=True)
class Estimate:
    """A binomial proportion with its confidence interval."""

    successes: int
    trials: int
    low: float
    high: float

    @property
    def value(self) -> float:
        return self.successes / self.trials if self.trials else math.nan


def proportion(successes: int, trials: int, confidence: float = 0.95, method: str = "wilson") -> Estimate:
    """Maximum-likelihood estimate successes/trials with a Wilson or Wald interval.

    The paper reports Wald intervals; Wilson behaves better near 0 and 1 and with few trials,
    which is the usual situation for finality (often all or none of the simulations are final).
    """
    if trials == 0:
        return Estimate(0, 0, math.nan, math.nan)
    z = NormalDist().inv_cdf(0.5 + confidence / 2)
    p = successes / trials
    if method == "wald":
        half = z * math.sqrt(p * (1 - p) / trials)
        return Estimate(successes, trials, max(0.0, p - half), min(1.0, p + half))
    if method != "wilson":
        raise ValueError(f"unknown interval method {method!r}")
    denom = 1 + z * z / trials
    centre = (p + z * z / (2 * trials)) / denom
    half = z * math.sqrt(p * (1 - p) / trials + z * z / (4 * trials * trials)) / denom
    low = 0.0 if successes == 0 else max(0.0, centre - half)  # exact at the ends
    high = 1.0 if successes == trials else min(1.0, centre + half)
    return Estimate(successes, trials, low, high)


def belief_at(series: Series, t: float) -> float | None:
    """The degree of belief at time t: the last report at or before t (None before the first)."""
    value = None
    for time, v in series:
        if time > t:
            break
        value = v
    return value


def quantile(values: Sequence[float], q: float) -> float:
    """Linear-interpolation quantile (as numpy's default) of a non-empty sequence."""
    s = sorted(values)
    pos = (len(s) - 1) * q
    lo, hi = math.floor(pos), math.ceil(pos)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)
