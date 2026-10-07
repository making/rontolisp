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
	public static final String UNBOUNDED_WRITE_EXPECTED = String.join("\n",
			"(((\"hello\" 0 5)) ((\"hello\" 0 5) (:CHAR #\\Newline)) ((\"hello\" 0 5)))",
			"(((\"hello\" 0 5)) ((\"GWU-SYM\" 0 7)) ((\"GWU-SYM\" 0 7)))",
			"(((\"hello\" 0 5)) ((\"hello\" 1 5)) ((\"hello\" 0 2)))");

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
	public static final String FUNCTION_VALUE_EXPECTED = String.join("\n",
			"((\"hello\" (\"hello\")) (\"hello\" (\"el\")) (\"hello\" (\"hello\" \"", "\")) (\"hello\" (\"he\" \"",
			"\")))", "((ABC (\"ABC\")) (ABC (\"ABC\")) (NIL (\"xy\")) (\"hello\" (\"lo\")) (NIL (\"", "\")))", "((\"",
			"\") (NIL (:FORCED)) (NIL (:FINISHED)) (NIL (:CLEARED)))",
			"((#\\a \"b\") (#\\a \"b\") (#\\a \"ab\") (\"ab\" \"cd\") (T \"ab\"))",
			"((:EOF :END) ((3 \"-ab\") \"\") (1 \"b\"))", "(1 2 :DONE)", "((NIL T) (T NIL) (CHARACTER CHARACTER))",
			"(\"plain1\" #\\x)");

	/**
	 * The default {@code stream-unread-char} parks the character on ITS instance: two
	 * instances each hold one at once, {@code peek-char}'s read-and-unread on one leaves
	 * the other alone, an instance dropped with a character parked leaves no trace, the
	 * parked character is no part of the instance's {@code equal} hash, and it survives a
	 * {@code change-class} into a class with more slots. SBCL's sb-gray has no default
	 * method, so its answers come from a per-instance {@code stream-unread-char} /
	 * {@code :around stream-read-char} pair over the same program.
	 */
	public static final String PER_INSTANCE_PUSHBACK_PROGRAM = """
			(defclass gpb-src (rontolisp:fundamental-character-input-stream)
			  ((gpb-text :initarg :text) (gpb-pos :initform 0)))
			(defmethod rontolisp:stream-read-char ((gpb-s gpb-src))
			  (with-slots (gpb-text gpb-pos) gpb-s
			    (if (< gpb-pos (length gpb-text)) (prog1 (char gpb-text gpb-pos) (incf gpb-pos)) :eof)))
			(defclass gpb-tagged (gpb-src) ((gpb-tag :initform :tagged)))
			(defun gpb-new (gpb-text) (make-instance 'gpb-src :text gpb-text))
			(let ((gpb-a (gpb-new "abc")) (gpb-b (gpb-new "xyz")))
			  (unread-char (read-char gpb-a) gpb-a)
			  (unread-char (read-char gpb-b) gpb-b)
			  (print (list (read-char gpb-a) (read-char gpb-b) (read-char gpb-a))))
			(let ((gpb-a (gpb-new "abc")) (gpb-b (gpb-new "xyz")))
			  (print (list (peek-char nil gpb-a) (peek-char nil gpb-b) (read-char gpb-a) (read-line gpb-b) (read-line gpb-a))))
			(let ((gpb-a (gpb-new "dropped")))
			  (unread-char (read-char gpb-a) gpb-a))
			(let ((gpb-a (gpb-new "pqr")) (gpb-h (make-hash-table :test 'equal)))
			  (let ((gpb-c (read-char gpb-a)))
			    (setf (gethash gpb-a gpb-h) :found)
			    (unread-char gpb-c gpb-a)
			    (print (list (gethash gpb-a gpb-h) (read-char gpb-a) (read-char gpb-a)))))
			(let ((gpb-a (gpb-new "uvw")))
			  (unread-char (read-char gpb-a) gpb-a)
			  (change-class gpb-a 'gpb-tagged)
			  (print (list (read-char gpb-a) (slot-value gpb-a 'gpb-tag) (read-char gpb-a))))
			""";

	/** What {@link #PER_INSTANCE_PUSHBACK_PROGRAM} prints (SBCL's answers). */
	public static final String PER_INSTANCE_PUSHBACK_EXPECTED = String.join("\n", "(#\\a #\\x #\\b)",
			"(#\\a #\\x #\\a \"xyz\" \"bc\")", "(:FOUND #\\p #\\q)", "(#\\u :TAGGED #\\v)");

	/**
	 * In a program that uses the Gray protocol, every character read goes through a Gray
	 * dispatch helper whose fallback hands the built-in the stream: the pushback still
	 * lands on the open stream VALUE, so two string streams hold one each, and a synonym
	 * or {@code *standard-input*} parks on the stream it denotes.
	 */
	public static final String OPEN_STREAM_PUSHBACK_PROGRAM = """
			(defclass gos-src (rontolisp:fundamental-character-input-stream)
			  ((gos-text :initarg :text) (gos-pos :initform 0)))
			(defmethod rontolisp:stream-read-char ((gos-s gos-src))
			  (with-slots (gos-text gos-pos) gos-s
			    (if (< gos-pos (length gos-text)) (prog1 (char gos-text gos-pos) (incf gos-pos)) :eof)))
			(defvar *gos-x* nil)
			(print (read-char (make-instance 'gos-src :text "g")))
			(with-input-from-string (gos-a "12")
			  (with-input-from-string (gos-b "34")
			    (unread-char (read-char gos-a) gos-a)
			    (unread-char (read-char gos-b) gos-b)
			    (print (list (listen gos-a) (peek-char nil gos-a) (read-char gos-a) (read-line gos-b) (read-char gos-a)))))
			(with-input-from-string (gos-s "abc")
			  (setq *gos-x* gos-s)
			  (let ((gos-y (make-synonym-stream '*gos-x*)))
			    (unread-char (read-char gos-y) gos-y)
			    (print (list (file-position gos-s) (read-char gos-s)))))
			(with-input-from-string (gos-s "abc")
			  (let ((*standard-input* gos-s))
			    (unread-char (read-char))
			    (print (read-char gos-s))))
			""";

	/** What {@link #OPEN_STREAM_PUSHBACK_PROGRAM} prints (SBCL's answers). */
	public static final String OPEN_STREAM_PUSHBACK_EXPECTED = String.join("\n", "#\\g", "(T #\\1 #\\1 \"34\" #\\2)",
			"(0 #\\a)", "#\\a");

	/**
	 * A Gray instance bound to {@code *standard-input*} / {@code *standard-output*}
	 * receives the stream-LESS read and print families, an explicit {@code nil} stream, a
	 * stream argument that is nil at run time, {@code format t} and the operators taken
	 * as function values -- the stream each designates is the current value of the
	 * variable.
	 */
	public static final String STANDARD_STREAM_PROGRAM = """
			(defclass gsd-src (rontolisp:fundamental-character-input-stream)
			  ((gsd-text :initarg :text) (gsd-pos :initform 0)))
			(defmethod rontolisp:stream-read-char ((gsd-s gsd-src))
			  (with-slots (gsd-text gsd-pos) gsd-s
			    (if (< gsd-pos (length gsd-text)) (prog1 (char gsd-text gsd-pos) (incf gsd-pos)) :eof)))
			(defmethod rontolisp:stream-unread-char ((gsd-s gsd-src) gsd-c)
			  (decf (slot-value gsd-s 'gsd-pos))
			  nil)
			(defclass gsd-sink (rontolisp:fundamental-character-output-stream)
			  ((gsd-acc :initform nil)))
			(defmethod rontolisp:stream-write-char ((gsd-s gsd-sink) gsd-c)
			  (push gsd-c (slot-value gsd-s 'gsd-acc))
			  gsd-c)
			(defun gsd-read-from (&optional gsd-s) (read-char gsd-s))
			(defun gsd-write-to (gsd-x &optional gsd-s) (princ gsd-x gsd-s))
			(let ((*standard-input* (make-instance 'gsd-src :text (format nil "ab-cd~%line2~%  x~%rest"))))
			  (print (list (read-char) (peek-char) (read-char nil) (progn (unread-char #\\b) (read-char))
			               (read-char-no-hang) (read-line) (read-line nil)))
			  (print (list (peek-char t) (gsd-read-from) (funcall #'read-line) (read-line nil nil :eof)
			               (read-char nil nil :eof))))
			(let ((gsd-out (make-instance 'gsd-sink)))
			  (print (let ((*standard-output* gsd-out))
			           (list (princ "ab") (prin1 "q") (write-char #\\c) (write-char #\\d nil) (write-string "ef")
			                 (write-string "xyz" nil :start 1) (write-line "g"))))
			  (print (let ((*standard-output* gsd-out))
			           (list (terpri) (progn (fresh-line) :fresh) (format t "~a!" 2) (format nil "~a" 3)
			                 (force-output) (finish-output) (clear-output) (gsd-write-to "h") (funcall #'princ "i"))))
			  (print (coerce (reverse (slot-value gsd-out 'gsd-acc)) 'string)))
			""";

	/** What {@link #STANDARD_STREAM_PROGRAM} prints (SBCL's answers). */
	public static final String STANDARD_STREAM_EXPECTED = String.join("\n",
			"(#\\a #\\b #\\b #\\b #\\- \"cd\" \"line2\")", "(#\\x #\\x \"\" \"rest\" :EOF)",
			"(\"ab\" \"q\" #\\c #\\d \"ef\" \"xyz\" \"g\")", "(NIL :FRESH NIL \"3\" NIL NIL NIL \"h\" \"i\")",
			"\"ab\\\"q\\\"cdefyzg", "", "", "2!hi\"");

	/**
	 * A {@code (format t ...)} in a package other than {@code cl-user}, run with a Gray
	 * {@code *standard-output*}: the compile paths lower that call before the package
	 * resolver runs, so the names its expansion emits ({@code %princ-piece},
	 * {@code %prin1-piece}, {@code %fmt-render}) must not resolve into the user package.
	 */
	public static final String STANDARD_STREAM_IN_A_PACKAGE_PROGRAM = """
			(defclass gsp-sink (rontolisp:fundamental-character-output-stream)
			  ((gsp-acc :initform nil)))
			(defmethod rontolisp:stream-write-char ((gsp-s gsp-sink) gsp-c)
			  (push gsp-c (slot-value gsp-s 'gsp-acc))
			  gsp-c)
			(defpackage :gsp-app (:use :cl))
			(in-package :gsp-app)
			(defun banner (server port control address)
			  (format t "~&~:(~a~) server ~x ~:c.~%" server port #\\Space)
			  (format t control address port))
			(let ((sink (make-instance 'cl-user::gsp-sink)))
			  (let ((*standard-output* sink))
			    (banner :reactor 255 "Listening on ~a:~d~%" "127.0.0.1"))
			  (print (coerce (reverse (slot-value sink 'cl-user::gsp-acc)) 'string)))
			""";

	/** What {@link #STANDARD_STREAM_IN_A_PACKAGE_PROGRAM} prints (SBCL's answer). */
	public static final String STANDARD_STREAM_IN_A_PACKAGE_EXPECTED = String.join("\n", "\"",
			"Reactor server FF Space.", "Listening on 127.0.0.1:255", "\"");

}
