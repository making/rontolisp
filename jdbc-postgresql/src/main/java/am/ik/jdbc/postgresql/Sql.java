package am.ik.jdbc.postgresql;

/**
 * A statement's text as the server takes it: each {@code ?} of JDBC outside a string, a
 * quoted identifier and a comment is the server's {@code $1}, {@code $2}, ..., and
 * {@code ??} is one question mark (the operators spelled with one). What is a string, an
 * identifier and a comment is the server's lexical structure (PostgreSQL documentation,
 * "Lexical Structure").
 */
final class Sql {

	/** The text with the parameters numbered. */
	final String text;

	/** How many parameters it has. */
	final int parameters;

	// The statement's first word, in upper case.
	private final String command;

	// Whether the word RETURNING is in it, outside every quote.
	private final boolean returning;

	private Sql(String text, int parameters, String command, boolean returning) {
		this.text = text;
		this.parameters = parameters;
		this.command = command;
		this.returning = returning;
	}

	/**
	 * Reads a statement.
	 * @param placeholders whether a question mark is a parameter
	 */
	static Sql parse(String sql, boolean placeholders) {
		StringBuilder text = new StringBuilder(sql.length() + 8);
		int parameters = 0;
		String command = "";
		boolean returning = false;
		int length = sql.length();
		int i = 0;
		while (i < length) {
			char c = sql.charAt(i);
			int end = i + 1;
			if (c == '\'') {
				boolean escapes = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
						&& (i < 2 || !word(sql.charAt(i - 2)));
				end = quoted(sql, i, '\'', escapes);
			}
			else if (c == '"') {
				end = quoted(sql, i, '"', false);
			}
			else if (c == '-' && end < length && sql.charAt(end) == '-') {
				int line = sql.indexOf('\n', i);
				end = line < 0 ? length : line;
			}
			else if (c == '/' && end < length && sql.charAt(end) == '*') {
				end = comment(sql, i);
			}
			else if (c == '$' && (i == 0 || !word(sql.charAt(i - 1)))) {
				end = dollarQuoted(sql, i);
			}
			else if (c == '?' && placeholders) {
				if (end < length && sql.charAt(end) == '?') {
					text.append('?');
				}
				else {
					text.append('$').append(++parameters);
				}
				i = end < length && sql.charAt(end) == '?' ? end + 1 : end;
				continue;
			}
			else if (Character.isLetter(c) || c == '_') {
				while (end < length && word(sql.charAt(end))) {
					end++;
				}
				String word = sql.substring(i, end);
				if (command.isEmpty()) {
					command = word.toUpperCase();
				}
				returning |= word.equalsIgnoreCase("returning");
			}
			text.append(sql, i, end);
			i = end;
		}
		return new Sql(text.toString(), parameters, command, returning);
	}

	/**
	 * The text answering the rows it writes: {@code RETURNING} is added to an INSERT, an
	 * UPDATE, a DELETE or a MERGE that has none.
	 * @param columns the columns to answer, {@code null} for all
	 */
	Sql answering(String[] columns) {
		boolean writes = this.command.equals("INSERT") || this.command.equals("UPDATE") || this.command.equals("DELETE")
				|| this.command.equals("MERGE");
		if (!writes || this.returning) {
			return this;
		}
		int end = this.text.length();
		while (end > 0 && (this.text.charAt(end - 1) == ';' || Character.isWhitespace(this.text.charAt(end - 1)))) {
			end--;
		}
		StringBuilder text = new StringBuilder(this.text.substring(0, end)).append(" RETURNING ");
		if (columns == null || columns.length == 0) {
			text.append('*');
		}
		else {
			for (int i = 0; i < columns.length; i++) {
				text.append(i > 0 ? ", " : "").append(identifier(columns[i]));
			}
		}
		return new Sql(text.toString(), this.parameters, this.command, true);
	}

	/** A name in double quotes, a quote in it doubled. */
	static String identifier(String name) {
		return '"' + name.replace("\"", "\"\"") + '"';
	}

	private static boolean word(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$';
	}

	// The end of a quoted run starting at start: a doubled quote stays inside, and so
	// does what follows a backslash in a string with escapes.
	private static int quoted(String sql, int start, char quote, boolean escapes) {
		int i = start + 1;
		while (i < sql.length()) {
			char c = sql.charAt(i);
			if (c == '\\' && escapes) {
				i += 2;
			}
			else if (c != quote) {
				i++;
			}
			else if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
				i += 2;
			}
			else {
				return i + 1;
			}
		}
		return sql.length();
	}

	// The end of a comment in slash and star, which nests.
	private static int comment(String sql, int start) {
		int depth = 0;
		int i = start;
		while (i + 1 < sql.length()) {
			if (sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
				depth++;
				i += 2;
			}
			else if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
				depth--;
				i += 2;
				if (depth == 0) {
					return i;
				}
			}
			else {
				i++;
			}
		}
		return sql.length();
	}

	// The end of a dollar-quoted string starting at start, or of the dollar sign alone
	// when it starts none: a tag is a name with no dollar sign, between two.
	private static int dollarQuoted(String sql, int start) {
		int i = start + 1;
		while (i < sql.length() && sql.charAt(i) != '$') {
			char c = sql.charAt(i);
			if (!(Character.isLetter(c) || c == '_' || (i > start + 1 && Character.isDigit(c)))) {
				return start + 1;
			}
			i++;
		}
		if (i >= sql.length()) {
			return start + 1;
		}
		String tag = sql.substring(start, i + 1);
		int close = sql.indexOf(tag, i + 1);
		return close < 0 ? sql.length() : close + tag.length();
	}

}
