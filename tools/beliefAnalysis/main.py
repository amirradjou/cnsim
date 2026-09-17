import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import logging
from scipy.stats import bootstrap

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# Bootstrap function for lower bound
def boot_lower(x, stat_func, iterations):
    x = np.array(x)
    if len(x) < 10 or np.all(x == x[0]) or np.isnan(x).any():
        logging.warning("Bootstrapping lower bound skipped due to insufficient or invalid data.")
        return 0  # Match R behavior of replacing NaN with 0
    try:
        res = bootstrap((x,), stat_func, n_resamples=iterations, method='BCa')
        return res.confidence_interval.low
    except (ValueError, RuntimeWarning) as e:
        logging.error(f"Bootstrap lower bound error: {e}")
        return 0

# Bootstrap function for upper bound
def boot_upper(x, stat_func, iterations):
    x = np.array(x)
    if len(x) < 10 or np.all(x == x[0]) or np.isnan(x).any():
        logging.warning("Bootstrapping upper bound skipped due to insufficient or invalid data.")
        return 0
    try:
        res = bootstrap((x,), stat_func, n_resamples=iterations, method='BCa')
        return res.confidence_interval.high
    except (ValueError, RuntimeWarning) as e:
        logging.error(f"Bootstrap upper bound error: {e}")
        return 0

# Parameters
bootstrap_r = 499
experiment = "2025.06.19 03.13.19"

# Load data
logging.info(f"Loading data from {experiment}...")
data_path = f"../../new-thesis-log/malicious3/{experiment}/BeliefLog - {experiment}.csv"
data = pd.read_csv(data_path)
data.columns = data.columns.str.strip()
logging.info("Data loaded successfully.")

# Rename columns
data.rename(columns={"SimID": "Simulation", "Transaction ID": "Transaction", "Believes": "Believes", "Time (ms from start)": "Time"}, inplace=True)

# Aggregate over simulations
net_pre = data.groupby(["Simulation", "Transaction", "Time"]).agg(conf=('Believes', 'mean')).reset_index()
end_time = net_pre.groupby("Simulation")["Time"].max().min()
logging.info(f"End time computed: {end_time}")

# Define net after calculating end_time
net = net_pre[net_pre["Time"] <= end_time]
logging.info(f"Filtered net data to {len(net)} rows")

# Aggregate over simulations
confs = net.groupby(["Time", "Transaction"]).agg(
    avgConf=('conf', 'mean'),
    sd=('conf', 'std'),
    medConf=('conf', 'median')
).reset_index()
logging.info("Aggregated confidence statistics.")

# Compute bootstrapped confidence intervals
confs['lwr'] = confs.apply(lambda row: boot_lower(net[net['Time'] == row['Time']]['conf'].values, np.mean, bootstrap_r), axis=1)
confs['upr'] = confs.apply(lambda row: boot_upper(net[net['Time'] == row['Time']]['conf'].values, np.mean, bootstrap_r), axis=1)
confs['VaR'] = confs.apply(lambda row: np.percentile(net[net['Time'] == row['Time']]['conf'].values, 5), axis=1)
logging.info("Computed confidence intervals.")

# Convert transaction to categorical and time to minutes
confs2plot = confs.copy()
confs2plot['Condition'] = confs2plot['Transaction'].astype(str)  # Rename Transaction to Condition to match R script
confs2plot['Time'] = confs2plot['Time'] / 60000
confs2plot['Time'] = confs2plot['Time'].round(2)

# Drop NaN values in confidence intervals to avoid issues
logging.info("Dropping NaN values from confidence intervals.")
confs2plot = confs2plot.dropna(subset=['lwr', 'upr'])

# Ensure sorting for proper visualization
confs2plot = confs2plot.sort_values(by=['Time'])

# Plot confidence over time
logging.info("Generating confidence over time plot...")
plt.figure(figsize=(10, 6))
sns.lineplot(data=confs2plot, x='Time', y='avgConf', hue='Condition')
plt.xlabel("Time (min)")
plt.ylabel("Confidence")
plt.title("Confidence in f over time")
plt.axhline(y=0.8, color='r', linestyle='--')
plt.savefig("confidence_over_time.png")
plt.close()
logging.info("Saved confidence_over_time.png")

# Plot with confidence intervals
logging.info("Generating confidence interval plot...")
plt.figure(figsize=(10, 6))
for cond in confs2plot['Condition'].unique():
    subset = confs2plot[confs2plot['Condition'] == cond]

    sns.lineplot(data=subset, x='Time', y='avgConf', label=cond)

    min_range = 0.01  # Ensure a minimum width of CI for visualization
    lower_bound = np.maximum(subset['lwr'], subset['avgConf'] - min_range)
    upper_bound = np.minimum(subset['upr'], subset['avgConf'] + min_range)

    plt.fill_between(subset['Time'], lower_bound, upper_bound, alpha=0.2, label=f"{cond} CI")


plt.xlabel("Time (min)")
plt.ylabel("Confidence")
plt.title("Confidence in f over time (mean and 95% CI)")
plt.axhline(y=0.8, color='r', linestyle='--')
plt.legend(title="Condition")
plt.savefig("confidence_with_CI.png")
plt.close()
logging.info("Saved confidence_with_CI.png")
