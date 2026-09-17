import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
import logging

# --- Configure logging ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --- Parameters ---
EXPERIMENT_NAME = "2025.07.14 19.49.17" # As per user's last specified experiment
BELIEF_LOG_FILE_PATH = f"../../new-thesis-log-newer/malicious3/{EXPERIMENT_NAME}/BeliefLog - {EXPERIMENT_NAME}.csv"
EVENT_LOG_FILE_PATH = f"../../new-thesis-log-newer/malicious3/{EXPERIMENT_NAME}/EventLog - {EXPERIMENT_NAME}.csv" # New EventLog path
FIXED_BELIEF_THRESHOLD = 0.95

# --- Load and Prepare BeliefLog Data ---
try:
    logging.info(f"Loading BeliefLog data from {EXPERIMENT_NAME}...")
    data_df = pd.read_csv(BELIEF_LOG_FILE_PATH)
    data_df.columns = data_df.columns.str.strip()
    data_df.rename(columns={
        "SimID": "Simulation", "Transaction ID": "Transaction",
        "Time (ms from start)": "Time", "Believes": "Believes"
    }, inplace=True)
    # IMPORTANT: Removed .astype(int) for "Believes" column.
    # The "Believes" column (representing degBelief) should be float as it's an average/proportion.
    data_df["Transaction"] = data_df["Transaction"].astype(str)
    logging.info("BeliefLog data loaded successfully.")

except FileNotFoundError:
    logging.warning(f"BeliefLog File not found at {BELIEF_LOG_FILE_PATH}. Creating dummy BeliefLog data.")
    simulations = range(10)
    times = range(0, 120000, 1000)
    rows = []
    for tx_id_str in ["1", "2", "3"]: # Using strings for Transaction IDs
        for sim in simulations:
            if tx_id_str == '1' and sim > 5: continue
            reached_finality = np.random.rand() > 0.3
            finality_time = np.random.randint(40000, 80000)
            for t in times:
                conf_val = 0.0
                if reached_finality and t >= finality_time: conf_val = 1.0
                elif t > 20000: conf_val = max(0, min(0.95, (t - 20000) / finality_time))
                rows.append({"Simulation": sim, "Time": t, "Believes": conf_val, "Transaction": tx_id_str})
    data_df = pd.DataFrame(rows)

# --- Load EventLog Data and Determine Initial Times (t_0) ---
initial_times_ms_from_eventlog = {}
try:
    logging.info(f"Loading EventLog data from {EXPERIMENT_NAME}...")
    event_log_df = pd.read_csv(EVENT_LOG_FILE_PATH)
    event_log_df.columns = event_log_df.columns.str.strip()
    event_log_df["Object"] = event_log_df["Object"].astype(str)
    logging.info("EventLog data loaded successfully.")

    arrival_events_df = event_log_df[event_log_df['EventType'] == 'Event_NewTransactionArrival']
    if arrival_events_df.empty:
        logging.warning("No 'Event_NewTransactionArrival' events found in EventLog. Cannot determine t_0 automatically.")
    else:
        t0_df = arrival_events_df.groupby('Object')['SimTime'].min().reset_index()
        initial_times_ms_from_eventlog = pd.Series(t0_df.SimTime.values, index=t0_df.Object).to_dict()

except FileNotFoundError:
    logging.warning(f"EventLog File not found at {EVENT_LOG_FILE_PATH}. Cannot determine t_0 automatically. User will be prompted or defaults used.")
except Exception as e:
    logging.error(f"Error processing EventLog: {e}. User will be prompted or defaults used.")


# --- Get Per-Transaction Initial Times (Automated, with Manual Fallback) ---
all_transactions_list = sorted(data_df['Transaction'].unique())

