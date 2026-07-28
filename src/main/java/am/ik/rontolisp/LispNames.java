package am.ik.rontolisp;

/**
 * Centralized constants for all special form and built-in function names. These constants
 * are compile-time constants (static final String) and can be used in switch case labels.
 */
public final class LispNames {

	// Special forms

	/** The {@code quote} special form. */
	public static final String QUOTE = "QUOTE";

	/** The {@code if} special form. */
	public static final String IF = "IF";

	/** The {@code let} special form. */
	public static final String LET = "LET";

	/**
	 * The {@code progv} special form: establishes dynamic bindings for a runtime-computed
	 * list of symbols to a runtime-computed list of values, restored on exit. Interpreter
	 * only; the compilers reject it (the bound symbols are not known at compile time).
	 */
	public static final String PROGV = "PROGV";

	/** The {@code progn} special form. */
	public static final String PROGN = "PROGN";

	/** The {@code setq} special form. */
	public static final String SETQ = "SETQ";

	/** The {@code lambda} special form. */
	public static final String LAMBDA = "LAMBDA";

	/** The {@code funcall} special form. */
	public static final String FUNCALL = "FUNCALL";

	/** The {@code function} special form ({@code #'name} reader syntax). */
	public static final String FUNCTION = "FUNCTION";

	/** The {@code symbol-function} built-in function. */
	public static final String SYMBOL_FUNCTION = "SYMBOL-FUNCTION";

	/** The {@code while} special form. */
	public static final String WHILE = "WHILE";

	// Arithmetic

	/** The {@code +} built-in function. */
	public static final String ADD = "+";

	/** The {@code -} built-in function. */
	public static final String SUB = "-";

	/** The {@code *} built-in function. */
	public static final String MUL = "*";

	/** The {@code /} built-in function. */
	public static final String DIV = "/";

	/** The {@code mod} built-in function. */
	public static final String MOD = "MOD";

	/** The {@code rem} built-in function. */
	public static final String REM = "REM";

	/** The {@code abs} built-in function. */
	public static final String ABS = "ABS";

	/** The {@code min} built-in function. */
	public static final String MIN = "MIN";

	/** The {@code max} built-in function. */
	public static final String MAX = "MAX";

	/** The {@code sqrt} built-in function. */
	public static final String SQRT = "SQRT";

	/** The {@code isqrt} built-in function. */
	public static final String ISQRT = "ISQRT";

	/** The {@code expt} built-in function. */
	public static final String EXPT = "EXPT";

	/** The {@code exp} built-in function. */
	public static final String EXP = "EXP";

	/** The {@code log} built-in function. */
	public static final String LOG = "LOG";

	/** The {@code random} built-in function. */
	public static final String RANDOM = "RANDOM";

	/**
	 * The internal {@code %random-byte} primitive: one cryptographically strong random
	 * byte (0-255). Unlike {@code random} (a plain PRNG on the interpreter/JVM) this
	 * draws from the platform's cryptographic entropy source on every backend --
	 * {@code SecureRandom} on the interpreter/JVM, the WASI {@code random_get} host
	 * function (real host entropy in Preview 1, {@code wasi:random} under
	 * {@code --component}) on WASM. {@code rontolisp:random-bytes} is the public API over
	 * it.
	 */
	public static final String RANDOM_BYTE_INTERNAL = "%RANDOM-BYTE";

	/**
	 * The {@code rontolisp:random-bytes} function: a vector of {@code n}
	 * cryptographically strong random bytes, built over {@link #RANDOM_BYTE_INTERNAL} in
	 * the prelude.
	 */
	public static final String RANDOM_BYTES = "RANDOM-BYTES";

	/** The {@code get-universal-time} built-in function. */
	public static final String GET_UNIVERSAL_TIME = "GET-UNIVERSAL-TIME";

	/**
	 * The {@code encode-universal-time} prelude function (pure Gregorian calendar
	 * arithmetic, one Lisp definition shared by every backend).
	 */
	public static final String ENCODE_UNIVERSAL_TIME = "ENCODE-UNIVERSAL-TIME";

	/**
	 * The {@code decode-universal-time} prelude function (the inverse split into the nine
	 * decoded-time values).
	 */
	public static final String DECODE_UNIVERSAL_TIME = "DECODE-UNIVERSAL-TIME";

	/** The {@code get-internal-real-time} built-in function. */
	public static final String GET_INTERNAL_REAL_TIME = "GET-INTERNAL-REAL-TIME";

	/** The {@code get-internal-run-time} built-in function. */
	public static final String GET_INTERNAL_RUN_TIME = "GET-INTERNAL-RUN-TIME";

	/** The {@code getenv} built-in function. */
	public static final String GETENV = "GETENV";

	/** The {@code sin} built-in function. */
	public static final String SIN = "SIN";

	/** The {@code cos} built-in function. */
	public static final String COS = "COS";

	/** The {@code tan} built-in function. */
	public static final String TAN = "TAN";

	/** The {@code asin} built-in function. */
	public static final String ASIN = "ASIN";

	/** The {@code acos} built-in function. */
	public static final String ACOS = "ACOS";

	/** The {@code atan} built-in function. */
	public static final String ATAN = "ATAN";

	/** The {@code sinh} built-in function. */
	public static final String SINH = "SINH";

	/** The {@code cosh} built-in function. */
	public static final String COSH = "COSH";

	/** The {@code tanh} built-in function. */
	public static final String TANH = "TANH";

	/** The {@code gcd} built-in function. */
	public static final String GCD = "GCD";

	/** The {@code lcm} built-in function. */
	public static final String LCM = "LCM";

	/** The {@code signum} built-in function. */
	public static final String SIGNUM = "SIGNUM";

	/** The {@code logand} built-in function (bitwise AND, variadic; identity -1). */
	public static final String LOGAND = "LOGAND";

	/**
	 * The {@code logior} built-in function (bitwise inclusive OR, variadic; identity 0).
	 */
	public static final String LOGIOR = "LOGIOR";

	/**
	 * The {@code logxor} built-in function (bitwise exclusive OR, variadic; identity 0).
	 */
	public static final String LOGXOR = "LOGXOR";

	/** The {@code lognot} built-in function (bitwise NOT, i.e. ones' complement). */
	public static final String LOGNOT = "LOGNOT";

	/** The {@code logandc1} built-in function ({@code (logand (lognot x) y)}). */
	public static final String LOGANDC1 = "LOGANDC1";

	/** The {@code logandc2} built-in function ({@code (logand x (lognot y))}). */
	public static final String LOGANDC2 = "LOGANDC2";

	/** The {@code logorc1} built-in function ({@code (logior (lognot x) y)}). */
	public static final String LOGORC1 = "LOGORC1";

	/** The {@code logorc2} built-in function ({@code (logior x (lognot y))}). */
	public static final String LOGORC2 = "LOGORC2";

	/**
	 * The {@code ash} built-in function (arithmetic shift; left when the count is
	 * non-negative, right otherwise).
	 */
	public static final String ASH = "ASH";

	/**
	 * The {@code integer-length} built-in function (number of bits in the
	 * two's-complement magnitude of the argument, excluding sign).
	 */
	public static final String INTEGER_LENGTH = "INTEGER-LENGTH";

	/**
	 * The {@code logbitp} built-in function (tests whether a given bit of the
	 * two's-complement integer is set).
	 */
	public static final String LOGBITP = "LOGBITP";

	/**
	 * The {@code byte} built-in (builds a byte specifier). Represented internally as a
	 * two-element list {@code (size position)}.
	 */
	public static final String BYTE = "BYTE";

	/** The {@code byte-size} built-in (the size of a byte specifier). */
	public static final String BYTE_SIZE = "BYTE-SIZE";

	/** The {@code byte-position} built-in (the position of a byte specifier). */
	public static final String BYTE_POSITION = "BYTE-POSITION";

	/**
	 * The {@code ldb} built-in (load byte: extract the byte specifier's field from an
	 * integer, right-justified).
	 */
	public static final String LDB = "LDB";

	/**
	 * The {@code dpb} built-in (deposit byte: replace the byte specifier's field of an
	 * integer with the low bits of a new value).
	 */
	public static final String DPB = "DPB";

	// Comparison

	/** The {@code =} built-in function. */
	public static final String EQ = "=";

	/** The {@code eq} built-in function (general equality). */
	public static final String EQ_GENERAL = "EQ";

	/** The {@code eql} built-in function (type-aware value equality). */
	public static final String EQL = "EQL";

	/** The {@code equal} built-in function (structural equality). */
	public static final String EQUAL = "EQUAL";

	/**
	 * The {@code equalp} built-in function (like {@code equal} but strings/characters
	 * compare case-insensitively and numbers by value). Implemented as a recursive
	 * rontolisp-source {@code defun} shared by every backend (see {@code EqualpLibrary});
	 * lite: arrays/hash-tables/structures fall back to {@code eql} rather than recursing.
	 */
	public static final String EQUALP = "EQUALP";

	/** The {@code <} built-in function. */
	public static final String LT = "<";

	/** The {@code >} built-in function. */
	public static final String GT = ">";

	/** The {@code <=} built-in function. */
	public static final String LE = "<=";

	/** The {@code >=} built-in function. */
	public static final String GE = ">=";

	/**
	 * The {@code /=} numeric not-equal operator. Expands to negated {@code =} tests (all
	 * arguments pairwise different, like CL).
	 */
	public static final String NE = "/=";

	// List operations

	/** The {@code cons} built-in function. */
	public static final String CONS = "CONS";

	/** The {@code car} built-in function. */
	public static final String CAR = "CAR";

	/** The {@code cdr} built-in function. */
	public static final String CDR = "CDR";

	/** The {@code list} built-in function. */
	public static final String LIST = "LIST";

	/** The {@code append} built-in function. */
	public static final String APPEND = "APPEND";

	/** The {@code nthcdr} built-in function. */
	public static final String NTHCDR = "NTHCDR";

	/** The {@code length} built-in function. */
	public static final String LENGTH = "LENGTH";

	/** The {@code reverse} built-in function. */
	public static final String REVERSE = "REVERSE";

	/** The {@code member} built-in function. */
	public static final String MEMBER = "MEMBER";

	/**
	 * The {@code find} built-in function (return the first element {@code eql} to the
	 * given item, or nil).
	 */
	public static final String FIND = "FIND";

	/**
	 * The {@code find-if} built-in function (return the first element for which the
	 * predicate is true, or nil).
	 */
	public static final String FIND_IF = "FIND-IF";

	/**
	 * The {@code find-if-not} built-in function (return the first element for which the
	 * predicate is false, or nil).
	 */
	public static final String FIND_IF_NOT = "FIND-IF-NOT";

	/**
	 * The {@code member-if} built-in function (return the tail of the list starting at
	 * the first element for which the predicate is true, or nil).
	 */
	public static final String MEMBER_IF = "MEMBER-IF";

	/**
	 * The {@code position} built-in function (return the 0-based index of the first
	 * element {@code eql} to the given item, or nil).
	 */
	public static final String POSITION = "POSITION";

	/**
	 * The {@code position-if} built-in function (return the 0-based index of the first
	 * element for which the predicate is true, or nil).
	 */
	public static final String POSITION_IF = "POSITION-IF";

	/**
	 * The {@code position-if-not} built-in function (return the 0-based index of the
	 * first element for which the predicate is false, or nil).
	 */
	public static final String POSITION_IF_NOT = "POSITION-IF-NOT";

	/**
	 * The {@code complement} built-in (return a predicate answering the opposite of the
	 * given one). Classified as a macro here: it expands to a wrapping lambda, so it is
	 * not usable as {@code #'complement}.
	 */
	public static final String COMPLEMENT = "COMPLEMENT";

	/**
	 * The {@code count} built-in function (return the number of elements {@code eql} to
	 * the given item).
	 */
	public static final String COUNT = "COUNT";

	/**
	 * The {@code count-if} built-in function (return the number of elements for which the
	 * predicate is true).
	 */
	public static final String COUNT_IF = "COUNT-IF";

	/** The {@code assoc} built-in function. */
	public static final String ASSOC = "ASSOC";

	/**
	 * The {@code assoc-if} built-in function (return the first pair whose car satisfies
	 * the predicate, or nil).
	 */
	public static final String ASSOC_IF = "ASSOC-IF";

	/** The {@code last} built-in function. */
	public static final String LAST = "LAST";

	/**
	 * The {@code butlast} built-in function (return a copy of the list without its last
	 * element).
	 */
	public static final String BUTLAST = "BUTLAST";

	/**
	 * The {@code getf} built-in function (return the value following the indicator in a
	 * property list, or nil). The partner of {@code remf}.
	 */
	public static final String GETF = "GETF";

	/**
	 * The {@code get} standard function (+ its {@code (setf get)} writer): symbol
	 * property lists as prelude defuns over one global name-keyed alist store
	 * ({@code %symbol-plists}) -- symbols have no identity cells to hang plists on
	 * ({@code .kb/symbol-runtime-api.md}), so the store is program-global.
	 */
	public static final String GET = "GET";

	/**
	 * The {@code type-of} standard function, a prelude defun over {@link #CLASS_OF}: the
	 * type NAME of a struct/CLOS instance (class-of yields the instance TAG), else the
	 * built-in type name class-of reports.
	 */
	public static final String TYPE_OF = "TYPE-OF";

	/**
	 * The {@code find-package} standard function. Rontolisp has no package objects: the
	 * returned "package" is the canonical package name as a keyword, {@code eq}
	 * -comparable by name with what {@link #SYMBOL_PACKAGE} yields; nil for an unknown
	 * package.
	 */
	public static final String FIND_PACKAGE = "FIND-PACKAGE";

	/**
	 * The {@code symbol-package} standard function: the canonical name (same keyword
	 * shape as {@link #FIND_PACKAGE}) of the package in a symbol's qualified spelling;
	 * {@code keyword} for keywords, {@code cl}/{@code cl-user} for bare symbols, nil for
	 * uninterned ({@code #:}) ones.
	 */
	public static final String SYMBOL_PACKAGE = "SYMBOL-PACKAGE";

	/**
	 * The {@code copy-readtable} standard function, lowered to a nil-returning no-op:
	 * rontolisp's reader is not readtable-driven, so there is no readtable object to copy
	 * (the ironclad {@code *ironclad-readtable*} header idiom). The arguments are still
	 * evaluated for effect.
	 */
	public static final String COPY_READTABLE = "COPY-READTABLE";

	/**
	 * The {@code set-dispatch-macro-character} standard function, lowered to a
	 * t-returning no-op like {@link #COPY_READTABLE}: user dispatch macros cannot extend
	 * the Java-side reader. The one known user syntax, ironclad's {@code #N@(...)} s-box
	 * literal, is native in {@code LispLexer} instead.
	 */
	public static final String SET_DISPATCH_MACRO_CHARACTER = "SET-DISPATCH-MACRO-CHARACTER";

	/**
	 * The {@code *readtable*} standard variable, seeded nil so the
	 * {@code (setq *readtable* ...)} idiom loads (the assigned value is an opaque no-op
	 * token -- see {@link #COPY_READTABLE}).
	 */
	public static final String READTABLE_VAR = "*READTABLE*";

	/**
	 * The {@code remove} built-in function (return a copy without items eql to the given
	 * one).
	 */
	public static final String REMOVE = "REMOVE";

	/**
	 * The {@code remove-if} built-in function (return a copy without items satisfying a
	 * predicate).
	 */
	public static final String REMOVE_IF = "REMOVE-IF";

	/**
	 * The {@code remove-if-not} built-in function (return a copy keeping only items
	 * satisfying a predicate).
	 */
	public static final String REMOVE_IF_NOT = "REMOVE-IF-NOT";

	/**
	 * The {@code remove-duplicates} built-in function (return a copy of the list with
	 * duplicate elements removed, keeping the last occurrence; elements compared with
	 * {@code eql}).
	 */
	public static final String REMOVE_DUPLICATES = "REMOVE-DUPLICATES";

	/**
	 * The {@code delete} built-in function (destructive variant of {@code remove}:
	 * splices out every element {@code eql} to the given one in place, reusing the
	 * surviving cons cells; use the return value since the head may change).
	 */
	public static final String DELETE = "DELETE";

	/**
	 * The {@code delete-if} built-in function (destructive variant of {@code remove-if};
	 * see {@link #DELETE}).
	 */
	public static final String DELETE_IF = "DELETE-IF";

	/**
	 * The {@code delete-if-not} built-in function (destructive variant of
	 * {@code remove-if-not}; see {@link #DELETE}).
	 */
	public static final String DELETE_IF_NOT = "DELETE-IF-NOT";

	/**
	 * The {@code substitute} built-in function (return a copy of the list with each
	 * element {@code eql} to the old item replaced by the new item).
	 */
	public static final String SUBSTITUTE = "SUBSTITUTE";

	/**
	 * The {@code nsubstitute} built-in function (destructive variant of
	 * {@code substitute}: rewrites every {@code car} {@code eql} to the old item with the
	 * new item in place and returns the mutated list).
	 */
	public static final String NSUBSTITUTE = "NSUBSTITUTE";

	/**
	 * The {@code subst} function (a prelude defun): non-destructive tree substitution of
	 * {@code new} for every subtree/leaf matching {@code old} under {@code :test}
	 * (default {@code eql}) and {@code :key}. Unchanged subtrees are shared, not copied.
	 */
	public static final String SUBST = "SUBST";

	/**
	 * The {@code copy-tree} function (a prelude defun): a deep copy of a cons tree (every
	 * cons is fresh; non-cons leaves are shared).
	 */
	public static final String COPY_TREE = "COPY-TREE";

	/**
	 * The {@code search} function (a prelude defun): the position of the first (or with
	 * {@code :from-end} the last) occurrence of one sequence inside another, or nil.
	 * Supports {@code :start1}/{@code :end1}/{@code :start2}/{@code :end2}/{@code :test}
	 * (default {@code eql})/{@code :key}/{@code :from-end}; a simple O(n*m) scan over
	 * {@code elt}.
	 */
	public static final String SEARCH = "SEARCH";

	/**
	 * The {@code mismatch} prelude function: the index of the first mismatching element
	 * of two (bounded) sequences, or nil when they match.
	 */
	public static final String MISMATCH = "MISMATCH";

	/**
	 * The {@code nconc} built-in function (destructively concatenate two lists).
	 */
	public static final String NCONC = "NCONC";

	/**
	 * The {@code sort} built-in function (destructively sort a list using a comparison
	 * predicate).
	 */
	public static final String SORT = "SORT";

	/**
	 * The {@code stable-sort} built-in function (sort preserving the relative order of
	 * elements the predicate considers equal; supports {@code :key}). Lite: expanded to a
	 * decorate/{@code sort}/undecorate scan over the sequence as a list, so the result is
	 * always a fresh list (a vector argument does not come back as a vector).
	 */
	public static final String STABLE_SORT = "STABLE-SORT";

	/**
	 * The {@code copy-seq} built-in function (returns a fresh copy of a sequence;
	 * expanded to {@code (subseq seq 0)}).
	 */
	public static final String COPY_SEQ = "COPY-SEQ";

	/** The {@code identity} built-in function (returns its argument unchanged). */
	public static final String IDENTITY = "IDENTITY";

	/** The {@code copy-list} built-in function (returns a shallow copy of a list). */
	public static final String COPY_LIST = "COPY-LIST";

	/**
	 * The {@code nreverse} built-in function (destructively reverses a list by rewiring
	 * each {@code cdr} and returning the former last cell as the new head; use the return
	 * value).
	 */
	public static final String NREVERSE = "NREVERSE";

	/**
	 * The {@code make-list} built-in function (creates a list of n nil elements; the CL
	 * {@code :initial-element} keyword is not supported).
	 */
	public static final String MAKE_LIST = "MAKE-LIST";

	/**
	 * The {@code make-sequence} macro (lowered onto {@code make-string}/
	 * {@code make-list}/{@code make-array} by a literal-type expansion).
	 */
	public static final String MAKE_SEQUENCE = "MAKE-SEQUENCE";

	/**
	 * The {@code union} built-in function (set union of two lists, compared with
	 * {@code eql}; CL {@code :test}/{@code :key} keywords are not supported).
	 */
	public static final String UNION = "UNION";

	/**
	 * The {@code intersection} built-in function (set intersection of two lists, compared
	 * with {@code eql}).
	 */
	public static final String INTERSECTION = "INTERSECTION";

	/**
	 * The {@code set-difference} built-in function (elements of the first list not
	 * present in the second, compared with {@code eql}).
	 */
	public static final String SET_DIFFERENCE = "SET-DIFFERENCE";

	/**
	 * The {@code adjoin} built-in function (prepends an item to a list unless it is
	 * already a member, compared with {@code eql}).
	 */
	public static final String ADJOIN = "ADJOIN";

	/**
	 * The {@code list*} built-in function (build a list whose final element is the last
	 * argument used as the tail: {@code (list* a b c) -> (cons a (cons b c))}).
	 */
	public static final String LIST_STAR = "LIST*";

	/**
	 * The {@code acons} built-in function (prepend a {@code (key . value)} pair to an
	 * association list: {@code (acons k v alist) -> (cons (cons k v) alist)}).
	 */
	public static final String ACONS = "ACONS";

	/**
	 * The {@code endp} built-in function (true at the end of a list; here a synonym for
	 * {@code null}, the improper-list error of CL is relaxed).
	 */
	public static final String ENDP = "ENDP";

	/**
	 * The {@code elt} built-in function (0-based element access; lists only, a synonym
	 * for {@code nth} with reversed argument order, string indexing is not supported).
	 */
	public static final String ELT = "ELT";

	/**
	 * The {@code rassoc} built-in function (return the first pair whose cdr is
	 * {@code eql} to the given value, or nil).
	 */
	public static final String RASSOC = "RASSOC";

	/**
	 * The {@code pairlis} built-in function (pair up a list of keys and a list of values
	 * into an association list, prepended to an optional existing alist).
	 */
	public static final String PAIRLIS = "PAIRLIS";

	/**
	 * The {@code copy-alist} built-in function (copy an association list's spine and its
	 * {@code (key . value)} pair cells; the keys and values themselves are shared).
	 */
	public static final String COPY_ALIST = "COPY-ALIST";

	/**
	 * The {@code revappend} built-in function (reverse the first list and append the
	 * second: {@code (revappend x y) -> (append (reverse x) y)}).
	 */
	public static final String REVAPPEND = "REVAPPEND";

	/**
	 * The {@code nreconc} built-in function (destructive {@code revappend}: expands to
	 * {@code (nconc (nreverse x) y)}, so the cons cells of {@code x} are reused; use the
	 * return value).
	 */
	public static final String NRECONC = "NRECONC";

