package ca.yorku.cmg.cnsim.engine;

/**
 * A problem with the run's configuration: a missing or malformed key, an impossible combination
 * of settings, or an input file that cannot be read. The driver reports the message and exits
 * without a stack trace. Extends {@link IllegalArgumentException}, which configuration checks
 * threw before.
 */
public class ConfigException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public ConfigException(String message) {
		super(message);
	}

	public ConfigException(String message, Throwable cause) {
		super(message, cause);
	}
}
