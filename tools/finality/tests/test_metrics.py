import math

import pytest

from cnsim_finality import metrics

TIMES = [0, 10, 20, 30, 40, 50, 60]  # t0 ... t6 = horizon


def series(values):
    return list(zip(TIMES, values, strict=True))


# The three copies of the paper's Figure 1 (CCS26, section 3.2).
S1 = series([0, 0.2, 0.4, 0.8, 1.0, 1.0, 1.0])  # passes 0.9 at t4 and stays
S2 = series([0, 0.4, 0.6, 1.0, 0.6, 0.4, 0.4])  # passes 0.9 at t3, drops at t4
S3 = series([0, 0, 0.2, 0.2, 0.4, 0.6, 0.8])  # gets there after the horizon


def test_paper_figure_1_finality_is_one_third_at_d_09():
    finals = [metrics.is_final(s, 0.9, 0, 60) for s in (S1, S2, S3)]
    assert finals == [True, False, False]
    assert metrics.proportion(sum(finals), 3).value == pytest.approx(1 / 3)


def test_paper_figure_1_time_to_finality():
    assert metrics.time_to_finality(S1, 0.9, 0, 60) == 40  # t4 at d = 0.9
    assert metrics.time_to_finality(S1, 0.7, 0, 60) == 30  # t3 at d = 0.7
    assert metrics.time_to_finality(S2, 0.9, 0, 60) is None


def test_horizon_and_start_limit_the_window():
    assert metrics.is_final(S3, 0.7, 0, 60) is True
    assert metrics.is_final(S3, 0.7, 0, 50) is False, "horizon before it gets there"
    assert metrics.time_to_finality(S1, 0.9, 10, 60) == 30, "measured from t0 = 10"
    assert metrics.is_final(S1, 0.9, 70, 90) is None, "no report in the window"


def test_wilson_interval_matches_known_values():
    e = metrics.proportion(1, 3, 0.95)
    assert (round(e.low, 4), round(e.high, 4)) == (0.0615, 0.7923)
    none = metrics.proportion(0, 10)
    assert none.low == 0 and none.high == pytest.approx(0.2775, abs=1e-4)
    alls = metrics.proportion(10, 10)
    assert alls.high == 1 and alls.low == pytest.approx(0.7225, abs=1e-4)


def test_wald_interval_is_clipped_to_zero_one():
    e = metrics.proportion(1, 3, 0.95, "wald")
    assert e.low == 0
    assert e.high == pytest.approx(1 / 3 + 1.959964 * math.sqrt(2 / 27), abs=1e-4)
    with pytest.raises(ValueError):
        metrics.proportion(1, 3, 0.95, "exact")


def test_no_trials_gives_nan():
    assert math.isnan(metrics.proportion(0, 0).value)


def test_belief_at_takes_the_last_report_at_or_before():
    assert metrics.belief_at(S1, 35) == 0.8
    assert metrics.belief_at(S1, 40) == 1.0
    assert metrics.belief_at(S1, -1) is None


def test_quantile_interpolates_like_numpy():
    assert metrics.quantile([1, 2, 3, 4], 0.5) == 2.5
    assert metrics.quantile([5], 0.95) == 5
    assert metrics.quantile([0, 10], 0.05) == pytest.approx(0.5)
