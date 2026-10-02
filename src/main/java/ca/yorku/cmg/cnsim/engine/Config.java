package ca.yorku.cmg.cnsim.engine;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

public class Config {
    static Properties prop = new Properties();
    static boolean initialized = false;
    
    /**
     * Loads the configuration from a properties file, replacing any configuration loaded before.
     * @param propFileName Path of the properties file.
     * @throws ConfigException If the file cannot be read.
     */
    public static void init(String propFileName) {
        try (InputStream inputStream = new FileInputStream(propFileName)) {
        	Properties fresh = new Properties();
        	fresh.load(inputStream);
        	prop.clear();
        	prop.putAll(fresh);
        	initialized = true;
        } catch (IOException e) {
            throw new ConfigException("Cannot read configuration file " + propFileName + ": " + e.getMessage(), e);
        }
    }

    public static void chk(String propertyKey) throws Exception {
    	check(propertyKey);
    }

    /**
     * @throws ConfigException If no configuration is loaded or the key is missing.
     */
    public static void check(String propertyKey) {
    	if (!initialized) {
    		throw new ConfigException("No configuration loaded.");
    	} else if (prop.getProperty(propertyKey) == null) {
    		throw new ConfigException("Missing configuration key '" + propertyKey + "'.");
    	}
    }

    public static String getProperty(String propertyKey, boolean returnNull) {
    	if (returnNull) {
    		return prop.getProperty(propertyKey);
    	} else {
    		return getProperty(propertyKey);
    	}
    }

    public static String getProperty(String propertyKey) {
    	check(propertyKey);
    	return prop.getProperty(propertyKey);
    }

    private static ConfigException malformed(String propertyKey, String type) {
    	return new ConfigException("Configuration key '" + propertyKey + "' must be " + type + ", got '"
    			+ prop.getProperty(propertyKey) + "'.");
    }

    public static int getPropertyInt(String propertyKey) {
    	check(propertyKey);
    	try {
    		return Integer.parseInt(prop.getProperty(propertyKey).trim());
    	} catch (NumberFormatException e) {
    		throw malformed(propertyKey, "an integer");
    	}
    }

    public static Long getPropertyLong(String propertyKey) {
    	check(propertyKey);
    	try {
    		return Long.parseLong(prop.getProperty(propertyKey).trim());
    	} catch (NumberFormatException e) {
    		throw malformed(propertyKey, "an integer");
    	}
    }

    /**
     * Returns the float value of an optional property, or {@code defaultValue}
     * when the property is not defined. A defined but malformed value is still
     * a configuration error, like every other typed getter.
     * @param propertyKey The property name.
     * @param defaultValue The value to use when the key is absent.
     * @return The parsed value or the default.
     */
    public static float getPropertyFloat(String propertyKey, float defaultValue) {
    	if (!hasProperty(propertyKey)) {
    		return defaultValue;
    	}
    	return getPropertyFloat(propertyKey);
    }

    public static Float getPropertyFloat(String propertyKey) {
    	check(propertyKey);
    	try {
    		return Float.parseFloat(prop.getProperty(propertyKey).trim());
    	} catch (NumberFormatException e) {
    		throw malformed(propertyKey, "a number");
    	}
    }

    public static Double getPropertyDouble(String propertyKey) {
    	check(propertyKey);
    	try {
    		return Double.parseDouble(prop.getProperty(propertyKey).trim());
    	} catch (NumberFormatException e) {
    		throw malformed(propertyKey, "a number");
    	}
    }

	/**
	 * @throws ConfigException Unless the value is {@code true} or {@code false} (any case). The
	 *         lenient {@link Boolean#parseBoolean} would read a misspelt {@code ture} as false.
	 */
	public static boolean getPropertyBoolean(String propertyKey) {
		check(propertyKey);
		String value = prop.getProperty(propertyKey).trim();
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		if (value.equalsIgnoreCase("false")) {
			return false;
		}
		throw malformed(propertyKey, "true or false");
	}

	public static String getPropertyString(String propertyKey) {
		return(prop.getProperty(propertyKey,null));
	}

	
    /**
     * Takes a string of the form "{ID1, ID2, ...}" and returns a long array with the IDs.   
     * @param input A string of the form "{ID1, ID2, ...}", where ID1, ID2 are transaction IDs.
     * @return A long array of ID1, ID2, ... .  If input string is empty (""), or "{}", or null, 
     * return value is null.
     * @exception Throws exception if input string is malformed i.e., 
     *    (a) missing "{" or "}, 
     *    (b) any IDi is greater than workload.numTransactions TODO
     *    (c) any IDi is not numeric
     */
    public static long[] parseStringToArray(String input) {
        // Remove the curly braces and split the string by commas
    	
    	if (input.equals("")) return (new long[0]);
    	if (input.equals("{}")) return (new long[0]);
    	
    	if (!String.valueOf(input.charAt(input.length()-1)).equals("}")) throw new IllegalArgumentException("Error in configuration file, line with " + input + ": missing closing bracket.");
    	if (!String.valueOf(input.charAt(0)).equals("{")) throw new IllegalArgumentException("Error in configuration file, line with " + input + ": missing opening bracket.");
    	
    	
    	String trimmed = input.substring(1, input.length() - 1);
    	
        String[] parts = trimmed.split(",");

        for (int i = 0; i < parts.length; i++) {
            String element = parts[i];

            if (element == null || element.isEmpty()) {
                throw new IllegalArgumentException("Element at index " + i + " is null or empty.");
            }

            try {
                Integer.parseInt(element.trim()); // Attempt to parse the string as an integer
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid integer found at index " + i + ": '" + element + "'.");
            }
        }
        
        // Create an array to store the integers
        long[] result = new long[parts.length];
        
        // Parse each part to an integer and store it in the array
        for (int i = 0; i < parts.length; i++) {
            result[i] = Long.parseLong(parts[i].trim());
        }

        return result;
    }
	   
    
    public static boolean[] parseStringToBoolean(String input) {
        // Remove the curly braces and split the string by commas
        String trimmed = input.substring(1, input.length() - 1);
        String[] parts = trimmed.split(",");

        // Create an array to store the booleans
        boolean[] result = new boolean[parts.length];

        // Parse each part to a boolean and store it in the array
        for (int i = 0; i < parts.length; i++) {
            result[i] = Boolean.parseBoolean(parts[i].trim());
        }

        return result;
    }
    
    
    public static int[] parseStringToIntArray(String input) {
    	return (Arrays.stream(parseStringToArray(input)).
    			mapToInt(i -> (int) i).toArray());
    }
    

    public static void printProperties() {
        for (Object key: prop.keySet()) {
            System.out.println(key + ": " + prop.getProperty(key.toString()));
        }
    }
    
    public static String printPropertiesToString() {
    	String s = "";
        for (Object key: prop.keySet()) {
            s = s + key + "," + prop.getProperty(key.toString()) + System.lineSeparator();
        }
        return(s);
    }

	public static boolean hasProperty(String s) {
		return prop.containsKey(s);
	}
}