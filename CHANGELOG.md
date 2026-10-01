# Changelog

## Unreleased (since `thesis-v1.0-artifact`)

The thesis and CCS26 results were produced with the code at tag `thesis-v1.0-artifact`; check it out to reproduce them exactly. The entries below marked **results** change simulated outcomes for some configurations. All other changes leave the output of existing configs byte-identical; the golden run (`tools/golden/`) guards this.

### Fixed

- **results: the double-spend attacker mined at twice its hash rate** after mining the target's block itself. It kept two mining events alive for the rest of the run. In the thesis `malicious.second` scenario (q ≈ 0.30) the attack succeeded in 20% of 30 simulations, where the block race allows 11%.
- **results: the attacker wasted every block after giving up.** Its own target block's transactions stayed in its pool, so each later block repeated main-chain transactions and was discarded.
- **results: a competing genesis block could freeze a node.** A node that had its own first block discarded or re-parented another node's first block, and everything built on it stayed an orphan there forever.
- **results (opt-in, `bitcoin.reorg.restoreTransactions`): transactions of abandoned blocks were lost** after a reorg. No node would mine them again. With the flag they return to the pool.
- **results (only with a mid-run seed switch): pending mining events survived a seed switch**, so simulations meant to diverge at that point still shared their next blocks. They are now redrawn at the switch. Configs that switch at t = 0 or never are unaffected.
- The NetLog of every simulation after the first logged its links at the previous simulation's end time, and EventLog IDs kept counting across simulations; the clock and event IDs now restart with each simulation.
- `TxValuePerSizeComparator`, `TxValueComparator` and `TxSizeComparator` violated the `Comparator` contract for equal keys. No tested thesis scenario changed.
- `MaliciousNodeBehaviorTest` only passed after another test had loaded the configuration; `Config.init` now replaces earlier configuration.

### Added

- **Peer-to-peer overlays** (`net.topology`: `random-outbound`, `random-regular`, `erdos-renyi`, `small-world`, `scale-free`, `file`) with per-link throughput and latency, a relay delay, and delays computed as the fastest store-and-forward path. Also **link latency** for the original complete network (`net.latencyMean/SD`).
- **Proof of stake**: an Ouroboros-Praos-style slot lottery (`consensus.leaderElection = slot-lottery`).
- **`pow.targetBlockInterval`**: states the block interval directly; the difficulty is derived.
- **Presets for seven chains** in `examples/networks/`: Bitcoin, Bitcoin Cash, Litecoin, Dogecoin, Zcash, Ethereum Classic and Cardano, with measured block intervals and stale rates.
- **Selfish mining** (Eyal and Sirer), `consensus.tieBreak = first-seen`, and attack experiments in `examples/attacks/` checked against theory: the selfish revenue formula, and the exact double-spend race probability.
- Configurable attack thresholds (`bitcoin.attack.minChainLength/maxChainLength`); attack abandonment is logged.
- **`cnsim-finality`** (`tools/finality/`): the CNSim paper's estimators (finality, time to finality, aggregate belief), tested on the paper's own example.
- `tools/demo.sh`: double-spend finality by attacker share in one command.
- **Provenance** file with every run: commit, uncommitted-changes flag, Java version, command line, and input-file hashes.
- `--set key=value` command-line overrides.
- **`--parallel N`**: the simulations of a run in N processes, with merged logs identical to a sequential run's.
- A warning when simulations would never diverge (the thesis configs switch seeds only at the end).
- CI: build and tests on JDK 21 and 25, the golden run, a smoke run of every shipped config, the analysis tool's tests, and shellcheck.

### Changed

- **About 28× faster**: one simulation of the thesis base config takes 9.5 s instead of 269 s, with byte-identical output. The mempool keeps its fee-rate order and an ID index between receipts; chain lookups use ID sets.
- **Logs stream to disk** instead of being held in memory until the end, so memory no longer grows with the number of simulations.
- The build requires JDK 21 or newer (enforced), runs tests in a forked JVM, and no longer pulls in JUnit 4.
