import pandas as pd

# Load Data
experiment = "2025.06.10 12.52.41"
folder = "malicious3"
file_path = f"../../thesis-log/{folder}/{experiment}/StructureLog - {experiment}.csv"

# Read CSV
data = pd.read_csv(file_path)
data.columns = data.columns.str.strip()

# Check if required columns exists
required_columns = {'SimID', 'Height'}
if not required_columns.issubset(data.columns):
    raise ValueError(f"Missing required columns: {required_columns - set(data.columns)}")

# Find maximum Height for each SimID
max_heights = data.groupby('SimID')['Height'].max().reset_index()

# Compute statistical metrics
mean_max_height = max_heights['Height'].mean()
std_max_height = max_heights['Height'].std()

# Display results
print(max_heights)
print(f"Mean of max heights across all SimIDs: {mean_max_height}")
print(f"Standard deviation of max heights across all SimIDs: {std_max_height}")

# Save to CSV
output_path = f"../../thesis-log/{folder}/{experiment}/MaxHeights_{experiment}.csv"
max_heights.to_csv(output_path, index=False)

print(f"Results saved to {output_path}")
