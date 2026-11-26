package am.ik.jdbc.spin.postgres;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A statement's text as the host takes it: each {@code ?} of JDBC outside a string, a
 * quoted identifier and a comment is the server's {@code $1}, {@code $2}, ..., and
 * {@code ??} is one question mark (the operators spelled with one). What is a string, an
 * identifier and a comment is the server's lexical structure (PostgreSQL documentation,
 * "Lexical Structure").
 *
 * <p>
 * The host has two calls, one answering rows and one a count, and it converts a parameter
 * by the type the server reads it as, so the text tells two more things: whether the
 * statement answers rows, and the type of each parameter, written as a cast around it.
 */
final class Sql {

	// The text around the parameters: one piece more than there are parameters.
	private final String[] pieces;

	// The statement's first word, in upper case.
	private final String command;

	// The word that says what the statement does: the first one, or after WITH the
	// first of them outside every parenthesis.
	private final String verb;

	// Whether the word RETURNING is in it, outside every quote and parenthesis.
	private final boolean returning;

	// The characters at the end of the last piece that are white space, comments and
	// semicolons: what follows the statement itself.
	private final int trailing;

	private Sql(String[] pieces, String command, String verb, boolean returning, int trailing) {
		this.pieces = pieces;
		this.command = command;
		this.verb = verb;
		this.returning = returning;
		this.trailing = trailing;
	}

	/**
	 * Reads a statement.
	 * @param placeholders whether a question mark is a parameter
	 */
	static Sql parse(String sql, boolean placeholders) {
		List<String> pieces = new ArrayList<>();
		StringBuilder text = new StringBuilder(sql.length() + 8);
		String command = "";
		String verb = "";
		boolean returning = false;
		int trailing = 0;
		int depth = 0;
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
				trailing = 0;
				if (end < length && sql.charAt(end) == '?') {
					text.append('?');
					i = end + 1;
				}
				else {
					pieces.add(text.toString());
					text.setLength(0);
					i = end;
				}
				continue;
			}
			else if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth--;
			}
			else if (Character.isLetter(c) || c == '_') {
				while (end < length && word(sql.charAt(end))) {
					end++;
				}
				String word = sql.substring(i, end).toUpperCase();
				if (command.isEmpty()) {
					command = word;
					verb = word.equals("WITH") ? "" : word;
				}
				else if (depth == 0 && verb.isEmpty() && main(word)) {
					verb = word;
				}
				returning |= depth == 0 && word.equals("RETURNING");
			}
			// White space, a semicolon, or a comment (the one token of more than a
			// character that starts with either).
			trailing = c <= ' ' || c == ';' || (end > i + 1 && (c == '-' || c == '/')) ? trailing + end - i : 0;
			text.append(sql, i, end);
			i = end;
		}
		pieces.add(text.toString());
		return new Sql(pieces.toArray(new String[0]), command, verb, returning, trailing);
	}

	// A word a statement that starts with WITH goes on with after its queries.
	private static boolean main(String word) {
		return word.equals("SELECT") || word.equals("VALUES") || word.equals("TABLE") || writes(word);
	}

	private static boolean writes(String word) {
		return word.equals("INSERT") || word.equals("UPDATE") || word.equals("DELETE") || word.equals("MERGE");
	}

	/** How many parameters it has. */
	int parameters() {
		return this.pieces.length - 1;
	}

	/**
	 * Whether the statement answers rows, as far as its words tell: a query, a command
	 * that shows something, or one that writes rows and returns them.
	 */
	boolean rows() {
		return this.verb.equals("SELECT") || this.verb.equals("VALUES") || this.verb.equals("TABLE")
				|| this.command.equals("SHOW") || this.command.equals("EXPLAIN") || this.command.equals("FETCH")
				|| (writes(this.verb) && this.returning);
	}

	/**
	 * Whether the statement is a query: a SELECT, a VALUES or a TABLE, after WITH too.
	 */
	boolean query() {
		return this.verb.equals("SELECT") || this.verb.equals("VALUES") || this.verb.equals("TABLE");
	}

	/**
	 * A query answering the columns of this one's rows and none of its rows: this one in
	 * parentheses under a condition that is false, which the server plans as no row and
	 * runs nothing of (measured with a volatile function in it). The semicolons, the
	 * comments and the white space after the statement are left out.
	 * @param types as {@link #text(String[])} takes them
	 */
	String describing(@Nullable String @Nullable [] types) {
		String text = text(types);
		return "SELECT * FROM (" + text.substring(0, text.length() - this.trailing) + ") AS described WHERE false";
	}

	/** Whether the statement starts a transaction of the program's own. */
	boolean begins() {
		return this.command.equals("BEGIN") || this.command.equals("START");
	}

	/**
	 * The text with its parameters numbered. A parameter with a type is written as a cast
	 * of it in parentheses, {@code ($1::int4)}: the server then reads the parameter as
	 * that type, as if the type were declared with the statement, wherever it stands.
	 * @param types each parameter's type, {@code null} for one the server is left to tell
	 * from where it stands; {@code null} for a text with no parameter
	 */
	String text(@Nullable String @Nullable [] types) {
		if (this.pieces.length == 1) {
			return this.pieces[0];
		}
		StringBuilder text = new StringBuilder(this.pieces[0]);
		for (int i = 1; i < this.pieces.length; i++) {
			String type = types != null ? types[i - 1] : null;
			if (type == null) {
				text.append('$').append(i);
			}
			else {
				text.append("($").append(i).append("::").append(type).append(')');
			}
			text.append(this.pieces[i]);
		}
		return text.toString();
	}

	/**
	 * The text answering the rows it writes: {@code RETURNING} is added to an INSERT, an
	 * UPDATE, a DELETE or a MERGE that has none.
	 * @param columns the columns to answer, empty for all
	 * @return this text where nothing is added
	 */
	Sql answering(String[] columns) {
		if (!writes(this.verb) || this.returning) {
			return this;
		}
		String last = this.pieces[this.pieces.length - 1];
		StringBuilder text = new StringBuilder(last.substring(0, last.length() - this.trailing)).append(" RETURNING ");
		if (columns.length == 0) {
			text.append('*');
		}
		else {
			for (int i = 0; i < columns.length; i++) {
				text.append(i > 0 ? ", " : "").append(identifier(columns[i]));
			}
		}
		String[] pieces = this.pieces.clone();
		pieces[pieces.length - 1] = text.toString();
		return new Sql(pieces, this.command, this.verb, true, 0);
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
