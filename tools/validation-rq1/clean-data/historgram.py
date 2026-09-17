import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# --- Configuration ---
# Set the filenames for your data
SIM_DATA_FILE = 'simulation_times.csv'
REAL_DATA_FILE = 'february_bitcoin.csv'
OUTPUT_FILENAME = 'comparison_histogram.png'

# --- Load Data ---
try:
    # Read the single column from each CSV file
    sim_data = pd.read_csv(SIM_DATA_FILE).iloc[:, 0]
    real_data = pd.read_csv(REAL_DATA_FILE).iloc[:, 0]
except FileNotFoundError as e:
    print(f"Error: {e}. Please make sure your CSV files are in the same directory as the script.")
    exit()

# --- Plotting ---
# Create a figure and axes for the plot
plt.figure(figsize=(12, 7))

# Define the bins to be used for both histograms for a fair comparison
max_val = max(sim_data.max(), real_data.max())
bins = np.arange(0, max_val + 2, 2) # Using a bin width of 2 minutes

# Plot the first histogram (Simulation Data)
plt.hist(sim_data, bins=bins, alpha=0.7, label=f'Simulated Data (n={len(sim_data)})', color='royalblue', density=True)

# Plot the second histogram (Real-World Data) on the same axes
plt.hist(real_data, bins=bins, alpha=0.7, label=f'Real-World Data (n={len(real_data)})', color='darkorange', density=True)

# --- Formatting for Publication Quality ---
# Add a title and labels
plt.title('Distribution of Simulated vs. Real-World Block Times', fontsize=16)
plt.xlabel('Block Time (minutes)', fontsize=12)
plt.ylabel('Density', fontsize=12)

# Add a legend to identify the datasets
plt.legend(fontsize=11)

# Add a grid for better readability
plt.grid(axis='y', linestyle='--', alpha=0.7)

# Set the x-axis limit to focus on the main distribution, you can adjust this if needed
plt.xlim(0, 60)

# Ensure tight layout and save the figure
plt.tight_layout()
plt.savefig(OUTPUT_FILENAME, dpi=300) # dpi=300 is good for publications

print(f"Histogram saved as '{OUTPUT_FILENAME}'")

# Optionally, display the plot
plt.show()
