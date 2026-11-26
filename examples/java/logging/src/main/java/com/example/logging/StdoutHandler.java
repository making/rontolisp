package com.example.logging;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

// A handler of the program's: every level, on the standard output.
class StdoutHandler extends Handler {

	StdoutHandler(Formatter formatter) {
		setFormatter(formatter);
		setLevel(Level.ALL);
	}

	@Override
	public void publish(LogRecord record) {
		if (isLoggable(record)) {
			System.out.print(getFormatter().format(record));
		}
	}

	@Override
	public void flush() {
		System.out.flush();
	}

	@Override
	public void close() {
	}

}
