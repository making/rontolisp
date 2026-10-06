package am.ik.rontolisp;

/**
 * How a Gray stream's methods are called, shared by the backend suites and pinned to
 * SBCL's answers.
 */
public final class GrayStreamCallFixture {

	private GrayStreamCallFixture() {
	}

	/**
	 * A write that spells no bound reaches {@code stream-write-string} with integer
	 * {@code start} and {@code end} -- {@code write-string}, {@code write-line},
	 * {@code format}, the print family -- and {@code write-sequence} of a string reaches
	 * it once with the range.
	 */
	public static final String UNBOUNDED_WRITE_PROGRAM = """
			(defclass gwu-rec (rontolisp:fundamental-character-output-stream)
			  ((gwu-log :initform nil)))
			(defmethod rontolisp:stream-write-string ((gwu-s gwu-rec) gwu-str &optional gwu-start gwu-end)
			  (push (list gwu-str gwu-start gwu-end) (slot-value gwu-s 'gwu-log))
			  gwu-str)
			(defmethod rontolisp:stream-write-char ((gwu-s gwu-rec) gwu-c)
			  (push (list :char gwu-c) (slot-value gwu-s 'gwu-log))
			  gwu-c)
			(defmacro gwu-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f)
			                            `(let ((gwu-o (make-instance 'gwu-rec)))
			                               ,f
			                               (reverse (slot-value gwu-o 'gwu-log))))
			                          forms))))
			(gwu-row (write-string "hello" gwu-o) (write-line "hello" gwu-o) (format gwu-o "hello"))
			(gwu-row (princ "hello" gwu-o) (princ 'gwu-sym gwu-o) (prin1 'gwu-sym gwu-o))
			(gwu-row (write-sequence "hello" gwu-o) (write-sequence "hello" gwu-o :start 1)
			         (write-sequence "hello" gwu-o :end 2))
			""";

	/** What {@link #UNBOUNDED_WRITE_PROGRAM} prints (SBCL's answers). */
	public static final String UNBOUNDED_WRITE_EXPECTED = String.join("\n", "(((\"hello\" 0 5)) ((\"hello\" 0 5) (:CHAR #\\Newline)) ((\"hello\" 0 5)))", "(((\"hello\" 0 5)) ((\"GWU-SYM\" 0 7)) ((\"GWU-SYM\" 0 7)))", "(((\"hello\" 0 5)) ((\"hello\" 1 5)) ((\"hello\" 0 2)))");

