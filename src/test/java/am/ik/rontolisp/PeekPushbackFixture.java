package am.ik.rontolisp;

/**
 * A {@code peek-char} pushback drained by {@code read-line} and {@code read}, shared by
 * the backend suites and mirrored by the {@code peek-char-pushback-survives-read-line}
 * ci-spec case. The answers are sbcl's: a peeked character opens the next
 * {@code read-line} instead of being dropped (and the line after it ends where the
 * descriptor is), and a peeked datum opener is still there for {@code read}.
 */
public final class PeekPushbackFixture {

	private PeekPushbackFixture() {
	}

	/**
	 * The program over two files.
	 * @param file the line file's namestring, already escaped for a Lisp string literal
	 * @param rdFile the datum file's namestring, already escaped for a Lisp string
	 * literal
	 * @return the source
	 */
	public static String program(String file, String rdFile) {
		return """
				(with-open-file (o "%1$s" :direction :output :if-exists :supersede)
				  (write-string "ab" o)
				  (write-char #\\Return o)
				  (write-char #\\Newline o)
				  (write-string "cd" o)
				  (write-char #\\Newline o)
				  (write-string "ef" o))
				(with-open-file (s "%1$s")
				  (print (read-char s))
				  (print (peek-char nil s))
				  (print (read-line s))
				  (print (file-position s))
				  (print (read-char s))
				  (print (peek-char nil s))
				  (print (read-line s))
				  (print (file-position s))
				  (print (read-line s))
				  (print (read-line s nil :eof)))
				(with-open-file (o "%2$s" :direction :output :if-exists :supersede)
				  (write-string "(a b) (c)" o))
				(with-open-file (s "%2$s")
				  (print (peek-char nil s))
				  (print (read s))
				  (print (read s)))
				""".formatted(file, rdFile);
	}

	/** What {@link #program} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "#\\a", "#\\b", "\"b\"", "4", "#\\c", "#\\d", "\"d\"", "7",
			"\"ef\"", ":EOF", "#\\(", "(A B)", "(C)");

}