	/**
	 * The {@code maplist} built-in function (apply the function to successive cdrs of the
	 * list and collect the results; single-list only).
	 */
	public static final String MAPLIST = "MAPLIST";

	/**
	 * The {@code mapcon} built-in function (apply the function to successive cdrs of the
	 * list and concatenate the result lists; single-list only).
	 */
	public static final String MAPCON = "MAPCON";

	/**
	 * The {@code mapl} built-in function (apply the function to successive cdrs of the
	 * list for its side effects and return the original list; single-list only).
	 */
	public static final String MAPL = "MAPL";

	/** The {@code rplaca} built-in function. */
	public static final String RPLACA = "RPLACA";

	/** The {@code rplacd} built-in function. */
	public static final String RPLACD = "RPLACD";

	/** The {@code %remf-tail} built-in function. */
	public static final String REMF_TAIL = "%REMF-TAIL";

	// Hash tables

	/** The {@code make-hash-table} built-in function. */
	public static final String MAKE_HASH_TABLE = "MAKE-HASH-TABLE";

	/** The {@code gethash} built-in function. */
	public static final String GETHASH = "GETHASH";

	/**
	 * The {@code %puthash} internal built-in function. The target of the {@code gethash}
	 * {@code setf} place: {@code (%puthash key table value)} stores and returns the
	 * value.
	 */
	public static final String PUTHASH = "%PUTHASH";

	/** The {@code remhash} built-in function. */
	public static final String REMHASH = "REMHASH";

	/** The {@code clrhash} built-in function. */
	public static final String CLRHASH = "CLRHASH";

	/**
	 * The {@code hash-table-test} built-in function. Always answers {@code equal}: every
	 * backend's table is keyed structurally, so that is the test the lookups actually
	 * implement whatever {@code :test} the table was made with.
	 */
	public static final String HASH_TABLE_TEST = "HASH-TABLE-TEST";

	/**
	 * The {@code hash-table-size} built-in function. Lite: a rontolisp table has no
	 * separate capacity, so the size IS the entry count.
	 */
	public static final String HASH_TABLE_SIZE = "HASH-TABLE-SIZE";

	/**
	 * The {@code hash-table-rehash-size} built-in function. Lite: growth is the host
	 * map's business here, so the standard default (1.5) is reported.
	 */
	public static final String HASH_TABLE_REHASH_SIZE = "HASH-TABLE-REHASH-SIZE";

	/**
	 * The {@code hash-table-rehash-threshold} built-in function. Lite: reports the
	 * standard default (1.0), like {@link #HASH_TABLE_REHASH_SIZE}.
	 */
	public static final String HASH_TABLE_REHASH_THRESHOLD = "HASH-TABLE-REHASH-THRESHOLD";

	/** The {@code hash-table-count} built-in function. */
	public static final String HASH_TABLE_COUNT = "HASH-TABLE-COUNT";

	/** The {@code hash-table-p} predicate. */
	public static final String HASH_TABLE_P = "HASH-TABLE-P";

	/**
	 * The {@code maphash} built-in function (apply a function to each key/value pair).
	 */
	public static final String MAPHASH = "MAPHASH";

	// Arrays

	/**
	 * The {@code make-array} built-in function. Supports arrays of any rank {@code >= 1}
	 * and the {@code :initial-element} keyword.
	 */
	public static final String MAKE_ARRAY = "MAKE-ARRAY";

	/** The {@code aref} built-in function (array element access). */
	public static final String AREF = "AREF";

	/**
	 * The {@code %aset} internal built-in function. The target of the {@code aref}
	 * {@code setf} place: {@code (%aset array subscript... value)} stores and returns the
	 * value.
	 */
	public static final String ASET = "%ASET";

	/**
	 * The {@code row-major-aref} built-in function (flat row-major element access,
	 * independent of rank); also a {@code setf} place.
	 */
	public static final String ROW_MAJOR_AREF = "ROW-MAJOR-AREF";

	/**
	 * The {@code %row-major-aset} internal built-in function. The target of the
	 * {@code row-major-aref} {@code setf} place:
	 * {@code (%row-major-aset array index value)} stores and returns the value.
	 */
	public static final String ROW_MAJOR_ASET = "%ROW-MAJOR-ASET";

	/**
	 * The {@code array-row-major-index} built-in function (the flat row-major index of
	 * the given subscripts). Expanded by
	 * {@link LispMacroExpander#expandArrayRowMajorIndex} into a Horner fold over
	 * {@code array-dimensions}.
	 */
	public static final String ARRAY_ROW_MAJOR_INDEX = "ARRAY-ROW-MAJOR-INDEX";

	/** The {@code :initial-element} keyword accepted by {@code make-array}. */
	public static final String INITIAL_ELEMENT_KEYWORD = ":INITIAL-ELEMENT";

	/** The {@code :initial-contents} keyword of {@code make-array}. */
	public static final String INITIAL_CONTENTS_KEYWORD = ":INITIAL-CONTENTS";

	/** The {@code :fill-pointer} keyword accepted by {@code make-array}. */
	public static final String FILL_POINTER_KEYWORD = ":FILL-POINTER";

	/** The {@code :adjustable} keyword accepted by {@code make-array}. */
	public static final String ADJUSTABLE_KEYWORD = ":ADJUSTABLE";

	/**
	 * The {@code :displaced-to} keyword accepted by {@code make-array}: the built array
	 * is a view sharing the target's storage. Cannot be combined with
	 * {@code :fill-pointer}/{@code :adjustable}/{@code :initial-element} (lite
	 * semantics).
	 */
	public static final String DISPLACED_TO_KEYWORD = ":DISPLACED-TO";

	/** The {@code :displaced-index-offset} keyword accepted by {@code make-array}. */
	public static final String DISPLACED_INDEX_OFFSET_KEYWORD = ":DISPLACED-INDEX-OFFSET";

	/**
	 * The {@code adjust-array} built-in function: resize an array preserving the elements
	 * at common subscripts. Adjusts an {@code :adjustable} array in place (returning it),
	 * otherwise returns a fresh array. Expanded by
	 * {@link LispMacroExpander#expandAdjustArray} on the compile path.
	 */
	public static final String ADJUST_ARRAY = "ADJUST-ARRAY";

	/**
	 * The {@code array-displacement} built-in function: the {@code :displaced-to} target
	 * and offset as two values ({@code nil} and 0 for a non-displaced array). Expanded by
	 * {@link LispMacroExpander#expandArrayDisplacement} on the compile path.
	 */
	public static final String ARRAY_DISPLACEMENT = "ARRAY-DISPLACEMENT";

	/**
	 * The {@code %array-disp-target} internal built-in function: the displacement target
	 * of an array, or {@code nil} (the primary value of {@code array-displacement}).
	 */
	public static final String ARRAY_DISP_TARGET = "%ARRAY-DISP-TARGET";

	/**
	 * The {@code %array-disp-offset} internal built-in function: the displacement offset
	 * of an array, or 0 (the secondary value of {@code array-displacement}).
	 */
	public static final String ARRAY_DISP_OFFSET = "%ARRAY-DISP-OFFSET";

	/**
	 * The {@code %array-become} internal built-in function:
	 * {@code (%array-become old new)} replaces {@code old}'s dimensions, fill pointer and
	 * data with {@code new}'s in place and returns {@code old} (the in-place half of
	 * {@code adjust-array} on an adjustable array).
	 */
	public static final String ARRAY_BECOME = "%ARRAY-BECOME";

	/**
	 * {@code (%array-alike seq n)} allocates a fresh zero-filled rank-1 array of length
	 * {@code n} with the SAME representation as {@code seq}: a packed integer vector
	 * yields a packed vector of the same width, anything else a general (boxed) vector.
	 * The {@code subseq} vector lowering allocates through it so a subsequence of a
	 * packed vector stays packed on every backend.
	 */
	public static final String ARRAY_ALIKE = "%ARRAY-ALIKE";

	/**
	 * The {@code fill-pointer} built-in function (the fill pointer of a vector). Also a
	 * {@code setf} place (target {@link #SET_FILL_POINTER}).
	 */
	public static final String FILL_POINTER = "FILL-POINTER";

	/**
	 * The {@code %set-fill-pointer} internal built-in function. The target of the
	 * {@code fill-pointer} {@code setf} place: {@code (%set-fill-pointer vector value)}
	 * stores and returns the value.
	 */
	public static final String SET_FILL_POINTER = "%SET-FILL-POINTER";

	/** The {@code array-has-fill-pointer-p} built-in function. */
	public static final String ARRAY_HAS_FILL_POINTER_P = "ARRAY-HAS-FILL-POINTER-P";

	/**
	 * The {@code array-element-type} built-in function. Element types are not tracked, so
	 * it always returns {@code t}.
	 */
	public static final String ARRAY_ELEMENT_TYPE = "ARRAY-ELEMENT-TYPE";

	/** The {@code adjustable-array-p} built-in function. */
	public static final String ADJUSTABLE_ARRAY_P = "ADJUSTABLE-ARRAY-P";

	/**
	 * The {@code vector-push} built-in function: store an element at the fill pointer and
	 * increment it, returning the index used or {@code nil} when full.
	 */
	public static final String VECTOR_PUSH = "VECTOR-PUSH";

	/**
	 * The {@code vector-pop} built-in function: decrement the fill pointer and return the
	 * element below it.
	 */
	public static final String VECTOR_POP = "VECTOR-POP";

	/**
	 * The {@code vector-push-extend} built-in function: like {@code vector-push} but
	 * grows the vector when it is full.
	 */
	public static final String VECTOR_PUSH_EXTEND = "VECTOR-PUSH-EXTEND";

	/**
	 * The {@code vector} built-in function (build a fresh rank-1 array from the
	 * arguments). Expanded by {@link LispMacroExpander#expandVector} into
	 * {@code make-array} + {@code %aset}.
	 */
	public static final String VECTOR = "VECTOR";

	/**
	 * The {@code svref} built-in function (simple-vector element access). Expanded by
	 * {@link LispMacroExpander#expandSvref} into {@code aref}; also a {@code setf} place.
	 */
	public static final String SVREF = "SVREF";

	/**
	 * The {@code array-dimensions} built-in function (the dimension sizes as a list). The
	 * only array introspection primitive with per-backend support;
	 * {@code array-rank}/{@code array-dimension}/{@code array-total-size} expand onto it.
	 */
	public static final String ARRAY_DIMENSIONS = "ARRAY-DIMENSIONS";

	/** The {@code array-dimension} built-in function (one dimension size). */
	public static final String ARRAY_DIMENSION = "ARRAY-DIMENSION";

	/** The {@code array-rank} built-in function (1 for vectors, 2 for matrices). */
	public static final String ARRAY_RANK = "ARRAY-RANK";

	/** The {@code array-total-size} built-in function (the element count). */
	public static final String ARRAY_TOTAL_SIZE = "ARRAY-TOTAL-SIZE";

	/**
	 * The {@code coerce} built-in function. Supports the literal result types
	 * {@code 'list}, {@code 'vector}, and {@code 'string}; expanded by
	 * {@link LispMacroExpander#expandCoerce}.
	 */
	public static final String COERCE = "COERCE";

	// Higher-order functions

	/** The {@code mapcar} built-in function. */
	public static final String MAPCAR = "MAPCAR";

	/**
	 * The {@code map} built-in function (map a function over arbitrary sequences,
	 * building a result of a requested type: {@code 'list}, {@code 'string}, or nil for
	 * effect).
	 */
	public static final String MAP = "MAP";

	/**
	 * The {@code map-into} built-in function (destructively store the results of applying
	 * a function to successive elements of the argument sequences into the result
	 * sequence).
	 */
	public static final String MAP_INTO = "MAP-INTO";

	/** The {@code mapc} built-in function (apply for effect, return the list). */
	public static final String MAPC = "MAPC";

	/**
	 * The {@code mapcan} built-in function (apply over a list and concatenate the result
	 * lists).
	 */
	public static final String MAPCAN = "MAPCAN";

	/**
	 * The {@code apply} built-in function (apply a function to a spread argument list).
	 */
	public static final String APPLY = "APPLY";

	/** The {@code reduce} built-in function. */
	public static final String REDUCE = "REDUCE";

	/**
	 * The {@code every} built-in function (true if the predicate holds for every
	 * element).
	 */
	public static final String EVERY = "EVERY";

	/**
	 * The {@code some} built-in function (the first non-nil predicate result, or nil).
	 */
	public static final String SOME = "SOME";

	/**
	 * The {@code notany} built-in function (true if the predicate holds for no element;
	 * the complement of {@code some}).
	 */
	public static final String NOTANY = "NOTANY";

	/**
	 * The {@code notevery} built-in function (true if the predicate fails for some
	 * element; the complement of {@code every}).
	 */
	public static final String NOTEVERY = "NOTEVERY";

	// Macros

	/** The {@code setf} macro. */
	public static final String SETF = "SETF";

	/** The {@code push} macro. */
	public static final String PUSH = "PUSH";

	/** The {@code pop} macro. */
	public static final String POP = "POP";

	/** The {@code remf} macro. */
	public static final String REMF = "REMF";

	/** The {@code let*} macro. */
	public static final String LET_STAR = "LET*";

	/** The {@code dolist} macro. */
	public static final String DOLIST = "DOLIST";

	/** The {@code incf} macro. */
	public static final String INCF = "INCF";

	/** The {@code decf} macro. */
	public static final String DECF = "DECF";

	/** The {@code format} macro. */
	public static final String FORMAT = "FORMAT";

	/** The {@code defun} macro. */
	public static final String DEFUN = "DEFUN";

	/**
	 * The {@code defmacro} special form. User macros are expanded by the interpreter at
	 * evaluation time and by a compile-time pass on the compilation path, so the JVM/WASM
	 * backends never see a macro call.
	 */
	public static final String DEFMACRO = "DEFMACRO";

	/**
	 * The {@code defstruct} special form. Expanded into the defuns it generates
	 * (constructor, predicate, copier, accessors) by
	 * {@code LispMacroExpander.expandDefstruct}: the interpreter expands at evaluation
	 * time, the compilers splice top-level forms before Pass 1.
	 */
	public static final String DEFSTRUCT = "DEFSTRUCT";

	/**
	 * The {@code defclass} special form (static CLOS subset). Expanded into the defuns it
	 * generates (keyword constructor, reader/accessor functions) by
	 * {@code LispMacroExpander.expandDefclass}: the interpreter expands at evaluation
	 * time, the compilers splice top-level forms before Pass 1.
	 */
	public static final String DEFCLASS = "DEFCLASS";

	/**
	 * The {@code defgeneric} special form (static CLOS subset). Registers a generic
	 * function and defines its dispatcher defun (see
	 * {@code LispMacroExpander.generateDispatcher}).
	 */
	public static final String DEFGENERIC = "DEFGENERIC";

	/**
	 * The {@code defmethod} special form (static CLOS subset). Registers a method
	 * (optionally specialized on the FIRST parameter with an {@code eql} literal, a
	 * {@code defclass} class, or a built-in type) and regenerates the generic's
	 * dispatcher defun.
	 */
	public static final String DEFMETHOD = "DEFMETHOD";

	/**
	 * The {@code call-next-method} local operator (static CLOS subset, Stage 3). Valid
	 * only inside a {@code defmethod} body; {@code LispMacroExpander.expandDefmethod}
	 * rewrites it to a {@code funcall} of the method's next-method thunk, so it never
	 * reaches the evaluator/compilers as a symbol.
	 */
	public static final String CALL_NEXT_METHOD = "CALL-NEXT-METHOD";

	/**
	 * The {@code next-method-p} local operator (static CLOS subset, Stage 3). Valid only
	 * inside a {@code defmethod} body; rewritten by
	 * {@code LispMacroExpander.expandDefmethod} to a nil-test of the next-method thunk.
	 */
	public static final String NEXT_METHOD_P = "NEXT-METHOD-P";

	/**
	 * The {@code make-instance} macro (static CLOS subset). Requires a literal quoted
	 * class name; expands to the class's generated keyword constructor.
	 */
	public static final String MAKE_INSTANCE = "MAKE-INSTANCE";

	/**
	 * The {@code slot-value} macro (static CLOS subset). Requires a literal quoted slot
	 * name; expands to the slot's {@code nth} position and is a {@code setf} place.
	 */
	public static final String SLOT_VALUE = "SLOT-VALUE";

	/** The {@code &rest} lambda-list keyword. */
	public static final String LAMBDA_REST = "&REST";

	/**
	 * The {@code &body} lambda-list keyword ({@code defmacro} alias for {@code &rest}).
	 */
	public static final String LAMBDA_BODY = "&BODY";

	/** The {@code &optional} lambda-list keyword. */
	public static final String LAMBDA_OPTIONAL = "&OPTIONAL";

	/** The {@code &key} lambda-list keyword. */
	public static final String LAMBDA_KEY = "&KEY";

	/** The {@code &aux} lambda-list keyword. */
	public static final String LAMBDA_AUX = "&AUX";

	/** The {@code &allow-other-keys} lambda-list keyword. */
	public static final String LAMBDA_ALLOW_OTHER_KEYS = "&ALLOW-OTHER-KEYS";

	/**
	 * The {@code &environment} lambda-list keyword, accepted in macro lambda lists only
	 * (defmacro/macrolet). Lite: rontolisp macro expansion has no environment object, so
	 * the parameter is stripped from the lambda list and bound to nil around the body --
	 * enough for the portable idiom of threading it into {@code get-setf-expansion} /
	 * {@code constantp}, which ignore a nil environment.
	 */
	public static final String LAMBDA_ENVIRONMENT = "&ENVIRONMENT";

	/**
	 * The {@code &whole} macro-lambda-list keyword: binds the whole macro call form
	 * (defmacro) or the whole destructured list (destructuring-bind). Must be the first
	 * element of the lambda list, per CL.
	 */
	public static final String LAMBDA_WHOLE = "&WHOLE";

	/** The {@code :allow-other-keys} call-site keyword argument. */
	public static final String ALLOW_OTHER_KEYS_KEYWORD = ":ALLOW-OTHER-KEYS";

	/**
	 * The {@code gensym} function. Returns a fresh symbol named
	 * {@code #:<prefix><counter>} (default prefix {@code g}). rontolisp symbols are plain
	 * strings, so the result is an ordinary symbol whose uniqueness rests on the
	 * {@code #:} prefix and a monotonically increasing counter.
	 */
	public static final String GENSYM = "GENSYM";

	/**
	 * The {@code macroexpand-1} function. Expands the top-level form once when its
	 * operator is a user macro or a built-in macro; returns the form unchanged otherwise
	 * (rontolisp has no multiple values, so no second {@code expanded-p} value).
	 */
	public static final String MACROEXPAND_1 = "MACROEXPAND-1";

	/** The {@code macroexpand} function. Repeats {@code macroexpand-1} to a fixpoint. */
	public static final String MACROEXPAND = "MACROEXPAND";

	/**
	 * The {@code symbol-name} function. Returns the symbol's stored name verbatim (the
	 * same spelling {@code princ} prints), so keywords keep their leading {@code :} and
	 * rontolisp's case-preserving lowercase names are NOT upcased like in CL.
	 */
	public static final String SYMBOL_NAME = "SYMBOL-NAME";

	/**
	 * The {@code intern} function. Returns the symbol named by the argument string,
	 * verbatim. rontolisp symbols compare by name, so there is no intern table; the
	 * current package is ignored and a package argument is an error.
	 */
	public static final String INTERN = "INTERN";

	/**
	 * The {@code find-symbol} function. Like {@code intern} but never creates: returns
	 * the symbol when the name is known (a {@code cl} symbol, a keyword, or a
	 * user-defined function), nil otherwise.
	 */
	public static final String FIND_SYMBOL = "FIND-SYMBOL";

	/**
	 * The {@code make-symbol} function. Returns a fresh uninterned symbol named
	 * {@code #:<name>} (the same {@code #:} convention gensym uses).
	 */
	public static final String MAKE_SYMBOL = "MAKE-SYMBOL";

	/** The {@code boundp} function. Whether a symbol names a bound global variable. */
	public static final String BOUNDP = "BOUNDP";

	/**
	 * The {@code fboundp} function. Whether a symbol names a function, macro, or special
	 * operator.
	 */
	public static final String FBOUNDP = "FBOUNDP";

	/** The {@code symbol-value} function. The global variable value named by a symbol. */
	public static final String SYMBOL_VALUE = "SYMBOL-VALUE";

	/** The {@code defvar} special form. */
	public static final String DEFVAR = "DEFVAR";

	/** The {@code defparameter} special form (unconditional global assignment). */
	public static final String DEFPARAMETER = "DEFPARAMETER";

	/**
	 * The {@code defconstant} special form. rontolisp does not enforce constancy; it
	 * behaves like {@code defparameter}.
	 */
	public static final String DEFCONSTANT = "DEFCONSTANT";

	/** The {@code cond} macro. */
	public static final String COND = "COND";

	/** The {@code case} macro. */
	public static final String CASE = "CASE";

	/** The {@code otherwise} default-clause designator recognized by {@code case}. */
	public static final String OTHERWISE = "OTHERWISE";

	/**
	 * The {@code ecase} macro (exhaustive {@code case}; signals an error when no key
	 * matches).
	 */
	public static final String ECASE = "ECASE";

	/**
	 * The {@code etypecase} macro (exhaustive {@code typecase}; signals an error when no
	 * type matches).
	 */
	public static final String ETYPECASE = "ETYPECASE";

	/**
	 * The {@code ccase} macro. Without a restart system this behaves like {@code ecase}
	 * (signals an error when no key matches).
	 */
	public static final String CCASE = "CCASE";

	/**
	 * The {@code check-type} macro. Lite version: expands to a type test built from the
	 * {@code typecase} predicate map (plus compound specifiers) and an {@code error} call
	 * -- no restarts, no place re-storing.
	 */
	public static final String CHECK_TYPE = "CHECK-TYPE";

	/**
	 * The {@code assert} macro. Lite version: expands to {@code (unless test (error
	 * ...))} -- the optional places list is ignored (no restarts).
	 */
	public static final String ASSERT = "ASSERT";

	/**
	 * The {@code declare} declaration marker. Parsed no-op: the whole form expands to
	 * {@code nil} and its arguments are never evaluated or validated.
	 */
	public static final String DECLARE = "DECLARE";

	/**
	 * The {@code declaim} macro. Parsed no-op like {@link #DECLARE}.
	 */
	public static final String DECLAIM = "DECLAIM";

