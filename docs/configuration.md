# Configuration reference

A run is configured by a Java properties file (`-c file.properties`). Command-line options override it: `--sims N`, `--out DIR/`, and `--set key=value` (repeatable) for any key below. Keys marked *required* must be present; the others have the default shown. Units: time in milliseconds, sizes in bytes, throughput in bits per second, hash power in GH/s.

The effective configuration of every run is saved as `Config - <run id>.csv`, and `Provenance - <run id>.json` records the build, command line and input-file hashes.

Configuration is checked before the run starts. A missing required key, a value of the wrong type (including a boolean other than `true`/`false`), an input file that does not exist, or an unknown command-line option stops the run with a one-line message and exit code 2. A key that the simulator does not read is reported as a warning, with the closest known key when it looks like a typo (`worlkoad.targetTransaction` → `workload.targetTransaction`). Keys of earlier versions that have no effect (`net.propagationTime`, `sim.maxTransactions`, `node.createMaliciousNode`, `tangle.*`, ...) are listed in one note.

## Simulation

| Key | Default | Meaning |
|---|---|---|
| `sim.numSimulations` | 1 | Number of simulations (replicas) in the run. |
| `sim.terminate.atTime` | *required* | Simulated time at which each simulation stops. |
| `sim.maxNodes` | *required* | Upper bound on node IDs; sizes the network matrices. |
| `sim.output.directory` | `./log/` | Where the run directory `<run id>/` is created. |
| `sim.reporting.beliefReportInterval` | *required* | How often every node reports its belief in each sample transaction. |
| `sim.reporting.beliefReportOffset` | *required* | Belief reports continue this long after the last scheduled workload event. |
| `sim.reporting.window` | *required* | Events between periodic node reports (no effect for Bitcoin nodes). |
| `sim.initialMiningPoolTransactions` | 0 | Transactions placed in every node's pool before the workload starts. |
| `sim.parallelism` | 1 | Run the simulations in this many processes (`-p N` / `--parallel N`); the logs are merged and are the same as a sequential run's. Each process gets an even share of half the physical memory unless `-Xmx` is given. |
| `sim.firstSimID` | 1 | ID of the first simulation (set per process by `--parallel`; seeds derive from the ID). |

## Block production

| Key | Default | Meaning |
|---|---|---|
| `consensus.leaderElection` | `pow` | `pow`: proof of work. `slot-lottery`: Ouroboros-Praos-style proof of stake (node hash-power values are read as stake). |
| `pow.targetBlockInterval` | – | Mean block interval; the difficulty is derived from the total hash power of the created nodes. Use this or `pow.difficulty`. |
| `pow.difficulty` | – | Search space / success space. Mean interval = difficulty / (total GH/s × 10⁹) seconds. |
| `pow.hashPowerMean`, `pow.hashPowerSD` | *required* | Normal distribution (truncated at 0) of node hash power, for nodes not given in `node.sampler.file`. |
| `pos.slotDuration` | 1000 | Slot length for `slot-lottery`. |
| `pos.activeSlotCoefficient` | 0.05 | f: probability that a slot has a leader. A node with stake share α leads a slot with probability 1 − (1 − f)^α. |
| `consensus.tieBreak` | `legacy` | Between equally long branches: `legacy` (tip list order; after placing a block, the highest block ID) or `first-seen` (the tip received first, as in Bitcoin Core). |

## Blocks and transactions (Bitcoin engine)

| Key | Default | Meaning |
|---|---|---|
| `bitcoin.maxBlockSize` | *required* | Block size limit; blocks take the pool's highest fee-per-byte transactions up to it. |
| `bitcoin.minValueToMine` | *required* | Mine only while the pool's total fee value exceeds this. `-1` mines continuously, including empty blocks. |
| `bitcoin.minSizeToMine` | *required* | Read but not used. |
| `bitcoin.blockProcessingDelayPerByte` | 0 | Extra delay per byte when a block is received. |
| `bitcoin.txProcessingDelayPerByte` | 0 | Extra delay per byte when a transaction is received. |
| `bitcoin.reorg.restoreTransactions` | false | On a reorg, return transactions of abandoned blocks to the pool. Without it they are lost, which matters when forks are frequent. |

## Workload

| Key | Default | Meaning |
|---|---|---|
| `workload.lambda` | *required* | Poisson arrival rate, transactions per second. |
| `workload.numTransactions` | *required* | Transactions generated per simulation. |
| `workload.txSizeMean`, `workload.txSizeSD` | *required* | Normal distribution of transaction size (at least 10 bytes). |
| `workload.txFeeValueMean`, `workload.txFeeValueSD` | *required* | Normal distribution of transaction fee value. |
| `workload.sampleTransaction` | *required* | `{id, id, ...}`: transactions whose belief is reported (the BeliefLog). |
| `workload.sampler.file` | – | CSV workload (`id,time,value,size,node`) instead of generated transactions. |
| `workload.sampler.seed` | – | Seed of the workload random stream. |
| `workload.sampler.seed.updateSeed` | false | Switch to seed + simulation ID once transaction `updateTransaction` exists, so workloads differ per simulation. |
| `workload.sampler.seed.updateTransaction` | 0 | See above. |

