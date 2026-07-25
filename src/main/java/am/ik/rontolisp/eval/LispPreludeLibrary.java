package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

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
 * <li>{@code get-setf-expansion} -- the five setf-expansion values (lite: variable and
 * accessor-cons places, environment ignored), consumed through the ordinary
 * {@code %mv-spill} channel by a {@code multiple-value-bind} caller.</li>
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
		SOURCES.put(LispNames.FIND_CLASS, """
				(defun find-class (symbol &optional errorp environment) nil)
				""");
		// type-of over class-of: class-of yields a struct/CLOS instance's TAG symbol
		// (%struct-NAME / %class-NAME), and type-of is the type NAME -- so a digest
		// object's type is usable as the digest-name designator it came from. The tag
		// spelling is read with prin1-to-string, since symbol-name would drop the
		// package qualifier a canonical type name carries. Everything else answers what
		// class-of does (a built-in type name, or T).
		SOURCES.put(LispNames.TYPE_OF, """
				(defun type-of (object)
				  (let* ((c (class-of object))
				         (s (prin1-to-string c))
				         (n (length s)))
				    (cond ((and (> n 8) (string= (subseq s 0 8) "%struct-")) (intern (subseq s 8)))
				          ((and (> n 7) (string= (subseq s 0 7) "%class-")) (intern (subseq s 7)))
				          (t c))))
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
	}

	private static final Map<String, List<LispVal>> CACHE = new ConcurrentHashMap<>();

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
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the referenced prelude definitions spliced in
	 */
	public static List<LispVal> process(List<LispVal> program) {
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
				if (referenced.contains(name) || definesName(program, name)) {
					continue;
				}
				boolean used = referencesName(program, name);
				for (String pulled : referenced) {
					used = used || referencesName(formsFor(pulled), name);
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

	private static boolean definesName(List<LispVal> program, String name) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& (LispNames.DEFUN.equals(member(op.name())) || LispNames.ASYNC_DEFUN.equals(member(op.name())))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol defName
					&& name.equals(member(defName.name()))) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesName(List<LispVal> program, String name) {
		for (LispVal form : program) {
			if (referencesName(form, name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesName(LispVal form, String name) {
		return switch (form) {
			case LispSymbol sym -> name.equals(member(sym.name()));
			case LispCons cons -> referencesName(cons.car(), name) || referencesName(cons.cdr(), name);
			default -> false;
		};
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