	/**
	 * The {@code proclaim} operator. Parsed no-op like {@link #DECLARE} (classified as a
	 * macro here, not a function as in CL, so the argument is not evaluated either).
	 */
	public static final String PROCLAIM = "PROCLAIM";

	/**
	 * The {@code special} declaration identifier. A {@code (special ...)} clause inside a
	 * top-level {@code declaim}/{@code proclaim} proclaims its variables special (dynamic
	 * binding), collected by {@code SpecialVarCollector}. Not a callable symbol; only
	 * meaningful inside a declaration specifier, so it is not registered as a cl symbol.
	 */
	public static final String SPECIAL = "SPECIAL";

	/**
	 * The {@code the} operator. Expands to its value form (identity; no type checking).
	 */
	public static final String THE = "THE";

	/**
	 * The {@code deftype} macro. Parsed no-op like {@link #DECLAIM}: the type name is NOT
	 * registered, so it is only useful where the defined type is never used in a runtime
	 * type test (e.g. inside {@code declaim ftype} declarations, themselves no-ops); a
	 * later use in {@code check-type}/{@code typecase} fails naming the unsupported
	 * specifier.
	 */
	public static final String DEFTYPE = "DEFTYPE";

	/**
	 * The {@code define-condition} macro. Expands into the equivalent {@code defclass}
	 * over the CLOS static subset, so a condition type is an ordinary class registered in
	 * the {@code ClosRegistry} (parent defaults to {@code condition}); its
	 * {@code :report} is registered there too. Lite: single inheritance, and no restart
	 * layer.
	 */
	public static final String DEFINE_CONDITION = "DEFINE-CONDITION";

	/**
	 * The {@code define-modify-macro} macro. Lowers to a {@code defmacro} that expands
	 * {@code (name place args...)} into {@code (setf place (function place args...))}.
	 * Lite: the place subforms may be evaluated more than once (no
	 * {@code get-setf-expansion} single-evaluation protocol).
	 */
	public static final String DEFINE_MODIFY_MACRO = "DEFINE-MODIFY-MACRO";

	/**
	 * The {@code define-setf-expander} macro. The five-value setf-expansion protocol
	 * ({@code get-setf-expansion}/{@code &environment}) is supported: the interpreter's
	 * {@code setfExpanders} registry and {@code UserMacroExpander} rewrite
	 * {@code (setf/incf/decf (place ...) v)} for the newly defined place before the
	 * compilers, so it works on all four backends. The expr-compiler case for this symbol
	 * stays a nil no-op only because the rewrite already ran at macro-expansion time.
	 */
	public static final String DEFINE_SETF_EXPANDER = "DEFINE-SETF-EXPANDER";

	/**
	 * The {@code defsetf} macro (short and long forms). Registers a setf expansion for an
	 * accessor: the short form {@code (defsetf access update)} makes
	 * {@code (setf (access args...) v)} call {@code (update args... v)}; the long form
	 * {@code (defsetf access (lambda-list) (store-vars) body...)} evaluates its body at
	 * expansion time to produce the store form. Complements
	 * {@link #DEFINE_SETF_EXPANDER}.
	 */
	public static final String DEFSETF = "DEFSETF";

	/**
	 * The {@code define-compiler-macro} macro. Parsed no-op returning nil, like
	 * {@link #DECLAIM}/{@link #DEFTYPE}: a compiler macro is only an optimization hint,
	 * so dropping it leaves the ordinary function definition authoritative (the
	 * observable behavior is identical, only slower). The {@code &whole} parameter and
	 * any body are ignored.
	 */
	public static final String DEFINE_COMPILER_MACRO = "DEFINE-COMPILER-MACRO";

	/**
	 * The {@code restart-case} macro. Lite lowering to its primary form only: there is no
	 * restart/condition system, so the restart clauses are dead code (they can only be
	 * reached by {@code invoke-restart}, which does not exist here). A signaling primary
	 * form therefore signals as usual; see {@code .kb/declarations-type-checks.md} for
	 * the lite semantics shared with {@code check-type}/{@code assert}.
	 */
	public static final String RESTART_CASE = "RESTART-CASE";

	/**
	 * The {@code macrolet} macro (local, lexically scoped macro definitions). Expands its
	 * body with the local macros active and drops the definitions (see
	 * {@code LispMacroExpander}/{@code UserMacroExpander}/{@code LispEvaluator} macrolet
	 * handling). Local macro bodies run at expansion time like {@code defmacro}.
	 */
	public static final String MACROLET = "MACROLET";

	/**
	 * The {@code make-condition} operator. Lite expansion to the {@code :format-control}
	 * value (or the condition type name as a string), so the common
	 * {@code (error (make-condition 'type :format-control "..."))} idiom signals with the
	 * intended message. Classified as a macro here (in CL it is a function).
	 */
	public static final String MAKE_CONDITION = "MAKE-CONDITION";

	/**
	 * The {@code documentation} accessor. Lite: reads expand to nil and
	 * {@code (setf (documentation ...) "...")} discards the docstring (docstrings are not
	 * stored anywhere). Classified as a macro here (in CL it is a function).
	 */
	public static final String DOCUMENTATION = "DOCUMENTATION";

	/**
	 * The {@code pushnew} macro. Expands like {@link #PUSH} guarded by {@code member};
	 * extra {@code :test}/{@code :key} arguments are passed through to {@code member}.
	 */
	public static final String PUSHNEW = "PUSHNEW";

	/**
	 * The {@code eval-when} operator. Expands to {@code progn} of its body; at top level
	 * the compile path additionally splices the body into top-level forms (see
	 * {@code LispMacroExpander.flattenTopLevel}).
	 */
	public static final String EVAL_WHEN = "EVAL-WHEN";

	/**
	 * The {@code locally} operator. Expands to {@code progn} of its body with the leading
	 * declarations dropped (declarations are parsed no-ops).
	 */
	public static final String LOCALLY = "LOCALLY";

	/**
	 * The {@code flet} macro (local, non-recursive function bindings). Expands to
	 * let-bound lambdas plus a body rewrite of call position and {@code #'name} (see
	 * {@code LispMacroExpander.expandFlet}).
	 */
	public static final String FLET = "FLET";

	/**
	 * The {@code labels} macro. Like {@link #FLET} but the definitions see each other
	 * (mutual recursion) via the nil-then-{@code setq} letrec lowering.
	 */
	public static final String LABELS = "LABELS";

	/**
	 * The {@code values} function. In an ordinary (single-value) context it expands like
	 * {@code prog1}: every argument is evaluated and the first is the result (zero
	 * arguments yield nil). The multiple-value consumers recognize a literal
	 * {@code (values ...)} producer syntactically and receive all of its values (see
	 * {@code LispMacroExpander.lowerMvProducer}); there is no runtime multiple-value
	 * representation.
	 */
	public static final String VALUES = "VALUES";

	/**
	 * The {@code multiple-value-bind} macro. Binds the variables to the values of the
	 * producer form: a literal {@code (values ...)} call or a recognized two-value
	 * built-in ({@code floor}/{@code ceiling}/{@code round}/{@code truncate} and
	 * {@code gethash}); any other producer supplies a single value. Missing values bind
	 * to nil.
	 */
	public static final String MULTIPLE_VALUE_BIND = "MULTIPLE-VALUE-BIND";

	/**
	 * The {@code multiple-value-list} macro. Collects the producer's values (recognized
	 * like {@link #MULTIPLE_VALUE_BIND}) into a list.
	 */
	public static final String MULTIPLE_VALUE_LIST = "MULTIPLE-VALUE-LIST";

	/**
	 * The {@code multiple-value-call} macro. Calls the function with all values of every
	 * producer form (recognized like {@link #MULTIPLE_VALUE_BIND}) as the arguments;
	 * lowered to a direct {@code funcall}.
	 */
	public static final String MULTIPLE_VALUE_CALL = "MULTIPLE-VALUE-CALL";

	/**
	 * The {@code nth-value} macro. Returns the n-th (0-based) value of the producer form
	 * (recognized like {@link #MULTIPLE_VALUE_BIND}); expands to {@code nth} over
	 * {@code multiple-value-list}.
	 */
	public static final String NTH_VALUE = "NTH-VALUE";

	/**
	 * The {@code multiple-value-setq} macro. Assigns the multiple values of the producer
	 * form (recognized like {@link #MULTIPLE_VALUE_BIND}) to the existing variables via
	 * {@code setq} and returns the primary value; extra variables receive nil.
	 */
	public static final String MULTIPLE_VALUE_SETQ = "MULTIPLE-VALUE-SETQ";

	/** The {@code multiple-value-prog1} macro. */
	public static final String MULTIPLE_VALUE_PROG1 = "MULTIPLE-VALUE-PROG1";

	/**
	 * The {@code rotatef} macro. Rotates the values of its setf-able places left (each
	 * place receives the old value of the next, the last receives the old value of the
	 * first) and returns nil.
	 */
	public static final String ROTATEF = "ROTATEF";

	/**
	 * The {@code destructuring-bind} macro. Binds the variables of a (possibly nested)
	 * pattern to the corresponding parts of the evaluated form, supporting
	 * {@code &optional}/{@code &rest}/{@code &body}/{@code &key}/{@code &aux} inside the
	 * pattern; expands to a {@code let*} of car/cdr chains (see
	 * {@code LispMacroExpander.expandDestructuringBind}). Lite semantics: a mismatch
	 * between the pattern and the value does not signal (missing positions bind to nil,
	 * surplus elements are ignored); {@code &whole}/{@code &environment} are unsupported.
	 */
	public static final String DESTRUCTURING_BIND = "DESTRUCTURING-BIND";

	/**
	 * The {@code error} macro (signal an error). It builds the message with the
	 * {@code format} machinery and delegates to {@link #ERROR_INTERNAL}. Like
	 * {@code format} it has no function value (classified as a macro).
	 */
	public static final String ERROR = "ERROR";

	/**
	 * The {@code cerror} operator (lite): without restarts the error is not continuable,
	 * so {@code (cerror continue-format datum args...)} lowers to
	 * {@code (error datum args...)}.
	 */
	public static final String CERROR = "CERROR";

	/**
	 * Internal single-argument primitive that throws/traps with a pre-built message
	 * string. Not part of the public API; produced by the {@code error} macro expansion.
	 */
	public static final String ERROR_INTERNAL = "%ERROR";

	/**
	 * The {@code warn} macro (print a warning and continue). It builds the message with
	 * the {@code format} machinery like {@link #ERROR} and delegates to
	 * {@link #WARN_INTERNAL}; there is no condition system, so no condition object is
	 * created and nothing can handle or muffle the warning. Like {@code error} it has no
	 * function value (classified as a macro).
	 */
	public static final String WARN = "WARN";

	/**
	 * Internal single-argument primitive that writes {@code WARNING: message} plus a
	 * newline to the standard error stream and returns nil. Not part of the public API;
	 * produced by the {@code warn} macro expansion.
	 */
	public static final String WARN_INTERNAL = "%WARN";

	/**
	 * Internal two-argument primitive {@code (%error-cond condition message)} that
	 * signals a fatal error carrying a condition object (a CLOS-subset tagged-list
	 * instance) alongside the pre-built message string. Produced by the {@code error}
	 * macro expansion for the typed and condition-object designator forms; on the WASM
	 * backends it traps like {@link #ERROR_INTERNAL}.
	 */
	public static final String ERROR_COND_INTERNAL = "%ERROR-COND";

	/**
	 * The {@code signal} macro (signal a non-fatal condition). Same designator surface as
	 * {@link #ERROR}; when no handler is established the signal returns nil (the CL
	 * fall-through), which is the only behavior on the WASM backends.
	 */
	public static final String SIGNAL = "SIGNAL";

	/**
	 * Internal two-argument primitive {@code (%signal-cond condition message)} behind
	 * {@link #SIGNAL}: raises the condition when a {@code handler-case} handler is
	 * established on the current thread of control, and returns nil otherwise.
	 */
	public static final String SIGNAL_COND_INTERNAL = "%SIGNAL-COND";

	/**
	 * The {@code with-slots} macro: binds variables to the slot values of a CLOS-subset
	 * instance for the body. Lite (read-only): the bindings are plain {@code let}
	 * variables over {@code slot-value} reads, not symbol macros, so assigning one does
	 * NOT write back to the slot.
	 */
	public static final String WITH_SLOTS = "WITH-SLOTS";

	/**
	 * The {@code handler-case} operator: evaluates an expression, dispatching an error
	 * signaled during it to the first clause whose condition type matches (rethrowing
	 * when none does). Interpreter and JVM backends only; the WASM compilers reject it (a
	 * WASM error is an uncatchable trap).
	 */
	public static final String HANDLER_CASE = "HANDLER-CASE";

	/**
	 * The {@code ignore-errors} macro: sugar over {@code (handler-case (progn forms...)
	 * (error (c) (values nil c)))}. Interpreter and JVM backends only, like
	 * {@link #HANDLER_CASE}.
	 */
	public static final String IGNORE_ERRORS = "IGNORE-ERRORS";

	/**
	 * The {@code handler-bind} operator. NOT implemented on any backend (its handlers run
	 * at the signal point without unwinding, which the condition machinery does not
	 * model); the compilers lower a defun merely containing it to a call-time signal so a
	 * library carrying one in dead code stays compilable -- the same stub contract as a
	 * 2-arg {@code intern}. The interpreter errors at call time on its own (the name
	 * resolves to no function).
	 */
	public static final String HANDLER_BIND = "HANDLER-BIND";

	/**
	 * Internal zero-argument form that decrements the per-thread {@code handler-case}
	 * handler depth and yields nil. JVM backend only: emitted as the cleanup of the
	 * handler-depth bookkeeping when a {@code return} exits a {@code handler-case}
	 * protected region (the {@code UnwindScope} channel).
	 */
	public static final String HC_DEPTH_DEC_INTERNAL = "%HC-DEPTH-DEC";

	/** The {@code and} macro. */
	public static final String AND = "AND";

	/** The {@code or} macro. */
	public static final String OR = "OR";

	/** The {@code not} built-in function. */
	public static final String NOT = "NOT";

	/** The {@code when} macro. */
	public static final String WHEN = "WHEN";

	/** The {@code dotimes} macro. */
	public static final String DOTIMES = "DOTIMES";

	/** The {@code prog1} macro. */
	public static final String PROG1 = "PROG1";

	/**
	 * The {@code time} macro (evaluate a form, print the elapsed real time to standard
	 * output, and return the form's value).
	 */
	public static final String TIME = "TIME";

	/**
	 * The {@code prog2} macro (evaluate the forms in order and return the value of the
	 * second).
	 */
	public static final String PROG2 = "PROG2";

	/**
	 * The {@code psetq} macro (parallel assignment: every right-hand side is evaluated
	 * before any variable is assigned).
	 */
	public static final String PSETQ = "PSETQ";

	/**
	 * The {@code psetf} macro ({@code psetq} generalized to setf places): every place
	 * subform and every right-hand side is evaluated into a temporary before any
	 * assignment happens, so a later place reading a variable assigned by an earlier pair
	 * still sees the old value.
	 */
	public static final String PSETF = "PSETF";

	/**
	 * The {@code typecase} macro (dispatch on the type of an object using the built-in
	 * type predicates).
	 */
	public static final String TYPECASE = "TYPECASE";

	/** The {@code do} macro (parallel iteration). */
	public static final String DO = "DO";

	/**
	 * The {@code do*} macro (sequential iteration; {@code let*}-style bindings/steps).
	 */
	public static final String DO_STAR = "DO*";

	/**
	 * The {@code loop} macro. Only the "simple loop" subset is supported (numeric/list
	 * stepping, accumulation, simple control clauses); see
	 * {@link LispMacroExpander#expandLoop} for the exact grammar and limitations.
	 */
	public static final String LOOP = "LOOP";

	/** The {@code return} special form (non-local exit from the nearest loop block). */
	public static final String RETURN = "RETURN";

	/**
	 * The {@code return-from} macro. INTERPRETER: a real named non-local exit -- throws a
	 * name-carrying signal caught by the matching {@code block} (a {@code defun} body is
	 * wrapped in a block named after the function, a {@code defmethod} body in one named
	 * after the generic), so it crosses intervening {@code do}/{@code loop} blocks and
	 * closure calls within the exit's dynamic extent, as in CL; {@code (return-from nil
	 * v)} is plain {@code return}. COMPILE PATH (JVM / wasm-GC): a LEXICAL named exit --
	 * the jump targets the nearest lexically enclosing {@code block} with a matching name
	 * in the same compiled function, crossing intervening {@code do}/{@code loop}
	 * {@code %block}s; a {@code return-from} whose name matches no enclosing block exits
	 * the current function (the {@code %fn-block} function boundary), so a
	 * {@code return-from} inside a lambda cannot cross into its lexically enclosing
	 * function. See {@link am.ik.rontolisp.LambdaLists}.
	 */
	public static final String RETURN_FROM = "RETURN-FROM";

	/**
	 * The {@code block} operator. INTERPRETER: a real named block -- catches the
	 * {@code return-from} signal carrying its name ({@code (block nil ...)} additionally
	 * catches plain {@code return}, like the loop macros' implicit block). COMPILE PATH
	 * (JVM / wasm-GC): a lexical named block target compiled like {@code %block} but
	 * keyed by name ({@code (block nil ...)} behaves exactly like {@code %block}).
	 */
	public static final String BLOCK = "BLOCK";

	/**
	 * The internal {@code %block} special form establishing the {@code return} boundary
	 * that the loop macros ({@code do}/{@code dolist}/{@code dotimes}/{@code loop}) wrap
	 * their expansion in. Not part of the public Lisp API. The named {@code return-from}
	 * signal passes THROUGH it uncaught on the interpreter -- that transparency is what
	 * lets a named return cross an intervening loop.
	 */
	public static final String BLOCK_INTERNAL = "%BLOCK";

	/**
	 * The internal {@code (%fn-block name body...)} function-boundary block the compilers
	 * wrap a {@code return-from}-containing defun/lambda body in ({@code name} is the
	 * defun's name, {@code nil} for a lambda). It is a named block target (a
	 * {@code return-from} with the matching name exits the function) and ALSO the
	 * fallback target for a {@code return-from} whose name matches no lexically enclosing
	 * block -- the compile-path deviation that keeps a {@code return-from} inside a
	 * lambda a lambda-local exit (the interpreter's dynamic-extent crossing cannot span a
	 * separately compiled method). It does NOT catch plain {@code return}. Not part of
	 * the public Lisp API.
	 */
	public static final String FN_BLOCK_INTERNAL = "%FN-BLOCK";

	/**
	 * The internal {@code (%nlx-tag)} primitive: mints a fresh, unique identity object
	 * per evaluation -- the dynamic block-instance id that keys a cross-lambda non-local
	 * exit. The compile-path {@code CrossLambdaExitLowering} binds one per establishing
	 * block activation (via {@code let}) so recursion targets the right frame. Never
	 * printed or inspected; only compared by identity. Not part of the public Lisp API.
	 */
	public static final String NLX_TAG_INTERNAL = "%NLX-TAG";

	/**
	 * The internal {@code (%nlx-catch id body...)} form the compile-path
	 * {@code CrossLambdaExitLowering} wraps a block whose {@code return-from} escapes a
	 * nested lambda in. It runs {@code body} as an implicit {@code progn} inside an
	 * exception-handling region: a {@code %nlx-throw} whose id matches {@code id} is
	 * caught and its carried value becomes the form's value; any other throw (a real
	 * condition, or a {@code %nlx-throw} for an outer block) propagates. Only emitted
	 * when the program uses a cross-lambda exit (EH mode); not part of the public Lisp
	 * API.
	 */
	public static final String NLX_CATCH_INTERNAL = "%NLX-CATCH";

	/**
	 * The internal {@code (%nlx-throw id value)} form a cross-lambda {@code return-from}
	 * lowers to: it throws a non-local exit carrying the dynamic block-instance
	 * {@code id} (a lexical the lambda closed over) and {@code value}, unwinding the real
	 * call stack to the matching {@code %nlx-catch}. Intervening {@code unwind-protect}
	 * cleanups run on the way out. Not part of the public Lisp API.
	 */
	public static final String NLX_THROW_INTERNAL = "%NLX-THROW";

	// Type predicates

	/** The {@code null} built-in function. */
	public static final String NULL = "NULL";

	/** The {@code atom} built-in function. */
	public static final String ATOM = "ATOM";

	/** The {@code numberp} built-in function. */
	public static final String NUMBERP = "NUMBERP";

	/** The {@code integerp} built-in function. */
	public static final String INTEGERP = "INTEGERP";

	/** The {@code floatp} built-in function. */
	public static final String FLOATP = "FLOATP";

	/** The {@code rationalp} built-in function. */
	public static final String RATIONALP = "RATIONALP";

	/** The {@code symbolp} built-in function. */
	public static final String SYMBOLP = "SYMBOLP";

	/** The {@code stringp} built-in function. */
	public static final String STRINGP = "STRINGP";

	/** The {@code listp} built-in function. */
	public static final String LISTP = "LISTP";

	/** The {@code consp} built-in function. */
	public static final String CONSP = "CONSP";

	/** The {@code keywordp} built-in function. */
	public static final String KEYWORDP = "KEYWORDP";

	// Type conversion

	/** The {@code float} built-in function. */
	public static final String FLOAT = "FLOAT";

	/** The {@code truncate} built-in function. */
	public static final String TRUNCATE = "TRUNCATE";

	/** The {@code floor} built-in function. */
	public static final String FLOOR = "FLOOR";

	/** The {@code ceiling} built-in function. */
	public static final String CEILING = "CEILING";

	/** The {@code round} built-in function. */
	public static final String ROUND = "ROUND";

	/** The {@code numerator} built-in function. */
	public static final String NUMERATOR = "NUMERATOR";

	/** The {@code denominator} built-in function. */
	public static final String DENOMINATOR = "DENOMINATOR";

	// Convenience macros

	/** The {@code 1+} macro. */
	public static final String ONE_PLUS = "1+";

	/** The {@code 1-} macro. */
	public static final String ONE_MINUS = "1-";

	/** The {@code zerop} macro. */
	public static final String ZEROP = "ZEROP";

	/** The {@code plusp} macro. */
	public static final String PLUSP = "PLUSP";

	/** The {@code minusp} macro. */
	public static final String MINUSP = "MINUSP";

	/** The {@code evenp} macro. */
	public static final String EVENP = "EVENP";

	/** The {@code oddp} macro. */
	public static final String ODDP = "ODDP";

	/** The {@code unless} macro. */
	public static final String UNLESS = "UNLESS";

