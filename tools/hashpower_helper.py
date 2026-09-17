def calculate_hashpower_by_blocks(total_hashpower, data):
    """
    Calculates the hashpower for each mining pool based on the number of blocks found
    and returns their hashpower and percentage share.
    :param total_hashpower: Total network hashpower in EH/s
    :param data: List of tuples containing (Pool Name, Blocks Found)
    :return: Dictionary with mining pool names and a tuple of (hashpower in GH/s, percentage share)
    """
    hashpower_distribution = {}

    # Calculate the total number of blocks found
    total_blocks_found = sum(pool[1] for pool in data)

    if total_blocks_found == 0:
        return {} # Avoid division by zero

    for pool in data:
        name, blocks_found = pool

        # Calculate the share ratio based on blocks found
        share_ratio = blocks_found / total_blocks_found

        pool_hashpower = total_hashpower * share_ratio * 1e9  # Convert EH/s to GH/s
        pool_percentage = share_ratio * 100  # Convert ratio to percentage

        hashpower_distribution[name] = (round(pool_hashpower, 3), round(pool_percentage, 2))

    return hashpower_distribution

# Example Usage
total_network_hashpower = 763.17  # Example total hashpower in EH/s
data = [
    ("Unknown", 280),
    ("AntPool", 95),
    ("ViaBTC", 73),
    ("F2Pool", 61),
    ("Mara Pool", 25),
    ("Braiins Pool", 9),
    ("SBI Crypto", 8),
    ("BTC.com", 7),
    ("BTC M4", 5),
    ("Poolin", 2),
    ("Ultimus", 1)
]

hashpower_result = calculate_hashpower_by_blocks(total_network_hashpower, data)

# Print the results
for pool, values in hashpower_result.items():
    hashpower, percentage = values
    print(f"{pool}: {hashpower} GH/s ({percentage}%)")