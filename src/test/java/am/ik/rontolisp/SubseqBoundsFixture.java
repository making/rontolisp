package am.ik.rontolisp;

/**
 * {@code subseq}'s bounds check, shared by the backend suites (todo a42): a
 * {@code start}/{@code end} pair with {@code start &lt; 0}, {@code end} past the
 * sequence's length, or {@code start &gt; end} is an error with the SAME text on every
 * backend -- before the fix, the interpreter alone reported it; the JVM raised a raw
 * {@code StringIndexOutOfBoundsException} and wasm silently truncated or padded instead
 * of signalling.
 */
public final class SubseqBoundsFixture {

	private SubseqBoundsFixture() {
	}

	/** The program; a plain {@code handler-case} needs no file or stream setup. */
	public static final String PROGRAM = """
			(print (handler-case (subseq "abc" 2 1) (error (e) (princ-to-string e))))
			(print (handler-case (subseq "abc" 0 5) (error (e) (princ-to-string e))))
			""";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "\"SUBSEQ: invalid bounds 2, 1 for string of length 3\"",
			"\"SUBSEQ: invalid bounds 0, 5 for string of length 3\"");

}
