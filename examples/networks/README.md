# Network presets

Ready-to-run configurations for seven blockchains. Six are proof-of-work chains that differ in block interval, block size limit and load; Cardano uses the proof-of-stake slot lottery. All of them run on a peer-to-peer overlay with per-link throughput and latency instead of the original all-pairs network.

```bash
mvn -q package -DskipTests
java -jar target/cnsim-0.0.1-SNAPSHOT.jar -c examples/networks/litecoin.properties
# output: ./out/litecoin/<run id>/
```

Each preset runs 10 simulations. Pass `--sims N` to change that, and `--out DIR/` to write elsewhere.

## The presets

| Preset | Block production | Target interval | Block limit | Load | Nodes |
|---|---|---|---|---|---|
| `bitcoin` | PoW | 600 s | 1,000,000 vB | 5 tx/s, 250 vB | 20 |
| `bitcoin-cash` | PoW | 600 s | 32 MB | 0.5 tx/s, 350 B | 20 |
| `litecoin` | PoW | 150 s | 1,000,000 vB | 2 tx/s, 250 vB | 20 |
| `dogecoin` | PoW | 60 s | 1 MB | 0.5 tx/s, 300 B | 20 |
| `zcash` | PoW | 75 s | 2 MB | 0.1 tx/s, 2.5 kB | 20 |
| `ethereum-classic` | PoW | 13 s | 57 kB (≈ 8M gas of transfers) | 0.5 tx/s, 150 B | 20 |
| `cardano` | PoS slot lottery, 1 s slots, f = 0.05 | 20 s | 90,112 B | 1 tx/s, 600 B | 30 |

The consensus parameters are the protocols' own: block interval, block size or weight limit, and slot length plus active slot coefficient for Cardano. The loads are illustrative round numbers of the right order, not measurements. The nodes stand for mining or stake pools, and their hash power (stake) is drawn from a normal distribution; only relative values matter, because the difficulty is derived from `pow.targetBlockInterval`. Treat the presets as starting points and set the numbers your study needs.

Shared settings:

- **Overlay:** each node opens 8 outbound links (10 for Cardano), which is how Bitcoin Core connects. Links average 50 Mbps and 60 ms one-way latency. Relays forward after 20 ms (200 ms for Ethereum Classic, whose blocks must be executed first).
- **Empty blocks:** `bitcoin.minValueToMine = -1` lets nodes mine empty blocks, as real miners do. With the default, block production pauses whenever the mempool is empty, which stretches block intervals on low-traffic chains.
- **Reorgs:** `bitcoin.reorg.restoreTransactions = true` returns transactions of abandoned blocks to the mempool after a reorg.
- **Replica independence:** the nodes are the same in every simulation, and each simulation switches to its own random stream at t = 0, before any mining starts (`node.sampler.seedUpdateTimes = {0}`).

## Measured

10 simulations per preset with the settings above (`java -jar ... -c examples/networks/<preset>.properties`) on a laptop (AMD Ryzen 5 7640U):

| Preset | Mean block interval (± SD across simulations) | Stale blocks | Blocks per simulation | Run time per simulation |
|---|---|---|---|---|
| `bitcoin` (4 h) | 606 s ± 93 | 0 % | 24 | 23 s |
| `bitcoin-cash` (4 h) | 606 s ± 93 | 0 % | 24 | 0.5 s |
| `litecoin` (2 h) | 153 s ± 8 | 0 % | 46 | 0.9 s |
| `dogecoin` (1 h) | 60.5 s ± 4.3 | 0 % | 59 | 0.1 s |
| `zcash` (1.5 h) | 76.2 s ± 5.2 | 0.14 % | 70 | 0.1 s |
| `ethereum-classic` (30 min) | 13.3 s ± 1.6 | 0.80 % | 137 | 0.1 s |
| `cardano` (1 h) | 20.2 s ± 2.1 | 2.18 % | 183 | 0.3 s |

Bitcoin and Bitcoin Cash have the same target interval and seeds, and mining does not depend on block contents, so their block times coincide; they differ in block size and load. Bitcoin's run time is dominated by its 72,000 transactions, each gossiped to 19 peers.

"Stale" counts blocks outside node 1's final main chain. It is near zero for the proof-of-work presets because blocks reach every node in a fraction of a second, which is small next to their intervals. Cardano's stale blocks come from slot battles: two pools winning the same slot.

## What is and is not modelled

Modelled: exponential proof-of-work block times at a fixed difficulty, or Praos slot leadership; fee-per-byte block assembly up to the size limit; gossip over the overlay (fastest store-and-forward path); the longest-chain rule; forks and reorgs; per-node belief in each sample transaction over time (`BeliefLog`).

Not modelled:

- difficulty adjustment (hash rate is constant within a run)
- uncle/ommer blocks, GHOST, and Ethereum's gas market (gas is approximated by a byte limit)
- transaction relay delays such as Bitcoin Core's trickling
- epochs, stake changes and VRF tie-breaking in Praos (ties use the engine's usual rule)
- conflicting transactions other than the majority attacker's target

## Config keys used here

| Key | Meaning |
|---|---|
| `pow.targetBlockInterval` | Mean block interval in ms; the difficulty is derived from the nodes' total hash power. Replaces `pow.difficulty`. |
| `consensus.leaderElection` | `pow` (default) or `slot-lottery` |
| `pos.slotDuration`, `pos.activeSlotCoefficient` | Slot length (ms) and f for the slot lottery |
| `net.topology` | `complete` (default, the original model), `random-outbound`, `random-regular`, `erdos-renyi`, `small-world`, `scale-free`, `file` |
| `net.topology.degree` | Outbound links, regular degree, ring degree (small-world) or links per new node (scale-free); default 8 |
| `net.topology.edgeProbability`, `net.topology.rewireProbability` | For `erdos-renyi` and `small-world` |
| `net.topology.file` | Edge list `from,to[,throughput_bps[,latency_ms]]` for `file` |
| `net.topology.hopDelay` | Milliseconds each relay spends before forwarding |
| `net.latencyMean`, `net.latencySD` | One-way link latency in ms (also works with the complete network) |
| `bitcoin.reorg.restoreTransactions` | Return transactions of abandoned blocks to the pool on a reorg |
