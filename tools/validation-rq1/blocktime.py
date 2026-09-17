import pandas as pd
import os

def analyze_block_times(input_file_path):
    """
    Analyzes block mining times from a CSV file.

    This function reads a CSV file, calculates the time taken to mine each
    block within different simulations, and prints the results and summary
    statistics.

    Args:
        input_file_path (str): The path to the input CSV file.
    """
    try:
        # Check if the input file exists
        if not os.path.exists(input_file_path):
            print(f"Error: The file '{input_file_path}' was not found.")
            return

        # Read the CSV file into a pandas DataFrame
        df = pd.read_csv(input_file_path)

        # --- Clean Column Names ---
        # Remove any leading/trailing whitespace from column names
        df.columns = df.columns.str.strip()

        # --- Prepare Data ---
        # Ensure 'SimTime' is a numeric type for calculations
        df['SimTime'] = pd.to_numeric(df['SimTime'], errors='coerce')
        df.dropna(subset=['SimTime'], inplace=True) # Drop rows where SimTime is not a number

        # Sort the data first by the simulation ID, then by the simulation time.
        # This is crucial for correctly calculating the time difference between blocks.
        df.sort_values(by=['SimID', 'SimTime'], inplace=True)

        # --- Calculate Block Mining Time ---
        # We group by 'SimID' so the calculation is done for each simulation independently.
        # .diff() calculates the difference between the current and previous 'SimTime'.
        # For the first block in each simulation, the difference is NaN (Not a Number).
        # We fill that NaN with the block's own 'SimTime', as that's the time from the start (t=0).
        df['BlockTime_ms'] = df.groupby('SimID')['SimTime'].diff().fillna(df['SimTime'])

        # Convert the block time from milliseconds to minutes
        df['BlockTime_min'] = df['BlockTime_ms'] / (1000 * 60)

        # --- Display Results ---
        # Create a final DataFrame with the columns of interest
        result_df = df[['SimID', 'Object', 'SimTime', 'BlockTime_min']].copy()
        result_df.rename(columns={'Object': 'BlockID'}, inplace=True)


        print("--- Detailed Block Mining Times (in minutes) ---")
        print(result_df.to_string(index=False))
        print("\n" + "="*50 + "\n")


        print("--- Summary Statistics per Simulation ---")
        # Loop through each unique simulation ID to print its stats
        for sim_id in result_df['SimID'].unique():
            sim_data = result_df[result_df['SimID'] == sim_id]
            print(f"\n--- Simulation ID: {sim_id} ---")
            print(f"Total blocks mined: {len(sim_data)}")
            print(f"Average block time: {sim_data['BlockTime_min'].mean():.2f} minutes")
            print(f"Median block time:  {sim_data['BlockTime_min'].median():.2f} minutes")
            print(f"Min block time:     {sim_data['BlockTime_min'].min():.2f} minutes")
            print(f"Max block time:     {sim_data['BlockTime_min'].max():.2f} minutes")


    except Exception as e:
        print(f"An unexpected error occurred: {e}")

# --- How to use the script ---
if __name__ == "__main__":
    # 1. Set the name of your input CSV file
    # This should be the output from the previous filtering script.
    input_csv = 'filtered_container_validation.csv'

    # 2. Run the analysis function
    analyze_block_times(input_csv)

