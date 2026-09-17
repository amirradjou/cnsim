import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import logging
import os

# --- Configure logging ---
# Sets up logging to display informational messages during script execution.
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --- Global Parameters ---
# The confidence threshold that a transaction needs to meet and sustain.
FIXED_BELIEF_THRESHOLD = 0.90
# Assumes the script is run from the root of the directory tree containing the experiment folders.
ROOT_DIR = os.getcwd()
# The base directory where all analysis results will be saved.
OUTPUT_DIR_BASE = "analysis_results_final_90"

# --- Create Base Output Directory ---
# This ensures the folder for saving results exists.
os.makedirs(OUTPUT_DIR_BASE, exist_ok=True)


def analyze_max_height(df):
    """
    Calculates the mean and standard deviation of the maximum blockchain height
    achieved in each simulation run.
    """
    if df.empty or 'Height' not in df.columns or 'SimID' not in df.columns:
        logging.warning("Max height analysis skipped: DataFrame is empty or missing required columns.")
        return np.nan, np.nan
    
    # Find the maximum height for each simulation.
    max_heights = df.groupby('SimID')['Height'].max()
    
    # Compute the mean and standard deviation of these maximum heights.
    mean_val = max_heights.mean()
    std_val = max_heights.std()
    
    return mean_val, std_val


def analyze_forks(df):
    """
    Calculates the mean and standard deviation of the number of forked blocks
    across all simulation runs.
    """
    if df.empty:
        return np.nan, np.nan

    fork_counts = []
    sim_ids = df['SimID'].unique()

    for sim_id in sim_ids:
        sim_df = df[df['SimID'] == sim_id].copy()
        if sim_df.empty:
            continue
        
        # We only care about the existence of a block, not which node saw it.
        sim_df.drop_duplicates(subset=['BlockID'], keep='first', inplace=True)
        
        # Find the tip of the longest chain (highest block).
        longest_chain_tip = sim_df.loc[sim_df['Height'].idxmax()]
        
        # Create a map of each block to its parent for quick traversal.
        block_to_parent = pd.Series(sim_df.ParentBlockID.values, index=sim_df.BlockID).to_dict()
        
        # Trace the longest chain back to the genesis block.
        longest_chain_blocks = set()
        current_block_id = longest_chain_tip['BlockID']
        
        while current_block_id in block_to_parent:
            longest_chain_blocks.add(int(current_block_id))
            parent_id = block_to_parent.get(current_block_id)
            if parent_id is None or parent_id == current_block_id:
                break
            current_block_id = parent_id
        longest_chain_blocks.add(int(current_block_id))

        # Forked blocks are all unique blocks that are NOT in the longest chain.
        total_unique_blocks = set(sim_df['BlockID'].astype(int))
        forked_block_set = total_unique_blocks - longest_chain_blocks
        fork_counts.append(len(forked_block_set))

    if not fork_counts:
        return np.nan, np.nan

    # Calculate the mean and standard deviation of fork counts.
    return np.mean(fork_counts), np.std(fork_counts)


def analyze_confidence_sustain(df, threshold):
    """
    Analyzes if each transaction reaches a confidence threshold and sustains it.
    """
    analysis_results = []
    transactions = df['Transaction'].unique()

    for tx_id in transactions:
        tx_df = df[df['Transaction'] == tx_id].sort_values('Time')
        candidate_times = tx_df[tx_df['avgConf'] >= threshold]['Time']

        met_condition = False
        first_time_met = np.nan

        for time_val in candidate_times:
            subsequent_df = tx_df[tx_df['Time'] >= time_val]
            if (subsequent_df['avgConf'] >= threshold).all():
                met_condition = True
                first_time_met = time_val
                break 
        
        analysis_results.append({
            'Transaction': tx_id,
            'MetSustainCondition': met_condition,
            'FirstTimeSustained (ms)': first_time_met
        })
    return analysis_results


