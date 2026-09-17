import pandas as pd
import os

def filter_csv_by_event_type(input_file_path, output_file_path, event_to_keep):
    """
    Filters a CSV file to keep rows with a specific EventType.

    This function reads a CSV file, removes leading/trailing whitespace from
    column names, and filters the data to include only the rows where the
    'EventType' column matches the specified event.

    Args:
        input_file_path (str): The path to the input CSV file.
        output_file_path (str): The path where the filtered CSV will be saved.
        event_to_keep (str): The value to match in the 'EventType' column.
    """
    try:
        # Check if the input file exists
        if not os.path.exists(input_file_path):
            print(f"Error: The file '{input_file_path}' was not found.")
            return

        # Read the CSV file into a pandas DataFrame
        df = pd.read_csv(input_file_path)

        # --- Clean Column Names ---
        # The user mentioned column names might have extra spaces.
        # We'll create a mapping from the original names to stripped names.
        rename_mapping = {col: col.strip() for col in df.columns}
        df.rename(columns=rename_mapping, inplace=True)

        # --- Filter the Data ---
        # Check if the 'EventType' column exists after stripping spaces
        if 'EventType' not in df.columns:
            print("Error: 'EventType' column not found in the CSV file.")
            print(f"Available columns are: {list(df.columns)}")
            return

        # Filter the DataFrame to keep rows where 'EventType' matches the desired value
        filtered_df = df[df['EventType'] == event_to_keep].copy()

        # --- Save the Filtered Data ---
        # Save the filtered DataFrame to a new CSV file.
        # `index=False` prevents pandas from writing the DataFrame index as a column.
        filtered_df.to_csv(output_file_path, index=False)

        print(f"Successfully filtered the data.")
        print(f"Original rows: {len(df)}")
        print(f"Filtered rows: {len(filtered_df)}")
        print(f"Output saved to: '{output_file_path}'")

    except Exception as e:
        print(f"An unexpected error occurred: {e}")

# --- How to use the script ---
if __name__ == "__main__":
    # 1. Set the name of your input CSV file
    input_csv = 'EventLog - 2025.06.18 11.36.16.csv'  # <--- CHANGE THIS to your file name

    # 2. Set the name for your new, filtered CSV file
    output_csv = 'filtered_container_validation.csv'

    # 3. Define the event type you want to keep
    event_type_to_filter = 'Event_ContainerValidation'

    # 4. Run the filtering function
    filter_csv_by_event_type(input_csv, output_csv, event_type_to_filter)

