# cnsim-finality

Finality estimates and belief curves from a CNSim run, using the estimators of the CNSim method
(CCS26, section 3):

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
uv run pytest            # tests (the paper's Figure 1 example among them)
```

The core needs only the Python standard library; plotting needs matplotlib (`--extra plot`).
Arrival times come from the Input log (`reporter.reportTransactions = true`); without it use
`--t0 start`. Simulations that end before the horizon are reported as censored and left out.
