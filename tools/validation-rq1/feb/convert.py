import pandas as pd

# Define the input and output filenames
input_csv_path = 'aggregated_blocks.csv' # 📄 Change this to your file's name
output_csv_path = 'aggregated_converted.csv'

# Read the CSV file into a pandas DataFrame
try:
    df = pd.read_csv(input_csv_path)

    # Convert the 'time_to_mine' column from a string to a Timedelta object
    # The .dt accessor is used to apply datetime-like properties to the series
    timedelta_series = pd.to_timedelta(df['time_to_mine'])

    # Create a new column 'time_in_minutes' by converting the Timedelta to total seconds and dividing by 60
    df['time_in_minutes'] = timedelta_series.dt.total_seconds() / 60

    # Display the first few rows of the updated DataFrame
    print("Conversion successful! Here's a preview of the data:")
    print(df.head())

    # Save the updated DataFrame to a new CSV file
    df.to_csv(output_csv_path, index=False)
    print(f"\n✅ Successfully saved the converted data to {output_csv_path}")

except FileNotFoundError:
    print(f"❌ Error: The file '{input_csv_path}' was not found.")
except KeyError:
    print("❌ Error: A column named 'time_to_mine' was not found in the CSV.")
