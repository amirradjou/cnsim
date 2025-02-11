import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns

# Define parameters
CONFIDENCE_THRESHOLD = 0.8  # Finality threshold
BOOTSTRAP_R = 499            # Bootstrap iterations if needed

# Load Data
experiment = "2025.02.11 15.52.50"
file_path = f"../../log/{experiment}/BeliefLog - {experiment}.csv"

# Read CSV
data = pd.read_csv(file_path)
data.columns = data.columns.str.strip()

# Rename columns for consistency
data.rename(columns={"SimID": "Simulation", "Transaction ID": "Transaction",
                     "Time (ms from start)": "Time", "Believes": "Believes"}, inplace=True)

# Convert Time to minutes for easier analysis
data["Time"] = data["Time"] / 60000

# Aggregate belief confidence across simulations
net_pre = data.groupby(["Simulation", "Transaction", "Time"]).agg(conf=("Believes", "mean")).reset_index()

# Find the end of simulation time
end_time = net_pre.groupby("Simulation")["Time"].max().min()

# Filter transactions until the end time
net = net_pre[net_pre["Time"] <= end_time]

# Aggregate over simulations to calculate confidence
confs = net.groupby(["Time", "Transaction"]).agg(
    avgConf=("conf", "mean"),
    sd=("conf", "std"),
    medConf=("conf", "median")
).reset_index()

# Function to calculate finality score
def calculate_finality(confs, threshold=CONFIDENCE_THRESHOLD):
    finality_results = []

    for transaction, df in confs.groupby("Transaction"):
        df = df.sort_values("Time")  # Ensure time order

        # Find the first time the confidence reaches threshold
        first_reach_time = df[df["avgConf"] >= threshold]["Time"].min()

        if pd.notna(first_reach_time):
            # Check if confidence stays above threshold after first reach
            stays_final = (df[df["Time"] >= first_reach_time]["avgConf"] >= threshold).mean()
        else:
            stays_final = 0  # If never reaches threshold, finality score is 0

        finality_results.append({"Transaction": transaction, "First Reach Time": first_reach_time, "Finality Score": stays_final})

    return pd.DataFrame(finality_results)

# Calculate finality scores
finality_results = calculate_finality(confs)

# Save to CSV
output_path = f"../../log/{experiment}/FinalityScores_{experiment}.csv"
finality_results.to_csv(output_path, index=False)

# Print results
print(finality_results)

# Plot confidence over time with threshold line
plt.figure(figsize=(10, 6))
sns.lineplot(data=confs, x="Time", y="avgConf", hue="Transaction", marker="o")
plt.axhline(y=CONFIDENCE_THRESHOLD, color='r', linestyle='dashed', label=f'Finality Threshold ({CONFIDENCE_THRESHOLD})')
plt.xlabel("Time (minutes)")
plt.ylabel("Confidence")
plt.title("Confidence in f over time (Mean Confidence per Transaction)")
plt.legend()
plt.grid()
plt.show()