final_initial_times_ms = {} # This will store the t_0 for each transaction in ms
for tx_id in all_transactions_list:
    max_tx_time_belief = data_df[data_df['Transaction'] == tx_id]['Time'].max()

    if tx_id in initial_times_ms_from_eventlog:
        auto_t0_ms = initial_times_ms_from_eventlog[tx_id]
        logging.info(f"Using automated t_0 for Transaction '{tx_id}': {auto_t0_ms} ms (Last event in BeliefLog: {max_tx_time_belief} ms)")
        if not pd.isna(max_tx_time_belief) and auto_t0_ms > max_tx_time_belief:
            logging.warning(f"  Automated t_0 ({auto_t0_ms}ms) for '{tx_id}' is after its last event in BeliefLog ({max_tx_time_belief}ms). This might lead to no data for plots.")
        final_initial_times_ms[tx_id] = auto_t0_ms
    else:
        logging.warning(f"Could not automatically determine t_0 for Transaction '{tx_id}'. Please provide it manually.")
        if pd.isna(max_tx_time_belief):
            logging.warning(f"Transaction '{tx_id}' has no time data in BeliefLog. Assigning default t_0 = 0 ms for analysis, but this may be inaccurate.")
            final_initial_times_ms[tx_id] = 0
            continue
        while True:
            try:
                print(f"\n--- For Transaction: '{tx_id}' (Last recorded event in BeliefLog: {max_tx_time_belief} ms) ---")
                time_input = input(f"Please enter its initial time (t_0) in ms: ")
                manual_t0_ms = int(time_input)
                if not pd.isna(max_tx_time_belief) and manual_t0_ms > max_tx_time_belief:
                     logging.warning(f"Warning: Manual t_0 ({manual_t0_ms}ms) for '{tx_id}' is after the last event in BeliefLog ({max_tx_time_belief}ms). Consider a smaller t_0.")
                final_initial_times_ms[tx_id] = manual_t0_ms
                break
            except (ValueError, EOFError):
                print("Invalid input. Please enter an integer value.")
            except KeyboardInterrupt:
                print("\nExiting script due to user interruption.")
                exit()

# --- Plot 1: Confidence vs. Simulation Time (Lines start at t_0 on absolute time axis) ---
logging.info("Preparing data for Confidence vs. Time plot...")
# net_pre_confidence calculates the mean of "Believes" for each Simulation, Transaction, and Time.
# If "Believes" in the input CSV is already the 'degBelief' (group belief per simulation),
# this mean operation is redundant but harmless as it will return the same value.
net_pre_confidence = data_df.groupby(["Simulation", "Transaction", "Time"])["Believes"].mean().reset_index(name="conf")

# avg_confidence_df_for_plot1 calculates the 'aggDegBelief' (average confidence across simulations)
# for each unique (Time, Transaction) pair.
avg_confidence_df_for_plot1 = net_pre_confidence.groupby(["Time", "Transaction"]).agg(avgConf=('conf', 'mean')).reset_index()

# Map the final determined start times to the confidence DataFrame for Plot 1
start_times_series_plot1 = pd.Series(final_initial_times_ms)
avg_confidence_df_for_plot1['start_time_ms'] = avg_confidence_df_for_plot1['Transaction'].map(start_times_series_plot1)
avg_confidence_df_for_plot1.dropna(subset=['start_time_ms'], inplace=True) # Ensure all tx have a start_time_ms

# Filter data to start plotting from the determined t_0 for each transaction
filtered_avg_confidence_df_plot1 = avg_confidence_df_for_plot1[avg_confidence_df_for_plot1['Time'] >= avg_confidence_df_for_plot1['start_time_ms']].copy()

if not filtered_avg_confidence_df_plot1.empty:
    filtered_avg_confidence_df_plot1['Time (min)'] = filtered_avg_confidence_df_plot1['Time'] / 60000.0

