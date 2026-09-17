import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# --- Configuration ---
# Set the filenames for your data
SIM_DATA_FILE = 'simulation_times.csv'
REAL_DATA_FILE = 'february_bitcoin.csv'
OUTPUT_FILENAME = 'qq_plot.png'

# --- Load Data ---
try:
    # Read the single column from each CSV file
    sim_data = pd.read_csv(SIM_DATA_FILE).iloc[:, 0]
    real_data = pd.read_csv(REAL_DATA_FILE).iloc[:, 0]
except FileNotFoundError as e:
    print(f"Error: {e}. Please make sure your CSV files are in the same directory as the script.")
    exit()

# --- Prepare Data for Q-Q Plot ---
# Ensure both series are sorted
sim_data_sorted = np.sort(sim_data)
real_data_sorted = np.sort(real_data)

# Create an array of probabilities from 0 to 1
# The number of points is determined by the smaller dataset
n = min(len(sim_data_sorted), len(real_data_sorted))
percentiles = np.linspace(0.01, 0.99, n)

# Get the quantiles for each dataset at the specified percentiles
sim_quantiles = np.quantile(sim_data_sorted, percentiles)
real_quantiles = np.quantile(real_data_sorted, percentiles)

# --- Plotting ---
plt.figure(figsize=(8, 8))

# Create the scatter plot of the quantiles
plt.scatter(real_quantiles, sim_quantiles, alpha=0.5, c='royalblue', label='Data Quantiles')

# Add the 45-degree reference line (y=x) for a perfect match
max_val = max(sim_quantiles.max(), real_quantiles.max())
min_val = min(sim_quantiles.min(), real_quantiles.min())
plt.plot([min_val, max_val], [min_val, max_val], 'r--', linewidth=2, label='Perfect Match Line')

# --- Formatting for Publication Quality ---
plt.title('Q-Q Plot: Simulated vs. Real-World Block Times', fontsize=16)
plt.xlabel('Real-World Data Quantiles (minutes)', fontsize=12)
plt.ylabel('Simulated Data Quantiles (minutes)', fontsize=12)
plt.legend(fontsize=11)
plt.grid(True, linestyle='--')
plt.axis('equal') # Ensures the 45-degree line is truly 45 degrees
plt.tight_layout()
plt.savefig(OUTPUT_FILENAME, dpi=300)

print(f"Q-Q plot saved as '{OUTPUT_FILENAME}'")
plt.show()
