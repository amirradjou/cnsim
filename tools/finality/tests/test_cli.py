import json

from cnsim_finality import cli

RUN = "2026.01.01 00.00.00"


def write_run(tmp_path, believes_by_sim, arrivals):
    """A run directory with 2 nodes, one transaction (7), reports every 10 s."""
    run = tmp_path / RUN
    run.mkdir()
    lines = ["SimID, Node ID, Transaction ID, Believes, Time (ms from start)"]
    for sim, per_time in believes_by_sim.items():
        for t, (a, b) in per_time:
            lines.append(f"{sim},1,7,{str(a).lower()},{t}")
            lines.append(f"{sim},2,7,{str(b).lower()},{t}")
    (run / f"BeliefLog - {RUN}.csv").write_text("\n".join(lines) + "\n")
    inp = ["SimID, TxID, Size (bytes), Value (coins), ArrivalTime (ms)"]
    inp += [f"{sim},7,250.0,1000.0,{t}" for sim, t in arrivals.items()]
    (run / f"Input - {RUN}.csv").write_text("\n".join(inp) + "\n")
    return run


def run_json(argv, capsys):
    assert cli.main(argv + ["--json"]) == 0
    return json.loads(capsys.readouterr().out)["runs"][0]


def test_finality_counts_simulations_and_times_from_arrival(tmp_path, capsys):
    beliefs = {
        1: [(10000, (False, False)), (20000, (True, True)), (30000, (True, True))],  # final at 20 s
        2: [(10000, (True, False)), (20000, (True, True)), (30000, (True, False))],  # drops back
    }
    run = write_run(tmp_path, beliefs, {1: 5000, 2: 5000})
    out = run_json(["finality", str(run), "--horizon", "25", "--threshold", "0.9,0.5"], capsys)
    at09, at05 = out["results"]
    assert (at09["simulations"], at09["final"], at09["finality"]) == (2, 1, 0.5)
    assert at09["time_to_finality_median_s"] == 15.0  # 20 s report - 5 s arrival
    assert at05["final"] == 2
    assert at05["time_to_finality_median_s"] == 10.0  # sim 1: 20 - 5 = 15 s, sim 2: 10 - 5 = 5 s


def test_runs_that_end_before_the_horizon_are_censored(tmp_path, capsys):
    beliefs = {1: [(10000, (True, True))], 2: [(10000, (True, True)), (90000, (True, True))]}
    write_run(tmp_path, beliefs, {1: 0, 2: 0})
    out = run_json(["finality", str(tmp_path), "--horizon", "60"], capsys)  # output dir with one run
    r = out["results"][0]
    assert (r["censored"], r["simulations"], r["final"]) == (1, 1, 1)


def test_belief_table(tmp_path, capsys):
    beliefs = {1: [(0, (False, False)), (60000, (True, False)), (120000, (True, True))]}
    run = write_run(tmp_path, beliefs, {1: 0})
    assert cli.main(["belief", str(run), "--step", "60"]) == 0
    rows = capsys.readouterr().out.strip().splitlines()
    assert rows[0].startswith("run,transaction,seconds_since_t0,agg_deg_belief")
    assert [r.split(",")[3] for r in rows[1:]] == ["0.0000", "0.5000", "1.0000"]


def test_several_runs_are_reported_side_by_side(tmp_path, capsys):
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    a = write_run(tmp_path / "a", {1: [(10000, (True, True))]}, {1: 0})
    b = write_run(tmp_path / "b", {1: [(10000, (False, False))]}, {1: 0})
    assert cli.main(["finality", str(a), str(b), "--labels", "honest,attacked", "--json"]) == 0
    runs = json.loads(capsys.readouterr().out)["runs"]
    assert [(r["label"], r["results"][0]["finality"]) for r in runs] == [("honest", 1.0), ("attacked", 0.0)]
