# Network-partition experiment

A partition cuts some nodes off from the others for a while: `net.partition.nodes` lists the nodes on one side, `net.partition.start` and `net.partition.end` say when (ms). While it holds, each side mines its own branch. Blocks and transactions sent across the cut arrive when it heals, after their usual delay. Then the longer branch wins and the other one is orphaned.

```bash
mvn -q package -DskipTests
java -jar target/cnsim-0.0.1-SNAPSHOT.jar -c examples/partitions/partition.properties
python3 tools/partitions/partition_outcome.py out/partition/<run id>
tools/partitions/partition_sweep.sh        # 1 to 5 of the 10 nodes cut off, 400 simulations each (about 30 s)
```

Setup: ten miners with equal hash power, every pair directly connected with a fixed 100 ms latency, and a 60 s mean block interval T. Side A (`{1,2,3}` in the config, 30% of the hash power) is cut off from the other seven nodes (side B) from 30 to 40 minutes, so L = 10 T. The simulation runs on for 50 minutes after the heal, so every node agrees on the outcome. The partition starts after a 30-minute warm-up, so the window is clear of start-up effects.

## The closed form

While the partition holds, each side mines on its own. Side A, with share p of the hash power, mines N_A ~ Poisson(pL/T) blocks, and side B mines N_B ~ Poisson((1−p)L/T). When the partition heals, every node sees both branches and keeps the longer one. On a tie, each node keeps the branch it saw first, and the side that finds the next block wins (A with probability p). So

P(A wins) = P(N_A > N_B) + p · P(N_A = N_B ≥ 1)

where N_A − N_B has a Skellam distribution. The losing branch's blocks are orphaned.

## What is measured

`partition_outcome.py` reads the run's logs. For each simulation it counts the blocks each side mined while the partition held and the length of the branch each side grew. It also finds which side's branch ended up on the chain every node agrees on at the end. It reports:

- how often side A's branch won, with a 95% Wilson interval, next to the closed form;
- an exact check: in every simulation where the branches differ in length, the longer one must win;
- orphaned partition blocks and blocks mined per side, per simulation, next to their expected values.

## Result

Measured (400 simulations per row, `partition_sweep.sh`):

| Side A | p | A's branch won (95% CI) | Closed form | Longer branch won | Orphaned blocks / sim (expected) |
|---|---|---|---|---|---|
| `{1}` | 0.1 | 0.000 [0.000, 0.010] | 0.002 | 399 / 399 | 1.03 (1.00) |
| `{1,2}` | 0.2 | 0.020 [0.010, 0.039] | 0.019 | 392 / 392 | 2.06 (1.98) |
| `{1,2,3}` | 0.3 | 0.092 [0.068, 0.125] | 0.090 | 387 / 387 | 2.95 (2.86) |
| `{1,2,3,4}` | 0.4 | 0.240 [0.201, 0.284] | 0.255 | 359 / 359 | 3.64 (3.51) |
| `{1,2,3,4,5}` | 0.5 | 0.490 [0.441, 0.539] | 0.500 | 356 / 356 | 3.87 (3.75) |

The closed form lies inside every interval. In all 1,893 simulations where the two branches differed in length, the longer one won. The other 107 were ties, decided by the next block: side A won 47 of them, against 44.0 expected from its share. Blocks mined per side are within 4% of pL/T and (1−p)L/T. All rows use the same seeds, so they share their mining randomness: the total is 10.25 blocks per window in each row, against 10 expected. That shared excess also explains why orphaned blocks run slightly above expectation.

## Finality across a partition

The config tracks belief in three transactions: transaction 150, which arrives about 5 minutes before the partition, and transactions 200 and 220, which arrive during it at about 34 and 37 minutes. With `cnsim-finality` over 400 simulations (`finality ... --horizon 1800`):

| Transaction | Arrives | Median time to 0.9 belief |
|---|---|---|
| 150 | 25 min, before the partition | 46 s |
| 200 | 34 min, during it | 443 s |
| 220 | 37 min, during it | 204 s |

A transaction that arrives during a partition reaches only one side, so it cannot be believed by 90% of the nodes before the partition heals at 40 minutes. Its time to finality is the remaining partition time plus the usual delay.

## Limits

- One partition per run, into two sides.
- On a peer-to-peer overlay, messages within a side still take the fastest path over the whole overlay, which may pass through the other side; only messages between sides are held back.
- A message already on the way when the partition starts is delivered.