if not filtered_avg_confidence_df_plot1.empty:
    plt.figure(figsize=(14, 8))
    sns.lineplot(data=filtered_avg_confidence_df_plot1, x='Time (min)', y='avgConf', hue='Transaction')
    plt.axhline(y=FIXED_BELIEF_THRESHOLD, color='r', linestyle='--', label=f'{int(FIXED_BELIEF_THRESHOLD*100)}% Belief Threshold')
    plt.title('Plot 1: Average Confidence vs. Simulation Time (Lines start at t_0)')
    plt.xlabel('Simulation Time (min)')
    plt.ylabel('Average Confidence (aggDegBelief)')
    plt.grid(True)
    plt.legend(title='Transaction')
    plt.savefig("confidence_plot_absolute_time_minutes.png")
    plt.show()
    logging.info("Plot 'confidence_plot_absolute_time_minutes.png' saved.")
else:
    logging.warning("No data to plot for confidence after filtering by t_0.")


# --- Plot 2: Finality Score vs. Simulation Time (Lines start at t_0 on absolute time axis) ---
logging.info("Preparing data for Finality vs. Time plot (as per paper's definition)...")
all_finality_plot_data = [] # This will store data for the finality plot

def calculate_finality_score(df_tx_belief, belief_thresh, abs_deadline_ms, start_time_ms_arg):
    """
    Calculates finality score for a given transaction's grouped belief data based on the paper's definition.
    Finality is achieved in a simulation if the belief (confidence) reaches the threshold at a time t1
    (at or after t0) and stays above or equal to the threshold until abs_deadline_ms (th).
    """
    final_simulations = 0
    total_simulations = df_tx_belief['Simulation'].nunique()
    if total_simulations == 0: return 0.0

    for sim_id, sim_data in df_tx_belief.groupby('Simulation'):
        # Filter data points within the current horizon [t0, abs_deadline_ms]
        sim_data_in_horizon = sim_data[sim_data['Time'] <= abs_deadline_ms]

        # Find time points where confidence reaches or exceeds the threshold at or after t0
        reaches_thresh_df = sim_data_in_horizon[
            (sim_data_in_horizon['conf'] >= belief_thresh) &
            (sim_data_in_horizon['Time'] >= start_time_ms_arg)
        ]

        if not reaches_thresh_df.empty:
            # Get the first time (t1) where the threshold is reached
            first_reach_time_ms = reaches_thresh_df['Time'].min()

            # Check if belief STAYS above threshold from first_reach_time_ms (t1) up to abs_deadline_ms (th)
            data_after_reach = sim_data_in_horizon[sim_data_in_horizon['Time'] >= first_reach_time_ms]
            if not data_after_reach.empty and (data_after_reach['conf'] >= belief_thresh).all():
                final_simulations += 1
    return final_simulations / total_simulations

for tx_id, start_t_ms_val in final_initial_times_ms.items():
    if pd.isna(start_t_ms_val):
        logging.warning(f"Skipping finality calculation for '{tx_id}' as its t_0 was not determined.")
        continue

    tx_specific_belief_data = data_df[data_df['Transaction'] == tx_id]
    # group_belief_for_tx extracts the 'degBelief' (confidence per simulation) for the current transaction.
    # Similar to net_pre_confidence, if "Believes" is already degBelief, the mean operation is redundant.
    group_belief_for_tx = tx_specific_belief_data.groupby(["Simulation", "Time"]).agg(conf=("Believes", "mean")).reset_index()

    max_time_for_tx_ms = group_belief_for_tx['Time'].max()

    if pd.isna(max_time_for_tx_ms) or start_t_ms_val >= max_time_for_tx_ms:
        logging.warning(f"Skipping finality plot data for '{tx_id}': t_0 ({start_t_ms_val}ms) is too late or no data (max_time: {max_time_for_tx_ms}ms).")
        continue

    # Absolute simulation time points for the x-axis of the finality plot for this transaction (these are the 'th' values)
    abs_time_points_for_plot_ms = np.linspace(start_t_ms_val, max_time_for_tx_ms, 30).astype(int) # 30 points for smoothness

    for abs_deadline_ms_current_plot_point in abs_time_points_for_plot_ms:
        # Calculate finality score up to this specific abs_deadline_ms_current_plot_point (th)
        score = calculate_finality_score(group_belief_for_tx, FIXED_BELIEF_THRESHOLD, abs_deadline_ms_current_plot_point, start_t_ms_val)
        all_finality_plot_data.append({
            'Transaction': tx_id,
            'Simulation Time (min)': abs_deadline_ms_current_plot_point / 60000.0, # X-axis is absolute simulation time in minutes
            'Finality Score': score
        })

