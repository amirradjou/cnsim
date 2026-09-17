import pandas as pd
import os
import ast
import math
import numpy as np
import matplotlib.pyplot as plt
import glob

# ==============================================================================
# --- Main Configuration ---
# ==============================================================================

# --- Analysis & Plotting Configuration ---
# 1. Set the range of Transaction IDs to include in the plot analysis.
#    Set both to None to include all transactions.
TRANSACTION_ID_MIN = 1
TRANSACTION_ID_MAX = 50001

# 2. Set the node acceptance threshold for the per-scenario plots (e.g., 0.90 for 90%).
ACCEPTANCE_THRESHOLD = 0.95

# 3. Set the ratios for the final summary analysis file.
SUMMARY_RATIOS = [0.25, 0.50, 0.75, 0.90, 1.00]


# ==============================================================================
# --- Helper Functions ---
# ==============================================================================

def clean_column_names(df):
    """Strips leading/trailing whitespace from all column names of a DataFrame."""
    df.columns = [col.strip() for col in df.columns]
    return df

def parse_content_to_list(content_str):
    """
    Safely parses a string from the 'Content' column into a list of transaction IDs.
    Handles multiple formats: {a;b;c}, [a, b, c], and single items.
    """
    if isinstance(content_str, list):
        return content_str
    if not isinstance(content_str, str) or pd.isna(content_str) or not content_str.strip():
        return []

    content_str = content_str.strip()
    if content_str.startswith('{') and content_str.endswith('}'):
        # Handles formats like {123;456;789}
        return [item.strip() for item in content_str.strip('{}').split(';') if item.strip()]
    if content_str.startswith('[') and content_str.endswith(']'):
        # Handles formats like [123, 456, 789]
        try:
            return ast.literal_eval(content_str)
        except (ValueError, SyntaxError):
            return [item.strip() for item in content_str.strip('[]').split(',') if item.strip()]
    else:
        # Handles single items
        return [content_str]

# --- NEW: Function to identify the longest chain for a given node ---
def get_longest_chain_for_node(node_df):
    """
    Identifies the longest chain for a single node's block data.
    Assumes node_df is a DataFrame for one node in one simulation.
    """
    if node_df.empty:
        return pd.DataFrame()

    # Find the block(s) with the maximum height
    max_height = node_df['Height'].max()
    top_blocks = node_df[node_df['Height'] == max_height]

    # Tie-break by choosing the one with the earliest system time and then by BlockID
    top_block = top_blocks.sort_values(by=['SysTime', 'BlockID']).iloc[0]

    longest_chain_blocks = []
    current_block_series = top_block
    
    # Create a dictionary for quick lookups
    block_dict = node_df.set_index('BlockID').to_dict('index')

    while current_block_series is not None:
        longest_chain_blocks.append(current_block_series)
        parent_id = current_block_series['ParentBlockID']

        # Genesis block has parent ID 0 or is not in the log
        if parent_id == 0 or parent_id not in block_dict:
            break
            
        parent_block_data = block_dict.get(parent_id)
        
        if parent_block_data:
            # Add BlockID back in since it's the index
            parent_block_data['BlockID'] = parent_id
            current_block_series = pd.Series(parent_block_data)
        else:
            # Parent block not found, chain ends
            current_block_series = None

    return pd.DataFrame(longest_chain_blocks)


# ==============================================================================
# --- Core Processing Steps ---
# ==============================================================================

def get_corrected_structure_log(structure_df, event_df):
    """Corrects the SimTime in the structure log based on container events."""
    event_df_copy = event_df.copy()
    relevant_events = ['Event_ContainerArrival', 'Event_ContainerValidation']
    event_df_copy['EventType'] = event_df_copy['EventType'].str.strip()
    filtered_events = event_df_copy[event_df_copy['EventType'].isin(relevant_events)].copy()
    filtered_events.rename(columns={'Node': 'NodeID', 'Object': 'BlockID'}, inplace=True)
    time_corrections = filtered_events.groupby(['SimID', 'NodeID', 'BlockID'])['SimTime'].min().reset_index()
    time_corrections.rename(columns={'SimTime': 'CorrectedSimTime'}, inplace=True)
    updated_df = pd.merge(structure_df, time_corrections, on=['SimID', 'NodeID', 'BlockID'], how='left')
    updated_df['SimTime'] = updated_df['CorrectedSimTime'].fillna(updated_df['SimTime'])
    updated_df.drop(columns=['CorrectedSimTime'], inplace=True)
    return updated_df

def get_first_transaction_times(event_df):
    """Finds the first time each transaction appeared in the system."""
    event_df_copy = event_df.copy()
    event_df_copy['EventType'] = event_df_copy['EventType'].str.strip()
    tx_arrivals = event_df_copy[event_df_copy['EventType'] == 'Event_NewTransactionArrival'].copy()
    first_seen = tx_arrivals.groupby(['SimID', 'Object'])['SimTime'].min().reset_index()
    first_seen.rename(columns={'Object': 'Transaction', 'SimTime': 'FirstTransactionTime'}, inplace=True)
    return first_seen

