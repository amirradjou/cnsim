# Output format

Every run writes one directory, `<sim.output.directory>/<run id>/`. The run ID is the wall-clock start time, `yyyy.MM.dd HH.mm.ss`, and every file in the directory carries it: `BeliefLog - 2026.10.02 14.29.16.csv`. The output directory also gets `LatestFileName.txt`, which holds the latest run ID.

The CSV logs are plain, unquoted, comma-separated text with one header row. Every row has as many fields as its header; `tools/golden/check.sh` checks this. Lists inside a field use `;` and are wrapped in braces, for example the transactions of a block: `{6;5;15;7}`. With `--parallel N` the logs are the concatenation of the slices in simulation order, identical to a sequential run.

| File | Switched by | Rows |
|---|---|---|
| [`BeliefLog`](#belieflog) | `reporter.reportBeliefs` | one per node, sample transaction and report time |
| [`BlockLog`](#blocklog) | `reporter.reportBlockEvents` | one per block event at a node |
| [`StructureLog`](#structurelog) | `reporter.reportStructureEvents` | one per block in each node's final structure |
| [`EventLog`](#eventlog) | `reporter.reportEvents` | one per processed event |
| [`Input`](#input) | `reporter.reportTransactions` | one per transaction |
| [`Nodes`](#nodes) | `reporter.reportNodes` | one per node, at the end of each simulation |
| [`NetLog`](#netlog) | `reporter.reportNetEvents` | one per directed link |
| [`Config`](#config) | always | one per configuration key |
| [`Provenance`](#provenance) (.json) | always | one JSON object |
| [`ErrorLog`](#errorlog) (.txt) | always | one line per anomaly |

A log that is switched off is still created, with only its header.

Conventions in all logs:

- `SimID` is the simulation, from 1 (or `sim.firstSimID`).
- Times called `SimTime` or `Time (ms from start)` are simulated milliseconds since the start of that simulation.
- `SysTime` is wall-clock milliseconds since the start of the simulation. It differs between runs and machines, and the golden check ignores it.
- Node IDs start at 1; `-1` means "none".
- Block and transaction IDs are unique within a simulation. They restart with every simulation, so `(SimID, ID)` identifies a block or transaction in a run.

## BeliefLog

The input to every finality estimate.

| Column | Meaning |
|---|---|
| `SimID` | simulation |
| `Node ID` | the node reporting |
| `Transaction ID` | one of `workload.sampleTransaction` |
| `Believes` | `true` if the transaction is on the node's main chain (the chain back from its longest tip), else `false`, including before the transaction exists |
| `Time (ms from start)` | report time |

Every node reports on every sample transaction at `beliefReportInterval`, 2 × `beliefReportInterval`, …, until the last workload event plus `beliefReportOffset` or the end of the run. The degree of belief at a report time is the share of `true` rows at that time. `tools/finality` computes it and the estimators built on it.

The log grows as nodes × sample transactions × reports. In the thesis base config that is 11 × 3 × 16,800 rows per simulation, about 14 MB.

## BlockLog

One row each time a node mines, receives, places, orphans or rejects a block, switches its main chain, or takes an attack step.

| Column | Meaning |
|---|---|
| `SimID`, `SimTime`, `SysTime` | as above |
| `NodeID` | the node where it happens: the miner, or the node receiving or placing the block |
| `BlockID` | the block (for `Reorg` rows: the new tip) |
| `ParentID` | its parent, `-1` for a root or when not yet known |
| `Height` | `1` for a root, parent + 1 otherwise; `0` for a block that is not placed yet |
| `BlockContent` | `{tx;tx;…}` in block order (`{}` for `Reorg` rows) |
| `EvtType` | what happened, see below |
| `Difficulty` | the difficulty the block was mined at, on the miner's rows; `-1.0` on rows about a received copy |
| `Cycles` | hash trials the miner spent on it; `-1.0` like `Difficulty` |

`EvtType` values:

| Value | When |
|---|---|
| `Node Completes Validation` | `NodeID` mined the block. For honest and double-spend nodes the row is written before the block is placed, so `ParentID` is `-1` and `Height` `0`. The next row of that node has the placement. |
| `Node Receives Propagated Block` | a copy reached `NodeID`. Parent and height are as the sender had them. |
| `Appended On Chain (w/ parent)` | a received block was placed on its parent |
| `Appended On Chain (parentless)` | the node's own block was placed on its best tip that shares no transaction with it |
| `Appended On Chain (competing genesis)` | a second root block was kept as another tip. A node's first block gets no `Appended` row. |
| `Added to Orphans` | the parent is not known yet; the block is placed when the parent arrives |
| `Discarded due to overlap with parent's chain`, `Discarding due to chain overlap` | rejected: it repeats a transaction already on the chain it would extend |
| `Reorg: N block(s) abandoned; M tx restored` | the node's main chain switched branches. `N` blocks left it and `M` of their transactions went back to the pool. Only with `bitcoin.reorg.restoreTransactions`. |
| `Target Transaction Appeared - Attack Starts` | double-spend attacker: the target is in a block, and the hidden chain starts from that block's parent |
| `Adding block to hidden chain` | the attacker mined a hidden block |
| `Reveal of hidden chain starts here.` | the attacker publishes the hidden chain: a successful double spend if it wins |
| `Attack abandoned (hidden H; public growth G)` | the attacker gave up (`bitcoin.attack.maxChainLength`) |
| `Propagated Block Discarded (already exists)` | the attacker received a block it already has |
| `Selfish: block withheld`, `Selfish: block published`, `Selfish: private branch abandoned (N unpublished)` | selfish miner: Eyal and Sirer's algorithm (see [architecture.md](architecture.md#selfish-miner)) |
| `Discarding own Block (ERROR)`, `ERROR: propagated Block already exists`, `Error: Discarding own Block`, `ERROR: Discarding own Block` | should not happen; also written to `ErrorLog` |

To follow one block, select its `BlockID` within a `SimID`. Its `Node Completes Validation` row gives the miner and time, and the `Appended` rows of the other nodes show when each one accepted it.

## StructureLog

Each node's blockchain and orphans when the simulation ends.

| Column | Meaning |
|---|---|
| `SimID`, `SimTime`, `SysTime` | `SimTime` is the end of the simulation |
| `NodeID` | the node |
| `BlockID`, `ParentBlockID` | the block and its parent (`-1` for a root) |
| `Height` | as in BlockLog; `-1` for orphans |
| `Content` | `{tx;tx;…}` |
| `Place` | ` blockchain` (every placed block, side branches included) or ` orphans`. Note the leading space. |

The main chain of a node is the path from its highest block (on a tie, the one it saw first) back to its root. The thesis settlement scripts (`tools/postprocessing/`) read StructureLog with EventLog.

## EventLog

Every processed event, the largest log by far.

| Column | Meaning |
|---|---|
| `SimID` | simulation |
| `EventID` | order of scheduling, from 1 in each simulation |
| `SimTime`, `SysTime` | as above |
| `EventType` | see below |
| `Node` | the node the event happens at (`-1` for none) |
| `Object` | the transaction or block ID (`-1` for none) |

| `EventType` | Node, Object |
|---|---|
| `Event_NewTransactionArrival` | the node a client submits the transaction to; transaction |
| `Event_TransactionPropagation` | a node receiving the transaction from a peer; transaction |
| `Event_ContainerValidation` | a node completing a block it mined; block |
| `Event_ContainerValidation_Abandonded` (sic) | a scheduled block completion cancelled before it happened, because the node stopped mining when its pool was no longer worth it, or because the draw was renewed at a seed switch; block |
| `Event_ContainerArrival` | a node receiving a block; block |
| `Event_SeedUpdate` | the node sampler switching seeds; `-1`, `-1` |

Belief reports are events too, but they are not logged here.

## Input

| Column | Meaning |
|---|---|
| `SimID`, `TxID` | simulation and transaction |
| `Size (bytes)` | transaction size |
| `Value (coins)` | the fee the transaction pays (miners fill blocks by fee per byte) |
| `ArrivalTime (ms)` | when a client submits it |

`tools/finality` measures time from this arrival (`--t0 arrival`, the default).

## Nodes

| Column | Meaning |
|---|---|
| `SimID`, `NodeID` | simulation and node |
| `HashPower (GH/s)` | hash power; for a double-spend attacker or selfish miner, the configured one |
| `ElectricPower (W)`, `ElectricityCost (USD/kWh)` | electricity attributes |
| `TotalCycles` | hash trials over the simulation (those of cancelled mining attempts are not counted) |

## NetLog

One row per directed link when the network is built (time 0). The complete networks list every ordered pair. An overlay lists both directions of each of its links, so this log is also the topology.

| Column | Meaning |
|---|---|
| `SimID` | simulation |
| `From (NodeID)`, `To (NodeID)` | the link |
| `Bandwidth (bps)` | throughput in bits per second |
| `Time (ms from start)` | when it was set |

## Config

`Key, Value` rows: every configuration key in effect, after the file, `--set` and command-line options are merged. The order is unspecified. A value is written as given, and **may contain commas** (`workload.sampleTransaction,{20000, 30000, 40000}`), so split each row on its first comma only.

## Provenance

A JSON object. It is enough to re-run the run and tell whether its inputs changed.

| Key | Content |
|---|---|
| `runId`, `started`, `finished`, `wallClockMillis` | timing |
| `cnsim` | `version`, `commit`, `commitTime`, and `uncommittedChanges` (true if the working tree had changes when the jar was built) |
| `java`, `os` | runtime |
| `commandLine` | the arguments, in order |
| `config` | path and SHA-256 of the configuration file |
| `inputs` | path and SHA-256 of every input file (node list, workload, network, topology) |
| `simulations` | number of simulations |

## ErrorLog

Free text, one line per anomaly the simulator detected and survived, such as a block that overlaps its chain. A clean run leaves it empty.

## Reading the logs

```python
import pandas as pd

run = "out/2026.10.02 14.29.16/"
beliefs = pd.read_csv(run + "BeliefLog - 2026.10.02 14.29.16.csv", skipinitialspace=True)
degree = beliefs.groupby(["SimID", "Transaction ID", "Time (ms from start)"])["Believes"].mean()

blocks = pd.read_csv(run + "BlockLog - 2026.10.02 14.29.16.csv", skipinitialspace=True)
mined = blocks[blocks["EvtType"] == "Node Completes Validation"]
intervals = mined.sort_values("SimTime").groupby("SimID")["SimTime"].diff() / 1000  # seconds

config = dict(line.rstrip("\n").split(",", 1) for line in open(run + "Config - 2026.10.02 14.29.16.csv").readlines()[1:])
```

`skipinitialspace=True` drops the space after the commas in the headers (`SimID, SimTime`) and in StructureLog's `Place`. For finality, time to finality and belief curves, use `tools/finality` ([README](../tools/finality/README.md)) rather than these lines.