# Create DataFrame for Plot 2
finality_plot_df = pd.DataFrame(all_finality_plot_data) if all_finality_plot_data else pd.DataFrame()

if not finality_plot_df.empty:
    plt.figure(figsize=(14, 8))
    sns.lineplot(data=finality_plot_df, x='Simulation Time (min)', y='Finality Score', hue='Transaction', marker='o')
    plt.title(f'Plot 2: Finality Score vs. Simulation Time (at {int(FIXED_BELIEF_THRESHOLD*100)}% Belief)')
    plt.xlabel('Simulation Time (min)')
    plt.ylabel('Finality Score (Probability)')
    plt.grid(True)
    plt.legend(title='Transaction')
    plt.ylim(-0.05, 1.05)
    plt.savefig("finality_plot_absolute_time_minutes_per_tx_auto_t0.png")
    plt.show()
    logging.info("Plot 'finality_plot_absolute_time_minutes_per_tx_auto_t0.png' saved.")
else:
    logging.warning("No data to plot for finality. Final DataFrame was empty.")


# --- Plot 3: Cumulative Finalization Probability (Finality achieved by t1 and sustained to end of simulation) ---
logging.info("Preparing data for Cumulative Finalization Probability plot...")
all_cumulative_finalization_data = []

def calculate_cumulative_finalization_probability(df_tx_belief, belief_thresh, max_sim_time_ms, start_time_ms_arg, tx_id):
    """
    Calculates the probability that a transaction achieves finality BY a certain time (t_plot)
    AND sustains that belief until the fixed end of the simulation (max_sim_time_ms).
    This metric should be non-decreasing over time.
    """
    results = []
    # Time points for the x-axis, representing 't1' or 'time by which finality is achieved'
    x_axis_times_ms = np.linspace(start_time_ms_arg, max_sim_time_ms, 30).astype(int)

    total_simulations = df_tx_belief['Simulation'].nunique()
    if total_simulations == 0: return results

    for t_plot in x_axis_times_ms:
        sims_final_by_t_plot = 0
        for sim_id, sim_data in df_tx_belief.groupby('Simulation'):
            # Find the first time confidence reaches threshold at or before t_plot, and at or after t0
            potential_reach_times = sim_data[
                (sim_data['conf'] >= belief_thresh) &
                (sim_data['Time'] >= start_time_ms_arg) &
                (sim_data['Time'] <= t_plot) # t1 must be <= t_plot (our current X-axis point)
            ]['Time']

            if not potential_reach_times.empty:
                # Get the earliest time (t1) where the threshold was met for this sim
                first_reach_time_for_this_sim = potential_reach_times.min()

                # Check if belief STAYS above threshold from first_reach_time_for_this_sim
                # all the way to the absolute end of the simulation (max_sim_time_ms)
                check_data_for_sustained_belief = sim_data[
                    (sim_data['Time'] >= first_reach_time_for_this_sim) &
                    (sim_data['Time'] <= max_sim_time_ms) # Fixed ultimate deadline
                ]
                if not check_data_for_sustained_belief.empty and (check_data_for_sustained_belief['conf'] >= belief_thresh).all():
                    sims_final_by_t_plot += 1

        results.append({
            'Transaction': tx_id, # Using the tx_id directly
            'Time to Finalization (min)': t_plot / 60000.0, # X-axis
            'Cumulative Finalization Probability': sims_final_by_t_plot / total_simulations
        })
    return results

