package am.ik.rontolisp;

/**
 * {@code read-line}'s second value, missing-newline-p, shared by the backend suites and
 * pinned to SBCL's answers: true when end of file ended the line (and at end of file,
 * beside the eof-value), nil when a newline did -- through a consumer, a function tail, a
 * function value, a string, file or standard input stream, a pushed-back character and a
 * Gray stream's {@code stream-read-line}.
 */
public final class ReadLineValuesFixture {

	private ReadLineValuesFixture() {
	}

	/**
	 * The string-stream and file-stream program.
	 * @param file the file's namestring, already escaped for a Lisp string literal
	 * @return the source
	 */
	public static String program(String file) {
		return """
				(defvar *rlv-s* (make-string-input-stream (format nil "ab~%%cd")))
				(print (multiple-value-list (read-line *rlv-s*)))
				(print (multiple-value-list (read-line *rlv-s*)))
				(print (multiple-value-list (read-line *rlv-s* nil :eof)))
				(print (multiple-value-list (read-line *rlv-s* nil)))
				(with-input-from-string (s (format nil "x~%%y"))
				  (print (multiple-value-list (read-line s)))
				  (multiple-value-bind (l m) (read-line s) (print (list l m))))
				(print (with-input-from-string (s "") (multiple-value-list (read-line s nil :e))))
				(print (with-input-from-string (s (format nil "~%%")) (multiple-value-list (read-line s nil :e))))
				(print (with-input-from-string (s "q") (nth-value 1 (read-line s nil :e))))
				(defun rlv-read-one (s) (read-line s nil nil))
				(print (with-input-from-string (s "w") (multiple-value-list (rlv-read-one s))))
				(print (with-input-from-string (s (format nil "v~%%")) (multiple-value-list (rlv-read-one s))))
				(print (with-input-from-string (s "w") (multiple-value-list (funcall #'read-line s))))
				(print (with-input-from-string (s "u") (multiple-value-list (funcall #'read-line s nil :x))))
				(print (with-input-from-string (s "") (multiple-value-list (funcall #'read-line s nil :x))))
				(print (handler-case (multiple-value-list (read-line (make-string-input-stream "") t))
				         (end-of-file () :signalled)))
				(let ((e nil))
				  (print (with-input-from-string (s "") (multiple-value-list (read-line s e :c)))))
				(with-input-from-string (s (format nil "l1~%%l2~%%l3"))
				  (loop (multiple-value-bind (line missing) (read-line s nil nil)
				          (unless line (return))
				          (print (list line missing)))))
				(print (let ((*standard-input* (make-string-input-stream "y")))
				         (multiple-value-list (read-line))))
				(print (let ((x (read-line (make-string-input-stream "k")))) x))
				(with-open-file (o "%1$s" :direction :output :if-exists :supersede)
				  (write-string (format nil "f1~%%f2") o))
				(with-open-file (i "%1$s")
				  (print (multiple-value-list (read-line i)))
				  (print (multiple-value-list (read-line i)))
				  (print (multiple-value-list (read-line i nil :eof))))
				""".formatted(file);
	}

	/** What {@link #program} prints (SBCL's answers). */
	public static final String EXPECTED = String.join("\n", "(\"ab\" NIL)", "(\"cd\" T)", "(:EOF T)", "(NIL T)",
			"(\"x\" NIL)", "(\"y\" T)", "(:E T)", "(\"\" NIL)", "T", "(\"w\" T)", "(\"v\" NIL)", "(\"w\" T)",
			"(\"u\" T)", "(:X T)", ":SIGNALLED", "(:C T)", "(\"l1\" NIL)", "(\"l2\" NIL)", "(\"l3\" T)", "(\"y\" T)",
			"\"k\"", "(\"f1\" NIL)", "(\"f2\" T)", "(:EOF T)");

