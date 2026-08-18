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
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.Version;
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
 * <li>The pathname family -- {@code pathname} / {@code parse-namestring} /
 * {@code namestring} / {@code merge-pathnames} / {@code make-pathname} /
 * {@code probe-file} / {@code truename} plus the {@code %path-ns} unwrap helper: a
 * pathname VALUE is an instance carrying its namestring ({@code LispLayout.PATHNAME}), so
 * every public entry coerces its argument to the namestring, computes on strings, and
 * wraps the answer back into a pathname; {@code truename} is {@code probe-file} plus a
 * signal on a missing file, which is what makes the {@code (ignore-errors (truename x))}
 * existence-probe idiom work.</li>
 * <li>{@code file-namestring} / {@code directory-namestring} / {@code host-namestring} --
 * the string-valued halves of a namestring, split where {@code %pathname-split} splits it
 * (so the first two concatenate back to the whole namestring) and {@code ""} for the host
 * a rontolisp namestring does not carry.</li>
 * <li>{@code nstring-upcase} / {@code nstring-downcase} / {@code nstring-capitalize} --
 * the DESTRUCTIVE case family: the fold comes from the non-destructive sibling and
 * {@code %nstring-replace} writes it back into the argument.</li>
 * <li>The environment-enquiry family ({@code lisp-implementation-type} /
 * {@code -version}, {@code software-type} / {@code -version}, {@code machine-type} /
 * {@code -version} / {@code machine-instance}, {@code short-site-name} /
 * {@code long-site-name}) -- a per-backend constant each; only {@code machine-type}
 * differs between backends, through the {@code %target-machine-type} primitive. See
 * {@code .kb/time-environment-builtins.md} for what each one answers and why.</li>
 * <li>{@code directory} -- the directory-LISTING primitive, one rendering of the pattern
 * / prefix / kind / ordering rules over the single {@code %list-directory} primitive each
 * backend implements. uiop's listing family sits on top of it in
 * {@code uiop-filesystem.lisp}.</li>
 * <li>{@code %temp-file-name} -- the uniqueness rule {@code uiop:with-temporary-file}'s
 * expansion calls (smart-buffer's disk-spill path). The uiop half of that trio is
 * {@code UiopLibrary}'s.</li>
 * <li>{@code char-name} -- the standard character names ({@code Space}, {@code Newline},
 * ...), a {@code U+XXXX} label for other non-printing code points, nil for graphic
 * characters; mirrors the interpreter's Java primitive.</li>
 * <li>{@code rontolisp:read-all} -- an {@code rontolisp:async-defun} draining an
 * asynchronous stream into one string: string chunks concatenated, octet chunks (every
 * HTTP body stream's) joined by {@code %octets-join} and UTF-8 decoded by
 * {@code %octets-to-string} -- the lenient decoder the interpreter mirrors natively.</li>
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
		// special-operator-p / macro-function partition the operators with no function
		// value between them, from ONE definition each: the 25 ANSI special operators
		// answer t here, everything else rontolisp expands (a cl macro, or a CL macro it
		// implements as a special form of its own) answers a macro function below. Both
		// tables are generated from PackageRegistry so they cannot drift from the
		// expander they describe.
		SOURCES.put(LispNames.SPECIAL_OPERATOR_P, """
				(defun special-operator-p (symbol)
				  (if (member symbol '(%s)) t nil))
				""".formatted(nameTable(PackageRegistry.ansiSpecialOperatorNames().stream().sorted().toList())));
		// The compiled backends have no macro table left (every macro is expanded before
		// a backend sees the program), so the answer is a NAME test against the baked
		// table plus the program's own macro names -- UserMacroExpander overrides this
		// defun with one passing them in. The interpreter never reaches it: it defines
		// the real macro-function eagerly, over the expander table it still holds.
		SOURCES.put(LispNames.MACRO_FUNCTION, """
				(defun macro-function (symbol &optional environment)
				  (%macro-fn symbol nil))
				""");
		SOURCES.put(LispNames.MACRO_FN_INTERNAL, """
				(defun %%macro-fn (symbol names)
				  (if (or (member symbol names) (member symbol '(%s)))
				      #'%%macro-expander-stub
				      nil))
				""".formatted(nameTable(PackageRegistry.runtimeMacroNames())));
		// What macro-function answers with on the compiled backends: non-nil (which is
		// all a caller deciding "can I apply this" reads) and a signal when CALLED,
		// because the expander it stands for is gone.
		SOURCES.put(LispNames.MACRO_EXPANDER_STUB, """
				(defun %macro-expander-stub (form &optional environment)
				  (error "macro-function: a compiled program cannot expand a macro at run time"))
				""");
		// A compiled image has no macro table, so a form built at RUNTIME that is not a
		// macro call is already its own expansion: that identity answer is what lets a
		// portable code walker (ironclad's trivial-macroexpand-all) compile. A literal
		// quoted argument never reaches these -- UserMacroExpander folds it to the real
		// expansion at compile time -- and the interpreter defines the real functions
		// eagerly, so the prelude only ever serves the compiled backends.
		//
		// A form whose operator IS a macro signals instead of answering itself, and that
		// is the same answer macro-function's expander stub gives: the two must agree,
		// or the standard "expand until it stops expanding" loop (rove's form-steps)
		// spins forever here -- macro-function keeps saying "macro" while an identity
		// macroexpand-1 never makes progress. A signal is a wrong answer a caller can
		// see and handle; silence is one it cannot.
		SOURCES.put(LispNames.MACROEXPAND, """
				(defun macroexpand (form &optional environment)
				  (if (and (consp form) (macro-function (car form)))
				      (error "macroexpand: a compiled program cannot expand a macro at run time")
				      (values form nil)))
				""");
		SOURCES.put(LispNames.MACROEXPAND_1, """
				(defun macroexpand-1 (form &optional environment)
				  (if (and (consp form) (macro-function (car form)))
				      (error "macroexpand-1: a compiled program cannot expand a macro at run time")
				      (values form nil)))
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
				          ((string= s "%PATHNAME") 'pathname)
				          (t c))))
				""");
		// class-name reads the metaobject's name slot -- index 0 of the seeded
		// standard-class slot-order contract (ClosRegistry.ensureMopClassesSeeded),
		// the same read the closer-mop shim's class-name does.
		SOURCES.put(LispNames.CLASS_NAME, """
				(defun class-name (class)
				  (%obj-ref class 0))
				""");
		// symbol-plist carries its own copy of the store's defvar: defvar assigns only
		// when
		// unbound, so the two entries can both be spliced and the second one is a no-op.
		SOURCES.put(LispNames.SYMBOL_PLIST, """
				(defvar %symbol-plists nil)
				(defun symbol-plist (symbol)
				  (cdr (assoc symbol %symbol-plists)))
				""");
		// remprop: the plist REMOVER, over the same store -- its own copy of the
		// defvar for the same reason symbol-plist carries one (a program may drop a
		// property without ever calling get). rove's remove-test is (remprop name
		// 'test).
		SOURCES.put(LispNames.REMPROP, """
				(defvar %symbol-plists nil)
				(defun remprop (symbol indicator)
				  (let ((entry (assoc symbol %symbol-plists)))
				    (if entry
				        (do ((prev nil tail)
				             (tail (cdr entry) (cddr tail)))
				            ((null tail) nil)
				          (when (eq (car tail) indicator)
				            (if prev
				                (rplacd (cdr prev) (cddr tail))
				                (rplacd entry (cddr tail)))
				            (return t)))
				        nil)))
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
		// The uiop:getenv OVERRIDE store: what (setf (uiop:getenv name) value) writes
		// and what uiop:getenv reads BEFORE the host. No backend can rewrite its own
		// process environment -- the JVM cannot at all, WASI's is read-only -- so the
		// write is a per-program overlay, one definition for all four backends
		// (.kb/uiop.md). A nil value is an UNSET (upstream's unsetenv semantics), which
		// is why the reader answers the whole ENTRY rather than its cdr: "present and
		// nil" and "absent" are different answers. Each entry carries the store's own
		// defvar, like the %symbol-plists family -- defvar assigns only when unbound, so
		// two spliced copies are still one store.
		SOURCES.put(LispNames.GETENV_OVERRIDE, """
				(defvar %getenv-overrides nil)
				(defun %getenv-override (%go-name)
				  (assoc %go-name %getenv-overrides :test #'equal))
				""");
		SOURCES.put(LispNames.GETENV_OVERRIDE_SET, """
				(defvar %getenv-overrides nil)
				(defun %getenv-override-set (%gos-name %gos-value)
				  (let ((%gos-entry (assoc %gos-name %getenv-overrides :test #'equal)))
				    (if %gos-entry
				        (rplacd %gos-entry %gos-value)
				        (setq %getenv-overrides
				              (cons (cons %gos-name %gos-value) %getenv-overrides)))
				    %gos-value))
				""");
		// merge-pathnames / truename: namestring work over primitives every backend
		// has, so ONE Lisp definition serves all four. The pattern of the whole path
		// family: coerce a pathname-or-namestring argument through %path-ns
		// (or the strict namestring) at the entry, compute on the namestring, and wrap
		// the answer in a pathname VALUE with (pathname ...) at the exit -- internals
		// like %pathname-split stay string-typed. The merge rule is the same one
		// PathnameOps.mergePathnames implements; the two are pinned against each other
		// by LispPreludeLibraryTest.
		SOURCES.put(LispNames.MERGE_PATHNAMES, """
				(defun merge-pathnames (%mp-path &optional %mp-defaults)
				  (let* ((%mp-pp (%path-ns %mp-path))
				         (%mp-dd (%path-ns %mp-defaults))
				         (p (if (stringp %mp-pp) %mp-pp ""))
				         (d (if (stringp %mp-dd) %mp-dd ""))
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
				    (pathname (concatenate 'string dir (if (string= pfile "") dfile pfile)))))
				""");
		// %path-ns: the LENIENT designator unwrap -- a pathname value's namestring,
		// anything else unchanged -- so the family below keeps its "a non-string
		// coerces to the empty namestring" tolerance while accepting pathname values.
		// namestring is the STRICT sibling (it signals on a non-designator).
		SOURCES.put(LispNames.PATH_NS, """
				(defun %path-ns (%pns-x)
				  (if (%obj-is %pns-x '%PATHNAME) (%obj-ref %pns-x 0) %pns-x))
				""");
		// pathname: the canonical constructor every producer funnels through. The
		// value is an instance of the fixed %PATHNAME layout carrying its namestring,
		// which is what pathnamep / (typep x 'pathname) test.
		SOURCES.put(LispNames.PATHNAME, """
				(defun pathname (%pth-x)
				  (cond ((%obj-is %pth-x '%PATHNAME) %pth-x)
				        ((stringp %pth-x) (%obj-new '%PATHNAME %pth-x))
				        (t (error "PATHNAME: not a pathname designator: ~S" %pth-x))))
				""");
		// parse-namestring -- lite: no host parsing (a rontolisp namestring has no
		// host), so the value is the pathname over the whole namestring and the
		// second value is its length, like CL's success case.
		SOURCES.put(LispNames.PARSE_NAMESTRING, """
				(defun parse-namestring (%psn-thing &optional %psn-host %psn-defaults)
				  (let ((%psn-s (namestring %psn-thing)))
				    (values (pathname %psn-s) (length %psn-s))))
				""");
		// %pathname-split: the ONE rendering of CL's "the LAST dot separates the type,
		// and a dot at position 0 does not" rule -- (directory name type), with name and
		// type nil when absent. pathname-name / pathname-type / make-pathname all read
		// it, so the three cannot disagree; PathnameOps.components is the Java twin the
		// compile-time folder uses and LispPreludeLibraryTest pins them against each
		// other.
		SOURCES.put(LispNames.PATHNAME_SPLIT, """
				(defun %pathname-split (%ps-path)
				  (let* ((%ps-x (%path-ns %ps-path))
				         (%ps-p (if (stringp %ps-x) %ps-x ""))
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
		// The three components a rontolisp namestring does not model. Every one answers
		// nil -- the answer CL prescribes for a component that is not present, and the
		// one SBCL gives on Unix for :device and :version -- after validating the
		// argument through the strict namestring, so a non-designator signals exactly
		// where the rest of the family does. rove's resolve-file is the caller
		// (.todo/372 row 13): it pops a directory component only for a device that is
		// neither nil nor :unspecific.
		SOURCES.put(LispNames.PATHNAME_HOST, """
				(defun pathname-host (%phost-path &rest %phost-args)
				  (progn (namestring %phost-path) nil))
				""");
		SOURCES.put(LispNames.PATHNAME_DEVICE, """
				(defun pathname-device (%pdev-path &rest %pdev-args)
				  (progn (namestring %pdev-path) nil))
				""");
		SOURCES.put(LispNames.PATHNAME_VERSION, """
				(defun pathname-version (%pver-path &rest %pver-args)
				  (progn (namestring %pver-path) nil))
				""");
		// %wild-component-p: the ONE "this component is wild" rule -- it holds a * or a
		// ? -- so wild-pathname-p cannot disagree with what %wild-match actually treats
		// as a wildcard.
		SOURCES.put(LispNames.WILD_COMPONENT_P, """
				(defun %wild-component-p (%wc-s)
				  (and (stringp %wc-s)
				       (or (position #\\* %wc-s) (position #\\? %wc-s))
				       t))
				""");
		SOURCES.put(LispNames.WILD_PATHNAME_P, """
				(defun wild-pathname-p (%wp-path &optional %wp-field)
				  (let* ((%wp-parts (%pathname-split (namestring %wp-path)))
				         (%wp-dir (first %wp-parts))
				         (%wp-name (second %wp-parts))
				         (%wp-type (third %wp-parts)))
				    (cond ((null %wp-field)
				           (or (%wild-component-p %wp-dir)
				               (%wild-component-p %wp-name)
				               (%wild-component-p %wp-type)))
				          ((eq %wp-field :directory) (%wild-component-p %wp-dir))
				          ((eq %wp-field :name) (%wild-component-p %wp-name))
				          ((eq %wp-field :type) (%wild-component-p %wp-type))
				          ((or (eq %wp-field :host) (eq %wp-field :device) (eq %wp-field :version)) nil)
				          (t (error "WILD-PATHNAME-P: unknown field key ~S" %wp-field)))))
				""");
		// enough-namestring: the inverse of the merge above it. merge-pathnames prefixes
		// a relative namestring with the defaults' DIRECTORY, so the shortest namestring
		// that still names the same file is the path with that directory prefix removed
		// -- and when the path does not start with it, nothing can be dropped and the
		// whole namestring is the answer (which is what CL falls back to as well). The
		// value is a STRING, as CL specifies, not a pathname.
		SOURCES.put(LispNames.ENOUGH_NAMESTRING, """
				(defun enough-namestring (%en-path &optional (%en-defaults *default-pathname-defaults*))
				  (let* ((%en-p (namestring %en-path))
				         (%en-d (namestring %en-defaults))
				         (%en-s (position #\\/ %en-d :from-end t))
				         (%en-dir (if %en-s (subseq %en-d 0 (+ %en-s 1)) ""))
				         (%en-n (length %en-dir)))
				    (if (and (> %en-n 0)
				             (<= %en-n (length %en-p))
				             (string= %en-dir (subseq %en-p 0 %en-n)))
				        (subseq %en-p %en-n)
				        %en-p)))
				""");
		// file-namestring / directory-namestring: the two halves of a namestring, split
		// at the SAME place %pathname-split splits it (its first element is a literal
		// prefix of the namestring), so the pair concatenates back to the namestring for
		// every path and neither can drift from pathname-name / pathname-type. A
		// namestring that names a directory ("d/") has an empty file-namestring, and one
		// with no slash at all ("a.txt") an empty directory-namestring -- both what CL
		// answers. Values are STRINGS, as CL specifies.
		SOURCES.put(LispNames.FILE_NAMESTRING, """
				(defun file-namestring (%fns-path)
				  (let ((%fns-p (namestring %fns-path)))
				    (subseq %fns-p (length (first (%pathname-split %fns-p))))))
				""");
		SOURCES.put(LispNames.DIRECTORY_NAMESTRING, """
				(defun directory-namestring (%dns-path)
				  (first (%pathname-split (namestring %dns-path))))
				""");
		// host-namestring: the empty string, the STRING counterpart of pathname-host's
		// nil -- a rontolisp namestring has no host syntax, so there is no host name to
		// render, and CL requires a string here. SBCL answers "" on Unix as well.
		SOURCES.put(LispNames.HOST_NAMESTRING, """
				(defun host-namestring (%hns-path)
				  (progn (namestring %hns-path) ""))
				""");
		// %wild-captures: the CAPTURING twin of %wild-match -- one matcher rule, two
		// answers. Each wildcard contributes the substring it consumed (one character
		// for a ?), left to right; :no-match is the failure answer, which no capture
		// list can collide with. * is tried SHORTEST first, so (translate-pathname
		// "a/b.c" "*/*.*" "x/*.*") substitutes "b" and "c" rather than letting the
		// first star swallow the rest.
		SOURCES.put(LispNames.WILD_CAPTURES, """
				(defun %wild-captures (%wcp-pat %wcp-str)
				  (let ((%wcp-pn (length %wcp-pat)) (%wcp-sn (length %wcp-str)))
				    (labels ((m (p s acc)
				               (cond ((>= p %wcp-pn) (if (>= s %wcp-sn) (reverse acc) :no-match))
				                     ((char= (char %wcp-pat p) #\\*)
				                      (let ((r :no-match) (e s))
				                        (do () ((or (not (eq r :no-match)) (> e %wcp-sn)) r)
				                          (setq r (m (+ p 1) e (cons (subseq %wcp-str s e) acc)))
				                          (setq e (+ e 1)))))
				                     ((>= s %wcp-sn) :no-match)
				                     ((char= (char %wcp-pat p) #\\?)
				                      (m (+ p 1) (+ s 1) (cons (subseq %wcp-str s (+ s 1)) acc)))
				                     ((char= (char %wcp-pat p) (char %wcp-str s))
				                      (m (+ p 1) (+ s 1) acc))
				                     (t :no-match))))
				      (m 0 0 nil))))
				""");
		SOURCES.put(LispNames.TRANSLATE_PATHNAME, """
				(defun translate-pathname (%tp-source %tp-from %tp-to &rest %tp-args)
				  (let ((%tp-caps (%wild-captures (namestring %tp-from) (namestring %tp-source))))
				    (if (eq %tp-caps :no-match)
				        (error "TRANSLATE-PATHNAME: ~A does not match ~A"
				               (namestring %tp-source) (namestring %tp-from))
				        (let* ((%tp-t (namestring %tp-to))
				               (%tp-n (length %tp-t))
				               (%tp-acc "")
				               (%tp-i 0))
				          (do () ((>= %tp-i %tp-n) (pathname %tp-acc))
				            (let ((%tp-c (char %tp-t %tp-i)))
				              (if (or (char= %tp-c #\\*) (char= %tp-c #\\?))
				                  (progn
				                    (setq %tp-acc (concatenate 'string %tp-acc
				                                               (if %tp-caps (car %tp-caps) "")))
				                    (setq %tp-caps (cdr %tp-caps)))
				                  (setq %tp-acc (concatenate 'string %tp-acc
				                                             (subseq %tp-t %tp-i (+ %tp-i 1)))))
				              (setq %tp-i (+ %tp-i 1))))))))
				""");
		// Every rontolisp pathname is PHYSICAL: there are no logical hosts, so no
		// translation table exists to consult and the translation is the identity --
		// which is what CL prescribes for a physical argument. logical-pathname is the
		// honest other half: CL requires a type-error unless the argument names a
		// logical pathname, and here nothing can, so it always signals rather than
		// pretending a physical namestring is a logical one.
		SOURCES.put(LispNames.TRANSLATE_LOGICAL_PATHNAME, """
				(defun translate-logical-pathname (%tlp-path &rest %tlp-args)
				  (pathname %tlp-path))
				""");
		SOURCES.put(LispNames.LOGICAL_PATHNAME, """
				(defun logical-pathname (%lp-thing)
				  (error "LOGICAL-PATHNAME: ~S does not name a logical pathname (rontolisp defines no logical hosts)"
				         %lp-thing))
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
				  (let* ((%mkp-ds (%path-ns defaults))
				         (%mkp-d (%pathname-split (if (stringp %mkp-ds) %mkp-ds "")))
				         (%mkp-dir (if %mkp-dp (%pathname-directory-string directory) (first %mkp-d)))
				         (%mkp-n (if %mkp-np (%pathname-component-string name) (or (second %mkp-d) "")))
				         (%mkp-t (if %mkp-tp (%pathname-component-string type) (or (third %mkp-d) ""))))
				    (pathname
				      (concatenate 'string %mkp-dir
				                   (if (string= %mkp-t "")
				                       %mkp-n
				                       (concatenate 'string %mkp-n "." %mkp-t))))))
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
				  (if (%delete-file (namestring %dfl-path))
				      t
				      (error "DELETE-FILE: cannot delete ~A" %dfl-path)))
				""");
		// rename-file: the signalling ANSI surface over the %rename-file primitive (nil
		// when the source is not there or the host refused), the delete-file shape one
		// argument wider. CL merges the new name with the old one, so
		// (rename-file "d/a.txt" "b.txt") lands on "d/b.txt"; the answer is the
		// defaulted new name as a pathname. Lite: CL's other two values (the old and new
		// truenames) are not returned -- a prelude defun's secondary values do not
		// survive the function boundary on the compile paths (LispNames.RENAME_FILE).
		SOURCES.put(LispNames.RENAME_FILE, """
				(defun rename-file (%rnf-file %rnf-new-name)
				  (let ((%rnf-from (namestring %rnf-file))
				        (%rnf-to (namestring (merge-pathnames %rnf-new-name %rnf-file))))
				    (if (%rename-file %rnf-from %rnf-to)
				        (pathname %rnf-to)
				        (error "RENAME-FILE: cannot rename ~A to ~A" %rnf-from %rnf-to))))
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
		// %synonym-target: the ONE resolution of a stream DESIGNATOR through a synonym
		// stream. A synonym stream is a value (LispLayout.SYNONYM_STREAM) whose reserved
		// cell holds a zero-argument closure reading the variable it names, so calling it
		// answers that variable's value AS OF NOW -- the per-operation forwarding CL
		// prescribes -- and the recursion carries a synonym over a synonym. Reached from
		// both compile-path seams (Jvm/Wasm streamArg, via
		// StreamDesignators.throughSynonym)
		// and from every gray.lisp dispatch helper, which must resolve BEFORE its %obj-p
		// test or a synonym stream would take the CLOS arm.
		SOURCES.put(LispNames.SYNONYM_TARGET, """
				(defun %synonym-target (%st-s)
				  (if (%obj-is %st-s '%SYNONYM-STREAM)
				      (%synonym-target (funcall (%obj-ref %st-s 1)))
				      %st-s))
				""");
		SOURCES.put(LispNames.SYNONYM_STREAM_SYMBOL, """
				(defun synonym-stream-symbol (%sss-s)
				  (if (%obj-is %sss-s '%SYNONYM-STREAM)
				      (%obj-ref %sss-s 0)
				      (error "SYNONYM-STREAM-SYMBOL: not a synonym stream: ~S" %sss-s)))
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
		// package-shadowing-symbols: always nil -- rontolisp has no symbol shadowing
		// (defpackage's :shadow records names for RESOLUTION and mints no shadowing
		// symbol; the runtime shadow/shadowing-import are documented non-goals,
		// .kb/packages.md). The designator still goes through package-name, so an
		// unknown package signals exactly as it does there.
		SOURCES.put(LispNames.PACKAGE_SHADOWING_SYMBOLS, """
				(defun package-shadowing-symbols (%pss-pkg)
				  (progn (package-name %pss-pkg) nil))
				""");
		SOURCES.put(LispNames.NAMESTRING_CL, """
				(defun namestring (%ns-path)
				  (cond ((stringp %ns-path) %ns-path)
				        ((%obj-is %ns-path '%PATHNAME) (%obj-ref %ns-path 0))
				        (t (error "NAMESTRING: not a pathname designator: ~S" %ns-path))))
				""");
		SOURCES.put(LispNames.TRUENAME, """
				(defun truename (%tn-path)
				  (or (probe-file %tn-path)
				      (error "TRUENAME: no such file")))
				""");
		// probe-file: the pathname VALUE over the namestring the %probe-file primitive
		// answers (the primitive stays string-in/string-out per backend), nil when the
		// file is not there. Takes both designator spellings, like every path-taking
		// operator.
		SOURCES.put(LispNames.PROBE_FILE, """
				(defun probe-file (%pf-path)
				  (let ((%pf-r (%probe-file (namestring %pf-path))))
				    (if %pf-r (pathname %pf-r) nil)))
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
				  (let* ((%pd-x (%path-ns %pd-path))
				         (%pd-p (if (stringp %pd-x) %pd-x ""))
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
				  (let* ((%dn-x (%path-ns %dn-path))
				         (%dn-p (if (stringp %dn-x) %dn-x "")))
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
				  (let* ((%ede-x (%path-ns %ede-path))
				         (%ede-p (if (stringp %ede-x) %ede-x ""))
				         (%ede-s (position #\\/ %ede-p :from-end t))
				         (%ede-d (if %ede-s (subseq %ede-p 0 (+ %ede-s 1)) "")))
				    (if (string= %ede-d "") %ede-path (progn (%make-directories %ede-d) %ede-path))))
				""");
		SOURCES.put(LispNames.DIRECTORY, """
				(defun directory (%dir-spec)
				  (let* ((%dir-x (%path-ns %dir-spec))
				         (%dir-p (if (stringp %dir-x) %dir-x ""))
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
				          (mapcar #'pathname (sort %dir-acc #'string<)))
				        (let ((%dir-f (%dir-namestring %dir-p)))
				          (cond ((%list-directory (if (string= %dir-f "") "." %dir-f)) (list (pathname %dir-f)))
				                ((and (string/= %dir-n "") (probe-file %dir-p)) (list (pathname %dir-p)))
				                (t nil))))))
				""");
		// The uniqueness rule behind uiop:with-temporary-file: create the directory,
		// then draw random names until one names nothing. Deliberately NOT seeded from
		// the clock -- (random n) is the one entropy source every backend shares.
		SOURCES.put(LispNames.TEMP_FILE_NAME, """
				(defun %temp-file-name (%tfn-dir %tfn-prefix %tfn-type)
				  (let ((%tfn-d (namestring (uiop/pathname:ensure-directory-pathname
				                              (or %tfn-dir (uiop/stream:default-temporary-directory)))))
				        (%tfn-p (or %tfn-prefix "tmp"))
				        (%tfn-t (if %tfn-type (concatenate 'string "." %tfn-type) ""))
				        (%tfn-n nil))
				    (ensure-directories-exist %tfn-d)
				    (do () (%tfn-n %tfn-n)
				      (let ((%tfn-c (concatenate 'string %tfn-d %tfn-p
				                                 (write-to-string (random 1000000000))
				                                 %tfn-t)))
				        (unless (probe-file %tfn-c) (setq %tfn-n %tfn-c))))))
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
		// %octets-join: a list of (unsigned-byte 8) vectors -> ONE packed vector holding
		// them in order. The blit every drain of an octet-chunk stream needs (read-all
		// below, %http-drain in http-server.lisp): the chunks are collected and blitted
		// once rather than concatenated as they arrive, so a body pulled in n chunks
		// costs one copy, not n -- and a single chunk (the JVM's fetch answers its whole
		// body as one) costs none. The per-chunk copy is `replace`: native on the
		// interpreter (an interpreted per-byte loop over a document-sized body cost
		// seconds), and on wasm the destination is provably an array so the site calls
		// the narrow %replace-runtime-array arm (.kb/sequence-op-runtimes.md; measured
		// +0.5 KB on a serve component). http-server.lisp's %http-octets-join is the
		// same defun again, because that library is prelude-free by rule.
		SOURCES.put(LispNames.OCTETS_JOIN_INTERNAL, """
				(defun rontolisp::%octets-join (chunks total)
				  (if (and chunks (null (cdr chunks)))
				      (car chunks)
				      (let ((out (make-array total :element-type '(unsigned-byte 8))) (k 0))
				        (dolist (v chunks)
				          (replace out v :start1 k)
				          (setq k (+ k (length v))))
				        out)))
				""");
		// %octets-to-string: an (unsigned-byte 8) vector -> the string its UTF-8 bytes
		// spell, LENIENTLY: a byte that leads no valid sequence, and a sequence the
		// vector truncates, become their own characters, so malformed input never
		// signals -- the rule http-server.lisp's request decoder applies, so a body
		// decodes the same whichever transport carried it. The interpreter mirrors this
		// in Java arm for arm (Environment); the compile paths compile this defun.
		//
		// The per-byte loop is the FALLBACK. %octets-to-string-strict is native on every
		// backend (a platform decoder on the interpreter and the JVM, a validate-then-
		// array.copy runtime function on wasm) and answers nil on anything that is not
		// valid UTF-8, so a well-formed body -- every real one -- decodes at the speed of
		// a copy and only malformed bytes pay the loop. The two agree by construction:
		// where the input is well formed the strict answer IS what the arms below build,
		// which is what lets the fast path be taken without a second rule to keep in
		// step (LispPreludeLibraryTest pins it).
		//
		// The 4-byte arm re-tests the code point it just assembled: an #xF5.. lead, and
		// an #xF4 one whose continuation carries the sequence past U+10FFFF, spell no
		// character at all, so they take the "own character" arm like any other byte
		// that leads nothing. Without that test the arm handed code-char a value outside
		// the Unicode range, and the backends disagreed about what that meant -- a hard
		// error on the JVM (which is the one thing this decoder promises never to do),
		// an out-of-range character on wasm, while the interpreter's Java mirror had the
		// range test all along (Character.isValidCodePoint).
		SOURCES.put(LispNames.OCTETS_TO_STRING_INTERNAL, """
				(defun rontolisp::%octets-to-string (v)
				  (or
				   (rontolisp::%octets-to-string-strict v)
				   (let ((n (length v)))
				     (with-output-to-string (s)
				       (let ((i 0))
				         (while (< i n)
				           (let ((b (aref v i))
				                 (b1 (if (< (+ i 1) n) (aref v (+ i 1)) nil))
				                 (b2 (if (< (+ i 2) n) (aref v (+ i 2)) nil))
				                 (b3 (if (< (+ i 3) n) (aref v (+ i 3)) nil)))
				             (cond ((< b #x80)
				                    (write-char (code-char b) s)
				                    (setq i (+ i 1)))
				                   ((and (>= b #xC0) (< b #xE0) b1)
				                    (write-char
				                     (code-char (logior (ash (logand b #x1F) 6) (logand b1 #x3F)))
				                     s)
				                    (setq i (+ i 2)))
				                   ((and (>= b #xE0) (< b #xF0) b1 b2)
				                    (write-char (code-char
				                                 (logior (ash (logand b #x0F) 12)
				                                         (ash (logand b1 #x3F) 6)
				                                         (logand b2 #x3F))) s)
				                    (setq i (+ i 3)))
				                   ((and (>= b #xF0) (< b #xF8) b1 b2 b3)
				                    (let ((cp (logior (ash (logand b #x07) 18)
				                                      (ash (logand b1 #x3F) 12)
				                                      (ash (logand b2 #x3F) 6)
				                                      (logand b3 #x3F))))
				                      (if (<= cp #x10FFFF)
				                          (progn (write-char (code-char cp) s)
				                                 (setq i (+ i 4)))
				                          (progn (write-char (code-char b) s)
				                                 (setq i (+ i 1))))))
				                   (t
				                    (write-char (code-char b) s)
				                    (setq i (+ i 1)))))))))))
				""");
		// read-all: the stream drained into ONE string. A STRING passes through: a body
		// that has already fully arrived (the declared absent-body default, a user
		// plist) is its own drained value, so the one drain spelling works whatever
		// shape :body took. String chunks (a guest make-stream) are concatenated
		// through a string output stream rather than pairwise (that is quadratic in the
		// body size); OCTET chunks -- what every HTTP body stream answers, so a relayed
		// body crosses byte-exact -- are joined once and UTF-8 decoded, which is what
		// keeps a document-shaped consumer reading text off a byte stream. A stream
		// mixing the two kinds is an error rather than a guess.
		SOURCES.put(LispNames.READ_ALL, """
				(rontolisp:async-defun rontolisp:read-all (s)
				  (if (stringp s)
				      s
				      (let ((chunks nil) (octets nil) (text nil) (total 0)
				            (chunk (rontolisp:await (rontolisp:stream-read s))))
				        (while chunk
				          (if (stringp chunk) (setq text t) (setq octets t))
				          (setq total (+ total (length chunk)))
				          (setq chunks (cons chunk chunks))
				          (setq chunk (rontolisp:await (rontolisp:stream-read s))))
				        (setq chunks (nreverse chunks))
				        (cond ((and octets text)
				               (error "read-all: the stream mixes string and octet chunks"))
				              (octets
				               (rontolisp::%octets-to-string
				                (rontolisp::%octets-join chunks total)))
				              (t
				               (with-output-to-string (out)
				                 (dolist (c chunks) (write-string c out))))))))
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
		// The environment-enquiry family (CLHS 25.1.5). Every one of them is a CONSTANT
		// per backend, so they are prelude defuns rather than per-backend built-ins, and
		// only machine-type actually differs between backends (through the
		// %target-machine-type primitive below). The choice of answers -- and why the
		// unknowable ones are nil rather than a fabricated string -- is
		// .kb/time-environment-builtins.md; a caller composing a User-Agent out of them
		// must not get a different string per backend by accident, which is why the
		// version is BAKED here from the compiling build rather than read at run time.
		SOURCES.put(LispNames.LISP_IMPLEMENTATION_TYPE, """
				(defun lisp-implementation-type () "rontolisp")
				""");
		SOURCES.put(LispNames.LISP_IMPLEMENTATION_VERSION,
				"(defun lisp-implementation-version () \"" + Version.getVersion() + "\")\n");
		// software-type: the SAME claim uiop/os makes unconditionally (os-unix-p is t,
		// operating-system is :unix -- every backend presents the POSIX-shaped file
		// model), so the CL spelling and the uiop one cannot contradict each other.
		SOURCES.put(LispNames.SOFTWARE_TYPE, """
				(defun software-type () "Unix")
				""");
		SOURCES.put(LispNames.SOFTWARE_VERSION, """
				(defun software-version () nil)
				""");
		// machine-type: the ABI the artifact targets, not the host CPU -- a class file
		// and a wasm module are both CPU-independent, which is the same reason
		// uiop:architecture answers :jvm / :wasm32 rather than the processor.
		SOURCES.put(LispNames.MACHINE_TYPE, """
				(defun machine-type () (%target-machine-type))
				""");
		SOURCES.put(LispNames.MACHINE_VERSION, """
				(defun machine-version () nil)
				""");
		SOURCES.put(LispNames.MACHINE_INSTANCE, """
				(defun machine-instance () nil)
				""");
		SOURCES.put(LispNames.SHORT_SITE_NAME, """
				(defun short-site-name () nil)
				""");
		SOURCES.put(LispNames.LONG_SITE_NAME, """
				(defun long-site-name () nil)
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
		// tree-equal: same SHAPE, leaves matching under :test / :test-not. A cons is
		// only ever equal to a cons -- the "exactly one side is a cons" arm is what stops
		// the walk from comparing a leaf with a subtree, and it is why the atom arm can
		// compare nil with nil (the end of two equally long lists) without a special
		// case. The CDR direction is a LOOP, not a recursion: recursing there costs one
		// frame per element, and two 10,000-element flat lists then overflow the stack
		// on every backend. Only the CAR direction recurses, so the depth is the tree's
		// nesting depth.
		SOURCES.put(LispNames.TREE_EQUAL, """
				(defun tree-equal (tree-1 tree-2 &key (test #'eql) test-not)
				  (labels ((leaf= (a b)
				             (if test-not
				                 (not (funcall test-not a b))
				                 (if (funcall test a b) t nil)))
				           (cmp (a b)
				             (let ((ok t)
				                   (done nil))
				               (while (not done)
				                 (cond ((and (consp a) (consp b))
				                        (if (cmp (car a) (car b))
				                            (progn (setq a (cdr a)) (setq b (cdr b)))
				                            (progn (setq ok nil) (setq done t))))
				                       ((or (consp a) (consp b)) (setq ok nil) (setq done t))
				                       (t (setq ok (leaf= a b)) (setq done t))))
				               ok)))
				    (cmp tree-1 tree-2)))
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
		// count-if-not takes the full CL keyword set, unlike count-if (whose two-argument
		// expansion is inlined per site; .todo/006 records that gap). :from-end only
		// reorders the predicate calls, which cannot change a count, so it is accepted
		// and the scan stays forward. A LIST is walked with a cursor rather than indexed
		// with elt -- elt on a list is an nth walk from the head, so the obvious loop
		// would be quadratic.
		SOURCES.put(LispNames.COUNT_IF_NOT, """
				(defun count-if-not (predicate sequence &key from-end (start 0) end key)
				  (let* ((lst (listp sequence))
				         (e (or end (length sequence)))
				         (i (or start 0))
				         (cell (if lst (nthcdr i sequence) nil))
				         (n 0))
				    (while (and (< i e) (or (not lst) cell))
				      (let ((x (if lst (car cell) (elt sequence i))))
				        (unless (funcall predicate (if key (funcall key x) x))
				          (setq n (+ n 1))))
				      (setq cell (cdr cell))
				      (setq i (+ i 1)))
				    n))
				""");
		// set-exclusive-or: the symmetric difference. Both scans call the shared matcher
		// with the list-1 element FIRST, so an asymmetric :test/:test-not sees the same
		// argument order in either direction. The result lists the list-1-only elements
		// in order, then the list-2-only ones (CL leaves the order unspecified).
		SOURCES.put(LispNames.SET_XOR_MATCH, """
				(defun %set-xor-match (a b test test-not key)
				  (let ((ka (if key (funcall key a) a))
				        (kb (if key (funcall key b) b)))
				    (if test-not
				        (not (funcall test-not ka kb))
				        (if (funcall test ka kb) t nil))))
				""");
		SOURCES.put(LispNames.SET_EXCLUSIVE_OR, """
				(defun set-exclusive-or (list-1 list-2 &key (test #'eql) test-not key)
				  (let ((out nil))
				    (dolist (a list-1)
				      (let ((found nil))
				        (dolist (b list-2)
				          (when (%set-xor-match a b test test-not key) (setq found t)))
				        (unless found (setq out (cons a out)))))
				    (dolist (b list-2)
				      (let ((found nil))
				        (dolist (a list-1)
				          (when (%set-xor-match a b test test-not key) (setq found t)))
				        (unless found (setq out (cons b out)))))
				    (nreverse out)))
				""");
		// merge: the classic two-cursor walk, STABLE -- a tie takes from sequence-1,
		// which is what "(funcall predicate <sequence-2 element> <sequence-1 element>)"
		// decides: only a strictly-precedes answer moves the sequence-2 cursor. Both
		// inputs are read as lists and the result is built to result-type through
		// coerce, so a run-time type specifier works and the families are coerce's
		// (list / vector / string). Non-destructive, which CL permits.
		SOURCES.put(LispNames.MERGE, """
				(defun merge (result-type sequence-1 sequence-2 predicate &key key)
				  (let ((a (coerce sequence-1 'list))
				        (b (coerce sequence-2 'list))
				        (out nil))
				    (while (and a b)
				      (if (funcall predicate (if key (funcall key (car b)) (car b))
				                   (if key (funcall key (car a)) (car a)))
				          (progn (setq out (cons (car b) out)) (setq b (cdr b)))
				          (progn (setq out (cons (car a) out)) (setq a (cdr a)))))
				    (while a (setq out (cons (car a) out)) (setq a (cdr a)))
				    (while b (setq out (cons (car b) out)) (setq b (cdr b)))
				    (coerce (nreverse out) result-type)))
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
		// The DESTRUCTIVE case family. The fold itself is delegated to the
		// non-destructive sibling, so nstring-upcase and string-upcase cannot answer
		// different characters; what the n- spelling adds is the write BACK into the
		// argument, which is the whole point of the name (a caller that keeps a
		// reference to the string sees the change). %nstring-replace performs it with
		// (setf (aref s i) c) and answers what that write chain left behind: the SAME
		// object for a mutable character vector on every backend, and -- on the compile
		// paths only -- a rebuilt string for an IMMUTABLE one, the lite edge
		// .kb/string-write-runtime.md already documents for every indexed write. The
		// value is correct on all four backends either way, which is what chunga's
		// (intern (nstring-upcase s) :keyword) consumes.
		SOURCES.put(LispNames.NSTRING_REPLACE, """
				(defun %nstring-replace (%nsr-s %nsr-folded)
				  (let ((%nsr-n (length %nsr-s)) (%nsr-i 0))
				    (do () ((>= %nsr-i %nsr-n) %nsr-s)
				      (setf (aref %nsr-s %nsr-i) (char %nsr-folded %nsr-i))
				      (setq %nsr-i (+ %nsr-i 1)))))
				""");
		SOURCES.put(LispNames.NSTRING_UPCASE, """
				(defun nstring-upcase (%nsu-s)
				  (%nstring-replace %nsu-s (string-upcase %nsu-s)))
				""");
		SOURCES.put(LispNames.NSTRING_DOWNCASE, """
				(defun nstring-downcase (%nsd-s)
				  (%nstring-replace %nsd-s (string-downcase %nsd-s)))
				""");
		SOURCES.put(LispNames.NSTRING_CAPITALIZE, """
				(defun nstring-capitalize (%nsc-s)
				  (%nstring-replace %nsc-s (string-capitalize %nsc-s)))
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
		// *print-case*: the ONE case-applying renderer every backend prints through when
		// the program mentions the variable (LispMacroExpander rewrites the printing
		// operators onto it). It walks the value rather than the rendered text because
		// only SYMBOL spellings are cased -- a string element keeps its own characters,
		// and a character prints as itself. What it does NOT walk is the containers whose
		// rendering is a runtime form of its own (a structure, an instance, a hash table,
		// an array of rank != 1, a packed float array): those delegate to the raw
		// conversion, so a symbol nested in one keeps the stored (upper-case) spelling.
		// .kb/pretty-printer.md carries the re-evaluation trigger. The vector guard is
		// the
		// twin of the generated %print-object-str's -- the two walks are never both live
		// in one program (a program with a print-object route walks THERE and hands this
		// one leaves), so they have to be read together to stay in step.
		SOURCES.put(LispNames.PRINT_CASED_INTERNAL, """
				(defun %print-cased (%pc-x %pc-esc)
				  (if (eq *print-case* :upcase)
				      (if %pc-esc (%prin1-to-string %pc-x) (%princ-to-string %pc-x))
				      (cond ((symbolp %pc-x)
				             (%print-case-fold (if %pc-esc
				                                   (%prin1-to-string %pc-x)
				                                   (%princ-to-string %pc-x))))
				            ((consp %pc-x)
				             (let ((%pc-acc "(") (%pc-cur %pc-x) (%pc-sep ""))
				               (while (consp %pc-cur)
				                 (setq %pc-acc (concatenate 'string %pc-acc %pc-sep
				                                            (%print-cased (car %pc-cur) %pc-esc)))
				                 (setq %pc-sep " ")
				                 (setq %pc-cur (cdr %pc-cur)))
				               (unless (null %pc-cur)
				                 (setq %pc-acc (concatenate 'string %pc-acc " . "
				                                            (%print-cased %pc-cur %pc-esc))))
				               (concatenate 'string %pc-acc ")")))
				            ((and (vectorp %pc-x) (not (stringp %pc-x)) (eql (array-rank %pc-x) 1)
				                  (not (equal (array-element-type %pc-x) 'single-float))
				                  (not (equal (array-element-type %pc-x) 'double-float)))
				             (let ((%pc-acc "#(") (%pc-i 0) (%pc-n (length %pc-x)) (%pc-sep ""))
				               (while (< %pc-i %pc-n)
				                 (setq %pc-acc (concatenate 'string %pc-acc %pc-sep
				                                            (%print-cased (aref %pc-x %pc-i) %pc-esc)))
				                 (setq %pc-sep " ")
				                 (setq %pc-i (+ %pc-i 1)))
				               (concatenate 'string %pc-acc ")")))
				            (%pc-esc (%prin1-to-string %pc-x))
				            (t (%princ-to-string %pc-x)))))
				""");
		// One symbol spelling under the current *print-case*. :downcase is
		// string-downcase (char-downcase leaves a lower-case character alone, which is
		// exactly CLHS's "only the UPPERCASE characters are converted"); :capitalize
		// keeps each word's first character as it stands -- never upcasing a lower-case
		// one, which is where CL's rule parts company with string-capitalize -- and
		// downcases the rest of the word. A word is a run of alphanumerics, so *FOO*
		// capitalizes to *Foo* and A1B2-C3 to A1b2-C3.
		SOURCES.put(LispNames.PRINT_CASE_FOLD_INTERNAL, """
				(defun %print-case-fold (%pcf-s)
				  (cond ((eq *print-case* :downcase) (string-downcase %pcf-s))
				        ((eq *print-case* :capitalize)
				         (let ((%pcf-acc "") (%pcf-i 0) (%pcf-n (length %pcf-s)) (%pcf-word nil))
				           (while (< %pcf-i %pcf-n)
				             (let* ((%pcf-c (char %pcf-s %pcf-i))
				                    (%pcf-a (if (or (alpha-char-p %pcf-c) (digit-char-p %pcf-c)) t nil)))
				               (setq %pcf-acc (concatenate 'string %pcf-acc
				                                           (string (if (and %pcf-a %pcf-word)
				                                                       (char-downcase %pcf-c)
				                                                       %pcf-c))))
				               (setq %pcf-word %pcf-a))
				             (setq %pcf-i (+ %pcf-i 1)))
				           %pcf-acc))
				        (t %pcf-s)))
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

	/**
	 * A name set as the body of a quoted list literal, lowercased so the baked table
	 * reads like source (the reader upcases it back).
	 */
	private static String nameTable(List<String> names) {
		return String.join(" ", names).toLowerCase(java.util.Locale.ROOT);
	}

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
		return process(program, Features.INTERPRETER);
	}

	/**
	 * {@link #process(List)} for a target backend: the feature set is handed to the uiop
	 * splice this pass drives, whose {@code uiop:featurep} answers against the
	 * {@code *features*} of the backend being compiled ({@code UiopLibrary.process}).
	 * Nothing else in the prelude branches on features.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param features the target backend's feature set
	 * @return the program with the referenced prelude definitions spliced in
	 */
	public static List<LispVal> process(List<LispVal> program, Features features) {
		// uiop rides this pass. The two libraries are mutually dependent -- a uiop body
		// calls namestring / pathname / directory here, and %temp-file-name below calls
		// uiop back -- so they are ONE pass with a fixed order, and every pipeline that
		// splices the prelude needs both. Splicing uiop first is what lets the selection
		// below see the prelude names its bodies reach.
		program = UiopLibrary.process(program, features);
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
		if (LispNames.MAKE_BROADCAST_STREAM_INTERNAL.equals(entry)) {
			return callsWithArguments(program, LispNames.MAKE_BROADCAST_STREAM, canonical);
		}
		// The entry uiop:with-temporary-file's EXPANSION calls. Same timing problem as
		// %make-broadcast-stream: the expansion runs inside the expression compilers,
		// long after this pass, so the reference this selection would look for does not
		// exist yet -- without the rule smart-buffer's disk spill compiled to
		// "%TEMP-FILE-NAME is undefined". UiopLibrary.reachedBySurfaceForm makes the
		// mirror-image decision for the uiop half of the same expansion.
		if (LispNames.TEMP_FILE_NAME.equals(entry)) {
			return referencesName(program, LispNames.UIOP_WITH_TEMPORARY_FILE_QUALIFIED, canonical);
		}
		// The uiop lowerings expandUiopStubCall performs inside the expression
		// compilers, after this pass: uiop:file-exists-p becomes (probe-file x) and
		// uiop:namestring / uiop:native-namestring become (namestring x), so a program
		// spelling only the uiop name must still splice the CL definition.
		// %synonym-target has the same timing problem twice over: the compile-path
		// seams insert the call inside the expression compilers, and gray.lisp's
		// dispatch helpers -- which call it unconditionally -- are spliced by
		// GrayStreamsLibrary AFTER this pass. So the selection keys on the two SURFACE
		// facts instead: the program builds a synonym stream, or it uses the Gray
		// protocol (which is exactly when a dispatch helper can be spliced).
		if (LispNames.SYNONYM_TARGET.equals(entry)) {
			return referencesName(program, LispNames.MAKE_SYNONYM_STREAM, canonical)
					|| GrayStreamsLibrary.usesProtocol(program);
		}
		// %print-cased: the printing operators are rewritten onto it inside the
		// expression compilers, after this pass, so the reference this selection would
		// look for does not exist yet either. The surface fact is the program MENTIONING
		// *print-case* -- the same scan that gives the variable its defvar
		// (LispMacroExpander.injectMvSpillGlobal), so the renderer and the variable it
		// reads are spliced together or not at all.
		if (LispNames.PRINT_CASED_INTERNAL.equals(entry)) {
			return referencesName(program, LispNames.PRINT_CASE_VAR, canonical);
		}
		if (LispNames.PROBE_FILE.equals(entry)) {
			// The second producer is load's :if-does-not-exist option: the guard that
			// reads it is built by LispMacroExpander.lowerLoadOptions inside the
			// expression compilers, after this pass, so the surface fact -- the option
			// being written at all -- is what selection can see.
			return referencesUiopMember(program, LispNames.FILE_EXISTS_P, canonical)
					|| am.ik.rontolisp.macro.LispMacroExpander.callsLoadWithIfDoesNotExist(program);
		}
		if (LispNames.NAMESTRING_CL.equals(entry)) {
			return referencesName(program, PackageRegistry.qualify(LispNames.UIOP_PKG, LispNames.NAMESTRING), canonical)
					|| referencesUiopMember(program, LispNames.NATIVE_NAMESTRING, canonical);
		}
		return false;
	}

	/**
	 * Whether the program names a uiop member, in either the {@code uiop:} spelling or
	 * the home sub-package's ({@code UiopExports}) -- this pass runs on a resolved copy,
	 * where the home spelling is the one that survives, but falls back to the written
	 * spelling when resolution failed.
	 */
	private static boolean referencesUiopMember(List<LispVal> program, String member, boolean canonical) {
		return referencesName(program, PackageRegistry.qualify(LispNames.UIOP_PKG, member), canonical)
				|| referencesName(program, UiopExports.qualified(member), canonical);
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
	/**
	 * The name an entry's own {@code defun} defines, in the program's canonical spelling
	 * ({@code UIOP:DELETE-FILE-IF-EXISTS} for the {@code DELETE-FILE-IF-EXISTS} key).
	 * {@link LibraryDefunPruner} roots the synthesized-call entries by it.
	 * @param key the entry key
	 * @return the defined name
	 */
	static String definedName(String key) {
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