for tx_id, start_t_ms_val in final_initial_times_ms.items():
    if pd.isna(start_t_ms_val):
        logging.warning(f"Skipping cumulative finalization calculation for '{tx_id}' as its t_0 was not determined.")
        continue

    tx_specific_belief_data = data_df[data_df['Transaction'] == tx_id]
    group_belief_for_tx = tx_specific_belief_data.groupby(["Simulation", "Time"]).agg(conf=("Believes", "mean")).reset_index()

    max_time_for_tx_ms = group_belief_for_tx['Time'].max()

    if pd.isna(max_time_for_tx_ms) or start_t_ms_val >= max_time_for_tx_ms:
        logging.warning(f"Skipping cumulative finalization data for '{tx_id}': t_0 ({start_t_ms_val}ms) is too late or no data (max_time: {max_time_for_tx_ms}ms).")
        continue

    # Calculate data for the new plot
    cumulative_data = calculate_cumulative_finalization_probability(
        group_belief_for_tx, FIXED_BELIEF_THRESHOLD, max_time_for_tx_ms, start_t_ms_val, tx_id
    )
    all_cumulative_finalization_data.extend(cumulative_data)

# Create DataFrame for Plot 3
cumulative_finalization_df = pd.DataFrame(all_cumulative_finalization_data) if all_cumulative_finalization_data else pd.DataFrame()

if not cumulative_finalization_df.empty:
    plt.figure(figsize=(14, 8))
    sns.lineplot(data=cumulative_finalization_df, x='Time to Finalization (min)', y='Cumulative Finalization Probability', hue='Transaction', marker='o')
    plt.title(f'Plot 3: Cumulative Finalization Probability (Belief >={int(FIXED_BELIEF_THRESHOLD*100)}% until Simulation End)')
    plt.xlabel('Time to Finalization (min)')
    plt.ylabel('Cumulative Finalization Probability')
    plt.grid(True)
    plt.legend(title='Transaction')
    plt.ylim(-0.05, 1.05)
    plt.savefig("cumulative_finalization_probability_plot.png")
    plt.show()
    logging.info("Plot 'cumulative_finalization_probability_plot.png' saved.")
else:
    logging.warning("No data to plot for cumulative finalization probability. Final DataFrame was empty.")


# --- Textual Data Analysis Summary ---
analysis_output_lines = []
analysis_output_lines.append("\n\n--- Data Analysis Summary ---")