	/** The {@code first} macro. */
	public static final String FIRST = "FIRST";

	/** The {@code rest} macro. */
	public static final String REST = "REST";

	/** The {@code second} macro. */
	public static final String SECOND = "SECOND";

	/** The {@code third} macro. */
	public static final String THIRD = "THIRD";

	/** The {@code fourth} macro. */
	public static final String FOURTH = "FOURTH";

	/** The {@code nth} macro. */
	public static final String NTH = "NTH";

	// I/O

	/** The {@code print} built-in function. */
	public static final String PRINT = "PRINT";

	/** The {@code prin1} built-in function. */
	public static final String PRIN1 = "PRIN1";

	/** The {@code princ} built-in function. */
	public static final String PRINC = "PRINC";

	/** The {@code terpri} built-in function. */
	public static final String TERPRI = "TERPRI";

	/** The {@code fresh-line} built-in function (newline only if not at line start). */
	public static final String FRESH_LINE = "FRESH-LINE";

	/** The {@code princ-to-string} built-in function. */
	public static final String PRINC_TO_STRING = "PRINC-TO-STRING";

	/** The {@code prin1-to-string} built-in function. */
	public static final String PRIN1_TO_STRING = "PRIN1-TO-STRING";

	/** The {@code concatenate} built-in function (only {@code 'string} is supported). */
	public static final String CONCATENATE = "CONCATENATE";

	/** The {@code %string-concat} built-in function. */
	public static final String STRING_CONCAT = "%STRING-CONCAT";

	/** The {@code read-line} built-in function. */
	public static final String READ_LINE = "READ-LINE";

	/**
	 * The {@code read-char} built-in function (read a single character:
	 * {@code (read-char [stream [eof-error-p [eof-value]]])}). Works on the same stream
	 * handles as {@code read-line} (standard input, file streams and string input
	 * streams).
	 */
	public static final String READ_CHAR = "READ-CHAR";

	// String operations

	/** The {@code string} built-in function (string-designator coercion). */
	public static final String STRING = "STRING";

	/** The {@code string-upcase} built-in function. */
	public static final String STRING_UPCASE = "STRING-UPCASE";

	/** The {@code string-downcase} built-in function. */
	public static final String STRING_DOWNCASE = "STRING-DOWNCASE";

	/** The {@code string-capitalize} built-in function. */
	public static final String STRING_CAPITALIZE = "STRING-CAPITALIZE";

	/** The {@code subseq} built-in function (strings only). */
	public static final String SUBSEQ = "SUBSEQ";

	/**
	 * The internal {@code %subseq-core} operator (compile-path only): the JVM/WASM subseq
	 * compilers, without the arrayp dispatch that
	 * {@link am.ik.rontolisp.LispMacroExpander#expandSubseqCompat} injects to route a
	 * vector argument through {@code make-array} + {@code aref}. Never appears in user
	 * source (the expansion is only re-entered internally through the same subseq
	 * compilers).
	 */
	public static final String SUBSEQ_CORE = "%SUBSEQ-CORE";

	/**
	 * The {@code make-string} built-in function ({@code (make-string n &key
	 * initial-element element-type)}). Lowered to a fill loop over {@code concatenate};
	 * {@code element-type} is parsed and ignored (single string representation).
	 */
	public static final String MAKE_STRING = "MAKE-STRING";

	/**
	 * The {@code replace} built-in function ({@code (replace seq1 seq2 &key start1 end1
	 * start2 end2)}). String-aware; lowered to a {@code concatenate} of the untouched
	 * head/tail of {@code seq1} around the copied region of {@code seq2}. Since strings
	 * are immutable values, it returns a fresh string rather than mutating in place.
	 */
	public static final String REPLACE = "REPLACE";

	/** The {@code string=} built-in function (case-sensitive string equality). */
	public static final String STRING_EQ = "STRING=";

	/**
	 * The {@code string<} built-in function (case-sensitive lexicographic less-than;
	 * returns the mismatch index or nil). Implemented as a rontolisp-source {@code defun}
	 * shared by every backend (see {@code LispPreludeLibrary}).
	 */
	public static final String STRING_LT = "STRING<";

	/**
	 * The {@code string>} built-in function (case-sensitive lexicographic greater-than;
	 * returns the mismatch index or nil).
	 */
	public static final String STRING_GT = "STRING>";

	/**
	 * The {@code string<=} built-in function (case-sensitive lexicographic not-greater;
	 * returns the mismatch index or nil).
	 */
	public static final String STRING_LE = "STRING<=";

	/**
	 * The {@code string>=} built-in function (case-sensitive lexicographic not-less;
	 * returns the mismatch index or nil).
	 */
	public static final String STRING_GE = "STRING>=";

	/**
	 * The {@code string/=} built-in function (case-sensitive inequality; returns the
	 * mismatch index or nil).
	 */
	public static final String STRING_NE = "STRING/=";

	/** The {@code string-equal} built-in function (case-insensitive string equality). */
	public static final String STRING_EQUAL = "STRING-EQUAL";

	/**
	 * The {@code string-lessp} built-in function (case-insensitive lexicographic
	 * less-than; returns the mismatch index or nil).
	 */
	public static final String STRING_LESSP = "STRING-LESSP";

	/**
	 * The {@code string-greaterp} built-in function (case-insensitive lexicographic
	 * greater-than; returns the mismatch index or nil).
	 */
	public static final String STRING_GREATERP = "STRING-GREATERP";

	/**
	 * The {@code string-not-greaterp} built-in function (case-insensitive lexicographic
	 * not-greater; returns the mismatch index or nil).
	 */
	public static final String STRING_NOT_GREATERP = "STRING-NOT-GREATERP";

	/**
	 * The {@code string-not-lessp} built-in function (case-insensitive lexicographic
	 * not-less; returns the mismatch index or nil).
	 */
	public static final String STRING_NOT_LESSP = "STRING-NOT-LESSP";

	/**
	 * The {@code string-not-equal} built-in function (case-insensitive inequality;
	 * returns the mismatch index or nil).
	 */
	public static final String STRING_NOT_EQUAL = "STRING-NOT-EQUAL";

	/**
	 * The {@code %string-compare} internal helper backing the whole {@code string<} /
	 * {@code string-lessp} comparison family: one lexicographic walk returning
	 * {@code (order . mismatch-index)}.
	 */
	public static final String STRING_COMPARE = "%STRING-COMPARE";

	/** The {@code string-trim} built-in function. */
	public static final String STRING_TRIM = "STRING-TRIM";

	/** The {@code string-left-trim} built-in function. */
	public static final String STRING_LEFT_TRIM = "STRING-LEFT-TRIM";

	/** The {@code string-right-trim} built-in function. */
	public static final String STRING_RIGHT_TRIM = "STRING-RIGHT-TRIM";

	/** The {@code read} built-in function (interpreter only). */
	public static final String READ = "READ";

	/** The {@code read-from-string} built-in function (parse one form from a string). */
	public static final String READ_FROM_STRING = "READ-FROM-STRING";

	/** The {@code parse-integer} built-in function (parse an integer from a string). */
	public static final String PARSE_INTEGER = "PARSE-INTEGER";

	/** The {@code functionp} built-in function (is the value a function?). */
	public static final String FUNCTIONP = "FUNCTIONP";

	/**
	 * The {@code constantp} built-in function (true if the form is a constant object).
	 * Lite: true for self-evaluating objects (numbers, strings, characters, keywords,
	 * {@code t}/{@code nil}) and {@code (quote x)} forms; false otherwise (false
	 * negatives only push work to runtime).
	 */
	public static final String CONSTANTP = "CONSTANTP";

	/**
	 * The {@code get-setf-expansion} function (a prelude defun). Lite: returns the five
	 * setf-expansion values for a variable place ({@code setq} writer) or an accessor
	 * cons ({@code setf} writer over one temp per argument); the optional environment
	 * argument is accepted and ignored. The consumer destructures the values through the
	 * ordinary {@code %mv-spill} channel, so a portable {@code define-modify-macro}-style
	 * macro body ({@code multiple-value-bind} over this call) works unchanged.
	 */
	public static final String GET_SETF_EXPANSION = "GET-SETF-EXPANSION";

	/**
	 * The {@code streamp} built-in function (true if the argument is a stream). Streams
	 * are opaque integer handles across all backends, so this is lowered to
	 * {@code integerp} (lite).
	 */
	public static final String STREAMP = "STREAMP";

	/**
	 * The {@code simple-string-p} function, lowered to {@code stringp} (lite): every
	 * rontolisp string answers true, so the portable "coerce unless simple" idiom keeps
	 * the string unchanged instead of copying.
	 */
	public static final String SIMPLE_STRING_P = "SIMPLE-STRING-P";

	/**
	 * The internal {@code %arrayp} predicate (is the value an array?). Used by the
	 * {@code vector}/{@code array}/{@code sequence} type specifiers in
	 * {@code check-type}/{@code typecase} tests; not a public function.
	 */
	/**
	 * The {@code arrayp} built-in function: the standard spelling of the internal
	 * {@link #ARRAYP_INTERNAL} predicate (a string is an array, like CL).
	 */
	public static final String ARRAYP = "ARRAYP";

	public static final String ARRAYP_INTERNAL = "%ARRAYP";

	/**
	 * The {@code vectorp} built-in function (is the value a vector?). Strings are vectors
	 * in CL. Lite: like the {@code vector} type specifier, the rank is not checked (a
	 * rank-n array passes too).
	 */
	public static final String VECTORP = "VECTORP";

	/**
	 * The internal {@code %mv-spill} global variable carrying a producer's secondary
	 * values across a function boundary: every {@code (values ...)} call stores its extra
	 * values here (a fresh list) as it returns its primary, and a multiple-value consumer
	 * whose producer form is not syntactically recognized clears the spill, evaluates the
	 * producer, and reads the extras back. This is what makes {@code multiple-value-bind}
	 * over a user function work; the compilers inject a top-level
	 * {@code (setq %mv-spill nil)} to create the global when a program uses any
	 * multiple-value operator (the interpreter predefines it).
	 */
	public static final String MV_SPILL = "%MV-SPILL";

	// Characters

	/** The {@code char} built-in function (the character at an index of a string). */
	public static final String CHAR = "CHAR";

	/** The {@code schar} built-in function (a synonym for {@code char}). */
	public static final String SCHAR = "SCHAR";

	/**
	 * The {@code %schar-set} internal helper: the {@code (setf (schar s i) c)} /
	 * {@code (setf (char s i) c)} lowering, mutating the string in place and returning
	 * the stored character.
	 */
	public static final String SCHAR_SET = "%SCHAR-SET";

	/** The {@code char-code} built-in function (the code point of a character). */
	public static final String CHAR_CODE = "CHAR-CODE";

	/**
	 * The {@code code-char} built-in function (the character with a given code point).
	 */
	public static final String CODE_CHAR = "CODE-CHAR";

	/** The {@code char=} built-in function (character equality). */
	public static final String CHAR_EQ = "CHAR=";

	/** The {@code char<} built-in function (character less-than by code point). */
	public static final String CHAR_LT = "CHAR<";

	/**
	 * The {@code char<=} built-in function (character less-than-or-equal by code point).
	 */
	public static final String CHAR_LE = "CHAR<=";

	/** The {@code char>} built-in function (monotonically decreasing chain). */
	public static final String CHAR_GT = "CHAR>";

	/** The {@code char>=} built-in function (monotonically non-increasing chain). */
	public static final String CHAR_GE = "CHAR>=";

	/** The {@code char/=} built-in function (all arguments pairwise distinct). */
	public static final String CHAR_NE = "CHAR/=";

	/**
	 * The {@code char-equal} built-in function (case-insensitive {@code char=} chain).
	 */
	public static final String CHAR_EQUAL = "CHAR-EQUAL";

	/** The {@code char-upcase} built-in function (the uppercase form of a character). */
	public static final String CHAR_UPCASE = "CHAR-UPCASE";

	/**
	 * The {@code char-downcase} built-in function (the lowercase form of a character).
	 */
	public static final String CHAR_DOWNCASE = "CHAR-DOWNCASE";

	/** The {@code characterp} built-in function (true if the argument is a character). */
	public static final String CHARACTERP = "CHARACTERP";

	/**
	 * The {@code alpha-char-p} built-in function (true if the character is alphabetic).
	 */
	public static final String ALPHA_CHAR_P = "ALPHA-CHAR-P";

	/**
	 * The {@code alphanumericp} built-in function (true if the character is a letter or a
	 * decimal digit). A prelude defun over {@code alpha-char-p}/{@code digit-char-p}.
	 */
	public static final String ALPHANUMERICP = "ALPHANUMERICP";

	/**
	 * The {@code make-load-form-saving-slots} standard function. A prelude STUB that
	 * signals when called: rontolisp has no fasl dumper, but a library's
	 * {@code make-load-form} methods (cl-ppcre's charmap/charset) must still compile --
	 * the call sites are dead at run time.
	 */
	public static final String MAKE_LOAD_FORM_SAVING_SLOTS = "MAKE-LOAD-FORM-SAVING-SLOTS";

	/**
	 * The {@code sxhash} built-in function: a prelude defun hashing by structural content
	 * (integers/characters/strings/symbols/conses; anything else hashes to 0). Values are
	 * stable within a run but NOT specified across backends.
	 */
	public static final String SXHASH = "SXHASH";

	/**
	 * The {@code sbit} built-in function (+ its {@code (setf sbit)} writer): prelude
	 * defuns over {@code aref}, since a "bit vector" is the general array holding 0/1.
	 */
	/**
	 * The {@code bit} prelude function: a bit-array element (and its {@code setf}
	 * writer), the non-simple twin of {@link #SBIT}.
	 */
	public static final String BIT = "BIT";

	public static final String SBIT = "SBIT";

	/**
	 * The {@code both-case-p} built-in function: a prelude defun -- {@code lower-case-p}
	 * or {@code upper-case-p}.
	 */
	public static final String BOTH_CASE_P = "BOTH-CASE-P";

	/**
	 * The {@code special-operator-p} standard function: a lite prelude stub returning nil
	 * (compiled programs have no operator table; the interpreter's evaluator dispatch is
	 * not reified).
	 */
	public static final String SPECIAL_OPERATOR_P = "SPECIAL-OPERATOR-P";

	/**
	 * The {@code macro-function} standard function: a lite prelude stub returning nil
	 * (macros are fully expanded at compile time; no runtime macro table exists).
	 */
	public static final String MACRO_FUNCTION = "MACRO-FUNCTION";

	/**
	 * The {@code compiled-function-p} standard function: a lite prelude stub returning
	 * nil.
	 */
	public static final String COMPILED_FUNCTION_P = "COMPILED-FUNCTION-P";

	/**
	 * The {@code function-lambda-expression} standard function: a lite prelude stub
	 * returning {@code (values nil t nil)} (no source is recorded).
	 */
	public static final String FUNCTION_LAMBDA_EXPRESSION = "FUNCTION-LAMBDA-EXPRESSION";

	/**
	 * The {@code list-all-packages} standard function: a lite prelude stub returning nil
	 * (symbols are not interned into enumerable package tables; see
	 * {@code .kb/symbol-runtime-api.md}).
	 */
	public static final String LIST_ALL_PACKAGES = "LIST-ALL-PACKAGES";

	/**
	 * The {@code find-class} standard function: a lite prelude stub returning nil (no
	 * class metaobjects exist -- {@code class-of} is a name stub), regardless of
	 * {@code errorp}.
	 */
	public static final String FIND_CLASS = "FIND-CLASS";

	/**
	 * The {@code print-unreadable-object} macro: a lite expansion writing
	 * {@code #<[class ]...>} around the body ({@code :type} prints the {@code class-of}
	 * designator, {@code :identity} is ignored), returning nil.
	 */
	public static final String PRINT_UNREADABLE_OBJECT = "PRINT-UNREADABLE-OBJECT";

	/**
	 * The {@code with-package-iterator} macro: a lite expansion binding the iterator name
	 * to a LOCAL FUNCTION (an {@code flet}, not CL's {@code macrolet}) that always
	 * reports no more symbols -- there is no intern table to iterate, so the loop body
	 * runs zero times (cl-ppcre's regex-apropos).
	 */
	public static final String WITH_PACKAGE_ITERATOR = "WITH-PACKAGE-ITERATOR";

	/**
	 * The {@code do-external-symbols} macro: iterates a package's external symbols
	 * (interpreter-real over the package registry; the compile paths support it inside
	 * {@code #.} only, through the macro-time evaluator).
	 */
	public static final String DO_EXTERNAL_SYMBOLS = "DO-EXTERNAL-SYMBOLS";

	/**
	 * The {@code lower-case-p} built-in function (true if the character is a lowercase
	 * letter). Lowered to {@code (not (char= c (char-upcase c)))} so it follows the
	 * platform's Unicode case tables.
	 */
	public static final String LOWER_CASE_P = "LOWER-CASE-P";

	/**
	 * The {@code upper-case-p} built-in function (true if the character is an uppercase
	 * letter). Lowered to {@code (not (char= c (char-downcase c)))}.
	 */
	public static final String UPPER_CASE_P = "UPPER-CASE-P";

	/**
	 * The {@code digit-char-p} built-in function (the weight of a digit character in the
	 * given radix, or nil).
	 */
	/**
	 * The {@code digit-char} prelude function: the character denoting a weight in a radix
	 * (the inverse of {@code digit-char-p}), or nil.
	 */
	public static final String DIGIT_CHAR = "DIGIT-CHAR";

	public static final String DIGIT_CHAR_P = "DIGIT-CHAR-P";

	/** The {@code :radix} keyword recognized by {@code parse-integer}. */
	public static final String RADIX_KEYWORD = ":RADIX";

	/** The {@code :junk-allowed} keyword recognized by {@code parse-integer}. */
	public static final String JUNK_ALLOWED_KEYWORD = ":JUNK-ALLOWED";

	/** The {@code :start} keyword recognized by {@code parse-integer}. */
	public static final String START_KEYWORD = ":START";

	/** The {@code :end} keyword recognized by {@code parse-integer}. */
	public static final String END_KEYWORD = ":END";

	/** The {@code :start1} keyword recognized by {@code replace}. */
	public static final String START1_KEYWORD = ":START1";

	/** The {@code :end1} keyword recognized by {@code replace}. */
	public static final String END1_KEYWORD = ":END1";

	/** The {@code :start2} keyword recognized by {@code replace}. */
	public static final String START2_KEYWORD = ":START2";

	/** The {@code :end2} keyword recognized by {@code replace}. */
	public static final String END2_KEYWORD = ":END2";

	/** The {@code eval} built-in function (interpreter only). */
	public static final String EVAL = "EVAL";

	/** The {@code load} built-in function (interpreter only). */
	public static final String LOAD = "LOAD";

	/**
	 * The {@code require} built-in function: loads a module once. A runtime function on
	 * the interpreter; a literal, top-level compile-time directive on the compile path
	 * (consumed by {@code LoadInliner}).
	 */
	public static final String REQUIRE = "REQUIRE";

	/**
	 * The {@code provide} built-in function: marks a module as loaded so a later
	 * {@code require} of the same name is a no-op. A runtime function on the interpreter;
	 * a literal, top-level compile-time directive on the compile path (consumed by
	 * {@code LoadInliner}).
	 */
	public static final String PROVIDE = "PROVIDE";

	// File I/O

	/** The {@code open} built-in function. */
	public static final String OPEN = "OPEN";

	/** The {@code close} built-in function. */
	public static final String CLOSE = "CLOSE";

	/**
	 * The {@code probe-file} built-in function: the pathname when the file exists,
	 * {@code nil} otherwise. The one file primitive that does NOT signal on a missing
	 * path, so it is the only way to ask the question on WASM (where a failed
	 * {@code open} traps rather than signalling, which no {@code handler-case} can
	 * catch). rontolisp represents a pathname as its namestring, so the "truename" it
	 * answers with is the argument string itself -- no backend resolves symlinks or makes
	 * the path absolute.
	 */
	public static final String PROBE_FILE = "PROBE-FILE";

	/** The {@code write-line} built-in function. */
	public static final String WRITE_LINE = "WRITE-LINE";

	/**
	 * The {@code with-open-stream} macro: binds a variable to an already-open stream
	 * expression and closes it on exit (the {@code with-open-file} shape without the
	 * open).
	 */
	public static final String WITH_OPEN_STREAM = "WITH-OPEN-STREAM";

	/** The {@code with-open-file} macro. */
	public static final String WITH_OPEN_FILE = "WITH-OPEN-FILE";

	/**
	 * The {@code unwind-protect} special form: runs cleanup forms on every exit from the
	 * protected form (normal return, {@code error} unwind, {@code return}/
	 * {@code return-from}). Interpreter and JVM backends only; a WASM error is an
	 * uncatchable trap, so the WASM compilers reject it.
	 */
	public static final String UNWIND_PROTECT = "UNWIND-PROTECT";

	/** The {@code :direction} keyword recognized by {@code with-open-file}. */
	public static final String DIRECTION_KEYWORD = ":DIRECTION";

	/** The {@code :initial-value} keyword recognized by {@code reduce}. */
	public static final String INITIAL_VALUE_KEYWORD = ":INITIAL-VALUE";

	/**
	 * The {@code :test} keyword recognized by {@code member} (the equality predicate).
	 */
	public static final String TEST_KEYWORD = ":TEST";

	/**
	 * The {@code :key} keyword recognized by the sequence and alist functions (a selector
	 * applied to each element before the equality test).
	 */
	public static final String KEY_KEYWORD = ":KEY";

	/**
	 * The {@code :test-not} keyword recognized by {@code position} (the negated equality
	 * predicate).
	 */
	public static final String TEST_NOT_KEYWORD = ":TEST-NOT";

	/**
	 * The {@code :from-end} keyword recognized by the {@code position} family (when true,
	 * the index of the last match is returned).
	 */
	public static final String FROM_END_KEYWORD = ":FROM-END";

	/** The {@code :input} keyword (open a file for reading). */
	public static final String INPUT_KEYWORD = ":INPUT";

	/** The {@code :output} keyword (open a file for writing). */
	public static final String OUTPUT_KEYWORD = ":OUTPUT";

	/**
	 * The {@code :element-type} keyword recognized by {@code with-open-file} (and as the
	 * optional third {@code open} argument): the literal {@code '(unsigned-byte 8)}
	 * selects a binary stream, the literal {@code 'character} the default text stream.
	 */
	public static final String ELEMENT_TYPE_KEYWORD = ":ELEMENT-TYPE";

