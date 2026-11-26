package com.example.logging;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;

// One line a record: the level, the class and the method that logged, the message with
// its parameters, and what was thrown.
class LineFormatter extends Formatter {

	@Override
	public String format(LogRecord record) {
		String source = record.getSourceClassName();
		StringBuilder line = new StringBuilder();
		line.append(String.format("%-7s %s.%s: %s", record.getLevel(), source.substring(source.lastIndexOf('.') + 1),
				record.getSourceMethodName(), formatMessage(record)));
		if (record.getThrown() != null) {
			line.append(" (").append(record.getThrown()).append(')');
		}
		return line.append(System.lineSeparator()).toString();
	}

}
