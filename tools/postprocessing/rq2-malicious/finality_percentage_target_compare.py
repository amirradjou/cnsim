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
# The specific transaction ID to generate plots for.
TARGET_TX_ID = "20000"
# The name of the directory to be used as the baseline for comparisons.
BASE_DIR_NAME = "base"
# The list of scenario directories to compare against the base.
SCENARIO_DIRS = ["malicious", "malicious2", "malicious3"]
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
    
    max_heights = df.groupby('SimID')['Height'].max()
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
        
        sim_df.drop_duplicates(subset=['BlockID'], keep='first', inplace=True)
        longest_chain_tip = sim_df.loc[sim_df['Height'].idxmax()]
        block_to_parent = pd.Series(sim_df.ParentBlockID.values, index=sim_df.BlockID).to_dict()
        
        longest_chain_blocks = set()
        current_block_id = longest_chain_tip['BlockID']
        
        while current_block_id in block_to_parent:
            longest_chain_blocks.add(int(current_block_id))
            parent_id = block_to_parent.get(current_block_id)
            if parent_id is None or parent_id == current_block_id:
                break
            current_block_id = parent_id
        longest_chain_blocks.add(int(current_block_id))

        total_unique_blocks = set(sim_df['BlockID'].astype(int))
        forked_block_set = total_unique_blocks - longest_chain_blocks
        fork_counts.append(len(forked_block_set))

    if not fork_counts:
        return np.nan, np.nan

    return np.mean(fork_counts), np.std(fork_counts)


