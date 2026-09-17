import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# --- Configuration ---
SIM_DATA_FILE = 'simulation_times.csv'
REAL_DATA_FILE = 'february_bitcoin.csv'
OUTPUT_FILENAME = 'ecdf_comparison_plot.png'

# --- Load Data ---
try:
    sim_data = pd.read_csv(SIM_DATA_FILE).iloc[:, 0]
    real_data = pd.read_csv(REAL_DATA_FILE).iloc[:, 0]
except FileNotFoundError as e:
    print(f"Error: {e}. Please make sure your CSV files are in the same directory.")
    exit()

# --- Prepare eCDF Data ---
def get_ecdf_data(data_series):
    """Sorts data and calculates x, y coordinates for an eCDF plot."""
    x = np.sort(data_series)
    y = np.arange(1, len(x) + 1) / len(x)
    return x, y

sim_x, sim_y = get_ecdf_data(sim_data)
real_x, real_y = get_ecdf_data(real_data)

# --- Plotting ---
plt.figure(figsize=(12, 7))

# Plot the eCDFs
plt.plot(sim_x, sim_y, marker='.', linestyle='none', alpha=0.5, label='Simulated Data eCDF')
plt.plot(real_x, real_y, marker='.', linestyle='none', alpha=0.5, label='Real-World Data eCDF')

# --- Formatting for Publication Quality ---
plt.title('eCDF of Simulated vs. Real-World Block Times', fontsize=16)
plt.xlabel('Block Time (minutes)', fontsize=12)
plt.ylabel('Cumulative Probability', fontsize=12)
plt.legend(fontsize=11)
plt.grid(True, linestyle='--')
plt.xlim(0, 60) # Focus on the main distribution

plt.tight_layout()
plt.savefig(OUTPUT_FILENAME, dpi=300)

print(f"eCDF plot saved as '{OUTPUT_FILENAME}'")
plt.show()