	/**
	 * The positioned and the bidirectional file stream kinds a program that asks
	 * {@code file-position} / opens {@code :io} reads through.
	 * @param file the file's namestring, already escaped for a Lisp string literal
	 * @return the source
	 */
	public static String fileKindsProgram(String file) {
		return """
				(with-open-file (o "%1$s" :direction :output :if-exists :supersede)
				  (write-string (format nil "f1~%%f2") o))
				(with-open-file (s "%1$s" :direction :io :if-exists :overwrite)
				  (print (multiple-value-list (read-line s)))
				  (print (multiple-value-list (read-line s)))
				  (print (multiple-value-list (read-line s nil :eof))))
				(with-open-file (s "%1$s")
				  (read-line s)
				  (print (list (file-position s) (multiple-value-list (read-line s)))))
				""".formatted(file);
	}

	/** What {@link #fileKindsProgram} prints (SBCL's answers). */
	public static final String FILE_KINDS_EXPECTED = String.join("\n", "(\"f1\" NIL)", "(\"f2\" T)", "(:EOF T)",
			"(3 (\"f2\" T))");

	/**
	 * A program whose only {@code read-line} producer is a function tail (lowered before
	 * any consumer is), and one that can reach a socket -- its stream runtime carries the
	 * socket read too.
	 */
	public static final String TAIL_AND_SOCKET_PROGRAM = """
			(defun rlv-never-called (h) (when h (rontolisp:tcp-connect h 1)))
			(defun rlv-tail (s) (read-line s nil nil))
			(print (multiple-value-list (rlv-tail (make-string-input-stream "z"))))
			(print (multiple-value-list (rlv-tail (make-string-input-stream (format nil "y~%")))))
			""";

	/** What {@link #TAIL_AND_SOCKET_PROGRAM} prints (SBCL's answers). */
	public static final String TAIL_AND_SOCKET_EXPECTED = String.join("\n", "(\"z\" T)", "(\"y\" NIL)");

	/**
	 * A character pushed back with {@code unread-char} opens the next line: a parked
	 * newline ends it there (nil), a parked last character is a line ended by end of
	 * file.
	 */
	public static final String UNREAD_PROGRAM = """
			(with-input-from-string (s "pq")
			  (unread-char (read-char s) s)
			  (print (multiple-value-list (read-line s))))
			(with-input-from-string (s (format nil "~%z"))
			  (unread-char (read-char s) s)
			  (print (multiple-value-list (read-line s)))
			  (print (multiple-value-list (read-line s))))
			(with-input-from-string (s "r")
			  (unread-char (read-char s) s)
			  (print (multiple-value-list (read-line s nil :eof)))
			  (print (multiple-value-list (read-line s nil :eof))))
			(with-input-from-string (s (format nil "ab~%cd"))
			  (unread-char (read-char s) s)
			  (print (multiple-value-list (read-line s)))
			  (print (multiple-value-list (read-line s))))
			(defun rlv-unread-line (s) (read-line s nil :done))
			(with-input-from-string (s "pq")
			  (unread-char (read-char s) s)
			  (print (multiple-value-list (funcall #'read-line s nil :x)))
			  (print (multiple-value-list (funcall #'read-line s nil :x))))
			(with-input-from-string (s (format nil "ab~%c"))
			  (unread-char (read-char s) s)
			  (print (multiple-value-list (rlv-unread-line s)))
			  (unread-char (read-char s) s)
			  (print (multiple-value-list (rlv-unread-line s)))
			  (print (multiple-value-list (rlv-unread-line s))))
			""";

	/** What {@link #UNREAD_PROGRAM} prints (SBCL's answers). */
	public static final String UNREAD_EXPECTED = String.join("\n", "(\"pq\" T)", "(\"\" NIL)", "(\"z\" T)", "(\"r\" T)",
			"(:EOF T)", "(\"ab\" NIL)", "(\"cd\" T)", "(\"pq\" T)", "(:X T)", "(\"ab\" NIL)", "(\"c\" T)", "(:DONE T)");

