import json
import math

import pytest

from cnsim_finality import cli, forks

# Blocks 1-2-3-5 form the chain; 4 forks off 2 (a stale block at height 3); 6 and 7 both sit on 5,
# so a node that has both has not decided yet at height 5.
CHAIN = {1: (-1, 1), 2: (1, 2), 3: (2, 3), 5: (3, 4)}
WITH_STALE = {**CHAIN, 4: (2, 3)}


def test_main_chain_follows_the_highest_block():
    assert forks.main_chain(WITH_STALE) == {1, 2, 3, 5}


def test_main_chain_stops_below_an_undecided_tie():
    tied = {**CHAIN, 6: (5, 5), 7: (5, 5)}
    assert forks.main_chain(tied) == {1, 2, 3, 5}


def test_settled_chain_is_what_every_node_has_on_its_main_chain():
    behind = {1: (-1, 1), 2: (1, 2), 4: (2, 3)}  # this node still follows the stale block
    assert forks.settled_chain([WITH_STALE, CHAIN, behind]) == {1, 2}


def test_analyse_counts_stale_forked_and_pending_blocks():
    a = {**WITH_STALE, 6: (5, 5)}
    b = {**CHAIN, 7: (5, 5)}  # nodes disagree at height 5: pending, not stale
    f = forks.analyse({1: a, 2: b}, mined=[1, 2, 3, 4, 5, 6, 7, 8])
    assert (f.mined, f.settled, f.stale, f.pending, f.unplaced) == (8, 4, 1, 2, 1)
    assert f.forked_heights == 1  # height 3
    assert f.longest_stale_branch == 1
    assert f.stale_rate == pytest.approx(1 / 5)


def test_longest_stale_branch_counts_blocks_in_a_row():
    # 1-2-3-4-5 wins; 10-11 branch off 1 and lose
    tree = {1: (-1, 1), 2: (1, 2), 3: (2, 3), 4: (3, 4), 5: (4, 5), 10: (1, 2), 11: (10, 3)}
    f = forks.analyse({1: tree})
    assert (f.stale, f.forked_heights, f.longest_stale_branch, f.unplaced) == (2, 2, 2, None)


def test_predicted_fork_rate():
    # two equal miners: the other one has half the hash power, so 1 - exp(-D / (2T))
    assert forks.predicted_fork_rate([5, 5], 1, 10) == pytest.approx(1 - math.exp(-0.05))
    # a single miner never forks with itself
    assert forks.predicted_fork_rate([3], 1, 10) == 0
    # n equal miners: 1 - exp(-(1 - 1/n) D / T)
    assert forks.predicted_fork_rate([1] * 10, 2, 60) == pytest.approx(1 - math.exp(-0.9 * 2 / 60))


RUN = "2026.01.01 00.00.00"


def write_run(tmp_path):
    """Two nodes, one simulation ending at 100 s; WITH_STALE on node 1, CHAIN on node 2."""
    run = tmp_path / RUN
    run.mkdir()
    rows = ["SimID, SimTime, SysTime, NodeID, BlockID, ParentBlockID, Height, Content, Place"]
    for node, tree in ((1, WITH_STALE), (2, CHAIN)):
        rows += [f"1,100000,5,{node},{b},{p},{h},{{}}, blockchain" for b, (p, h) in tree.items()]
    rows.append("1,100000,5,2,9,8,-1,{}, orphans")
    (run / f"StructureLog - {RUN}.csv").write_text("\n".join(rows) + "\n")
    head = "SimID, SimTime,SysTime,NodeID,BlockID,ParentID,Height,BlockContent,EvtType,Difficulty,Cycles"
    mined = [
        f"1,{10000 * b},1,{1 + b % 2},{b},-1,0,{{}},Node Completes Validation,1.0,1.0"
        for b in (1, 2, 3, 4, 5)
    ]
    (run / f"BlockLog - {RUN}.csv").write_text("\n".join([head, *mined]) + "\n")
    nodes = ["SimID, NodeID, HashPower (GH/s), ElectricPower (W), ElectricityCost (USD/kWh), TotalCycles"]
    nodes += ["1,1,10.0,1.0,0.1,1.0", "1,2,10.0,1.0,0.1,1.0"]
    (run / f"Nodes - {RUN}.csv").write_text("\n".join(nodes) + "\n")
    return run


def test_forks_command(tmp_path, capsys):
    write_run(tmp_path)
    assert cli.main(["forks", str(tmp_path), "--delay", "1", "--json"]) == 0  # output dir with one run
    r = json.loads(capsys.readouterr().out)["runs"][0]
    assert (r["mined"], r["settled"], r["stale"], r["unplaced"], r["forked_heights"]) == (5, 4, 1, 0, 1)
    assert r["stale_rate"] == pytest.approx(0.2)
    assert r["fork_rate"] == pytest.approx(0.25)
    assert r["mean_interval_s"] == pytest.approx(20.0)  # 100 s / 5 blocks
    assert r["predicted_fork_rate"] == pytest.approx(1 - math.exp(-0.5 / 20))


def test_forks_table(tmp_path, capsys):
    run = write_run(tmp_path)
    assert cli.main(["forks", str(run), "--labels", "toy"]) == 0
    out = capsys.readouterr().out.splitlines()
    assert out[2].split()[:5] == ["toy", "1", "20.0s", "5", "1"]


def test_delay_needs_the_nodes_log(tmp_path):
    run = write_run(tmp_path)
    (run / f"Nodes - {RUN}.csv").unlink()
    with pytest.raises(SystemExit, match="Nodes log"):
        cli.main(["forks", str(run), "--delay", "1"])