def process_directory(dir_name):
    """
    Processes a single experiment directory: loads all relevant log files, 
    generates a plot, and runs all analyses.
    """
    logging.info(f"--- Starting processing for directory: {dir_name} ---")
    
    # --- Find the timestamped experiment folder ---
    try:
        sub_path = os.path.join(ROOT_DIR, dir_name)
        potential_folders = [f for f in os.listdir(sub_path) if os.path.isdir(os.path.join(sub_path, f))]
        if not potential_folders:
            logging.error(f"No experiment sub-folder found in '{dir_name}'. Skipping.")
            return None
        experiment_name = potential_folders[0]
        logging.info(f"Found experiment folder: {experiment_name}")

    except Exception as e:
        logging.error(f"Could not find a valid experiment sub-folder in '{dir_name}': {e}. Skipping.")
        return None

    # --- Define file paths ---
    belief_log_path = os.path.join(sub_path, experiment_name, f"BeliefLog - {experiment_name}.csv")
    event_log_path = os.path.join(sub_path, experiment_name, f"EventLog - {experiment_name}.csv")
    structure_log_path = os.path.join(sub_path, experiment_name, f"StructureLog - {experiment_name}.csv")
    
    current_output_dir = os.path.join(OUTPUT_DIR_BASE, dir_name)
    os.makedirs(current_output_dir, exist_ok=True)

    # --- Load Data: BeliefLog (for confidence) ---
    try:
        belief_df = pd.read_csv(belief_log_path)
        belief_df.columns = belief_df.columns.str.strip()
        belief_df.rename(columns={"Transaction ID": "Transaction", "Time (ms from start)": "Time"}, inplace=True)
        belief_df["Transaction"] = belief_df["Transaction"].astype(str)
    except FileNotFoundError:
        logging.error(f"BeliefLog not found at {belief_log_path}. Skipping directory '{dir_name}'.")
        return None

    # --- Load Data: StructureLog (for height and forks) ---
    try:
        structure_df = pd.read_csv(structure_log_path)
        structure_df.columns = [col.strip() for col in structure_df.columns]
    except FileNotFoundError:
        logging.warning(f"StructureLog not found at {structure_log_path}. Height and fork analysis will be skipped.")
        structure_df = pd.DataFrame()

    # --- Structural Analysis: Max Height and Forks ---
    mean_max_height, std_max_height = analyze_max_height(structure_df)
    mean_forks, std_forks = analyze_forks(structure_df)
    logging.info(f"Directory '{dir_name}' -> Mean Max Height: {mean_max_height:.2f}, Mean Forks: {mean_forks:.2f}")

    # --- Get Initial Times (t_0) for Transactions ---
    initial_times = {}
    try:
        event_df = pd.read_csv(event_log_path)
        event_df.columns = event_df.columns.str.strip()
        event_df["Object"] = event_df["Object"].astype(str)
        arrivals = event_df[event_df['EventType'] == 'Event_NewTransactionArrival']
        if not arrivals.empty:
            t0_map = arrivals.groupby('Object')['SimTime'].min()
            initial_times = t0_map.to_dict()
    except FileNotFoundError:
        logging.warning(f"EventLog not found at {event_log_path}. Defaulting all t_0 to 0 ms.")
    
    all_transactions = belief_df['Transaction'].unique()
    final_initial_times = {tx: initial_times.get(tx, 0) for tx in all_transactions}

    # --- Confidence Data Preparation ---
    net_conf = belief_df.groupby(["SimID", "Transaction", "Time"])["Believes"].mean().reset_index(name="conf")
    avg_conf = net_conf.groupby(["Time", "Transaction"]).agg(avgConf=('conf', 'mean')).reset_index()
    avg_conf['start_time_ms'] = avg_conf['Transaction'].map(final_initial_times)
    filtered_conf = avg_conf[avg_conf['Time'] >= avg_conf['start_time_ms']].copy()
    
    # --- Generate Plot ---
    if not filtered_conf.empty:
        filtered_conf['Time (min)'] = filtered_conf['Time'] / 60000.0
        plt.figure(figsize=(15, 8))
        sns.lineplot(data=filtered_conf, x='Time (min)', y='avgConf', hue='Transaction', marker='o', markersize=4, alpha=0.7)
        plt.axhline(y=FIXED_BELIEF_THRESHOLD, color='r', linestyle='--', label=f'{int(FIXED_BELIEF_THRESHOLD*100)}% Belief Threshold')
        plt.title(f'Average Confidence vs. Simulation Time\n({dir_name})', fontsize=16)
        plt.xlabel('Simulation Time (min)', fontsize=12)
        plt.ylabel('Average Confidence', fontsize=12)
        plt.ylim(-0.05, 1.05)
        plt.grid(True, which='both', linestyle='--', linewidth=0.5)
        plt.legend(title='Transaction', bbox_to_anchor=(1.02, 1), loc='upper left')
        plt.tight_layout(rect=[0, 0, 0.88, 1])
        plot_path = os.path.join(current_output_dir, "confidence_plot.png")
        plt.savefig(plot_path)
        plt.close()
        logging.info(f"Plot saved to '{plot_path}'")
    else:
        logging.warning("No data to plot for confidence after filtering by t_0.")

    # --- Confidence Sustain Analysis ---
    confidence_results_list = analyze_confidence_sustain(filtered_conf, FIXED_BELIEF_THRESHOLD)
    if not confidence_results_list:
        return None

    # --- Combine All Results for This Directory ---
    results_df = pd.DataFrame(confidence_results_list)
    results_df['Directory'] = dir_name
    
    # Add t0 and calculate TimeToSustain in minutes
    results_df['t0 (min)'] = results_df['Transaction'].map(final_initial_times) / 60000.0
    
    for idx, row in results_df.iterrows():
        tx_id = row['Transaction']
        start_time_ms = final_initial_times.get(tx_id, 0)
        if pd.notna(row['FirstTimeSustained (ms)']):
            duration_ms = row['FirstTimeSustained (ms)'] - start_time_ms
            results_df.loc[idx, 'TimeToSustain (min)'] = duration_ms / 60000.0
        else:
            results_df.loc[idx, 'TimeToSustain (min)'] = np.nan
    
    results_df.drop(columns=['FirstTimeSustained (ms)'], inplace=True)

    # Add the structural metrics to the dataframe.
    results_df['MeanMaxHeight'] = mean_max_height
    results_df['StdMaxHeight'] = std_max_height
    results_df['MeanForkedBlocks'] = mean_forks
    results_df['StdForkedBlocks'] = std_forks

    logging.info(f"--- Finished processing for directory: {dir_name} ---\n")
    return results_df