	/**
	 * The stream operators as function values ({@code #'op}, a quoted designator,
	 * {@code apply}) handed a Gray stream instance reach its methods as a call does, and
	 * a stream handle still reaches the built-in.
	 */
	public static final String FUNCTION_VALUE_PROGRAM = """
			(defclass gfv-out (rontolisp:fundamental-character-output-stream)
			  ((gfv-log :initform nil)))
			(defmethod rontolisp:stream-write-string ((gfv-s gfv-out) gfv-str &optional (gfv-start 0) gfv-end)
			  (push (subseq gfv-str gfv-start gfv-end) (slot-value gfv-s 'gfv-log))
			  gfv-str)
			(defmethod rontolisp:stream-write-char ((gfv-s gfv-out) gfv-c)
			  (push (string gfv-c) (slot-value gfv-s 'gfv-log))
			  gfv-c)
			(defmethod rontolisp:stream-force-output ((gfv-s gfv-out))
			  (push :forced (slot-value gfv-s 'gfv-log))
			  nil)
			(defmethod rontolisp:stream-finish-output ((gfv-s gfv-out))
			  (push :finished (slot-value gfv-s 'gfv-log))
			  nil)
			(defmethod rontolisp:stream-clear-output ((gfv-s gfv-out))
			  (push :cleared (slot-value gfv-s 'gfv-log))
			  nil)
			(defclass gfv-in (rontolisp:fundamental-character-input-stream)
			  ((gfv-text :initarg :text) (gfv-at :initform 0)))
			(defmethod rontolisp:stream-read-char ((gfv-s gfv-in))
			  (let ((gfv-i (slot-value gfv-s 'gfv-at)) (gfv-t (slot-value gfv-s 'gfv-text)))
			    (if (< gfv-i (length gfv-t))
			        (progn (setf (slot-value gfv-s 'gfv-at) (+ gfv-i 1)) (char gfv-t gfv-i))
			        :eof)))
			(defmethod rontolisp:stream-unread-char ((gfv-s gfv-in) gfv-c)
			  (declare (ignore gfv-c))
			  (decf (slot-value gfv-s 'gfv-at))
			  nil)
			(defmethod rontolisp:stream-listen ((gfv-s gfv-in))
			  (< (slot-value gfv-s 'gfv-at) (length (slot-value gfv-s 'gfv-text))))
			(defmethod rontolisp:stream-file-position ((gfv-s gfv-in))
			  (slot-value gfv-s 'gfv-at))
			(defclass gfv-bytes (rontolisp:fundamental-binary-input-stream) ((gfv-n :initform 0)))
			(defmethod rontolisp:stream-read-byte ((gfv-s gfv-bytes))
			  (if (< (slot-value gfv-s 'gfv-n) 2) (incf (slot-value gfv-s 'gfv-n)) :eof))
			(defun gfv-out (gfv-fn &rest gfv-args)
			  (let ((gfv-o (make-instance 'gfv-out)))
			    (list (apply gfv-fn (subst gfv-o :o gfv-args)) (reverse (slot-value gfv-o 'gfv-log)))))
			(defun gfv-in (gfv-fn &rest gfv-args)
			  (let ((gfv-i (make-instance 'gfv-in :text (format nil "ab~%cd"))))
			    (list (apply gfv-fn (subst gfv-i :i gfv-args)) (funcall #'read-line gfv-i nil :end))))
			(print (list (gfv-out #'write-string "hello" :o) (gfv-out #'write-string "hello" :o :start 1 :end 3)
			             (gfv-out #'write-line "hello" :o) (gfv-out 'write-line "hello" :o :end 2)))
			(print (list (gfv-out #'princ 'abc :o) (gfv-out #'prin1 'abc :o) (gfv-out #'format :o "xy")
			             (gfv-out #'write-sequence "hello" :o :start 3) (gfv-out #'terpri :o)))
			(print (list (second (gfv-out #'fresh-line :o)) (gfv-out #'force-output :o) (gfv-out #'finish-output :o)
			             (gfv-out #'clear-output :o)))
			(print (list (gfv-in #'read-char :i) (gfv-in #'read-char-no-hang :i) (gfv-in #'peek-char nil :i)
			             (gfv-in #'read-line :i) (gfv-in #'listen :i)))
			(print (list (gfv-in (lambda (s) (read-line s) (funcall #'read-line s) (funcall #'read-char s nil :eof)) :i)
			             (gfv-in (lambda (s) (let ((b (make-string 3 :initial-element #\\-))) (list (funcall #'read-sequence b s :start 1) b))) :i)
			             (gfv-in (lambda (s) (read-char s) (funcall #'file-position s)) :i)))
			(print (let ((b (make-instance 'gfv-bytes)))
			         (list (funcall #'read-byte b) (funcall #'read-byte b) (funcall #'read-byte b nil :done))))
			(print (mapcar (lambda (f) (list (funcall f (make-instance 'gfv-out)) (funcall f (make-instance 'gfv-in :text ""))))
			               (list #'input-stream-p #'output-stream-p #'stream-element-type)))
			(print (list (with-output-to-string (s) (funcall #'write-string "plain" s) (funcall #'princ 1 s))
			             (with-input-from-string (s "x") (funcall #'read-char s))))
			""";

	/** What {@link #FUNCTION_VALUE_PROGRAM} prints (SBCL's answers). */
	public static final String FUNCTION_VALUE_EXPECTED = String.join("\n", "((\"hello\" (\"hello\")) (\"hello\" (\"el\")) (\"hello\" (\"hello\" \"", "\")) (\"hello\" (\"he\" \"", "\")))", "((ABC (\"ABC\")) (ABC (\"ABC\")) (NIL (\"xy\")) (\"hello\" (\"lo\")) (NIL (\"", "\")))", "((\"", "\") (NIL (:FORCED)) (NIL (:FINISHED)) (NIL (:CLEARED)))", "((#\\a \"b\") (#\\a \"b\") (#\\a \"ab\") (\"ab\" \"cd\") (T \"ab\"))", "((:EOF :END) ((3 \"-ab\") \"\") (1 \"b\"))", "(1 2 :DONE)", "((NIL T) (T NIL) (CHARACTER CHARACTER))", "(\"plain1\" #\\x)");

}
