package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispPackageException;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * A small prelude of standard functions implemented once in rontolisp source, so a single
 * recursive definition runs on every backend instead of a hand-assembled runtime helper
 * per backend. Each entry is a self-contained {@code defun} using only primitives every
 * backend compiles; it is materialised only when the program actually references the
 * function (the interpreter lazy-loads it on first resolution; the compile path prepends
 * it).
 *
 * <p>
 * Current members:
 * <ul>
 * <li>{@code equalp} -- like {@code equal} but strings/characters compare case
 * insensitively, numbers by value, and arrays element-wise (same dimensions, elements
 * compared with {@code equalp}); lite (hash-tables/structures fall back to
 * {@code eql}).</li>
 * <li>{@code string<} / {@code string>} / {@code string<=} / {@code string>=} /
 * {@code string/=} and their case-insensitive counterparts {@code string-lessp} /
 * {@code string-greaterp} / {@code string-not-greaterp} / {@code string-not-lessp} /
 * {@code string-not-equal} -- lexicographic comparison returning the mismatch index or
 * nil, with {@code :start1}/{@code :end1}/{@code :start2}/{@code :end2}; all ten share
 * the {@code %string-compare} walk.</li>
 * <li>{@code char-lessp} / {@code char-greaterp} / {@code char-not-lessp} /
 * {@code char-not-greaterp} -- the case-insensitive character ordering chain, all four
 * over the shared {@code %char-fold-chain} walk; {@code char-not-equal} (pairwise
 * distinct, like {@code char/=}); and {@code graphic-char-p} / {@code standard-char-p} by
 * code point.</li>
 * <li>{@code get-setf-expansion} -- the five setf-expansion values (lite: variable and
 * accessor-cons places, environment ignored), consumed through the ordinary
 * {@code %mv-spill} channel by a {@code multiple-value-bind} caller.</li>
 * <li>{@code encode-universal-time} / {@code decode-universal-time} -- the CL universal
 * time codec (seconds since 1900-01-01 00:00:00 GMT) as pure era-based Gregorian
 * arithmetic; lite: a nil time-zone means GMT, not the local zone (no backend-portable
 * local-zone source exists), and {@code daylight-p} decodes as nil.</li>
 * <li>{@code merge-pathnames} / {@code truename} -- the two ANSI pathname functions that
 * are pure namestring work: the merge rule {@code PathnameOps.mergePathnames} implements
 * (a rontolisp pathname IS its namestring), and {@code probe-file} plus a signal on a
 * missing file, which is what makes the {@code (ignore-errors (truename x))}
 * existence-probe idiom work.</li>
 * <li>{@code uiop/image:print-condition-backtrace} -- lite: prints the CONDITION and no
 * backtrace (no backend carries a Lisp-level call stack).</li>
 * <li>{@code directory} plus {@code uiop:directory-exists-p} /
 * {@code uiop:directory-files} / {@code uiop:subdirectories} /
 * {@code uiop:collect-sub*directories} -- the directory-LISTING family, one rendering of
 * the pattern / prefix / kind / ordering rules over the single {@code %list-directory}
 * primitive each backend implements.</li>
 * <li>{@code char-name} -- the standard character names ({@code Space}, {@code Newline},
 * ...), a {@code U+XXXX} label for other non-printing code points, nil for graphic
 * characters; mirrors the interpreter's Java primitive.</li>
 * <li>{@code rontolisp:read-all} -- an {@code rontolisp:async-defun} draining an
 * asynchronous stream's string chunks into one string.</li>
 * <li>{@code rontolisp:then} / {@code then*} / {@code catch} / {@code finally} -- the
 * future-as-value combinator quartet ({@code (rl:then future fn)} attaches a transform,
 * {@code (rl:catch future handler)} an error fallback, {@code (rl:finally future thunk)}
 * a cleanup; {@code (rl:then* future fn1 fn2 ...)} is the variadic chain sugar). Each
 * returns a fresh future composing the input's settlement; a non-future first argument
 * signals a {@code type-error}. Expands to {@code async-lambda} + {@code await} +
 * {@code handler-case}/{@code unwind-protect}, so every backend supports them
 * identically.</li>
 * <li>{@code rontolisp:plist-hash-table} / {@code rontolisp:hash-table-plist} and
 * {@code rontolisp:alist-hash-table} / {@code rontolisp:hash-table-alist} -- lightweight
 * subsets of the same-named {@code alexandria} utilities, converting a property list or
 * association list to a hash table (and back); the {@code *-hash-table} ones pair with
 * {@code rontolisp:json-stringify} for building JSON objects.</li>
 * <li>{@code rontolisp:alist-plist} / {@code rontolisp:plist-alist} -- the same pair of
 * lightweight {@code alexandria} subsets without the hash table in between, so both
 * directions preserve the input order.</li>
 * <li>{@code write} / {@code pprint} plus the pretty-printer subset
 * ({@code pprint-newline}, {@code pprint-indent}, {@code pprint-tab},
 * {@code copy-pprint-dispatch} / {@code set-pprint-dispatch} / {@code pprint-dispatch})
 * -- see {@code .kb/pretty-printer.md} for what is real (the keyword bindings, the
 * dispatch tables) and what a stream with no column cannot do.</li>
 * </ul>
 */
public final class LispPreludeLibrary {

	// name -> canonical-shape source (bare cl names, like json.lisp: needs no package
	// resolution). Order is preserved so prepended definitions keep a stable order.
	private static final Map<String, String> SOURCES = new LinkedHashMap<>();