# --- Main script execution ---
if __name__ == "__main__":
    all_dirs = [d for d in os.listdir(ROOT_DIR) if os.path.isdir(os.path.join(ROOT_DIR, d))]
    dirs_to_process = [d for d in all_dirs if d not in ['.git', '__pycache__', OUTPUT_DIR_BASE]]

    if not dirs_to_process:
        logging.warning("No data directories found to process.")
    else:
        logging.info(f"Found {len(dirs_to_process)} directories to process: {dirs_to_process}")
        
        all_results_dfs = []
        for directory in dirs_to_process:
            dir_results_df = process_directory(directory)
            if dir_results_df is not None:
                all_results_dfs.append(dir_results_df)
        
        # --- Save Combined Analysis to CSV ---
        if all_results_dfs:
            logging.info("Combining results from all directories and saving to CSV...")
            final_df = pd.concat(all_results_dfs, ignore_index=True)
            
            # Define the final column order for the output CSV.
            final_columns = [
                'Directory', 'Transaction', 't0 (min)', 'MetSustainCondition', 'TimeToSustain (min)',
                'MeanMaxHeight', 'StdMaxHeight', 'MeanForkedBlocks', 'StdForkedBlocks'
            ]
            final_df = final_df[final_columns]
            
            output_csv_path = os.path.join(OUTPUT_DIR_BASE, "confidence_sustain_analysis.csv")
            final_df.to_csv(output_csv_path, index=False, float_format='%.2f')
            logging.info(f"Combined analysis saved successfully to '{output_csv_path}'")
        else:
            logging.warning("No analysis results were generated to save.")
    
    logging.info("--- All processing complete. ---")