## Nodes

| Key | Default | Meaning |
|---|---|---|
| `net.numOfNodes` | *required* | Total nodes; must equal honest + malicious + selfish. |
| `net.numOfHonestNodes` | *required* | Honest nodes. |
| `node.sampler.file` | – | CSV (`id,hashpower,electricPower,electricityCost`) for the first nodes; the rest are sampled. |
| `node.sampler.seed` | – | `{s0, s1, ...}`: seeds of the node random stream, which draws node attributes and mining intervals. |
| `node.sampler.updateSeedFlags` | – | `{b0, b1, ...}`: whether each seed gets the simulation ID added. |
| `node.sampler.seedUpdateTimes` | – | `{t1, ...}`: times at which the stream moves to the next seed. Simulations share their history until they switch to a per-simulation seed and evolve independently afterwards (pending mining events are redrawn at the switch). `{0}` makes them independent from the start; the CNSim method switches at the arrival of the transaction under study. A switch at or after `sim.terminate.atTime` means the simulations never diverge; the run warns about it. |
| `node.electricPowerMean`, `node.electricPowerSD`, `node.electricCostMean`, `node.electricCostSD` | *required* | Energy attributes (reported, not used by consensus). |

## Attacks

| Key | Default | Meaning |
|---|---|---|
| `net.numOfMaliciousNodes` | *required* | Double-spend attackers (normally 0 or 1). |
| `node.maliciousPowerByRatio` | – | Needed with attackers. `true`: attacker hash power from `node.maliciousRatio`; `false`: `node.maliciousHashPower`. |
| `node.maliciousRatio` | – | Attacker's share of the total hash power. |
| `node.maliciousHashPower` | – | Attacker's hash power in GH/s. |
| `workload.targetTransaction` | first sample | Transaction the attacker tries to reverse. |
| `bitcoin.attack.minChainLength` | 2 | Reveal the hidden chain once it is longer than the public chain's growth and that growth exceeds this. |
| `bitcoin.attack.maxChainLength` | 15 | Past this public growth, reveal if ahead, otherwise give up. |
| `net.numOfSelfishNodes` | 0 | 1 adds a selfish miner (Eyal and Sirer). |
| `node.selfishRatio` | – | Selfish miner's share of the total hash power. |
| `node.selfishHashPower` | – | Its hash power in GH/s, if no ratio is given. |

## Network

| Key | Default | Meaning |
|---|---|---|
| `net.topology` | `complete` | `complete`: every pair of nodes directly connected (the original model). Peer overlays: `random-outbound`, `random-regular`, `erdos-renyi`, `small-world`, `scale-free`, `file`. |
| `net.topology.degree` | 8 | Outbound links (`random-outbound`), degree (`random-regular`), ring degree (`small-world`, even) or links per new node (`scale-free`). |
| `net.topology.edgeProbability` | – | Link probability for `erdos-renyi`. |
| `net.topology.rewireProbability` | 0.1 | Rewiring probability for `small-world`. |
| `net.topology.file` | – | Edge list `from,to[,throughput_bps[,latency_ms]]` for `file`; missing values are sampled. |
| `net.topology.hopDelay` | 0 | Time each relay spends before forwarding (overlays). |
| `net.throughputMean`, `net.throughputSD` | *required* | Normal distribution of link throughput. |
| `net.latencyMean`, `net.latencySD` | 0 | Normal distribution (truncated at 0) of one-way link latency. |
| `net.sampler.file` | – | End-to-end throughput matrix (`from,to,bps,time`) instead of sampled throughputs; complete topology only. |
| `net.sampler.seed` | – | Seed of the network random stream (link properties, overlay shape). |
| `net.sampler.seed.updateSeed` | – | Add the simulation ID to it. |

On an overlay, the delay between two nodes is the fastest store-and-forward path: per hop, latency + size × 8000 / throughput, plus the hop delay at each relay. This is when a message flooded by every node on receipt first arrives.

## Reporting

| Key | Log file | Content |
|---|---|---|
| `reporter.reportBeliefs` | `BeliefLog` | SimID, node, transaction, believes (in its main chain), time. |
| `reporter.reportBlockEvents` | `BlockLog` | Every block event: validation, reception, placement, orphaning, reorgs, attack actions. |
| `reporter.reportStructureEvents` | `StructureLog` | Each node's final blockchain and orphans. |
| `reporter.reportTransactions` | `Input` | Transaction arrivals (size, fee value, time). |
| `reporter.reportNodes` | `Nodes` | Node attributes and total hashing cycles. |
| `reporter.reportNetEvents` | `NetLog` | Link throughputs. |
| `reporter.reportEvents` | `EventLog` | Every processed event (large). |

All seven keys are required. `ErrorLog - <run id>.txt` and the Config and Provenance files are always written.
