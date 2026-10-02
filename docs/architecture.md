# Architecture

CNSim is a discrete-event simulator. A run executes K independent simulations of the same configuration; each simulation is a priority queue of timed events that nodes react to, and every node periodically reports what it believes. The packages split into a protocol-agnostic engine and the Nakamoto-consensus (Bitcoin) implementation.

```mermaid
flowchart LR
    cfg["config .properties<br/>+ --set overrides"] --> driver["BitcoinMainDriver"]
    driver -->|"simulations 1..K<br/>(or --parallel slices)"| sim["Simulation<br/>event queue"]
    sim --> logs["logs per run<br/>BeliefLog, BlockLog, ..."]
    logs --> analysis["tools/finality<br/>finality, time to finality,<br/>belief curves"]
```

## Packages

| Package | Contents |
|---|---|
| `engine` | `Simulation` (event loop), `Sampler` and the node / transaction / network samplers, `SeedManager`, `ParallelRunner`, `Config` |
| `engine.event` | Events: transaction arrival and propagation, container (block) validation and arrival, belief reports, seed switches |
| `engine.node` | `Node` (mining state, propagation to peers), `NodeSet` |
| `engine.network` | `RandomEndToEndNetwork` and `FileBasedEndToEndNetwork` (every pair connected), `OverlayNetwork` + `Topology` (peer-to-peer graphs), `NetworkFactory` |
| `engine.consensus` | `LeaderElection` hook, `SlotLotteryElection` (proof of stake), `ConsensusSetup` (difficulty from target interval) |
| `engine.transaction` | `Transaction`, `TransactionGroup` (pools and blocks, with an incremental fee-rate order and ID index) |
| `engine.reporter` | `Reporter` with one `LogStream` per log, `Provenance` |
| `bitcoin` | `BitcoinNode`, `Blockchain` (tips, orphans, reorgs, tie-break), `Block`, and the node behaviours: `HonestNodeBehavior`, `MaliciousNodeBehavior` (double spend), `SelfishMiningBehavior` |

## One simulation

```mermaid
sequenceDiagram
    participant Q as Event queue
    participant A as Node A
    participant B as Node B (peer)
    Q->>A: NewTransactionArrival(tx)
    A->>A: add to pool, rebuild mining pool, consider mining
    A->>Q: TransactionPropagation(tx) at each other node, after network delay
    Q->>B: TransactionPropagation(tx)
    B->>B: add to pool if new
    Note over A: mining: one pending ContainerValidation event,<br/>delay drawn from hash power and difficulty (or the slot lottery)
    Q->>A: ContainerValidation(block)
    A->>A: append to best tip, start mining the next block
    A->>Q: ContainerArrival(block) at each other node, after network delay
    Q->>B: ContainerArrival(block)
    B->>B: place on parent (or keep as orphan), reorg if the main chain switches
    Q->>A: BeliefReport (every interval)
    A->>A: log: is each sample transaction on my main chain?
```

- **Delays.** On the complete network, a message takes `size × 8000 / throughput` ms plus link latency to each node. On an overlay it takes the fastest store-and-forward path, which is when flooding first delivers it.
- **Mining.** A node keeps one pending validation event while it mines. Receiving a block does not reschedule it: block production is memoryless, and the block's parent is chosen when it completes.
- **Beliefs.** Belief is binary per node: whether the transaction is on the node's main chain. Group belief is the share of nodes believing, and finality is estimated across the K simulations (see [tools/finality](../tools/finality/README.md)).

## Randomness and replicas

All randomness comes from three seeded streams: node (attributes and mining intervals), transaction (workload), network (link properties and overlay shape). `node.sampler.seedUpdateTimes` switches the node stream to a per-simulation seed (seed + simulation ID). Simulations share their history until then and are independent afterwards, because pending mining events are redrawn at the switch. A simulation depends only on its ID, which is why `--parallel` can split a run across processes and still produce the sequential run's logs.

## Double-spend attacker

`MaliciousNodeBehavior` follows the protocol until the target transaction appears in a block. It then builds a hidden chain from that block's parent, without the target.

```mermaid
stateDiagram-v2
    [*] --> Honest
    Honest --> Attacking: target transaction appears in a block
    Attacking --> Attacking: hidden or public chain grows
    Attacking --> Revealed: hidden > growth and growth > minChainLength,<br/>or growth > maxChainLength
    Attacking --> Abandoned: growth >= maxChainLength and hidden <= growth
    Revealed --> [*]: target marked double-spent
    Abandoned --> [*]: pool resynchronised with the main chain
```

## Selfish miner

`SelfishMiningBehavior` implements Eyal and Sirer's Algorithm 1. The state is the private lead: the private branch's height minus the public chain's.

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Lead0
    Lead0 --> Lead1: selfish pool finds a block (withheld)
    Lead1 --> Race: others find a block / publish it
    Race --> Lead0: anyone finds the next block<br/>(the selfish pool publishes and wins, or the race is decided)
    Lead1 --> Lead2: selfish pool finds a block
    Lead2 --> Lead0: others find a block / publish all, win by one
    Lead2 --> LeadN: selfish pool finds a block
    LeadN --> LeadN: others find a block / publish to match their height
    LeadN --> Lead2: lead shrinks to 2
    Lead0 --> Lead0: others find a block / adopt the public chain
```

## Verification

- `tools/golden/check.sh` hashes the logs of a small but complete run (attacker, forks, every log). CI runs it sequentially and with `--parallel 2`, on JDK 21 and 25.
- `tools/smoke.sh` runs a shortened copy of every shipped configuration.
- The attack experiments in `examples/attacks/` compare simulated outcomes with closed-form results.
