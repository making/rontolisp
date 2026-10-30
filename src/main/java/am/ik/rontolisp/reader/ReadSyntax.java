package am.ik.rontolisp.reader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;

/**
 * A readtable as the lexer reads with it ({@code .kb/readtables.md}): the case a token's
 * unescaped letters are converted by, and the characters whose syntax differs from the
 * standard one, with the evaluator that calls a reader macro function. The interpreter
 * builds one per read from the current {@code *readtable*}, and the compile path's
 * read-time image from the one its compile-time evaluator holds
 * ({@code eval/ReadtableSyntax}, {@code eval/ReadTimeImage}); a read without one -- under
 * the standard readtable -- reads the standard syntax.
 */
public final class ReadSyntax {

	/** The {@code readtable-case} a token's unescaped letters are converted by. */
	public enum Case {

		/** Every unescaped letter upper case: the standard readtable's. */
		UPCASE,
		/** Every unescaped letter lower case. */
		DOWNCASE,
		/** Letters kept as written. */
		PRESERVE,
		/**
		 * A token whose unescaped letters are all one case is inverted, any other one
		 * kept.
		 */
		INVERT

	}

	/** What a character is, where the readtable differs from the standard syntax. */
	public sealed interface Entry permits Whitespace, Constituent, Standard, Call, Sharp {

		/**
		 * Whether the character continues a token it appears in: a constituent and a
		 * non-terminating macro character do, whitespace and a terminating macro
		 * character end it.
		 * @return true when it continues the token
		 */
		boolean continuesToken();

	}

	/** A whitespace character. */
	public record Whitespace() implements Entry {

		@Override
		public boolean continuesToken() {
			return false;
		}

	}

	/** A constituent character: part of the token it appears in. */
	public record Constituent() implements Entry {

		@Override
		public boolean continuesToken() {
			return true;
		}

	}

	/**
	 * A standard macro character's own function installed under this character
	 * ({@code (set-syntax-from-char #\} #\))}): it reads as {@code as} does.
	 *
	 * @param as the standard macro character whose function this is
	 */
	public record Standard(int as) implements Entry {

		@Override
		public boolean continuesToken() {
			return this.as == '#';
		}

	}

	/**
	 * A reader macro function the evaluator calls -- a user one, or the dispatching
	 * function of a dispatch character other than {@code #}.
	 *
	 * @param function the function (or function designator)
	 * @param nonTerminating whether the character is non-terminating
	 */
	public record Call(LispVal function, boolean nonTerminating) implements Entry {

		@Override
		public boolean continuesToken() {
			return this.nonTerminating;
		}

	}

	/**
	 * The dispatch character {@code #} with a table of its own: a sub-character the table
	 * holds a function for is called, one absent from it -- or holding its own standard
	 * function -- reads as standard.
	 *
	 * @param subs the sub-characters, upper-cased, to their entries
	 */
	public record Sharp(Map<Integer, Sub> subs) implements Entry {

		@Override
		public boolean continuesToken() {
			return true;
		}

	}

	/**
	 * One sub-character of {@link Sharp}.
	 *
	 * @param function the function to call, or {@code null} for a standard one
	 * @param standardAs the standard sub-character whose function this is, or -1 for a
	 * user function
	 */
	public record Sub(@Nullable LispVal function, int standardAs) {
	}

	/** Calls a reader macro function over the input being read. */
	@FunctionalInterface
	public interface Invoker {

		/**
		 * Calls {@code function} with a stream over {@code input} positioned at
		 * {@code offset}, followed by {@code arguments}.
		 * @param function the reader macro function
		 * @param input the text being read
		 * @param offset the UTF-16 offset the function reads from
		 * @param arguments the arguments after the stream (the character; or the
		 * sub-character and the numeric argument)
		 * @return the value the function answered, and where it left the input
		 */
		Result call(LispVal function, String input, int offset, List<LispVal> arguments);

	}

