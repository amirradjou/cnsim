import pandas as pd
import os
import numpy as np
import matplotlib.pyplot as plt
import glob
import math

# ==============================================================================
# --- Configuration ---
# ==============================================================================

# 1. Set the acceptance threshold (must match the original script for consistency).
ACCEPTANCE_THRESHOLD = 0.95

# 2. Define the name of the base scenario for comparison.
BASE_SCENARIO_NAME = 'base'

# 3. Define the directory where comparison plots will be saved.
OUTPUT_DIR = 'comparison_plots'

# 4. Set the range of Transaction IDs to include in the plot analysis.
#    Set both to None to include all transactions (should match original script).
TRANSACTION_ID_MIN = 1
TRANSACTION_ID_MAX = 50001


# ==============================================================================
# --- Core Functions ---
# ==============================================================================

def prepare_plot_data(df):
    """
    Processes a settlement dataframe to calculate the x and y coordinates for a CDF plot.

    Args:
        df (pd.DataFrame): The settlement data for a single scenario.

    Returns:
        A tuple (plot_x, plot_y) for plotting, or (None, None) if data is insufficient.
    """
    # Filter by transaction ID range if specified
    if TRANSACTION_ID_MIN is not None and TRANSACTION_ID_MAX is not None:
        df = df[(df['Transaction'] >= TRANSACTION_ID_MIN) & (df['Transaction'] <= TRANSACTION_ID_MAX)]
    
    df.dropna(subset=['TimeToSettle'], inplace=True)
    if df.empty:
        return None, None

    # Convert settlement time from milliseconds to minutes
    df['TimeToSettle'] = df['TimeToSettle'] / 60000.0

    # Group by transaction and node to handle cases where a transaction is seen multiple times
    avg_settle_df = df.groupby(['Transaction', 'NodeID'])['TimeToSettle'].mean().reset_index()

    total_nodes = df['NodeID'].nunique()
    total_transactions = df['Transaction'].nunique()

    if total_nodes == 0 or total_transactions == 0:
        return None, None

    # Determine the number of nodes required to meet the acceptance threshold
    threshold_node_count = math.ceil(ACCEPTANCE_THRESHOLD * total_nodes)

    # Find transactions that were accepted by at least the threshold number of nodes
    nodes_per_tx = avg_settle_df.groupby('Transaction')['NodeID'].count()
    eligible_txs = nodes_per_tx[nodes_per_tx >= threshold_node_count].index
    filtered_settle_df = avg_settle_df[avg_settle_df['Transaction'].isin(eligible_txs)]

    # For each eligible transaction, find the time it took to be accepted by the Nth node
    # (where N is the threshold_node_count)
    acceptance_times = filtered_settle_df.sort_values('TimeToSettle').groupby('Transaction').nth(threshold_node_count - 1)

    if acceptance_times.empty:
        return None, None

    # Prepare data for CDF plot
    sorted_times = np.sort(acceptance_times['TimeToSettle'].values)
    y_values = np.arange(1, len(sorted_times) + 1) / total_transactions
    
    # Insert a (0,0) point to start the plot from the origin
    plot_x = np.insert(sorted_times, 0, 0)
    plot_y = np.insert(y_values, 0, 0)

    return plot_x, plot_y


def find_latest_log_dir(scenario_path):
    """Finds the most recent timestamped log directory within a scenario folder."""
    log_dirs = [d for d in glob.glob(os.path.join(scenario_path, '*')) if os.path.isdir(d)]
    if not log_dirs:
        return None
    return max(log_dirs, key=os.path.getmtime)


def main():
    """Main function to find scenario data and generate comparison plots."""
    print("===== Comparison Plotting Script Started =====\n")
    
    # Create the output directory if it doesn't exist
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print(f"Output directory set to: ./{OUTPUT_DIR}/\n")

    # --- Step 1: Process the Base Scenario ---
    base_path = os.path.join('.', BASE_SCENARIO_NAME)
    base_log_dir = find_latest_log_dir(base_path)
    if not base_log_dir:
        print(f"Error: Could not find log directory for base scenario '{BASE_SCENARIO_NAME}'.")
        return

    try:
        base_csv_path = glob.glob(os.path.join(base_log_dir, f'{BASE_SCENARIO_NAME}_SettlementTime.csv'))[0]
        base_df = pd.read_csv(base_csv_path)
        base_x, base_y = prepare_plot_data(base_df)
        if base_x is None:
            print(f"Error: Could not process data for base scenario. No plot data generated.")
            return
        print(f"Successfully processed base scenario data from: {base_csv_path}")
    except (IndexError, FileNotFoundError):
        print(f"Error: Could not find or read '{BASE_SCENARIO_NAME}_SettlementTime.csv' in {base_log_dir}.")
        return

    # --- Step 2: Process and Plot Comparison Scenarios ---
    root_path = '.'
    scenarios = [d for d in os.listdir(root_path) if os.path.isdir(os.path.join(root_path, d)) and not d.startswith('.') and d != OUTPUT_DIR]
    
    for scenario_name in sorted(scenarios):
        if scenario_name == BASE_SCENARIO_NAME:
            continue

        print(f"\n--- Comparing Scenario: {scenario_name} ---")
        scenario_path = os.path.join(root_path, scenario_name)
        log_dir = find_latest_log_dir(scenario_path)

        if not log_dir:
            print(f"  - Warning: No log directory found for '{scenario_name}'. Skipping.")
            continue

        try:
            scenario_csv_path = glob.glob(os.path.join(log_dir, f'{scenario_name}_SettlementTime.csv'))[0]
            scenario_df = pd.read_csv(scenario_csv_path)
            scenario_x, scenario_y = prepare_plot_data(scenario_df)

            if scenario_x is None:
                print(f"  - Warning: Could not process data for '{scenario_name}'. Skipping plot.")
                continue

            # --- Step 3: Create and Save the Plot ---
            plt.figure(figsize=(12, 7))
            
            # Plot base scenario
            plt.plot(base_x, base_y, drawstyle='steps-post', linestyle='--', color='black', label=f'Base ({BASE_SCENARIO_NAME})')
            
            # Plot current scenario
            plt.plot(scenario_x, scenario_y, drawstyle='steps-post', linestyle='-', color='blue', label=f'Scenario ({scenario_name})')
            
            plt.title(f'Comparison: {scenario_name} vs. Base', fontsize=16)
            plt.xlabel('Time To Settle (minutes)', fontsize=12)
            plt.ylabel(f'Ratio of Accepted Transactions ({int(ACCEPTANCE_THRESHOLD*100)}% of Nodes)', fontsize=12)
            plt.xlim(left=0)
            plt.ylim(0, 1.05)
            plt.grid(True, which='both', linestyle='--', linewidth=0.5)
            plt.legend()
            plt.tight_layout()
            
            output_plot_path = os.path.join(OUTPUT_DIR, f'Compare_{scenario_name}_vs_Base.png')
            plt.savefig(output_plot_path)
            plt.close()
            
            print(f"  - Plot saved successfully to: {output_plot_path}")

        except (IndexError, FileNotFoundError):
            print(f"  - Warning: Could not find settlement CSV for '{scenario_name}'. Skipping.")
            continue
            
    print("\n===== Comparison Plotting Finished Successfully! =====")


if __name__ == '__main__':
    main()
