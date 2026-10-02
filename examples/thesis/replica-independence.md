# Are the thesis replicas independent?

The finality estimates treat the 30 simulations of a thesis run as 30 independent trials. In the thesis configs they are not. All 30 simulations draw their mining intervals from one shared seed, so a run is lucky or unlucky as a whole. This note measures how much that matters for the base scenario.

**In short**

- The 30 simulations of a run share their block times for the first half hour and then drift apart. Their *luck* stays shared, though: how many blocks a simulation mines is decided more by the run's seed than by the simulation (intraclass correlation 0.40).
- For the time to finality of a sample transaction, 30 replicas are worth 8 to 15 independent ones (design effect 2.0 to 3.5). A run's nominal 95% interval for the mean time to finality contained the reference value in 23 of 30 cases, against 29 of 30 with independent replicas.
- The thesis base run (seed 444) is a fast seed: 30.5 blocks per simulation, where the configured difficulty and hash power give 26.1. It is the only one of ten seeds with finality 1.000 for all three sample transactions. Its mean time to finality is the lowest of the ten for transaction 30000 and the third lowest for 20000. The point estimates still lie within their own nominal intervals of the reference values. Those intervals are too narrow by a factor of 1.4 to 1.9.
- With `node.sampler.seedUpdateTimes = {0}` the simulations are independent, and none of these effects remains.

## The seeding

All twelve thesis configs (`src/main/resources/new-config/`) set

```properties
node.sampler.seed = {444,222}
node.sampler.updateSeedFlags = {false,true}
node.sampler.seedUpdateTimes = {16800000}
sim.terminate.atTime = 16800000
```

The node sampler starts every simulation on seed 444 and would switch to a per-simulation seed (222 + simulation ID) at t = 16,800,000 ms. That is the moment the run ends, so the switch never takes effect. The workload seed does change with every simulation, so the simulations differ in their transactions but draw their mining intervals from the same stream. A mining interval is `−ln(u) · difficulty / hash power` for the next number u in that stream. The simulator warns about such configs at startup.

The simulations are still not copies of each other. When the miners start, and in what order the draws are made, depends on the transactions, so the same sequence of numbers u gets assigned to different miners at different times. The question is how much dependence survives that reshuffling.

## Method

All runs use the base config on the current engine (master at `dec01e4`). It reproduces the thesis base run exactly: the block intervals of its first 10 simulations match the 309 values in `tools/validation-rq1/clean-data/simulation_times.csv` one for one, and all 30 simulations give the same total, 916.

- **Block timing.** Ten simulations with block events, as configured (seed 444) and with independent replicas (`seedUpdateTimes = {0}`). For each pair of simulations: the share of one simulation's mined blocks that have a mined block of the other simulation within one second.
- **Run-to-run variation.** Ten runs of 30 simulations for each seeding:
  - as configured, with base seeds 444, 1444, …, 9444 (seed 444 is the thesis run);
  - independent, with per-simulation seeds from 222, 1222, …, 9222.

  For each simulation: blocks mined, and the time to finality of each sample transaction (20000, 30000, 40000) at d = 0.9 within one hour of its arrival. If the 30 simulations of a run are independent, run means vary only as much as their standard errors predict. The intraclass correlation (ICC) measures the excess. The design effect, 1 + 29 · ICC, is how many times more simulations the run needs than independent replicas would for the same precision. p is a permutation test of the one-way ANOVA F.

## Results

### Shared block times fade within an hour

Share of mined blocks with a mined block of another simulation within 1 s (10 simulations, all 90 ordered pairs):

| Minutes into the run | 0–30 | 30–60 | 60–90 | 90–120 | 120–180 | 180–280 |
|---|---|---|---|---|---|---|
| As configured | 83.3% | 16.9% | 0.0% | 0.6% | 0.0% | 0.9% |
| Independent | 0.0% | 0.6% | 0.0% | 0.0% | 0.0% | 0.5% |

As configured, every simulation finds its first block at 314,880 ms, by node 7. By the time the sample transactions arrive (about 80, 120 and 160 minutes in), block times coincide no more often than between independent simulations.

### Shared luck does not fade

| Per simulation | As configured: run means | ICC | Design effect | p | Independent: run means | ICC | Design effect | p |
|---|---|---|---|---|---|---|---|---|
| Blocks mined | 18.6 – 30.5 | +0.404 | 12.7 | < 0.001 | 25.2 – 28.7 | +0.002 | 1.05 | 0.39 |
| Time to finality, tx 20000 | 515 – 1037 s | +0.035 | 1.95 | 0.04 | 415 – 974 s | +0.025 | 1.68 | 0.08 |
| Time to finality, tx 30000 | 544 – 1162 s | +0.089 | 3.52 | < 0.001 | 551 – 841 s | −0.008 | 0.76 | 0.68 |
| Time to finality, tx 40000 | 471 – 1146 s | +0.059 | 2.65 | 0.003 | 497 – 990 s | +0.006 | 1.16 | 0.32 |

