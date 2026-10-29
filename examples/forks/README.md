# Fork-rate experiment

Checks the network and fork model against a closed form: how often two miners find a block at the same height because the first block has not reached the second miner yet.

```bash
mvn -q package -DskipTests
java -jar target/cnsim-0.0.1-SNAPSHOT.jar -c examples/forks/forks.properties --set net.latencyMean=6000
cd tools/finality && uv run cnsim-finality forks ../../out/forks --delay 6
tools/forks/fork_sweep.sh        # every delay in the table, 10 simulations each (about 30 s)
```

Setup: ten miners with equal hash power, every pair of nodes directly connected, and a 60 s mean block interval T. Each block reaches every other node after the same delay D: links have a fixed one-way latency (`net.latencyMean`, SD 0) and 1 Tbps throughput, so transmission time is negligible. Ties between equally long branches are broken by first-seen. Each simulation runs for 2,000 block intervals.

## The closed form

Take the first block found at some height, by miner a, whose share of the hash power is s_a. The other miners keep mining on the old tip until the block reaches them, D later. The height is forked if one of them finds a block in that window, which happens with probability 1 − e^(−(1 − s_a) D / T). Averaging over which miner finds the first block:

P(fork) = 1 − Σ_a s_a · e^(−(1 − s_a) D / T)

For n equal miners this is 1 − e^(−(1 − 1/n) D / T). It is the fork model of Decker and Wattenhofer (*Information propagation in the Bitcoin network*, IEEE P2P 2013) for a fixed delay, with the miner's own share taken out, since a miner does not fork with itself.

The formula assumes every node has the old tip when the race starts. That fails when the old tip was itself found less than D after its parent, so other miners start late, or when the previous height is still contested. Both effects are of order (D/T)², so the formula is accurate for small D/T and overestimates slightly beyond that.

## What is measured

`cnsim-finality forks` reads each node's final blockchain (StructureLog) and the mined blocks (BlockLog):

- The **settled chain** is the part of the main chain that every node agrees on when the simulation ends. A node with several blocks at its top height counts only the part below them.
- The **fork rate** is the share of settled heights at which more than one block was placed. The closed form predicts this quantity.
- A **stale** block is one placed at or below the settled tip that is not on the settled chain. The **stale rate** is stale blocks / (settled + stale), the usual "share of blocks that are orphaned" measure. Blocks above the settled tip are still undecided and are left out.
- `--delay D` adds the prediction above, using the hash powers from the Nodes log and the measured block interval.

## Result

Measured (10 simulations per delay, about 20,000 settled heights each, `fork_sweep.sh`):

| D | D/T | Fork rate (95% CI) | Predicted | Stale rate |
|---|---|---|---|---|
| 0.6 s | 0.01 | 0.93% [0.80%, 1.07%] | 0.90% | 0.92% |
| 1.2 s | 0.02 | 1.79% [1.61%, 1.98%] | 1.80% | 1.78% |
| 3 s | 0.05 | 4.20% [3.93%, 4.49%] | 4.43% | 4.13% |
| 6 s | 0.10 | 8.23% [7.85%, 8.64%] | 8.66% | 7.91% |
| 12 s | 0.20 | 16.11% [15.57%, 16.67%] | 16.58% | 14.93% |
| 18 s | 0.30 | 23.30% [22.65%, 23.96%] | 23.80% | 20.83% |
| 30 s | 0.50 | 35.32% [34.53%, 36.11%] | 36.42% | 29.94% |

The prediction lies inside the intervals for D/T up to 0.05 and at 0.2 and 0.3; at 0.1 it is 0.02 points above the interval. From D/T = 0.05 on the simulation is consistently below the prediction, by 0.2 to 0.5 points and 1.1 at 0.5, which is the direction of the formula's second-order overestimate. The stale rate falls increasingly below the fork rate as D grows, because at long delays a fork is more often followed by another one on the losing branch: the longest stale branch grows from 1 block at D/T = 0.01 to 4 at 0.5.

Caveats: the intervals treat heights as independent, while consecutive heights are slightly correlated. Every delay uses the same seeds, so the rows share their mining randomness: the block interval is 59.62 s in all of them.

## Using it on other runs

`cnsim-finality forks` works on any run with a StructureLog, including the chain presets and the attack experiments; see [tools/finality](../../tools/finality/README.md). On a peer-to-peer overlay the delay to the last node is not one fixed number, so `--delay` gives at best a rough guide there.
