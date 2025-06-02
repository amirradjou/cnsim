def calculate_hashpower(total_hashpower, data):
    """
    Calculates the hashpower for each mining pool based on their share.
    :param total_hashpower: Total network hashpower in EH/s
    :param data: List of tuples containing (Pool Name, Share Percentage, Blocks Found)
    :return: Dictionary with mining pool names and their hashpower in GH/s
    """
    hashpower_distribution = {}

    for pool in data:
        name, share_percent, blocks_found = pool
        share_ratio = share_percent / 100  # Convert percentage to ratio
        pool_hashpower = total_hashpower * share_ratio * 1e9  # Convert EH/s to GH/s
        hashpower_distribution[name] = round(pool_hashpower, 3)
    return hashpower_distribution

# Example Usage
total_network_hashpower = 788.86  # Example total hashpower in EH/s
data = [
    ("Unknown", 48.116, 281),
    ("AntPool", 16.610, 97),
    ("ViaBTC", 12.671, 74),
    ("F2Pool", 9.760, 57),
    ("Mara Pool", 6.336, 37),
    ("Braiins Pool", 2.055, 12),
    ("SBI Crypto", 2.055, 12),
    ("BTC.com", 0.856, 5),
    ("BTC M4", 0.856, 5),
    ("Poolin", 0.514, 3),
    ("Ultimus", 0.171, 1)
]

hashpower_result = calculate_hashpower(total_network_hashpower, data)

# Print the results
for pool, hashpower in hashpower_result.items():
    print(f"{pool}: {hashpower} GH/s")
