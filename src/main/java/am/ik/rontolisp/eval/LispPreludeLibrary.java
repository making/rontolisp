package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.ArrayElementTypes;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
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
 * <li>{@code ldiff} / {@code sublis} / {@code gentemp} -- the three list-and-symbol
 * functions iterate's own source needs at load time: the elements preceding a given tail
 * (the whole list, dotted tail included, when it is not a tail), a fresh tree with every
 * alist-key subtree replaced ({@code :key}/{@code :test}/{@code :test-not}; the
 * destructive {@code nsublis} is absent), and a fresh INTERNED symbol named prefix plus a
 * counter.</li>
 * <li>{@code equalp} -- like {@code equal} but strings/characters compare case
 * insensitively, numbers by value, and arrays element-wise (same dimensions, elements
 * compared with {@code equalp}); lite (hash-tables/structures fall back to {@code eql}).
 * The array walk and the cons-chain walk are ITERATIVE: the recursion they replaced cost
 * one interpreter frame per element, so comparing two 720-element rank-5 arrays
 * (array-operations' own permute test) overflowed the stack. Only the depth of a NESTED
 * structure recurses now, which is what the shape actually calls for.</li>
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
 * <li>{@code read} plus the {@code %rd-*} scanner family -- CL's {@code read}, which
 * consumes exactly the characters of ONE datum and leaves the stream positioned after
 * them. The scanner only DELIMITS the datum (over {@code read-char} /
 * {@code unread-char}, riding the same one-character pushback cell {@code unread-char}
 * uses); {@code read-from-string} parses the text it collected, so the datum syntax keeps
 * exactly one definition per backend. There is no {@code read} built-in on any backend --
 * {@code .kb/read-load-streams.md}.</li>
 * </ul>
 */
public final class LispPreludeLibrary {

	// name -> canonical-shape source (bare cl names, like json.lisp: needs no package
	// resolution). Order is preserved so prepended definitions keep a stable order.
	private static final Map<String, String> SOURCES = new LinkedHashMap<>();

	static {
		// Two eql instances are equalp before any slot is walked: on wasm-GC a --native
		// program's Objective-C wrapper is eql by address while its handle slots differ
		// (.kb/objc.md, "--native").
		SOURCES.put(LispNames.EQUALP, """
				(defun equalp (a b)
				  (cond ((and (numberp a) (numberp b)) (= a b))
				        ((and (stringp a) (stringp b)) (string-equal a b))
				        ((and (characterp a) (characterp b))
				         (char= (char-downcase a) (char-downcase b)))
				        ((and (consp a) (consp b))
				         (do ((%eqp-a a (cdr %eqp-a))
				              (%eqp-b b (cdr %eqp-b)))
				             ((not (and (consp %eqp-a) (consp %eqp-b)))
				              (equalp %eqp-a %eqp-b))
				           (unless (equalp (car %eqp-a) (car %eqp-b)) (return nil))))
				        ((and (%obj-p a) (%obj-p b))
				         (or (eql a b)
				             (and (equal (%obj-tag a) (%obj-tag b))
				                  (equalp (%obj-slots a) (%obj-slots b)))))
				        ((and (%arrayp a) (%arrayp b))
				         (and (equal (array-dimensions a) (array-dimensions b))
				              (do ((%eqp-i 0 (+ %eqp-i 1))
				                   (%eqp-n (array-total-size a)))
				                  ((>= %eqp-i %eqp-n) t)
				                (unless (equalp (row-major-aref a %eqp-i) (row-major-aref b %eqp-i))
				                  (return nil)))))
				        (t (eql a b))))
				""");
		// ldiff / sublis / gentemp -- the three list-and-symbol functions iterate's own
		// source needs at LOAD time (expand-iterate splits a body's declarations with
		// ldiff, defmacro-clause substitutes its template parameters with sublis, and a
		// clause's dispatch function is named by gentemp). Pure walks over primitives
		// every backend has, so one definition serves all four.
		SOURCES.put(LispNames.LDIFF, """
				(defun ldiff (%ld-list %ld-object)
				  (do ((%ld-tail %ld-list (cdr %ld-tail))
				       (%ld-acc nil))
				      ((atom %ld-tail)
				       (if (eql %ld-tail %ld-object)
				           (nreverse %ld-acc)
				           (append (nreverse %ld-acc) %ld-tail)))
				    (if (eql %ld-tail %ld-object)
				        (return (nreverse %ld-acc))
				        (setq %ld-acc (cons (car %ld-tail) %ld-acc)))))
				""");
		// sublis walks the CDR direction with a loop like tree-equal below; the entry
		// test is still applied to every spine node (the recursive shape called the walk
		// on the cdr, which checked the next node there), and -- as before -- every cons
		// is copied.
		SOURCES.put(LispNames.SUBLIS, """
				(defun sublis (%sb-alist %sb-tree &key key test test-not)
				  (labels ((%sb-entry (%sb-x)
				             (let ((%sb-probe (if key (funcall key %sb-x) %sb-x)))
				               (cond (test-not
				                       (assoc %sb-probe %sb-alist :test-not test-not))
				                     (test
				                       (assoc %sb-probe %sb-alist :test test))
				                     (t (assoc %sb-probe %sb-alist)))))
				           (%sb-walk (%sb-x)
				             (if (consp %sb-x)
				                 (let* ((%sb-head (cons nil nil)) (%sb-tail %sb-head) (%sb-p %sb-x))
				                   (while (and (consp %sb-p) (not (%sb-entry %sb-p)))
				                     (let ((%sb-cell (cons (%sb-walk (car %sb-p)) nil)))
				                       (setf (cdr %sb-tail) %sb-cell)
				                       (setq %sb-tail %sb-cell))
				                     (setq %sb-p (cdr %sb-p)))
				                   (setf (cdr %sb-tail)
				                         (if (consp %sb-p)
				                             (cdr (%sb-entry %sb-p))
				                             (%sb-walk %sb-p)))
				                   (cdr %sb-head))
				                 (let ((%sb-e (%sb-entry %sb-x)))
				                   (if %sb-e (cdr %sb-e) %sb-x)))))
				    (%sb-walk %sb-tree)))
				""");
		SOURCES.put(LispNames.NSUBLIS, """
				(defun nsublis (alist tree &key key test test-not)
				  (sublis alist tree :key key :test test :test-not test-not))
				""");
		// The counter is a defvar for the same reason the %symbol-plists store is one:
		// defvar assigns only when unbound, so the spliced copy never resets a count.
		SOURCES.put(LispNames.GENTEMP, """
				(defvar %gentemp-counter 0)
				(defun gentemp (&optional %gt-prefix %gt-package)
				  (let ((%gt-stem (if %gt-prefix (string %gt-prefix) "T")))
				    (do ((%gt-name nil))
				        (nil)
				      (setq %gentemp-counter (+ %gentemp-counter 1))
				      (setq %gt-name
				            (concatenate 'string %gt-stem (write-to-string %gentemp-counter)))
				      (unless (if %gt-package
				                  (find-symbol %gt-name %gt-package)
				                  (find-symbol %gt-name))
				        (return (if %gt-package
				                    (intern %gt-name %gt-package)
				                    (intern %gt-name)))))))
				""");
		SOURCES.put(LispNames.ALPHANUMERICP, """
				(defun alphanumericp (c)
				  (or (alpha-char-p c) (digit-char-p c)))
				""");
		SOURCES.put(LispNames.MAKE_LOAD_FORM_SAVING_SLOTS, """
				(defun make-load-form-saving-slots (object &key slot-names environment)
				  (declare (ignore slot-names environment))
				  (list* '%obj-new
				         (list 'quote (%obj-tag object))
				         (mapcar (lambda (%mlfss-v) (list 'quote %mlfss-v))
				                 (%obj-slots object))))
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
				         ;; The spine is folded with a LOOP -- recursing on the cdr put one
				         ;; frame per element on the stack. Each level combined as
				         ;; (logand (+ (* 31 (sxhash car)) <rest>) most-positive-fixnum);
				         ;; mod distributes over the +, so the fold can run leftward taking
				         ;; the mod each step and answer exactly what the recursion did.
				         (let ((h 0) (p obj))
				           (while (consp p)
				             (setq h (logand (+ h (* 31 (sxhash (car p)))) most-positive-fixnum))
				             (setq p (cdr p)))
				           (logand (+ h (sxhash p)) most-positive-fixnum)))
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
		// The eleven bit-* array-logic operators, over the general boxed bit-vector
		// representation (a #* literal or a (make-array ... :element-type 'bit) of any
		// rank). Three private helpers carry the validation and the result handling so
		// the eleven defuns are one loop each: %bit-array-p recognizes a bit array of
		// any rank (bit-vector-p is rank-1 only, while the operators take any rank),
		// %bit-check-2 answers the shared dimensions or signals, %bit-result-2 answers
		// the array to write into (a fresh bit array for a nil result, the first input
		// destructively for t, or the supplied result array after checking it is a bit
		// array of the same dimensions). The loops walk row-major-aref, which is
		// rank-blind, so a rank-n bit array works the way CL requires (.todo/043).
		SOURCES.put("%BIT-ARRAY-P", """
				(defun %bit-array-p (x)
				  (and (arrayp x) (equal (array-element-type x) 'bit)))
				""");
		SOURCES.put("%BIT-CHECK-2", """
				(defun %bit-check-2 (name a b)
				  (if (not (%bit-array-p a))
				      (error "~a: first argument is not a bit array: ~s" name a)
				      (if (not (%bit-array-p b))
				          (error "~a: second argument is not a bit array: ~s" name b)
				          (let ((da (array-dimensions a))
				                (db (array-dimensions b)))
				            (if (not (equal da db))
				                (error "~a: bit arrays have different dimensions: ~s and ~s" name da db)
				                da)))))
				""");
		SOURCES.put("%BIT-RESULT-2", """
				(defun %bit-result-2 (name first dims result)
				  (cond ((null result) (make-array dims :element-type 'bit))
				        ((eq result t) first)
				        ((not (%bit-array-p result))
				         (error "~a: result is not a bit array: ~s" name result))
				        ((not (equal (array-dimensions result) dims))
				         (error "~a: result has different dimensions: ~s, expected ~s"
				                name (array-dimensions result) dims))
				        (t result)))
				""");
		SOURCES.put(LispNames.BIT_AND, """
				(defun bit-and (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-and a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-and a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (logand (row-major-aref a i) (row-major-aref b i))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_IOR, """
				(defun bit-ior (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-ior a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-ior a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (logior (row-major-aref a i) (row-major-aref b i))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_XOR, """
				(defun bit-xor (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-xor a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-xor a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (logxor (row-major-aref a i) (row-major-aref b i))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_EQV, """
				(defun bit-eqv (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-eqv a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-eqv a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (- 1 (logxor (row-major-aref a i) (row-major-aref b i)))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_NAND, """
				(defun bit-nand (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-nand a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-nand a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (- 1 (logand (row-major-aref a i) (row-major-aref b i)))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_NOR, """
				(defun bit-nor (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-nor a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-nor a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (- 1 (logior (row-major-aref a i) (row-major-aref b i)))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_ANDC1, """
				(defun bit-andc1 (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-andc1 a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-andc1 a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (logand (- 1 (row-major-aref a i)) (row-major-aref b i))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_ANDC2, """
				(defun bit-andc2 (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-andc2 a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-andc2 a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (logand (row-major-aref a i) (- 1 (row-major-aref b i)))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_ORC1, """
				(defun bit-orc1 (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-orc1 a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-orc1 a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (logior (- 1 (row-major-aref a i)) (row-major-aref b i))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_ORC2, """
				(defun bit-orc2 (a b &optional result)
				  (let ((dims (%bit-check-2 'bit-orc2 a b))
				        (n (array-total-size a)))
				    (let ((out (%bit-result-2 'bit-orc2 a dims result)))
				      (dotimes (i n)
				        (setf (row-major-aref out i)
				              (logior (row-major-aref a i) (- 1 (row-major-aref b i)))))
				      out)))
				""");
		SOURCES.put(LispNames.BIT_NOT, """
				(defun bit-not (a &optional result)
				  (if (not (%bit-array-p a))
				      (error "bit-not: argument is not a bit array: ~s" a)
				      (let ((dims (array-dimensions a))
				            (n (array-total-size a)))
				        (let ((out (%bit-result-2 'bit-not a dims result)))
				          (dotimes (i n)
				            (setf (row-major-aref out i) (- 1 (row-major-aref a i))))
				          out))))
				""");
		// simple-vector-p/bit-vector-p/simple-bit-vector-p: exactly what the
		// matching typep answers, so the predicate and the specifier cannot
		// drift apart. No bit-vector value exists yet (a :element-type 'bit
		// array is a plain vector), so the two bit spellings answer nil for
		// every value today; the functions exist so portable code calling
		// them loads (.todo/043).
		SOURCES.put(LispNames.SIMPLE_VECTOR_P, """
				(defun simple-vector-p (x)
				  (typep x 'simple-vector))
				""");
		SOURCES.put(LispNames.BIT_VECTOR_P, """
				(defun bit-vector-p (x)
				  (typep x 'bit-vector))
				""");
		SOURCES.put(LispNames.SIMPLE_BIT_VECTOR_P, """
				(defun simple-bit-vector-p (x)
				  (typep x 'simple-bit-vector))
				""");
		// array-in-bounds-p: the bounds check without signaling. Nil for a
		// non-array, a rank/subscript-count mismatch or any out-of-range
		// subscript; t only when every subscript is a valid index. Strings
		// count (they are rank-1 character arrays), and the dimension -- not
		// the fill pointer -- is what a subscript is checked against, matching
		// array-dimensions on every backend (.todo/043).
		SOURCES.put(LispNames.ARRAY_IN_BOUNDS_P, """
				(defun array-in-bounds-p (array &rest subscripts)
				  (if (not (arrayp array))
				      nil
				      (let ((dims (array-dimensions array)))
				        (if (not (= (length dims) (length subscripts)))
				            nil
				            (do ((d dims (cdr d))
				                 (s subscripts (cdr s)))
				                ((null d) t)
				              (if (or (not (integerp (car s)))
				                      (< (car s) 0)
				                      (>= (car s) (car d)))
				                  (return nil)))))))
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
		// because the expander it stands for is gone. A macro function takes EXACTLY
		// two arguments (CLHS 3.2.1: the form and the environment), so the lambda list
		// is NOT optional -- calling this with 0, 1 or 4 arguments must reach the
		// ordinary arity check (a program-error on every backend,
		// .kb/symbol-runtime-api.md)
		// before the body's own signal, exactly like SBCL 2.2.9's answer.
		SOURCES.put(LispNames.MACRO_EXPANDER_STUB, """
				(defun %macro-expander-stub (form environment)
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
		SOURCES.put(LispNames.STREAM_ERROR_STREAM, """
				(defun stream-error-stream (condition) (slot-value condition 'stream))
				""");
		SOURCES.put(LispNames.FILE_ERROR_PATHNAME, """
				(defun file-error-pathname (condition) (slot-value condition 'pathname))
				""");
		// Undoes the |...|-framing todo 626 gave prin1-to-string's spelling of a symbol
		// whose name is not upcase-invariant. type-of and symbol-package both read a
		// KNOWN internal tag's prefix or a qualifier's colon off prin1-to-string's text
		// (below), and the %struct-/%class- tag prefix is always lowercase, so an
		// unqualified tag now round-trips as e.g. "|%struct-PT|" -- this peels exactly
		// one leading/trailing '|' back off so the prefix match still sees "%struct-PT".
		// Does not undo interior backslash-doubling: neither caller's input (a tag
		// prefix, a qualifier, a reader-upcased name) can itself contain '|' or '\'.
		SOURCES.put(LispNames.UNESCAPED_SYMBOL_TEXT_INTERNAL, """
				(defun %unescaped-symbol-text (s)
				  (if (and (> (length s) 1) (char= (char s 0) #\\|))
				      (subseq s 1 (1- (length s)))
				      s))
				""");
		// A "package" is the upcased canonical package name as a keyword (there are no
		// package objects), so symbol-package reads the qualifier off the symbol's
		// stored spelling: prin1-to-string keeps it, unlike symbol-name. The
		// interpreter overrides this with the registry-backed version (which
		// distinguishes cl from cl-user); the compiled backends have no registry at
		// runtime, so every bare symbol answers CL-USER here.
		SOURCES.put(LispNames.SYMBOL_PACKAGE, """
				(defun symbol-package (symbol)
				  (let* ((s (%unescaped-symbol-text (%prin1-to-string symbol)))
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
		//
		// An ARRAY has no designator of its own (every representation answers T), so
		// its type is BUILT here, as the COMPOUND specifier CL requires -- T carries no
		// information at all, and nothing could tell a vector from a matrix. The shapes
		// are SBCL's: a simple general rank-1 array is (simple-vector N), a
		// fill-pointered or adjustable one (vector ELEMENT-TYPE SIZE), everything else
		// (simple-array ELEMENT-TYPE DIMENSIONS) -- the rank-0 array included, whose
		// dimension list is nil. The element type is array-element-type's UPGRADED
		// answer, so a (make-array n :element-type 'fixnum) reads back as t; the same
		// specifier makeTypeTest builds a test for, which is what keeps
		// (typep a (type-of a)) true. The arm fires only where the designator is the
		// uninformative T, which is also what keeps a CHARACTER array out of it: a
		// rank-1 character array is a string value on the interpreter and a marked
		// general array on the compile paths, and both designate STRING -- so all four
		// backends answer STRING for it rather than (simple-array character (n)).
		//
		// The SIMPLICITY arm comes first: a fill-pointered, adjustable or DISPLACED
		// array is not simple whatever it holds, so (make-array 4 :element-type
		// 'double-float :fill-pointer 0) is (vector double-float 4) and not a
		// simple-array. It asks %simple-array-p, one TOTAL predicate that answers for
		// every representation -- including the displacement, which
		// array-has-fill-pointer-p / adjustable-array-p cannot see at all and which
		// used to make a displaced array read as (SIMPLE-VECTOR 2). A NON-simple array
		// is (vector ELEMENT-TYPE SIZE) at rank 1 and (array ELEMENT-TYPE DIMENSIONS)
		// above it, SBCL 2.2.9's answer for all three shapes -- and that is what keeps
		// (typep a (type-of a)) true now that typep checks simplicity.
		SOURCES.put(LispNames.TYPE_OF, """
				(defun type-of (object)
				  (let* ((c (%class-designator object))
				         (s (%unescaped-symbol-text (%prin1-to-string c)))
				         (n (length s)))
				    (cond ((and (> n 8) (string= (subseq s 0 8) "%struct-")) (intern (subseq s 8)))
				          ((and (> n 7) (string= (subseq s 0 7) "%class-")) (intern (subseq s 7)))
				          ((string= s "%PATHNAME") 'pathname)
				          ((and (string= s "T") (%arrayp object))
				           (let ((et (array-element-type object))
				                 (dims (array-dimensions object)))
				             (cond ((not (%simple-array-p object))
				                    (if (= (length dims) 1)
				                        (if (equal et 'bit)
				                            (list 'bit-vector (car dims))
				                            (list 'vector et (car dims)))
				                        (list 'array et dims)))
				                   ((and (eq et t) (= (length dims) 1)) (list 'simple-vector (car dims)))
				                   ((and (equal et 'bit) (= (length dims) 1))
				                    (list 'simple-bit-vector (car dims)))
				                   (t (list 'simple-array et dims)))))
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
				(defun merge-pathnames (%mp-path &optional %mp-defaults %mp-default-version)
				  (declare (ignore %mp-default-version))
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
				(defun parse-namestring (%psn-thing &optional %psn-host %psn-defaults
				                         &key ((:start %psn-start) 0) ((:end %psn-end)) ((:junk-allowed %psn-junk)))
				  (declare (ignore %psn-host %psn-defaults %psn-junk))
				  (let* ((%psn-s (namestring %psn-thing))
				         (%psn-e (or %psn-end (length %psn-s))))
				    (values (pathname (subseq %psn-s %psn-start %psn-e)) %psn-e)))
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
		// A component that is exactly "*" answers :wild -- the keyword make-pathname
		// builds it from, so decomposition is the inverse of construction here as it is
		// for the directory list. %pathname-split itself stays string-typed: it is what
		// directory's matcher and make-pathname's :defaults defaulting read.
		SOURCES.put(LispNames.PATHNAME_NAME, """
				(defun pathname-name (%pn-path)
				  (let ((%pn-n (second (%pathname-split %pn-path))))
				    (if (equal %pn-n "*") :wild %pn-n)))
				""");
		SOURCES.put(LispNames.PATHNAME_TYPE, """
				(defun pathname-type (%pt-path)
				  (let ((%pt-t (third (%pathname-split %pt-path))))
				    (if (equal %pt-t "*") :wild %pt-t)))
				""");
		// The three components a rontolisp namestring does not model. Every one answers
		// nil -- the answer CL prescribes for a component that is not present, and the
		// one SBCL gives on Unix for :device and :version -- after validating the
		// argument through the strict namestring, so a non-designator signals exactly
		// where the rest of the family does. rove's resolve-file is the caller: it
		// pops a directory component only for a device that is neither nil nor
		// :unspecific.
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
		// %wild-inferiors-at: the ONE spelling of "a wild-inferiors segment starts here",
		// read by %wild-match and %wild-captures; translate-pathname's directory matcher
		// reads the SAME token component-wise (a component that is exactly "**", the
		// separator belonging to it as here). The token is THREE characters -- ** plus
		// the separator -- because it matches ZERO levels as well as many, and only
		// swallowing the separator lets "/a/**/*.lisp" match "/a/c.lisp" the way CL does.
		SOURCES.put(LispNames.WILD_INFERIORS_AT, """
				(defun %wild-inferiors-at (%wia-pat %wia-p)
				  (and (< (+ %wia-p 2) (length %wia-pat))
				       (char= (char %wia-pat %wia-p) #\\*)
				       (char= (char %wia-pat (+ %wia-p 1)) #\\*)
				       (char= (char %wia-pat (+ %wia-p 2)) #\\/)
				       t))
				""");
		// %wild-captures: the CAPTURING twin of %wild-match -- one matcher rule, two
		// answers. Each wildcard contributes the substring it consumed (one character
		// for a ?, the whole run of directory levels for a **/), left to right;
		// :no-match is the failure answer, which no capture list can collide with. * is
		// tried SHORTEST first, so (translate-pathname "a/b.c" "*/*.*" "x/*.*")
		// substitutes "b" and "c" rather than letting the first star swallow the rest,
		// and a **/ likewise tries zero levels before one. translate-pathname calls it
		// WITHIN one component at a time (the run-consuming **/ branch only fires inside
		// a pattern that still carries separators, which the component split leaves in
		// the directory matcher's hands).
		SOURCES.put(LispNames.WILD_CAPTURES, """
				(defun %wild-captures (%wcp-pat %wcp-str)
				  (let ((%wcp-pn (length %wcp-pat)) (%wcp-sn (length %wcp-str)))
				    (labels ((m (p s acc)
				               (cond ((>= p %wcp-pn) (if (>= s %wcp-sn) (reverse acc) :no-match))
				                     ((%wild-inferiors-at %wcp-pat p)
				                      (let ((%wcp-r :no-match) (%wcp-e s) (%wcp-done nil))
				                        (do () ((or (not (eq %wcp-r :no-match)) %wcp-done) %wcp-r)
				                          (setq %wcp-r
				                                (m (+ p 3) %wcp-e
				                                   (cons (subseq %wcp-str s %wcp-e) acc)))
				                          (when (eq %wcp-r :no-match)
				                            (let ((%wcp-k (position #\\/ %wcp-str :start %wcp-e)))
				                              (if %wcp-k
				                                  (setq %wcp-e (+ %wcp-k 1))
				                                  (setq %wcp-done t)))))))
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
		// translate-pathname -- the COMPONENT-WISE translation (todo-447), a port of
		// SBCL's translate-component / translate-directories rule onto the flat
		// namestring model: source, from and to are split with %pathname-split +
		// %path-dir-parts and handled DIRECTORY to DIRECTORY, NAME to NAME, TYPE to
		// TYPE, so each to component receives the captures of its OWN from component.
		// Per component (mirroring SBCL): a NIL or exactly-* TO component answers the
		// source component; a TO component holding wildcards is filled from the from
		// component's %wild-captures (an exactly-* FROM contributes the whole source
		// component); a literal FROM answers the source when it equals it and fails
		// otherwise; a literal TO answers itself when the source matches FROM. In the
		// DIRECTORY: a NIL from-or-to directory takes the source's whole directory, a
		// NIL source directory takes the TO's (minus any **), and a ** component
		// consumes a RUN of source directories as one capture -- zero levels tried
		// first, the %wild-inferiors-at token rendered component-wise. Lite where CL
		// is undefined and SBCL signals: a TO component with more wildcard SLOTS than
		// there are captures substitutes "" once the captures run out (SBCL's "not
		// enough wildcards", ":WILD-INFERIORS not paired" and "middle of a word"
		// errors are not reproduced), and consecutive wildcards consume one capture
		// each rather than one share. A failed MATCH in any slot is the error every
		// other path operator signals from.
		SOURCES.put(LispNames.TRANSLATE_PATHNAME,
				"""
						(defun translate-pathname (%tp-source %tp-from %tp-to &rest %tp-args)
						  (let* ((%tp-sp (%pathname-split (namestring %tp-source)))
						         (%tp-fp (%pathname-split (namestring %tp-from)))
						         (%tp-tp (%pathname-split (namestring %tp-to))))
						    (labels ((%tp-match-dirs (%tp-f %tp-s)
						               (cond ((null %tp-f) (if (null %tp-s) nil :no-match))
						                     ((string= (car %tp-f) "**")
						                      (let ((%tp-r :no-match) (%tp-s2 %tp-s) (%tp-run "") (%tp-done nil))
						                        (do () ((or (not (eq %tp-r :no-match)) %tp-done) %tp-r)
						                          (let ((%tp-x (%tp-match-dirs (cdr %tp-f) %tp-s2)))
						                            (if (eq %tp-x :no-match)
						                                (if (null %tp-s2)
						                                    (setq %tp-done t)
						                                    (progn
						                                      (setq %tp-run
						                                            (concatenate 'string %tp-run (car %tp-s2) "/"))
						                                      (setq %tp-s2 (cdr %tp-s2))))
						                                (setq %tp-r (cons %tp-run %tp-x)))))))
						                     ((null %tp-s) :no-match)
						                     (t (let ((%tp-c (%wild-captures (car %tp-f) (car %tp-s))))
						                          (if (eq %tp-c :no-match)
						                              :no-match
						                              (let ((%tp-r (%tp-match-dirs (cdr %tp-f) (cdr %tp-s))))
						                                (if (eq %tp-r :no-match)
						                                    :no-match
						                                    (append %tp-c %tp-r))))))))
						             (%tp-sub (%tp-pat %tp-caps)
						               (let ((%tp-n (length %tp-pat)) (%tp-i 0) (%tp-out "") (%tp-c %tp-caps))
						                 (do () ((>= %tp-i %tp-n) (cons %tp-out %tp-c))
						                   (let ((%tp-ch (char %tp-pat %tp-i)))
						                     (if (or (char= %tp-ch #\\*) (char= %tp-ch #\\?))
						                         (progn
						                           (setq %tp-out
						                                 (concatenate 'string %tp-out (if %tp-c (car %tp-c) "")))
						                           (setq %tp-c (cdr %tp-c))
						                           (setq %tp-i (+ %tp-i 1)))
						                         (progn
						                           (setq %tp-out
						                                 (concatenate 'string %tp-out
						                                              (subseq %tp-pat %tp-i (+ %tp-i 1))))
						                           (setq %tp-i (+ %tp-i 1))))))))
						             (%tp-dir (%tp-sd %tp-fd %tp-td)
						               (cond ((string= %tp-td "") %tp-sd)
						                     ((string= %tp-sd "")
						                      (let ((%tp-out (if (and (> (length %tp-td) 0)
						                                              (char= (char %tp-td 0) #\\/))
						                                         "/" "")))
						                        (dolist (%tp-p (%path-dir-parts %tp-td))
						                          (unless (string= %tp-p "**")
						                            (setq %tp-out
						                                  (concatenate 'string %tp-out %tp-p "/"))))
						                        %tp-out))
						                     ((string= %tp-fd "") %tp-sd)
						                     (t (let ((%tp-caps (%tp-match-dirs (%path-dir-parts %tp-fd)
						                                                        (%path-dir-parts %tp-sd))))
						                          (if (eq %tp-caps :no-match)
						                              :no-match
						                              (let ((%tp-out (if (or (and (> (length %tp-td) 0)
						                                                         (char= (char %tp-td 0) #\\/))
						                                                      (and (> (length %tp-sd) 0)
						                                                           (char= (char %tp-sd 0) #\\/)))
						                                                 "/" ""))
						                                    (%tp-c %tp-caps))
						                                (dolist (%tp-p (%path-dir-parts %tp-td))
						                                  (let ((%tp-r (if (string= %tp-p "**")
						                                                   (let ((%tp-run (if %tp-c (car %tp-c) "")))
						                                                     (setq %tp-c (cdr %tp-c))
						                                                     (if (and (> (length %tp-run) 0)
						                                                              (char= (char %tp-run (- (length %tp-run) 1))
						                                                                     #\\/))
						                                                         (subseq %tp-run 0 (- (length %tp-run) 1))
						                                                         %tp-run))
						                                                   (let ((%tp-e (%tp-sub %tp-p %tp-c)))
						                                                     (setq %tp-c (cdr %tp-e))
						                                                     (car %tp-e)))))
						                                    (when (> (length %tp-r) 0)
						                                      (setq %tp-out
						                                            (concatenate 'string %tp-out %tp-r "/")))))
						                                %tp-out))))))
						             (%tp-comp (%tp-src %tp-frm %tp-to2)
						               (cond ((null %tp-to2) (cons %tp-src nil))
						                     ((string= %tp-to2 "*") (cons %tp-src nil))
						                     ((%wild-component-p %tp-to2)
						                      (cond ((null %tp-frm)
						                             (if (null %tp-src) (cons nil nil) (cons nil t)))
						                            ((string= %tp-frm "*")
						                             (cons (car (%tp-sub %tp-to2
						                                                 (list (if (null %tp-src) "" %tp-src))))
						                                   nil))
						                            ((%wild-component-p %tp-frm)
						                             (if (null %tp-src)
						                                 (cons nil nil)
						                                 (let ((%tp-c (%wild-captures %tp-frm %tp-src)))
						                                   (if (eq %tp-c :no-match)
						                                       (cons nil t)
						                                       (cons (car (%tp-sub %tp-to2 %tp-c)) nil)))))
						                            (t
						                             (if (and %tp-src (string= %tp-src %tp-frm))
						                                 (cons %tp-src nil)
						                                 (cons nil t)))))
						                     (t
						                      (cond ((null %tp-frm)
						                             (if (null %tp-src) (cons %tp-to2 nil) (cons nil t)))
						                            ((string= %tp-frm "*") (cons %tp-to2 nil))
						                            ((%wild-component-p %tp-frm)
						                             (if (or (null %tp-src) (%wild-match %tp-frm %tp-src))
						                                 (cons %tp-to2 nil)
						                                 (cons nil t)))
						                            (t
						                             (if (and %tp-src (string= %tp-src %tp-frm))
						                                 (cons %tp-to2 nil)
						                                 (cons nil t))))))))
						      (let* ((%tp-d (%tp-dir (first %tp-sp) (first %tp-fp) (first %tp-tp)))
						             (%tp-n (%tp-comp (second %tp-sp) (second %tp-fp) (second %tp-tp)))
						             (%tp-y (%tp-comp (third %tp-sp) (third %tp-fp) (third %tp-tp))))
						        (if (or (eq %tp-d :no-match) (cdr %tp-n) (cdr %tp-y))
						            (error "TRANSLATE-PATHNAME: ~A does not match ~A"
						                   (namestring %tp-source) (namestring %tp-from))
						            (let ((%tp-name (car %tp-n))
						                  (%tp-type (car %tp-y)))
						              (pathname (concatenate 'string %tp-d
						                                     (if (null %tp-name) "" %tp-name)
						                                     (if (or (null %tp-type) (string= %tp-type ""))
						                                         ""
						                                         (concatenate 'string "." %tp-type))))))))))
						""");
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
				                                      ((eq %pds-c :wild-inferiors) "**")
				                                      (t (error "MAKE-PATHNAME: unsupported :directory component ~S"
				                                                %pds-c)))
				                                "/")))
				           %pds-acc))
				        (t (error "MAKE-PATHNAME: :directory must be a list or string, got ~S" %pds-dir))))
				""");
		// make-pathname at RUN time: the shapes cli/CompileTimePathnameFolder
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
				        ((eq %pcs-v :wild) "*")
				        ((symbolp %pcs-v) (string %pcs-v))
				        (t (error "MAKE-PATHNAME: :name and :type must be a string or nil, got ~S" %pcs-v))))
				""");
		// delete-file: the signalling ANSI surface over the %delete-file primitive (nil
		// when the file is not there), so the "a missing file is a file-error" rule has
		// one definition -- %file-error, carrying the pathname as given. mito's
		// generate-migrations deletes superseded migration files with it.
		SOURCES.put(LispNames.DELETE_FILE, """
				(defun delete-file (%dfl-path)
				  (if (%delete-file (namestring %dfl-path))
				      t
				      (%file-error %dfl-path (format nil "DELETE-FILE: cannot delete ~A" %dfl-path))))
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
				        (%file-error %rnf-file
				                     (format nil "RENAME-FILE: cannot rename ~A to ~A" %rnf-from %rnf-to)))))
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
		// therefore cannot drift -- and a component-LESS call is the same class over an
		// empty list, so the file queries answer for it (length 0, external-format
		// :default) instead of the discarding sink's. Reached only through
		// LispMacroExpander.expandMakeBroadcastStream.
		// %make-array-et: make-array whose :element-type is only known at RUN time.
		// Every backend but the interpreter decides an array's representation from the
		// literal designator at the call site, so a designator held in a variable has to
		// be turned back into a literal one -- which means spelling out the whole upgrade
		// space, all seven ArrayElementTypes codes. SEVEN: this comment said seven while
		// the cond spelled six from the day bfloat16 landed until 2026-09-05, so a
		// runtime designator naming that width degraded to a BOXED general array on
		// every backend but the interpreter -- and on wasm walked straight past the
		// refusal WasmArrayCompiler puts on the literal spelling for exactly that reason
		// (.todo/487). Count the arms against ArrayElementTypes, never against this
		// sentence. Doing that AT each call site costs
		// ~1.3 KB of wasm per site (measured; .kb/array-literals.md), and
		// array-operations alone has 21 of them, so the arms live here instead and every
		// site becomes one call. Each arm's literal spelling is what the backends'
		// recognizers read: it selects the packed representation where one exists and
		// carries the remembered element type and its zero fill where it degrades. The
		// unsupplied element is the arm's OWN zero, which is why %mae-given is a
		// parameter rather than a nil test at the call site.
		SOURCES.put(LispNames.MAKE_ARRAY_ET_INTERNAL,
				makeArrayElementTypeDispatch(LispNames.MAKE_ARRAY_ET_INTERNAL, "%mae", "", ""));
		// %make-array-et-fp: the same dispatch for a site that also spells :fill-pointer
		// or :adjustable. Those two are what force EVERY arm to the general
		// representation, so keeping them out of the helper above is what lets it pick a
		// packed one; here they ride along and the arms differ only in the element type
		// each general array remembers. Two sites in array-operations' similar-array,
		// one in the whole quicklisp cache besides.
		SOURCES.put(LispNames.MAKE_ARRAY_ET_FP_INTERNAL,
				makeArrayElementTypeDispatch(LispNames.MAKE_ARRAY_ET_FP_INTERNAL, "%maef", " %maef-fp %maef-adj",
						"\n                     :fill-pointer %maef-fp :adjustable %maef-adj"));
		// %symbol-in-package: a compiled find-symbol / intern with a computed package
		// designator calls it (LispMacroExpander.computedPackageLookup) -- the
		// find-package guard and the package-error once, not at every site. The body IS
		// the inline lowering, printed, so the two cannot drift.
		SOURCES.put(LispNames.SYMBOL_IN_PACKAGE_INTERNAL,
				am.ik.rontolisp.macro.LispMacroExpander.symbolInPackageDefinition());
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
				(defmethod rontolisp:stream-write-byte ((%bs-s %broadcast-stream) %bs-b)
				  (dolist (%bs-x (%broadcast-stream-components %bs-s))
				    (write-byte %bs-b %bs-x))
				  %bs-b)
				(defmethod rontolisp:stream-fresh-line ((%bs-s %broadcast-stream))
				  (let ((%bs-cs (%broadcast-stream-components %bs-s)))
				    (if %bs-cs
				        (fresh-line (car (last %bs-cs)))
				        nil)))
				(defun %make-broadcast-stream (%mbs-components)
				  (make-instance '%broadcast-stream :components %mbs-components))
				""");
		// make-two-way-stream: the composite-stream pattern one more time -- a Gray
		// stream subclassing BOTH character base classes, sreading from the input
		// component and writing to the output one. The methods use the BUILT-INS
		// (read-char / write-char / write-string) so a component that is a stream
		// HANDLE works, and the Gray rewrite that runs over the spliced prelude (the
		// .kb/gray-streams.md pass, which runs AFTER this one in CompileFrontend)
		// rewrites those call sites onto the dispatch helpers, so a component that is
		// itself a Gray instance dispatches too. :eof is the protocol's end-of-stream
		// answer, returned by the read built-in's eof-value.
		SOURCES.put(LispNames.MAKE_TWO_WAY_STREAM, """
				(defclass %two-way-stream
				  (rontolisp:fundamental-character-input-stream
				   rontolisp:fundamental-character-output-stream)
				  ((in :initarg :input :reader %two-way-input)
				   (out :initarg :output :reader %two-way-output)))
				(defmethod rontolisp:stream-read-char ((%tw %two-way-stream))
				  (read-char (%two-way-input %tw) nil :eof))
				(defmethod rontolisp:stream-write-char ((%tw %two-way-stream) %tw-c)
				  (write-char %tw-c (%two-way-output %tw))
				  %tw-c)
				(defmethod rontolisp:stream-write-string ((%tw %two-way-stream) %tw-str
				                                         &optional (%tw-start 0) %tw-end)
				  (write-string (subseq %tw-str %tw-start (or %tw-end (length %tw-str)))
				                (%two-way-output %tw))
				  %tw-str)
				(defmethod rontolisp:stream-read-byte ((%tw %two-way-stream))
				  (read-byte (%two-way-input %tw) nil :eof))
				(defmethod rontolisp:stream-write-byte ((%tw %two-way-stream) %tw-b)
				  (write-byte %tw-b (%two-way-output %tw))
				  %tw-b)
				(defun make-two-way-stream (input-stream output-stream)
				  (if (input-stream-p input-stream)
				      nil
				      (error 'type-error :datum input-stream :expected-type '(satisfies input-stream-p)))
				  (if (output-stream-p output-stream)
				      nil
				      (error 'type-error :datum output-stream :expected-type '(satisfies output-stream-p)))
				  (make-instance '%two-way-stream :input input-stream :output output-stream))
				""");
		// The two-way-stream accessors are their OWN prelude defuns (not part of the
		// constructor entry): each surface name must be a prelude entry key so it
		// resolves as a function value on the interpreter (every CL_FUNCTIONS name
		// must, BuiltinFunctionWrapperCatalogTest). The body references the reader
		// lazily, so it only needs the class defined at CALL time.
		SOURCES.put(LispNames.TWO_WAY_STREAM_INPUT_STREAM, """
				(defun two-way-stream-input-stream (%tw2wi-s)
				  (%two-way-input %tw2wi-s))
				""");
		SOURCES.put(LispNames.TWO_WAY_STREAM_OUTPUT_STREAM, """
				(defun two-way-stream-output-stream (%tw2wo-s)
				  (%two-way-output %tw2wo-s))
				""");
		// make-echo-stream: a two-way stream whose reads are also written to the output
		// component. Defined as its OWN Gray class (own in/out slots and readers) rather
		// than a subclass of %two-way-stream: each prelude entry loads standalone on the
		// interpreter (per-name), so an entry must not need another entry's defclass
		// already evaluated. The read method echoes.
		SOURCES.put(LispNames.MAKE_ECHO_STREAM, """
				(defclass %echo-stream
				  (rontolisp:fundamental-character-input-stream
				   rontolisp:fundamental-character-output-stream)
				  ((in :initarg :input :reader %echo-input)
				   (out :initarg :output :reader %echo-output)))
				(defmethod rontolisp:stream-read-char ((%es %echo-stream))
				  (let ((%es-c (read-char (%echo-input %es) nil :eof)))
				    (if (eq %es-c :eof)
				        :eof
				        (progn
				          (write-char %es-c (%echo-output %es))
				          %es-c))))
				(defmethod rontolisp:stream-write-char ((%es %echo-stream) %es-c)
				  (write-char %es-c (%echo-output %es))
				  %es-c)
				(defmethod rontolisp:stream-write-string ((%es %echo-stream) %es-str
				                                         &optional (%es-start 0) %es-end)
				  (write-string (subseq %es-str %es-start (or %es-end (length %es-str)))
				                (%echo-output %es))
				  %es-str)
				(defmethod rontolisp:stream-read-byte ((%es %echo-stream))
				  (let ((%es-b (read-byte (%echo-input %es) nil :eof)))
				    (if (eq %es-b :eof)
				        :eof
				        (progn
				          (write-byte %es-b (%echo-output %es))
				          %es-b))))
				(defmethod rontolisp:stream-write-byte ((%es %echo-stream) %es-b)
				  (write-byte %es-b (%echo-output %es))
				  %es-b)
				(defmethod rontolisp:stream-peek-char ((%es %echo-stream))
				  ;; Only what is READ echoes: a peek looks at the input component
				  ;; without writing, so the output stream's position does not move.
				  (peek-char nil (%echo-input %es) nil :eof))
				(defun make-echo-stream (input-stream output-stream)
				  (if (input-stream-p input-stream)
				      nil
				      (error 'type-error :datum input-stream :expected-type '(satisfies input-stream-p)))
				  (if (output-stream-p output-stream)
				      nil
				      (error 'type-error :datum output-stream :expected-type '(satisfies output-stream-p)))
				  (make-instance '%echo-stream :input input-stream :output output-stream))
				""");
		SOURCES.put(LispNames.ECHO_STREAM_INPUT_STREAM, """
				(defun echo-stream-input-stream (%es2i-s)
				  (%echo-input %es2i-s))
				""");
		SOURCES.put(LispNames.ECHO_STREAM_OUTPUT_STREAM, """
				(defun echo-stream-output-stream (%es2o-s)
				  (%echo-output %es2o-s))
				""");
		// make-concatenated-stream: a character input stream whose read walks the
		// component list, dropping each at its end of file. Like make-two-way-stream and
		// make-echo-stream, the constructor refuses a component of the wrong DIRECTION
		// with a type-error -- which the direction predicates can tell only since they
		// answer the real direction (.kb/read-load-streams.md, "String streams").
		SOURCES.put(LispNames.MAKE_CONCATENATED_STREAM, """
				(defclass %concatenated-stream (rontolisp:fundamental-character-input-stream)
				  ((streams :initarg :streams :reader %concatenated-stream-streams)))
				(defmethod rontolisp:stream-read-char ((%cs %concatenated-stream))
				  (let ((%cs-st (%concatenated-stream-streams %cs)) (%cs-r :eof) (%cs-done nil))
				    (do ()
				        (%cs-done %cs-r)
				      (cond ((null %cs-st)
				             (setq %cs-r :eof)
				             (setq %cs-done t))
				            (t
				             (let ((%cs-c (read-char (car %cs-st) nil :eof)))
				               (if (eq %cs-c :eof)
				                   (setq %cs-st (cdr %cs-st))
				                   (progn
				                     (setq %cs-r %cs-c)
				                     (setq %cs-done t)))))))))
				(defmethod rontolisp:stream-read-byte ((%cs %concatenated-stream))
				  (let ((%cs-st (%concatenated-stream-streams %cs)) (%cs-r :eof) (%cs-done nil))
				    (do ()
				        (%cs-done %cs-r)
				      (cond ((null %cs-st)
				             (setq %cs-r :eof)
				             (setq %cs-done t))
				            (t
				             (let ((%cs-b (read-byte (car %cs-st) nil :eof)))
				               (if (eq %cs-b :eof)
				                   (setq %cs-st (cdr %cs-st))
				                   (progn
				                     (setq %cs-r %cs-b)
				                     (setq %cs-done t)))))))))
				(defun make-concatenated-stream (&rest %mcs-streams)
				  (dolist (%mcs-s %mcs-streams)
				    (if (input-stream-p %mcs-s)
				        nil
				        (error 'type-error :datum %mcs-s :expected-type '(satisfies input-stream-p))))
				  (make-instance '%concatenated-stream :streams %mcs-streams))
				""");
		SOURCES.put(LispNames.CONCATENATED_STREAM_STREAMS, """
				(defun concatenated-stream-streams (%css-s)
				  (%concatenated-stream-streams %css-s))
				""");
		SOURCES.put(LispNames.BROADCAST_STREAM_STREAMS, """
				(defun broadcast-stream-streams (%bss-s)
				  (%broadcast-stream-components %bss-s))
				""");
		// clear-input: the read-side twin of clear-output. Nothing on any backend
		// buffers input a program could throw away, so the operation validates its
		// stream DESIGNATOR (nil and t are the standard input stream) and answers nil.
		SOURCES.put(LispNames.CLEAR_INPUT, """
				(defun clear-input (&optional %ci-s)
				  (if (or (null %ci-s) (eq %ci-s t) (streamp %ci-s))
				      nil
				      (error 'type-error :datum %ci-s :expected-type 'stream)))
				""");
		// interactive-stream-p: no backend can tell a terminal from a pipe, so the
		// answer is nil for every stream -- but the ARGUMENT is still a stream, not a
		// designator, and a non-stream is a type-error.
		SOURCES.put(LispNames.INTERACTIVE_STREAM_P, """
				(defun interactive-stream-p (%isp-s)
				  (if (streamp %isp-s)
				      nil
				      (error 'type-error :datum %isp-s :expected-type 'stream)))
				""");
		// stream-external-format: UTF-8 is the one format the reader and every writer
		// use, on every backend, so there is nothing to select and nothing to remember --
		// except a broadcast stream, which answers its last component (or :default with
		// none), the same last-component rule its file queries follow.
		SOURCES.put(LispNames.STREAM_EXTERNAL_FORMAT, """
				(defun stream-external-format (%sef-s)
				  ;; A broadcast stream answers its last component (or :default with
				  ;; none). Slot 0 directly: these defuns travel without the broadcast
				  ;; class entry, so the reader may not be defined where they run.
				  (if (%obj-is %sef-s '|%class-%BROADCAST-STREAM|)
				      (let ((%sef-cs (%obj-ref %sef-s 0)))
				        (if %sef-cs
				            (stream-external-format (car (last %sef-cs)))
				            :default))
				      (if (streamp %sef-s)
				          :utf-8
				          (error 'type-error :datum %sef-s :expected-type 'stream))))
				""");
		// file-string-length: how far writing the object would move file-position --
		// the UTF-8 byte length, since that is what every backend writes. A character
		// counts as the one-character string it is. A broadcast stream answers its last
		// component (or 1 with none).
		SOURCES.put(LispNames.FILE_STRING_LENGTH, """
				(defun file-string-length (%fsl-s %fsl-obj)
				  ;; Slot 0 directly (see stream-external-format above).
				  (if (%obj-is %fsl-s '|%class-%BROADCAST-STREAM|)
				      (let ((%fsl-cs (%obj-ref %fsl-s 0)))
				        (if %fsl-cs
				            (file-string-length (car (last %fsl-cs)) %fsl-obj)
				            1))
				      (let ((%fsl-str (if (characterp %fsl-obj) (string %fsl-obj) %fsl-obj))
				            (%fsl-n 0))
				        (if (streamp %fsl-s)
				            nil
				            (error 'type-error :datum %fsl-s :expected-type 'stream))
				        (dotimes (%fsl-i (length %fsl-str) %fsl-n)
				          (let ((%fsl-c (char-code (char %fsl-str %fsl-i))))
				            (setq %fsl-n
				                  (+ %fsl-n
				                     (cond ((< %fsl-c 128) 1)
				                           ((< %fsl-c 2048) 2)
				                           ((< %fsl-c 65536) 3)
				                           (t 4)))))))))
				""");
		// %stream-target: the ONE resolution of a stream DESIGNATOR down to the raw
		// handle the I/O primitives act on. Two things are resolved, in this order.
		// A synonym stream is a value (LispLayout.SYNONYM_STREAM) whose reserved cell
		// holds a zero-argument closure reading the variable it names, so calling it
		// answers that variable's value AS OF NOW -- the per-operation forwarding CL
		// prescribes -- and the recursion carries a synonym over a synonym. An OPEN
		// stream is a value too (LispLayout.STREAM), and its slot 0 IS the handle.
		// Reached from both compile-path seams (Jvm/Wasm streamArg, via
		// StreamDesignators.throughStream) and from every gray.lisp dispatch helper,
		// which must resolve BEFORE its %obj-p test or a stream value -- synonym or open
		// -- would take the CLOS arm.
		SOURCES.put(LispNames.STREAM_TARGET, """
				(defun %stream-target (%st-s)
				  (if (%obj-is %st-s '%SYNONYM-STREAM)
				      (%stream-target (funcall (%obj-ref %st-s 1)))
				      (if (%obj-is %st-s '%STREAM)
				          (%obj-ref %st-s 0)
				          %st-s)))
				""");
		// The compile paths' element-type registry (.kb/read-load-streams.md, "Element
		// types wider and narrower than one octet"). A backend's file descriptor moves
		// octets whatever the element type, so the TYPE rides beside the stream: every
		// literal binary open leaf is wrapped in %file-stream-register, keyed by the
		// HANDLE -- the Gray dispatchers hand the built-ins a resolved handle, not the
		// stream value -- and every close forgets its entry (%file-stream-forget), so a
		// WASM descriptor reused after close never inherits one. An entry is
		// (octets signed spec). The backends call these only
		// when the entry is spliced (ctx.functions), and selection keys on the SURFACE
		// fact (referencedBySurfaceForm), because the calls are synthesized inside the
		// expression compilers. Each helper is its own entry (the pruner roots entries
		// by name), and the two that touch the table carry their own copy of its defvar
		// -- defvar assigns only when unbound, the %symbol-plists precedent.
		SOURCES.put(LispNames.FILE_STREAM_ENTRY_INTERNAL, """
				(defvar %file-stream-types (make-hash-table))
				(defun %file-stream-entry (%fse-s)
				  (gethash (%stream-target %fse-s) %file-stream-types))
				""");
		SOURCES.put(LispNames.FILE_STREAM_REGISTER_INTERNAL, """
				(defvar %file-stream-types (make-hash-table))
				(defun %file-stream-register (%fsr-s %fsr-n %fsr-signed %fsr-spec)
				  (setf (gethash (%obj-ref %fsr-s 0) %file-stream-types) (list %fsr-n %fsr-signed %fsr-spec))
				  %fsr-s)
				""");
		// Closing a SYNONYM closes the synonym, not its target, so only an open stream
		// value (or a raw handle, which the Gray close dispatcher can pass) forgets.
		SOURCES.put(LispNames.FILE_STREAM_FORGET_INTERNAL, """
				(defvar %file-stream-types (make-hash-table))
				(defun %file-stream-forget (%fsf-s)
				  (if (%obj-is %fsf-s '%STREAM)
				      (remhash (%obj-ref %fsf-s 0) %file-stream-types)
				      (if (integerp %fsf-s) (remhash %fsf-s %file-stream-types) nil)))
				""");
		// Whether read-sequence / write-sequence move CHARACTERS through a buffer that is
		// not a string: the STREAM picks the element, and a string stream is a character
		// stream (.kb/read-load-streams.md, "The stream picks the element"). ONE defun
		// every site calls, rather than the kind test inline at each: inline it cost
		// ~400 B of wasm and ~1 KB of JVM bytecode per site.
		SOURCES.put(LispNames.CHARACTER_STREAM_P_INTERNAL, """
				(defun %character-stream-p (%csp-s)
				  (if (%obj-is %csp-s '%STREAM)
				      (let ((%csp-k (%obj-ref %csp-s 1)))
				        (if (equal %csp-k :string-input) t (equal %csp-k :string-output)))
				      nil))
				""");
		// The read-sequence / write-sequence argument check (.todo/932): a dotted-list
		// buffer, a negative, non-integer or symbolic bound, and a range outside the
		// buffer are type-errors. ONE defun every site calls, not an inline test: the
		// inline check cost ~8 KB of wasm per site, the defun call ~0.2 KB -- the
		// %character-stream-p shape, for the same per-site reason. The
		// signals carry static messages (a runtime datum in the message pulled the
		// ~s printer, +17 KB of wasm); the datum and expected type ride the instance.
		// A quantized-matrix buffer is neither stringp nor arrayp, so without its own
		// arm it would fall into list-length and spuriously fail: its transfers move
		// block BYTES (:start/:end count bytes, .kb/quantized-matrix.md) and no
		// Lisp-level reader measures them, so its length is nil -- like a circular
		// list's, the range check stays the transfer arm's while the bound types
		// still check here.
		SOURCES.put(LispNames.CHECK_SEQUENCE_BOUNDS_INTERNAL, """
				(defun %check-sequence-bounds (%csq-seq %csq-start %csq-end)
				  (let ((%csq-len (if (rontolisp:quantized-matrix-p %csq-seq)
				                      nil
				                      (if (stringp %csq-seq)
				                          (length %csq-seq)
				                          (if (arrayp %csq-seq)
				                              (if (= (array-rank %csq-seq) 1)
				                                  (length %csq-seq)
				                                  (array-total-size %csq-seq))
				                              (list-length %csq-seq))))))
				    (if (or (not (integerp %csq-start)) (< %csq-start 0))
				        (error 'type-error :datum %csq-start :expected-type '(integer 0 *)
				               :format-control "Invalid sequence bound.")
				        (if (and %csq-end (or (not (integerp %csq-end)) (< %csq-end 0)))
				            (error 'type-error :datum %csq-end :expected-type '(integer 0 *)
				                   :format-control "Invalid sequence bound.")
				            (if (and %csq-len (or (> %csq-start %csq-len)
				                                  (and %csq-end (or (> %csq-end %csq-len)
				                                                     (< %csq-end %csq-start)))))
				                (error 'type-error
				                       :datum (if (and %csq-end (> %csq-end %csq-len)) %csq-end %csq-start)
				                       :expected-type '(integer 0 *)
				                       :format-control "Sequence bounds out of range.")
				                nil)))))
				""");
		// The compile paths' FILE-stream direction record (.kb/read-load-streams.md,
		// "String streams"): every literal open leaf registers its direction bits (1
		// input, 2 output, 3 both) under its HANDLE -- the element-type registry's key
		// -- and every close forgets it, so a closed file stream is neither, as the
		// interpreter's emptied table entry says. The direction predicates read it
		// INLINE (LispMacroExpander.expandStreamDirectionP); a classifier defun cost
		// +6.2 KB on the JVM for one output-stream-p of a string stream, where the
		// inline test costs +0.6 KB. An ALIST, not a hash table: the table runtime was
		// +14.7 KB and a second class file on the JVM, and the entries are the file
		// streams open at once.
		SOURCES.put(LispNames.FILE_STREAM_DIRECTION_REGISTER_INTERNAL, """
				(defvar rontolisp::%file-stream-directions nil)
				(defun rontolisp::%file-stream-direction-register (%fsdr-s %fsdr-d)
				  (setq rontolisp::%file-stream-directions
				        (cons (cons (%obj-ref %fsdr-s 0) %fsdr-d) rontolisp::%file-stream-directions))
				  %fsdr-s)
				""");
		SOURCES.put(LispNames.FILE_STREAM_DIRECTION_FORGET_INTERNAL, """
				(defvar rontolisp::%file-stream-directions nil)
				(defun rontolisp::%file-stream-direction-forget (%fsdf-s)
				  (let ((%fsdf-h (if (%obj-is %fsdf-s '%STREAM)
				                     (%obj-ref %fsdf-s 0)
				                     (if (integerp %fsdf-s) %fsdf-s nil)))
				        (%fsdf-kept nil))
				    (if %fsdf-h
				        (progn
				          (dolist (%fsdf-e rontolisp::%file-stream-directions)
				            (if (eql (car %fsdf-e) %fsdf-h) nil (setq %fsdf-kept (cons %fsdf-e %fsdf-kept))))
				          (setq rontolisp::%file-stream-directions %fsdf-kept))
				        nil)
				    nil))
				""");
		SOURCES.put(LispNames.FILE_STREAM_ELEMENT_TYPE_INTERNAL, """
				(defun %file-stream-element-type (%fset-s)
				  (let ((%fset-e (%file-stream-entry %fset-s)))
				    (if %fset-e (car (cdr (cdr %fset-e))) 'character)))
				""");
		// The WIDE element: more than one octet, or a signed one. The backends lower
		// read-byte / write-byte onto these, and file-length / file-position through the
		// two scaling helpers, only when these entries are spliced -- a program that
		// opens no wide stream keeps every byte it had. An element is little-endian,
		// two's complement when signed, composed from the backend's one-octet primitive
		// (%read-octet / %write-octet, the unlowered read-byte / write-byte).
		SOURCES.put(LispNames.WIDE_WIDTH_INTERNAL, """
				(defun %wide-width (%ww-s)
				  (let ((%ww-e (%file-stream-entry %ww-s)))
				    (if %ww-e (if (or (> (car %ww-e) 1) (car (cdr %ww-e))) %ww-e nil) nil)))
				""");
		SOURCES.put(LispNames.WIDE_READ_BYTE_INTERNAL, """
				(defun %wide-read-byte (%wrb-s %wrb-errp %wrb-v)
				  (let* ((%wrb-st (if %wrb-s %wrb-s *standard-input*))
				         (%wrb-e (%wide-width %wrb-st))
				         (%wrb-n (if %wrb-e (car %wrb-e) 1))
				         (%wrb-acc 0)
				         (%wrb-i 0)
				         (%wrb-b 0))
				    (while (if %wrb-b (< %wrb-i %wrb-n) nil)
				      (setq %wrb-b (%read-octet %wrb-st nil nil))
				      (if %wrb-b
				          (progn (setq %wrb-acc (logior %wrb-acc (ash %wrb-b (* 8 %wrb-i))))
				                 (setq %wrb-i (+ %wrb-i 1)))
				          nil))
				    (if (< %wrb-i %wrb-n)
				        (if %wrb-errp (error 'end-of-file :stream %wrb-st) %wrb-v)
				        (if (if %wrb-e (if (car (cdr %wrb-e)) (logbitp (- (* 8 %wrb-n) 1) %wrb-acc) nil) nil)
				            (- %wrb-acc (ash 1 (* 8 %wrb-n)))
				            %wrb-acc))))
				""");
		SOURCES.put(LispNames.WIDE_WRITE_BYTE_INTERNAL, """
				(defun %wide-write-byte (%wwb-i %wwb-s)
				  (let ((%wwb-e (%wide-width %wwb-s)))
				    (if (null %wwb-e)
				        (%write-octet %wwb-i %wwb-s)
				        (let* ((%wwb-n (car %wwb-e))
				               (%wwb-bits (* 8 %wwb-n))
				               (%wwb-signed (car (cdr %wwb-e)))
				               (%wwb-lo (if %wwb-signed (- (ash 1 (- %wwb-bits 1))) 0))
				               (%wwb-hi (- (if %wwb-signed (ash 1 (- %wwb-bits 1)) (ash 1 %wwb-bits)) 1))
				               (%wwb-k 0))
				          (if (if (integerp %wwb-i) (if (>= %wwb-i %wwb-lo) (<= %wwb-i %wwb-hi) nil) nil)
				              (let ((%wwb-u (if (< %wwb-i 0) (+ %wwb-i (ash 1 %wwb-bits)) %wwb-i)))
				                (while (< %wwb-k %wwb-n)
				                  (%write-octet (logand (ash %wwb-u (- (* 8 %wwb-k))) 255) %wwb-s)
				                  (setq %wwb-k (+ %wwb-k 1)))
				                %wwb-i)
				              (error "WRITE-BYTE expects an integer between ~a and ~a" %wwb-lo %wwb-hi))))))
				""");
		SOURCES.put(LispNames.WIDE_ELEMENTS_INTERNAL, """
				(defun %wide-elements (%we-s %we-n)
				  (let ((%we-e (%wide-width %we-s)))
				    (if (if %we-e (integerp %we-n) nil) (values (floor %we-n (car %we-e))) %we-n)))
				""");
		SOURCES.put(LispNames.WIDE_POSITION_OCTETS_INTERNAL, """
				(defun %wide-position-octets (%wpo-s %wpo-p)
				  (let ((%wpo-e (%wide-width %wpo-s)))
				    (if (if %wpo-e (integerp %wpo-p) nil) (* %wpo-p (car %wpo-e)) %wpo-p)))
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
		// package-shadowing-symbols: a runtime package's shadowing members (its entry's
		// fifth cell names them), a read/compile-time package's baked shadows (the
		// defpackage :shadow / :shadowing-import-from names, spelled as the symbols
		// they resolve to). The interpreter answers from its live registry instead. An
		// unknown designator signals through package-name, exactly as it does there.
		SOURCES.put(LispNames.PACKAGE_SHADOWING_SYMBOLS, """
				(defun package-shadowing-symbols (%pss-pkg)
				  (let* ((%pss-s (string %pss-pkg))
				         (%pss-e (%runtime-package-find %pss-s)))
				    (if %pss-e
				        (mapcar (lambda (%pss-n) (cadr (assoc %pss-n (cadddr %pss-e) :test #'string=)))
				                (sort (copy-list (nth 4 %pss-e)) #'string<))
				        (let ((%pss-row (%baked-package-find %pss-s)))
				          (if %pss-row
				              (mapcar (lambda (%pss-sp) (intern %pss-sp)) (%split-packed (seventh %pss-row)))
				              (progn (package-name %pss-pkg) nil))))))
				""");
		// shadow / shadowing-import / unintern: the member-table mutations, over the
		// %runtime-packages% entries through %runtime-package-op (a read/compile-time
		// package is frozen there and answers t -- nil for unintern -- without a
		// change). The interpreter binds natives over its live registry instead.
		SOURCES.put(LispNames.SHADOW, """
				(defun shadow (%sh-names &optional %sh-pkg)
				  (%runtime-package-op "SHADOW" %sh-names %sh-pkg))
				""");
		SOURCES.put(LispNames.SHADOWING_IMPORT, """
				(defun shadowing-import (%si-syms &optional %si-pkg)
				  (%runtime-package-op "SHADOWING-IMPORT" %si-syms %si-pkg))
				""");
		SOURCES.put(LispNames.UNINTERN, """
				(defun unintern (%un-sym &optional %un-pkg)
				  (%runtime-package-op "UNINTERN" (list %un-sym) %un-pkg))
				""");
		// The runtime-tier package API (see .kb/packages.md): make-package /
		// delete-package / rename-package / package-nicknames as prelude defuns for
		// the COMPILED backends, over the injected %baked-packages% table (the
		// read/compile-time packages) plus the mutable %runtime-packages% table. The
		// interpreter never loads these -- its natives mutate the live registry
		// instead, so a package created there is visible to later forms -- but the
		// signal shapes and messages match the natives exactly.
		//
		// Entry layout, baked and runtime alike: (name use nicknames) with the name
		// and use entries as keywords and the nicknames as upcased strings. Baked
		// entries carry the symbol universes after that (accessible externals, as
		// packed spelling strings), the import redirects (dotted (member . home)
		// pairs) and the packed shadowing symbols. A runtime entry carries its MEMBER
		// TABLE instead: a list of (name symbol status) triples -- the symbols intern,
		// export, import, shadowing-import and shadow put there, status :internal or
		// :external -- and the list of its shadowing names; what it inherits comes
		// from its use list at lookup time (%runtime-member-find).
		SOURCES.put(LispNames.RUNTIME_PACKAGES_INTERNAL, """
				(defvar %runtime-packages% nil)
				""");
		SOURCES.put(LispNames.RUNTIME_EXTERNAL_FIND_INTERNAL, """
				(defun %runtime-external-find (%ref-pkg %ref-name)
				  (let* ((%ref-s (string %ref-pkg))
				         (%ref-e (%runtime-package-find %ref-s)))
				    (if %ref-e
				        (let ((%ref-hit (assoc %ref-name (cadddr %ref-e) :test #'string=)))
				          (if (and %ref-hit (eq (caddr %ref-hit) :external)) (cadr %ref-hit) nil))
				        (let ((%ref-row (%baked-package-find %ref-s))
				              (%ref-found nil))
				          (when %ref-row
				            (dolist (%ref-sp (%split-packed (fifth %ref-row)))
				              (when (and (null %ref-found) (string= %ref-name (symbol-name (intern %ref-sp))))
				                (setq %ref-found (intern %ref-sp)))))
				          %ref-found))))
				""");
		SOURCES.put(LispNames.RUNTIME_MEMBER_FIND_INTERNAL, """
				(defun %runtime-member-find (%rmf-e %rmf-name)
				  (let ((%rmf-hit (assoc %rmf-name (cadddr %rmf-e) :test #'string=)))
				    (if %rmf-hit
				        (cadr %rmf-hit)
				        (let ((%rmf-found nil))
				          (dolist (%rmf-u (cadr %rmf-e) %rmf-found)
				            (when (null %rmf-found)
				              (setq %rmf-found (%runtime-external-find %rmf-u %rmf-name))))))))
				""");
		SOURCES.put(LispNames.RUNTIME_MEMBER_STATUS_INTERNAL, """
				(defun %runtime-member-status (%rms-e %rms-name)
				  (let ((%rms-hit (assoc %rms-name (cadddr %rms-e) :test #'string=)))
				    (if %rms-hit
				        (caddr %rms-hit)
				        (if (%runtime-member-find %rms-e %rms-name) :inherited nil))))
				""");
		SOURCES.put(LispNames.RUNTIME_MEMBER_INTERN_INTERNAL, """
				(defun %runtime-member-intern (%rmi-e %rmi-name)
				  (or (%runtime-member-find %rmi-e %rmi-name)
				      (let ((%rmi-sym (intern (concatenate 'string (string (car %rmi-e)) "::" %rmi-name))))
				        (rplaca (nthcdr 3 %rmi-e) (cons (list %rmi-name %rmi-sym :internal) (cadddr %rmi-e)))
				        %rmi-sym)))
				""");
		// The member-table mutations behind export / unexport / import /
		// shadowing-import / shadow / unintern / use-package / unuse-package on a
		// runtime package (the lowerings and the prelude defuns above route here). A
		// designator naming a read/compile-time package answers t unchanged (nil for
		// unintern): that registry is frozen. One naming nothing is a package-error.
		SOURCES.put(LispNames.RUNTIME_PACKAGE_OP_INTERNAL,
				"""
						(defun %runtime-package-op (%rpo-op %rpo-syms %rpo-pkg)
						  (let* ((%rpo-d (if (null %rpo-pkg) *package* %rpo-pkg))
						         (%rpo-s (string %rpo-d))
						         (%rpo-e (%runtime-package-find %rpo-s))
						         (%rpo-list (if (listp %rpo-syms) %rpo-syms (list %rpo-syms)))
						         (%rpo-r t))
						    (cond ((null %rpo-e)
						           (if (find-package %rpo-d)
						               (if (string= %rpo-op "UNINTERN") nil t)
						               (error 'package-error :package (intern (string-upcase %rpo-s) :keyword)
						                      :format-control (concatenate 'string %rpo-op ": no such package: " %rpo-s))))
						          ((string= %rpo-op "USE-PACKAGE")
						           (dolist (%rpo-u %rpo-list t)
						             (let ((%rpo-k (find-package %rpo-u)))
						               (if (null %rpo-k)
						                   (error 'package-error :package (intern (string-upcase (string %rpo-u)) :keyword)
						                          :format-control (concatenate 'string "USE-PACKAGE: no such package: " (string %rpo-u))))
						               (unless (member (string %rpo-k) (mapcar (lambda (%rpo-x) (string %rpo-x)) (cadr %rpo-e)) :test #'string=)
						                 (rplaca (cdr %rpo-e) (append (cadr %rpo-e) (list %rpo-k)))))))
						          ((string= %rpo-op "UNUSE-PACKAGE")
						           (dolist (%rpo-u %rpo-list t)
						             (let ((%rpo-us (string %rpo-u)))
						               (rplaca (cdr %rpo-e)
						                       (remove-if (lambda (%rpo-x) (string= %rpo-us (string %rpo-x))) (cadr %rpo-e))))))
						          (t (dolist (%rpo-x %rpo-list %rpo-r)
						               (setq %rpo-r (%runtime-member-op %rpo-op %rpo-e %rpo-x)))))))
						(defun %runtime-member-op (%rmo-op %rmo-e %rmo-x)
						  (let* ((%rmo-name (if (stringp %rmo-x) %rmo-x (symbol-name %rmo-x)))
						         (%rmo-members (cadddr %rmo-e))
						         (%rmo-hit (assoc %rmo-name %rmo-members :test #'string=)))
						    (cond ((string= %rmo-op "EXPORT")
						           (if %rmo-hit
						               (rplaca (cddr %rmo-hit) :external)
						               (let ((%rmo-inh (%runtime-member-find %rmo-e %rmo-name)))
						                 (rplaca (nthcdr 3 %rmo-e)
						                         (cons (list %rmo-name (if %rmo-inh %rmo-inh %rmo-x) :external) %rmo-members))))
						           t)
						          ((string= %rmo-op "UNEXPORT")
						           (when %rmo-hit (rplaca (cddr %rmo-hit) :internal))
						           t)
						          ((string= %rmo-op "IMPORT")
						           (if %rmo-hit
						               (unless (eq (cadr %rmo-hit) %rmo-x)
						                 (error 'package-error :package (car %rmo-e)
						                        :format-control (concatenate 'string "IMPORT: the symbol " (prin1-to-string %rmo-x)
						                                                     " conflicts with a symbol present in package " (string (car %rmo-e)))))
						               (rplaca (nthcdr 3 %rmo-e) (cons (list %rmo-name %rmo-x :internal) %rmo-members)))
						           t)
						          ((string= %rmo-op "SHADOWING-IMPORT")
						           (rplaca (nthcdr 3 %rmo-e)
						                   (cons (list %rmo-name %rmo-x :internal)
						                         (remove-if (lambda (%rmo-m) (string= %rmo-name (car %rmo-m))) %rmo-members)))
						           (unless (member %rmo-name (nth 4 %rmo-e) :test #'string=)
						             (rplaca (nthcdr 4 %rmo-e) (cons %rmo-name (nth 4 %rmo-e))))
						           t)
						          ((string= %rmo-op "SHADOW")
						           (unless %rmo-hit
						             (rplaca (nthcdr 3 %rmo-e)
						                     (cons (list %rmo-name (intern (concatenate 'string (string (car %rmo-e)) "::" %rmo-name)) :internal)
						                           %rmo-members)))
						           (unless (member %rmo-name (nth 4 %rmo-e) :test #'string=)
						             (rplaca (nthcdr 4 %rmo-e) (cons %rmo-name (nth 4 %rmo-e))))
						           t)
						          ((string= %rmo-op "UNINTERN")
						           (if (and %rmo-hit (eq (cadr %rmo-hit) %rmo-x))
						               (progn (rplaca (nthcdr 3 %rmo-e)
						                              (remove-if (lambda (%rmo-m) (string= %rmo-name (car %rmo-m))) %rmo-members))
						                      (rplaca (nthcdr 4 %rmo-e) (remove %rmo-name (nth 4 %rmo-e) :test #'string=))
						                      t)
						               nil))
						          (t t))))
						""");
		// %package-iterator-entries: the (symbol status package) triples behind
		// with-package-iterator, over the %do-symbols-list universe. A runtime entry's
		// member table carries the status; a baked row answers :external from its
		// external universe, :internal for a symbol homed in the package and
		// :inherited otherwise (cl-user's standard names are inherited, whatever the
		// compiled symbol-package says about a bare name). The interpreter binds a
		// native over its live registry instead.
		SOURCES.put(LispNames.PACKAGE_ITERATOR_ENTRIES_INTERNAL,
				"""
						(defun %package-iterator-entries (%pie-pkgs %pie-types)
						  (let ((%pie-acc nil))
						    (dolist (%pie-p (if (listp %pie-pkgs) %pie-pkgs (list %pie-pkgs)) (nreverse %pie-acc))
						      (let* ((%pie-k (find-package %pie-p))
						             (%pie-e (if %pie-k (%runtime-package-find (string %pie-k)) nil))
						             (%pie-ext (if %pie-k (%do-symbols-list %pie-k t "WITH-PACKAGE-ITERATOR") nil)))
						        (if (null %pie-k)
						            (error "WITH-PACKAGE-ITERATOR: no package named ~A" %pie-p))
						        (dolist (%pie-s (%do-symbols-list %pie-k nil "WITH-PACKAGE-ITERATOR"))
						          (let ((%pie-st (cond (%pie-e (%runtime-member-status %pie-e (symbol-name %pie-s)))
						                               ((member %pie-s %pie-ext) :external)
						                               ((and (string= (string %pie-k) "CL-USER") (find-symbol (symbol-name %pie-s) :cl))
						                                :inherited)
						                               ((string= (string (symbol-package %pie-s)) (string %pie-k)) :internal)
						                               (t :inherited))))
						            (when (member %pie-st %pie-types)
						              (push (list %pie-s %pie-st %pie-k) %pie-acc))))))))
						""");
		SOURCES.put(LispNames.BAKED_IMPORT_REDIRECT_INTERNAL, """
				(defun %baked-import-redirect (%bir-name %bir-home)
				  (let ((%bir-imports nil))
				    (dolist (%bir-e %baked-packages%)
				      (when (string= (string %bir-home) (car %bir-e))
				        (setq %bir-imports (sixth %bir-e))))
				    (let ((%bir-hit (assoc %bir-name %bir-imports :test #'string=)))
				      (when %bir-hit
				        (let ((%bir-dest (cdr %bir-hit)))
				          (if (string= %bir-dest "CL")
				              (intern %bir-name)
				              (intern (concatenate 'string %bir-dest ":" %bir-name))))))))
				""");
		SOURCES.put(LispNames.PACKAGE_SPELLING_NORMALIZE_INTERNAL, """
				(defun %package-spelling-normalize (%psn-s)
				  (if (or (not (symbolp %psn-s))
				          (null (symbol-package %psn-s))
				          (search "::" (prin1-to-string %psn-s)))
				      %psn-s
				      (let ((%psn-name (symbol-name %psn-s))
				            (%psn-home (symbol-package %psn-s)))
				        (or (%baked-import-redirect %psn-name %psn-home)
				            (find-symbol %psn-name %psn-home)
				            %psn-s))))
				""");
		SOURCES.put(LispNames.BAKED_PACKAGE_FIND_INTERNAL, """
				(defun %baked-package-find (%bpf-s)
				  (find-if (lambda (%bpf-e)
				             (or (string= %bpf-s (string (car %bpf-e)))
				                 (member %bpf-s (caddr %bpf-e) :test #'string=)))
				           %baked-packages%))
				""");
		SOURCES.put(LispNames.RUNTIME_PACKAGE_FIND_INTERNAL, """
				(defun %runtime-package-find (%rpf-s)
				  (find-if (lambda (%rpf-e)
				             (or (string= %rpf-s (string (car %rpf-e)))
				                 (member %rpf-s (caddr %rpf-e) :test #'string=)))
				           %runtime-packages%))
				""");
		SOURCES.put(LispNames.PACKAGE_SYMBOLS_WHERE_INTERNAL, """
				(defun %package-symbols-where (%psw-pat %psw-exact %psw-pkg)
				  (let ((%psw-pkgs (if (null %psw-pkg) (list-all-packages) (list %psw-pkg)))
				        (%psw-op (if %psw-exact "FIND-ALL-SYMBOLS" "APROPOS-LIST"))
				        (%psw-acc nil))
				    (dolist (%psw-p %psw-pkgs (nreverse %psw-acc))
				      (dolist (%psw-s (%do-symbols-list %psw-p nil %psw-op))
				        ;; Normalize to the spelling code uses (a re-export redirect
				        ;; resolved to its home); a symbol nothing can see keeps its
				        ;; enumerated spelling.
				        (let ((%psw-n (%package-spelling-normalize %psw-s)))
				          (when (if %psw-exact
				                    (string= %psw-pat (symbol-name %psw-n))
				                    (search %psw-pat (string-upcase (symbol-name %psw-n))))
				            (unless (member %psw-n %psw-acc :test #'equal)
				              (push %psw-n %psw-acc))))))))
				""");
		SOURCES.put(LispNames.SPLIT_PACKED_INTERNAL, """
				(defun %split-packed (%sp-s)
				  (if (or (null %sp-s) (string= %sp-s "")) nil
				      (let ((%sp-acc nil) (%sp-i 0) (%sp-n (length %sp-s)))
				        (while (< %sp-i %sp-n)
				          (let ((%sp-j (position #\\: %sp-s :start %sp-i)))
				            (let ((%sp-len (parse-integer (subseq %sp-s %sp-i %sp-j)))
				                  (%sp-k (+ %sp-j 1)))
				              (push (subseq %sp-s %sp-k (+ %sp-k %sp-len)) %sp-acc)
				              (setq %sp-i (+ %sp-k %sp-len)))))
				        (nreverse %sp-acc))))
				""");
		SOURCES.put(LispNames.DO_SYMBOLS_LIST_INTERNAL,
				"""
						(defun %do-symbols-list (%dsl-pkg %dsl-ext-only %dsl-op)
						  (let ((%dsl-p (find-package %dsl-pkg))
						        (%dsl-acc nil))
						    (if (null %dsl-p)
						        (error (concatenate 'string %dsl-op ": no package named ~A") %dsl-pkg))
						    ;; The baked rows carry each universe as one packed string
						    ;; (quoted symbols would trip the backends' reference scans
						    ;; and grow the top-level body past the wasmtime bound);
						    ;; unpack, then materialize through intern.
						    (dolist (%dsl-e %baked-packages%)
						      (when (string= (string %dsl-p) (car %dsl-e))
						        (setq %dsl-acc (append %dsl-acc (mapcar (lambda (%dsl-s) (intern %dsl-s))
						                                               (%split-packed (if %dsl-ext-only (fifth %dsl-e) (cadddr %dsl-e))))))))
						    ;; A runtime entry: its own and imported members (the external
						    ;; ones alone for do-external-symbols), plus -- for do-symbols --
						    ;; what it inherits: the externals of every package it uses, a
						    ;; runtime entry's external members or a baked row's external
						    ;; universe.
						    (dolist (%dsl-r %runtime-packages%)
						      (when (string= (string %dsl-p) (string (car %dsl-r)))
						        (dolist (%dsl-m (cadddr %dsl-r))
						          (when (or (not %dsl-ext-only) (eq (caddr %dsl-m) :external))
						            (setq %dsl-acc (append %dsl-acc (list (cadr %dsl-m))))))
						        (unless %dsl-ext-only
						          (dolist (%dsl-u (cadr %dsl-r))
						            (let ((%dsl-ue (%runtime-package-find (string %dsl-u))))
						              (if %dsl-ue
						                  (dolist (%dsl-m (cadddr %dsl-ue))
						                    (when (eq (caddr %dsl-m) :external)
						                      (setq %dsl-acc (append %dsl-acc (list (cadr %dsl-m))))))
						                  (dolist (%dsl-e %baked-packages%)
						                    (when (string= (string %dsl-u) (car %dsl-e))
						                      (setq %dsl-acc (append %dsl-acc (mapcar (lambda (%dsl-s) (intern %dsl-s))
						                                                             (%split-packed (fifth %dsl-e)))))))))))))
						    (remove-duplicates %dsl-acc :test #'equal)))
						""");
		SOURCES.put(LispNames.PACKAGE_ERROR_PACKAGE, """
				(defun package-error-package (%pep-c) (slot-value %pep-c 'package))
				""");
		SOURCES.put(LispNames.PACKAGEP, """
				(defun packagep (%pp-x)
				  (if (and (or (stringp %pp-x) (symbolp %pp-x)) (find-package %pp-x)) t nil))
				""");
		SOURCES.put(LispNames.MAKE_PACKAGE,
				"""
						(defun make-package (%mp-name &key use nicknames)
						  (let ((%mp-n (string-upcase (string %mp-name)))
						        (%mp-u (if (null use) nil (if (listp use) use (list use))))
						        (%mp-nn (if (null nicknames) nil (if (listp nicknames) nicknames (list nicknames)))))
						    (if (string= %mp-n "")
						        (error 'package-error :package nil :format-control "MAKE-PACKAGE expects a package name"))
						    (if (or (%baked-package-find %mp-n) (%runtime-package-find %mp-n))
						        (error 'package-error :package (intern %mp-n :keyword) :format-control (concatenate 'string "MAKE-PACKAGE: package already exists: " %mp-n)))
						    (setq %mp-u (mapcar (lambda (%mp-e)
						                          (or (find-package %mp-e)
						                              (error 'package-error :package (intern (string-upcase (string %mp-e)) :keyword) :format-control (concatenate 'string "MAKE-PACKAGE: no such package: " (string %mp-e)))))
						                        %mp-u))
						    (setq %mp-nn (sort (mapcar (lambda (%mp-e)
						                           (let ((%mp-k (string-upcase (string %mp-e))))
						                             (if (or (%baked-package-find %mp-k) (%runtime-package-find %mp-k))
						                                 (error 'package-error :package (intern %mp-k :keyword) :format-control (concatenate 'string "MAKE-PACKAGE: package already exists: " %mp-k))
						                                 %mp-k)))
						                         %mp-nn) #'string<))
						    (push (list (intern %mp-n :keyword) %mp-u %mp-nn nil nil) %runtime-packages%)
						    (intern %mp-n :keyword)))
						""");
		SOURCES.put(LispNames.DELETE_PACKAGE,
				"""
						(defun delete-package (%dp-pkg)
						  (let ((%dp-s (string %dp-pkg)))
						    (if (%runtime-package-find %dp-s)
						        (progn (setq %runtime-packages%
						                    (remove-if (lambda (%dp-e)
						                                 (or (string= %dp-s (string (car %dp-e)))
						                                     (member %dp-s (caddr %dp-e) :test #'string=)))
						                               %runtime-packages%))
						               t)
						        (if (%baked-package-find %dp-s)
						            (error 'package-error :package (intern (string-upcase %dp-s) :keyword) :format-control (concatenate 'string "DELETE-PACKAGE: cannot delete read/compile-time package: " (string-upcase %dp-s)))
						            (error 'package-error :package (intern (string-upcase %dp-s) :keyword) :format-control (concatenate 'string "DELETE-PACKAGE: no such package: " %dp-s))))))
						""");
		SOURCES.put(LispNames.RENAME_PACKAGE,
				"""
						(defun rename-package (%rp-pkg %rp-new &optional %rp-new-nicknames)
						  (let ((%rp-s (string %rp-pkg))
						        (%rp-n (string-upcase (string %rp-new)))
						        (%rp-nn (if (null %rp-new-nicknames) nil (if (listp %rp-new-nicknames) %rp-new-nicknames (list %rp-new-nicknames)))))
						    (let ((%rp-old (%runtime-package-find %rp-s)))
						      (if (null %rp-old)
						          (if (%baked-package-find %rp-s)
						              (error 'package-error :package (intern (string-upcase %rp-s) :keyword) :format-control (concatenate 'string "RENAME-PACKAGE: cannot rename read/compile-time package: " (string-upcase %rp-s)))
						              (error 'package-error :package (intern (string-upcase %rp-s) :keyword) :format-control (concatenate 'string "RENAME-PACKAGE: no such package: " %rp-s))))
						      (if (string= %rp-n "")
						          (error 'package-error :package nil :format-control "RENAME-PACKAGE expects a new package name"))
						      (if (and (not (string= %rp-n (string (car %rp-old))))
						               (or (%baked-package-find %rp-n) (%runtime-package-find %rp-n)))
						          (error 'package-error :package (intern %rp-n :keyword) :format-control (concatenate 'string "RENAME-PACKAGE: package already exists: " %rp-n)))
						      (setq %rp-nn (sort (mapcar (lambda (%rp-e)
						                             (let ((%rp-k (string-upcase (string %rp-e))))
						                               (if (and (not (member %rp-k (caddr %rp-old) :test #'string=))
						                                        (or (%baked-package-find %rp-k) (%runtime-package-find %rp-k)))
						                                   (error 'package-error :package (intern %rp-k :keyword) :format-control (concatenate 'string "RENAME-PACKAGE: package already exists: " %rp-k))
						                                   %rp-k)))
						                           %rp-nn) #'string<))
						      (setq %runtime-packages%
						            (remove-if (lambda (%rp-e)
						                         (or (string= %rp-s (string (car %rp-e)))
						                             (member %rp-s (caddr %rp-e) :test #'string=)))
						                       %runtime-packages%))
						      (push (list (intern %rp-n :keyword) (cadr %rp-old) %rp-nn (cadddr %rp-old) (nth 4 %rp-old))
						            %runtime-packages%)
						      (intern %rp-n :keyword))))
						""");
		SOURCES.put(LispNames.PACKAGE_NICKNAMES,
				"""
						(defun package-nicknames (%pn2-pkg)
						  (let ((%pn2-s (string %pn2-pkg))
						        (%pn2-e nil))
						    (setq %pn2-e (or (%runtime-package-find %pn2-s) (%baked-package-find %pn2-s)))
						    (if (null %pn2-e)
						        (error 'package-error :package (intern (string-upcase %pn2-s) :keyword) :format-control (concatenate 'string "PACKAGE-NICKNAMES: no such package: " %pn2-s))
						        (copy-list (caddr %pn2-e)))))
						""");
		SOURCES.put(LispNames.FIND_ALL_SYMBOLS, """
				(defun find-all-symbols (%fas-sym &optional %fas-pkg)
				  (%package-symbols-where (if (symbolp %fas-sym) (symbol-name %fas-sym) (string %fas-sym)) t %fas-pkg))
				""");
		SOURCES.put(LispNames.APROPOS_LIST, """
				(defun apropos-list (%apl-str &optional %apl-pkg)
				  (%package-symbols-where (string-upcase (string %apl-str)) nil %apl-pkg))
				""");
		SOURCES.put(LispNames.APROPOS, """
				(defun apropos (%ap-str &optional %ap-pkg)
				  (dolist (%ap-s (apropos-list %ap-str %ap-pkg)) (print %ap-s)))
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
				      (%file-error %tn-path (format nil "TRUENAME: no such file ~A" %tn-path))))
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
		// %pathname-directory-component: the INVERSE of the per-component rendering
		// %pathname-directory-string performs, so (make-pathname :directory
		// (pathname-directory p)) reproduces p's directory for every shape, wild
		// components included. Only an EXACT * / ** / .. is a keyword -- "a*" is an
		// ordinary (wild-matching) name, which is what CL calls a :wild component's
		// pattern form and what SBCL answers as a string here too.
		SOURCES.put(LispNames.PATHNAME_DIRECTORY_COMPONENT, """
				(defun %pathname-directory-component (%pdc-s)
				  (cond ((string= %pdc-s "*") :wild)
				        ((string= %pdc-s "**") :wild-inferiors)
				        ((string= %pdc-s "..") :up)
				        (t %pdc-s)))
				""");
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
				                (setq %pd-acc
				                      (cons (%pathname-directory-component
				                              (subseq %pd-p %pd-start %pd-i))
				                            %pd-acc)))
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
				                     ((%wild-inferiors-at %wm-pat p)
				                      (let ((%wm-r nil) (%wm-e s) (%wm-done nil))
				                        (do () ((or %wm-r %wm-done) %wm-r)
				                          (if (m (+ p 3) %wm-e)
				                              (setq %wm-r t)
				                              (let ((%wm-k (position #\\/ %wm-str :start %wm-e)))
				                                (if %wm-k
				                                    (setq %wm-e (+ %wm-k 1))
				                                    (setq %wm-done t)))))))
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
		// %make-directories answers nil rather than signalling when the host refuses --
		// the delete-file / rename-file shape (%file-error carrying the designator as
		// given), so "a directory the host refuses is a file-error" has one definition.
		SOURCES.put(LispNames.ENSURE_DIRECTORIES_EXIST, """
				(defun ensure-directories-exist (%ede-path)
				  (let* ((%ede-x (%path-ns %ede-path))
				         (%ede-p (if (stringp %ede-x) %ede-x ""))
				         (%ede-s (position #\\/ %ede-p :from-end t))
				         (%ede-d (if %ede-s (subseq %ede-p 0 (+ %ede-s 1)) "")))
				    (if (string= %ede-d "")
				        %ede-path
				        (if (%make-directories %ede-d)
				            %ede-path
				            (%file-error %ede-path
				                         (format nil "ENSURE-DIRECTORIES-EXIST: cannot create directory ~A"
				                                 %ede-d))))))
				""");
		// %directory-in: directory's per-directory half -- the entries of ONE directory
		// prefix that the final (name) component matches, or the pathspec itself when
		// that component is not wild. Split out of directory so the wild-DIRECTORY walk
		// below runs the identical name matching in every directory it expanded to.
		SOURCES.put(LispNames.DIRECTORY_IN, """
				(defun %directory-in (%din-d %din-n)
				  (if (or (position #\\* %din-n) (position #\\? %din-n))
				      (let ((%din-e (%list-directory (if (string= %din-d "") "." %din-d)))
				            (%din-acc nil))
				        (dolist (%din-x (cdr %din-e))
				          (let ((%din-b (if (char= (char %din-x (- (length %din-x) 1)) #\\/)
				                            (subseq %din-x 0 (- (length %din-x) 1))
				                            %din-x)))
				            (when (or (string= %din-n "*.*")
				                      (and (%wild-match %din-n %din-b)
				                           (or (%pathname-typed-p %din-n)
				                               (not (%pathname-typed-p %din-b)))))
				              (setq %din-acc (cons (concatenate 'string %din-d %din-x) %din-acc)))))
				        %din-acc)
				      (let* ((%din-p (concatenate 'string %din-d %din-n))
				             (%din-f (%dir-namestring %din-p)))
				        (cond ((%list-directory (if (string= %din-f "") "." %din-f)) (list %din-f))
				              ((and (string/= %din-n "") (probe-file %din-p)) (list %din-p))
				              (t nil)))))
				""");
		SOURCES.put(LispNames.DIRECTORY_SUBDIRS, """
				(defun %directory-subdirs (%dsd-base)
				  (let ((%dsd-e (%list-directory (if (string= %dsd-base "") "." %dsd-base)))
				        (%dsd-acc nil))
				    (dolist (%dsd-x (cdr %dsd-e))
				      (when (char= (char %dsd-x (- (length %dsd-x) 1)) #\\/)
				        (setq %dsd-acc (cons %dsd-x %dsd-acc))))
				    (sort %dsd-acc #'string<)))
				""");
		SOURCES.put(LispNames.PATH_DIR_PARTS, """
				(defun %path-dir-parts (%pdp-d)
				  (let ((%pdp-acc nil) (%pdp-start 0))
				    (dotimes (%pdp-i (length %pdp-d))
				      (when (char= (char %pdp-d %pdp-i) #\\/)
				        (when (> %pdp-i %pdp-start)
				          (setq %pdp-acc (cons (subseq %pdp-d %pdp-start %pdp-i) %pdp-acc)))
				        (setq %pdp-start (+ %pdp-i 1))))
				    (reverse %pdp-acc)))
				""");
		// %wild-dirs: the directory-component half of the walk. ** contributes the base
		// ITSELF before descending, which is what makes :wild-inferiors match zero levels
		// -- (directory "a/**/*.lisp") answers the files directly in a/ as well as those
		// below it, exactly as SBCL does. Any other wild component descends exactly one
		// level, matched by the same %wild-match the name component uses. The recursion
		// terminates because %directory-subdirs only ever answers entries the host
		// reports below the base.
		SOURCES.put(LispNames.WILD_DIRS, """
				(defun %wild-dirs (%wd-base %wd-comps)
				  (if (null %wd-comps)
				      (list %wd-base)
				      (let ((%wd-c (car %wd-comps)) (%wd-rest (cdr %wd-comps)))
				        (cond ((string= %wd-c "**")
				               (let ((%wd-acc (%wild-dirs %wd-base %wd-rest)))
				                 (dolist (%wd-s (%directory-subdirs %wd-base))
				                   (setq %wd-acc
				                         (append %wd-acc
				                                 (%wild-dirs (concatenate 'string %wd-base %wd-s)
				                                             %wd-comps))))
				                 %wd-acc))
				              ((%wild-component-p %wd-c)
				               (let ((%wd-acc nil))
				                 (dolist (%wd-s (%directory-subdirs %wd-base))
				                   (when (%wild-match %wd-c (subseq %wd-s 0 (- (length %wd-s) 1)))
				                     (setq %wd-acc
				                           (append %wd-acc
				                                   (%wild-dirs (concatenate 'string %wd-base %wd-s)
				                                               %wd-rest)))))
				                 %wd-acc))
				              (t (%wild-dirs (concatenate 'string %wd-base %wd-c "/") %wd-rest))))))
				""");
		SOURCES.put(LispNames.DIRECTORY, """
				(defun directory (%dir-spec)
				  (let* ((%dir-x (%path-ns %dir-spec))
				         (%dir-p (if (stringp %dir-x) %dir-x ""))
				         (%dir-s (position #\\/ %dir-p :from-end t))
				         (%dir-d (if %dir-s (subseq %dir-p 0 (+ %dir-s 1)) ""))
				         (%dir-n (if %dir-s (subseq %dir-p (+ %dir-s 1)) %dir-p)))
				    (mapcar #'pathname
				            (sort (if (%wild-component-p %dir-d)
				                      (let ((%dir-acc nil))
				                        (dolist (%dir-b (%wild-dirs
				                                          (if (char= (char %dir-d 0) #\\/) "/" "")
				                                          (%path-dir-parts %dir-d)))
				                          (setq %dir-acc
				                                (append %dir-acc (%directory-in %dir-b %dir-n))))
				                        %dir-acc)
				                      (%directory-in %dir-d %dir-n))
				                  #'string<))))
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
		// The uiop/stream designator tables (.todo/359): upstream defines
		// call-with-input and call-with-output but does NOT export them, so no
		// uiop resource may define them -- they live here, called by the
		// with-input / with-output Java expansions and by the exported designator
		// readers (input-string, output-string, eval-input) in uiop-stream.lisp.
		// A stream is used as-is, nil is the standard stream, t is the
		// terminal/console stream, a string is a string stream, a pathname is
		// opened. The string arm of the output side appends through the string's fill
		// pointer, as upstream's does.
		SOURCES.put(LispNames.CALL_WITH_INPUT_INTERNAL, """
				(defun %call-with-input (%cwi-input %cwi-function &key ((:keys %cwi-keys)))
				  (cond ((null %cwi-input) (funcall %cwi-function *standard-input*))
				        ((eql %cwi-input t) (funcall %cwi-function *terminal-io*))
				        ((streamp %cwi-input) (funcall %cwi-function %cwi-input))
				        ((stringp %cwi-input)
				         (with-input-from-string (%cwi-s %cwi-input)
				           (funcall %cwi-function %cwi-s)))
				        ((pathnamep %cwi-input)
				         (apply #'uiop/stream:call-with-input-file %cwi-input %cwi-function %cwi-keys))
				        (t (error "CALL-WITH-INPUT: invalid input designator ~S" %cwi-input))))
				""");
		SOURCES.put(LispNames.CALL_WITH_OUTPUT_INTERNAL, """
				(defun %call-with-output (%cwo-output %cwo-function &key ((:element-type %cwo-element-type) 'character))
				  (cond ((null %cwo-output)
				         (with-output-to-string (%cwo-s) (funcall %cwo-function %cwo-s)))
				        ((eql %cwo-output t) (funcall %cwo-function *standard-output*))
				        ((streamp %cwo-output) (funcall %cwo-function %cwo-output))
				        ((stringp %cwo-output)
				         (with-output-to-string (%cwo-s %cwo-output) (funcall %cwo-function %cwo-s)))
				        ((pathnamep %cwo-output)
				         (uiop/stream:call-with-output-file %cwo-output %cwo-function
				                                            :element-type %cwo-element-type))
				        (t (error "CALL-WITH-OUTPUT: invalid output designator ~S" %cwo-output))))
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
		// rontolisp:octets-to-string: the PUBLIC name for the decoder above (.todo/691).
		// A thin delegator, not a second definition -- the lenient policy (an
		// (unsigned-byte 8) vector, never nil, never signals; a byte that leads no
		// valid sequence, one a shorter prefix truncates, and one whose 4-byte
		// assembly falls outside the Unicode range all decode to their OWN byte value
		// as a character) is %octets-to-string's alone, so the http body / read-all /
		// fetch call sites and a user program calling this name see the identical
		// answer for the identical bytes on every backend. Overlong and
		// surrogate-encoding sequences are NOT rejected by the lenient fallback (only
		// the strict fast path -- a platform decoder -- refuses them, and refusing
		// silently falls through to the lenient arm), so they decode to the code
		// point their bits assemble rather than to their own bytes; a rontolisp
		// CHARACTER has no narrower range than any other code point
		// (.kb/characters-code-points.md), so a decoded surrogate is not itself an
		// error here. Pinned by ci-spec `octets-string-conversions`.
		SOURCES.put(LispNames.OCTETS_TO_STRING, """
				(defun rontolisp:octets-to-string (octets)
				  (rontolisp::%octets-to-string octets))
				""");
		// rontolisp:string-to-octets: the encoder half. Total over every CHARACTER
		// value -- 0 to #x10FFFF, surrogates included -- so unlike the decoder it has
		// no malformed case to pin: every code point has exactly one (shortest, i.e.
		// non-overlong) UTF-8 encoding. Round-trips through octets-to-string for
		// every well-formed input; the reverse round-trip (octets-to-string then
		// string-to-octets) only reproduces the original bytes when they were already
		// a well-formed, non-overlong encoding -- a malformed byte decodes to a
		// character whose canonical re-encoding can differ in length from the byte it
		// came from, which is exactly the signal examples/llm/llm.lisp's
		// streaming printer uses to tell a still-arriving multi-byte character from a
		// finished one.
		SOURCES.put(LispNames.STRING_TO_OCTETS, """
				(defun rontolisp:string-to-octets (string)
				  (let ((%s2o-bytes nil))
				    (dotimes (%s2o-i (length string))
				      (let ((%s2o-c (char-code (char string %s2o-i))))
				        (cond ((< %s2o-c #x80) (push %s2o-c %s2o-bytes))
				              ((< %s2o-c #x800)
				               (push (logior #xC0 (ash %s2o-c -6)) %s2o-bytes)
				               (push (logior #x80 (logand %s2o-c #x3F)) %s2o-bytes))
				              ((< %s2o-c #x10000)
				               (push (logior #xE0 (ash %s2o-c -12)) %s2o-bytes)
				               (push (logior #x80 (logand (ash %s2o-c -6) #x3F)) %s2o-bytes)
				               (push (logior #x80 (logand %s2o-c #x3F)) %s2o-bytes))
				              (t
				               (push (logior #xF0 (ash %s2o-c -18)) %s2o-bytes)
				               (push (logior #x80 (logand (ash %s2o-c -12) #x3F)) %s2o-bytes)
				               (push (logior #x80 (logand (ash %s2o-c -6) #x3F)) %s2o-bytes)
				               (push (logior #x80 (logand %s2o-c #x3F)) %s2o-bytes)))))
				    (setq %s2o-bytes (nreverse %s2o-bytes))
				    (let ((%s2o-out (make-array (length %s2o-bytes) :element-type '(unsigned-byte 8)))
				          (%s2o-k 0))
				      (dolist (%s2o-b %s2o-bytes %s2o-out)
				        (setf (aref %s2o-out %s2o-k) %s2o-b)
				        (setq %s2o-k (+ %s2o-k 1))))))
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
		// user-homedir-pathname: the HOME the process was started with, as a DIRECTORY
		// pathname (a trailing separator, per CLHS: the name and type components are
		// nil). The host argument is accepted and ignored -- there is one host. Nil when
		// the variable is unset, which CLHS allows and is the honest answer on a WASI
		// component that was given no environment.
		SOURCES.put(LispNames.USER_HOMEDIR_PATHNAME, """
				(defun user-homedir-pathname (&optional %uh-host)
				  (declare (ignore %uh-host))
				  (let ((%uh-home (%host-getenv "HOME")))
				    (if (and %uh-home (> (length %uh-home) 0))
				        (pathname (concatenate 'string (string-right-trim "/" %uh-home) "/"))
				        nil)))
				""");
		// copy-symbol: a fresh uninterned symbol of the same name. It inherits
		// make-symbol's identity deviation exactly -- two uninterned symbols of one name
		// are eq here, because a symbol IS its spelling and there is no intern table
		// (.kb/symbol-runtime-api.md) -- so the copy is not distinguishable from the
		// original's other copies. copy-props is accepted and ignored: there is no
		// (setf symbol-plist) to carry a plist across.
		SOURCES.put(LispNames.COPY_SYMBOL, """
				(defun copy-symbol (%cs-symbol &optional %cs-copy-props)
				  (declare (ignore %cs-copy-props))
				  (make-symbol (symbol-name %cs-symbol)))
				""");
		// invoke-debugger: there is no debugger to enter on any backend, and CLHS says
		// invoke-debugger never returns, so the honest implementation is to signal the
		// condition unhandled -- which is what entering a debugger that the user then
		// aborts amounts to here. Handlers established OUTSIDE the caller still see it,
		// which is CL's behaviour for a condition the debugger is entered on.
		SOURCES.put(LispNames.INVOKE_DEBUGGER, """
				(defun invoke-debugger (%id-condition)
				  (error %id-condition))
				""");
		// remove-method: a method is a registry row plus a generated defun here, never a
		// first-class object -- there is no method metaobject and no find-method to
		// obtain one from (.kb/clos.md), so no caller can name the method to remove. It
		// exists (a program that loads may reference it) and signals if it is reached.
		// RE-EVALUATE when method metaobjects land.
		SOURCES.put(LispNames.REMOVE_METHOD, """
				(defun remove-method (%rm-generic %rm-method)
				  (declare (ignore %rm-generic %rm-method))
				  (error "remove-method is not supported (no method metaobjects exist to name a method)"))
				""");
		// compile-file / compile-file-pathname: there is no file compiler. The compile
		// backends compile a whole PROGRAM in one pass and a loaded file is spliced into
		// it, so no fasl is ever produced and no pathname names one -- which is also why
		// *compile-file-pathname* / *compile-file-truename* are permanently nil. Both
		// signal rather than answering a fabricated pathname for a file that will never
		// exist; nothing on any path calls them.
		SOURCES.put(LispNames.COMPILE_FILE, """
				(defun compile-file (%cf-input &rest %cf-options)
				  (declare (ignore %cf-input %cf-options))
				  (error "compile-file is not supported (no file compiler: a program is compiled whole)"))
				""");
		SOURCES.put(LispNames.COMPILE_FILE_PATHNAME, """
				(defun compile-file-pathname (%cfp-input &rest %cfp-options)
				  (declare (ignore %cfp-input %cfp-options))
				  (error "compile-file-pathname is not supported (no file compiler: nothing names its output)"))
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
		// subst walks the CDR direction with a loop like tree-equal below -- one frame
		// per
		// element is a StackOverflowError on an ordinary flat list. The structure-sharing
		// test the recursive body made is kept exactly: the spine cells are built
		// speculatively, but the chain is closed at the CELL of the last node whose CAR
		// differed (or at the end when the tail itself differs) and shares the original
		// rest, so an unchanged subtree -- including the spine suffix below the deepest
		// change -- comes back as-is and a tree with no match is returned identically.
		SOURCES.put(LispNames.SUBST_WALK, """
				(defun %subst-walk (%sw-new %sw-match %sw-tree)
				  (if (consp %sw-tree)
				      (let* ((%sw-head (cons nil nil)) (%sw-tail %sw-head) (%sw-p %sw-tree)
				             (%sw-changed nil) (%sw-link nil) (%sw-attach nil))
				        (while (and (consp %sw-p) (not (funcall %sw-match %sw-p)))
				          (let ((%sw-a (%subst-walk %sw-new %sw-match (car %sw-p))))
				            (let ((%sw-cell (cons %sw-a nil)))
				              (setf (cdr %sw-tail) %sw-cell)
				              (setq %sw-tail %sw-cell))
				            (unless (eq %sw-a (car %sw-p))
				              (setq %sw-changed t)
				              (setq %sw-link %sw-tail)
				              (setq %sw-attach (cdr %sw-p))))
				          (setq %sw-p (cdr %sw-p)))
				        (let ((%sw-d (if (consp %sw-p) %sw-new (%subst-walk %sw-new %sw-match %sw-p))))
				          (unless (eq %sw-d %sw-p)
				            (setq %sw-changed t)
				            (setq %sw-link %sw-tail)
				            (setq %sw-attach %sw-d))
				          (if %sw-changed
				              (progn (setf (cdr %sw-link) %sw-attach) (cdr %sw-head))
				              %sw-tree)))
				      (if (funcall %sw-match %sw-tree) %sw-new %sw-tree)))
				""");
		SOURCES.put(LispNames.SUBST, """
				(defun subst (new old tree &key test test-not key)
				  (%subst-walk new
				               (if test-not
				                   (lambda (%sb2-x)
				                     (not (funcall test-not old (if key (funcall key %sb2-x) %sb2-x))))
				                   (lambda (%sb2-x)
				                     (if (funcall (or test #'eql) old
				                                  (if key (funcall key %sb2-x) %sb2-x))
				                         t
				                         nil)))
				               tree))
				""");
		// The -if / -if-not spellings differ from subst only in the MATCH function they
		// hand the shared walk: the predicate over the (optionally keyed) subtree, taken
		// straight or negated. Nothing about the copying, the structure sharing or the
		// dotted-tail handling is restated.
		SOURCES.put(LispNames.SUBST_IF, """
				(defun subst-if (new predicate tree &key key)
				  (%subst-walk new
				               (lambda (%si-x)
				                 (if (funcall predicate (if key (funcall key %si-x) %si-x)) t nil))
				               tree))
				""");
		SOURCES.put(LispNames.SUBST_IF_NOT, """
				(defun subst-if-not (new predicate tree &key key)
				  (%subst-walk new
				               (lambda (%sn-x)
				                 (if (funcall predicate (if key (funcall key %sn-x) %sn-x)) nil t))
				               tree))
				""");
		// CLHS lets every n-prefixed operator of this chapter answer the non-destructive
		// result -- the destructive promise is a licence, not an obligation -- and the
		// walks above already SHARE every unchanged subtree, so an alias is not a stub:
		// it
		// is the same answer with the same sharing, reached without a second walk to
		// maintain. ANSI agrees by construction: its own tests call these through
		// nunion-with-copy / nset-difference-with-check, which copy first precisely
		// because an implementation may or may not mutate.
		SOURCES.put(LispNames.NSUBST, """
				(defun nsubst (new old tree &key test test-not key)
				  (subst new old tree :test test :test-not test-not :key key))
				""");
		SOURCES.put(LispNames.NSUBST_IF, """
				(defun nsubst-if (new predicate tree &key key)
				  (subst-if new predicate tree :key key))
				""");
		SOURCES.put(LispNames.NSUBST_IF_NOT, """
				(defun nsubst-if-not (new predicate tree &key key)
				  (subst-if-not new predicate tree :key key))
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
		// so the scaling loop introduces no rounding error on any backend. A NaN or
		// an infinity has no decomposition (x - x is not 0.0 for exactly those) and
		// is signalled -- the loop never ended on an infinity.
		SOURCES.put(LispNames.DECODE_FLOAT, """
				(defun decode-float (f)
				  (let ((x (abs (float f)))
				        (s (if (< f 0) -1.0 1.0))
				        (e 0))
				    (if (= x 0.0)
				        (values 0.0 0 s)
				        (progn
				          (unless (= (- x x) 0.0)
				            (error "decode-float of a non-finite float is undefined"))
				          (while (>= x 1.0) (setq x (/ x 2.0)) (setq e (+ e 1)))
				          (while (< x 0.5) (setq x (* x 2.0)) (setq e (- e 1)))
				          (values x e s)))))
				""");
		// logcount: the two's-complement population count -- 1 bits for a
		// non-negative integer, 0 bits for a negative one (so (logcount x) is
		// (logcount (lognot x)) for every integer). A negative operand is
		// complemented first; the loop then runs over the magnitude only.
		SOURCES.put(LispNames.LOGCOUNT, """
				(defun logcount (x)
				  (check-type x integer)
				  (if (< x 0)
				      (logcount (lognot x))
				      (let ((c 0))
				        (while (> x 0)
				          (setq c (+ c (logand x 1)))
				          (setq x (ash x -1)))
				        c)))
				""");
		// integer-decode-float: like decode-float but the significand is an
		// integer -- f = significand * 2^exponent * sign, exactly, with the sign
		// an INTEGER (CLHS: a float sign is decode-float's business only). The
		// significand is scaled to exactly (float-digits f) bits -- 53 for a
		// normal double, fewer for a subnormal, whose true precision is shorter
		// (see float-digits below) -- never stripped of trailing factors of two
		// beyond that width: a normal double's significand always fills the full
		// 53 bits, even bits included, matching the fixed-width mantissa field
		// the hardware stores (.todo/896 -- the previous body stripped every
		// factor of two it could, answering 1 0 1.0 for 1.0d0 where SBCL answers
		// 4503599627370496 -52 1). Every intermediate is scalar-small (at most a
		// 53-bit significand), exact on the interpreter, the JVM and WASM-GC.
		// Zero decodes as 0, 0 and its sign; a non-finite float has no
		// decomposition and is signalled (x - x is 0.0 for every finite float and
		// NaN for an infinity or a NaN -- a NaN used to loop forever).
		SOURCES.put(LispNames.INTEGER_DECODE_FLOAT, """
				(defun integer-decode-float (f)
				  (check-type f float)
				  (if (= f 0.0)
				      (values 0 0 (if (< f 0) -1 1))
				      (if (not (= (- f f) 0.0))
				          (error "integer-decode-float of a non-finite float is undefined")
				          (let* ((a (abs f))
				                 (e 0)
				                 (digits (float-digits a))
				                 (hi (expt 2.0 digits))
				                 (lo (expt 2.0 (1- digits))))
				            (while (>= a hi)
				              (setq a (/ a 2.0))
				              (setq e (+ e 1)))
				            (while (< a lo)
				              (setq a (* a 2.0))
				              (setq e (- e 1)))
				            (values (truncate a) e (if (< f 0) -1 1))))))
				""");
		// float-sign: the sign of the first float as a float -- 1.0 or -1.0, or
		// the second float's magnitude with the first float's sign. A comparison
		// alone cannot see the sign of a zero, so a zero is divided into 1.0:
		// -0.0 answers negative infinity, +0.0 positive infinity. NaN compares
		// false both ways and answers positive.
		SOURCES.put(LispNames.FLOAT_SIGN, """
				(defun float-sign (f &optional (m 1.0))
				  (check-type f float)
				  (check-type m float)
				  (if (or (< f 0.0) (and (= f 0.0) (< (/ 1.0 f) 0.0)))
				      (- (abs m))
				      (abs m)))
				""");
		// float-digits: the number of radix-2 digits in the significand,
		// including the implicit leading bit -- 53 for every normal double. A
		// zero has no digits; a non-finite float keeps the representation
		// width. A subnormal's significand is shorter: doubling it up to
		// [2^52, 2^53) (each doubling exact in binary floating point) takes one
		// shift per missing leading bit, and the shifted value is the odd-free
		// integer k * 2^(j - 1074) with 53 significant bits -- so k, the true
		// significand, has 53 - (j - 1074) = 1127 - j digits. At most 1126
		// shifts (the least positive double), every intermediate scalar-small
		// and exact on every backend.
		SOURCES.put(LispNames.FLOAT_DIGITS, """
				(defun float-digits (f)
				  (check-type f float)
				  (let ((a (abs f)))
				    (cond ((= a 0.0) 0)
				          ((not (= a a)) 53)
				          ((= (/ 1.0 a) 0.0) 53)
				          ((< a (expt 2.0 -1022))
				           (let ((j 0))
				             (while (< a (expt 2.0 52))
				               (setq a (* a 2.0))
				               (setq j (+ j 1)))
				             (- 1127 j)))
				          (t 53))))
				""");
		// ldb-test: T when any bit of the byte specifier's field is set -- the
		// negation of ldb's zero test. Going through ldb (not a hand-rolled
		// dpb spelling) dodges the deposit-field/dpb mix-up (.todo/037 Slice A:
		// dpb deposits newbyte's LOW size bits, deposit-field its bits AT the
		// field); the inner ldb call validates the argument shapes.
		SOURCES.put(LispNames.LDB_TEST, """
				(defun ldb-test (bytespec integer)
				  (not (zerop (ldb bytespec integer))))
				""");
		// rationalize: the simplest rational within half a ulp of a float on either
		// side (so floating the answer reproduces the input), the integer or ratio
		// itself for an exact input. The float's exact value and ulp come from
		// integer-decode-float as integers, and the simplest rational in the
		// interval is the continued-fraction mediant (one floor jump per term,
		// never a unit walk). An integer-valued float answers its exact integer
		// (already a simplest rational, and the only one that round-trips by
		// construction) -- with no fraction involved this is exact on every
		// backend, and the check runs BEFORE integer-decode-float: since
		// .todo/896 that function answers a full-width (float-digits ax)-bit
		// significand instead of one stripped down to its odd part, an
		// integer-valued float's exponent is no longer reliably non-negative
		// (2.0 decodes to significand 4503599627370496 and exponent -51, not
		// significand 1 and exponent 1), so branching on the exponent's sign
		// would route ordinary whole-number floats through the fractional
		// interval search below with 53-bit-wide intermediates -- exactly the
		// case a backend whose fractions wrap (WASM-GC past i31) cannot carry.
		// The round-trip postcondition on the fractional branch is checked
		// with the same float conversion the callers use; when it fails the
		// exact value is the answer, for that same backend.
		SOURCES.put(LispNames.RATIONALIZE,
				"""
						(defun rationalize (x)
						  (check-type x real)
						  (if (floatp x)
						      (if (= x 0.0)
						          0
						          (let ((ax (abs x)))
						            (unless (= (- ax ax) 0.0)
						              (error "rationalize of a non-finite float is undefined"))
						            (if (= (ftruncate ax) ax)
						                (if (< x 0) (- (truncate ax)) (truncate ax))
						                (multiple-value-bind (sig exp sign) (integer-decode-float ax)
						                  (let* ((u (if (< exp -1022) (/ (expt 2 1074)) (expt 2 (+ exp -53 (integer-length sig)))))
						                         (xc (/ sig (ash 1 (- exp))))
						                         (half (/ u 2))
						                         (r (labels ((simplest (lo hi)
						                                       (let ((flo (floor lo)))
						                                         (cond ((= flo lo) lo)
						                                               ((<= (1+ flo) hi) (1+ flo))
						                                               (t (+ flo (/ (simplest (/ (- hi flo)) (/ (- lo flo))))))))))
						                              (simplest (- xc half) (+ xc half)))))
						                    (if (= (float r ax) ax)
						                        (if (< x 0) (- r) r)
						                        (if (< x 0) (- xc) xc)))))))
						      x))
						""");
		// A LIST operand is read through a cons cursor rather than indexed with elt --
		// elt on a list is an nth walk from the head, so the obvious loop is quadratic
		// (the same defect the replace list SOURCE arm and count-if-not already avoid).
		// The cursor is the map-into shape with the advance folded into the read:
		// (if (consp c) (prog1 (car c) (setq c (cdr c))) (elt seq i)). A NON-list
		// operand pins a nil cursor and keeps indexing; a list whose cursor has run out
		// -- past an out-of-range bound, or onto a dotted tail -- falls back to the very
		// elt call the body used to make, answer and error alike. That fallback is what
		// keeps an invalid bound answering exactly what it always did, which
		// SequenceScanFast declines precisely so this body keeps owning it. Folding the
		// advance into the read is worth the prog1: a SEPARATE (if (consp c) (cdr c) c)
		// step costs a second consp call per element, which on the interpreter's
		// declined path (a string, where the cursor never fires) measured +26%/+36%
		// against +7%/+16% for this shape.
		SOURCES.put(LispNames.MISMATCH, """
				(defun mismatch (seq1 seq2 &key (test #'eql) key (start1 0) end1 (start2 0) end2 from-end)
				  (let* ((e1 (or end1 (length seq1)))
				         (e2 (or end2 (length seq2)))
				         (i start1)
				         (j start2)
				         (c1 (if (and (listp seq1) (integerp start1) (>= start1 0))
				                 (nthcdr start1 seq1)
				                 nil))
				         (c2 (if (and (listp seq2) (integerp start2) (>= start2 0))
				                 (nthcdr start2 seq2)
				                 nil))
				         (result nil)
				         (done nil))
				    (while (not done)
				      (cond ((and (>= i e1) (>= j e2)) (setq done t))
				            ((or (>= i e1) (>= j e2)) (setq result i) (setq done t))
				            (t (let ((a (if (consp c1) (prog1 (car c1) (setq c1 (cdr c1))) (elt seq1 i)))
				                     (b (if (consp c2) (prog1 (car c2) (setq c2 (cdr c2))) (elt seq2 j))))
				                 (if (funcall test (if key (funcall key a) a)
				                              (if key (funcall key b) b))
				                     (progn (setq i (+ i 1)) (setq j (+ j 1)))
				                     (progn (setq result i) (setq done t)))))))
				    result))
				""");
		// copy-tree walks the CDR direction with a loop like tree-equal below -- the
		// recursive shape put one frame per element on the stack and a flat list of ten
		// thousand conses, an ordinary size, overflowed on every backend. Only the CAR
		// direction recurses, so the depth is the tree's nesting depth; the spine is
		// built forward from a dummy head and the non-cons tail is attached as-is.
		SOURCES.put(LispNames.COPY_TREE, """
				(defun copy-tree (tree)
				  (if (consp tree)
				      (let* ((head (cons nil nil)) (tail head) (p tree))
				        (while (consp p)
				          (let ((cell (cons (copy-tree (car p)) nil)))
				            (setf (cdr tail) cell)
				            (setq tail cell))
				          (setq p (cdr p)))
				        (setf (cdr tail) p)
				        (cdr head))
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
		// search: the same cons-cursor treatment as mismatch above, one level harder
		// because the inner walk RESTARTS at every outer position. The needle window
		// never moves, so its cursor is seeded once (h1) and copied into the inner loop;
		// the haystack's cursor advances one cdr per OUTER step (h2) and is likewise
		// copied for the inner walk. Two lists are therefore O(n*m) rather than
		// O(n^2*m). Everything the cursor cannot answer -- a non-list operand, a
		// negative or non-integer start, a bound past the end -- pins a nil cursor and
		// reads through the original elt call.
		// :test-not is spelled out here (not defaulted into :test) so the precedence CLHS
		// leaves to the implementation matches every other scan in this codebase: a
		// :test spelled alongside it wins, and only an ABSENT :test falls back to the
		// (complemented) :test-not, then to eql (ANSI's search.order.2 and
		// search-list.16). SequenceScanFast.search already declines any keyword it does
		// not recognize, so a :test-not call simply falls through to this body -- no
		// change needed there.
		SOURCES.put(LispNames.SEARCH, """
				(defun search (seq1 seq2 &key (start1 0) end1 (start2 0) end2 test test-not key from-end)
				  (let* ((e1 (or end1 (length seq1)))
				         (e2 (or end2 (length seq2)))
				         (w (- e1 start1))
				         (h1 (if (and (listp seq1) (integerp start1) (>= start1 0))
				                 (nthcdr start1 seq1)
				                 nil))
				         (h2 (if (and (listp seq2) (integerp start2) (>= start2 0))
				                 (nthcdr start2 seq2)
				                 nil))
				         (result nil))
				    (do ((pos start2 (+ pos 1)))
				        ((or (> (+ pos w) e2) (and result (not from-end))) result)
				      (let ((ok t) (c1 h1) (c2 h2))
				        (do ((i 0 (+ i 1)))
				            ((or (>= i w) (not ok)))
				          (let ((a (if (consp c1)
				                       (prog1 (car c1) (setq c1 (cdr c1)))
				                       (elt seq1 (+ start1 i))))
				                (b (if (consp c2)
				                       (prog1 (car c2) (setq c2 (cdr c2)))
				                       (elt seq2 (+ pos i)))))
				            (let ((ka (if key (funcall key a) a))
				                  (kb (if key (funcall key b) b)))
				              (unless (if test
				                          (funcall test ka kb)
				                          (if test-not (not (funcall test-not ka kb)) (eql ka kb)))
				                (setq ok nil)))))
				        (setq h2 (if (consp h2) (cdr h2) h2))
				        (when ok (setq result pos))))))
				""");
		// count-if-not is count-if over the complemented predicate: count-if now carries
		// the whole keyword set itself (:key and CLHS 17.2.1's bounding keywords), so the
		// only thing left to spell here is the negation. :from-end is NOT a no-op for a
		// count -- it reverses the order the predicate and :key designator are called in,
		// which a side-effecting one sees (ANSI's count-list.9) -- so it is forwarded,
		// not swallowed. A nil :key is defaulted here because the expansion inlines the
		// designator and would call nil.
		SOURCES.put(LispNames.COUNT_IF_NOT, """
				(defun count-if-not (predicate sequence &key from-end (start 0) end key)
				  (count-if (lambda (x) (not (funcall predicate x))) sequence
				            :key (if key key #'identity)
				            :start (if start start 0) :end end :from-end from-end))
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
				        (if (funcall (or test #'eql) ka kb) t nil))))
				""");
		SOURCES.put(LispNames.SET_EXCLUSIVE_OR, """
				(defun set-exclusive-or (list-1 list-2 &key test test-not key)
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
		// The four n-prefixed set operations, aliases for the reason %subst-walk's
		// comment gives. union / intersection / set-difference are EXPANSIONS, so the
		// forwarded keyword values ride LispMacroExpander.KeywordTail's defaulting -- a
		// nil :test binds as eql, a nil :key as identity -- and only the :test-not arm
		// needs spelling out, since a :test spelled beside it would win.
		SOURCES.put(LispNames.NUNION, """
				(defun nunion (list-1 list-2 &key test test-not key)
				  (if test-not
				      (union list-1 list-2 :test-not test-not :key key)
				      (union list-1 list-2 :test test :key key)))
				""");
		SOURCES.put(LispNames.NINTERSECTION, """
				(defun nintersection (list-1 list-2 &key test test-not key)
				  (if test-not
				      (intersection list-1 list-2 :test-not test-not :key key)
				      (intersection list-1 list-2 :test test :key key)))
				""");
		SOURCES.put(LispNames.NSET_DIFFERENCE, """
				(defun nset-difference (list-1 list-2 &key test test-not key)
				  (if test-not
				      (set-difference list-1 list-2 :test-not test-not :key key)
				      (set-difference list-1 list-2 :test test :key key)))
				""");
		SOURCES.put(LispNames.NSET_EXCLUSIVE_OR, """
				(defun nset-exclusive-or (list-1 list-2 &key test test-not key)
				  (set-exclusive-or list-1 list-2 :test test :test-not test-not :key key))
				""");
		// The -if-not spellings of the alist/list scans: the -if operator over the
		// negated
		// predicate, forwarding :key. Written here rather than as three more expansions
		// for the reason count-if-not is -- the negation is the whole difference, and a
		// defun spells it once for all four backends. The duplicate :key ANSI passes
		// (member-if-not.order.2) needs no rule of its own: an ordinary call evaluates
		// every argument form in order and the lambda list takes the first.
		SOURCES.put(LispNames.MEMBER_IF_NOT, """
				(defun member-if-not (predicate list &key key)
				  (member-if (lambda (%mn-x) (not (funcall predicate %mn-x))) list :key key))
				""");
		SOURCES.put(LispNames.ASSOC_IF_NOT, """
				(defun assoc-if-not (predicate alist &key key)
				  (assoc-if (lambda (%an-x) (not (funcall predicate %an-x))) alist :key key))
				""");
		SOURCES.put(LispNames.RASSOC_IF_NOT, """
				(defun rassoc-if-not (predicate alist &key key)
				  (rassoc-if (lambda (%rn-x) (not (funcall predicate %rn-x))) alist :key key))
				""");
		// nbutlast is the one n-prefixed operator here that must really mutate: ANSI's
		// nbutlast.1 asserts the answer is eq to the argument AND that the argument's own
		// cells kept their identity. Length is counted first so a count past the end (or
		// a bignum one -- nbutlast.7 passes most-positive-fixnum + 1) decides without
		// walking, and the cut is a single rplacd on the last cell that survives.
		SOURCES.put(LispNames.NBUTLAST, """
				(defun nbutlast (%nb-list &optional (%nb-n 1))
				  (let ((%nb-len 0) (%nb-p %nb-list))
				    (while (consp %nb-p)
				      (setq %nb-len (+ %nb-len 1))
				      (setq %nb-p (cdr %nb-p)))
				    (if (>= %nb-n %nb-len)
				        nil
				        (let ((%nb-c %nb-list) (%nb-i (- (- %nb-len %nb-n) 1)))
				          (while (> %nb-i 0)
				            (setq %nb-c (cdr %nb-c))
				            (setq %nb-i (- %nb-i 1)))
				          (rplacd %nb-c nil)
				          %nb-list))))
				""");
		// list-length is length's circular-safe sibling: the tortoise/hare walk is what
		// lets it answer nil for a circular list in bounded time instead of hanging, and
		// the two consp guards are what make a dotted or non-list argument LIST-LENGTH's
		// type-error, naming the atom the walk met, rather than a silent count.
		SOURCES.put(LispNames.LIST_LENGTH, """
				(defun list-length (%ll-list)
				  (let ((%ll-n 0) (%ll-fast %ll-list) (%ll-slow %ll-list)
				        (%ll-done nil) (%ll-result nil))
				    (while (not %ll-done)
				      (cond ((null %ll-fast)
				             (setq %ll-result %ll-n)
				             (setq %ll-done t))
				            ((not (consp %ll-fast))
				             (%check-list %ll-fast 'list-length))
				            ((null (cdr %ll-fast))
				             (setq %ll-result (+ %ll-n 1))
				             (setq %ll-done t))
				            ((not (consp (cdr %ll-fast)))
				             (%check-list (cdr %ll-fast) 'list-length))
				            (t (setq %ll-fast (cdr (cdr %ll-fast)))
				               (setq %ll-slow (cdr %ll-slow))
				               (setq %ll-n (+ %ll-n 2))
				               (when (eq %ll-fast %ll-slow)
				                 (setq %ll-result nil)
				                 (setq %ll-done t)))))
				    %ll-result))
				""");
		// tailp: eq against every cons of the spine, eql against the DOTTED terminator --
		// which is X3J13's TAILP-NIL:T reading, and what makes (tailp 'e '(a b . e)) true
		// and (tailp "abcde" (list* 'a 'b (copy-seq "abcde"))) false.
		SOURCES.put(LispNames.TAILP, """
				(defun tailp (%tp-object %tp-list)
				  (do ((%tp-p %tp-list (cdr %tp-p)))
				      ((atom %tp-p) (if (eql %tp-p %tp-object) t nil))
				    (when (eq %tp-p %tp-object) (return t))))
				""");
		// get-properties answers THREE values -- the indicator found, its value, and the
		// tail of the plist starting at it -- so a caller can tell a stored nil from an
		// absent property, which is the whole reason it exists beside getf. A dotted
		// plist walks into (cdr <atom>) and signals there, exactly as CL requires.
		SOURCES.put(LispNames.GET_PROPERTIES, """
				(defun get-properties (%gp-plist %gp-indicators)
				  (do ((%gp-p %gp-plist (cdr (cdr %gp-p))))
				      ((atom %gp-p)
				       (if (null %gp-p)
				           (values nil nil nil)
				           (error 'type-error :datum %gp-plist :expected-type 'list)))
				    (when (member (car %gp-p) %gp-indicators :test #'eq)
				      (return (values (car %gp-p) (car (cdr %gp-p)) %gp-p)))))
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
		// The printer-control renderer: the ONE walk every backend prints through when
		// the program mentions *print-case* / *print-length* / *print-level* /
		// *print-gensym* / *print-base* / *print-radix* -- or calls write /
		// write-to-string with a keyword that binds one (LispMacroExpander rewrites the
		// printing operators onto it). It walks the VALUE rather than the rendered text
		// because only SYMBOL spellings are cased, only lists and vectors are truncated
		// and only a rational is re-based -- a string element keeps its own characters,
		// and a character prints as itself. What it does NOT walk is the containers
		// whose rendering is a runtime form of its own (a structure, an instance, a
		// hash table, an array of rank != 1, a packed float array): those delegate to
		// the raw conversion, so a symbol nested in one keeps the stored (upper-case)
		// spelling and its elements are never truncated. .kb/pretty-printer.md carries
		// the re-evaluation trigger. The vector guard is the twin of the generated
		// %print-object-str's -- the two walks are never both live in one program (a
		// program with a print-object route walks THERE and hands this one leaves), so
		// they have to be read together to stay in step. Under every default the raw
		// conversion answers directly, which is what keeps a program that merely calls
		// write byte-identical in OUTPUT to one that never mentions a variable.
		SOURCES.put(LispNames.PRINT_CASED_INTERNAL, """
				(defun %print-cased (%pc-x %pc-esc)
				  (if (and (eq *print-case* :upcase) (null *print-length*) (null *print-level*)
				           *print-gensym* (eql *print-base* 10) (null *print-radix*)
				           (or (null %pc-esc) (%print-package-raw-p)))
				      (if %pc-esc (%prin1-to-string %pc-x) (%princ-to-string %pc-x))
				      (%pc-walk %pc-x %pc-esc nil 0 0)))
				""");
		// The recursive half of %print-cased, carrying the cycle guard the raw
		// renderers carry (RenderCycleGuard, .kb/pretty-printer.md): the rendering path
		// and its depth thread through as arguments, a cons or vector already on the
		// path -- or the frame past 256 -- prints "#", and the cdr chain's cycle-start
		// cell (Floyd, %pc-chain-stop) prints as the " . #" improper tail on its second
		// arrival. The *print-level* depth (%pc-lvl) is a SEPARATE counter from the
		// guard's: the 'x / #'x abbreviation is transparent to it (SBCL prints
		// (a '(b)) as (A '#) under level 1 and ''(b) as '(B)) but still opens a guard
		// frame, exactly like the raw renderer's. *print-length* counts the elements a
		// list or vector has printed and spells the rest as "..." -- only when the rest
		// is a cons, so (1 2 . 3) under length 2 keeps its dotted tail. A gensym loses
		// its #: under a nil *print-gensym*, a rational takes %print-radixed when
		// *print-base* / *print-radix* are off their defaults, and a symbol spelling
		// goes through %print-case-fold. The guard is the twin of the
		// %print-object-str walk's (%pos-walk); the two walks are never both live in
		// one program, and have to stay in step.
		SOURCES.put(LispNames.PRINT_CASED_WALK_INTERNAL,
				"""
						(defun %pc-walk (%pc-x %pc-esc %pc-path %pc-depth %pc-lvl)
						  (cond ((symbolp %pc-x)
						         (%pc-fold
						           (let ((%pc-s (if %pc-esc (%prin1-to-string %pc-x) (%princ-to-string %pc-x))))
						             (cond ((and %pc-esc (null *print-gensym*) (> (length %pc-s) 2)
						                         (char= (char %pc-s 0) #\\#) (char= (char %pc-s 1) #\\:))
						                    (subseq %pc-s 2))
						                   (%pc-esc (%pc-unqualified %pc-x %pc-s))
						                   (t %pc-s)))))
						        ((consp %pc-x)
						         (if (or (%pc-on-path %pc-x %pc-path) (>= %pc-depth 256))
						             "#"
						             (let ((%pc-sub (cons %pc-x %pc-path)) (%pc-subd (+ %pc-depth 1))
						                   (%pc-subl (+ %pc-lvl 1)))
						               (cond ((and (symbolp (car %pc-x)) (consp (cdr %pc-x)) (null (cddr %pc-x))
						                           (or (eq (car %pc-x) 'quote) (eq (car %pc-x) 'function)))
						                      (concatenate 'string (if (eq (car %pc-x) 'quote) "'" "#'")
						                                   (%pc-walk (cadr %pc-x) %pc-esc %pc-sub %pc-subd %pc-lvl)))
						                     ((and *print-level* (>= %pc-lvl *print-level*)) "#")
						                     (t
						                      (let ((%pc-acc "(") (%pc-cur %pc-x) (%pc-sep "") (%pc-n 0)
						                            (%pc-stop (%pc-chain-stop %pc-x)) (%pc-seen nil) (%pc-done nil))
						                        (while (and (consp %pc-cur) (not %pc-done))
						                          (cond ((and %pc-seen (eq %pc-cur %pc-stop))
						                                 (setq %pc-done :cycle))
						                                ((and *print-length* (>= %pc-n *print-length*))
						                                 (setq %pc-acc (concatenate 'string %pc-acc %pc-sep "..."))
						                                 (setq %pc-done :length))
						                                (t
						                                 (when (eq %pc-cur %pc-stop)
						                                   (setq %pc-seen t))
						                                 (setq %pc-acc (concatenate 'string %pc-acc %pc-sep
						                                                            (%pc-walk (car %pc-cur) %pc-esc %pc-sub %pc-subd %pc-subl)))
						                                 (setq %pc-sep " ")
						                                 (setq %pc-n (+ %pc-n 1))
						                                 (setq %pc-cur (cdr %pc-cur)))))
						                        (cond ((eq %pc-done :cycle)
						                               (concatenate 'string %pc-acc " . #)"))
						                              ((eq %pc-done :length)
						                               (concatenate 'string %pc-acc ")"))
						                              (t
						                               (unless (null %pc-cur)
						                                 (setq %pc-acc (concatenate 'string %pc-acc " . "
						                                                            (%pc-walk %pc-cur %pc-esc %pc-sub %pc-subd %pc-subl))))
						                               (concatenate 'string %pc-acc ")")))))))))
						        ((and (vectorp %pc-x) (not (stringp %pc-x)) (eql (array-rank %pc-x) 1)
						              (not (equal (array-element-type %pc-x) 'single-float))
						              (not (equal (array-element-type %pc-x) 'double-float)))
						         (if (or (%pc-on-path %pc-x %pc-path) (>= %pc-depth 256)
						                 (and *print-level* (>= %pc-lvl *print-level*)))
						             "#"
						             (let ((%pc-acc "#(") (%pc-i 0) (%pc-n (length %pc-x)) (%pc-sep "")
						                   (%pc-sub (cons %pc-x %pc-path)) (%pc-subd (+ %pc-depth 1))
						                   (%pc-subl (+ %pc-lvl 1)))
						               (when (and *print-length* (< *print-length* %pc-n))
						                 (setq %pc-n *print-length*))
						               (while (< %pc-i %pc-n)
						                 (setq %pc-acc (concatenate 'string %pc-acc %pc-sep
						                                            (%pc-walk (aref %pc-x %pc-i) %pc-esc %pc-sub %pc-subd %pc-subl)))
						                 (setq %pc-sep " ")
						                 (setq %pc-i (+ %pc-i 1)))
						               (when (< %pc-n (length %pc-x))
						                 (setq %pc-acc (concatenate 'string %pc-acc %pc-sep "...")))
						               (concatenate 'string %pc-acc ")"))))
						        ((and (rationalp %pc-x) (or (not (eql *print-base* 10)) *print-radix*))
						         (%pc-radixed %pc-x))
						        (%pc-esc (%prin1-to-string %pc-x))
						        (t (%princ-to-string %pc-x))))
						""");
		SOURCES.put(LispNames.PRINT_CASED_ON_PATH_INTERNAL, """
				(defun %pc-on-path (%pc-x %pc-path)
				  (let ((%pc-c %pc-path) (%pc-hit nil))
				    (while (consp %pc-c)
				      (when (eq (car %pc-c) %pc-x)
				        (setq %pc-hit t))
				      (setq %pc-c (cdr %pc-c)))
				    %pc-hit))
				""");
		SOURCES.put(LispNames.PRINT_CASED_CHAIN_STOP_INTERNAL, """
				(defun %pc-chain-stop (%pc-x)
				  (let ((%pc-slow %pc-x) (%pc-fast %pc-x) (%pc-hit nil) (%pc-end nil))
				    (while (and (not %pc-hit) (not %pc-end))
				      (if (and (consp %pc-fast) (consp (cdr %pc-fast)))
				          (progn
				            (setq %pc-fast (cdr (cdr %pc-fast)))
				            (setq %pc-slow (cdr %pc-slow))
				            (when (eq %pc-slow %pc-fast)
				              (setq %pc-hit t)))
				          (setq %pc-end t)))
				    (if %pc-hit
				        (progn
				          (setq %pc-slow %pc-x)
				          (while (not (eq %pc-slow %pc-fast))
				            (setq %pc-slow (cdr %pc-slow))
				            (setq %pc-fast (cdr %pc-fast)))
				          %pc-slow)
				        nil)))
				""");
		// The prin1 text of a symbol with its package qualifier dropped when the symbol
		// is accessible in the current *package* (CLHS 22.1.3.3.1: no qualifier for a
		// symbol that is the package's own, inherited through :use as an external, or
		// imported). The qualifier is parsed off the RAW text -- a keyword's ":", a
		// gensym's "#:" and an escaped |...| member are left alone -- and the
		// accessibility question goes to %symbol-print-bare-p: the live registry on the
		// interpreter, the baked SymbolPrintTable on the compile paths
		// (.kb/pretty-printer.md). The qualifier's colon count says whether the symbol
		// is external, which is what the structural half of that answer reads.
		SOURCES.put(LispNames.PRINT_CASED_UNQUALIFIED_INTERNAL, """
				(defun %pc-unqualified (%pu-x %pu-s)
				  (let ((%pu-n (length %pu-s)) (%pu-i 0) (%pu-colon nil))
				    (if (or (= %pu-n 0) (char= (char %pu-s 0) #\\:) (char= (char %pu-s 0) #\\#)
				            (char= (char %pu-s 0) #\\|))
				        %pu-s
				        (progn
				          (while (and (< %pu-i %pu-n) (null %pu-colon))
				            (when (char= (char %pu-s %pu-i) #\\:)
				              (setq %pu-colon %pu-i))
				            (setq %pu-i (+ %pu-i 1)))
				          (if (null %pu-colon)
				              %pu-s
				              (let ((%pu-ext (if (and (< (+ %pu-colon 1) %pu-n)
				                                      (char= (char %pu-s (+ %pu-colon 1)) #\\:))
				                                 nil
				                                 t)))
				                (if (%symbol-print-bare-p %pu-x (subseq %pu-s 0 %pu-colon) %pu-ext)
				                    (subseq %pu-s (if %pu-ext (+ %pu-colon 1) (+ %pu-colon 2)))
				                    %pu-s)))))))
				""");
		// An integer or ratio under *print-base* / *print-radix*, spelled as SBCL spells
		// it: bare upper-case digits in the base, and with *print-radix* the #b / #o /
		// #x prefix for bases 2 / 8 / 16, a trailing "." for a base-10 INTEGER (a
		// base-10 ratio takes the general prefix: #10r1/2) and #<base>r otherwise.
		SOURCES.put(LispNames.PRINT_RADIXED_INTERNAL, """
				(defun %print-radixed (%pr-n)
				  (let* ((%pr-base *print-base*)
				         (%pr-int (integerp %pr-n))
				         (%pr-digits (if %pr-int
				                         (%print-in-base %pr-n %pr-base)
				                         (concatenate 'string (%print-in-base (numerator %pr-n) %pr-base) "/"
				                                      (%print-in-base (denominator %pr-n) %pr-base)))))
				    (if *print-radix*
				        (cond ((eql %pr-base 2) (concatenate 'string "#b" %pr-digits))
				              ((eql %pr-base 8) (concatenate 'string "#o" %pr-digits))
				              ((eql %pr-base 16) (concatenate 'string "#x" %pr-digits))
				              ((and %pr-int (eql %pr-base 10)) (concatenate 'string %pr-digits "."))
				              (t (concatenate 'string "#" (%print-in-base %pr-base 10) "r" %pr-digits)))
				        %pr-digits)))
				""");
		SOURCES.put(LispNames.PRINT_IN_BASE_INTERNAL, """
				(defun %print-in-base (%pib-n %pib-base)
				  (cond ((< %pib-n 0)
				         (concatenate 'string "-" (%print-in-base (- %pib-n) %pib-base)))
				        ((< %pib-n %pib-base)
				         (string (char "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ" %pib-n)))
				        (t (concatenate 'string (%print-in-base (floor %pib-n %pib-base) %pib-base)
				                        (string (char "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
				                                      (mod %pib-n %pib-base)))))))
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
		// printer control variables around one print, so that is literally what this
		// does. :escape / :readably pick between the two conversions every backend has;
		// :case / :length / :level / :gensym / :base / :radix reach the printer through
		// %print-cased (the printing operators are rewritten onto it for a program that
		// mentions one of those variables, which -- this defun being spliced -- every
		// write user does); :pretty / :circle / :array and the three layout widths are
		// inert because the variable they bind is (.kb/pretty-printer.md).
		SOURCES.put(LispNames.WRITE, """
				(defun write (object &key (stream *standard-output*)
				                          (escape *print-escape*) (readably *print-readably*)
				                          (pretty *print-pretty*) (circle *print-circle*)
				                          (right-margin *print-right-margin*)
				                          (miser-width *print-miser-width*)
				                          (lines *print-lines*)
				                          (pprint-dispatch *print-pprint-dispatch*)
				                          (length *print-length*) (level *print-level*)
				                          (base *print-base*) (radix *print-radix*)
				                          (case *print-case*) (gensym *print-gensym*)
				                          (array *print-array*))
				  (let ((*print-escape* escape) (*print-readably* readably)
				        (*print-pretty* pretty) (*print-circle* circle)
				        (*print-right-margin* right-margin) (*print-miser-width* miser-width)
				        (*print-lines* lines) (*print-pprint-dispatch* pprint-dispatch)
				        (*print-length* length) (*print-level* level)
				        (*print-base* base) (*print-radix* radix)
				        (*print-case* case) (*print-gensym* gensym) (*print-array* array))
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
		// read: exactly ONE datum's characters off the stream, leaving it positioned
		// after them -- CL's contract, and the reason a second datum on the same line
		// is not lost. The scanner below only DELIMITS the datum (it is the lexer's
		// raw skipDatum walk, written in rontolisp); read-from-string then parses the
		// text it collected, so the datum syntax has exactly one definition per
		// backend and this family never has to agree with it about what a value means.
		//
		// The one character a token's terminator has to give back rides the SAME
		// pushback cell unread-char uses (unread-char.lisp on the compile paths, the
		// Environment cell on the interpreter), which is what lets read and read-line
		// be mixed on one stream. See .kb/read-load-streams.md.
		//
		// The (values ...) around the parse is load-bearing: read-from-string answers
		// its stop index as a SECOND value, and CL's read answers ONE value. Left bare,
		// the index would ride out of read's tail through the spill and surface as a
		// second value of every (read s).
		SOURCES.put(LispNames.READ, """
				(defun read (&optional stream (eof-error-p t) eof-value recursive-p)
				  (let ((%rd-out (make-string-output-stream)))
				    (if (%rd-datum stream %rd-out)
				        (let ((%rd-text (get-output-stream-string %rd-out)))
				          (let ((%rd-c (read-char stream nil nil)))
				            (when (and %rd-c (not (%rd-whitespace-p %rd-c)))
				              (unread-char %rd-c stream)))
				          (close %rd-out)
				          (values (read-from-string %rd-text)))
				        (progn
				          (close %rd-out)
				          (if eof-error-p (error 'end-of-file :stream (or stream *standard-input*)) eof-value)))))
				""");
		SOURCES.put(LispNames.RD_DATUM, """
				(defun %rd-datum (%rd-s %rd-out)
				  (let ((%rd-c (%rd-skip %rd-s)))
				    (if (null %rd-c) nil (%rd-dispatch %rd-c %rd-s %rd-out))))
				""");
		SOURCES.put(LispNames.RD_DISPATCH, """
				(defun %rd-dispatch (%rd-c %rd-s %rd-out)
				  (cond
				    ((char= %rd-c #\\() (write-char %rd-c %rd-out) (%rd-list %rd-s %rd-out) t)
				    ((char= %rd-c #\\") (write-char %rd-c %rd-out) (%rd-string %rd-s %rd-out) t)
				    ((char= %rd-c #\\)) (error 'reader-error :format-control "Unexpected ')'"
				                                  :stream (or %rd-s *standard-input*)))
				    ((char= %rd-c #\\#) (%rd-sharp %rd-s %rd-out))
				    ((or (char= %rd-c #\\') (char= %rd-c #\\`))
				     (write-char %rd-c %rd-out)
				     (or (%rd-datum %rd-s %rd-out)
				         (error 'end-of-file :stream (or %rd-s *standard-input*))))
				    ((char= %rd-c #\\,)
				     (write-char %rd-c %rd-out)
				     (let ((%rd-d (read-char %rd-s nil nil)))
				       (cond ((null %rd-d) (error 'end-of-file :stream (or %rd-s *standard-input*)))
				             ((or (char= %rd-d #\\@) (char= %rd-d #\\.))
				              (write-char %rd-d %rd-out))
				             (t (unread-char %rd-d %rd-s)))
				       (or (%rd-datum %rd-s %rd-out)
				           (error 'end-of-file :stream (or %rd-s *standard-input*)))))
				    (t (write-char %rd-c %rd-out)
				       (cond ((char= %rd-c #\\|) (%rd-bars %rd-s %rd-out))
				             ((char= %rd-c #\\\\)
				              (let ((%rd-e (read-char %rd-s nil nil)))
				                (when %rd-e (write-char %rd-e %rd-out))))
				             (t nil))
				       (%rd-token-rest %rd-s %rd-out)
				       t)))
				""");
		SOURCES.put(LispNames.RD_SHARP, """
				(defun %rd-sharp (%rd-s %rd-out)
				  (let ((%rd-d (read-char %rd-s nil nil)))
				    (cond
				      ((null %rd-d) (error 'end-of-file :stream (or %rd-s *standard-input*)))
				      ((char= %rd-d #\\|) (%rd-block-comment %rd-s) (%rd-datum %rd-s %rd-out))
				      ((char= %rd-d #\\\\)
				       (write-char #\\# %rd-out) (write-char %rd-d %rd-out)
				       (%rd-char-literal %rd-s %rd-out))
				      ((char= %rd-d #\\')
				       (write-char #\\# %rd-out) (write-char %rd-d %rd-out)
				       (or (%rd-datum %rd-s %rd-out)
				           (error 'end-of-file :stream (or %rd-s *standard-input*))))
				      ((char= %rd-d #\\()
				       (write-char #\\# %rd-out) (write-char %rd-d %rd-out)
				       (%rd-list %rd-s %rd-out) t)
				      ((or (char= %rd-d #\\+) (char= %rd-d #\\-))
				       (write-char #\\# %rd-out) (write-char %rd-d %rd-out)
				       (or (%rd-datum %rd-s %rd-out)
				           (error 'end-of-file :stream (or %rd-s *standard-input*)))
				       (write-char #\\Space %rd-out)
				       (or (%rd-datum %rd-s %rd-out)
				           (error 'end-of-file :stream (or %rd-s *standard-input*))))
				      ((char= %rd-d #\\.)
				       (write-char #\\# %rd-out) (write-char %rd-d %rd-out)
				       (or (%rd-datum %rd-s %rd-out)
				           (error 'end-of-file :stream (or %rd-s *standard-input*))))
				      (t
				       (write-char #\\# %rd-out) (write-char %rd-d %rd-out)
				       (%rd-token-rest %rd-s %rd-out)
				       (let ((%rd-e (read-char %rd-s nil nil)))
				         (cond ((null %rd-e) t)
				               ((char= %rd-e #\\()
				                (write-char %rd-e %rd-out) (%rd-list %rd-s %rd-out) t)
				               ((char= %rd-e #\\")
				                (write-char %rd-e %rd-out) (%rd-string %rd-s %rd-out) t)
				               (t (unread-char %rd-e %rd-s) t)))))))
				""");
		SOURCES.put(LispNames.RD_LIST, """
				(defun %rd-list (%rd-s %rd-out)
				  (do ((%rd-depth 1))
				      ((= %rd-depth 0) nil)
				    (let ((%rd-c (read-char %rd-s nil nil)))
				      (cond
				        ((null %rd-c) (error 'end-of-file :stream (or %rd-s *standard-input*)))
				        ((char= %rd-c #\\()
				         (write-char %rd-c %rd-out) (setq %rd-depth (+ %rd-depth 1)))
				        ((char= %rd-c #\\))
				         (write-char %rd-c %rd-out) (setq %rd-depth (- %rd-depth 1)))
				        ((char= %rd-c #\\")
				         (write-char %rd-c %rd-out) (%rd-string %rd-s %rd-out))
				        ((char= %rd-c #\\;)
				         (%rd-skip-line %rd-s) (write-char #\\Newline %rd-out))
				        ((char= %rd-c #\\|)
				         (write-char %rd-c %rd-out) (%rd-bars %rd-s %rd-out))
				        ((char= %rd-c #\\\\)
				         (write-char %rd-c %rd-out)
				         (let ((%rd-e (read-char %rd-s nil nil)))
				           (when %rd-e (write-char %rd-e %rd-out))))
				        ((char= %rd-c #\\#)
				         (let ((%rd-d (read-char %rd-s nil nil)))
				           (cond
				             ((null %rd-d) (error 'end-of-file :stream (or %rd-s *standard-input*)))
				             ((char= %rd-d #\\|) (%rd-block-comment %rd-s))
				             ((char= %rd-d #\\\\)
				              (write-char %rd-c %rd-out) (write-char %rd-d %rd-out)
				              (let ((%rd-e (read-char %rd-s nil nil)))
				                (if (null %rd-e)
				                    (error 'end-of-file :stream (or %rd-s *standard-input*))
				                    (write-char %rd-e %rd-out))))
				             ((char= %rd-d #\\()
				              (write-char %rd-c %rd-out) (write-char %rd-d %rd-out)
				              (setq %rd-depth (+ %rd-depth 1)))
				             (t (write-char %rd-c %rd-out) (write-char %rd-d %rd-out)))))
				        (t (write-char %rd-c %rd-out))))))
				""");
		SOURCES.put(LispNames.RD_STRING, """
				(defun %rd-string (%rd-s %rd-out)
				  (do ((%rd-c (read-char %rd-s nil nil) (read-char %rd-s nil nil)))
				      ((or (null %rd-c) (char= %rd-c #\\"))
				       (if (null %rd-c)
				           (error 'end-of-file :stream (or %rd-s *standard-input*))
				           (write-char %rd-c %rd-out)))
				    (write-char %rd-c %rd-out)
				    (when (char= %rd-c #\\\\)
				      (let ((%rd-e (read-char %rd-s nil nil)))
				        (if (null %rd-e)
				            (error 'end-of-file :stream (or %rd-s *standard-input*))
				            (write-char %rd-e %rd-out))))))
				""");
		SOURCES.put(LispNames.RD_BARS, """
				(defun %rd-bars (%rd-s %rd-out)
				  (do ((%rd-c (read-char %rd-s nil nil) (read-char %rd-s nil nil)))
				      ((or (null %rd-c) (char= %rd-c #\\|))
				       (if (null %rd-c)
				           (error 'end-of-file :stream (or %rd-s *standard-input*))
				           (write-char %rd-c %rd-out)))
				    (write-char %rd-c %rd-out)
				    (when (char= %rd-c #\\\\)
				      (let ((%rd-e (read-char %rd-s nil nil)))
				        (when %rd-e (write-char %rd-e %rd-out))))))
				""");
		SOURCES.put(LispNames.RD_TOKEN_REST, """
				(defun %rd-token-rest (%rd-s %rd-out)
				  (do ((%rd-c (read-char %rd-s nil nil) (read-char %rd-s nil nil)))
				      ((or (null %rd-c) (%rd-whitespace-p %rd-c) (%rd-terminating-p %rd-c))
				       (progn (when %rd-c (unread-char %rd-c %rd-s)) %rd-c))
				    (write-char %rd-c %rd-out)
				    (cond ((char= %rd-c #\\\\)
				           (let ((%rd-e (read-char %rd-s nil nil)))
				             (when %rd-e (write-char %rd-e %rd-out))))
				          ((char= %rd-c #\\|) (%rd-bars %rd-s %rd-out))
				          (t nil))))
				""");
		SOURCES.put(LispNames.RD_CHAR_LITERAL, """
				(defun %rd-char-literal (%rd-s %rd-out)
				  (let ((%rd-c (read-char %rd-s nil nil)))
				    (if (null %rd-c)
				        (error 'end-of-file :stream (or %rd-s *standard-input*))
				        (progn (write-char %rd-c %rd-out)
				               (when (alpha-char-p %rd-c) (%rd-token-rest %rd-s %rd-out))
				               t))))
				""");
		SOURCES.put(LispNames.RD_BLOCK_COMMENT, """
				(defun %rd-block-comment (%rd-s)
				  (do ((%rd-depth 1))
				      ((= %rd-depth 0) nil)
				    (let ((%rd-c (read-char %rd-s nil nil)))
				      (cond
				        ((null %rd-c) (error 'end-of-file :stream (or %rd-s *standard-input*)))
				        ((char= %rd-c #\\|)
				         (let ((%rd-d (read-char %rd-s nil nil)))
				           (cond ((null %rd-d) (error 'end-of-file :stream (or %rd-s *standard-input*)))
				                 ((char= %rd-d #\\#) (setq %rd-depth (- %rd-depth 1)))
				                 (t (unread-char %rd-d %rd-s)))))
				        ((char= %rd-c #\\#)
				         (let ((%rd-d (read-char %rd-s nil nil)))
				           (cond ((null %rd-d) (error 'end-of-file :stream (or %rd-s *standard-input*)))
				                 ((char= %rd-d #\\|) (setq %rd-depth (+ %rd-depth 1)))
				                 (t (unread-char %rd-d %rd-s)))))
				        (t nil)))))
				""");
		SOURCES.put(LispNames.RD_SKIP, """
				(defun %rd-skip (%rd-s)
				  (do ((%rd-c (read-char %rd-s nil nil) (read-char %rd-s nil nil)))
				      ((or (null %rd-c)
				           (and (not (%rd-whitespace-p %rd-c)) (not (char= %rd-c #\\;))))
				       %rd-c)
				    (when (char= %rd-c #\\;) (%rd-skip-line %rd-s))))
				""");
		SOURCES.put(LispNames.RD_SKIP_LINE, """
				(defun %rd-skip-line (%rd-s)
				  (do ((%rd-c (read-char %rd-s nil nil) (read-char %rd-s nil nil)))
				      ((or (null %rd-c) (char= %rd-c #\\Newline)) nil)))
				""");
		SOURCES.put(LispNames.RD_WHITESPACE_P, """
				(defun %rd-whitespace-p (%rd-c)
				  (or (char= %rd-c #\\Space) (char= %rd-c #\\Newline) (char= %rd-c #\\Tab)
				      (char= %rd-c #\\Return) (char= %rd-c #\\Page)))
				""");
		SOURCES.put(LispNames.RD_TERMINATING_P, """
				(defun %rd-terminating-p (%rd-c)
				  (or (char= %rd-c #\\() (char= %rd-c #\\)) (char= %rd-c #\\')
				      (char= %rd-c #\\") (char= %rd-c #\\;) (char= %rd-c #\\,)
				      (char= %rd-c #\\`)))
				""");
	}

	private static final Map<String, List<LispVal>> CACHE = new ConcurrentHashMap<>();

	/**
	 * The entries' source text, keyed by member name -- for the tests that pin what the
	 * prelude spells (the multiple-value defuns {@code LispMacroExpander} must know).
	 * @return the source table, unmodifiable
	 */
	static Map<String, String> sources() {
		return java.util.Collections.unmodifiableMap(SOURCES);
	}

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
			return List.copyOf(LispReader.readAllFromString(source, Features.INTERPRETER));
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
		//
		// The program does not change while the fixpoint runs, so what it names, what it
		// defines and what it answers for each entry are computed ONCE: asking each entry
		// as a walk of the whole program, every round, was measured (2026-09-24) at 64%
		// of
		// the front end of a program that loads an ASDF system: hundreds of entries,
		// almost all of them absent, times a program of tens of thousands of conses.
		java.util.Set<String> programNames = symbolNames(resolved, canonical);
		java.util.Set<String> programDefines = new java.util.HashSet<>();
		for (LispVal form : resolved) {
			String definedHere = defunName(form);
			if (definedHere != null) {
				programDefines.add(canonical ? definedHere : member(definedHere));
			}
		}
		Map<String, Boolean> usedByProgram = new java.util.HashMap<>();
		java.util.Set<String> referenced = new java.util.LinkedHashSet<>();
		boolean grew = true;
		while (grew) {
			grew = false;
			for (String name : SOURCES.keySet()) {
				String defined = definedName(name);
				if (referenced.contains(name) || programDefines.contains(canonical ? defined : member(defined))) {
					continue;
				}
				Boolean known = usedByProgram.get(name);
				boolean used;
				if (known != null) {
					used = known;
				}
				else {
					used = programNames.contains(canonical ? defined : member(defined))
							|| referencedBySurfaceForm(name, resolved, canonical);
					usedByProgram.put(name, used);
				}
				for (String pulled : referenced) {
					// Prelude sources spell each other exactly as they define
					// themselves, so the entry-to-entry edges stay member-matched:
					// they carry no package ambiguity to resolve.
					used = used || entryMemberNames(pulled).contains(member(name))
							|| (PRINT_CONTROL_ENTRIES.contains(name)
									&& referencedBySurfaceForm(name, formsFor(pulled), false));
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
	/**
	 * The printer-control entries whose selection also asks
	 * {@link #referencedBySurfaceForm} over each PULLED entry's forms: the prelude
	 * {@code write} binds every printer-control variable, so a {@code write} user carries
	 * the renderer and its leaves.
	 */
	private static final java.util.Set<String> PRINT_CONTROL_ENTRIES = java.util.Set.of(LispNames.PRINT_CASED_INTERNAL,
			LispNames.PRINT_CASE_FOLD_INTERNAL, LispNames.PRINT_RADIXED_INTERNAL);

	static boolean referencedBySurfaceForm(String entry, List<LispVal> program, boolean canonical) {
		if (LispNames.MAKE_BROADCAST_STREAM_INTERNAL.equals(entry)) {
			return referencesName(program, LispNames.MAKE_BROADCAST_STREAM, canonical)
					|| referencesName(program, LispNames.BROADCAST_STREAM_STREAMS, canonical);
		}
		// The composite-stream entries define a whole cluster -- the constructor, the
		// accessors and the Gray class/methods. A program that names only an ACCESSOR
		// (the stream arrives from elsewhere, a library hands it over) must still splice
		// the cluster, which the constructor-name-based selection would miss -- so key on
		// ANY of the surface names. Same for the interpreter's per-name loading.
		if (LispNames.MAKE_TWO_WAY_STREAM.equals(entry)) {
			return referencesAny(program, canonical, LispNames.MAKE_TWO_WAY_STREAM,
					LispNames.TWO_WAY_STREAM_INPUT_STREAM, LispNames.TWO_WAY_STREAM_OUTPUT_STREAM);
		}
		if (LispNames.MAKE_ECHO_STREAM.equals(entry)) {
			return referencesAny(program, canonical, LispNames.MAKE_ECHO_STREAM, LispNames.ECHO_STREAM_INPUT_STREAM,
					LispNames.ECHO_STREAM_OUTPUT_STREAM);
		}
		if (LispNames.MAKE_CONCATENATED_STREAM.equals(entry)) {
			return referencesAny(program, canonical, LispNames.MAKE_CONCATENATED_STREAM,
					LispNames.CONCATENATED_STREAM_STREAMS);
		}
		// %make-array-et: the call is produced by
		// LispMacroExpander.lowerRuntimeElementTypeMakeArray inside the expression
		// compilers, after this pass, so selection keys on the SURFACE fact -- a
		// make-array whose :element-type is a runtime designator. A site the helper
		// cannot serve (a fill pointer, adjustability, :initial-contents) is not counted:
		// that one keeps the inline expansion, and a program with only such sites must
		// splice nothing.
		if (LispNames.MAKE_ARRAY_ET_INTERNAL.equals(entry)) {
			return am.ik.rontolisp.macro.LispMacroExpander.callsMakeArrayWithRuntimeElementType(program, false);
		}
		if (LispNames.MAKE_ARRAY_ET_FP_INTERNAL.equals(entry)) {
			return am.ik.rontolisp.macro.LispMacroExpander.callsMakeArrayWithRuntimeElementType(program, true);
		}
		// %symbol-in-package: the find-symbol / intern lowerings call it from inside the
		// expression compilers, after this pass; the surface fact is a call spelling a
		// computed package designator. A site synthesized later keeps the inline form.
		if (LispNames.SYMBOL_IN_PACKAGE_INTERNAL.equals(entry)) {
			return am.ik.rontolisp.macro.LispMacroExpander.callsWithComputedPackageDesignator(program);
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
		// The entries uiop:with-input's and uiop:with-output's EXPANSIONS call
		// (.todo/359). Same timing problem: the expansions run inside the
		// expression compilers, long after this pass, so the reference this
		// selection would look for does not exist yet. The uiop halves of the
		// same expansions -- the pathname arms opening through
		// call-with-input-file / call-with-output-file -- are covered by
		// UiopLibrary's mirror-image table.
		if (LispNames.CALL_WITH_INPUT_INTERNAL.equals(entry)) {
			return referencesName(program, LispNames.UIOP_WITH_INPUT_QUALIFIED, canonical);
		}
		if (LispNames.CALL_WITH_OUTPUT_INTERNAL.equals(entry)) {
			return referencesName(program, LispNames.UIOP_WITH_OUTPUT_QUALIFIED, canonical);
		}
		// The uiop lowerings expandUiopStubCall performs inside the expression
		// compilers, after this pass: uiop:file-exists-p becomes (probe-file x) and
		// uiop:namestring / uiop:native-namestring become (namestring x), so a program
		// spelling only the uiop name must still splice the CL definition.
		// %stream-target has the same timing problem three times over: the compile-path
		// seams insert the call inside the expression compilers, and gray.lisp's
		// dispatch helpers -- which call it unconditionally -- are spliced by
		// GrayStreamsLibrary AFTER this pass. So the selection keys on the SURFACE
		// facts instead: the program can build an OPEN stream value (every stream
		// consumer then resolves through it), it builds a synonym stream, or it uses
		// the Gray protocol (which is exactly when a dispatch helper can be spliced).
		//
		// This is a best effort, not a guarantee: a stream value can also arrive from a
		// form injected AFTER this pass (the generated condition renderer and the
		// print-object seam both open a string output stream), which nothing here can
		// see. That is why the compile-path seams fall back to the INLINE unwrap when
		// the defun is absent -- see StreamDesignators.throughStreamInline.
		// The element-type registry and the wide-element helpers: every call to them is
		// synthesized by the expression compilers (the open leaf's registration, the
		// read-byte / write-byte / file-length / file-position / stream-element-type
		// lowerings), long after this pass. The wide half keys on a literal wide
		// element type being opened at all; the registry also serves a program that
		// asks stream-element-type and can open a file.
		if (LispNames.WIDE_READ_BYTE_INTERNAL.equals(entry) || LispNames.WIDE_WRITE_BYTE_INTERNAL.equals(entry)
				|| LispNames.WIDE_ELEMENTS_INTERNAL.equals(entry)
				|| LispNames.WIDE_POSITION_OCTETS_INTERNAL.equals(entry)) {
			return am.ik.rontolisp.macro.LispMacroExpander.opensWideElementStream(program);
		}
		if (LispNames.FILE_STREAM_REGISTER_INTERNAL.equals(entry)
				|| LispNames.FILE_STREAM_ELEMENT_TYPE_INTERNAL.equals(entry)
				|| LispNames.FILE_STREAM_FORGET_INTERNAL.equals(entry)) {
			return am.ik.rontolisp.macro.LispMacroExpander.opensWideElementStream(program)
					|| (referencesName(program, LispNames.STREAM_ELEMENT_TYPE, canonical)
							&& (referencesName(program, LispNames.OPEN, canonical)
									|| referencesName(program, LispNames.WITH_OPEN_FILE, canonical)));
		}
		// The file-stream direction record: its register is synthesized at every open
		// leaf and its forget at every close, and only a program that asks a direction
		// predicate, can build a stream value and can open a file needs either.
		if (LispNames.FILE_STREAM_DIRECTION_REGISTER_INTERNAL.equals(entry)
				|| LispNames.FILE_STREAM_DIRECTION_FORGET_INTERNAL.equals(entry)) {
			return namesADirectionPredicate(program, canonical)
					&& am.ik.rontolisp.macro.LispMacroExpander.mayCreateStreamValues(program)
					&& (referencesName(program, LispNames.OPEN, canonical)
							|| referencesName(program, LispNames.WITH_OPEN_FILE, canonical));
		}
		// %character-stream-p: called from the read-sequence / write-sequence expansions
		// the expression compilers build, and only needed where a string stream VALUE can
		// exist at all -- without one every stream is a bivalent standard stream and the
		// buffer alone decides.
		if (LispNames.CHARACTER_STREAM_P_INTERNAL.equals(entry)) {
			return (referencesName(program, LispNames.READ_SEQUENCE, canonical)
					|| referencesName(program, LispNames.WRITE_SEQUENCE, canonical)
					|| referencesName(program, LispNames.READ_SEQUENCE_RAW_INTERNAL, canonical)
					|| referencesName(program, LispNames.WRITE_SEQUENCE_RAW_INTERNAL, canonical))
					&& am.ik.rontolisp.macro.LispMacroExpander.mayCreateStreamValues(program);
		}
		// %check-sequence-bounds: called from every read-sequence / write-sequence
		// expansion the expression compilers build, so it is needed wherever such a
		// call can reach -- a direct call, a raw-alias call, or a first-class
		// reference (whose injected wrapper body re-enters the expansion after this
		// selection; referencesName sees the (function name) spelling's symbol).
		// The Gray sequence dispatchers call it too, so a program using the Gray
		// protocol carries it like %stream-target below.
		if (LispNames.CHECK_SEQUENCE_BOUNDS_INTERNAL.equals(entry)) {
			return referencesName(program, LispNames.READ_SEQUENCE, canonical)
					|| referencesName(program, LispNames.WRITE_SEQUENCE, canonical)
					|| referencesName(program, LispNames.READ_SEQUENCE_RAW_INTERNAL, canonical)
					|| referencesName(program, LispNames.WRITE_SEQUENCE_RAW_INTERNAL, canonical)
					|| GrayStreamsLibrary.usesProtocol(program);
		}
		if (LispNames.STREAM_TARGET.equals(entry)) {
			return am.ik.rontolisp.macro.LispMacroExpander.mayCreateStreamValues(program)
					|| referencesName(program, LispNames.MAKE_SYNONYM_STREAM, canonical)
					|| GrayStreamsLibrary.usesProtocol(program);
		}
		// %print-cased: the printing operators are rewritten onto it inside the
		// expression compilers, after this pass, so the reference this selection would
		// look for does not exist yet either. The surface fact is the program MENTIONING
		// a printer-control variable (or binding one through a write-to-string keyword)
		// -- the same scan that gives the variable its defvar
		// (LispMacroExpander.injectMvSpillGlobal) and flips the compilers' route, so the
		// renderer and the variable it reads are spliced together or not at all. A
		// spliced prelude entry counts as surface too (the fixpoint above asks this for
		// each pulled entry): `write` binds the whole variable set, and the compilers'
		// scan runs over the spliced program, so a write user must carry the renderer.
		if (LispNames.PRINT_CASED_INTERNAL.equals(entry)) {
			// The package gate (.kb/pretty-printer.md) pulls the renderer only when a
			// prin1-style conversion is in reach from the surface: princ never spells
			// a qualifier, so a program that only ever princs has nothing to route.
			return am.ik.rontolisp.macro.LispMacroExpander.mentionsPrintControlVariable(program)
					|| (am.ik.rontolisp.macro.LispMacroExpander.printsUnderAPackage(program)
							&& am.ik.rontolisp.macro.LispMacroExpander.reachesPrin1FromTheSurface(program));
		}
		// The walk's two heavy leaves (the Unicode case fold; the re-basing) are
		// reached through the %pc-fold / %pc-radixed primitives, which the compile
		// paths lower to the leaf only when the program names a printer-control
		// variable (LispMacroExpander.expandPrintCasedLeaf) -- a program routed for its
		// *package* alone never binds one, and must not pay for them.
		if (LispNames.PRINT_CASE_FOLD_INTERNAL.equals(entry) || LispNames.PRINT_RADIXED_INTERNAL.equals(entry)) {
			return am.ik.rontolisp.macro.LispMacroExpander.mentionsPrintControlVariable(program);
		}
		if (LispNames.PROBE_FILE.equals(entry)) {
			// The second producer is load's :if-does-not-exist option: the guard that
			// reads it is built by LispMacroExpander.lowerLoadOptions inside the
			// expression compilers, after this pass, so the surface fact -- the option
			// being written at all -- is what selection can see.
			// The third is open's existence guard (:if-exists / :if-does-not-exist /
			// :direction :probe), built by LispMacroExpander.lowerRuntimeOpenOptions in
			// the same place and read the same way.
			return referencesUiopMember(program, LispNames.FILE_EXISTS_P, canonical)
					|| am.ik.rontolisp.macro.LispMacroExpander.callsLoadWithIfDoesNotExist(program)
					|| am.ik.rontolisp.macro.LispMacroExpander.callsOpenWithExistenceGuard(program);
		}
		if (LispNames.NAMESTRING_CL.equals(entry)) {
			return referencesName(program, PackageRegistry.qualify(LispNames.UIOP_PKG, LispNames.NAMESTRING), canonical)
					|| referencesUiopMember(program, LispNames.NATIVE_NAMESTRING, canonical);
		}
		// The mutable runtime-package table: the backends' find-package / query /
		// find-symbol / intern / do-symbols lowerings read it whenever the program
		// can create packages at run time, but those lowerings run inside the
		// expression compilers -- long after this pass -- so the reference this
		// selection would look for does not exist yet. The surface fact is the
		// program naming a package-mutating operator.
		if (LispNames.RUNTIME_PACKAGES_INTERNAL.equals(entry) || LispNames.RUNTIME_MEMBER_FIND_INTERNAL.equals(entry)
				|| LispNames.RUNTIME_MEMBER_STATUS_INTERNAL.equals(entry)
				|| LispNames.RUNTIME_MEMBER_INTERN_INTERNAL.equals(entry)) {
			return referencesRuntimePackageMutation(program, canonical);
		}
		// %runtime-package-op: the export / unexport / import / use-package /
		// unuse-package lowerings route to it when the program can create packages
		// (the shadow / shadowing-import / unintern defuns reference it directly).
		if (LispNames.RUNTIME_PACKAGE_OP_INTERNAL.equals(entry)) {
			return referencesRuntimePackageMutation(program, canonical)
					&& (referencesName(program, LispNames.EXPORT, canonical)
							|| referencesName(program, LispNames.UNEXPORT, canonical)
							|| referencesName(program, LispNames.IMPORT, canonical)
							|| referencesName(program, LispNames.USE_PACKAGE, canonical)
							|| referencesName(program, LispNames.UNUSE_PACKAGE, canonical));
		}
		// %package-iterator-entries: the with-package-iterator expansion calls it, and
		// the expansion happens inside the expression compilers, after this pass.
		if (LispNames.PACKAGE_ITERATOR_ENTRIES_INTERNAL.equals(entry)) {
			return referencesName(program, LispNames.WITH_PACKAGE_ITERATOR, canonical);
		}
		// The %do-symbols-list universe helper: the do-symbols lowerings call it, from
		// the expression compilers, after this pass -- and so do the find-all-symbols
		// / apropos-list entries below, whose selection runs here but whose bodies
		// the compilers have not expanded yet. Keyed on every surface name that can
		// reach it.
		if (LispNames.DO_SYMBOLS_LIST_INTERNAL.equals(entry)
				|| LispNames.PACKAGE_SYMBOLS_WHERE_INTERNAL.equals(entry)) {
			return referencesName(program, LispNames.DO_SYMBOLS, canonical)
					|| referencesName(program, LispNames.DO_EXTERNAL_SYMBOLS, canonical)
					|| referencesName(program, LispNames.DO_ALL_SYMBOLS, canonical)
					|| referencesName(program, LispNames.FIND_ALL_SYMBOLS, canonical)
					|| referencesName(program, LispNames.APROPOS, canonical)
					|| referencesName(program, LispNames.APROPOS_LIST, canonical)
					|| referencesName(program, LispNames.WITH_PACKAGE_ITERATOR, canonical);
		}
		return false;
	}

	/**
	 * Whether the program names a runtime package-mutating operator ({@code make-package}
	 * / {@code delete-package} / {@code rename-package}) -- the surface fact behind the
	 * {@code %runtime-packages%} table and the member-table helpers the backends'
	 * lowerings call when the program can create packages at run time.
	 */
	private static boolean referencesRuntimePackageMutation(List<LispVal> program, boolean canonical) {
		return referencesName(program, LispNames.MAKE_PACKAGE, canonical)
				|| referencesName(program, LispNames.DELETE_PACKAGE, canonical)
				|| referencesName(program, LispNames.RENAME_PACKAGE, canonical);
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

	/** Whether the program names ANY of the given prelude surface functions. */
	private static boolean referencesAny(List<LispVal> program, boolean canonical, String... names) {
		for (String name : names) {
			if (referencesName(program, name, canonical)) {
				return true;
			}
		}
		return false;
	}

	private static boolean callsWithArguments(LispVal form, String name, boolean canonical) {
		while (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && matches(head.name(), name, canonical)
					&& cons.cdr() instanceof LispCons) {
				return true;
			}
			if (callsWithArguments(cons.car(), name, canonical)) {
				return true;
			}
			form = cons.cdr();
		}
		return false;
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

	private static boolean namesADirectionPredicate(List<LispVal> program, boolean canonical) {
		return referencesName(program, LispNames.INPUT_STREAM_P, canonical)
				|| referencesName(program, LispNames.OUTPUT_STREAM_P, canonical);
	}

	/** Per entry, the member names of every symbol its forms spell. */
	private static final Map<String, java.util.Set<String>> ENTRY_MEMBER_NAMES = new ConcurrentHashMap<>();

	private static java.util.Set<String> entryMemberNames(String entry) {
		return ENTRY_MEMBER_NAMES.computeIfAbsent(member(entry), n -> symbolNames(formsFor(n), false));
	}

	/**
	 * The name of every symbol anywhere in the program -- exactly what
	 * {@link #referencesName(List, String, boolean)} can match, as the symbols themselves
	 * when {@code canonical}, as member names otherwise -- so that one walk answers any
	 * number of its questions.
	 */
	private static java.util.Set<String> symbolNames(List<LispVal> program, boolean canonical) {
		java.util.Set<String> names = new java.util.HashSet<>();
		for (LispVal form : program) {
			collectSymbolNames(form, canonical, names);
		}
		return java.util.Set.copyOf(names);
	}

	private static void collectSymbolNames(LispVal form, boolean canonical, java.util.Set<String> names) {
		LispVal cur = form;
		while (cur instanceof LispCons cons) {
			collectSymbolNames(cons.car(), canonical, names);
			cur = cons.cdr();
		}
		if (cur instanceof LispSymbol sym) {
			names.add(canonical ? sym.name() : member(sym.name()));
		}
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
		while (true) {
			switch (form) {
				case LispSymbol sym -> {
					return matches(sym.name(), name, canonical);
				}
				case LispCons cons -> {
					if (referencesName(cons.car(), name, canonical)) {
						return true;
					}
					form = cons.cdr();
				}
				default -> {
					return false;
				}
			}
		}
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

	/**
	 * The source of one {@code %make-array-et*} prelude defun, GENERATED from
	 * {@link ArrayElementTypes#specializedCodes()} rather than transcribed.
	 *
	 * <p>
	 * A {@code make-array} whose {@code :element-type} is only known at RUN time has to
	 * be turned back into literal spellings -- every backend but the interpreter decides
	 * an array's representation from the literal designator at the call site -- so the
	 * dispatch must name every specialized code. Doing that AT each call site costs ~1.3
	 * KB of wasm per site (measured 2026-08-31; {@code .kb/array-literals.md}) and
	 * array-operations alone has 21 of them, so the arms live in this helper and every
	 * site becomes one call. Each arm's literal spelling is what the backends'
	 * recognizers read: it selects the packed representation where one exists and carries
	 * the remembered element type and its zero fill where it degrades. The unsupplied
	 * element is the arm's OWN zero, which is why the {@code given} parameter exists
	 * rather than a nil test at the call site.
	 *
	 * <p>
	 * <b>Why it is generated.</b> This list was written out by hand here, in the
	 * {@code -fp} twin, in the inline lowering and in the program-scan mask -- four
	 * copies, each documented as covering seven codes and each spelling six.
	 * {@code bfloat16} was missing from all four from the day the width landed until
	 * 2026-09-05, so a runtime designator naming it degraded to a BOXED general array on
	 * every backend but the interpreter, and on wasm walked past the refusal
	 * {@code WasmArrayCompiler} puts on the literal spelling for exactly that reason
	 * ({@code .todo/487}). The set is a fact about {@link ArrayElementTypes}; an eighth
	 * width is one entry there and all four sites follow.
	 * @param name the defun's name
	 * @param prefix the defun's parameter-name prefix ({@code %mae} / {@code %maef})
	 * @param extraParams the extra parameters, each preceded by a space, or empty
	 * @param extraKeywords the extra keyword arguments every arm passes on, or empty
	 * @return the defun source
	 */
	private static String makeArrayElementTypeDispatch(String name, String prefix, String extraParams,
			String extraKeywords) {
		String dims = prefix + "-dims";
		String et = prefix + "-et";
		String init = prefix + "-init";
		String given = prefix + "-given";
		StringBuilder sb = new StringBuilder();
		sb.append("(defun ")
			.append(name)
			.append(" (")
			.append(dims)
			.append(' ')
			.append(et)
			.append(' ')
			.append(init)
			.append(' ')
			.append(given)
			.append(extraParams)
			.append(")\n  (cond ");
		boolean first = true;
		for (int code : ArrayElementTypes.specializedCodes()) {
			if (!first) {
				sb.append("\n        ");
			}
			first = false;
			sb.append('(')
				.append(elementTypeTestSource(et, code))
				.append("\n         (make-array ")
				.append(dims)
				.append(" :element-type ")
				.append(quotedDesignatorSource(code))
				.append("\n                     :initial-element (if ")
				.append(given)
				.append(' ')
				.append(init)
				.append(' ')
				.append(zeroSource(code))
				.append(')')
				.append(extraKeywords)
				.append("))");
		}
		sb.append("\n        (t (make-array ")
			.append(dims)
			.append(" :initial-element (if ")
			.append(given)
			.append(' ')
			.append(init)
			.append(" nil)")
			.append(extraKeywords)
			.append("))))\n");
		return sb.toString();
	}

	// (member et '(character base-char standard-char)) for the one code with several
	// spellings, (equal et '(unsigned-byte n)) for a compound designator, (eq et 'name)
	// for a plain type-name symbol -- decided by the SHAPE of ArrayElementTypes.valueOf,
	// so a new code needs no case here.
	private static String elementTypeTestSource(String et, int code) {
		if (code == ArrayElementTypes.CHARACTER) {
			return "(member " + et + " '(" + String.join(" ", ArrayElementTypes.CHARACTER_SPELLINGS).toLowerCase()
					+ "))";
		}
		LispVal spec = ArrayElementTypes.valueOf(code);
		return "(" + (spec instanceof LispCons ? "equal " : "eq ") + et + " " + quotedDesignatorSource(code) + ")";
	}

	private static String quotedDesignatorSource(int code) {
		return "'" + ArrayElementTypes.valueOf(code).print().toLowerCase();
	}

	// The arm's own zero, as source: the character type's space, the float widths' 0.0,
	// the integer widths' 0.
	private static String zeroSource(int code) {
		LispVal zero = ArrayElementTypes.defaultElement(code);
		if (code == ArrayElementTypes.CHARACTER) {
			return "#\\Space";
		}
		return zero instanceof LispDouble ? "0.0" : "0";
	}

}