# --- MODIFIED: This function now processes based on the longest chain ---
def calculate_and_plot(structure_fixed_df, first_seen_df, output_csv_path, output_plot_path):
    """
    Calculates settlement times based on the longest chain, generates the
    acceptance plot, and returns data for summary.
    """
    print("  Step 3: Calculating transaction settlement times based on the longest chain...")
    
    all_settlement_data = []

    # Group by each simulation and node to find the longest chain for each
    for (sim_id, node_id), group in structure_fixed_df.groupby(['SimID', 'NodeID']):
        print(f"    - Processing SimID: {sim_id}, Node: {node_id}")
        
        longest_chain_df = get_longest_chain_for_node(group)

        if longest_chain_df.empty:
            continue

        longest_chain_df['TransactionList'] = longest_chain_df['Content'].apply(parse_content_to_list)
        exploded_df = longest_chain_df.explode('TransactionList')
        exploded_df['TransactionList'] = pd.to_numeric(exploded_df['TransactionList'], errors='coerce')
        exploded_df.dropna(subset=['TransactionList'], inplace=True)
        exploded_df['TransactionList'] = exploded_df['TransactionList'].astype('int64')
        exploded_df.rename(columns={'TransactionList': 'Transaction', 'SimTime': 'TimeSeenInLongestChain'}, inplace=True)

        first_seen_df['Transaction'] = first_seen_df['Transaction'].astype('int64')

        settlement_df = pd.merge(exploded_df, first_seen_df, on=['SimID', 'Transaction'], how='left')
        settlement_df['TimeToSettle'] = settlement_df['TimeSeenInLongestChain'] - settlement_df['FirstTransactionTime']
        
        output_columns = ['SimID', 'Transaction', 'NodeID', 'TimeSeenInLongestChain', 'FirstTransactionTime', 'TimeToSettle']
        all_settlement_data.append(settlement_df.reindex(columns=output_columns))

    if not all_settlement_data:
        print("    - Error: No settlement data was generated. Check input files and longest chain logic.")
        return None
        
    final_settlement_df = pd.concat(all_settlement_data, ignore_index=True)
    
    # Save the detailed settlement data
    print(f"    - Saving settlement time data to: {output_csv_path}")
    final_settlement_df.to_csv(output_csv_path, index=False)

    print("  Step 4: Generating transaction acceptance plot...")
    df = final_settlement_df.copy()
    if TRANSACTION_ID_MIN is not None and TRANSACTION_ID_MAX is not None:
        df = df[(df['Transaction'] >= TRANSACTION_ID_MIN) & (df['Transaction'] <= TRANSACTION_ID_MAX)]
    
    df.dropna(subset=['TimeToSettle'], inplace=True)
    if df.empty:
        print("    - Error: No data to plot after filtering. Skipping plot generation.")
        return None

    df['TimeToSettle'] = df['TimeToSettle'] / 60000.0  # Convert to minutes
    avg_settle_df = df.groupby(['Transaction', 'NodeID'])['TimeToSettle'].mean().reset_index()
    
    total_nodes = structure_fixed_df['NodeID'].nunique() # Use original df for total node count
    source_tx_df = first_seen_df.copy()
    if TRANSACTION_ID_MIN is not None and TRANSACTION_ID_MAX is not None:
        source_tx_df = source_tx_df[(source_tx_df['Transaction'] >= TRANSACTION_ID_MIN) & (source_tx_df['Transaction'] <= TRANSACTION_ID_MAX)]
    total_transactions = source_tx_df['Transaction'].nunique()

    if total_nodes == 0 or total_transactions == 0:
        print("    - Error: No nodes or transactions in range. Skipping plot.")
        return None
    
    threshold_node_count = math.ceil(ACCEPTANCE_THRESHOLD * total_nodes)
    nodes_per_tx = avg_settle_df.groupby('Transaction')['NodeID'].count()
    eligible_txs = nodes_per_tx[nodes_per_tx >= threshold_node_count].index
    
    filtered_settle_df = avg_settle_df[avg_settle_df['Transaction'].isin(eligible_txs)]
    acceptance_times = filtered_settle_df.sort_values('TimeToSettle').groupby('Transaction').nth(threshold_node_count - 1)
    
    if acceptance_times.empty:
        print("    - Error: No transactions met the acceptance threshold. Skipping plot.")
        return {'total_transactions': total_transactions, 'acceptance_times': pd.Series(dtype='float64')}
    
    sorted_times = np.sort(acceptance_times['TimeToSettle'].values)
    y_values = np.arange(1, len(sorted_times) + 1) / total_transactions
    plot_x = np.insert(sorted_times, 0, 0)
    plot_y = np.insert(y_values, 0, 0)

    plt.figure(figsize=(12, 7))
    plt.plot(plot_x, plot_y, drawstyle='steps-post')
    plt.title(f'Cumulative Transaction Acceptance Ratio ({int(ACCEPTANCE_THRESHOLD*100)}% of Nodes)', fontsize=16)
    plt.xlabel('Time To Settle (minutes)', fontsize=12)
    plt.ylabel('Ratio of Accepted Transactions', fontsize=12)
    plt.xlim(left=0)
    plt.ylim(0, 1.05)
    plt.grid(True, which='both', linestyle='--', linewidth=0.5)
    plt.tight_layout()
    
    print(f"    - Plot saved successfully to: {output_plot_path}")
    plt.savefig(output_plot_path)
    plt.close()

    return {'total_transactions': total_transactions, 'acceptance_times': acceptance_times['TimeToSettle']}


