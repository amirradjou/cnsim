import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns

# Define parameters
CONFIDENCE_THRESHOLDS = [0.6, 0.8, 1.0]  # Multiple finality thresholds
BOOTSTRAP_R = 499            # Bootstrap iterations if needed

# Load Data
experiment = "2025.06.05 17.26.16"
file_path = f"../../thesis-log/base/{experiment}/BeliefLog - {experiment}.csv"

# Read CSV
data = pd.read_csv(file_path)
data.columns = data.columns.str.strip()

# Rename columns for consistency
data.rename(columns={"SimID": "Simulation", "Transaction ID": "Transaction",
                     "Time (ms from start)": "Time", "Believes": "Believes"}, inplace=True)

# Convert Time to seconds for easier analysis
data["Time"] = data["Time"]

# Aggregate belief confidence across simulations
net_pre = data.groupby(["Simulation", "Transaction", "Time"]).agg(conf=("Believes", "mean")).reset_index()

# Find the end of simulation time
end_time = net_pre.groupby("Simulation")["Time"].max().min()

# Filter transactions until the end time
net = net_pre[net_pre["Time"] <= end_time]

# Aggregate over simulations to calculate confidence
confs = net.groupby(["Simulation", "Time", "Transaction"]).agg(
    avgConf=("conf", "mean"),
    sd=("conf", "std"),
    medConf=("conf", "median")
).reset_index()

# Function to calculate first seen time and finality score
def calculate_finality(confs, raw_data, thresholds):
    finality_results = []

    first_seen_times = raw_data[raw_data["Believes"] > 0].groupby(["Simulation", "Transaction"]) \
        ["Time"].min().reset_index()
    first_seen_times.rename(columns={"Time": "First Seen Time"}, inplace=True)

    for threshold in thresholds:
        for (simulation, transaction), df in confs.groupby(["Simulation", "Transaction"]):
            df = df.sort_values("Time")  # Ensure time order

            # Find the first time the confidence reaches threshold
            first_reach_time = df[df["avgConf"] >= threshold]["Time"].min()

            if pd.notna(first_reach_time):
                # Check if confidence stays above threshold after first reach
                stays_final = (df[df["Time"] >= first_reach_time]["avgConf"] >= threshold).mean()
            else:
                stays_final = 0  # If never reaches threshold, finality score is 0

            first_seen_time = first_seen_times.loc[
                (first_seen_times["Simulation"] == simulation) & (first_seen_times["Transaction"] == transaction),
                "First Seen Time"
            ].values[0]
            time_to_final = first_reach_time - first_seen_time if pd.notna(first_reach_time) else np.nan

            finality_results.append({
                "Threshold": threshold,
                "Simulation": simulation,
                "Transaction": transaction,
                "First Seen Time": first_seen_time,
                "First Reach Time": first_reach_time,
                "Finality Score": stays_final,
                "Time to Finality": time_to_final
            })

    finality_df = pd.DataFrame(finality_results)

    # Calculate mean and std deviation of time to finality per transaction across all simulations for each threshold
    summary_stats = finality_df.groupby(["Threshold", "Transaction"])["Time to Finality"].agg([
        ("Mean Time to Finality", "mean"), ("Std Dev Time to Finality", "std")
    ]).reset_index()

    return finality_df, summary_stats

# Calculate finality scores
finality_results, summary_stats = calculate_finality(confs, data, CONFIDENCE_THRESHOLDS)

# Add 'ReachedFinality' column
finality_results["ReachedFinality"] = finality_results["First Reach Time"].notna()

# Compute ratio of ReachedFinality for each threshold
finality_ratio = (
    finality_results
    .groupby("Threshold")["ReachedFinality"]
    .mean()
    .reset_index(name="Ratio of Reached Finality")
)

# Print or save the ratio
print("Ratio of transactions reaching finality per threshold:")
print(finality_ratio)

# (Optional) Ratio by transaction or simulation
finality_ratio_by_tx = (
    finality_results
    .groupby(["Threshold", "Transaction"])["ReachedFinality"]
    .mean()
    .reset_index(name="Ratio of Reached Finality")
)

finality_ratio_by_sim = (
    finality_results
    .groupby(["Threshold", "Simulation"])["ReachedFinality"]
    .mean()
    .reset_index(name="Ratio of Reached Finality")
)

# Save data frames to CSV if needed
output_path = f"../../thesis-log/base/{experiment}/FinalityScores_{experiment}.csv"
finality_results.to_csv(output_path, index=False)

summary_output_path = f"../../thesis-log/base/{experiment}/FinalitySummary_{experiment}.csv"
summary_stats.to_csv(summary_output_path, index=False)

ratio_output_path = f"../../thesis-log/base/{experiment}/FinalityRatio_{experiment}.csv"
finality_ratio.to_csv(ratio_output_path, index=False)

ratio_by_tx_output_path = f"../../thesis-log/base/{experiment}/FinalityRatioByTx_{experiment}.csv"
finality_ratio_by_tx.to_csv(ratio_by_tx_output_path, index=False)

ratio_by_sim_output_path = f"../../thesis-log/base/{experiment}/FinalityRatioBySim_{experiment}.csv"
finality_ratio_by_sim.to_csv(ratio_by_sim_output_path, index=False)

# Print results
print(finality_results)
print(summary_stats)
print(finality_ratio_by_tx)
print(finality_ratio_by_sim)

mean_time_to_finality = (
    finality_results
    .groupby("Threshold")["Time to Finality"]
    .mean()               # compute the mean, ignoring NaN
    .reset_index(name="Mean Time to Finality")
)

print(mean_time_to_finality)


# Plot confidence over time with threshold lines
plt.figure(figsize=(10, 6))
sns.lineplot(data=confs, x="Time", y="avgConf", hue="Transaction", marker="o")
for threshold in CONFIDENCE_THRESHOLDS:
    plt.axhline(y=threshold, linestyle='dashed', label=f'Finality Threshold ({threshold})')
plt.xlabel("Time (seconds)")
plt.ylabel("Confidence")
plt.title("Confidence in Transactions Over Time (Mean Confidence per Transaction)")
plt.legend()
plt.grid()
plt.show()