	/** The {@code unsigned-byte} type specifier symbol used in {@code :element-type}. */
	public static final String UNSIGNED_BYTE = "UNSIGNED-BYTE";

	/** The {@code character} type specifier symbol used in {@code :element-type}. */
	public static final String CHARACTER_TYPE = "CHARACTER";

	/**
	 * The {@code double-float} type specifier symbol. As the {@code :element-type} of
	 * {@code make-array} (and the element type of a {@code #d(...)} literal) it selects
	 * the packed {@link am.ik.rontolisp.LispDoubleFloatArray} representation.
	 */
	public static final String DOUBLE_FLOAT = "DOUBLE-FLOAT";

	/**
	 * The {@code single-float} type specifier symbol. As the {@code :element-type} of
	 * {@code make-array} (and the element type of a {@code #f(...)} literal) it selects
	 * the packed {@link am.ik.rontolisp.LispSingleFloatArray} representation (f32
	 * backing; scalars still read/write as {@code double}, widening on read and narrowing
	 * on write).
	 */
	public static final String SINGLE_FLOAT = "SINGLE-FLOAT";

	/** The {@code read-byte} built-in function (binary streams only). */
	public static final String READ_BYTE = "READ-BYTE";

	/** The {@code write-byte} built-in function (binary streams only). */
	public static final String WRITE_BYTE = "WRITE-BYTE";

	/** The {@code read-sequence} macro (fills a vector from a binary stream). */
	public static final String READ_SEQUENCE = "READ-SEQUENCE";

	/** The {@code write-sequence} macro (writes a vector to a binary stream). */
	public static final String WRITE_SEQUENCE = "WRITE-SEQUENCE";

	/**
	 * The {@code force-output} built-in function: flushes an output stream's buffered
	 * bytes to the underlying sink ({@code finish-output} is the same operation here --
	 * every write is synchronous once flushed). Returns nil.
	 */
	public static final String FORCE_OUTPUT = "FORCE-OUTPUT";

	/** The {@code finish-output} built-in function (alias of {@code force-output}). */
	public static final String FINISH_OUTPUT = "FINISH-OUTPUT";

	/**
	 * The {@code listen} built-in function: whether a character/byte is immediately
	 * available on an input stream without blocking.
	 */
	public static final String LISTEN = "LISTEN";

	// String streams

	/** The {@code write-string} built-in function (a string to a stream, no newline). */
	public static final String WRITE_STRING = "WRITE-STRING";

	/**
	 * The {@code write-char} operator: writes a single character, expanding to
	 * {@code write-string} of its one-character string on every backend.
	 */
	public static final String WRITE_CHAR = "WRITE-CHAR";

	/**
	 * The {@code write-to-string} built-in function (a {@code prin1-to-string} alias).
	 */
	public static final String WRITE_TO_STRING = "WRITE-TO-STRING";

	/** The {@code with-output-to-string} macro (collect output into a string). */
	public static final String WITH_OUTPUT_TO_STRING = "WITH-OUTPUT-TO-STRING";

	/** The {@code with-input-from-string} macro (read from a string as a stream). */
	public static final String WITH_INPUT_FROM_STRING = "WITH-INPUT-FROM-STRING";

	/**
	 * The {@code with-standard-io-syntax} macro. Expands to {@code progn} of its body:
	 * every reader/printer control variable Common Lisp asks it to rebind is, in
	 * rontolisp, either informational or resolved before run time -- see
	 * {@code LispMacroExpander#expandWithStandardIoSyntax} for the per-variable audit and
	 * for when this has to stop being an identity.
	 */
	public static final String WITH_STANDARD_IO_SYNTAX = "WITH-STANDARD-IO-SYNTAX";

	/** The internal {@code %make-string-output-stream} helper (string-builder stream). */
	public static final String MAKE_STRING_OUTPUT_STREAM = "%MAKE-STRING-OUTPUT-STREAM";

	/** The internal {@code %make-string-input-stream} helper (read from a string). */
	public static final String MAKE_STRING_INPUT_STREAM = "%MAKE-STRING-INPUT-STREAM";

	/**
	 * The internal {@code %string-stream-contents} helper (the string accumulated by a
	 * {@code %make-string-output-stream} stream).
	 */
	public static final String STRING_STREAM_CONTENTS = "%STRING-STREAM-CONTENTS";

	// Packages

	/** The {@code in-package} directive that switches the current package. */
	public static final String IN_PACKAGE = "IN-PACKAGE";

	/**
	 * The {@code defpackage} directive that defines a new package. Like
	 * {@code in-package}, it is a literal, top-level, read/compile-time directive
	 * consumed by the {@code PackageResolver}.
	 */
	public static final String DEFPACKAGE = "DEFPACKAGE";

	/**
	 * Internal marker inserted by {@code LoadInliner} before the spliced forms of a
	 * loaded file: it makes the {@code PackageResolver} save the current package so a
	 * file's internal {@code in-package} cannot leak past the load, mirroring Common Lisp
	 * binding {@code *package*} around a {@code load}. Consumed by the resolver, never
	 * reaching the backends. Paired with {@link #POP_PACKAGE}.
	 */
	public static final String PUSH_PACKAGE = "%PUSH-PACKAGE";

	/**
	 * Internal marker inserted by {@code LoadInliner} after the spliced forms of a loaded
	 * file: it makes the {@code PackageResolver} restore the package saved by the
	 * matching {@link #PUSH_PACKAGE}. Consumed by the resolver, never reaching the
	 * backends.
	 */
	public static final String POP_PACKAGE = "%POP-PACKAGE";

	/**
	 * Internal marker inserted by {@code LoadInliner} before the forms it splices for an
	 * ASDF system, carrying the system's name as a string: it is the provenance the
	 * {@code LibraryDefunPruner} tree-shakes a third-party tree by, since no other pass
	 * records which file a top-level form came from. Brackets nest with
	 * {@code :depends-on}, and the innermost one wins. Consumed by the resolver like
	 * {@link #PUSH_PACKAGE} (without touching the package stack), never reaching the
	 * backends. Paired with {@link #END_SYSTEM}.
	 */
	public static final String BEGIN_SYSTEM = "%BEGIN-SYSTEM";

	/**
	 * Internal marker inserted by {@code LoadInliner} after the forms it splices for an
	 * ASDF system, closing the innermost {@link #BEGIN_SYSTEM}. Consumed by the resolver,
	 * never reaching the backends.
	 */
	public static final String END_SYSTEM = "%END-SYSTEM";

	/** The {@code :use} clause keyword of {@code defpackage}. */
	public static final String USE_KEYWORD = ":USE";

	/** The {@code :export} clause keyword of {@code defpackage}. */
	public static final String EXPORT_KEYWORD = ":EXPORT";

	/** The {@code :nicknames} clause keyword of {@code defpackage}. */
	public static final String NICKNAMES_KEYWORD = ":NICKNAMES";

	/** The {@code :import-from} clause keyword of {@code defpackage}. */
	public static final String IMPORT_FROM_KEYWORD = ":IMPORT-FROM";

	/** The {@code *package*} variable holding the current package name. */
	public static final String PACKAGE_VAR = "*PACKAGE*";

	/**
	 * The {@code *read-default-float-format*} variable. Every float shares the one double
	 * representation, so its value ({@code double-float}) is informational: it exists so
	 * library code that reads it (or rebinds it through a keyword argument, lexically
	 * here) loads and runs.
	 */
	public static final String READ_DEFAULT_FLOAT_FORMAT = "*READ-DEFAULT-FLOAT-FORMAT*";

	/**
	 * The {@code values-list} built-in function (spread a list as multiple values through
	 * the {@code %mv-spill} channel).
	 */
	public static final String VALUES_LIST = "VALUES-LIST";

	/**
	 * The {@code complex} operator. Lite: no complex representation exists, so a zero
	 * imaginary part yields the real part and anything else signals (classified as a
	 * macro here, in CL it is a function).
	 */
	public static final String COMPLEX = "COMPLEX";

	/**
	 * The {@code *features*} variable, substituted at read time with the active feature
	 * list (see {@code reader.Features}).
	 */
	public static final String FEATURES_VAR = "*FEATURES*";

	/** The {@code version} function provided by the {@code rontolisp} package. */
	public static final String VERSION = "VERSION";

	/** The {@code list-functions} function provided by the {@code rontolisp} package. */
	public static final String LIST_FUNCTIONS = "LIST-FUNCTIONS";

	/** The {@code list-macros} function provided by the {@code rontolisp} package. */
	public static final String LIST_MACROS = "LIST-MACROS";

	/**
	 * The {@code list-special-forms} function provided by the {@code rontolisp} package.
	 */
	public static final String LIST_SPECIAL_FORMS = "LIST-SPECIAL-FORMS";

	/**
	 * The {@code fetch} function provided by the {@code rontolisp} package. Starts an
	 * outgoing HTTP request (JavaScript {@code fetch}-style) and immediately returns a
	 * <em>future</em> while the request runs asynchronously. The optional second argument
	 * is an options property list ({@code :method}, {@code :headers}, {@code :body}).
	 * Awaiting the future yields the result property list
	 * {@code (:status <int> :headers <alist> :body <stream>)} whose body is a stream of
	 * string chunks ({@code rontolisp:read-all} drains it).
	 */
	public static final String FETCH = "FETCH";

	/**
	 * The {@code await} special form provided by the {@code rontolisp} package. Legal
	 * inside {@code rontolisp:async-defun}/{@code async-lambda} bodies and at top level
	 * (the top level is implicitly asynchronous). Given a future, suspends the current
	 * asynchronous function until it settles and returns its value; a future that settled
	 * with an error re-signals that condition. A settled future never suspends, nested
	 * futures are flattened, and any other value is returned unchanged, like a JavaScript
	 * {@code await} on a non-promise.
	 */
	public static final String AWAIT = "AWAIT";

	/**
	 * The {@code async} wrapper macro provided by the {@code rontolisp} package. Wraps an
	 * ordinary defining form and turns it into its asynchronous counterpart, for a
	 * notation closer to JavaScript's {@code async function} / {@code async () =>}:
	 * {@code (rontolisp:async (defun f (x) ...))} expands to
	 * {@code (rontolisp:async-defun f (x) ...)} and
	 * {@code (rontolisp:async (lambda (x) ...))} to
	 * {@code (rontolisp:async-lambda (x) ...)}; anything else inside is an error. A pure
	 * frontend rewrite: the expansion runs before the async machinery looks at the
	 * program, so every backend only ever sees the canonical lowered forms.
	 */
	public static final String ASYNC = "ASYNC";

	/**
	 * The {@code async-defun} special form provided by the {@code rontolisp} package.
	 * Defines an asynchronous function: same surface as {@code defun} (full lambda-list
	 * support), but calling it runs the body only until its first suspension point (an
	 * {@code rontolisp:await} of an unsettled future) and returns a future that settles
	 * with the body's value (or its error).
	 */
	public static final String ASYNC_DEFUN = "ASYNC-DEFUN";

	/**
	 * The {@code async-lambda} special form provided by the {@code rontolisp} package.
	 * The anonymous counterpart of {@code rontolisp:async-defun}: evaluates to a function
	 * value whose invocation returns a future.
	 */
	public static final String ASYNC_LAMBDA = "ASYNC-LAMBDA";

	/**
	 * The {@code futurep} predicate provided by the {@code rontolisp} package. Returns
	 * {@code t} if the argument is a future (as returned by calling an
	 * {@code rontolisp:async-defun} function or {@code rontolisp:fetch}), {@code nil}
	 * otherwise.
	 */
	public static final String FUTUREP = "FUTUREP";

	/**
	 * The {@code streamp} predicate provided by the {@code rontolisp} package. Returns
	 * {@code t} if the argument is an asynchronous stream value (as returned by
	 * {@code rontolisp:make-stream} or carried in a {@code rontolisp:fetch} response
	 * body), {@code nil} otherwise. The same spelling as the {@code cl:streamp}
	 * file-stream predicate ({@link #STREAMP}) but a different symbol: the packages
	 * disambiguate, and each predicate answers {@code nil} for the other's streams.
	 */
	public static final String ASYNC_STREAMP = "STREAMP";

	/**
	 * The {@code make-stream} function provided by the {@code rontolisp} package. Creates
	 * a fresh open asynchronous stream; one value owns both the read and the write end.
	 * Producers append chunks with {@code rontolisp:stream-write} and finish with
	 * {@code rontolisp:stream-close}; consumers take chunks with
	 * {@code rontolisp:stream-read}.
	 */
	public static final String MAKE_STREAM = "MAKE-STREAM";

	/**
	 * The {@code stream-read} function provided by the {@code rontolisp} package. Returns
	 * a future that settles to the stream's next chunk, or {@code nil} once the stream is
	 * closed and drained (end of stream). Chunks are never {@code nil}.
	 */
	public static final String STREAM_READ = "STREAM-READ";

	/**
	 * The {@code stream-write} function provided by the {@code rontolisp} package.
	 * Appends a chunk to a stream and returns a future that settles when the stream has
	 * accepted it, so producers can be flow-controlled with {@code rontolisp:await}.
	 */
	public static final String STREAM_WRITE = "STREAM-WRITE";

	/**
	 * The {@code stream-close} function provided by the {@code rontolisp} package. Closes
	 * a stream's write end: pending and future reads drain the remaining chunks and then
	 * observe end of stream.
	 */
	public static final String STREAM_CLOSE = "STREAM-CLOSE";

	/**
	 * The {@code read-all} function provided by the {@code rontolisp} package. Returns a
	 * future that settles to the concatenation of all remaining string chunks of a stream
	 * (an error is signaled for a non-string chunk).
	 */
	public static final String READ_ALL = "READ-ALL";

	/**
	 * The {@code wait-for} function provided by the {@code rontolisp} package. Returns a
	 * future that settles (to {@code nil}) after the given number of milliseconds -- the
	 * timer primitive of the async/await surface, mirroring WASI 0.3's
	 * {@code wasi:clocks/monotonic-clock.wait-for}. Deliberately NOT named {@code sleep}:
	 * Common Lisp's {@code sleep} is a blocking function taking seconds, while this one
	 * only starts a timer ({@code (rontolisp:await (rontolisp:wait-for
	 * 500))} is the sleeping form).
	 */
	public static final String WAIT_FOR = "WAIT-FOR";

	/**
	 * The {@code then} future-as-value combinator provided by the {@code rontolisp}
	 * package: {@code (rontolisp:then future fn)} returns a fresh future that, on the
	 * input's successful settlement, applies {@code fn} to its value; on error it
	 * propagates the condition through unchanged (the JavaScript {@code .then} shape).
	 * Implemented as a Lisp-prelude {@code defun} over {@code async-lambda} +
	 * {@code await}, so every backend supports it identically.
	 */
	public static final String THEN = "THEN";

	/**
	 * The {@code then*} variadic future combinator provided by the {@code rontolisp}
	 * package: {@code (rontolisp:then* future fn1 fn2 ...)} chains each function through
	 * the previous stage's (flattened) settled value. With no callbacks the operator
	 * returns the input future unchanged. A stage that returns a future is auto-flattened
	 * on the next stage's read.
	 */
	public static final String THEN_STAR = "THEN*";

	/**
	 * The {@code catch} future-as-value combinator provided by the {@code rontolisp}
	 * package: {@code (rontolisp:catch future handler)} returns a fresh future that, on
	 * the input's error, invokes {@code handler} on the condition and settles to its
	 * return value; on success it passes the value through. Deliberately named
	 * {@code catch}: it is the JavaScript {@code .catch} shape, not the CL
	 * {@code catch}/{@code throw} tag-based non-local exit. Users writing in the
	 * {@code cl-user} package (or with {@code cl:} qualified) still get the CL sense; a
	 * program that qualifies with {@code rontolisp:}/{@code rl:} gets this operator.
	 */
	public static final String CATCH = "CATCH";

	/**
	 * The {@code finally} future-as-value combinator provided by the {@code rontolisp}
	 * package: {@code (rontolisp:finally future thunk)} returns a fresh future carrying
	 * the input's original settlement (value or condition) and runs the thunk exactly
	 * once on either outcome. The thunk's return value is discarded; a condition raised
	 * inside the thunk replaces the pending outcome (matches {@code unwind-protect}).
	 */
	public static final String FINALLY = "FINALLY";

	/**
	 * The internal {@code %async-run} primitive backing the
	 * {@code rontolisp:async-defun}/{@code async-lambda} lowering on the interpreter, JVM
	 * and Preview-1 WASM backends: takes a zero-argument function value, runs it under
	 * the backend's asynchronous mechanism and returns the resulting future. The
	 * {@code --component} backend compiles the async defining forms natively (state
	 * machines) and never sees this name.
	 */
	public static final String ASYNC_RUN = "%ASYNC-RUN";

	/**
	 * The internal {@code rontolisp::%future-new} test primitive of the
	 * {@code --component} async state machines: a fresh PENDING first-class future.
	 * Undocumented; it exists so the suspension machinery (spill/restore, waiter cascade)
	 * is exercisable end-to-end before the Phase-8 import layer produces pending futures
	 * of its own.
	 */
	public static final String FUTURE_NEW_INTERNAL = "%FUTURE-NEW";

	/**
	 * The internal {@code rontolisp::%future-settle} test primitive: settles a pending
	 * future with a value, resuming its waiters. See {@link #FUTURE_NEW_INTERNAL}.
	 */
	public static final String FUTURE_SETTLE_INTERNAL = "%FUTURE-SETTLE";

	/**
	 * The internal {@code rontolisp::%future-reject} test primitive: rejects a pending
	 * future with a message (a {@code simple-error} re-signals at await). See
	 * {@link #FUTURE_NEW_INTERNAL}.
	 */
	public static final String FUTURE_REJECT_INTERNAL = "%FUTURE-REJECT";

	/**
	 * The internal {@code rontolisp::%subtask-future} primitive of the
	 * {@code --component} async import layer: takes an async-lowered call's
	 * {@code (packed . retptr)} token and the member's lift wrapper (as a function
	 * value), and returns a first-class future -- settled immediately when the call
	 * completed eagerly ({@code RETURNED}), otherwise pending and registered in the
	 * task's waitable&rarr;future scheduler registry, to be settled by the event loop
	 * when the subtask reports {@code RETURNED}. Synthesized by
	 * {@code WitImportDirective} for {@code async func} members; component-only.
	 */
	public static final String SUBTASK_FUTURE_INTERNAL = "%SUBTASK-FUTURE";

	/**
	 * The internal {@code rontolisp::%wasi-stream-new} primitive of the
	 * {@code --component} async import layer: takes a read thunk and a close thunk
	 * (arity-0 function values over a wasi byte-stream handle) and returns a first-class
	 * stream value ({@code TYPE_WASI_STREAM}) that
	 * {@code rontolisp:stream-read}/{@code stream-close}/{@code streamp} operate on.
	 * Synthesized by http.lisp for request/response bodies; component-only.
	 */
	public static final String WASI_STREAM_NEW_INTERNAL = "%WASI-STREAM-NEW";

	/**
	 * The internal {@code rontolisp::%future-force} primitive of the {@code --component}
	 * synchronous surface: blocks on the module scheduler ({@code _sched_loop}) until the
	 * given future settles and yields its value (a rejection re-signals, like await). It
	 * is what lets a synchronous built-in surface (the tcp-* wrappers in sockets.lisp)
	 * sit on an asynchronous WIT import; async bodies get the await-shaped promotion
	 * instead. Component-only.
	 */
	public static final String FUTURE_FORCE_INTERNAL = "%FUTURE-FORCE";

	/**
	 * The internal {@code rontolisp::%read-line-raw}/{@code %read-char-raw}/
	 * {@code %read-byte-raw}/{@code %write-line-raw}/{@code %write-byte-raw}/
	 * {@code %close-raw} aliases of the NATIVE stream built-ins on the
	 * {@code --component} backend: the socket-dispatch defuns sockets.lisp splices
	 * ({@code %io-read-line} &amp;c) fall back through these for a non-socket handle, so
	 * the compile-time socket rewrite of the public names cannot recurse. Component-only.
	 */
	public static final String READ_LINE_RAW_INTERNAL = "%READ-LINE-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String READ_CHAR_RAW_INTERNAL = "%READ-CHAR-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String READ_BYTE_RAW_INTERNAL = "%READ-BYTE-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String WRITE_LINE_RAW_INTERNAL = "%WRITE-LINE-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String WRITE_BYTE_RAW_INTERNAL = "%WRITE-BYTE-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String WRITE_STRING_RAW_INTERNAL = "%WRITE-STRING-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String CLOSE_RAW_INTERNAL = "%CLOSE-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String READ_SEQUENCE_RAW_INTERNAL = "%READ-SEQUENCE-RAW";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String WRITE_SEQUENCE_RAW_INTERNAL = "%WRITE-SEQUENCE-RAW";

	/**
	 * The internal {@code rontolisp::%str-byte-length} accessor of the
	 * {@code --component} socket layer: the content BYTE count of a string (its
	 * {@code $str_bytes} length minus the two surrounding quotes). A socket chunk's bytes
	 * are the wire truth, and the character accessors UTF-8-decode them (binary bytes
	 * that happen to form a valid multi-byte sequence would collapse), so sockets.lisp's
	 * chunk-buffer bookkeeping walks bytes through this family instead. Component-only.
	 */
	public static final String STR_BYTE_LENGTH_INTERNAL = "%STR-BYTE-LENGTH";

	/**
	 * The internal {@code rontolisp::%str-byte-ref} accessor: the i-th content byte
	 * (0-255) of a string. See {@link #STR_BYTE_LENGTH_INTERNAL}.
	 */
	public static final String STR_BYTE_REF_INTERNAL = "%STR-BYTE-REF";

	/**
	 * The internal {@code rontolisp::%str-from-byte} constructor: a fresh string whose
	 * ONE content byte is the given value (0-255), regardless of UTF-8 validity -- what
	 * lets the socket {@code write-byte} put exactly one byte on the wire through the
	 * write path's raw {@code $str_bytes} copy. See {@link #STR_BYTE_LENGTH_INTERNAL}.
	 */
	public static final String STR_FROM_BYTE_INTERNAL = "%STR-FROM-BYTE";

	/**
	 * The {@code json-parse} function provided by the {@code rontolisp} package. Parses a
	 * JSON document string into Lisp values following {@code com.inuoe.jzon}'s defaults:
	 * a JSON object becomes a hash table with string keys, an array a simple vector, and
	 * {@code true}/{@code false}/{@code null} become {@code t}/{@code nil}/the symbol
	 * {@code null} -- so it is a lightweight, forward-compatible subset of jzon.
	 */
	public static final String JSON_PARSE = "JSON-PARSE";