def analyze_for_summary(analysis_data, scenario_name):
    """Calculates the time to reach different acceptance ratios for the summary file."""
    if not analysis_data or analysis_data['acceptance_times'].empty:
        return {'Scenario': scenario_name}
        
    total_tx = analysis_data['total_transactions']
    sorted_times = np.sort(analysis_data['acceptance_times'].values)
    
    summary_row = {'Scenario': scenario_name}
    
    for ratio in SUMMARY_RATIOS:
        col_name = f'Time_to_{int(ratio*100)}%_Settle (min)'
        target_tx_count = math.ceil(ratio * total_tx)
        
        if target_tx_count > len(sorted_times):
            summary_row[col_name] = np.nan
        else:
            summary_row[col_name] = sorted_times[target_tx_count - 1]
            
    return summary_row

def process_scenario(scenario_path):
    """Runs the full analysis pipeline for a single scenario directory."""
    scenario_name = os.path.basename(scenario_path)
    print(f"\n--- Processing Scenario: {scenario_name} ---")

    log_dirs = [d for d in glob.glob(os.path.join(scenario_path, '*')) if os.path.isdir(d)]
    if not log_dirs:
        print(f"  - No log directory found in {scenario_path}. Skipping.")
        return None
    log_dir_path = log_dirs[0]

    try:
        structure_log_path = glob.glob(os.path.join(log_dir_path, 'StructureLog*.csv'))[0]
        event_log_path = glob.glob(os.path.join(log_dir_path, 'EventLog*.csv'))[0]
    except IndexError:
        print(f"  - Could not find StructureLog or EventLog in {log_dir_path}. Skipping.")
        return None

    print(f"  - Found log files in: {log_dir_path}")
    structure_df = pd.read_csv(structure_log_path)
    event_df = pd.read_csv(event_log_path)
    
    structure_df = clean_column_names(structure_df)
    event_df = clean_column_names(event_df)
    
    print("  Step 1: Correcting StructureLog SimTime...")
    structure_fixed_df = get_corrected_structure_log(structure_df, event_df)
    
    print("  Step 2: Finding first transaction arrival times...")
    first_seen_df = get_first_transaction_times(event_df)
    
    output_csv = os.path.join(log_dir_path, f"{scenario_name}_SettlementTime_LongestChain.csv")
    output_plot = os.path.join(log_dir_path, f"{scenario_name}_AcceptanceRatio_LongestChain.png")

    analysis_data = calculate_and_plot(structure_fixed_df, first_seen_df, output_csv, output_plot)
    
    return analysis_data, scenario_name


def main():
    """Main function to find and process all scenario directories."""
    print("===== Full Post-Processing and Comparison Pipeline Started (Longest Chain Analysis) =====\n")
    
    root_path = '.'
    scenarios = [d for d in os.listdir(root_path) if os.path.isdir(os.path.join(root_path, d)) and not d.startswith('.')]
    
    if not scenarios:
        print("Error: No scenario subdirectories found.")
        return

    summary_results = []
    for scenario_dir in sorted(scenarios):
        scenario_path = os.path.join(root_path, scenario_dir)
        result = process_scenario(scenario_path)
        
        if result:
            analysis_data, scenario_name = result
            summary_row = analyze_for_summary(analysis_data, scenario_name)
            summary_results.append(summary_row)

    if summary_results:
        print("\n--- Generating Master Analysis Summary (Longest Chain) ---")
        summary_df = pd.DataFrame(summary_results)
        summary_filename = 'Master_Analysis_Summary_LongestChain.csv'
        summary_df.to_csv(summary_filename, index=False)
        print(f"Summary saved to {summary_filename}")
    
    print("\n===== Pipeline Finished Successfully! =====")


if __name__ == '__main__':
    main()
