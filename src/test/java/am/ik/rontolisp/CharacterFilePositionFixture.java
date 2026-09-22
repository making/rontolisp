package am.ik.rontolisp;

/**
 * The character file stream {@code file-position} program shared by the three backend
 * suites and mirrored by the {@code file-position-of-a-character-file-stream-is-its-byte-
 * offset} ci-spec case. The answers are sbcl's: a character's position advances by its
 * UTF-8 length, a CRLF line ends after its LF, a peeked or un-read character is not yet
 * consumed, and an appending stream starts at the end of the file.
 */
public final class CharacterFilePositionFixture {

	private CharacterFilePositionFixture() {
	}

	/**
	 * The program over one file.
	 * @param file the file's namestring, already escaped for a Lisp string literal
	 * @return the source
	 */
	public static String program(String file) {
		return """
				(with-open-file (out "%1$s" :direction :output :if-exists :supersede)
				  (print (file-position out))
				  (write-char #\\a out)
				  (print (file-position out))
				  (write-string (format nil "~C~C" (code-char 233) (code-char 8364)) out)
				  (print (file-position out))
				  (write-string (format nil "~C~C" #\\Return #\\Newline) out)
				  (print (file-position out))
				  (write-line "xyz" out)
				  (print (file-position out))
				  (print (file-position out 0))
				  (write-char #\\A out)
				  (print (file-position out))
				  (print (file-position out :end))
				  (print (file-position out)))
				(with-open-file (in "%1$s")
				  (print (file-position in))
				  (print (read-char in))
				  (print (file-position in))
				  (print (char-code (peek-char nil in)))
				  (print (file-position in))
				  (print (char-code (read-char in)))
				  (print (file-position in))
				  (print (char-code (char (read-line in) 0)))
				  (print (file-position in))
				  (print (file-position in 1))
				  (print (char-code (read-char in)))
				  (print (file-position in))
				  (let ((buf (make-string 2)))
				    (print (read-sequence buf in))
				    (print (file-position in)))
				  (print (file-position in :end))
				  (print (read-line in nil :eof))
				  (print (file-position in)))
				(with-open-file (app "%1$s" :direction :output :if-exists :append)
				  (print (file-position app))
				  (write-char #\\q app)
				  (print (file-position app)))
				(with-open-file (in "%1$s")
				  (read-char in)
				  (let ((c (read-char in)))
				    (unread-char c in)
				    (print (file-position in))
				    (print (char-code (read-char in)))
				    (print (file-position in))
				    (unread-char c in)
				    (print (file-position in 0))
				    (print (read-char in))))
				""".formatted(file);
	}

	/** What {@link #program} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "0", "1", "6", "8", "12", "T", "1", "T", "12", "0", "#\\A",
			"1", "233", "1", "233", "3", "8364", "8", "T", "233", "3", "2", "7", "T", ":EOF", "12", "12", "13", "1",
			"233", "3", "T", "#\\A");

}
