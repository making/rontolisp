package am.ik.rontolisp;

/**
 * The operators whose standard lambda list ends in an argument rontolisp accepts and
 * ignores ({@code macro/IgnoredArgument}) called with it, directly and through their
 * function values, shared by the backend suites: the {@code recursive-p} of
 * {@code peek-char}, {@code read-char}, {@code read-char-no-hang} and {@code read-line}
 * (only a reader macro's recursive read consults it) and the {@code environment} of
 * {@code subtypep}. The argument is still evaluated in its place. The three programs take
 * the three compile-path shapes of a read: the plain lowering, the handle-side pushback a
 * program naming {@code unread-char} is rewritten onto, and the Gray dispatch an instance
 * stream is rewritten onto.
 */
public final class IgnoredArgumentFixture {

	private IgnoredArgumentFixture() {
	}

	/** The plain reads, their end of file and their evaluation order, and subtypep. */
	public static final String PROGRAM = """
			(defvar *rp-log* nil)
			(defun rp (x) (push x *rp-log*) nil)
			(print (with-input-from-string (s (format nil "abc~%de~%f"))
			         (list (peek-char nil s nil :eof nil) (peek-char t s nil :eof t)
			               (read-char s t (rp 0) nil) (read-char-no-hang s t nil nil)
			               (read-line s nil :eof nil) (read-line s t nil (rp 1)))))
			(print (with-input-from-string (s "xy")
			         (list (funcall #'peek-char nil s nil :eof nil) (funcall #'read-char s nil :eof nil)
			               (funcall #'read-char-no-hang s nil :eof nil) (funcall #'read-line s nil :eof nil)
			               (apply #'read-char s nil :eof '(nil)))))
			(print (with-input-from-string (s "")
			         (list (peek-char nil s nil :eof (rp 2)) (peek-char t s nil :eof (rp 3))
			               (read-char s nil :eof (rp 4)) (read-char-no-hang s nil :eof (rp 5))
			               (read-line s nil :eof (rp 6)))))
			(print (list (subtypep 'integer 'number nil) (subtypep 'number 'integer (rp 7))
			             (multiple-value-list (subtypep 'integer 'number nil))
			             (multiple-value-list (subtypep (car (list 'integer)) 'number (rp 8)))))
			(print (reverse *rp-log*))
			(print (let ((order nil))
			         (with-input-from-string (s (format nil "q~%r"))
			           (list (read-char (progn (push 's order) s) (progn (push 'e order) t)
			                            (progn (push 'v order) :eof) (progn (push 'r order) nil))
			                 (read-line (progn (push 's2 order) s) nil (progn (push 'v2 order) :eof)
			                            (progn (push 'r2 order) nil))
			                 (reverse order)))))
			(print (list (handler-case (with-input-from-string (s "") (read-char s t nil t))
			               (end-of-file () :read-char))
			             (handler-case (with-input-from-string (s "") (read-char-no-hang s))
			               (end-of-file () :no-hang))
			             (handler-case (with-input-from-string (s "") (funcall #'read-char-no-hang s))
			               (end-of-file () :no-hang-value))
			             (handler-case (with-input-from-string (s "") (peek-char nil s t nil t))
			               (end-of-file () :peek-char))
			             (handler-case (with-input-from-string (s "") (read-line s t nil t))
			               (end-of-file () :read-line))))
			""";

	/** What {@link #PROGRAM} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(#\\a #\\a #\\a #\\b \"c\" \"de\")",
			"(#\\x #\\x #\\y :EOF :EOF)", "(:EOF :EOF :EOF :EOF :EOF)", "(T NIL (T T) (T T))", "(0 1 2 3 4 5 6 7 8)",
			"(#\\q \"\" (S E V R S2 V2 R2))", "(:READ-CHAR :NO-HANG :NO-HANG-VALUE :PEEK-CHAR :READ-LINE)");

	/** The reads of a program that names {@code unread-char}. */
	public static final String PUSHBACK_PROGRAM = """
			(print (with-input-from-string (s "ab")
			         (let ((c (read-char s nil nil nil)))
			           (unread-char c s)
			           (list (peek-char nil s nil :eof nil) (read-char-no-hang s nil :eof nil)
			                 (read-line s nil :eof nil) (read-char s nil :eof nil)))))
			""";

	/** What {@link #PUSHBACK_PROGRAM} prints. */
	public static final String PUSHBACK_EXPECTED = "(#\\a #\\a \"b\" :EOF)";

	/** The reads of a Gray instance stream. */
	public static final String GRAY_PROGRAM = """
			(defclass rp-source (rontolisp:fundamental-character-input-stream)
			  ((text :initarg :text) (pos :initform 0)))
			(defmethod rontolisp:stream-read-char ((s rp-source))
			  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
			    (if (>= pos (length text))
			        :eof
			        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
			(let ((in (make-instance 'rp-source :text (format nil "ab~%cd"))))
			  (print (list (peek-char nil in nil :eof nil) (read-char in nil :eof nil)
			               (read-char-no-hang in nil :eof nil) (read-line in nil :eof nil)
			               (read-line in nil :eof nil) (read-char in nil :eof nil))))
			""";

	/** What {@link #GRAY_PROGRAM} prints. */
	public static final String GRAY_EXPECTED = "(#\\a #\\a #\\b \"\" \"cd\" :EOF)";

}
