package am.ik.rontolisp.testsupport;

/**
 * Three programs pinned identically on the interpreter, the JVM and both WASM backends:
 * the direction predicates answering a stream's REAL direction, {@code file-position} on
 * a string stream, and {@code with-input-from-string}'s {@code :index} over a character
 * {@code unread-char} parked ({@code .kb/read-load-streams.md}, "String streams").
 *
 * <p>
 * One text per program, because the behavior must not differ between backends and a
 * per-class copy lets one expectation drift. The answers are SBCL's (checked 2026-09-22),
 * except for the {@code t} designator, which is a designator here rather than a stream
 * and answers both directions. The last two lines are the standard stream values over
 * {@code t} a Clojure program's {@code *in*}/{@code *out*} read answers.
 */
public final class StringStreamPrograms {

	private StringStreamPrograms() {
	}

	/**
	 * The direction program; every {@code %p} is the path of a scratch file the program
	 * creates.
	 */
	public static final String DIRECTION_PROGRAM = """
			(defun dir-of (s) (list (input-stream-p s) (output-stream-p s)))
			(print (dir-of (make-string-input-stream "abc")))
			(print (dir-of (make-string-output-stream)))
			(with-input-from-string (s "abc") (print (dir-of s)))
			(print (with-output-to-string (s) (prin1 (dir-of s) s)))
			(with-open-file (o "%p" :direction :output :if-exists :supersede)
			  (print (dir-of o))
			  (write-line "hello" o))
			(with-open-file (i "%p") (print (dir-of i)))
			(with-open-file (i "%p" :element-type '(unsigned-byte 8)) (print (dir-of i)))
			(with-open-file (i "%p" :direction :io :if-exists :overwrite) (print (dir-of i)))
			(with-open-file (i "%p" :direction :output :if-exists :append) (print (dir-of i)))
			(with-open-file (i "%p" :direction :output :if-exists :overwrite) (print (dir-of i)))
			(let ((i (open "%p"))) (close i) (print (dir-of i)))
			(defvar *dir-in* (make-string-input-stream "a"))
			(defvar *dir-out* (make-string-output-stream))
			(print (dir-of (make-synonym-stream '*dir-in*)))
			(print (dir-of (make-synonym-stream '*dir-out*)))
			(print (dir-of *error-output*))
			(print (dir-of t))
			(print (dir-of 3))
			(print (dir-of (%obj-new '%stream t :standard-input)))
			(print (dir-of (%obj-new '%stream t :standard-output)))
			""";

	/** What {@link #DIRECTION_PROGRAM} prints. */
	public static final String DIRECTION_EXPECTED = """
			(T NIL)
			(NIL T)
			(T NIL)
			"(NIL T)"
			(NIL T)
			(T NIL)
			(T NIL)
			(T T)
			(NIL T)
			(NIL T)
			(NIL NIL)
			(T NIL)
			(NIL T)
			(NIL T)
			(T T)
			(NIL NIL)
			(T NIL)
			(NIL T)""";

	/**
	 * The direction program over a concrete scratch path.
	 * @param path the scratch file
	 * @return the program text
	 */
	public static String directionProgram(String path) {
		return DIRECTION_PROGRAM.replace("%p", path.replace("\\", "\\\\"));
	}

	/**
	 * {@code file-position} on string streams: an input stream's query counts the
	 * characters consumed from its own start (so a bounded stream starts at 0) and the
	 * set moves the cursor -- to a character index, {@code :start} or {@code :end}, never
	 * past the end; a character {@code unread-char} parked is not consumed; an output
	 * stream counts what was written and cannot be repositioned elsewhere, and
	 * {@code get-output-stream-string} empties it.
	 */
	public static final String POSITION_PROGRAM = """
			(let ((s (make-string-input-stream "abcdef" 1 5)))
			  (print (file-position s))
			  (read-char s)
			  (read-char s)
			  (print (file-position s))
			  (print (file-position s 1))
			  (print (file-position s))
			  (print (read-char s))
			  (print (file-position s :start))
			  (print (read-char s))
			  (print (file-position s 9))
			  (print (file-position s :end))
			  (print (file-position s))
			  (print (read-char s nil :eof)))
			(with-input-from-string (s "héllo")
			  (read-char s)
			  (read-char s)
			  (print (file-position s))
			  (print (file-position s 1))
			  (print (read-char s)))
			(let ((s (make-string-input-stream "xyz")))
			  (read-char s)
			  (read-char s)
			  (unread-char #\\y s)
			  (print (file-position s))
			  (print (read-char s)))
			(let ((s (make-string-output-stream)))
			  (print (file-position s))
			  (write-string "abé" s)
			  (print (file-position s))
			  (print (file-position s 3))
			  (print (file-position s 1))
			  (print (get-output-stream-string s))
			  (print (file-position s)))
			(let ((i nil))
			  (print (list (with-input-from-string (s "abcdef" :index i :start 1) (read-char s) (read-char s)) i)))
			""";

	/** What {@link #POSITION_PROGRAM} prints. */
	public static final String POSITION_EXPECTED = """
			0
			2
			T
			1
			#\\c
			T
			#\\b
			NIL
			T
			4
			:EOF
			2
			T
			#\\é
			1
			#\\y
			0
			3
			T
			NIL
			"abé"
			0
			(#\\c 3)""";

	/**
	 * {@code with-input-from-string}'s {@code :index} in a program that uses
	 * {@code unread-char} and never names {@code file-position}: a character parked on
	 * the stream counts as unread, and the index counts characters from the string's
	 * start. The last form leaves its character parked when the body exits -- the one
	 * pushback cell then still holds it, so nothing after it may unread.
	 */
	public static final String INDEX_PUSHBACK_PROGRAM = """
			(let ((i nil))
			  (print (list (with-input-from-string (s "xhéllo wörld" :index i :start 1 :end 9)
			                 (read-char s) (read-char s) (unread-char (read-char s) s) (read-char s))
			               i)))
			(let ((i nil))
			  (print (list (with-input-from-string (s "αβγδ" :index i :start 1 :end 3) (read-char s)) i)))
			(let ((i nil))
			  (with-input-from-string (s "abc" :index i) (read-char s) (unread-char #\\a s) (read-char s) (read-char s))
			  (print i))
			(let ((i 0))
			  (with-input-from-string (s "123  " :index i)
			    (read-char s) (read-char s) (read-char s)
			    (unread-char (read-char s) s))
			  (print i))
			""";

	/** What {@link #INDEX_PUSHBACK_PROGRAM} prints (SBCL's answers). */
	public static final String INDEX_PUSHBACK_EXPECTED = """
			(#\\l 4)
			(#\\β 2)
			2
			3""";

}
