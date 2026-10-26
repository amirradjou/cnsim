# cnsim-finality

Finality estimates, belief curves and fork statistics from a CNSim run. The first two use the
estimators of the CNSim method (CCS26, section 3):

- **degree of belief** of the network in transaction *f* at time *t*: the share of nodes whose
  main chain contains *f* (from the BeliefLog);
- ***f* is final** within horizon *t_h* at threshold *d* if, before *t_h*, the degree of belief
  reaches *d* and does not drop below it again;
- **estimated finality**: the share of the K simulations in which *f* is final, with a Wilson (or
  Wald) confidence interval;
- **time to finality**: from *f*'s arrival (or the start) to the beginning of the final stretch;
- **aggregate degree of belief**: the mean over simulations as a function of time since arrival,
  with 5th/50th/95th percentiles.

```bash
cd tools/finality
uv run cnsim-finality finality ../../out/litecoin --horizon 3600 --threshold 0.9,0.99
uv run --extra plot cnsim-finality belief ../../out/litecoin --horizon 3600 --plot belief.png
uv run cnsim-finality forks ../../out/litecoin
uv run pytest            # tests (the paper's Figure 1 example among them)
```

The core needs only the Python standard library; plotting needs matplotlib (`--extra plot`).
Arrival times come from the Input log (`reporter.reportTransactions = true`); without it use
`--t0 start`. Simulations that end before the horizon are reported as censored and left out.

## Forks and stale blocks

`cnsim-finality forks RUN [RUN ...]` reads each node's final blockchain (StructureLog) and the
mined blocks (BlockLog), and reports per run, pooled over its simulations:

| Column | Meaning |
|---|---|
| `T` | mean block interval: simulated time / blocks mined |
| `mined`, `stale` | blocks mined; blocks placed at or below the settled tip but not on the settled chain |
| `stale rate` | stale / (settled + stale), with a Wilson interval |
| `fork rate` | share of settled heights at which more than one block was placed, with a Wilson interval |
| `predicted` | with `--delay D` (seconds): 1 − Σ s_a e^(−(1 − s_a) D / T) from the Nodes log's hash powers |
| `longest` | most stale blocks in a row on one branch |
| `unplaced` | mined blocks that no node placed: withheld, still orphaned, or rejected |

The *settled chain* is the part of the main chain every node agrees on when the simulation
ends; blocks above it are undecided and not counted. `--json` adds per-simulation rows.
[examples/forks](../../examples/forks/README.md) checks the fork rate against the closed form.