for tx_id_analysis in sorted(final_initial_times_ms.keys()):
    t0_ms_current = final_initial_times_ms.get(tx_id_analysis)
    if pd.isna(t0_ms_current):
        analysis_output_lines.append(f"\nTransaction: {tx_id_analysis}\n  - Initial time (t_0) was not determined. Cannot perform detailed analysis.")
        continue

    t0_min_current = t0_ms_current / 60000.0
    analysis_output_lines.append(f"\nTransaction: {tx_id_analysis} (t_0 = {t0_min_current:.2f} min from simulation start)")

    # 1. Max Finality Score (from Plot 2 logic)
    tx_finality_data_for_analysis = finality_plot_df[finality_plot_df['Transaction'] == tx_id_analysis] if not finality_plot_df.empty else pd.DataFrame()
    if not tx_finality_data_for_analysis.empty:
        max_finality_score_val = tx_finality_data_for_analysis['Finality Score'].max()
        analysis_output_lines.append(f"  - Maximum Finality Score (Plot 2) Achieved: {max_finality_score_val*100:.1f}%")

        # 2. Time to Reach 100% Finality Score (from Plot 2 logic)
        if max_finality_score_val >= 1.0: # Use >= 1.0 to catch potential float precision issues
            first_time_100_finality_abs_min = tx_finality_data_for_analysis[tx_finality_data_for_analysis['Finality Score'] >= 0.99999]['Simulation Time (min)'].min() # Using 0.99999 for float comparison
            if not pd.isna(first_time_100_finality_abs_min):
                time_to_100_finality_duration_min = first_time_100_finality_abs_min - t0_min_current
                analysis_output_lines.append(f"  - Time to Reach 100% Finality (Plot 2, after t_0): {time_to_100_finality_duration_min:.2f} minutes")
            else:
                analysis_output_lines.append(f"  - Reached 100% Finality Score (Plot 2), but couldn't pinpoint exact time (check data).")
        else:
            analysis_output_lines.append(f"  - Did not reach 100% Finality Score (Plot 2) within the observed window.")
    else:
        analysis_output_lines.append(f"  - No Finality Score (Plot 2) data available for analysis.")

    # 3. Time for Average Confidence to First Reach Threshold (from Plot 1 logic)
    tx_confidence_data_for_analysis = filtered_avg_confidence_df_plot1[filtered_avg_confidence_df_plot1['Transaction'] == tx_id_analysis] if not filtered_avg_confidence_df_plot1.empty else pd.DataFrame()
    if not tx_confidence_data_for_analysis.empty:
        avg_conf_reaches_threshold_data = tx_confidence_data_for_analysis[tx_confidence_data_for_analysis['avgConf'] >= FIXED_BELIEF_THRESHOLD]
        if not avg_conf_reaches_threshold_data.empty:
            first_time_avg_conf_reach_abs_min = avg_conf_reaches_threshold_data['Time (min)'].min()
            time_to_avg_conf_reach_duration_min = first_time_avg_conf_reach_abs_min - t0_min_current
            analysis_output_lines.append(f"  - Time for Average Confidence (Plot 1) to Reach {FIXED_BELIEF_THRESHOLD*100:.0f}% (after t_0): {time_to_avg_conf_reach_duration_min:.2f} minutes")
        else:
            analysis_output_lines.append(f"  - Average Confidence (Plot 1) did not reach {FIXED_BELIEF_THRESHOLD*100:.0f}% threshold after t_0.")
    else:
        analysis_output_lines.append(f"  - No confidence data available for analysis.")

    # 4. Analysis for Cumulative Finalization Probability (New Plot 3 logic)
    tx_cumulative_finalization_data_for_analysis = cumulative_finalization_df[cumulative_finalization_df['Transaction'] == tx_id_analysis] if not cumulative_finalization_df.empty else pd.DataFrame()
    if not tx_cumulative_finalization_data_for_analysis.empty:
        max_cumulative_prob_val = tx_cumulative_finalization_data_for_analysis['Cumulative Finalization Probability'].max()
        analysis_output_lines.append(f"  - Max Cumulative Finalization Probability (Plot 3) Achieved: {max_cumulative_prob_val*100:.1f}%")

        if max_cumulative_prob_val >= 1.0:
            first_time_100_cumulative_prob_abs_min = tx_cumulative_finalization_data_for_analysis[
                tx_cumulative_finalization_data_for_analysis['Cumulative Finalization Probability'] >= 0.99999
            ]['Time to Finalization (min)'].min()
            if not pd.isna(first_time_100_cumulative_prob_abs_min):
                time_to_100_cumulative_prob_duration_min = first_time_100_cumulative_prob_abs_min - t0_min_current
                analysis_output_lines.append(f"  - Time to Reach 100% Cumulative Finalization (Plot 3, after t_0): {time_to_100_cumulative_prob_duration_min:.2f} minutes")
            else:
                analysis_output_lines.append(f"  - Reached 100% Cumulative Finalization (Plot 3), but couldn't pinpoint exact time (check data).")
        else:
            analysis_output_lines.append(f"  - Did not reach 100% Cumulative Finalization (Plot 3) within the observed window.")
    else:
        analysis_output_lines.append(f"  - No Cumulative Finalization Probability (Plot 3) data available for analysis.")

# Print all analysis results
for line in analysis_output_lines:
    print(line)