	/**
	 * A Gray stream: the default {@code stream-read-line} over {@code stream-read-char},
	 * a class's own method answering {@code (values line missing-newline-p)} and ending
	 * the stream with SBCL's {@code ("" t)}, a Gray {@code *standard-input*}, a function
	 * tail and a function value.
	 */
	public static final String GRAY_PROGRAM = """
			(defclass rlv-chars (rontolisp:fundamental-character-input-stream)
			  ((text :initarg :text) (pos :initform 0)))
			(defmethod rontolisp:stream-read-char ((s rlv-chars))
			  (with-slots (text pos) s
			    (if (< pos (length text))
			        (prog1 (char text pos) (incf pos))
			        :eof)))
			(defclass rlv-lines (rontolisp:fundamental-character-input-stream)
			  ((lines :initarg :lines)))
			(defmethod rontolisp:stream-read-char ((s rlv-lines)) :eof)
			(defmethod rontolisp:stream-read-line ((s rlv-lines))
			  (let ((l (pop (slot-value s 'lines))))
			    (if l (values (car l) (cdr l)) (values "" t))))
			(let ((g (make-instance 'rlv-chars :text (format nil "g1~%g2"))))
			  (print (multiple-value-list (read-line g)))
			  (print (multiple-value-list (read-line g)))
			  (print (multiple-value-list (read-line g nil :eof))))
			(let ((g (make-instance 'rlv-chars :text (format nil "h1~%"))))
			  (print (multiple-value-list (read-line g)))
			  (print (multiple-value-list (read-line g nil :eof))))
			(let ((g (make-instance 'rlv-lines :lines '(("m1" . nil) ("m2" . t)))))
			  (print (multiple-value-list (read-line g)))
			  (print (multiple-value-list (read-line g)))
			  (print (multiple-value-list (read-line g nil :eof))))
			(let ((*standard-input* (make-instance 'rlv-chars :text "i1")))
			  (print (multiple-value-list (read-line))))
			(defun rlv-gray-line (g) (read-line g nil :done))
			(let ((g (make-instance 'rlv-chars :text "t1")))
			  (print (multiple-value-list (rlv-gray-line g)))
			  (print (multiple-value-list (rlv-gray-line g))))
			(let ((g (make-instance 'rlv-chars :text "f1")))
			  (print (multiple-value-list (funcall #'read-line g nil :done))))
			""";

	/** What {@link #GRAY_PROGRAM} prints (SBCL's answers, {@code sb-gray}). */
	public static final String GRAY_EXPECTED = String.join("\n", "(\"g1\" NIL)", "(\"g2\" T)", "(:EOF T)",
			"(\"h1\" NIL)", "(:EOF T)", "(\"m1\" NIL)", "(\"m2\" T)", "(:EOF T)", "(\"i1\" T)", "(\"t1\" T)",
			"(:DONE T)", "(\"f1\" T)");

	/**
	 * A class's own {@code stream-read-line} ending the stream with SBCL's {@code ("" t)}
	 * in a program with no multiple-value operator of its own: an empty line in the
	 * middle is still a line.
	 */
	public static final String GRAY_EOF_PROGRAM = """
			(defclass rlv-only-lines (rontolisp:fundamental-character-input-stream)
			  ((lines :initarg :lines)))
			(defmethod rontolisp:stream-read-char ((s rlv-only-lines)) :eof)
			(defmethod rontolisp:stream-read-line ((s rlv-only-lines))
			  (let ((l (pop (slot-value s 'lines))))
			    (if l (values l nil) (values "" t))))
			(let ((g (make-instance 'rlv-only-lines :lines (list "a" "" "b"))))
			  (do ((line (read-line g nil :end) (read-line g nil :end)))
			      ((eq line :end) (print :done))
			    (print line)))
			""";

	/** What {@link #GRAY_EOF_PROGRAM} prints (SBCL's answers, {@code sb-gray}). */
	public static final String GRAY_EOF_EXPECTED = String.join("\n", "\"a\"", "\"\"", "\"b\"", ":DONE");

	/** Lines read from standard input. */
	public static final String STDIN_PROGRAM = """
			(print (multiple-value-list (read-line)))
			(print (multiple-value-list (read-line *standard-input* nil :eof)))
			(print (multiple-value-list (read-line *standard-input* nil :eof)))
			""";