	/**
	 * The {@code json-stringify} function provided by the {@code rontolisp} package.
	 * Serializes a Lisp value into a JSON document string following
	 * {@code com.inuoe.jzon}'s defaults: {@code nil} is {@code false}, the symbol
	 * {@code null} is {@code null}, a vector or list is an array, and a hash table is an
	 * object -- the inverse of {@code rontolisp:json-parse}.
	 */
	public static final String JSON_STRINGIFY = "JSON-STRINGIFY";

	/**
	 * The {@code plist-hash-table} function provided by the {@code rontolisp} package. A
	 * lightweight subset of {@code alexandria:plist-hash-table}: builds a hash table from
	 * a property list (odd elements keys, even elements values), passing any trailing
	 * arguments on to {@code make-hash-table}. Pairs naturally with
	 * {@code rontolisp:json-stringify} for building JSON objects.
	 */
	public static final String PLIST_HASH_TABLE = "PLIST-HASH-TABLE";

	/**
	 * The {@code hash-table-plist} function provided by the {@code rontolisp} package. A
	 * lightweight subset of {@code alexandria:hash-table-plist}: returns a property list
	 * of the hash table's key/value pairs (the inverse of {@link #PLIST_HASH_TABLE}).
	 */
	public static final String HASH_TABLE_PLIST = "HASH-TABLE-PLIST";

	/**
	 * The {@code alist-hash-table} function provided by the {@code rontolisp} package. A
	 * lightweight subset of {@code alexandria:alist-hash-table}: builds a hash table from
	 * an association list (first occurrence of a key wins), passing any trailing
	 * arguments on to {@code make-hash-table}.
	 */
	public static final String ALIST_HASH_TABLE = "ALIST-HASH-TABLE";

	/**
	 * The {@code hash-table-alist} function provided by the {@code rontolisp} package. A
	 * lightweight subset of {@code alexandria:hash-table-alist}: returns an association
	 * list of the hash table's key/value pairs (the inverse of
	 * {@link #ALIST_HASH_TABLE}).
	 */
	public static final String HASH_TABLE_ALIST = "HASH-TABLE-ALIST";

	/**
	 * The {@code url-decode} function provided by the {@code rontolisp} package. Decodes
	 * a percent-encoded (URL-encoded) string: {@code %XX} byte sequences are decoded as
	 * UTF-8 and {@code +} becomes a space.
	 */
	public static final String URL_DECODE = "URL-DECODE";

	/**
	 * The {@code url-encode} function provided by the {@code rontolisp} package. Encodes
	 * a string for use in a URL: unreserved characters (letters, digits, {@code -},
	 * {@code _}, {@code .}, {@code ~}) pass through and everything else becomes
	 * percent-encoded UTF-8 bytes (a space becomes {@code %20}).
	 */
	public static final String URL_ENCODE = "URL-ENCODE";

	/**
	 * The {@code query-params} function provided by the {@code rontolisp} package. Parses
	 * a query string such as {@code "a=1&b=two&flag"} into an alist of
	 * {@code (key . value)} string pairs with keys and values url-decoded, duplicates
	 * preserved in order; {@code nil} yields {@code nil}.
	 */
	public static final String QUERY_PARAMS = "QUERY-PARAMS";

	/**
	 * The {@code query-param} function provided by the {@code rontolisp} package. Returns
	 * the url-decoded value of the first match of a name in a query string, or
	 * {@code nil}; {@code nil}-safe in the query argument.
	 */
	public static final String QUERY_PARAM = "QUERY-PARAM";

	/**
	 * The {@code url-path} function provided by the {@code rontolisp} package. Returns
	 * the part of a URL or request-target string before the first {@code ?}.
	 */
	public static final String URL_PATH = "URL-PATH";

	/**
	 * The {@code url-query} function provided by the {@code rontolisp} package. Returns
	 * the raw query-string part of a URL or request-target string (after the first
	 * {@code ?}, possibly empty), or {@code nil} when there is no {@code ?}.
	 */
	public static final String URL_QUERY = "URL-QUERY";

	/**
	 * The {@code tcp-connect} function provided by the {@code rontolisp} package. Opens a
	 * blocking TCP connection to {@code host} (a hostname or IP literal; the WASM
	 * component backend supports IPv4 literals only) and {@code port}, and returns a
	 * bidirectional stream handle usable with {@code read-line}, {@code write-line},
	 * {@code read-byte}, {@code write-byte} and {@code close}.
	 */
	public static final String TCP_CONNECT = "TCP-CONNECT";

	/**
	 * The {@code tcp-listen} function provided by the {@code rontolisp} package. Binds a
	 * listening TCP socket on {@code port} (0 picks an ephemeral port, see
	 * {@code rontolisp:tcp-local-port}) and an optional {@code host} (default: all
	 * interfaces) and returns a listener handle for {@code rontolisp:tcp-accept} /
	 * {@code close}.
	 */
	public static final String TCP_LISTEN = "TCP-LISTEN";

	/**
	 * The {@code tcp-accept} function provided by the {@code rontolisp} package. Blocks
	 * until a client connects to the given listener handle and returns a bidirectional
	 * stream handle for the accepted connection (same stream operations as
	 * {@code rontolisp:tcp-connect}).
	 */
	public static final String TCP_ACCEPT = "TCP-ACCEPT";

	/**
	 * The {@code tcp-local-port} function provided by the {@code rontolisp} package.
	 * Returns the local port number bound to a listener or socket handle (useful after
	 * listening on port 0).
	 */
	public static final String TCP_LOCAL_PORT = "TCP-LOCAL-PORT";

	/**
	 * The {@code tcp-local-address} function provided by the {@code rontolisp} package.
	 * Returns the local/bound IP address of a listener or socket handle as a string.
	 * Interpreter and JVM backends only for a real value; the WASM component backend
	 * returns {@code nil}.
	 */
	public static final String TCP_LOCAL_ADDRESS = "TCP-LOCAL-ADDRESS";

	/**
	 * The {@code tcp-peer-address} function provided by the {@code rontolisp} package.
	 * Returns the remote IP address of a connected socket handle as a string. Interpreter
	 * and JVM backends only for a real value; the WASM component backend returns
	 * {@code nil}.
	 */
	public static final String TCP_PEER_ADDRESS = "TCP-PEER-ADDRESS";

	/**
	 * The {@code tcp-peer-port} function provided by the {@code rontolisp} package.
	 * Returns the remote port number of a connected socket handle. Interpreter and JVM
	 * backends only for a real value; the WASM component backend returns {@code nil}.
	 */
	public static final String TCP_PEER_PORT = "TCP-PEER-PORT";

	/**
	 * The {@code tls-connect} function provided by the {@code rontolisp} package. Opens a
	 * blocking TCP connection to {@code host} and {@code port}, performs a TLS handshake
	 * (the server certificate is validated against the JDK default trust store and the
	 * hostname is verified), and returns a bidirectional stream handle usable with
	 * {@code read-line}, {@code write-line}, {@code read-byte}, {@code write-byte} and
	 * {@code close}. Interpreter and JVM backends only; the WASM backend has no TLS host
	 * support.
	 */
	public static final String TLS_CONNECT = "TLS-CONNECT";

	/**
	 * The {@code tls-listen} function provided by the {@code rontolisp} package. Binds a
	 * listening TLS socket serving the certificate from a PKCS12 keystore file:
	 * {@code (tls-listen keystore password port &optional host)}. The listener handle
	 * works with {@code rontolisp:tcp-accept}, {@code rontolisp:tcp-local-port} and
	 * {@code close}; an accepted connection performs its TLS handshake on the first
	 * read/write. Interpreter and JVM backends only; the WASM backend has no TLS host
	 * support.
	 */
	public static final String TLS_LISTEN = "TLS-LISTEN";

	/**
	 * The {@code tls-listen-pem} function provided by the {@code rontolisp} package.
	 * Binds a listening TLS socket serving the certificate chain and unencrypted PKCS#8
	 * private key read from two PEM files:
	 * {@code (tls-listen-pem cert-file key-file port &optional
	 * host)}. Otherwise identical to {@code tls-listen} (the listener handle works with
	 * {@code rontolisp:tcp-accept}, {@code rontolisp:tcp-local-port} and {@code close}).
	 * The interpreter reads the PEM files at run time; the JVM backend embeds the parsed
	 * keystore at compile time (so the {@code cert-file}/{@code key-file} paths must be
	 * string literals when compiling). WASM has no TLS host support.
	 */
	public static final String TLS_LISTEN_PEM = "TLS-LISTEN-PEM";

	/**
	 * Internal helper the {@code tls-listen-pem} compile-time inliner rewrites to: binds
	 * a TLS listener from an in-memory PKCS12 keystore passed as a Base64 string
	 * ({@code (%tls-listen-p12 base64 password port &optional host)}). Not part of the
	 * public API -- programs call {@code tls-listen-pem}.
	 */
	public static final String TLS_LISTEN_P12 = "%TLS-LISTEN-P12";

	/** The {@code cl} package name (standard functions, macros and variables). */
	public static final String CL_PKG = "CL";

	/** The {@code cl-user} package name (default working package, uses {@code cl}). */
	public static final String CL_USER_PKG = "CL-USER";

	/** The {@code rontolisp} package name (does not use {@code cl}). */
	public static final String RONTOLISP_PKG = "RONTOLISP";

	/**
	 * The {@code linalg} package name (numpy-style vector/matrix operations). Like the
	 * JSON functions, the package is implemented once in rontolisp itself
	 * ({@code linalg.lisp}, see {@code LinalgLibrary}) so a single implementation runs on
	 * every backend; the exported function names live in
	 * {@code PackageRegistry#linalgFunctionNames()}.
	 */
	public static final String LINALG_PKG = "LINALG";

	// The linalg members an --simd build intercepts (see .kb/linalg-simd.md). The other
	// exported names exist only as linalg.lisp defuns, so PackageRegistry keeps them as
	// bare strings; these fifteen are dispatched on by name in three interceptors
	// (eval.LinalgSimd, codegen.jvm.JvmLinalgSimdCompiler,
	// codegen.wasm.WasmLinalgSimdCompiler) and so need constants.

	/** {@code linalg:add}: element-wise {@code a + b}; either operand may be a scalar. */
	public static final String LINALG_ADD = "ADD";

	/** {@code linalg:sub}: element-wise {@code a - b}; either operand may be a scalar. */
	public static final String LINALG_SUB = "SUB";

	/**
	 * {@code linalg:mul}: element-wise (Hadamard) {@code a * b}, NOT a matrix product;
	 * either operand may be a scalar.
	 */
	public static final String LINALG_MUL = "MUL";

	/** {@code linalg:div}: element-wise {@code a / b}; either operand may be a scalar. */
	public static final String LINALG_DIV = "DIV";

	/** {@code linalg:sum}: the sum of every element, at any rank. */
	public static final String LINALG_SUM = "SUM";

	/** {@code linalg:amax}: the largest element, at any rank. */
	public static final String LINALG_AMAX = "AMAX";

	/** {@code linalg:amin}: the smallest element, at any rank. */
	public static final String LINALG_AMIN = "AMIN";

	/** {@code linalg:norm}: the Euclidean (L2 / Frobenius) norm. */
	public static final String LINALG_NORM = "NORM";

	/**
	 * {@code linalg:dot}: the numpy dispatch -- vector.vector to a scalar, matrix.vector
	 * and vector.matrix to a vector, matrix.matrix to a matrix.
	 */
	public static final String LINALG_DOT = "DOT";

	/**
	 * {@code linalg:outer}: the outer product of two vectors (inputs flattened first).
	 */
	public static final String LINALG_OUTER = "OUTER";

	/** {@code linalg:transpose}: a matrix transpose; a vector is returned unchanged. */
	public static final String LINALG_TRANSPOSE = "TRANSPOSE";

	/** {@code linalg:trace}: the main-diagonal sum of a square matrix. */
	public static final String LINALG_TRACE = "TRACE";

	/** {@code linalg:argmax}: the index of the largest element of a vector. */
	public static final String LINALG_ARGMAX = "ARGMAX";

	/** {@code linalg:argmin}: the index of the smallest element of a vector. */
	public static final String LINALG_ARGMIN = "ARGMIN";

	/** {@code linalg:reshape}: a fresh array of the given shape, row-major elements. */
	public static final String LINALG_RESHAPE = "RESHAPE";

	/** {@code linalg:exp}: element-wise {@code e^x} (numpy {@code np.exp}). */
	public static final String LINALG_EXP = "EXP";

	/** {@code linalg:log}: element-wise natural log (numpy {@code np.log}). */
	public static final String LINALG_LOG = "LOG";

	/** {@code linalg:tanh}: element-wise hyperbolic tangent (numpy {@code np.tanh}). */
	public static final String LINALG_TANH = "TANH";

	/** {@code linalg:sin}: element-wise sine (numpy {@code np.sin}). */
	public static final String LINALG_SIN = "SIN";

	/** {@code linalg:cos}: element-wise cosine (numpy {@code np.cos}). */
	public static final String LINALG_COS = "COS";

	/** {@code linalg:tan}: element-wise tangent (numpy {@code np.tan}). */
	public static final String LINALG_TAN = "TAN";

	/** {@code linalg:asin}: element-wise arc sine (numpy {@code np.arcsin}). */
	public static final String LINALG_ASIN = "ASIN";

	/** {@code linalg:acos}: element-wise arc cosine (numpy {@code np.arccos}). */
	public static final String LINALG_ACOS = "ACOS";

	/** {@code linalg:atan}: element-wise arc tangent (numpy {@code np.arctan}). */
	public static final String LINALG_ATAN = "ATAN";

	/** {@code linalg:sinh}: element-wise hyperbolic sine (numpy {@code np.sinh}). */
	public static final String LINALG_SINH = "SINH";

	/** {@code linalg:cosh}: element-wise hyperbolic cosine (numpy {@code np.cosh}). */
	public static final String LINALG_COSH = "COSH";

	/** {@code linalg:sqrt}: element-wise square root (numpy {@code np.sqrt}). */
	public static final String LINALG_SQRT = "SQRT";

	/** {@code linalg:abs}: element-wise absolute value (numpy {@code np.abs}). */
	public static final String LINALG_ABS = "ABS";

	/** {@code linalg:negative}: element-wise negation (numpy {@code np.negative}). */
	public static final String LINALG_NEGATIVE = "NEGATIVE";

	/** {@code linalg:sign}: element-wise {@code signum} (numpy {@code np.sign}). */
	public static final String LINALG_SIGN = "SIGN";

	/**
	 * {@code linalg:maximum}: element-wise larger of two operands (numpy
	 * {@code np.maximum}); either operand may be a scalar. Defined by the strict
	 * comparison {@code (if (> x y) x y)}, so the second operand wins whenever the first
	 * is not strictly greater (ties, and unordered {@code NaN} comparisons).
	 */
	public static final String LINALG_MAXIMUM = "MAXIMUM";

	/**
	 * {@code linalg:minimum}: element-wise smaller of two operands (numpy
	 * {@code np.minimum}); either operand may be a scalar. Defined by
	 * {@code (if (< x y) x y)}, the mirror of {@link #LINALG_MAXIMUM}.
	 */
	public static final String LINALG_MINIMUM = "MINIMUM";

	/**
	 * {@code linalg:clip}: element-wise {@code min(max(x, lo), hi)} (numpy
	 * {@code np.clip} with scalar bounds), defined as the composition
	 * {@code (linalg:minimum (linalg:maximum a lo) hi)}.
	 */
	public static final String LINALG_CLIP = "CLIP";

	/**
	 * {@code linalg:relu}: element-wise {@code max(x, 0.0)}, defined as
	 * {@code (linalg:maximum a 0.0)}.
	 */
	public static final String LINALG_RELU = "RELU";

	/**
	 * {@code linalg::%la-im2col} (INTERNAL, note the double colon): unfolds a rank-4 NCHW
	 * array into the {@code (N*out-h*out-w, C*fh*fw)} window matrix behind the CNN
	 * examples (Deep Learning from Scratch {@code common/util.py}). Pure index
	 * arithmetic, intercepted under {@code --simd} because it dominates the accelerated
	 * convolution runs.
	 */
	public static final String LINALG_IM2COL = "%LA-IM2COL";

	/**
	 * {@code linalg::%la-col2im} (INTERNAL): the {@code %la-im2col} adjoint --
	 * scatter-adds the window matrix back into a fresh zero rank-4 NCHW array
	 * (overlapping windows accumulate; the convolution backward pass).
	 */
	public static final String LINALG_COL2IM = "%LA-COL2IM";

	/**
	 * The {@code vec} package name: portable packed-{@code f64} vector kernels over the
	 * packed {@code (array double-float)} type. Implemented once in rontolisp itself
	 * ({@code vec.lisp}, see {@code VecLibrary}) as the scalar reference / cross-backend
	 * oracle, spliced/loaded on demand exactly like {@code linalg}: the interpreter, the
	 * JVM compiler and the wasm-GC compiler run those {@code defun}s over the packed
	 * representation. Two backends add an acceleration layer that intercepts the
	 * vectorizable kernels at their call sites: the JVM {@code --simd} flag lowers them
	 * to {@code jdk.incubator.vector} lane loops, and the {@code --no-gc} scalar WASM
	 * backend lowers them to real fixed-width WASM SIMD ({@code v128} / {@code f64x2.*})
	 * over its packed {@code [len][f64...]} linear-memory block. The package names the
	 * portable abstraction; {@code --simd} names the (optional) acceleration mechanism.
	 */
	public static final String VEC_PKG = "VEC";

	/** {@code vec:zeros}: a fresh length-n vector of {@code 0.0}. */
	public static final String VEC_ZEROS = "ZEROS";

	/** {@code vec:ones}: a fresh length-n vector of {@code 1.0}. */
	public static final String VEC_ONES = "ONES";

	/**
	 * {@code vec:arange}: a fresh vector {@code [0.0, 1.0, ..., n-1]} (numpy name, as in
	 * {@code linalg}).
	 */
	public static final String VEC_ARANGE = "ARANGE";

	/**
	 * {@code vec:from-list}: a fresh vector from a Lisp list of numbers (portable
	 * backends only).
	 */
	public static final String VEC_FROM_LIST = "FROM-LIST";

	/**
	 * {@code vec:to-list}: a Lisp list of a vector's elements (portable backends only).
	 */
	public static final String VEC_TO_LIST = "TO-LIST";

	/**
	 * {@code vec:aref}: read one element of a vector; a setf place via {@code vec:aset}.
	 */
	public static final String VEC_AREF = "AREF";

	/**
	 * {@code vec:aset}: write one element of a vector, returning the stored value (the
	 * {@code setf} writer).
	 */
	public static final String VEC_ASET = "ASET";

	/** {@code vec:length}: the element count of a vector. */
	public static final String VEC_LENGTH = "LENGTH";

	/** {@code vec:add}: element-wise {@code a + b} into a fresh vector. */
	public static final String VEC_ADD = "ADD";

	/** {@code vec:sub}: element-wise {@code a - b} into a fresh vector. */
	public static final String VEC_SUB = "SUB";

	/** {@code vec:mul}: element-wise (Hadamard) {@code a * b} into a fresh vector. */
	public static final String VEC_MUL = "MUL";

	/** {@code vec:scale}: {@code v * s} (scalar broadcast) into a fresh vector. */
	public static final String VEC_SCALE = "SCALE";

	/** {@code vec:sum}: horizontal sum of a vector, a scalar. */
	public static final String VEC_SUM = "SUM";

	/** {@code vec:mean}: arithmetic mean of a vector, a scalar. */
	public static final String VEC_MEAN = "MEAN";

	/** {@code vec:dot}: dot product of two vectors, a scalar. */
	public static final String VEC_DOT = "DOT";

	/** {@code vec:norm}: Euclidean norm {@code sqrt(dot(v, v))}, a scalar. */
	public static final String VEC_NORM = "NORM";

	/**
	 * {@code vec:matvec}: GEMV -- a rank-2 matrix {@code W(d, n)} times a rank-1 vector
	 * {@code x(n)}, yielding a rank-1 vector {@code y(d)} with
	 * {@code y[i] = dot(row_i, x)}.
	 */
	public static final String VEC_MATVEC = "MATVEC";

	/**
	 * {@code vec:add-into}: element-wise {@code a + b} written into {@code out}, which is
	 * returned. The destination-passing sibling of {@link #VEC_ADD}: it allocates
	 * nothing, so a loop over it keeps the bump-allocated linear heap of the WASM
	 * backends flat. {@code out} may alias {@code a} and/or {@code b} (element {@code i}
	 * depends only on element {@code i}).
	 */
	public static final String VEC_ADD_INTO = "ADD-INTO";

	/** {@code vec:sub-into}: element-wise {@code a - b} into {@code out}. */
	public static final String VEC_SUB_INTO = "SUB-INTO";

	/** {@code vec:mul-into}: element-wise {@code a * b} into {@code out}. */
	public static final String VEC_MUL_INTO = "MUL-INTO";

	/** {@code vec:scale-into}: {@code v * s} (scalar broadcast) into {@code out}. */
	public static final String VEC_SCALE_INTO = "SCALE-INTO";

	/**
	 * {@code vec:matvec-into}: GEMV written into {@code out}. Unlike the element-wise
	 * kernels, {@code out[i]} depends on every element of {@code x}, so {@code out} must
	 * not be {@code eq} to {@code x} (nor to {@code w}); the call signals otherwise.
	 */
	public static final String VEC_MATVEC_INTO = "MATVEC-INTO";

	// The element-wise unary ufuncs. Each has a fresh-vector form and a
	// destination-passing -into sibling; out MAY alias the operand (element i depends
	// only
	// on element i, the add-into rule).

	/** {@code vec:exp}: element-wise {@code e^x} into a fresh vector. */
	public static final String VEC_EXP = "EXP";

	/** {@code vec:log}: element-wise natural log into a fresh vector. */
	public static final String VEC_LOG = "LOG";

	/** {@code vec:tanh}: element-wise hyperbolic tangent into a fresh vector. */
	public static final String VEC_TANH = "TANH";

	/** {@code vec:sin}: element-wise sine into a fresh vector. */
	public static final String VEC_SIN = "SIN";

	/** {@code vec:cos}: element-wise cosine into a fresh vector. */
	public static final String VEC_COS = "COS";

	/** {@code vec:tan}: element-wise tangent into a fresh vector. */
	public static final String VEC_TAN = "TAN";

	/** {@code vec:asin}: element-wise arc sine into a fresh vector. */
	public static final String VEC_ASIN = "ASIN";