Finality itself (final within the hour or not) clusters too, for transaction 20000: 17 of the 300 configured simulations are not final, and 7 of them come from one run, seed 2444 (p = 0.001). That run estimates finality at 0.767 [0.591, 0.882], while the pooled independent value is 0.960. For transactions 30000 and 40000 the non-final simulations are spread across the runs as chance would spread them (p = 0.85 and 0.61).

### Intervals of one run are too narrow

Out of the 30 run × transaction intervals, the number that contain the pooled value of the 300 independent simulations (nominally 95%, i.e. about 28.5):

| | Finality (Wilson) | Mean time to finality (t interval) |
|---|---|---|
| As configured | 26 / 30 | 23 / 30 |
| Independent | 28 / 30 | 29 / 30 |

### Seed 444, the thesis run

| Seed 444 | Finality | Mean time to finality | Pooled independent finality | Pooled independent mean time to finality |
|---|---|---|---|---|
| tx 20000 | 1.000 [0.886, 1.000] | 523 s | 0.960 [0.931, 0.977] | 691 s |
| tx 30000 | 1.000 [0.886, 1.000] | 544 s | 0.990 [0.971, 0.997] | 666 s |
| tx 40000 | 1.000 [0.886, 1.000] | 840 s | 0.967 [0.940, 0.982] | 716 s |

The difficulty (4.90364E+23) and total hash power (7.6317E+11 GH/s) give a mean block interval of 642.5 s, or 26.1 blocks in a run's 16,800 s. The thesis run mines 30.5 per simulation, 17% more than that. Of the ten configured seeds, it mines the most blocks.

The RQ1 block-interval comparison used this run. Its 916 intervals include many repeats: 327 of them repeat a value found in another simulation, and the first interval, 5.248 min, appears 30 times.

| Block intervals | n | Mean | Median | KS distance from February 2024 |
|---|---|---|---|---|
| February 2024 (Blockchair) | 4294 | 9.72 min | 6.83 min | – |
| Thesis base run (seed 444) | 916 | 8.88 min | 5.97 min | 0.082 |
| Independent replicas (10 simulations) | 259 | 10.46 min | 6.27 min | 0.046 |

## What it means for the thesis

- **Point estimates** from one configured run are estimates for one seed's luck, not for the configured network. The base run mines blocks 17% faster than configured. Its mean time to finality is 18–24% below the reference for transactions 20000 and 30000, and 17% above it for 40000.
- **Stated uncertainty** is too small. For the per-transaction time to finality, widen the intervals by √(design effect), a factor of 1.4 to 1.9, or treat the 30 simulations as 8 to 15.
- **Comparisons between scenarios** all use seed 444. This acts like common random numbers and may make differences between scenarios more precise than absolute values. It was not measured here, and parameter changes such as difficulty or block size reshuffle the draws just as transactions do.
- **Other scenarios** use the same seeding, so the mechanism applies to all twelve. Only the base scenario was measured. With an attacker, finality depends on the block race, so a lucky or unlucky seed could matter more there.

## Recommendation

- Keep the thesis configs as they are, so that every figure stays reproducible from them.
- For new runs, including any re-run of the thesis scenarios for the CCS26 text, switch to independent replicas:

  ```sh
  java -jar target/cnsim-0.0.1-SNAPSHOT.jar -c src/main/resources/new-config/thesis.bitcoin.base.properties \
      --parallel 6 --set "node.sampler.seedUpdateTimes={0}"
  ```

  A 30-simulation base run takes 1.5 to 2 minutes on 6 cores with the current engine.

## Reproducing

```sh
mvn -B package
tools/replicas/seed_sweep.sh -o replicas.jsonl            # 10 runs per seeding, about 35 min on 6 cores
uv run --project tools/finality python tools/replicas/replicas.py analyse replicas.jsonl
```

`seed_sweep.sh -c <config>` runs the same comparison for another scenario. `analyse` prints:

- the per-run table;
- the pooled finality (Wilson interval) and mean time to finality;
- the ICC, design effect and permutation p for blocks mined and for each transaction's time to finality;
- the clustering of non-final simulations.

The tables of run-to-run variation and the pooled values above come from `analyse` with its defaults (4000 permutations, random seed 1). The block-time overlaps, the interval coverage and the RQ1 interval comparison were computed from the same runs with short one-off scripts.