	/** The standard input {@link #STDIN_PROGRAM} reads: its last line has no newline. */
	public static final String STDIN = "s1\ns2";

	/** What {@link #STDIN_PROGRAM} prints over {@link #STDIN} (SBCL's answers). */
	public static final String STDIN_EXPECTED = String.join("\n", "(\"s1\" NIL)", "(\"s2\" T)", "(:EOF T)");

	/**
	 * The lines of {@link #STDIN} read inside an async body -- on the component, the
	 * wit-imported standard input's own line reader.
	 */
	public static final String ASYNC_STDIN_PROGRAM = """
			(rontolisp:async-defun rlv-async-lines ()
			  (print (multiple-value-list (read-line)))
			  (print (multiple-value-list (read-line))))
			(rontolisp:await (rlv-async-lines))
			""";

	/** What {@link #ASYNC_STDIN_PROGRAM} prints over {@link #STDIN} (SBCL's reads). */
	public static final String ASYNC_STDIN_EXPECTED = String.join("\n", "(\"s1\" NIL)", "(\"s2\" T)");

	/**
	 * {@code read-line} with eof arguments after a plain one, inside an async body: both
	 * read the one standard input, so the second does not lose what the first buffered.
	 */
	public static final String ASYNC_STDIN_EOF_LINES_PROGRAM = """
			(rontolisp:async-defun rlv-async-eof-lines ()
			  (print (multiple-value-list (read-line)))
			  (print (multiple-value-list (read-line *standard-input* nil :eof)))
			  (print (multiple-value-list (read-line *standard-input* nil :eof)))
			  (print (handler-case (read-line *standard-input* t)
			           (end-of-file () :signalled))))
			(rontolisp:await (rlv-async-eof-lines))
			""";

	/**
	 * What {@link #ASYNC_STDIN_EOF_LINES_PROGRAM} prints over {@link #STDIN} (SBCL's
	 * reads).
	 */
	public static final String ASYNC_STDIN_EOF_LINES_EXPECTED = String.join("\n", "(\"s1\" NIL)", "(\"s2\" T)",
			"(:EOF T)", ":SIGNALLED");

	/** The same, one character at a time and across a character / line boundary. */
	public static final String ASYNC_STDIN_EOF_CHARS_PROGRAM = """
			(rontolisp:async-defun rlv-async-eof-chars ()
			  (print (read-char))
			  (print (read-char *standard-input* nil :eof))
			  (print (multiple-value-list (read-line)))
			  (print (multiple-value-list (read-line *standard-input* nil :eof)))
			  (print (read-char *standard-input* nil :eof))
			  (print (handler-case (read-char *standard-input* t)
			           (end-of-file () :signalled))))
			(rontolisp:await (rlv-async-eof-chars))
			""";

	/**
	 * What {@link #ASYNC_STDIN_EOF_CHARS_PROGRAM} prints over {@link #STDIN} (SBCL's
	 * reads).
	 */
	public static final String ASYNC_STDIN_EOF_CHARS_EXPECTED = String.join("\n", "#\\s", "#\\1", "(\"\" NIL)",
			"(\"s2\" T)", ":EOF", ":SIGNALLED");

	/** An eof-argument read outside any async body, ahead of an async plain read. */
	public static final String ASYNC_STDIN_EOF_TOP_LEVEL_PROGRAM = """
			(rontolisp:async-defun rlv-async-rest ()
			  (print (multiple-value-list (read-line))))
			(print (multiple-value-list (read-line *standard-input* nil :eof)))
			(rontolisp:await (rlv-async-rest))
			""";

	/**
	 * What {@link #ASYNC_STDIN_EOF_TOP_LEVEL_PROGRAM} prints over {@link #STDIN} (SBCL's
	 * reads).
	 */
	public static final String ASYNC_STDIN_EOF_TOP_LEVEL_EXPECTED = String.join("\n", "(\"s1\" NIL)", "(\"s2\" T)");

}
