import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import logging
from scipy.stats import bootstrap
from joblib import Parallel, delayed  # For parallel processing

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# Parameters
bootstrap_r = 499
experiment = "2025.05.24 12.52.37"

# Load data
logging.info(f"Loading data from {experiment}...")
data_path = f"../../thesis-log/double-minSizeToMine/{experiment}/BeliefLog - {experiment}.csv"
data = pd.read_csv(data_path)
data.columns = data.columns.str.strip()
logging.info("Data loaded successfully.")

# Rename columns
data.rename(columns={"SimID": "Simulation", "Transaction ID": "Transaction",
                     "Believes": "Believes", "Time (ms from start)": "Time"}, inplace=True)

# Convert 'Believes' to numerical values (if not already)
data["Believes"] = data["Believes"].astype(int)

# Aggregate over simulations
net_pre = data.groupby(["Simulation", "Transaction", "Time"])["Believes"].mean().reset_index(name="conf")
end_time = net_pre.groupby("Simulation")["Time"].max().min()
logging.info(f"End time computed: {end_time}")

# Filter data to ensure consistent time range
net = net_pre[net_pre["Time"] <= end_time]
logging.info(f"Filtered net data to {len(net)} rows.")

# Aggregate over simulations for confidence statistics
confs = net.groupby(["Time", "Transaction"]).agg(
    avgConf=('conf', 'mean'),
    sd=('conf', 'std'),
    medConf=('conf', 'median'),
    count=('conf', 'count')
).reset_index()

logging.info("Aggregated confidence statistics.")

# Ensure enough data for bootstrapping
if len(confs) > 0:
    confs = confs[confs["count"] >= 5]  # Reduce threshold to avoid dropping too many rows
    logging.info(f"Filtered confs row count (count >= 5): {len(confs)}")

# Ensure confs time values align with net
valid_times = net["Time"].unique()
confs = confs[confs["Time"].isin(valid_times)]
logging.info(f"Filtered confs row count after time alignment: {len(confs)}")

# Define bootstrap function

def compute_bootstrap_ci(time, data):
    """Computes the bootstrap confidence intervals for a given time value."""
    x = data[data["Time"] == time]["conf"].values

    if len(x) < 10:
        logging.warning(f"Skipping bootstrap for Time={time}: Insufficient data ({len(x)} values).")
        return np.mean(x), np.mean(x)  # Return mean instead of NaN

    if np.all(x == 1):
        logging.info(f"Time={time}: All values are 1, setting CI to (1,1).")
        return 1, 1

    if np.all(x == 0):
        logging.info(f"Time={time}: All values are 0, setting CI to (0,0).")
        return 0, 0

    try:
        res = bootstrap((x,), np.mean, n_resamples=bootstrap_r, method='BCa')
        return res.confidence_interval.low, res.confidence_interval.high
    except (ValueError, RuntimeWarning) as e:
        logging.error(f"Bootstrap error at Time={time}: {e}")
        return np.mean(x), np.mean(x)

    # If confs is still empty, prevent crash and continue with mean confidence
if len(confs) == 0:
    logging.error("Filtered confs is empty. Adjusting approach.")
    confs = net.groupby(["Time", "Transaction"]).agg(avgConf=('conf', 'mean')).reset_index()
    confs["lwr"], confs["upr"] = confs["avgConf"], confs["avgConf"]
    logging.warning("Using avgConf for all confidence intervals due to missing bootstrap data.")
else:
    logging.info(f"Computing bootstrap confidence intervals for {len(confs)} time steps (parallel processing)...")
    bootstrap_results = Parallel(n_jobs=-1)(
        delayed(compute_bootstrap_ci)(t, net) for t in confs["Time"]
    )

    # Store results
    if len(bootstrap_results) == 0:
        logging.error("Bootstrap results are empty. Using avgConf for CI.")
        confs["lwr"], confs["upr"] = confs["avgConf"], confs["avgConf"]
    else:
        confs["lwr"], confs["upr"] = zip(*bootstrap_results)
        logging.info("Bootstrap confidence intervals computed.")

# Convert transaction to categorical and time to minutes
confs2plot = confs.copy()
confs2plot["Condition"] = confs2plot["Transaction"].astype(str)
confs2plot["Time"] = confs2plot["Time"] / 60000
confs2plot["Time"] = confs2plot["Time"].round(2)

# Ensure sorting for proper visualization
confs2plot = confs2plot.sort_values(by=["Time"])

# Plot confidence over time
logging.info("Generating confidence over time plot...")
plt.figure(figsize=(10, 6))
sns.lineplot(data=confs2plot, x="Time", y="avgConf", hue="Condition")
plt.xlabel("Time (min)")
plt.ylabel("Confidence")
plt.title("Confidence in f over time")
plt.axhline(y=0.8, color="r", linestyle="--")
#plt.xlim(20, 160)
plt.savefig("confidence_over_time.png")
plt.close()
logging.info("Saved confidence_over_time.png")

# Plot with confidence intervals
logging.info("Generating confidence interval plot...")
plt.figure(figsize=(10, 6))
for cond in confs2plot["Condition"].unique():
    subset = confs2plot[confs2plot["Condition"] == cond]
    sns.lineplot(data=subset, x="Time", y="avgConf", label=cond)
    plt.fill_between(subset["Time"], subset["lwr"], subset["upr"], alpha=0.3, color='gray')

plt.xlabel("Time (min)")
plt.ylabel("Confidence")
plt.title("Confidence in f over time (mean and 95% CI)")
plt.axhline(y=0.8, color="r", linestyle="--")
#plt.xlim(20, 160)
plt.legend(title="Condition")
plt.savefig("confidence_with_CI.png")
plt.close()
logging.info("Saved confidence_with_CI.png")
