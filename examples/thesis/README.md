# Thesis experiment artifact

Everything in this directory tree that is needed to re-run the experiments in

> Amirreza Radjou, *Simulation-based Evaluation of Transaction Finality in
> Bitcoin using CNSim*, MSc thesis, York University, 2025
> (also the basis of the CCS26 submission on the CNS framework).

The simulator is the code in this repository at tag `thesis-v1.0-artifact`.
The raw run logs (about 49 GB of BeliefLog / StructureLog / EventLog CSVs)
are **not** in git; see "Run logs" at the end.

## Build and run

```sh
export JAVA_HOME=/path/to/jdk-21-or-newer   # the ./comp, ./do, ./run wrappers assume this is set
mvn -B package                               # 88 tests, produces target/cnsim-0.0.1-SNAPSHOT.jar
java -jar target/cnsim-0.0.1-SNAPSHOT.jar -c src/main/resources/new-config/thesis.bitcoin.base.properties
```

Each config writes `BeliefLog`, `BlockLog`, `StructureLog`, `EventLog`,
`NetLog`, `Nodes`, `Input`, `Config` (all `- <run id>.csv`) and
`ErrorLog - <run id>.txt` into `<sim.output.directory>/<run id>/`, where
`<run id>` is the wall-clock start time, e.g. `2025.06.18 11.36.16`. A 30-simulation run of one scenario takes
a few hours and produces roughly 2 GB.

`run_configs.sh` at the repository root runs a list of configs back to back.

## Two config sets

| Directory | Sims | Output dir | Nodelist | Used for |
|---|---|---|---|---|
| `src/main/resources/bitcoin-config/thesis.bitcoin.*.properties` | 10 | `./thesis-log/<scenario>/` | `src/main/resources/nodelist*.csv` | June 2025 pilot runs; the attacker fractions quoted in the thesis text (16.61 / 29.28 / 64.73 %) come from these nodelists |
| `src/main/resources/new-config/thesis.bitcoin.*.properties` | 30 | `./new-thesis-log/<scenario>/` (`./new-thesis-log-newer/` for malicious) | `src/main/resources/new-nodelist/*.csv` | **Final runs behind every figure in the thesis** (attacker fractions 16.78 / 29.68 / 66.25 %) |

Apart from `sim.numSimulations`, `sim.output.directory` and
`node.sampler.file` the two sets are identical. Every variant differs from
`thesis.bitcoin.base.properties` in exactly one parameter:

| Scenario (output dir) | Config file | Parameter changed from base |
|---|---|---|
| base | `thesis.bitcoin.base.properties` | (11 honest nodes, 4.90364E+23 difficulty, 25 Mbps, lambda 4.161 tx/s, 1 MB blocks, 16,800,000 ms, 75,000 tx) |
| increase-difficulty | `thesis.bitcoin.difficulty.double.properties` | `pow.difficulty = 6.90364E+23` |
| decrease-difficulty | `thesis.bitcoin.difficulty.half.properties` | `pow.difficulty = 2.90364E+23` |
| blocksize-double | `thesis.bitcoin.blocksize_double.properties` | `bitcoin.maxBlockSize = 2000000` |
| blocksize-half | `thesis.bitcoin.blocksize_half.properties` | `bitcoin.maxBlockSize = 500000` |
| increase-throughput | `thesis.bitcoin.propagationTime_half.properties` | `net.throughputMean = 100000001f` (100 Mbps) |
| decrease-throughput | `thesis.bitcoin.propagationTime_double.properties` | `net.throughputMean = 1000000f` (1 Mbps) |
| rate-increase | `thesis.bitcoin.rate_increase.properties` | `workload.lambda = 8.161f` |
| rate-decrease | `thesis.bitcoin.rate_decrease.properties` | `workload.lambda = 2.161f` |
| malicious | `thesis.bitcoin.malicious.first.properties` | 10 honest + 1 attacker (`nodelist-malicious1.csv`), target tx 20000 |
| malicious2 | `thesis.bitcoin.malicious.second.properties` | 9 honest + 1 attacker (`nodelist-malicious2.csv`), target tx 20000 |
| malicious3 | `thesis.bitcoin.malicious.third.properties` | 9 honest + 1 attacker (`nodelist-malicious3.csv`), target tx 20000 |

The `propagationTime_*` file names are historical: `net.propagationTime` is
no longer read by the engine, and what these two configs vary is the
end-to-end throughput, which is how the thesis describes them.

## Figure provenance

Run IDs are the `<run id>` directory names under the scenario output
directory of the **new-config** set. Every figure below was matched to its
producing script output by checksum.

### RQ1 (fidelity of the base scenario)

| Figure in thesis | Produced by | Input |
|---|---|---|
| `base_AcceptanceRatio.png` | `tools/postprocessing/rq3-settlement/main.py` (per-scenario acceptance-ratio plot) | base run `2025.06.18 11.36.16` |
| `ecdf_comparison_plot.png` | `tools/validation-rq1/clean-data/cdf.py` | `simulation_times.csv` (block intervals of base run `2025.06.18 11.36.16`, via `filter.py` then `blocktime.py`) vs `february_bitcoin.csv` (Blockchair daily block exports for February 2024, via `feb/clean.py` then `feb/convert.py`) |