	/**
	 * What a reader macro function did.
	 *
	 * @param value the value it answered, or {@code null} when it answered none (a
	 * comment)
	 * @param end the UTF-16 offset it left the input at
	 */
	public record Result(@Nullable LispVal value, int end) {
	}

	/**
	 * Thrown by an {@link Invoker} that refuses a call -- the compile path's, for a value
	 * no compiled program can carry as a literal or a function that signalled: the reader
	 * reports it as a read error at the macro character.
	 */
	public static final class Refusal extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/**
		 * A refusal.
		 * @param message what was refused
		 */
		public Refusal(String message) {
			super(message);
		}

		/**
		 * A refusal of a call that failed.
		 * @param message what was refused
		 * @param cause the failure
		 */
		public Refusal(String message, Throwable cause) {
			super(message, cause);
		}

	}

	private final Case caseMode;

	private final @Nullable Entry[] ascii = new Entry[128];

	private final Map<Integer, Entry> others = new HashMap<>();

	private final Invoker invoker;

	/**
	 * A syntax.
	 * @param caseMode the case conversion
	 * @param entries the characters whose syntax differs from the standard one
	 * @param invoker calls a reader macro function
	 */
	public ReadSyntax(Case caseMode, Map<Integer, Entry> entries, Invoker invoker) {
		this.caseMode = caseMode;
		this.invoker = invoker;
		for (Map.Entry<Integer, Entry> entry : entries.entrySet()) {
			int c = entry.getKey();
			if (c >= 0 && c < this.ascii.length) {
				this.ascii[c] = entry.getValue();
			}
			else {
				this.others.put(c, entry.getValue());
			}
		}
	}

	/**
	 * The case conversion.
	 * @return the case
	 */
	public Case caseMode() {
		return this.caseMode;
	}

	/**
	 * The entry of a character whose syntax differs from the standard one.
	 * @param c the code point
	 * @return the entry, or {@code null} when the character reads as standard
	 */
	public @Nullable Entry entry(int c) {
		if (c >= 0 && c < this.ascii.length) {
			return this.ascii[c];
		}
		return this.others.isEmpty() ? null : this.others.get(c);
	}

	/**
	 * Calls a reader macro function.
	 * @param function the function
	 * @param input the text being read
	 * @param offset the offset it reads from
	 * @param arguments the arguments after the stream
	 * @return its value and where it left the input
	 */
	Result call(LispVal function, String input, int offset, List<LispVal> arguments) {
		return this.invoker.call(function, input, offset, arguments);
	}

	/**
	 * Converts the case of a token's text by this syntax's case.
	 * @param text the token's characters
	 * @param escaped which of them were escaped (kept as written), or {@code null} for
	 * none
	 * @return the converted text
	 */
	String convertCase(String text, java.util.@Nullable BitSet escaped) {
		return convertCase(this.caseMode, text, escaped);
	}

	/**
	 * Converts the case of a token's text: CLHS 23.1.2's four modes over the UNESCAPED
	 * characters, an escaped one always kept as written.
	 * @param mode the case
	 * @param text the token's characters
	 * @param escaped which of them were escaped, or {@code null} for none
	 * @return the converted text
	 */
	static String convertCase(Case mode, String text, java.util.@Nullable BitSet escaped) {
		boolean upper = false;
		boolean lower = false;
		if (mode == Case.INVERT) {
			for (int i = 0; i < text.length(); i++) {
				if (escaped != null && escaped.get(i)) {
					continue;
				}
				char c = text.charAt(i);
				upper |= Character.isUpperCase(c);
				lower |= Character.isLowerCase(c);
			}
			if (upper == lower) {
				// Mixed (or no letters): kept as written.
				return text;
			}
		}
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (escaped != null && escaped.get(i)) {
				sb.append(c);
				continue;
			}
			sb.append(switch (mode) {
				case UPCASE -> Character.toUpperCase(c);
				case DOWNCASE -> Character.toLowerCase(c);
				case PRESERVE -> c;
				case INVERT -> upper ? Character.toLowerCase(c) : Character.toUpperCase(c);
			});
		}
		return sb.toString();
	}

}
