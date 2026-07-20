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
 * <li>{@code string<} -- case-sensitive lexicographic less-than, returning the mismatch
 * index or nil.</li>
 * <li>{@code get-setf-expansion} -- the five setf-expansion values (lite: variable and
 * accessor-cons places, environment ignored), consumed through the ordinary
 * {@code %mv-spill} channel by a {@code multiple-value-bind} caller.</li>
 * <li>{@code char-name} -- the standard character names ({@code Space}, {@code Newline},
 * ...), a {@code U+XXXX} label for other non-printing code points, nil for graphic
 * characters; mirrors the interpreter's Java primitive.</li>
 * <li>{@code rontolisp:read-all} -- an {@code rontolisp:async-defun} draining an
 * asynchronous stream's string chunks into one string.</li>
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
		SOURCES.put(LispNames.COMPILED_FUNCTION_P, """
				(defun compiled-function-p (object) nil)
				""");
		SOURCES.put(LispNames.FUNCTION_LAMBDA_EXPRESSION, """
				(defun function-lambda-expression (function) (values nil t nil))
				""");
		SOURCES.put(LispNames.LIST_ALL_PACKAGES, """
				(defun list-all-packages () nil)
				""");
		SOURCES.put(LispNames.FIND_CLASS, """
				(defun find-class (symbol &optional errorp environment) nil)
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
		SOURCES.put(LispNames.STRING_LT, """
				(defun string< (a b)
				  (let* ((sa (string a)) (sb (string b))
				         (la (length sa)) (lb (length sb)))
				    (labels ((cmp (i)
				               (cond ((and (>= i la) (>= i lb)) nil)
				                     ((>= i la) i)
				                     ((>= i lb) nil)
				                     ((char< (char sa i) (char sb i)) i)
				                     ((char< (char sb i) (char sa i)) nil)
				                     (t (cmp (+ i 1))))))
				      (cmp 0))))
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
			return LispReader.readAllFromString(source, Features.INTERNAL);
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
		List<String> referenced = new ArrayList<>();
		for (String name : SOURCES.keySet()) {
			if (referencesName(program, name) && !definesName(program, name)) {
				referenced.add(name);
			}
		}
		if (referenced.isEmpty()) {
			return program;
		}
		List<LispVal> out = new ArrayList<>();
		for (String name : referenced) {
			out.addAll(formsFor(name));
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