def analyze_confidence_sustain(df, threshold):
    """
    Analyzes if each transaction reaches a confidence threshold and sustains it
    based on the AVERAGE confidence across all simulations.
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


def analyze_finality_per_simulation(df, threshold):
    """
    Calculates the number of simulations in which each transaction reached finality.
    """
    if df.empty or 'Transaction' not in df.columns or 'SimID' not in df.columns:
        logging.warning("Finality per simulation analysis skipped: DataFrame is empty or missing required columns.")
        return {}

    finality_results = {}
    transactions = df['Transaction'].unique()

    for tx_id in transactions:
        tx_df = df[df['Transaction'] == tx_id]
        sim_ids = tx_df['SimID'].unique()
        
        sim_finality_count = 0
        total_sims_for_tx = len(sim_ids)

        for sim_id in sim_ids:
            sim_tx_df = tx_df[tx_df['SimID'] == sim_id].sort_values('Time')
            candidate_times = sim_tx_df[sim_tx_df['conf'] >= threshold]['Time']

            finality_achieved_in_sim = False
            for time_val in candidate_times:
                subsequent_df = sim_tx_df[sim_tx_df['Time'] >= time_val]
                if (subsequent_df['conf'] >= threshold).all():
                    finality_achieved_in_sim = True
                    break
            
            if finality_achieved_in_sim:
                sim_finality_count += 1
        
        finality_results[tx_id] = {
            'FinalitySimCount': sim_finality_count,
            'TotalSims': total_sims_for_tx
        }
    
    return finality_results


def process_directory(dir_name):
    """
    Processes a single experiment directory: loads log files and runs all numerical analyses.
    Returns a DataFrame with analysis results and a DataFrame with confidence data for plotting.
    """
    logging.info(f"--- Starting processing for directory: {dir_name} ---")
    
    try:
        sub_path = os.path.join(ROOT_DIR, dir_name)
        potential_folders = [f for f in os.listdir(sub_path) if os.path.isdir(os.path.join(sub_path, f))]
        if not potential_folders:
            logging.error(f"No experiment sub-folder found in '{dir_name}'. Skipping.")
            return None, None
        experiment_name = potential_folders[0]
        logging.info(f"Found experiment folder: {experiment_name}")
    except Exception as e:
        logging.error(f"Could not find a valid experiment sub-folder in '{dir_name}': {e}. Skipping.")
        return None, None

    belief_log_path = os.path.join(sub_path, experiment_name, f"BeliefLog - {experiment_name}.csv")
    event_log_path = os.path.join(sub_path, experiment_name, f"EventLog - {experiment_name}.csv")
    structure_log_path = os.path.join(sub_path, experiment_name, f"StructureLog - {experiment_name}.csv")
    
    try:
        belief_df = pd.read_csv(belief_log_path)
        belief_df.columns = belief_df.columns.str.strip()
        belief_df.rename(columns={"Transaction ID": "Transaction", "Time (ms from start)": "Time"}, inplace=True)
        belief_df["Transaction"] = belief_df["Transaction"].astype(str)
    except FileNotFoundError:
        logging.error(f"BeliefLog not found at {belief_log_path}. Skipping directory '{dir_name}'.")
        return None, None

    try:
        structure_df = pd.read_csv(structure_log_path)
        structure_df.columns = [col.strip() for col in structure_df.columns]
    except FileNotFoundError:
        logging.warning(f"StructureLog not found at {structure_log_path}. Height and fork analysis will be skipped.")
        structure_df = pd.DataFrame()

    mean_max_height, std_max_height = analyze_max_height(structure_df)
    mean_forks, std_forks = analyze_forks(structure_df)
    logging.info(f"Directory '{dir_name}' -> Mean Max Height: {mean_max_height:.2f}, Mean Forks: {mean_forks:.2f}")

    initial_times = {}
    try:
        event_df = pd.read_csv(event_log_path)
        event_df.columns = event_df.columns.str.strip()
        event_df["Object"] = event_df["Object"].astype(str)
        arrivals = event_df[event_df['EventType'] == 'Event_NewTransactionArrival']
        if not arrivals.empty:
            initial_times = arrivals.groupby('Object')['SimTime'].min().to_dict()
    except FileNotFoundError:
        logging.warning(f"EventLog not found at {event_log_path}. Defaulting all t_0 to 0 ms.")
    
    all_transactions = belief_df['Transaction'].unique()
    final_initial_times = {tx: initial_times.get(tx, 0) for tx in all_transactions}

    net_conf = belief_df.groupby(["SimID", "Transaction", "Time"])["Believes"].mean().reset_index(name="conf")
    finality_sim_results = analyze_finality_per_simulation(net_conf, FIXED_BELIEF_THRESHOLD)

    avg_conf = net_conf.groupby(["Time", "Transaction"]).agg(avgConf=('conf', 'mean')).reset_index()
    avg_conf['start_time_ms'] = avg_conf['Transaction'].map(final_initial_times)
    filtered_conf = avg_conf[avg_conf['Time'] >= avg_conf['start_time_ms']].copy()
    
    confidence_results_list = analyze_confidence_sustain(filtered_conf, FIXED_BELIEF_THRESHOLD)
    if not confidence_results_list:
        return None, filtered_conf

    results_df = pd.DataFrame(confidence_results_list)
    results_df['Directory'] = dir_name

    finality_df = pd.DataFrame.from_dict(finality_sim_results, orient='index').reset_index().rename(columns={'index': 'Transaction'})
    if not finality_df.empty:
        results_df = pd.merge(results_df, finality_df, on='Transaction', how='left')
    else:
        results_df['FinalitySimCount'] = np.nan
        results_df['TotalSims'] = np.nan
    
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

    results_df['MeanMaxHeight'] = mean_max_height
    results_df['StdMaxHeight'] = std_max_height
    results_df['MeanForkedBlocks'] = mean_forks
    results_df['StdForkedBlocks'] = std_forks
    results_df['FinalitySimCount'] = results_df['FinalitySimCount'].astype('Int64')
    results_df['TotalSims'] = results_df['TotalSims'].astype('Int64')

    logging.info(f"--- Finished processing for directory: {dir_name} ---\n")
    return results_df, filtered_conf


def generate_comparative_plot(plot_df, base_name, scenario_name, tx_id):
    """
    Generates and saves a plot comparing confidence for a single transaction
    between a base scenario and another scenario, matching a specific style.
    """
    if plot_df.empty:
        logging.warning(f"No data to plot for comparison between {base_name} and {scenario_name}.")
        return

    plot_df['Time (min)'] = plot_df['Time'] / 60000.0
    
    plt.figure(figsize=(15, 8))
    
    # --- Define specific styles to match the example image ---
    # 1. Color Palette: Base is black, Scenario is blue
    color_map = {base_name: 'black', scenario_name: 'blue'}
    
    # 2. Dash Styles: Base is dashed, Scenario is solid
    dashes_map = {base_name: (4, 2), scenario_name: ""}

    # Create the plot using the defined styles and removing markers
    sns.lineplot(
        data=plot_df,
        x='Time (min)',
        y='avgConf',
        hue='Scenario',
        style='Scenario',
        palette=color_map,
        dashes=dashes_map,
        linewidth=2
    )
    
    plt.axhline(y=FIXED_BELIEF_THRESHOLD, color='r', linestyle='--', label=f'{int(FIXED_BELIEF_THRESHOLD*100)}% Belief Threshold')
    plt.title(f'Confidence Comparison for Transaction {tx_id}\n({base_name} vs. {scenario_name})', fontsize=16)
    plt.xlabel('Simulation Time (min)', fontsize=12)
    plt.ylabel('Average Confidence', fontsize=12)
    plt.ylim(-0.05, 1.05)
    plt.grid(True, which='both', linestyle='--', linewidth=0.5)
    plt.legend(title='Scenario')
    plt.tight_layout()

    comparison_output_dir = os.path.join(OUTPUT_DIR_BASE, f"comparison_{base_name}_vs_{scenario_name}")
    os.makedirs(comparison_output_dir, exist_ok=True)
    plot_path = os.path.join(comparison_output_dir, f"confidence_plot_tx_{tx_id}.png")
    
    plt.savefig(plot_path)
    plt.close()
    logging.info(f"Comparative plot saved to '{plot_path}'")


# --- Main script execution ---
if __name__ == "__main__":
    all_available_dirs = [d for d in os.listdir(ROOT_DIR) if os.path.isdir(os.path.join(ROOT_DIR, d))]
    
    if BASE_DIR_NAME not in all_available_dirs:
        logging.error(f"Base directory '{BASE_DIR_NAME}' not found. Cannot proceed with comparison.")
        exit()

    scenarios_to_process = [d for d in SCENARIO_DIRS if d in all_available_dirs]
    if not scenarios_to_process:
        logging.warning("No scenario directories found to compare against the base.")

    all_results_dfs = []
    
    # --- Process Base Directory First ---
    logging.info(f"Processing base directory '{BASE_DIR_NAME}' to establish baseline...")
    base_results_df, base_conf_df = process_directory(BASE_DIR_NAME)
    
    if base_results_df is None or base_conf_df is None:
        logging.error(f"Failed to process base directory '{BASE_DIR_NAME}'. Aborting.")
        exit()
    
    all_results_dfs.append(base_results_df)
    base_plot_data = base_conf_df[base_conf_df['Transaction'] == TARGET_TX_ID].copy()
    base_plot_data['Scenario'] = BASE_DIR_NAME

    # --- Process Scenario Directories and Create Comparative Plots ---
    for scenario_name in scenarios_to_process:
        logging.info(f"Processing scenario '{scenario_name}' for comparison...")
        scenario_results_df, scenario_conf_df = process_directory(scenario_name)
        
        if scenario_results_df is not None:
            all_results_dfs.append(scenario_results_df)

        if scenario_conf_df is not None:
            scenario_plot_data = scenario_conf_df[scenario_conf_df['Transaction'] == TARGET_TX_ID].copy()
            scenario_plot_data['Scenario'] = scenario_name
            
            combined_plot_df = pd.concat([base_plot_data, scenario_plot_data], ignore_index=True)
            
            generate_comparative_plot(combined_plot_df, BASE_DIR_NAME, scenario_name, TARGET_TX_ID)
        else:
            logging.warning(f"Could not retrieve confidence data for '{scenario_name}'. Skipping plot generation for it.")

    # --- Save Combined Numerical Analysis to CSV ---
    if all_results_dfs:
        logging.info("Combining numerical results from all directories and saving to CSV...")
        final_df = pd.concat(all_results_dfs, ignore_index=True)
        
        final_columns = [
            'Directory', 'Transaction', 't0 (min)', 'MetSustainCondition', 
            'FinalitySimCount', 'TotalSims', 'TimeToSustain (min)',
            'MeanMaxHeight', 'StdMaxHeight', 'MeanForkedBlocks', 'StdForkedBlocks'
        ]
        final_df = final_df.reindex(columns=final_columns)
        
        output_csv_path = os.path.join(OUTPUT_DIR_BASE, "comparative_analysis.csv")
        final_df.to_csv(output_csv_path, index=False, float_format='%.2f')
        logging.info(f"Combined analysis saved successfully to '{output_csv_path}'")
    else:
        logging.warning("No analysis results were generated to save.")
    
    logging.info("--- All processing complete. ---")
