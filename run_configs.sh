#!/bin/bash

# Define an array of your configuration file paths
CONFIG_FILES=(
    "./src/main/resources/new-config/thesis.bitcoin.malicious.first.properties"
    "./src/main/resources/new-config/thesis.bitcoin.malicious.second.properties"
    "./src/main/resources/new-config/thesis.bitcoin.malicious.third.properties"
    # Add more config file paths here
    # e.g., "./src/main/resources/new-config/another_config.properties"
)

# Loop through each configuration file
for config_file in "${CONFIG_FILES[@]}"; do
    echo "Running with configuration: $config_file"
    mvn exec:java -Dexec.args="-c $config_file"
    echo "Finished running with $config_file"
    echo "------------------------------------"
done

echo "All configurations have been processed."
