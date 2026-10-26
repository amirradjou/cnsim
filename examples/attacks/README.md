# Attack experiments

Two attacks, each checked against a closed-form result.

| Config | Attack | Checked against |
|---|---|---|
| `selfish-mining.properties` | Selfish mining (Eyal and Sirer 2014): one pool withholds its blocks and publishes them strategically | The paper's relative revenue formula R(α, γ) |
| `double-spend.properties` | The thesis attack (`MaliciousNodeBehavior`): a hidden chain that leaves out the target transaction, revealed once it is longer than the public chain | The exact probability of the block race under the attacker's reveal and give-up rules |

Build the jar first (`mvn -q package -DskipTests`). The scripts in `tools/attacks/` read the run logs and need only Python 3.

## Selfish mining

```bash
java -jar target/cnsim-0.0.1-SNAPSHOT.jar -c examples/attacks/selfish-mining.properties
python3 tools/attacks/selfish_revenue.py out/selfish-mining/<run id>
tools/attacks/selfish_sweep.sh target/cnsim-0.0.1-SNAPSHOT.jar 20   # every share in the table, 20 simulations each
```

Setup: ten honest pools and one selfish pool with `node.selfishRatio` (α) of the hash power; Bitcoin's 10-minute interval; about 600 blocks per 100-hour simulation. Nodes are fully connected with 50 ms ± 20 ms latency and break ties between equally long branches by first-seen (`consensus.tieBreak = first-seen`).

Revenue is the selfish pool's share of the blocks on an honest node's final main chain. In the formula, γ is the share of honest power that builds on the selfish block during a race. Here it is not a parameter: it comes from which of the two competing blocks each node hears first. `selfish_revenue.py` measures it from the logs. Among races that an honest miner decides, it counts the share decided on top of the selfish block, then evaluates the formula at that value.

Measured (20 simulations per share, `selfish_sweep.sh`):

| α | Revenue (95% CI) | R(α, 0) | Measured γ | R(α, γ) |
|---|---|---|---|---|
| 0.10 | 0.044 ± 0.004 | 0.036 | 0.08 | 0.042 |
| 0.20 | 0.141 ± 0.007 | 0.130 | 0.08 | 0.138 |
| 0.25 | 0.207 ± 0.008 | 0.195 | 0.07 | 0.203 |
| 0.30 | 0.281 ± 0.009 | 0.273 | 0.07 | 0.280 |
| 0.33 | 0.340 ± 0.010 | 0.327 | 0.08 | 0.335 |
| 0.35 | 0.371 ± 0.010 | 0.367 | 0.07 | 0.374 |
| 0.40 | 0.487 ± 0.011 | 0.484 | 0.06 | 0.489 |
| 0.45 | 0.632 ± 0.011 | 0.652 | 0.09 | 0.657 |

The simulation reproduces the paper's main result: with γ near 0, selfish mining pays only above about α = 1/3 (revenue 0.281 at α = 0.30, 0.340 at α = 0.33). Up to α = 0.40 revenue matches R(α, γ) within about 0.005.

At 0.45 it is 0.025 below. Two things bias the measurement at large α:

- the intervals treat blocks as independent, while a long selfish lead correlates many blocks in a row, so the stated intervals are too narrow for large α;
- blocks still withheld when a run ends are never counted.

## Double spend

```bash
java -jar target/cnsim-0.0.1-SNAPSHOT.jar -c examples/attacks/double-spend.properties
python3 tools/attacks/double_spend_outcome.py out/double-spend/<run id>
tools/attacks/double_spend_sweep.sh        # q = 0.1 ... 0.45, 100 simulations each
```

Setup: ten honest pools and one attacker with `node.maliciousRatio` (q) of the hash power. The target is transaction 5. When it appears in a block, the attacker starts a hidden chain from that block's parent. It reveals the chain once it is longer than the public chain's growth and that growth exceeds `bitcoin.attack.minChainLength` = 2 confirmations. It gives up once the public chain has grown by `bitcoin.attack.maxChainLength` = 15 without being overtaken.

A success is a revealed attack after which the target transaction is not on the main chain. The exact probability treats each new block as the attacker's with probability q, starts from hidden chain 0 against public growth 1 (the target's block), and applies the same reveal and give-up rules; `double_spend_outcome.py` computes it by dynamic programming.

Measured (100 simulations per share, `double_spend_sweep.sh`):

| q | Success rate (95% CI) | Exact race probability |
|---|---|---|
| 0.10 | 0.000 | 0.001 |
| 0.20 | 0.030 ± 0.033 | 0.023 |
| 0.30 | 0.090 ± 0.056 | 0.116 |
| 0.40 | 0.300 ± 0.090 | 0.344 |
| 0.45 | 0.470 ± 0.098 | 0.505 |

All five are within their intervals. A larger check at q = 0.40, 400 simulations, gives 0.347 ± 0.047.

Building this comparison exposed a bug in the attacker. When it mined the target's block itself, it kept two mining events alive and mined at twice its hash rate for the rest of the run. With q = 0.30, 5 of 10 attacks succeeded where the race allows 11.6%. It is fixed; the fix commit has the details.

## What these runs do not model

Transactions in CNSim never conflict, apart from the attacker's target, which is marked double-spent when the hidden chain is revealed so that honest nodes do not mine it again. There is no difficulty adjustment, so a selfish pool's revenue share is exactly its share of main-chain blocks, not a profit per unit of time. Only one selfish pool and one double-spend attacker are supported per simulation.