	/** {@code vec:acos}: element-wise arc cosine into a fresh vector. */
	public static final String VEC_ACOS = "ACOS";

	/** {@code vec:atan}: element-wise arc tangent into a fresh vector. */
	public static final String VEC_ATAN = "ATAN";

	/** {@code vec:sinh}: element-wise hyperbolic sine into a fresh vector. */
	public static final String VEC_SINH = "SINH";

	/** {@code vec:cosh}: element-wise hyperbolic cosine into a fresh vector. */
	public static final String VEC_COSH = "COSH";

	/** {@code vec:sqrt}: element-wise square root into a fresh vector. */
	public static final String VEC_SQRT = "SQRT";

	/** {@code vec:abs}: element-wise absolute value into a fresh vector. */
	public static final String VEC_ABS = "ABS";

	/** {@code vec:square}: element-wise {@code x * x} into a fresh vector. */
	public static final String VEC_SQUARE = "SQUARE";

	/** {@code vec:negative}: element-wise negation into a fresh vector. */
	public static final String VEC_NEGATIVE = "NEGATIVE";

	/** {@code vec:sign}: element-wise {@code signum} into a fresh vector. */
	public static final String VEC_SIGN = "SIGN";

	/** {@code vec:reciprocal}: element-wise {@code 1 / x} into a fresh vector. */
	public static final String VEC_RECIPROCAL = "RECIPROCAL";

	// The comparison-select ufuncs. All are defined by the strict comparison the linalg:
	// siblings state ((if (> x y) x y) and its mirrors), so the second operand / the
	// bound
	// wins whenever the comparison is false -- including unordered NaN comparisons.

	/** {@code vec:maximum}: element-wise larger of two vectors into a fresh vector. */
	public static final String VEC_MAXIMUM = "MAXIMUM";

	/** {@code vec:minimum}: element-wise smaller of two vectors into a fresh vector. */
	public static final String VEC_MINIMUM = "MINIMUM";

	/** {@code vec:relu}: element-wise {@code max(x, 0.0)} into a fresh vector. */
	public static final String VEC_RELU = "RELU";

	/**
	 * {@code vec:clip}: element-wise {@code min(max(x, lo), hi)} (scalar bounds) into a
	 * fresh vector.
	 */
	public static final String VEC_CLIP = "CLIP";

	/** {@code vec:exp-into}: element-wise {@code e^x} into {@code out}. */
	public static final String VEC_EXP_INTO = "EXP-INTO";

	/** {@code vec:log-into}: element-wise natural log into {@code out}. */
	public static final String VEC_LOG_INTO = "LOG-INTO";

	/** {@code vec:tanh-into}: element-wise hyperbolic tangent into {@code out}. */
	public static final String VEC_TANH_INTO = "TANH-INTO";

	/** {@code vec:sin-into}: element-wise sine into {@code out}. */
	public static final String VEC_SIN_INTO = "SIN-INTO";

	/** {@code vec:cos-into}: element-wise cosine into {@code out}. */
	public static final String VEC_COS_INTO = "COS-INTO";

	/** {@code vec:tan-into}: element-wise tangent into {@code out}. */
	public static final String VEC_TAN_INTO = "TAN-INTO";

	/** {@code vec:asin-into}: element-wise arc sine into {@code out}. */
	public static final String VEC_ASIN_INTO = "ASIN-INTO";

	/** {@code vec:acos-into}: element-wise arc cosine into {@code out}. */
	public static final String VEC_ACOS_INTO = "ACOS-INTO";

	/** {@code vec:atan-into}: element-wise arc tangent into {@code out}. */
	public static final String VEC_ATAN_INTO = "ATAN-INTO";

	/** {@code vec:sinh-into}: element-wise hyperbolic sine into {@code out}. */
	public static final String VEC_SINH_INTO = "SINH-INTO";

	/** {@code vec:cosh-into}: element-wise hyperbolic cosine into {@code out}. */
	public static final String VEC_COSH_INTO = "COSH-INTO";

	/** {@code vec:sqrt-into}: element-wise square root into {@code out}. */
	public static final String VEC_SQRT_INTO = "SQRT-INTO";

	/** {@code vec:abs-into}: element-wise absolute value into {@code out}. */
	public static final String VEC_ABS_INTO = "ABS-INTO";

	/** {@code vec:square-into}: element-wise {@code x * x} into {@code out}. */
	public static final String VEC_SQUARE_INTO = "SQUARE-INTO";

	/** {@code vec:negative-into}: element-wise negation into {@code out}. */
	public static final String VEC_NEGATIVE_INTO = "NEGATIVE-INTO";

	/** {@code vec:sign-into}: element-wise {@code signum} into {@code out}. */
	public static final String VEC_SIGN_INTO = "SIGN-INTO";

	/** {@code vec:reciprocal-into}: element-wise {@code 1 / x} into {@code out}. */
	public static final String VEC_RECIPROCAL_INTO = "RECIPROCAL-INTO";

	/** {@code vec:maximum-into}: element-wise larger of two vectors into {@code out}. */
	public static final String VEC_MAXIMUM_INTO = "MAXIMUM-INTO";

	/** {@code vec:minimum-into}: element-wise smaller of two vectors into {@code out}. */
	public static final String VEC_MINIMUM_INTO = "MINIMUM-INTO";

	/** {@code vec:relu-into}: element-wise {@code max(x, 0.0)} into {@code out}. */
	public static final String VEC_RELU_INTO = "RELU-INTO";

	/**
	 * {@code vec:clip-into}: element-wise {@code min(max(x, lo), hi)} into {@code out}.
	 */
	public static final String VEC_CLIP_INTO = "CLIP-INTO";

	/**
	 * {@code vec:aref} fully qualified: a {@code setf} place (writer {@code vec:aset}).
	 */
	public static final String VEC_QUALIFIED_AREF = VEC_PKG + ":" + VEC_AREF;

	/**
	 * {@code vec:aset} fully qualified: the {@code setf} writer for {@code vec:aref}.
	 */
	public static final String VEC_QUALIFIED_ASET = VEC_PKG + ":" + VEC_ASET;

	/**
	 * The {@code usocket} package name (a usocket-compatible shim over the
	 * {@code rontolisp:tcp-*} built-ins). Like {@code linalg}/{@code vec} it is
	 * implemented once in rontolisp itself ({@code usocket.lisp}, see
	 * {@code UsocketLibrary}) so a single implementation runs on every backend; the
	 * exported names live in {@code PackageRegistry#usocketFunctionNames()}. The four
	 * {@code with-*} convenience macros are built-in {@code LispMacroExpander} expansions
	 * (the {@code rontolisp:with-arena} pattern), not library defuns.
	 */
	public static final String USOCKET_PKG = "USOCKET";

	/**
	 * {@code usocket:socket-connect}: the shim's TCP client entry point. Named as a
	 * constant because {@code UsocketLibrary}'s dedup guard checks whether a program
	 * already defines it (the ASDF built-in-system hook may have spliced the library
	 * before the generic {@code process()} pass runs).
	 */
	public static final String USOCKET_SOCKET_CONNECT = "SOCKET-CONNECT";

	/**
	 * The {@code usocket::%usock-guard} internal form: wraps a socket-operation body so
	 * an underlying failure is re-signaled as a typed {@code usocket:socket-error}. A
	 * {@code handler-case} wrap on the interpreter and the JVM; a plain pass-through on
	 * WASM, where errors are uncatchable traps (the shim source is parsed once and shared
	 * by every backend, so the branch lives in the expansion, not in reader features).
	 */
	public static final String USOCKET_GUARD = "%USOCK-GUARD";

	/** The package-qualified spelling of {@code usocket::%usock-guard}. */
	public static final String USOCKET_GUARD_QUALIFIED = USOCKET_PKG + "::" + USOCKET_GUARD;

	/**
	 * The {@code usocket:with-client-socket} macro:
	 * {@code (with-client-socket (socket stream host port &rest connect-args) body...)}
	 * connects, binds {@code socket} and its stream, runs the body and closes the socket
	 * on every exit on the interpreter/JVM ({@code unwind-protect}), on normal exit only
	 * on WASM.
	 */
	public static final String USOCKET_WITH_CLIENT_SOCKET = "WITH-CLIENT-SOCKET";

	/** The canonical package-qualified spelling of {@code usocket:with-client-socket}. */
	public static final String USOCKET_WITH_CLIENT_SOCKET_QUALIFIED = USOCKET_PKG + ":" + USOCKET_WITH_CLIENT_SOCKET;

	/**
	 * The {@code usocket:with-connected-socket} macro:
	 * {@code (with-connected-socket (var socket-form) body...)} binds {@code var}, runs
	 * the body and closes the socket on normal exit.
	 */
	public static final String USOCKET_WITH_CONNECTED_SOCKET = "WITH-CONNECTED-SOCKET";

	/**
	 * The canonical package-qualified spelling of {@code usocket:with-connected-socket}.
	 */
	public static final String USOCKET_WITH_CONNECTED_SOCKET_QUALIFIED = USOCKET_PKG + ":"
			+ USOCKET_WITH_CONNECTED_SOCKET;

	/**
	 * The {@code usocket:with-server-socket} macro: same expansion as
	 * {@code usocket:with-connected-socket} (usocket aliases the two).
	 */
	public static final String USOCKET_WITH_SERVER_SOCKET = "WITH-SERVER-SOCKET";

	/** The canonical package-qualified spelling of {@code usocket:with-server-socket}. */
	public static final String USOCKET_WITH_SERVER_SOCKET_QUALIFIED = USOCKET_PKG + ":" + USOCKET_WITH_SERVER_SOCKET;

	/**
	 * The {@code usocket:with-socket-listener} macro:
	 * {@code (with-socket-listener (var host port &rest listen-args) body...)} listens,
	 * binds {@code var}, runs the body and closes the listener on normal exit.
	 */
	public static final String USOCKET_WITH_SOCKET_LISTENER = "WITH-SOCKET-LISTENER";

	/**
	 * The canonical package-qualified spelling of {@code usocket:with-socket-listener}.
	 */
	public static final String USOCKET_WITH_SOCKET_LISTENER_QUALIFIED = USOCKET_PKG + ":"
			+ USOCKET_WITH_SOCKET_LISTENER;

	/**
	 * The {@code wasm-export} directive provided by the {@code rontolisp} package. Used
	 * as {@code (rontolisp:wasm-export 'name :params '(...) :returns ...)} to mark a
	 * function for direct WASM export. A no-op on the interpreter and the JVM backend.
	 */
	public static final String WASM_EXPORT = "WASM-EXPORT";

	/**
	 * The {@code wit-export} directive provided by the {@code rontolisp} package. Used as
	 * {@code (rontolisp:wit-export "world.wit" :world name)} to declare that the program
	 * implements a WIT world: the compiler checks every {@code defun} against the world's
	 * exports and lowers them into the equivalent {@link #WASM_EXPORT} directives. A
	 * contract check (but no export) on the interpreter and the JVM backend.
	 */
	public static final String WIT_EXPORT = "WIT-EXPORT";

	/** The fully qualified {@code rontolisp:wit-export} directive name. */
	public static final String WIT_EXPORT_QUALIFIED = RONTOLISP_PKG + ":" + WIT_EXPORT;

	/**
	 * The {@code http-handler} directive provided by the {@code rontolisp} package. Used
	 * as {@code (rontolisp:http-handler 'name [port])} to serve HTTP requests with a
	 * handler function that receives a request property list ({@code :method} /
	 * {@code :path} / {@code :headers} / {@code :body}) and returns a response property
	 * list ({@code :status} / {@code :headers} / {@code :body}). On the interpreter and
	 * the JVM backend it runs a blocking embedded HTTP server on the given port (default
	 * 8080); when compiled to a WASI component ({@code --component}) it exports
	 * {@code wasi:http/incoming-handler} so the module runs under {@code wasmtime serve}
	 * (the port argument is ignored, the host owns the socket).
	 */
	public static final String HTTP_HANDLER = "HTTP-HANDLER";

