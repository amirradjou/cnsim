package ca.yorku.cmg.cnsim.engine.reporter;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One output log, written to disk as lines arrive instead of being kept in memory until the end
 * of the run. The file is opened on the first line (or on {@link #close()} if no line ever
 * came), its header written first; the content is the same as writing the collected lines at
 * the end.
 *
 * @author Amirreza Radjou
 */
public final class LogStream {

	private final String file;
	private final String header;
	private BufferedWriter out;
	private boolean closed;
	private long lines;

	/**
	 * @param file Path of the log file.
	 * @param header First line of the file, or {@code null} for none.
	 */
	public LogStream(String file, String header) {
		this.file = file;
		this.header = header;
	}

	/** Appends a line. */
	public void add(String line) {
		try {
			open();
			out.write(line);
			out.write(System.lineSeparator());
			lines++;
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + file, e);
		}
	}

	/** Writes out and closes the file (creating it, with just the header, if nothing was added). */
	public void close() {
		try {
			open();
			out.close();
			out = null;
			closed = true;
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + file, e);
		}
	}

	/** @return The number of lines added (not counting the header). */
	public long lineCount() {
		return lines;
	}

	public String getFile() {
		return file;
	}

	private void open() throws IOException {
		if (out != null) {
			return;
		}
		// After close(), further lines are appended rather than replacing the file.
		out = new BufferedWriter(new FileWriter(file, closed), 1 << 16);
		if (!closed && header != null) {
			out.write(header);
			out.write(System.lineSeparator());
		}
	}
}
