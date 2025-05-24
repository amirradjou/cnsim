library(tidyverse)
library(nptest)

# Define function to check if finality condition is met
calculate_finality <- function(confidence_data, threshold = 0.8) {
  finality_results <- confidence_data %>%
    group_by(Transaction) %>%
    summarise(
      first_reach_time = min(Time[avgConf >= threshold], na.rm = TRUE),
      finality_score = mean(avgConf[Time >= first_reach_time] >= threshold, na.rm = TRUE)
    ) %>%
    replace_na(list(finality_score = 0)) # Replace NA values with 0 if finality was never reached

  return(finality_results)
}

# Load Data
Bootstrap_R = 499
experiment = "2025.02.12 21.54.59"
data <- read_csv(paste0("../../log/", experiment, "/BeliefLog - ", experiment, ".csv"))

# Rename columns for consistency
data <- data %>% rename(Simulation = SimID, Transaction = `Transaction ID`, Time = `Time (ms from start)`)

# Aggregate data over simulations
net_pre <- data %>%
  group_by(Simulation, Transaction, Time) %>%
  summarise(conf = mean(Believes), .groups = 'drop')

# Determine the end time of the simulation
endTime <- min(net_pre %>% group_by(Simulation) %>% summarise(maxTime = max(Time)) %>% pull(maxTime))

# Filter data within the valid time range
net <- net_pre %>% filter(Time <= endTime)

# Aggregate over simulations to compute confidence statistics
confs <- net %>%
  group_by(Time, Transaction) %>%
  summarise(
    avgConf = mean(conf),
    sd = sd(conf),
    medConf = median(conf),
    lwr = boot.lower(conf, mean, Bootstrap_R),
    upr = boot.upper(conf, mean, Bootstrap_R),
    VaR = quantile(conf, 0.05),
    .groups = 'drop'
  )

# Calculate finality scores
finality_results <- calculate_finality(confs)

# Display finality results
print(finality_results)
write_csv(finality_results, paste0("../../log/", experiment, "/FinalityScores_", experiment, ".csv"))

# Plot confidence evolution with finality threshold
confs2plot <- confs %>% mutate(Condition = as.factor(Transaction))

ggplot(data = confs2plot, aes(x = Time, y = avgConf)) +
  geom_line(aes(color = Condition)) +
  geom_ribbon(aes(ymin = lwr, ymax = upr, fill = Condition), alpha = 0.1) +
  geom_hline(yintercept = 0.8, linetype = "dashed", color = "red") +
  ylab("Confidence") +
  ggtitle(label = "Confidence in f over time (mean and 95% CI)") +
  theme_minimal()

View(finality_results)