	static {
		SOURCES.put(LispNames.EQUALP, """
				(defun equalp (a b)
				  (cond ((and (numberp a) (numberp b)) (= a b))
				        ((and (stringp a) (stringp b)) (string-equal a b))
				        ((and (characterp a) (characterp b))
				         (char= (char-downcase a) (char-downcase b)))
				        ((and (consp a) (consp b))
				         (and (equalp (car a) (car b)) (equalp (cdr a) (cdr b))))
				        ((and (%obj-p a) (%obj-p b))
				         (and (equal (%obj-tag a) (%obj-tag b))
				              (equalp (%obj-slots a) (%obj-slots b))))
				        ((and (%arrayp a) (%arrayp b))
				         (and (equal (array-dimensions a) (array-dimensions b))
				              (let ((n (array-total-size a)))
				                (labels ((cmp (i)
				                           (cond ((>= i n) t)
				                                 ((equalp (row-major-aref a i) (row-major-aref b i))
				                                  (cmp (+ i 1)))
				                                 (t nil))))
				                  (cmp 0)))))
				        (t (eql a b))))
				""");
		SOURCES.put(LispNames.ALPHANUMERICP, """
				(defun alphanumericp (c)
				  (or (alpha-char-p c) (digit-char-p c)))
				""");
		SOURCES.put(LispNames.MAKE_LOAD_FORM_SAVING_SLOTS, """
				(defun make-load-form-saving-slots (object &key slot-names environment)
				  (error "make-load-form-saving-slots is not supported (no fasl dumper)"))
				""");
		// Three real UIOP sequence/character utilities, bodies VERBATIM from upstream
		// utility.lisp. They join the uiop package rather than the stub lowering
		// because they are pure Lisp one-liners over primitives every backend has --
		// quri's render-uri calls all three to decide whether to insert a path slash.
		SOURCES.put(LispNames.EMPTYP, """
				(defun uiop:emptyp (x)
				  (or (null x) (and (vectorp x) (zerop (length x)))))
				""");
		SOURCES.put(LispNames.FIRST_CHAR, """
				(defun uiop:first-char (s)
				  (and (stringp s) (plusp (length s)) (char s 0)))
				""");
		SOURCES.put(LispNames.LAST_CHAR, """
				(defun uiop:last-char (s)
				  (and (stringp s) (plusp (length s)) (char s (1- (length s)))))
				""");
		// uiop:split-string -- upstream's semantics (split on ANY character of the
		// separator sequence, scanning right to left so :max keeps the UNsplit head:
		// ("a.b.c" :max 2 -> ("a.b" "c")), empty string -> ("")), rewritten without
		// upstream's flet-return-from-outer-block shape: a `return` inside `do` would
		// exit do's own nil block, so the loop carries an explicit done flag instead.
		// sxql's sql-symbol tokenizer calls it on every dotted column name.
		SOURCES.put(LispNames.SPLIT_STRING, """
				(defun uiop:split-string (string &key max (separator '(#\\Space #\\Tab)))
				  (let ((end (length string)))
				    (if (zerop end)
				        (list "")
				        (let ((parts nil) (words 0) (done nil))
				          (do ()
				              (done)
				            (if (and max (>= words (1- max)))
				                (setq done t)
				                (let ((start (position-if (lambda (c) (find c separator))
				                                          string :end end :from-end t)))
				                  (if (null start)
				                      (setq done t)
				                      (progn
				                        (setq parts (cons (subseq string (1+ start) end) parts))
				                        (setq words (1+ words))
				                        (setq end start))))))
				          (cons (subseq string 0 end) parts)))))
				""");
		// uiop/image:print-condition-backtrace -- lite: no backend carries a Lisp-level
		// call stack, so there is no backtrace to print and the honest rendering is the
		// condition alone. Real UIOP's own fallback for an implementation without a
		// backtrace API is the same shape. Defined in its home package (uiop/image);
		// the uiop package IMPORTS the name (PackageRegistry), so both spellings a
		// library may use name this one function. lack-middleware-backtrace calls it as
		// the first line of its error report.
		SOURCES.put(LispNames.PRINT_CONDITION_BACKTRACE, """
				(defun uiop/image:print-condition-backtrace (%pcb-condition &key (stream *error-output*) count)
				  (format stream "~A~%" %pcb-condition)
				  (values))
				""");
		SOURCES.put(LispNames.SXHASH, """
				(defun sxhash (obj)
				  (cond ((integerp obj) (logand obj most-positive-fixnum))
				        ((characterp obj) (char-code obj))
				        ((stringp obj)
				         (let ((h 0))
				           (dotimes (i (length obj))
				             (setq h (logand (+ (* h 31) (char-code (char obj i))) most-positive-fixnum)))
				           h))
				        ((symbolp obj) (sxhash (symbol-name obj)))
				        ((consp obj)
				         (logand (+ (* 31 (sxhash (car obj))) (sxhash (cdr obj))) most-positive-fixnum))
				        (t 0)))
				""");
		SOURCES.put(LispNames.SBIT, """
				(defun sbit (bit-array index)
				  (aref bit-array index))
				(defun (setf sbit) (new-value bit-array index)
				  (setf (aref bit-array index) new-value))
				""");
		SOURCES.put(LispNames.BIT, """
				(defun bit (bit-array index)
				  (aref bit-array index))
				(defun (setf bit) (new-value bit-array index)
				  (setf (aref bit-array index) new-value))
				""");
		SOURCES.put(LispNames.BOTH_CASE_P, """
				(defun both-case-p (c)
				  (or (lower-case-p c) (upper-case-p c)))
				""");
		SOURCES.put(LispNames.SPECIAL_OPERATOR_P, """
				(defun special-operator-p (symbol) nil)
				""");
		SOURCES.put(LispNames.MACRO_FUNCTION, """
				(defun macro-function (symbol &optional environment) nil)
				""");
		// A compiled image has no macro table, so a form built at RUNTIME is already its
		// own expansion: these identity defuns are what let a portable code walker
		// (ironclad's trivial-macroexpand-all) compile. A literal quoted argument never
		// reaches them -- UserMacroExpander folds it to the real expansion at compile
		// time -- and the interpreter defines the real functions eagerly, so the prelude
		// only ever serves the compiled backends.
		SOURCES.put(LispNames.MACROEXPAND, """
				(defun macroexpand (form &optional environment) (values form nil))
				""");
		SOURCES.put(LispNames.MACROEXPAND_1, """
				(defun macroexpand-1 (form &optional environment) (values form nil))
				""");
		SOURCES.put(LispNames.COMPILED_FUNCTION_P, """
				(defun compiled-function-p (object) nil)
				""");
		SOURCES.put(LispNames.FUNCTION_LAMBDA_EXPRESSION, """
				(defun function-lambda-expression (function) (values nil t nil))
				""");
		// The standard condition readers over the seeded hierarchy's slots. They are
		// prelude defuns rather than Java built-ins so one definition serves all four
		// backends, exactly like the slot readers a define-condition generates.
		SOURCES.put(LispNames.TYPE_ERROR_DATUM, """
				(defun type-error-datum (condition) (slot-value condition 'datum))
				""");
		SOURCES.put(LispNames.TYPE_ERROR_EXPECTED_TYPE, """
				(defun type-error-expected-type (condition) (slot-value condition 'expected-type))
				""");
		SOURCES.put(LispNames.CELL_ERROR_NAME, """
				(defun cell-error-name (condition) (slot-value condition 'name))
				""");
		SOURCES.put(LispNames.UNBOUND_SLOT_INSTANCE, """
				(defun unbound-slot-instance (condition) (slot-value condition 'instance))
				""");
		SOURCES.put(LispNames.LIST_ALL_PACKAGES, """
				(defun list-all-packages () nil)
				""");
		// A "package" is the upcased canonical package name as a keyword (there are no
		// package objects), so symbol-package reads the qualifier off the symbol's
		// stored spelling: prin1-to-string keeps it, unlike symbol-name. The
		// interpreter overrides this with the registry-backed version (which
		// distinguishes cl from cl-user); the compiled backends have no registry at
		// runtime, so every bare symbol answers CL-USER here.
		SOURCES.put(LispNames.SYMBOL_PACKAGE, """
				(defun symbol-package (symbol)
				  (let* ((s (prin1-to-string symbol))
				         (n (length s)))
				    (cond ((= n 0) nil)
				          ((char= (char s 0) #\\:) :keyword)
				          ((and (> n 1) (char= (char s 0) #\\#) (char= (char s 1) #\\:)) nil)
				          (t (let ((idx (position #\\: s)))
				               (if idx
				                   (intern (subseq s 0 idx) :keyword)
				                   :cl-user))))))
				""");
		// type-of over %class-designator (NOT class-of, which answers a metaobject
		// since the migration): the designator is a struct/CLOS instance's TAG symbol
		// (%struct-NAME / %class-NAME), and type-of is the type NAME -- so a digest
		// object's type is usable as the digest-name designator it came from. The tag
		// spelling is read with prin1-to-string, since symbol-name would drop the
		// package qualifier a canonical type name carries. Everything else answers the
		// designator itself (a built-in type name, or T).
		SOURCES.put(LispNames.TYPE_OF, """
				(defun type-of (object)
				  (let* ((c (%class-designator object))
				         (s (prin1-to-string c))
				         (n (length s)))
				    (cond ((and (> n 8) (string= (subseq s 0 8) "%struct-")) (intern (subseq s 8)))
				          ((and (> n 7) (string= (subseq s 0 7) "%class-")) (intern (subseq s 7)))
				          (t c))))
				""");
		// class-name reads the metaobject's name slot -- index 0 of the seeded
		// standard-class slot-order contract (ClosRegistry.ensureMopClassesSeeded),
		// the same read the closer-mop shim's class-name does.
		SOURCES.put(LispNames.CLASS_NAME, """
				(defun class-name (class)
				  (%obj-ref class 0))
				""");
		SOURCES.put(LispNames.GET, """
				(defvar %symbol-plists nil)
				(defun get (symbol indicator &optional default)
				  (let ((entry (assoc symbol %symbol-plists)))
				    (if entry
				        (do ((tail (cdr entry) (cddr tail)))
				            ((null tail) default)
				          (when (eq (car tail) indicator)
				            (return (car (cdr tail)))))
				        default)))
				(defun (setf get) (new-value symbol indicator)
				  (let ((entry (assoc symbol %symbol-plists)))
				    (unless entry
				      (setq entry (cons symbol nil))
				      (setq %symbol-plists (cons entry %symbol-plists)))
				    (do ((tail (cdr entry) (cddr tail)))
				        ((null tail)
				         (rplacd entry (cons indicator (cons new-value (cdr entry))))
				         new-value)
				      (when (eq (car tail) indicator)
				        (rplaca (cdr tail) new-value)
				        (return new-value)))))
				""");
		// merge-pathnames / truename: pure namestring work over primitives every
		// backend has, so ONE Lisp definition serves all four -- unlike make-pathname
		// and uiop:merge-pathnames*, which stay Java + compile-time folding because
		// their keyword shapes are resolved at compile time (PathnameOps). The merge
		// rule is the same one PathnameOps.mergePathnames implements; the two are
		// pinned against each other by LispPreludeLibraryTest.
		SOURCES.put(LispNames.MERGE_PATHNAMES, """
				(defun merge-pathnames (%mp-path &optional %mp-defaults)
				  (let* ((p (if (stringp %mp-path) %mp-path ""))
				         (d (if (stringp %mp-defaults) %mp-defaults ""))
				         (ps (position #\\/ p :from-end t))
				         (ds (position #\\/ d :from-end t))
				         (pdir (if ps (subseq p 0 (+ ps 1)) ""))
				         (pfile (if ps (subseq p (+ ps 1)) p))
				         (ddir (if ds (subseq d 0 (+ ds 1)) ""))
				         (dfile (if ds (subseq d (+ ds 1)) d))
				         (dir (cond ((string= pdir "") ddir)
				                    ((char= (char pdir 0) #\\/) pdir)
				                    ((string= ddir "") pdir)
				                    (t (concatenate 'string ddir pdir)))))
				    (concatenate 'string dir (if (string= pfile "") dfile pfile))))
				""");
		// %pathname-split: the ONE rendering of CL's "the LAST dot separates the type,
		// and a dot at position 0 does not" rule -- (directory name type), with name and
		// type nil when absent. pathname-name / pathname-type / make-pathname all read
		// it, so the three cannot disagree; PathnameOps.components is the Java twin the
		// compile-time folder uses and LispPreludeLibraryTest pins them against each
		// other.
		SOURCES.put(LispNames.PATHNAME_SPLIT, """
				(defun %pathname-split (%ps-path)
				  (let* ((%ps-p (if (stringp %ps-path) %ps-path ""))
				         (%ps-s (position #\\/ %ps-p :from-end t))
				         (%ps-d (if %ps-s (subseq %ps-p 0 (+ %ps-s 1)) ""))
				         (%ps-f (if %ps-s (subseq %ps-p (+ %ps-s 1)) %ps-p))
				         (%ps-dot (position #\\. %ps-f :from-end t)))
				    (if (or (null %ps-dot) (= %ps-dot 0))
				        (list %ps-d (if (string= %ps-f "") nil %ps-f) nil)
				        (list %ps-d (subseq %ps-f 0 %ps-dot) (subseq %ps-f (+ %ps-dot 1))))))
				""");
		SOURCES.put(LispNames.PATHNAME_NAME, """
				(defun pathname-name (%pn-path)
				  (second (%pathname-split %pn-path)))
				""");
		SOURCES.put(LispNames.PATHNAME_TYPE, """
				(defun pathname-type (%pt-path)
				  (third (%pathname-split %pt-path)))
				""");
		SOURCES.put(LispNames.PATHNAME_DIRECTORY_STRING, """
				(defun %pathname-directory-string (%pds-dir)
				  (cond ((null %pds-dir) "")
				        ((stringp %pds-dir)
				         (if (or (string= %pds-dir "")
				                 (char= (char %pds-dir (- (length %pds-dir) 1)) #\\/))
				             %pds-dir
				             (concatenate 'string %pds-dir "/")))
				        ((consp %pds-dir)
				         (let* ((%pds-head (car %pds-dir))
				                (%pds-abs (eq %pds-head :absolute))
				                (%pds-rest (if (or %pds-abs (eq %pds-head :relative))
				                               (cdr %pds-dir)
				                               %pds-dir))
				                (%pds-acc (if %pds-abs "/" "")))
				           (dolist (%pds-c %pds-rest)
				             (setq %pds-acc
				                   (concatenate 'string %pds-acc
				                                (cond ((stringp %pds-c) %pds-c)
				                                      ((eq %pds-c :up) "..")
				                                      ((eq %pds-c :back) "..")
				                                      ((eq %pds-c :wild) "*")
				                                      (t (error "MAKE-PATHNAME: unsupported :directory component ~S"
				                                                %pds-c)))
				                                "/")))
				           %pds-acc))
				        (t (error "MAKE-PATHNAME: :directory must be a list or string, got ~S" %pds-dir))))
				""");
		// make-pathname at RUN time (.todo/222): the shapes cli/CompileTimePathnameFolder
		// declines -- a computed :defaults or :name -- used to compile to a call-time
		// error on all three compiled backends. One Lisp definition serves all four, and
		// it implements the SAME rule as PathnameOps.makePathname (which the folder still
		// uses, so an ASDF-located data directory stays a literal in the artifact).
		//
		// :defaults defaults COMPONENT-WISE and is NOT a merge: a supplied :directory
		// REPLACES the defaults' directory rather than being appended to it, and a
		// supplied nil means "no component". That is what CL specifies and what SBCL
		// answers. :host / :device / :version / :case are accepted and dropped (the
		// components a namestring does not model), as is any other key, so a portability
		// layer's call still works.
		SOURCES.put(LispNames.MAKE_PATHNAME, """
				(defun make-pathname (&key (directory nil %mkp-dp) (name nil %mkp-np) (type nil %mkp-tp)
				                           defaults host device version case
				                      &allow-other-keys)
				  (let* ((%mkp-d (%pathname-split (if (stringp defaults) defaults "")))
				         (%mkp-dir (if %mkp-dp (%pathname-directory-string directory) (first %mkp-d)))
				         (%mkp-n (if %mkp-np (%pathname-component-string name) (or (second %mkp-d) "")))
				         (%mkp-t (if %mkp-tp (%pathname-component-string type) (or (third %mkp-d) ""))))
				    (concatenate 'string %mkp-dir
				                 (if (string= %mkp-t "")
				                     %mkp-n
				                     (concatenate 'string %mkp-n "." %mkp-t)))))
				""");
		SOURCES.put(LispNames.PATHNAME_COMPONENT_STRING, """
				(defun %pathname-component-string (%pcs-v)
				  (cond ((null %pcs-v) "")
				        ((stringp %pcs-v) %pcs-v)
				        ((symbolp %pcs-v) (string %pcs-v))
				        (t (error "MAKE-PATHNAME: :name and :type must be a string or nil, got ~S" %pcs-v))))
				""");
		// delete-file: the signalling ANSI surface over the %delete-file primitive (nil
		// when the file is not there), so the "a missing file is a file-error" rule has
		// one definition. mito's generate-migrations deletes superseded migration files
		// with it.
		SOURCES.put(LispNames.DELETE_FILE, """
				(defun delete-file (%dfl-path)
				  (if (%delete-file %dfl-path)
				      t
				      (error "DELETE-FILE: cannot delete ~A" %dfl-path)))
				""");
		// y-or-n-p: prompt + a line of standard input, re-asking on anything that is
		// neither y nor n. Lite: CL reads single characters without echo, and end of
		// input answers nil here rather than looping forever on a non-interactive
		// backend.
		SOURCES.put(LispNames.Y_OR_N_P, """
				(defun y-or-n-p (&optional %ynp-control &rest %ynp-args)
				  (let ((%ynp-done nil) (%ynp-answer nil))
				    (do () (%ynp-done %ynp-answer)
				      (when %ynp-control
				        (apply #'format t %ynp-control %ynp-args))
				      (format t " (y or n) ")
				      (finish-output)
				      (let ((%ynp-line (read-line *standard-input* nil nil)))
				        (cond ((null %ynp-line) (setq %ynp-done t))
				              ((string= %ynp-line "") nil)
				              (t (let ((%ynp-c (char-downcase (char %ynp-line 0))))
				                   (cond ((char= %ynp-c #\\y) (setq %ynp-answer t) (setq %ynp-done t))
				                         ((char= %ynp-c #\\n) (setq %ynp-done t))
				                         (t nil)))))))))
				""");
		// A broadcast stream WITH components is a Gray output stream whose two write
		// generics loop the components (.kb/gray-streams.md): no runtime learns a new
		// stream kind, the dispatch that already exists carries it, and the four backends
		// therefore cannot drift. Reached only through
		// LispMacroExpander.expandMakeBroadcastStream's multi-argument branch -- a
		// component-LESS (make-broadcast-stream) still lowers to the discarding
		// %make-string-output-stream sink and pulls NONE of this in, which is what keeps
		// every existing sink program's bytes (and keeps the entry from dragging the Gray
		// protocol into pipelines that never run GrayStreamsLibrary.process).
		SOURCES.put(LispNames.MAKE_BROADCAST_STREAM_INTERNAL, """
				(defclass %broadcast-stream (rontolisp:fundamental-character-output-stream)
				  ((components :initarg :components :reader %broadcast-stream-components)))
				(defmethod rontolisp:stream-write-char ((%bs-s %broadcast-stream) %bs-c)
				  (dolist (%bs-x (%broadcast-stream-components %bs-s))
				    (write-char %bs-c %bs-x))
				  %bs-c)
				(defmethod rontolisp:stream-write-string ((%bs-s %broadcast-stream) %bs-str
				                                         &optional (%bs-start 0) %bs-end)
				  (let ((%bs-part (subseq %bs-str %bs-start (or %bs-end (length %bs-str)))))
				    (dolist (%bs-x (%broadcast-stream-components %bs-s))
				      (write-string %bs-part %bs-x)))
				  %bs-str)
				(defun %make-broadcast-stream (%mbs-components)
				  (make-instance '%broadcast-stream :components %mbs-components))
				""");
		SOURCES.put(LispNames.CONSTANTLY, """
				(defun constantly (%ct-value)
				  (lambda (&rest %ct-args) %ct-value))
				""");
		// package-name: a "package" is its canonical upcased name as a keyword
		// (.kb/symbol-runtime-api.md), so the name string is that keyword's string;
		// resolving through find-package first honors designators and nicknames, and
		// an unknown designator signals like CL's package-error. dbi's
		// connection-driver-type is the driving consumer.
		SOURCES.put(LispNames.PACKAGE_NAME, """
				(defun package-name (%pn-pkg)
				  (string (or (find-package %pn-pkg)
				              (error "PACKAGE-NAME: no package named ~A" %pn-pkg))))
				""");
		SOURCES.put(LispNames.NAMESTRING_CL, """
				(defun namestring (%ns-path)
				  (if (stringp %ns-path)
				      %ns-path
				      (error "NAMESTRING: not a pathname designator: ~S" %ns-path)))
				""");
		SOURCES.put(LispNames.TRUENAME, """
				(defun truename (%tn-path)
				  (or (probe-file %tn-path)
				      (error "TRUENAME: no such file")))
				""");
		// The directory-LISTING family: one Lisp rendering of the pattern, prefix,
		// kind and ordering rules over the single per-backend primitive
		// (%list-directory), so the four backends cannot drift. %list-directory
		// answers nil for anything that is not a readable directory and (t . names)
		// otherwise -- the leading t is what tells an EMPTY directory from a missing
		// one, which a bare list cannot. Names come back in the host's order and are
		// sorted here, so the same program prints the same listing everywhere.
		SOURCES.put(LispNames.PATHNAME_DIRECTORY, """
				(defun pathname-directory (%pd-path)
				  (let* ((%pd-p (if (stringp %pd-path) %pd-path ""))
				         (%pd-s (position #\\/ %pd-p :from-end t)))
				    (if (null %pd-s)
				        nil
				        (let ((%pd-acc nil) (%pd-start 0))
				          (dotimes (%pd-i (+ %pd-s 1))
				            (when (char= (char %pd-p %pd-i) #\\/)
				              (when (> %pd-i %pd-start)
				                (setq %pd-acc (cons (subseq %pd-p %pd-start %pd-i) %pd-acc)))
				              (setq %pd-start (+ %pd-i 1))))
				          (cons (if (char= (char %pd-p 0) #\\/) :absolute :relative)
				                (reverse %pd-acc))))))
				""");
		SOURCES.put(LispNames.PATHNAME_TYPED_P, """
				(defun %pathname-typed-p (%pt-name)
				  (let ((%pt-d (position #\\. %pt-name)))
				    (and %pt-d (> %pt-d 0) t)))
				""");
		SOURCES.put(LispNames.WILD_MATCH, """
				(defun %wild-match (%wm-pat %wm-str)
				  (let ((%wm-pn (length %wm-pat)) (%wm-sn (length %wm-str)))
				    (labels ((m (p s)
				               (cond ((>= p %wm-pn) (>= s %wm-sn))
				                     ((char= (char %wm-pat p) #\\*)
				                      (or (m (+ p 1) s)
				                          (and (< s %wm-sn) (m p (+ s 1)))))
				                     ((>= s %wm-sn) nil)
				                     ((or (char= (char %wm-pat p) #\\?)
				                          (char= (char %wm-pat p) (char %wm-str s)))
				                      (m (+ p 1) (+ s 1)))
				                     (t nil))))
				      (m 0 0))))
				""");
		SOURCES.put(LispNames.DIR_NAMESTRING, """
				(defun %dir-namestring (%dn-path)
				  (let ((%dn-p (if (stringp %dn-path) %dn-path "")))
				    (if (or (string= %dn-p "")
				            (char= (char %dn-p (- (length %dn-p) 1)) #\\/))
				        %dn-p
				        (concatenate 'string %dn-p "/"))))
				""");
		// ensure-directories-exist: the DIRECTORY component of the namestring is
		// everything
		// up to and including the last slash, so "logs/app.log" creates "logs/" and a
		// namestring that already ends in a slash IS the directory. A namestring with no
		// slash names a file in the working directory and creates nothing. Returns the
		// pathspec (see LispNames.ENSURE_DIRECTORIES_EXIST for the missing second value).
		SOURCES.put(LispNames.ENSURE_DIRECTORIES_EXIST, """
				(defun ensure-directories-exist (%ede-path)
				  (let* ((%ede-p (if (stringp %ede-path) %ede-path ""))
				         (%ede-s (position #\\/ %ede-p :from-end t))
				         (%ede-d (if %ede-s (subseq %ede-p 0 (+ %ede-s 1)) "")))
				    (if (string= %ede-d "") %ede-path (progn (%make-directories %ede-d) %ede-path))))
				""");
		SOURCES.put(LispNames.DIRECTORY, """
				(defun directory (%dir-spec)
				  (let* ((%dir-p (if (stringp %dir-spec) %dir-spec ""))
				         (%dir-s (position #\\/ %dir-p :from-end t))
				         (%dir-d (if %dir-s (subseq %dir-p 0 (+ %dir-s 1)) ""))
				         (%dir-n (if %dir-s (subseq %dir-p (+ %dir-s 1)) %dir-p)))
				    (if (or (position #\\* %dir-n) (position #\\? %dir-n))
				        (let ((%dir-e (%list-directory (if (string= %dir-d "") "." %dir-d)))
				              (%dir-acc nil))
				          (dolist (%dir-x (cdr %dir-e))
				            (let ((%dir-b (if (char= (char %dir-x (- (length %dir-x) 1)) #\\/)
				                              (subseq %dir-x 0 (- (length %dir-x) 1))
				                              %dir-x)))
				              (when (or (string= %dir-n "*.*")
				                        (and (%wild-match %dir-n %dir-b)
				                             (or (%pathname-typed-p %dir-n)
				                                 (not (%pathname-typed-p %dir-b)))))
				                (setq %dir-acc (cons (concatenate 'string %dir-d %dir-x) %dir-acc)))))
				          (sort %dir-acc #'string<))
				        (let ((%dir-f (%dir-namestring %dir-p)))
				          (cond ((%list-directory (if (string= %dir-f "") "." %dir-f)) (list %dir-f))
				                ((and (string/= %dir-n "") (probe-file %dir-p)) (list %dir-p))
				                (t nil))))))
				""");
		SOURCES.put(LispNames.DIRECTORY_EXISTS_P, """
				(defun uiop:directory-exists-p (%de-path)
				  (let ((%de-d (%dir-namestring %de-path)))
				    (if (%list-directory (if (string= %de-d "") "." %de-d)) %de-d nil)))
				""");
		// The optional PATTERN is UIOP's own second argument: a name-and-type wildcard
		// (never a directory one -- real UIOP signals "Invalid file pattern" for that and
		// so does this), appended to the directory and matched by the same `directory`
		// rules. mito's migration reader spells it (uiop:directory-files dir "*.up.sql").
		SOURCES.put(LispNames.DIRECTORY_FILES, """
				(defun uiop:directory-files (%df-dir &optional (%df-pat "*.*"))
				  (when (position #\\/ %df-pat)
				    (error "Invalid file pattern ~S" %df-pat))
				  (let ((%df-acc nil))
				    (dolist (%df-e (directory (concatenate 'string (%dir-namestring %df-dir) %df-pat)))
				      (unless (char= (char %df-e (- (length %df-e) 1)) #\\/)
				        (setq %df-acc (cons %df-e %df-acc))))
				    (nreverse %df-acc)))
				""");
		// Chunked, NOT (make-string (file-length s)): file-length answers nil on both
		// WASM backends (no WASI filestat call is imported, .kb/read-load-streams.md), so
		// sizing the buffer from it traps there. The loop also reads EOF at most once --
		// it stops as soon as a read comes back short -- because a SECOND read past EOF
		// traps on the --component backend (the adapter's stream_read after the writable
		// end dropped).
		SOURCES.put(LispNames.READ_FILE_STRING, """
				(defun uiop:read-file-string (%rfs-file &rest %rfs-keys)
				  (with-open-file (%rfs-in %rfs-file)
				    (let ((%rfs-acc "") (%rfs-buf (make-string 4096)) (%rfs-n 4096))
				      (while (= %rfs-n 4096)
				        (setq %rfs-n (read-sequence %rfs-buf %rfs-in))
				        (when (> %rfs-n 0)
				          (setq %rfs-acc (concatenate 'string %rfs-acc (subseq %rfs-buf 0 %rfs-n)))))
				      %rfs-acc)))
				""");
		SOURCES.put(LispNames.SUBDIRECTORIES, """
				(defun uiop:subdirectories (%sd-dir)
				  (let ((%sd-acc nil))
				    (dolist (%sd-e (directory (concatenate 'string (%dir-namestring %sd-dir) "*.*")))
				      (when (char= (char %sd-e (- (length %sd-e) 1)) #\\/)
				        (setq %sd-acc (cons %sd-e %sd-acc))))
				    (nreverse %sd-acc)))
				""");
		SOURCES.put(LispNames.COLLECT_SUB_DIRECTORIES, """
				(defun uiop:collect-sub*directories (%cd-dir %cd-collectp %cd-recursep %cd-collector)
				  (let ((%cd-d (%dir-namestring %cd-dir)))
				    (when (funcall %cd-collectp %cd-d)
				      (funcall %cd-collector %cd-d))
				    (dolist (%cd-sub (uiop:subdirectories %cd-d))
				      (when (funcall %cd-recursep %cd-sub)
				        (uiop:collect-sub*directories %cd-sub %cd-collectp %cd-recursep %cd-collector))))
				  nil)
				""");
		SOURCES.put(LispNames.CHAR_NAME, """
				(defun char-name (c)
				  (let ((cp (char-code c)))
				    (cond ((= cp 32) "Space")
				          ((= cp 10) "Newline")
				          ((= cp 9) "Tab")
				          ((= cp 13) "Return")
				          ((= cp 12) "Page")
				          ((= cp 8) "Backspace")
				          ((= cp 0) "Null")
				          ((= cp 127) "Rubout")
				          ((or (< cp 32) (> cp 126))
				           (let ((digits "0123456789ABCDEF"))
				             (labels ((hex (n acc)
				                        (if (and (= n 0) (>= (length acc) 4))
				                            (concatenate 'string "U+" acc)
				                            (hex (floor n 16)
				                                 (concatenate 'string
				                                              (subseq digits (mod n 16) (+ (mod n 16) 1))
				                                              acc)))))
				               (hex cp ""))))
				          (t nil))))
				""");
		SOURCES.put(LispNames.READ_ALL, """
				(rontolisp:async-defun rontolisp:read-all (s)
				  (let ((acc "")
				        (chunk (rontolisp:await (rontolisp:stream-read s))))
				    (while chunk
				      (setq acc (concatenate 'string acc chunk))
				      (setq chunk (rontolisp:await (rontolisp:stream-read s))))
				    acc))
				""");
		// The future-as-value combinator quartet (rontolisp:then / then* / catch /
		// finally): each returns a FRESH future that composes the input future's
		// settlement. Implemented in Lisp over async-lambda + await + handler-case +
		// unwind-protect, so every backend supports them identically (the WASM EH mode
		// gate flips automatically because handler-case/unwind-protect head symbols land
		// in the AST via the splice, and --no-gc rejects the surface by name).
		//
		// Non-future first argument is a type-error -- no JS-style auto-coercion to a
		// resolved promise (users write (funcall (async-lambda () v)) for that).
		SOURCES.put(LispNames.THEN, """
				(defun rontolisp:then (%rl-then-fut %rl-then-fn)
				  (unless (rontolisp:futurep %rl-then-fut)
				    (error "rontolisp:THEN expects a future as its first argument"))
				  (funcall (rontolisp:async-lambda ()
				             (funcall %rl-then-fn (rontolisp:await %rl-then-fut)))))
				""");
		// then* with no callbacks returns the input future unchanged -- degenerate
		// identity, documented as such. With callbacks it threads each function's
		// (auto-flattened via await) result to the next stage.
		SOURCES.put(LispNames.THEN_STAR, """
				(defun rontolisp:then* (%rl-thens-fut &rest %rl-thens-fns)
				  (unless (rontolisp:futurep %rl-thens-fut)
				    (error "rontolisp:THEN* expects a future as its first argument"))
				  (if (null %rl-thens-fns)
				      %rl-thens-fut
				      (funcall (rontolisp:async-lambda ()
				                 (let ((%rl-thens-v (rontolisp:await %rl-thens-fut)))
				                   (dolist (%rl-thens-fn %rl-thens-fns)
				                     (setq %rl-thens-v (rontolisp:await
				                                        (funcall %rl-thens-fn %rl-thens-v))))
				                   %rl-thens-v)))))
				""");
		// catch: JS-style single-handler on the error channel. The value-shaped
		// counterpart to (handler-case (await f) (some-type (c) ...)) already exists
		// lexically -- this operator is only the value combinator you attach when the
		// future crosses a boundary. A handler that itself signals produces a future
		// carrying THAT condition.
		SOURCES.put(LispNames.CATCH, """
				(defun rontolisp:catch (%rl-catch-fut %rl-catch-handler)
				  (unless (rontolisp:futurep %rl-catch-fut)
				    (error "rontolisp:CATCH expects a future as its first argument"))
				  (funcall (rontolisp:async-lambda ()
				             (handler-case (rontolisp:await %rl-catch-fut)
				               (error (%rl-catch-c) (funcall %rl-catch-handler %rl-catch-c))))))
				""");
		// finally: runs the thunk exactly once on either channel; the original outcome
		// carries through (a thunk-raised condition replaces it, per unwind-protect).
		SOURCES.put(LispNames.FINALLY, """
				(defun rontolisp:finally (%rl-fin-fut %rl-fin-thunk)
				  (unless (rontolisp:futurep %rl-fin-fut)
				    (error "rontolisp:FINALLY expects a future as its first argument"))
				  (funcall (rontolisp:async-lambda ()
				             (unwind-protect (rontolisp:await %rl-fin-fut)
				               (funcall %rl-fin-thunk)))))
				""");
		SOURCES.put(LispNames.PLIST_HASH_TABLE, """
				(defun rontolisp:plist-hash-table (plist &rest hash-table-initargs)
				  (let ((table (apply #'make-hash-table hash-table-initargs)))
				    (do ((tail plist (cddr tail)))
				        ((null tail) table)
				      (setf (gethash (car tail) table) (cadr tail)))))
				""");
		SOURCES.put(LispNames.HASH_TABLE_PLIST, """
				(defun rontolisp:hash-table-plist (table)
				  (let ((%htp-acc nil))
				    (maphash (lambda (k v) (setq %htp-acc (cons k (cons v %htp-acc)))) table)
				    %htp-acc))
				""");
		SOURCES.put(LispNames.ALIST_HASH_TABLE, """
				(defun rontolisp:alist-hash-table (alist &rest hash-table-initargs)
				  (let ((table (apply #'make-hash-table hash-table-initargs)))
				    (dolist (cell alist)
				      (unless (nth-value 1 (gethash (car cell) table))
				        (setf (gethash (car cell) table) (cdr cell))))
				    table))
				""");
		SOURCES.put(LispNames.HASH_TABLE_ALIST, """
				(defun rontolisp:hash-table-alist (table)
				  (let ((%hta-acc nil))
				    (maphash (lambda (k v) (setq %hta-acc (cons (cons k v) %hta-acc))) table)
				    %hta-acc))
				""");
		// The alist <-> plist pair goes through no hash table, so unlike the four above
		// it is order-preserving in both directions, exactly like alexandria's.
		SOURCES.put(LispNames.ALIST_PLIST, """
				(defun rontolisp:alist-plist (alist)
				  (let ((%ap-acc nil))
				    (dolist (%ap-cell alist)
				      (setq %ap-acc (cons (cdr %ap-cell) (cons (car %ap-cell) %ap-acc))))
				    (nreverse %ap-acc)))
				""");
		SOURCES.put(LispNames.PLIST_ALIST, """
				(defun rontolisp:plist-alist (plist)
				  (let ((%pa-acc nil))
				    (do ((%pa-tail plist (cddr %pa-tail)))
				        ((null %pa-tail) (nreverse %pa-acc))
				      (setq %pa-acc (cons (cons (car %pa-tail) (cadr %pa-tail)) %pa-acc)))))
				""");
		SOURCES.put(LispNames.GET_SETF_EXPANSION, """
				(defun get-setf-expansion (place &optional env)
				  (if (consp place)
				      (let ((temps (mapcar (lambda (a) (gensym)) (cdr place)))
				            (store (gensym)))
				        (values temps
				                (cdr place)
				                (list store)
				                (list 'setf (cons (car place) temps) store)
				                (cons (car place) temps)))
				      (let ((store (gensym)))
				        (values nil nil (list store) (list 'setq place store) place))))
				""");
		// Universal time = seconds since 1900-01-01 00:00:00 GMT. Days go through the
		// era-based civil-date algorithm (proleptic Gregorian, exact for any year),
		// so both directions are pure integer arithmetic that every backend runs
		// identically. Lite deviation, documented: a nil time-zone means GMT (zone 0),
		// not the machine's local zone -- no backend-portable local-zone source
		// exists (WASI exposes no timezone), and defaulting to the one zone every
		// backend agrees on keeps the pair backend-identical. 25567 = days from
		// 1900-01-01 to the 1970-01-01 pivot of the civil-date algorithm.
		SOURCES.put(LispNames.ENCODE_UNIVERSAL_TIME, """
				(defun encode-universal-time (second minute hour date month year &optional time-zone)
				  (let* ((y (if (<= month 2) (- year 1) year))
				         (era (floor y 400))
				         (yoe (- y (* era 400)))
				         (mp (mod (+ month 9) 12))
				         (doy (+ (floor (+ (* 153 mp) 2) 5) (- date 1)))
				         (doe (+ (* yoe 365) (floor yoe 4) (- (floor yoe 100)) doy))
				         (days-1970 (+ (* era 146097) doe -719468))
				         (tz (or time-zone 0)))
				    (+ (* (+ days-1970 25567) 86400)
				       (* hour 3600) (* minute 60) second
				       (* tz 3600))))
				""");
		SOURCES.put(LispNames.DECODE_UNIVERSAL_TIME, """
				(defun decode-universal-time (universal-time &optional time-zone)
				  (let* ((tz (or time-zone 0))
				         (ut (- universal-time (* tz 3600)))
				         (days (floor ut 86400))
				         (secs (- ut (* days 86400)))
				         (z (+ (- days 25567) 719468))
				         (era (floor z 146097))
				         (doe (- z (* era 146097)))
				         (yoe (floor (- (+ doe (floor doe 36524))
				                        (+ (floor doe 1460) (floor doe 146096)))
				                     365))
				         (y (+ yoe (* era 400)))
				         (doy (- doe (+ (* 365 yoe) (floor yoe 4) (- (floor yoe 100)))))
				         (mp (floor (+ (* 5 doy) 2) 153))
				         (date (+ (- doy (floor (+ (* 153 mp) 2) 5)) 1))
				         (month (if (< mp 10) (+ mp 3) (- mp 9)))
				         (year (if (<= month 2) (+ y 1) y)))
				    (values (mod secs 60)
				            (mod (floor secs 60) 60)
				            (floor secs 3600)
				            date month year
				            (mod (+ (- days 25567) 3) 7)
				            nil tz)))
				""");
		// rontolisp:random-bytes -- the public cryptographic-entropy API, one Lisp
		// definition over the per-backend %random-byte primitive (SecureRandom on the
		// interpreter/JVM, WASI random_get on both WASM backends).
		SOURCES.put(LispNames.RANDOM_BYTES, """
				(defun rontolisp:random-bytes (n)
				  (let ((%rb-out (make-array n)))
				    (dotimes (%rb-i n)
				      (setf (aref %rb-out %rb-i) (rontolisp::%random-byte)))
				    %rb-out))
				""");
		SOURCES.put(LispNames.SUBST, """
				(defun subst (new old tree &key (test #'eql) key)
				  (labels ((walk (x)
				             (cond ((funcall test old (if key (funcall key x) x)) new)
				                   ((consp x)
				                    (let ((a (walk (car x))) (d (walk (cdr x))))
				                      (if (and (eq a (car x)) (eq d (cdr x))) x (cons a d))))
				                   (t x))))
				    (walk tree)))
				""");
		// mismatch: the index INTO SEQUENCE1 of the first differing element, or nil
		// when the bounded subsequences match. Lite: :from-end is accepted and the
		// scan still runs forward (the returned index is then the forward one).
		// digit-char: the inverse of digit-char-p -- the (upper-case) character
		// denoting a weight in the radix, or nil when the weight is out of range.
		SOURCES.put(LispNames.DIGIT_CHAR, """
				(defun digit-char (weight &optional (radix 10))
				  (if (and (integerp weight) (>= weight 0) (< weight radix))
				      (if (< weight 10)
				          (code-char (+ (char-code #\\0) weight))
				          (code-char (+ (char-code #\\A) (- weight 10))))
				      nil))
				""");
		// decode-float: significand in [1/2, 1), exponent, sign -- CL's binary
		// decomposition. Halving/doubling by two is exact in binary floating point,
		// so the scaling loop introduces no rounding error on any backend.
		SOURCES.put(LispNames.DECODE_FLOAT, """
				(defun decode-float (f)
				  (let ((x (abs (float f)))
				        (s (if (< f 0) -1.0 1.0))
				        (e 0))
				    (if (= x 0.0)
				        (values 0.0 0 s)
				        (progn
				          (while (>= x 1.0) (setq x (/ x 2.0)) (setq e (+ e 1)))
				          (while (< x 0.5) (setq x (* x 2.0)) (setq e (- e 1)))
				          (values x e s)))))
				""");
		SOURCES.put(LispNames.MISMATCH, """
				(defun mismatch (seq1 seq2 &key (test #'eql) key (start1 0) end1 (start2 0) end2 from-end)
				  (let* ((e1 (or end1 (length seq1)))
				         (e2 (or end2 (length seq2)))
				         (i start1)
				         (j start2)
				         (result nil)
				         (done nil))
				    (while (not done)
				      (cond ((and (>= i e1) (>= j e2)) (setq done t))
				            ((or (>= i e1) (>= j e2)) (setq result i) (setq done t))
				            (t (let ((a (elt seq1 i)) (b (elt seq2 j)))
				                 (if (funcall test (if key (funcall key a) a)
				                              (if key (funcall key b) b))
				                     (progn (setq i (+ i 1)) (setq j (+ j 1)))
				                     (progn (setq result i) (setq done t)))))))
				    result))
				""");
		SOURCES.put(LispNames.COPY_TREE, """
				(defun copy-tree (tree)
				  (if (consp tree)
				      (cons (copy-tree (car tree)) (copy-tree (cdr tree)))
				      tree))
				""");
		SOURCES.put(LispNames.SEARCH, """
				(defun search (seq1 seq2 &key (start1 0) end1 (start2 0) end2 (test #'eql) key from-end)
				  (let* ((e1 (or end1 (length seq1)))
				         (e2 (or end2 (length seq2)))
				         (w (- e1 start1))
				         (result nil))
				    (do ((pos start2 (+ pos 1)))
				        ((or (> (+ pos w) e2) (and result (not from-end))) result)
				      (let ((ok t))
				        (do ((i 0 (+ i 1)))
				            ((or (>= i w) (not ok)))
				          (let ((a (elt seq1 (+ start1 i)))
				                (b (elt seq2 (+ pos i))))
				            (unless (funcall test (if key (funcall key a) a)
				                             (if key (funcall key b) b))
				              (setq ok nil))))
				        (when ok (setq result pos))))))
				""");
		// The whole string comparison family walks ONE shared lexicographic loop:
		// %string-compare returns (order . mismatch-index), where order is -1/0/1 for
		// substring1 before/equal/after substring2 and mismatch-index is the index into
		// string1 of the first differing character (end1 when the substrings are equal,
		// which is what string<=/string>= must return). Each operator is then a one-line
		// test on the order, so the case-folding and the bounding-index handling exist
		// once instead of ten times. Iterative (not recursive) so comparing long strings
		// cannot exhaust the stack on any backend.
		SOURCES.put(LispNames.STRING_COMPARE, """
				(defun %string-compare (a b start1 end1 start2 end2 foldp)
				  (let* ((sa (string a)) (sb (string b))
				         (i (or start1 0)) (j (or start2 0))
				         (e1 (or end1 (length sa))) (e2 (or end2 (length sb)))
				         (result nil))
				    (while (null result)
				      (cond ((and (>= i e1) (>= j e2)) (setq result (cons 0 i)))
				            ((>= i e1) (setq result (cons -1 i)))
				            ((>= j e2) (setq result (cons 1 i)))
				            (t (let ((ca (char sa i))
				                     (cb (char sb j)))
				                 (when foldp
				                   (setq ca (char-downcase ca))
				                   (setq cb (char-downcase cb)))
				                 (cond ((char< ca cb) (setq result (cons -1 i)))
				                       ((char< cb ca) (setq result (cons 1 i)))
				                       (t (setq i (+ i 1))
				                          (setq j (+ j 1))))))))
				    result))
				""");
		SOURCES.put(LispNames.STRING_LT, """
				(defun string< (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 nil)))
				    (if (< (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_GT, """
				(defun string> (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 nil)))
				    (if (> (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_LE, """
				(defun string<= (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 nil)))
				    (if (<= (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_GE, """
				(defun string>= (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 nil)))
				    (if (>= (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_NE, """
				(defun string/= (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 nil)))
				    (if (= (car r) 0) nil (cdr r))))
				""");
		SOURCES.put(LispNames.STRING_LESSP, """
				(defun string-lessp (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 t)))
				    (if (< (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_GREATERP, """
				(defun string-greaterp (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 t)))
				    (if (> (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_NOT_GREATERP, """
				(defun string-not-greaterp (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 t)))
				    (if (<= (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_NOT_LESSP, """
				(defun string-not-lessp (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 t)))
				    (if (>= (car r) 0) (cdr r) nil)))
				""");
		SOURCES.put(LispNames.STRING_NOT_EQUAL, """
				(defun string-not-equal (string1 string2 &key (start1 0) end1 (start2 0) end2)
				  (let ((r (%string-compare string1 string2 start1 end1 start2 end2 t)))
				    (if (= (car r) 0) nil (cdr r))))
				""");
		// The case-INSENSITIVE character ordering family, on the same "one shared walk,
		// one-line operators" plan as %string-compare above: %char-fold-chain checks each
		// ADJACENT pair's sign against [lo, hi] after downcasing both, which is exactly
		// the
		// interpreter's Java charCompareChain with a fold added. char-not-equal is the
		// odd
		// one out -- CL specifies it as ALL arguments pairwise distinct, not adjacent
		// ones,
		// so it gets its own quadratic walk (mirroring char/=).
		SOURCES.put(LispNames.CHAR_FOLD_CHAIN, """
				(defun %char-fold-chain (chars lo hi)
				  (let ((ok t))
				    (while (and ok (cdr chars))
				      (let* ((a (char-code (char-downcase (car chars))))
				             (b (char-code (char-downcase (car (cdr chars)))))
				             (s (cond ((< a b) -1) ((> a b) 1) (t 0))))
				        (if (and (>= s lo) (<= s hi))
				            (setq chars (cdr chars))
				            (setq ok nil))))
				    ok))
				""");
		SOURCES.put(LispNames.CHAR_LESSP, """
				(defun char-lessp (character &rest more-characters)
				  (%char-fold-chain (cons character more-characters) -1 -1))
				""");
		SOURCES.put(LispNames.CHAR_GREATERP, """
				(defun char-greaterp (character &rest more-characters)
				  (%char-fold-chain (cons character more-characters) 1 1))
				""");
		SOURCES.put(LispNames.CHAR_NOT_GREATERP, """
				(defun char-not-greaterp (character &rest more-characters)
				  (%char-fold-chain (cons character more-characters) -1 0))
				""");
		SOURCES.put(LispNames.CHAR_NOT_LESSP, """
				(defun char-not-lessp (character &rest more-characters)
				  (%char-fold-chain (cons character more-characters) 0 1))
				""");
		SOURCES.put(LispNames.CHAR_NOT_EQUAL, """
				(defun char-not-equal (character &rest more-characters)
				  (let ((rest (cons character more-characters))
				        (ok t))
				    (while rest
				      (let ((a (char-code (char-downcase (car rest)))))
				        (dolist (b (cdr rest))
				          (when (= a (char-code (char-downcase b)))
				            (setq ok nil))))
				      (setq rest (cdr rest)))
				    ok))
				""");
		// The graphic / standard character predicates, by code point: 32..126 prints on
		// every backend, and so does everything from 160 up (128..159 is the C1 control
		// block). Newline is NOT graphic but IS standard -- the one place the two
		// predicates disagree, and the reason describe-terminal prints a character's name
		// rather than the character for it.
		SOURCES.put(LispNames.GRAPHIC_CHAR_P, """
				(defun graphic-char-p (character)
				  (let ((n (char-code character)))
				    (if (or (and (> n 31) (< n 127)) (> n 159)) t nil)))
				""");
		SOURCES.put(LispNames.STANDARD_CHAR_P, """
				(defun standard-char-p (character)
				  (let ((n (char-code character)))
				    (if (or (and (> n 31) (< n 127)) (= n 10)) t nil)))
				""");
		// The printer entry point: CL specifies write's keywords as BINDINGS of the
		// printer
		// control variables around one print, so that is literally what this does -- the
		// keywords rontolisp models take effect, the ones it does not are inert because
		// the
		// variable they bind is inert (.kb/pretty-printer.md). Only :escape / :readably
		// change the text, and they pick between the two conversions every backend has.
		SOURCES.put(LispNames.WRITE, """
				(defun write (object &key (stream *standard-output*)
				                          (escape *print-escape*) (readably *print-readably*)
				                          (pretty *print-pretty*) (circle *print-circle*)
				                          (right-margin *print-right-margin*)
				                          (miser-width *print-miser-width*)
				                          (lines *print-lines*)
				                          (pprint-dispatch *print-pprint-dispatch*))
				  (let ((*print-escape* escape) (*print-readably* readably)
				        (*print-pretty* pretty) (*print-circle* circle)
				        (*print-right-margin* right-margin) (*print-miser-width* miser-width)
				        (*print-lines* lines) (*print-pprint-dispatch* pprint-dispatch))
				    (write-string (if (or *print-escape* *print-readably*)
				                      (prin1-to-string object)
				                      (princ-to-string object))
				                  stream))
				  object)
				""");
		SOURCES.put(LispNames.PPRINT, """
				(defun pprint (object &optional (stream *standard-output*))
				  (terpri stream)
				  (write object :stream stream :escape t :pretty t)
				  (values))
				""");
		// A conditional line break needs the stream's current column; no backend tracks
		// one, so only :mandatory breaks and the other three kinds are no-ops. That is
		// the
		// SAME rule the format logical block follows (.kb/format.md), and it is why
		// *print-right-margin* is accepted and ignored rather than partly honored.
		SOURCES.put(LispNames.PPRINT_NEWLINE, """
				(defun pprint-newline (kind &optional (stream *standard-output*))
				  (when (and *print-pretty* (eq kind :mandatory))
				    (terpri stream))
				  nil)
				""");
		SOURCES.put(LispNames.PPRINT_INDENT, """
				(defun pprint-indent (relative-to n &optional (stream *standard-output*))
				  nil)
				""");
		SOURCES.put(LispNames.PPRINT_TAB, """
				(defun pprint-tab (kind colnum colinc &optional (stream *standard-output*))
				  nil)
				""");
		// A pprint dispatch table is a one-element LIST holding the entry list, so
		// set-pprint-dispatch can mutate a table it was handed (rplaca) -- the whole
		// point
		// of the (copy-pprint-dispatch) + set-pprint-dispatch idiom esrap builds its
		// result
		// printer with. Each entry is (type-specifier function priority).
		SOURCES.put(LispNames.COPY_PPRINT_DISPATCH, """
				(defun copy-pprint-dispatch (&optional (table *print-pprint-dispatch*))
				  (list (if (consp table) (copy-list (car table)) nil)))
				""");
		SOURCES.put(LispNames.SET_PPRINT_DISPATCH, """
				(defun set-pprint-dispatch (type-specifier function
				                            &optional (priority 0) (table *print-pprint-dispatch*))
				  (when (consp table)
				    (let ((kept nil))
				      (dolist (entry (car table))
				        (unless (equal (car entry) type-specifier)
				          (setq kept (cons entry kept))))
				      (when function
				        (setq kept (cons (list type-specifier function priority) kept)))
				      (rplaca table (reverse kept))))
				  nil)
				""");
		SOURCES.put(LispNames.PPRINT_DISPATCH, """
				(defun pprint-dispatch (object &optional (table *print-pprint-dispatch*))
				  (let ((best nil))
				    (when (consp table)
				      (dolist (entry (car table))
				        (when (and (typep object (car entry))
				                   (or (null best) (> (third entry) (third best))))
				          (setq best entry))))
				    (if best
				        (values (second best) t)
				        (values #'%pprint-dispatch-default nil))))
				""");
		SOURCES.put(LispNames.PPRINT_DISPATCH_DEFAULT, """
				(defun %pprint-dispatch-default (stream object)
				  (write object :stream stream))
				""");
	}

	private static final Map<String, List<LispVal>> CACHE = new ConcurrentHashMap<>();

	// Entry key (member name) -> the name its own defun defines, in the spelling the
	// program's resolved copy uses for the same symbol: RONTOLISP:ALIST-HASH-TABLE for a
	// rontolisp: entry, bare EQUALP for a cl one (a bundled library source is a resolver
	// fixed point, so its raw spelling IS the canonical one).
	private static final Map<String, String> DEFINED_NAMES = new ConcurrentHashMap<>();

	private LispPreludeLibrary() {
	}

	// Package-private: LibraryDefunPruner keys the prelude defuns as prunable too.
	static java.util.Set<String> names() {
		return SOURCES.keySet();
	}

	/**
	 * Returns the parsed definition for a prelude function. Parsed once and cached.
	 * @param name a prelude function name (bare)
	 * @return the library forms (a single {@code defun})
	 */
	public static List<LispVal> formsFor(String name) {
		return CACHE.computeIfAbsent(member(name), n -> {
			String source = SOURCES.get(n);
			if (source == null) {
				throw new IllegalArgumentException(n + " is not a prelude function");
			}
			return LispReader.readAllFromString(source, Features.INTERPRETER);
		});
	}

	/**
	 * Returns whether {@code name} designates a prelude function (bare or
	 * package-qualified).
	 * @param name a resolved function name
	 * @return true if it is a prelude function
	 */
	public static boolean isPreludeFunction(String name) {
		return SOURCES.containsKey(member(name));
	}

	/**
	 * The compile-path pre-pass: for each prelude function the program references but
	 * does not define itself, prepends its {@code defun}. A program that uses none is
	 * returned unchanged.
	 *
	 * <p>
	 * Selection runs on a {@link PackageResolver#resolveProgram(List) resolved} copy of
	 * the program, so a reference and a definition are matched as the SYMBOLS they are,
	 * not by member name: {@code alexandria:alist-hash-table} (a real defun in the
	 * alexandria every second quicklisp system pulls in) is a different function from
	 * {@code rontolisp:alist-hash-table}, and neither one shadows the other. Matching by
	 * member name made the alexandria defun suppress the splice, and the program's
	 * {@code rl:alist-hash-table} call then compiled to a call-time "undefined function"
	 * error. When the program does not resolve (a package error is not this pass's to
	 * report -- the backends run the identical resolution first thing) selection falls
	 * back to the member-name matching.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the referenced prelude definitions spliced in
	 */
	public static List<LispVal> process(List<LispVal> program) {
		List<LispVal> resolved;
		boolean canonical;
		try {
			resolved = new PackageResolver().resolveProgram(program);
			canonical = true;
		}
		catch (LispPackageException ex) {
			resolved = program;
			canonical = false;
		}
		// A prelude definition may itself call another one (string< and its nine
		// siblings all go through %string-compare), so the selection runs to a fixpoint:
		// a name pulled in for its own sake drags in whatever IT references. The
		// interpreter gets this for free -- it resolves prelude names lazily at call
		// time, one at a time.
		java.util.Set<String> referenced = new java.util.LinkedHashSet<>();
		boolean grew = true;
		while (grew) {
			grew = false;
			for (String name : SOURCES.keySet()) {
				String defined = definedName(name);
				if (referenced.contains(name) || definesName(resolved, defined, canonical)) {
					continue;
				}
				boolean used = referencesName(resolved, defined, canonical)
						|| referencedBySurfaceForm(name, resolved, canonical);
				for (String pulled : referenced) {
					// Prelude sources spell each other exactly as they define
					// themselves, so the entry-to-entry edges stay member-matched:
					// they carry no package ambiguity to resolve.
					used = used || referencesName(formsFor(pulled), name, false);
				}
				if (used) {
					referenced.add(name);
					grew = true;
				}
			}
		}
		if (referenced.isEmpty()) {
			return program;
		}
		List<LispVal> out = new ArrayList<>();
		// Emit in SOURCES order, not discovery order, so the spliced prefix stays stable.
		for (String name : SOURCES.keySet()) {
			if (referenced.contains(name)) {
				out.addAll(formsFor(name));
			}
		}
		out.addAll(program);
		return out;
	}

	/**
	 * Whether an entry the program never NAMES is nonetheless reached from it.
	 *
	 * <p>
	 * {@code %make-broadcast-stream} is the one such entry:
	 * {@code LispMacroExpander.expandMakeBroadcastStream} produces the call, and that
	 * runs inside the expression compilers -- long after this pass. Selection therefore
	 * keys on the SURFACE form the expansion answers to, and on its ARITY, because the
	 * two shapes lower differently: a component-less {@code (make-broadcast-stream)}
	 * becomes the string-output-stream sink and must keep splicing nothing (it would
	 * otherwise drag the Gray protocol into every program that merely wanted a discarding
	 * sink -- and into every pipeline that runs this pass without
	 * {@code GrayStreamsLibrary.process}), while a call WITH components becomes the Gray
	 * stream defined here.
	 *
	 * <p>
	 * {@code LibraryDefunPruner} consults the same predicate, for the same reason: the
	 * reference the tree-shaker would look for does not exist yet either.
	 * @param entry the prelude entry key
	 * @param program the resolved program
	 * @param canonical whether the program resolved (see {@link #matches})
	 * @return whether the entry must be spliced
	 */
	static boolean referencedBySurfaceForm(String entry, List<LispVal> program, boolean canonical) {
		return LispNames.MAKE_BROADCAST_STREAM_INTERNAL.equals(entry)
				&& callsWithArguments(program, LispNames.MAKE_BROADCAST_STREAM, canonical);
	}

	private static boolean callsWithArguments(List<LispVal> program, String name, boolean canonical) {
		for (LispVal form : program) {
			if (callsWithArguments(form, name, canonical)) {
				return true;
			}
		}
		return false;
	}

	private static boolean callsWithArguments(LispVal form, String name, boolean canonical) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head && matches(head.name(), name, canonical)
				&& cons.cdr() instanceof LispCons) {
			return true;
		}
		return callsWithArguments(cons.car(), name, canonical) || callsWithArguments(cons.cdr(), name, canonical);
	}

	/**
	 * The name an entry's own {@code defun} defines, cached per entry.
	 */
	private static String definedName(String key) {
		return DEFINED_NAMES.computeIfAbsent(key, k -> {
			for (LispVal form : formsFor(k)) {
				String name = defunName(form);
				if (name != null && k.equals(member(name))) {
					return name;
				}
			}
			// Defensive: an entry whose source does not define its own key.
			return k;
		});
	}

	@Nullable private static String defunName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& (LispNames.DEFUN.equals(member(op.name())) || LispNames.ASYNC_DEFUN.equals(member(op.name())))
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol defName) {
			return defName.name();
		}
		return null;
	}

	private static boolean definesName(List<LispVal> program, String name, boolean canonical) {
		for (LispVal form : program) {
			String defined = defunName(form);
			if (defined != null && matches(defined, name, canonical)) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesName(List<LispVal> program, String name, boolean canonical) {
		for (LispVal form : program) {
			if (referencesName(form, name, canonical)) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesName(LispVal form, String name, boolean canonical) {
		return switch (form) {
			case LispSymbol sym -> matches(sym.name(), name, canonical);
			case LispCons cons ->
				referencesName(cons.car(), name, canonical) || referencesName(cons.cdr(), name, canonical);
			default -> false;
		};
	}

	/**
	 * Whether a symbol occurrence names the prelude entry: the symbols themselves when
	 * the program resolved (so a same-member symbol of another package is a different
	 * function), member names otherwise.
	 */
	private static boolean matches(String symbolName, String definedName, boolean canonical) {
		return canonical ? symbolName.equals(definedName) : member(symbolName).equals(member(definedName));
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
