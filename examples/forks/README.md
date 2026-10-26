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
| 0.6 s | 0.01 | 0.83% [0.71%, 0.97%] | 0.89% | 0.82% |
| 1.2 s | 0.02 | 1.64% [1.47%, 1.83%] | 1.77% | 1.63% |
| 3 s | 0.05 | 4.22% [3.94%, 4.51%] | 4.36% | 4.16% |
| 6 s | 0.10 | 8.51% [8.12%, 8.93%] | 8.52% | 8.14% |
| 12 s | 0.20 | 16.45% [15.89%, 17.02%] | 16.32% | 15.12% |
| 18 s | 0.30 | 23.00% [22.35%, 23.67%] | 23.45% | 20.60% |
| 30 s | 0.49 | 34.51% [33.72%, 35.30%] | 35.94% | 29.64% |

Up to D/T = 0.3 the prediction lies inside every interval. At D/T = 0.49 the simulation is 1.4 points below it, which is the expected second-order overestimate. The stale rate falls increasingly below the fork rate as D grows, because at long delays a fork is more often followed by another one on the losing branch: the longest stale branch grows from 1 block at D/T = 0.01 to 4 at 0.49.

Caveats: the intervals treat heights as independent, while consecutive heights are slightly correlated. Every delay uses the same seeds, so the rows share their mining randomness: the block interval is 60.67 s in all of them.

## Using it on other runs

`cnsim-finality forks` works on any run with a StructureLog, including the chain presets and the attack experiments; see [tools/finality](../../tools/finality/README.md). On a peer-to-peer overlay the delay to the last node is not one fixed number, so `--delay` gives at best a rough guide there.