	/**
	 * The {@code with-arena} macro provided by the {@code rontolisp} package. Used as
	 * {@code (rontolisp:with-arena () body...)} to name a reclamation boundary: on the
	 * interpreter, the JVM backend and wasm-GC it expands to a plain {@code progn} (a
	 * real GC already reclaims), while the {@code --no-gc} backend lowers it to a bump
	 * heap-pointer mark / body / reset with the body's value (a string or packed float
	 * array) copied down to the mark. Nothing allocated inside the body may be reachable
	 * after it, except the body's own value.
	 */
	public static final String WITH_ARENA = "WITH-ARENA";

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:with-arena}, as it
	 * appears in call position after {@code PackageResolver} resolution.
	 */
	public static final String WITH_ARENA_QUALIFIED = RONTOLISP_PKG + ":" + WITH_ARENA;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:async}, as it appears
	 * in call position after {@code PackageResolver} resolution.
	 */
	public static final String ASYNC_QUALIFIED = RONTOLISP_PKG + ":" + ASYNC;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:async-defun}, as it
	 * appears in call position after {@code PackageResolver} resolution.
	 */
	public static final String ASYNC_DEFUN_QUALIFIED = RONTOLISP_PKG + ":" + ASYNC_DEFUN;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:async-lambda}, as it
	 * appears in call position after {@code PackageResolver} resolution.
	 */
	public static final String ASYNC_LAMBDA_QUALIFIED = RONTOLISP_PKG + ":" + ASYNC_LAMBDA;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:await}, as it appears
	 * in call position after {@code PackageResolver} resolution.
	 */
	public static final String AWAIT_QUALIFIED = RONTOLISP_PKG + ":" + AWAIT;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:then}.
	 */
	public static final String THEN_QUALIFIED = RONTOLISP_PKG + ":" + THEN;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:then*}.
	 */
	public static final String THEN_STAR_QUALIFIED = RONTOLISP_PKG + ":" + THEN_STAR;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:catch}.
	 */
	public static final String CATCH_QUALIFIED = RONTOLISP_PKG + ":" + CATCH;

	/**
	 * The canonical package-qualified spelling of {@code rontolisp:finally}.
	 */
	public static final String FINALLY_QUALIFIED = RONTOLISP_PKG + ":" + FINALLY;

	/**
	 * The canonical internal-qualified spelling of {@code rontolisp::%async-run}, the
	 * name the {@code async-defun}/{@code async-lambda} lowering synthesizes in call
	 * position.
	 */
	public static final String ASYNC_RUN_QUALIFIED = RONTOLISP_PKG + "::" + ASYNC_RUN;

	/**
	 * The canonical internal-qualified spelling of {@code rontolisp::%subtask-future},
	 * the name {@code WitImportDirective} synthesizes in call position for an
	 * {@code async func} member's binding under {@code --component}.
	 */
	public static final String SUBTASK_FUTURE_INTERNAL_QUALIFIED = RONTOLISP_PKG + "::" + SUBTASK_FUTURE_INTERNAL;

	/**
	 * The {@code wasm-import} directive provided by the {@code rontolisp} package. Used
	 * as {@code (rontolisp:wasm-import 'name :from "module" :as "field" :params '(...)
	 * :returns ...)} to declare a host function imported into the compiled WASM module
	 * and callable from Lisp like a top-level defun. On the interpreter and the JVM
	 * backend it defines a stub that signals an error when called.
	 */
	public static final String WASM_IMPORT = "WASM-IMPORT";

	/**
	 * The {@code wit-import} directive provided by the {@code rontolisp} package. Used as
	 * {@code (rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package
	 * kv)} to bind a WIT interface's functions as ordinary Lisp functions. The mirror of
	 * {@link #WIT_EXPORT}: the WIT file is the single description of the boundary, and
	 * each backend binds it to what it can reach -- Preview 1 WASM lowers it to
	 * {@link #WASM_IMPORT} directives, while the interpreter and the JVM backend dispatch
	 * through the provider bound for the interface ({@link #WIT_PROVIDE}, or a built-in
	 * one).
	 */
	public static final String WIT_IMPORT = "WIT-IMPORT";

	/** The fully qualified {@code rontolisp:wit-import} directive name. */
	public static final String WIT_IMPORT_QUALIFIED = RONTOLISP_PKG + ":" + WIT_IMPORT;

	/**
	 * The {@code wit-provide} function provided by the {@code rontolisp} package. Used as
	 * {@code (rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)} to bind the
	 * implementation of a {@link #WIT_IMPORT}ed interface: the provider is an ordinary
	 * Lisp callable taking the bound function's name followed by its arguments. It is
	 * what makes the same WIT-importing source run against a different implementation per
	 * backend. A no-op on the WASM backends, where the host supplies the imports.
	 */
	public static final String WIT_PROVIDE = "WIT-PROVIDE";

	/**
	 * The internal {@code rontolisp::%wit-call} dispatch primitive: the body every
	 * {@link #WIT_IMPORT} binding lowers to on the interpreter and the JVM backend.
	 * Defined in {@code wit.lisp} (see {@code WitLibrary}), not by a backend.
	 */
	public static final String WIT_CALL = "%WIT-CALL";

	/**
	 * The {@code wit-error} condition provided by the {@code rontolisp} package: what the
	 * error arm of a WIT {@code result<T, E>} signals, carrying the mapped {@code E}
	 * payload in its {@code payload} slot (the settled type mapping, {@code .kb/wit.md}).
	 * Defined in {@code wit.lisp} (see {@code WitLibrary}).
	 */
	public static final String WIT_ERROR = "WIT-ERROR";

	/**
	 * The reader of {@link #WIT_ERROR}'s {@code payload} slot: the mapped {@code E} value
	 * of the WIT {@code result} whose error arm signaled.
	 */
	public static final String WIT_ERROR_PAYLOAD = "WIT-ERROR-PAYLOAD";

	/**
	 * The internal form a {@link #WIT_IMPORT} directive lowers to under
	 * {@code --component}: {@code (rontolisp::%component-import "iface-id" "wit text"
	 * ("member" "lisp-name") ...)}. The WIT text travels inside the form so the WASM
	 * compiler reads no files (the browser playground has no filesystem). Consumed by
	 * {@code WasmComponentImportCompiler}; never user-written.
	 */
	public static final String COMPONENT_IMPORT = "%COMPONENT-IMPORT";

	/**
	 * The internal envelope unwrapper a result-returning {@link #WIT_IMPORT} binding's
	 * public wrapper defun calls on the WASM backends: the raw import returns
	 * {@code (:ok . V)} / {@code (:error . E)} and {@code rontolisp::%wit-result} either
	 * yields the ok value or signals {@link #WIT_ERROR} with the error payload. Defined
	 * in {@code wit.lisp}.
	 */
	public static final String WIT_RESULT = "%WIT-RESULT";

	/**
	 * The {@code java} package name (interpreter-only Java interop by reflection). It
	 * does not use {@code cl}; its functions wrap arbitrary host objects as
	 * {@code LispJavaObject} and so run on the JVM interpreter only -- the JVM-class and
	 * WASM backends cannot lower a {@code LispJavaObject}.
	 */
	public static final String JAVA_PKG = "JAVA";

	/**
	 * {@code java:new} -- constructs a host object: {@code (java:new "fqcn" args...)}.
	 */
	public static final String JAVA_NEW = "NEW";

	/** {@code java:call} -- invokes an instance method on a host object. */
	public static final String JAVA_CALL = "CALL";

	/**
	 * {@code java:static} -- invokes a static method:
	 * {@code (java:static "fqcn" "m" ...)}.
	 */
	public static final String JAVA_STATIC = "STATIC";

	/** {@code java:field} -- reads a static or instance field (e.g. a constant). */
	public static final String JAVA_FIELD = "FIELD";

	/**
	 * {@code java:proxy} -- makes a host interface instance from a rontolisp callable.
	 */
	public static final String JAVA_PROXY = "PROXY";

	/**
	 * The {@code asdf} package name (a limited, API-compatible subset of ASDF: system
	 * definitions parsed from {@code .asd} files as plain data -- see
	 * {@code eval.AsdfSystems}). Real ASDF is not ported; only {@code defsystem} and
	 * {@code load-system} exist.
	 */
	public static final String ASDF_PKG = "ASDF";

	/**
	 * {@code asdf:defsystem} -- defines a system (name, {@code :depends-on},
	 * {@code :serial}, {@code :components}) for a later {@code asdf:load-system}.
	 * Consumed at compile time by the {@code LoadInliner} pass; a special form (the
	 * options are data, not evaluated) on the interpreter.
	 */
	public static final String DEFSYSTEM = "DEFSYSTEM";

	/**
	 * {@code asdf:load-system} -- loads a system by name: dependency systems first, then
	 * the component files in {@code :depends-on}/{@code :serial} order. The system comes
	 * from a prior {@code asdf:defsystem} or from {@code NAME.asd} found on the system
	 * search path. Loading the same system twice is a no-op. Spliced at compile time by
	 * the {@code LoadInliner} pass; a runtime function on the interpreter.
	 */
	public static final String LOAD_SYSTEM = "LOAD-SYSTEM";

	/**
	 * {@code %read-eval} -- the internal marker the tolerant reader wraps a {@code #.}
	 * read-time-eval datum in ({@code #.datum} lexes to {@code (%read-eval datum)}), so
	 * {@code .asd} consumers ({@code eval.AsdfSystems}) can resolve the datum against the
	 * file's top-level {@code defparameter} bindings (the cl-postgres
	 * {@code (:file #.*string-file*)} idiom). Never appears in evaluated/compiled ASTs:
	 * only the {@code .asd} reading path tolerates {@code #.} at all.
	 */
	public static final String READ_EVAL = "%READ-EVAL";

	/**
	 * The {@code #.} marker variant the backquote reader emits for a marker kept whole
	 * inside a template's construction code: the load-time substitution wraps the value
	 * in {@code quote} (the value is template DATA, not code), where the plain
	 * {@link #READ_EVAL} marker substitutes the raw value. Unresolved occurrences
	 * evaluate as the same 1-arg identity.
	 */
	public static final String READ_EVAL_TEMPLATE = "%READ-EVAL-TEMPLATE";

	/** The canonical qualified spelling of {@code asdf:defsystem}. */
	public static final String ASDF_DEFSYSTEM = ASDF_PKG + ":" + DEFSYSTEM;

	/** The canonical qualified spelling of {@code asdf:load-system}. */
	public static final String ASDF_LOAD_SYSTEM = ASDF_PKG + ":" + LOAD_SYSTEM;

	/**
	 * {@code asdf:find-system} -- looks up a system by name; returns the system name (as
	 * a string) when found, or {@code nil} when the optional {@code error-p} arg is nil
	 * and the system is not registered. The uax-15 pattern:
	 * {@code (asdf:system-source-directory (asdf:find-system 'uax-15 nil))}.
	 */
	public static final String FIND_SYSTEM = "FIND-SYSTEM";

	/**
	 * {@code asdf:system-source-directory} -- returns the system's source directory (with
	 * a trailing {@code /}) as a string, for building runtime paths to bundled data files
	 * that live next to the {@code .asd}.
	 */
	public static final String SYSTEM_SOURCE_DIRECTORY = "SYSTEM-SOURCE-DIRECTORY";

	/** The canonical qualified spelling of {@code asdf:find-system}. */
	public static final String ASDF_FIND_SYSTEM = ASDF_PKG + ":" + FIND_SYSTEM;

	/** The canonical qualified spelling of {@code asdf:system-source-directory}. */
	public static final String ASDF_SYSTEM_SOURCE_DIRECTORY = ASDF_PKG + ":" + SYSTEM_SOURCE_DIRECTORY;

	/** The canonical qualified spelling of {@code uiop:merge-pathnames*}. */
	public static final String UIOP_MERGE_PATHNAMES_STAR = "UIOP:MERGE-PATHNAMES*";

	/**
	 * {@code make-pathname} (CL) -- builds a pathname value. Rontolisp uses strings for
	 * paths, so this returns a namestring composed of {@code :directory} (a list starting
	 * with {@code :relative} or {@code :absolute}) plus optional {@code :name} and
	 * {@code :type} components.
	 */
	public static final String MAKE_PATHNAME = "MAKE-PATHNAME";

	/**
	 * The {@code ql} package name (a limited, API-compatible subset of Quicklisp). Its
	 * canonical spelling is {@code ql}; {@code quicklisp} is a nickname. Downloads a
	 * system (and its dependencies) from the real Quicklisp distribution into a local
	 * cache and then defers to the {@code asdf} subset to load it -- see
	 * {@code eval.QuicklispClient}.
	 */
	public static final String QL_PKG = "QL";

	/**
	 * {@code ql:quickload} -- downloads a system by name (with its dependencies) from the
	 * Quicklisp distribution into the local cache, then loads it like
	 * {@code asdf:load-system}. The download happens at interpret time or compile time
	 * (Java-side); a compiled program has the sources spliced in, so the WASM/JVM runtime
	 * never fetches. Spliced at compile time by the {@code LoadInliner} pass; a runtime
	 * function on the interpreter.
	 */
	public static final String QUICKLOAD = "QUICKLOAD";

	/** The canonical qualified spelling of {@code ql:quickload}. */
	public static final String QL_QUICKLOAD = QL_PKG + ":" + QUICKLOAD;

	/**
	 * The {@code tagbody} special form: body forms interleaved with go-tag labels
	 * (symbols/integers); {@code go} transfers control to a label. Interpreter-only for
	 * now (the compile path rejects it).
	 */
	public static final String TAGBODY = "TAGBODY";

	/** The {@code go} special form: transfers control to a {@code tagbody} label. */
	public static final String GO = "GO";

	/** The {@code prog} macro: {@code (block nil (let bindings (tagbody body...)))}. */
	public static final String PROG = "PROG";

	/** The {@code prog*} macro: like {@link #PROG} with sequential bindings. */
	public static final String PROG_STAR = "PROG*";

	/**
	 * The {@code shiftf} macro: shifts place values left, stores the last value, and
	 * returns the first place's old value.
	 */
	public static final String SHIFTF = "SHIFTF";

	/**
	 * The {@code load-time-value} special operator: its form is evaluated once per
	 * occurrence, not once per use. The compile path hoists the value into a synthesized
	 * {@link #LOAD_TIME_VALUE_SLOT_PREFIX} global filled on first use
	 * ({@code LispMacroExpander.hoistLoadTimeValues}); the interpreter memoizes the
	 * occurrence by identity. A value form cheap enough not to be worth a slot (an atom,
	 * or a {@code quote}/{@code function}/{@code find-package} wrapper) keeps the plain
	 * re-evaluating lowering.
	 */
	public static final String LOAD_TIME_VALUE = "LOAD-TIME-VALUE";

	/**
	 * Prefix of the synthesized global holding one hoisted {@code load-time-value}
	 * result, numbered from 1 in walk order (so the emitted program stays deterministic).
	 * The slot holds a one-element list, {@code nil} meaning "not computed yet".
	 */
	public static final String LOAD_TIME_VALUE_SLOT_PREFIX = "%LOAD-TIME-VALUE-";

	/** The {@code mask-field} built-in function (ldb shifted back into position). */
	public static final String MASK_FIELD = "MASK-FIELD";

	/** The {@code scale-float} built-in function ({@code f * 2^n}). */
	/**
	 * The {@code decode-float} prelude function: the three values of a float's binary
	 * decomposition -- significand in [1/2, 1), exponent, and sign (1.0 or -1.0).
	 */
	public static final String DECODE_FLOAT = "DECODE-FLOAT";

	public static final String SCALE_FLOAT = "SCALE-FLOAT";

	/**
	 * The {@code typep} macro -- lite: the type specifier must be a literal (quoted)
	 * type; it lowers through the shared static type-test builder.
	 */
	public static final String TYPEP = "TYPEP";

	/**
	 * The {@code subtypep} built-in function -- registered on the evaluator (it needs the
	 * CLOS class registry) over the built-in type lattice; a single primary value.
	 */
	public static final String SUBTYPEP = "SUBTYPEP";

	/**
	 * The shared runtime-{@code subtypep} dispatch defun the compilers inject once per
	 * program when a {@code subtypep} call carries a non-literal type specifier.
	 */
	public static final String SUBTYPEP_RUNTIME = "%SUBTYPEP-RUNTIME";

	/**
	 * The ancestor-set table backing {@link #SUBTYPEP_RUNTIME}: an alist-like constant,
	 * each entry {@code ((sub-name...) ancestor-name...)} grouping the type universe's
	 * names by ancestor set. Emitted as a top-level {@code defvar} of pure quoted data so
	 * the dispatch defun stays small regardless of how many classes a program registers.
	 */
	public static final String SUBTYPEP_ANCESTOR_TABLE = "%SUBTYPEP-ANCESTOR-TABLE";

	/**
	 * The shared runtime-{@code typep} dispatch defun the compilers inject once per
	 * program when a {@code typep} call carries a non-literal type specifier. Inlining
	 * that dispatch at every call site (the interpreter's model) grows with the number of
	 * registered classes and overflowed the JVM's 16-bit branch offsets at cl-postgres
	 * scale; the defun keeps each call site one fixed-size call.
	 */
	public static final String TYPEP_RUNTIME = "%TYPEP-RUNTIME";

	/**
	 * The shared runtime-{@code error} dispatch defun the compilers inject once per
	 * program when an {@code error} call carries a computed condition-type symbol with
	 * initargs (cl-postgres' {@code (error (get-error-type code) :code ...)}). It
	 * dispatches over the registered CONDITION classes, each arm a small call into a
	 * per-class construction helper; inlining every class's typed expansion at the call
	 * site produced a 90 KB method body at cl-postgres scale -- past even the JVM's 64 KB
	 * hard method limit.
	 */
	public static final String ERROR_RUNTIME = "%ERROR-RUNTIME";

	/**
	 * The instance-tag acceptance table backing {@link #TYPEP_RUNTIME}: an alist-like
	 * constant, each entry {@code ((type-name...) tag...)} mapping every registered
	 * class/struct type name (qualified and plain spellings) to the instance tags it
	 * accepts. Emitted as a top-level {@code defvar} holding pure quoted data so the
	 * dispatch defun stays small regardless of how many classes a program registers.
	 */
	public static final String TYPEP_TAG_TABLE = "%TYPEP-TAG-TABLE";

	/** The {@code char-name} built-in function. */
	public static final String CHAR_NAME = "CHAR-NAME";

	/** The {@code fdefinition} built-in function (alias of {@code symbol-function}). */
	public static final String FDEFINITION = "FDEFINITION";

	/**
	 * The {@code file-position} built-in function -- lite: always {@code nil} (streams do
	 * not support repositioning), so callers take their non-seeking fallback.
	 */
	public static final String FILE_POSITION = "FILE-POSITION";

	/** The {@code file-length} built-in function -- lite: always {@code nil}. */
	public static final String FILE_LENGTH = "FILE-LENGTH";

	/**
	 * The {@code make-broadcast-stream} built-in function -- lite: with no component
	 * streams it returns a discarding sink (a fresh string output stream nobody reads).
	 */
	public static final String MAKE_BROADCAST_STREAM = "MAKE-BROADCAST-STREAM";

	/** The {@code pathnamep} built-in function -- no pathname type exists: always nil. */
	public static final String PATHNAMEP = "PATHNAMEP";

	/** The {@code input-stream-p} built-in function -- lite: any stream handle. */
	public static final String INPUT_STREAM_P = "INPUT-STREAM-P";

	/**
	 * The {@code open-stream-p} built-in function: real against the stream table (a
	 * closed handle's entry is removed), unlike the lite direction predicates.
	 */
	public static final String OPEN_STREAM_P = "OPEN-STREAM-P";

	/** The {@code output-stream-p} built-in function -- lite: any stream handle. */
	public static final String OUTPUT_STREAM_P = "OUTPUT-STREAM-P";

	/** The {@code stream-element-type} built-in function -- always {@code character}. */
	public static final String STREAM_ELEMENT_TYPE = "STREAM-ELEMENT-TYPE";

	/**
	 * The {@code slot-boundp} built-in -- lite: true when the instance's class has the
	 * slot (slots are always initialized; see {@link #SLOT_MAKUNBOUND}).
	 */
	public static final String SLOT_BOUNDP = "SLOT-BOUNDP";

	/** The {@code slot-makunbound} built-in -- lite: stores {@code nil} into the slot. */
	public static final String SLOT_MAKUNBOUND = "SLOT-MAKUNBOUND";

	/**
	 * The {@code class-of} built-in function -- lite: the class-tag symbol of a CLOS
	 * instance, or the type name symbol of a built-in value.
	 */
	public static final String CLASS_OF = "CLASS-OF";

	/**
	 * The internal {@code %class-slot-defs} introspection helper: takes a class
	 * designator (the {@code %class-<name>} tag symbol {@code class-of} returns, or the
	 * class name) and returns a list of {@code (slot-name declared-type)} pairs for the
	 * class's full slot list. The closer-mop shim's {@code class-slots} is built on it,
	 * so slot-walking serializers (jzon) see real fields. Not a public function.
	 */
	public static final String CLASS_SLOT_DEFS_INTERNAL = "%CLASS-SLOT-DEFS";

	/**
	 * The internal instance constructor: {@code (%obj-new '<tag> v1 ... vn)} builds an
	 * instance of the type carrying that tag, with the values in layout order. The tag
	 * must be a quoted literal on the compile path (the same rule
	 * {@code make-instance}/{@code slot-value} follow), because the layout is resolved at
	 * expansion time.
	 */
	public static final String OBJ_NEW = "%OBJ-NEW";

	/**
	 * The internal slot reader: {@code (%obj-ref obj <k>)} reads slot {@code k} (0-based,
	 * a literal integer) of an instance.
	 */
	public static final String OBJ_REF = "%OBJ-REF";

	/**
	 * The internal slot writer: {@code (%obj-set obj <k> v)} writes slot {@code k}
	 * (0-based, a literal integer) of an instance and returns the value written.
	 */
	public static final String OBJ_SET = "%OBJ-SET";

	/**
	 * The internal instance-of test: {@code (%obj-is obj '<tag1> '<tag2> ...)} is
	 * {@code t} when the value is an instance whose type tag is one of the listed ones.
	 * Replaces the tagged-list era's {@code (if (consp x) (equal (car x) 'tag) nil)} in
	 * struct predicates, class specializers and condition-type tests.
	 */
	public static final String OBJ_IS = "%OBJ-IS";

	/**
	 * The internal instance tag reader: {@code (%obj-tag obj)} yields the
	 * {@code %struct-<name>} / {@code %class-<name>} symbol of an instance, or nil for a
	 * non-instance. {@code class-of} is built on it.
	 */
	public static final String OBJ_TAG = "%OBJ-TAG";

	/**
	 * The internal instance predicate: {@code (%obj-p x)} is {@code t} for any instance
	 * of a struct or class type. Used where a test must accept every instance regardless
	 * of type (the Gray-stream dispatch, {@code standard-object}).
	 */
	public static final String OBJ_P = "%OBJ-P";

	/**
	 * The internal instance slot reader: {@code (%obj-slots x)} is a FRESH list of the
	 * instance's slot values in layout order, nil for anything else. It exists so Lisp
	 * code can walk an instance's contents without a registry lookup per slot -- the
	 * prelude's {@code equalp} compares two instances by handing their slot lists back to
	 * itself, which is exact AND costs no per-call-site slot dispatch.
	 */
	public static final String OBJ_SLOTS = "%OBJ-SLOTS";

	/** The {@code simple-condition-format-control} condition reader. */
	public static final String SIMPLE_CONDITION_FORMAT_CONTROL = "SIMPLE-CONDITION-FORMAT-CONTROL";

	/** The {@code simple-condition-format-arguments} condition reader. */
	public static final String SIMPLE_CONDITION_FORMAT_ARGUMENTS = "SIMPLE-CONDITION-FORMAT-ARGUMENTS";

	/** The {@code array-dimension-limit} constant variable. */
	public static final String ARRAY_DIMENSION_LIMIT = "ARRAY-DIMENSION-LIMIT";

	/**
	 * The {@code internal-time-units-per-second} constant variable: 1000 on every
	 * backend, since {@code get-internal-real-time}/{@code get-internal-run-time} count
	 * milliseconds.
	 */
	public static final String INTERNAL_TIME_UNITS_PER_SECOND = "INTERNAL-TIME-UNITS-PER-SECOND";

	/** The {@code char-code-limit} constant variable. */
	public static final String CHAR_CODE_LIMIT = "CHAR-CODE-LIMIT";

	/**
	 * The {@code lambda-list-keywords} constant variable (substituted at read time as a
	 * quoted list of the supported {@code &}-symbols).
	 */
	public static final String LAMBDA_LIST_KEYWORDS = "LAMBDA-LIST-KEYWORDS";

	/** The {@code array-total-size-limit} constant variable. */
	public static final String ARRAY_TOTAL_SIZE_LIMIT = "ARRAY-TOTAL-SIZE-LIMIT";

	/** The {@code *print-circle*} variable (accepted and ignored by the printer). */
	public static final String PRINT_CIRCLE_VAR = "*PRINT-CIRCLE*";

	/**
	 * {@code %ieee754-double-bits} -- the IEEE 754 bits of a double as an unsigned 64-bit
	 * integer. The float-features shim library is built over these four.
	 */
	public static final String IEEE754_DOUBLE_BITS = "%IEEE754-DOUBLE-BITS";

	/** {@code %ieee754-double-from-bits} -- the double of unsigned 64-bit IEEE bits. */
	public static final String IEEE754_DOUBLE_FROM_BITS = "%IEEE754-DOUBLE-FROM-BITS";

	/**
	 * {@code %ieee754-single-bits} -- the IEEE 754 single-precision bits (unsigned
	 * 32-bit) of a float rounded to single precision.
	 */
	public static final String IEEE754_SINGLE_BITS = "%IEEE754-SINGLE-BITS";

	/** {@code %ieee754-single-from-bits} -- the float of unsigned 32-bit IEEE bits. */
	public static final String IEEE754_SINGLE_FROM_BITS = "%IEEE754-SINGLE-FROM-BITS";

	/** The {@code closer-mop} shim package (and built-in ASDF system) name. */
	public static final String CLOSER_MOP_PKG = "CLOSER-MOP";

	/** {@code closer-mop:class-slots} -- lite: always nil (no slot metaobjects). */
	public static final String CLASS_SLOTS = "CLASS-SLOTS";

	/** {@code closer-mop:ensure-finalized} -- lite: identity. */
	public static final String ENSURE_FINALIZED = "ENSURE-FINALIZED";

	/** {@code closer-mop:slot-definition-name}. */
	public static final String SLOT_DEFINITION_NAME = "SLOT-DEFINITION-NAME";

	/** {@code closer-mop:slot-definition-type}. */
	public static final String SLOT_DEFINITION_TYPE = "SLOT-DEFINITION-TYPE";

	/** The {@code flexi-streams} shim package (and built-in ASDF system) name. */
	public static final String FLEXI_STREAMS_PKG = "FLEXI-STREAMS";

	/** {@code flexi-streams:make-flexi-stream} -- lite: the underlying stream. */
	public static final String MAKE_FLEXI_STREAM = "MAKE-FLEXI-STREAM";

	/** {@code string-to-octets} -- the flexi-streams shim's UTF-8 encoder. */
	public static final String STRING_TO_OCTETS = "STRING-TO-OCTETS";

	/** {@code octets-to-string} -- the flexi-streams shim's UTF-8 decoder. */
	public static final String OCTETS_TO_STRING = "OCTETS-TO-STRING";

	/**
	 * The {@code org.shirakumo.float-features} shim package name ({@code float-features}
	 * is its built-in nickname and the built-in ASDF system name).
	 */
	public static final String FLOAT_FEATURES_PKG = "ORG.SHIRAKUMO.FLOAT-FEATURES";

	/** {@code float-features:bits-double-float}. */
	public static final String BITS_DOUBLE_FLOAT = "BITS-DOUBLE-FLOAT";

	/** {@code float-features:double-float-bits}. */
	public static final String DOUBLE_FLOAT_BITS = "DOUBLE-FLOAT-BITS";

	/** {@code float-features:single-float-bits}. */
	public static final String SINGLE_FLOAT_BITS = "SINGLE-FLOAT-BITS";

	/** {@code float-features:bits-single-float}. */
	public static final String BITS_SINGLE_FLOAT = "BITS-SINGLE-FLOAT";

	/** The {@code trivial-gray-streams} shim package (and built-in ASDF system) name. */
	public static final String TRIVIAL_GRAY_STREAMS_PKG = "TRIVIAL-GRAY-STREAMS";

	/** The {@code uiop} stub package (and built-in ASDF system) name. */
	public static final String UIOP_PKG = "UIOP";

	/** {@code uiop:native-namestring} (stub: resolves, undefined when called). */
	public static final String NATIVE_NAMESTRING = "NATIVE-NAMESTRING";

	/** {@code uiop:namestring} (stub). */
	public static final String NAMESTRING = "NAMESTRING";

	/** {@code uiop:os-unix-p} (stub). */
	public static final String OS_UNIX_P = "OS-UNIX-P";

	/** {@code uiop:os-macosx-p} (stub). */
	public static final String OS_MACOSX_P = "OS-MACOSX-P";

	/**
	 * {@code uiop:add-package-local-nickname} -- lite: registers a GLOBAL nickname (no
	 * per-package scoping), the mechanism jzon's README recommends for shortening
	 * {@code com.inuoe.jzon} to {@code jzon}.
	 */
	public static final String ADD_PACKAGE_LOCAL_NICKNAME = "ADD-PACKAGE-LOCAL-NICKNAME";

	/**
	 * {@code uiop:merge-pathnames*} -- the safer defaults-aware merge, portable across
	 * ASDF-loaded libraries. Runtime function on all backends (interpreter + JVM at
	 * present; the WASM sandbox has no filesystem access to the loaded system's data dir,
	 * so the WASM tests using it are gated at the E2E layer).
	 */
	public static final String MERGE_PATHNAMES_STAR = "MERGE-PATHNAMES*";

	/**
	 * {@code uiop:file-exists-p} -- not a stub: its contract is {@link #PROBE_FILE}'s
	 * (the truename on success, nil otherwise), so the 1-argument call lowers onto
	 * {@code probe-file} in {@code LispMacroExpander.expandUiopStubCall} and runs on
	 * every backend. postmodern's {@code execute-file.lisp} has five of them.
	 */
	public static final String FILE_EXISTS_P = "FILE-EXISTS-P";

	/**
	 * {@code uiop:run-program} (stub: resolves, undefined when called). Spawning an
	 * external process is outside every backend's sandbox by design, so the honest answer
	 * is a call-time error rather than a silent no-op.
	 */
	public static final String RUN_PROGRAM = "RUN-PROGRAM";

	/**
	 * {@code uiop::get-pathname-defaults} (internal in real UIOP too, hence the double
	 * colon at every call site) -- the pathname relative names are resolved against.
	 * rontolisp answers with the empty namestring on every backend: a relative path is
	 * resolved by the host against its own working directory, and {@code ""} is exactly
	 * the pathname designating that, so
	 * {@code (merge-pathnames X (get-pathname-defaults))} yields {@code X} unchanged.
	 */
	public static final String GET_PATHNAME_DEFAULTS = "GET-PATHNAME-DEFAULTS";

	/**
	 * The {@code defpackage} {@code :local-nicknames} clause keyword -- lite: each
	 * {@code (nickname actual-package)} pair registers a GLOBAL nickname.
	 */
	public static final String LOCAL_NICKNAMES_KEYWORD = ":LOCAL-NICKNAMES";

	/**
	 * {@code rontolisp:fundamental-character-output-stream} -- the base class of
	 * rontolisp's own Gray-stream extension (eval.GrayStreamsLibrary).
	 */
	public static final String GRAY_CHAR_OUTPUT_STREAM = "FUNDAMENTAL-CHARACTER-OUTPUT-STREAM";

	/** {@code rontolisp:fundamental-character-input-stream}. */
	public static final String GRAY_CHAR_INPUT_STREAM = "FUNDAMENTAL-CHARACTER-INPUT-STREAM";

	/** {@code rontolisp:stream-write-char} -- the Gray per-character write generic. */
	public static final String GRAY_STREAM_WRITE_CHAR = "STREAM-WRITE-CHAR";

	/**
	 * {@code rontolisp:stream-write-string} -- the Gray write generic the
	 * {@code write-string}/{@code write-char} built-ins dispatch to for CLOS-instance
	 * streams.
	 */
	public static final String GRAY_STREAM_WRITE_STRING = "STREAM-WRITE-STRING";

	/**
	 * The {@code *standard-output*} variable -- bound to the stream designator {@code t}
	 * (standard output), which every print-family function accepts.
	 */
	public static final String STANDARD_OUTPUT_VAR = "*STANDARD-OUTPUT*";

	/** The {@code *error-output*} variable -- also the {@code t} designator (lite). */
	public static final String ERROR_OUTPUT_VAR = "*ERROR-OUTPUT*";

	private LispNames() {
	}

}
