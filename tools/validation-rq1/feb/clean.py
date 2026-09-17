import pandas as pd
import glob
import os

# --- Script Configuration ---
# File pattern to search for in the current directory
file_pattern = '*.tsv'
# Columns to keep from the original files
columns_to_keep = ['id', 'time']
# Name for the final aggregated file
output_filename = 'aggregated_blocks.csv'
# --------------------------

print("Starting the script...")

# Find all files matching the pattern (e.g., all .tsv files)
file_list = glob.glob(file_pattern)

if not file_list:
    print(f"Error: No files found matching the pattern '{file_pattern}'.")
    print("Please place the script in the same directory as your TSV files.")
else:
    print(f"Found {len(file_list)} files to process.")
    
    # Create a list to hold the data from each file
    dataframes_list = []

    # Loop through all the files found
    for file_path in file_list:
        try:
            # Read only the specified columns from the TSV file
            df = pd.read_csv(file_path, sep='\t', usecols=columns_to_keep)
            dataframes_list.append(df)
            print(f"  - Successfully loaded {os.path.basename(file_path)}")
        except ValueError as e:
            # Handle cases where specified columns are not in the file
            print(f"  - Warning: Could not process {os.path.basename(file_path)}. Check if columns '{', '.join(columns_to_keep)}' exist. Error: {e}")
        except Exception as e:
            print(f"  - An unexpected error occurred with {os.path.basename(file_path)}: {e}")

    # Combine all the data into a single DataFrame if any files were processed
    if dataframes_list:
        print("\nAggregating data...")
        combined_df = pd.concat(dataframes_list, ignore_index=True)

        # --- Data Processing ---
        # 1. Convert the 'time' column from text to a proper datetime format
        combined_df['time'] = pd.to_datetime(combined_df['time'])

        # 2. Sort the entire dataset by time to ensure blocks are in chronological order
        combined_df = combined_df.sort_values(by='time').reset_index(drop=True)

        # 3. Calculate the new 'time_to_mine' column
        # This calculates the difference between the 'time' of a row and the 'time' of the previous row
        combined_df['time_to_mine'] = combined_df['time'].diff()

        # --- Save the Result ---
        combined_df.to_csv(output_filename, index=False)

        print(f"\n✅ Success! All data has been processed and saved to '{output_filename}'.")
        print(f"Total blocks processed: {len(combined_df)}")
    else:
        print("\nNo data was successfully loaded. The output file was not created.")