### RQ2 (majority attack, one attacker)

| Figure in thesis | Produced by | Runs |
|---|---|---|
| `RQ2/scenario{1,2,3}/Compare_malicious{,2,3}_vs_Base.png` | `tools/postprocessing/rq2-malicious/main.py` then `comparison_plotter.py` (threshold 0.95, tx 1..50001, longest-chain settlement) | base `2025.06.18 11.36.16`; malicious `2025.07.14 14.05.25`; malicious2 `2025.07.14 17.02.27`; malicious3 `2025.07.14 19.49.17` (the July runs live under `new-thesis-log-newer/`) |
| `RQ2/scenario{1,2,3}/confidence_plot_tx_20000.png` | `tools/postprocessing/rq2-malicious/finality_percentage_target_compare.py` (threshold 0.90, output `analysis_results_final_90/comparison_base_vs_malicious*/`) | same runs |
| `Master_Analysis_Summary_LongestChain.csv` (time-to-settle table) | `tools/postprocessing/rq2-malicious/main.py` | same runs |

### RQ3 (network and protocol parameters)

| Figure in thesis | Produced by | Runs |
|---|---|---|
| `comparison_plots/Compare_<scenario>_vs_Base.png` for increase/decrease-difficulty, blocksize-double/half, increase/decrease-throughput, rate-increase/decrease | `tools/postprocessing/rq3-settlement/main.py` then `comparison_plotter.py` (threshold 0.90, tx 1..20000) | base `2025.06.18 11.36.16`; blocksize-double `2025.06.18 13.10.33`; blocksize-half `2025.06.18 14.37.16`; increase-difficulty `2025.06.18 18.02.24`; decrease-difficulty `2025.06.18 20.38.04`; decrease-throughput `2025.06.19 06.11.56`; increase-throughput `2025.06.19 08.07.57`; rate-decrease `2025.06.19 10.11.58`; rate-increase `2025.06.19 10.33.55` (malicious `2025.06.18 21.38.23`, malicious2 `2025.06.19 00.11.29`, malicious3 `2025.06.19 03.13.19` were in the same batch but superseded by the July runs above) |
| `Master_Analysis_Summary.csv` (time-to-settle table) | `tools/postprocessing/rq3-settlement/main.py` | same runs |

The BeliefLog-based confidence-over-time plots
(`tools/beliefAnalysis/finality_modified.py`, `finality_3plot.py`) were used
on the June pilot runs (`thesis-log/<scenario>/`) during development and are
kept for reference; the run stamps hard-coded in those scripts are the pilot
run IDs.

## Running the post-processing

Both `main.py` scripts expect to be run from a directory whose immediate
subdirectories are scenario names, each containing one `<run id>/`
directory with the `StructureLog - *.csv` and `EventLog - *.csv` of that run:

```
work/
  base/2025.06.18 11.36.16/{StructureLog,EventLog} - 2025.06.18 11.36.16.csv
  malicious/2025.07.14 14.05.25/...
  ...
```

```sh
cd work
python3 /path/to/tools/postprocessing/rq2-malicious/main.py        # writes <scenario>_SettlementTime_LongestChain.csv per run + Master_Analysis_Summary_LongestChain.csv
python3 /path/to/tools/postprocessing/rq2-malicious/comparison_plotter.py   # writes comparison_plots/Compare_<scenario>_vs_Base.png
```

Python dependencies: pandas, numpy, matplotlib, seaborn (any recent version;
nothing is pinned).

## Run logs

The raw logs are kept outside git on the author's machine and should be
archived as a release asset (or on Zenodo) alongside this tag:

| Path (relative to `cnsim-updated/cnsim/`) | Size | Contents |
|---|---|---|
| `new-thesis-log/` | 25 GB | 30-sim runs of all 12 scenarios, June 18-19 2025 (RQ3 figures, RQ1 base run) |
| `new-thesis-log-newer/` | 12 GB | 30-sim malicious re-runs, July 10-14 2025 (RQ2 figures); includes `malicious-logs.zip` (690 MB) |
| `new-thesis-log2/` | 2 GB | one intermediate malicious run, July 9 2025 |
| `thesis-log/` | 9.8 GB | 10-sim June pilot runs plus `analysis_results*` from the BeliefLog scripts |
| `../PostProcessing/` | 186 GB | working copies of the logs split per scenario plus every analysis attempt (`test*`, `test-Jul8`, `test-July10*`) |
| `../ScriptLogs/Juul15-malicious/` | (in the 76 GB `ScriptLogs/`) | final RQ2 analysis outputs |
| `../validation-RQ1/EventLog - 2025.06.18 11.36.16.csv` | 1.45 GB | base-run EventLog behind the RQ1 block-interval validation |
