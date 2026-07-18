package am.ik.rontolisp;

/**
 * Centralized constants for all special form and built-in function names. These constants
 * are compile-time constants (static final String) and can be used in switch case labels.
 */
public final class LispNames {

	// Special forms

	/** The {@code quote} special form. */
	public static final String QUOTE = "quote";

	/** The {@code if} special form. */
	public static final String IF = "if";

	/** The {@code let} special form. */
	public static final String LET = "let";

	/**
	 * The {@code progv} special form: establishes dynamic bindings for a runtime-computed
	 * list of symbols to a runtime-computed list of values, restored on exit. Interpreter
	 * only; the compilers reject it (the bound symbols are not known at compile time).
	 */
	public static final String PROGV = "progv";

	/** The {@code progn} special form. */
	public static final String PROGN = "progn";

	/** The {@code setq} special form. */
	public static final String SETQ = "setq";

	/** The {@code lambda} special form. */
	public static final String LAMBDA = "lambda";

	/** The {@code funcall} special form. */
	public static final String FUNCALL = "funcall";

	/** The {@code function} special form ({@code #'name} reader syntax). */
	public static final String FUNCTION = "function";

	/** The {@code symbol-function} built-in function. */
	public static final String SYMBOL_FUNCTION = "symbol-function";

	/** The {@code while} special form. */
	public static final String WHILE = "while";

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
	public static final String MOD = "mod";

	/** The {@code rem} built-in function. */
	public static final String REM = "rem";

	/** The {@code abs} built-in function. */
	public static final String ABS = "abs";

	/** The {@code min} built-in function. */
	public static final String MIN = "min";

	/** The {@code max} built-in function. */
	public static final String MAX = "max";

	/** The {@code sqrt} built-in function. */
	public static final String SQRT = "sqrt";

	/** The {@code isqrt} built-in function. */
	public static final String ISQRT = "isqrt";

	/** The {@code expt} built-in function. */
	public static final String EXPT = "expt";

	/** The {@code exp} built-in function. */
	public static final String EXP = "exp";

	/** The {@code log} built-in function. */
	public static final String LOG = "log";

	/** The {@code random} built-in function. */
	public static final String RANDOM = "random";

	/** The {@code get-universal-time} built-in function. */
	public static final String GET_UNIVERSAL_TIME = "get-universal-time";

	/** The {@code get-internal-real-time} built-in function. */
	public static final String GET_INTERNAL_REAL_TIME = "get-internal-real-time";

	/** The {@code get-internal-run-time} built-in function. */
	public static final String GET_INTERNAL_RUN_TIME = "get-internal-run-time";

	/** The {@code getenv} built-in function. */
	public static final String GETENV = "getenv";

	/** The {@code sin} built-in function. */
	public static final String SIN = "sin";

	/** The {@code cos} built-in function. */
	public static final String COS = "cos";

	/** The {@code tan} built-in function. */
	public static final String TAN = "tan";

	/** The {@code asin} built-in function. */
	public static final String ASIN = "asin";

	/** The {@code acos} built-in function. */
	public static final String ACOS = "acos";

	/** The {@code atan} built-in function. */
	public static final String ATAN = "atan";

	/** The {@code sinh} built-in function. */
	public static final String SINH = "sinh";

	/** The {@code cosh} built-in function. */
	public static final String COSH = "cosh";

	/** The {@code tanh} built-in function. */
	public static final String TANH = "tanh";

	/** The {@code gcd} built-in function. */
	public static final String GCD = "gcd";

	/** The {@code lcm} built-in function. */
	public static final String LCM = "lcm";

	/** The {@code signum} built-in function. */
	public static final String SIGNUM = "signum";

	/** The {@code logand} built-in function (bitwise AND, variadic; identity -1). */
	public static final String LOGAND = "logand";

	/**
	 * The {@code logior} built-in function (bitwise inclusive OR, variadic; identity 0).
	 */
	public static final String LOGIOR = "logior";

	/**
	 * The {@code logxor} built-in function (bitwise exclusive OR, variadic; identity 0).
	 */
	public static final String LOGXOR = "logxor";

	/** The {@code lognot} built-in function (bitwise NOT, i.e. ones' complement). */
	public static final String LOGNOT = "lognot";

	/**
	 * The {@code ash} built-in function (arithmetic shift; left when the count is
	 * non-negative, right otherwise).
	 */
	public static final String ASH = "ash";

	/**
	 * The {@code integer-length} built-in function (number of bits in the
	 * two's-complement magnitude of the argument, excluding sign).
	 */
	public static final String INTEGER_LENGTH = "integer-length";

	/**
	 * The {@code logbitp} built-in function (tests whether a given bit of the
	 * two's-complement integer is set).
	 */
	public static final String LOGBITP = "logbitp";

	/**
	 * The {@code byte} built-in (builds a byte specifier). Represented internally as a
	 * two-element list {@code (size position)}.
	 */
	public static final String BYTE = "byte";

	/** The {@code byte-size} built-in (the size of a byte specifier). */
	public static final String BYTE_SIZE = "byte-size";

	/** The {@code byte-position} built-in (the position of a byte specifier). */
	public static final String BYTE_POSITION = "byte-position";

	/**
	 * The {@code ldb} built-in (load byte: extract the byte specifier's field from an
	 * integer, right-justified).
	 */
	public static final String LDB = "ldb";

	/**
	 * The {@code dpb} built-in (deposit byte: replace the byte specifier's field of an
	 * integer with the low bits of a new value).
	 */
	public static final String DPB = "dpb";

	// Comparison

	/** The {@code =} built-in function. */
	public static final String EQ = "=";

	/** The {@code eq} built-in function (general equality). */
	public static final String EQ_GENERAL = "eq";

	/** The {@code eql} built-in function (type-aware value equality). */
	public static final String EQL = "eql";

	/** The {@code equal} built-in function (structural equality). */
	public static final String EQUAL = "equal";

	/**
	 * The {@code equalp} built-in function (like {@code equal} but strings/characters
	 * compare case-insensitively and numbers by value). Implemented as a recursive
	 * rontolisp-source {@code defun} shared by every backend (see {@code EqualpLibrary});
	 * lite: arrays/hash-tables/structures fall back to {@code eql} rather than recursing.
	 */
	public static final String EQUALP = "equalp";

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
	public static final String CONS = "cons";

	/** The {@code car} built-in function. */
	public static final String CAR = "car";

	/** The {@code cdr} built-in function. */
	public static final String CDR = "cdr";

	/** The {@code list} built-in function. */
	public static final String LIST = "list";

	/** The {@code append} built-in function. */
	public static final String APPEND = "append";

	/** The {@code nthcdr} built-in function. */
	public static final String NTHCDR = "nthcdr";

	/** The {@code length} built-in function. */
	public static final String LENGTH = "length";

	/** The {@code reverse} built-in function. */
	public static final String REVERSE = "reverse";

	/** The {@code member} built-in function. */
	public static final String MEMBER = "member";

	/**
	 * The {@code find} built-in function (return the first element {@code eql} to the
	 * given item, or nil).
	 */
	public static final String FIND = "find";

	/**
	 * The {@code find-if} built-in function (return the first element for which the
	 * predicate is true, or nil).
	 */
	public static final String FIND_IF = "find-if";

	/**
	 * The {@code find-if-not} built-in function (return the first element for which the
	 * predicate is false, or nil).
	 */
	public static final String FIND_IF_NOT = "find-if-not";

	/**
	 * The {@code member-if} built-in function (return the tail of the list starting at
	 * the first element for which the predicate is true, or nil).
	 */
	public static final String MEMBER_IF = "member-if";

	/**
	 * The {@code position} built-in function (return the 0-based index of the first
	 * element {@code eql} to the given item, or nil).
	 */
	public static final String POSITION = "position";

	/**
	 * The {@code position-if} built-in function (return the 0-based index of the first
	 * element for which the predicate is true, or nil).
	 */
	public static final String POSITION_IF = "position-if";

	/**
	 * The {@code position-if-not} built-in function (return the 0-based index of the
	 * first element for which the predicate is false, or nil).
	 */
	public static final String POSITION_IF_NOT = "position-if-not";

	/**
	 * The {@code complement} built-in (return a predicate answering the opposite of the
	 * given one). Classified as a macro here: it expands to a wrapping lambda, so it is
	 * not usable as {@code #'complement}.
	 */
	public static final String COMPLEMENT = "complement";

	/**
	 * The {@code count} built-in function (return the number of elements {@code eql} to
	 * the given item).
	 */
	public static final String COUNT = "count";

	/**
	 * The {@code count-if} built-in function (return the number of elements for which the
	 * predicate is true).
	 */
	public static final String COUNT_IF = "count-if";

	/** The {@code assoc} built-in function. */
	public static final String ASSOC = "assoc";

	/**
	 * The {@code assoc-if} built-in function (return the first pair whose car satisfies
	 * the predicate, or nil).
	 */
	public static final String ASSOC_IF = "assoc-if";

	/** The {@code last} built-in function. */
	public static final String LAST = "last";

	/**
	 * The {@code butlast} built-in function (return a copy of the list without its last
	 * element).
	 */
	public static final String BUTLAST = "butlast";

	/**
	 * The {@code getf} built-in function (return the value following the indicator in a
	 * property list, or nil). The partner of {@code remf}.
	 */
	public static final String GETF = "getf";

	/**
	 * The {@code remove} built-in function (return a copy without items eql to the given
	 * one).
	 */
	public static final String REMOVE = "remove";

	/**
	 * The {@code remove-if} built-in function (return a copy without items satisfying a
	 * predicate).
	 */
	public static final String REMOVE_IF = "remove-if";

	/**
	 * The {@code remove-if-not} built-in function (return a copy keeping only items
	 * satisfying a predicate).
	 */
	public static final String REMOVE_IF_NOT = "remove-if-not";

	/**
	 * The {@code remove-duplicates} built-in function (return a copy of the list with
	 * duplicate elements removed, keeping the last occurrence; elements compared with
	 * {@code eql}).
	 */
	public static final String REMOVE_DUPLICATES = "remove-duplicates";

	/**
	 * The {@code delete} built-in function (destructive variant of {@code remove}:
	 * splices out every element {@code eql} to the given one in place, reusing the
	 * surviving cons cells; use the return value since the head may change).
	 */
	public static final String DELETE = "delete";

	/**
	 * The {@code delete-if} built-in function (destructive variant of {@code remove-if};
	 * see {@link #DELETE}).
	 */
	public static final String DELETE_IF = "delete-if";

	/**
	 * The {@code delete-if-not} built-in function (destructive variant of
	 * {@code remove-if-not}; see {@link #DELETE}).
	 */
	public static final String DELETE_IF_NOT = "delete-if-not";

	/**
	 * The {@code substitute} built-in function (return a copy of the list with each
	 * element {@code eql} to the old item replaced by the new item).
	 */
	public static final String SUBSTITUTE = "substitute";

	/**
	 * The {@code nsubstitute} built-in function (destructive variant of
	 * {@code substitute}: rewrites every {@code car} {@code eql} to the old item with the
	 * new item in place and returns the mutated list).
	 */
	public static final String NSUBSTITUTE = "nsubstitute";

	/**
	 * The {@code nconc} built-in function (destructively concatenate two lists).
	 */
	public static final String NCONC = "nconc";

	/**
	 * The {@code sort} built-in function (destructively sort a list using a comparison
	 * predicate).
	 */
	public static final String SORT = "sort";

	/**
	 * The {@code stable-sort} built-in function (sort preserving the relative order of
	 * elements the predicate considers equal; supports {@code :key}). Lite: expanded to a
	 * decorate/{@code sort}/undecorate scan over the sequence as a list, so the result is
	 * always a fresh list (a vector argument does not come back as a vector).
	 */
	public static final String STABLE_SORT = "stable-sort";

	/**
	 * The {@code copy-seq} built-in function (returns a fresh copy of a sequence;
	 * expanded to {@code (subseq seq 0)}).
	 */
	public static final String COPY_SEQ = "copy-seq";

	/** The {@code identity} built-in function (returns its argument unchanged). */
	public static final String IDENTITY = "identity";

	/** The {@code copy-list} built-in function (returns a shallow copy of a list). */
	public static final String COPY_LIST = "copy-list";

	/**
	 * The {@code nreverse} built-in function (destructively reverses a list by rewiring
	 * each {@code cdr} and returning the former last cell as the new head; use the return
	 * value).
	 */
	public static final String NREVERSE = "nreverse";

	/**
	 * The {@code make-list} built-in function (creates a list of n nil elements; the CL
	 * {@code :initial-element} keyword is not supported).
	 */
	public static final String MAKE_LIST = "make-list";

	/**
	 * The {@code union} built-in function (set union of two lists, compared with
	 * {@code eql}; CL {@code :test}/{@code :key} keywords are not supported).
	 */
	public static final String UNION = "union";

	/**
	 * The {@code intersection} built-in function (set intersection of two lists, compared
	 * with {@code eql}).
	 */
	public static final String INTERSECTION = "intersection";

	/**
	 * The {@code set-difference} built-in function (elements of the first list not
	 * present in the second, compared with {@code eql}).
	 */
	public static final String SET_DIFFERENCE = "set-difference";

	/**
	 * The {@code adjoin} built-in function (prepends an item to a list unless it is
	 * already a member, compared with {@code eql}).
	 */
	public static final String ADJOIN = "adjoin";

	/**
	 * The {@code list*} built-in function (build a list whose final element is the last
	 * argument used as the tail: {@code (list* a b c) -> (cons a (cons b c))}).
	 */
	public static final String LIST_STAR = "list*";

	/**
	 * The {@code acons} built-in function (prepend a {@code (key . value)} pair to an
	 * association list: {@code (acons k v alist) -> (cons (cons k v) alist)}).
	 */
	public static final String ACONS = "acons";

	/**
	 * The {@code endp} built-in function (true at the end of a list; here a synonym for
	 * {@code null}, the improper-list error of CL is relaxed).
	 */
	public static final String ENDP = "endp";

	/**
	 * The {@code elt} built-in function (0-based element access; lists only, a synonym
	 * for {@code nth} with reversed argument order, string indexing is not supported).
	 */
	public static final String ELT = "elt";

	/**
	 * The {@code rassoc} built-in function (return the first pair whose cdr is
	 * {@code eql} to the given value, or nil).
	 */
	public static final String RASSOC = "rassoc";

	/**
	 * The {@code pairlis} built-in function (pair up a list of keys and a list of values
	 * into an association list, prepended to an optional existing alist).
	 */
	public static final String PAIRLIS = "pairlis";

	/**
	 * The {@code copy-alist} built-in function (copy an association list's spine and its
	 * {@code (key . value)} pair cells; the keys and values themselves are shared).
	 */
	public static final String COPY_ALIST = "copy-alist";

	/**
	 * The {@code revappend} built-in function (reverse the first list and append the
	 * second: {@code (revappend x y) -> (append (reverse x) y)}).
	 */
	public static final String REVAPPEND = "revappend";

	/**
	 * The {@code nreconc} built-in function (destructive {@code revappend}: expands to
	 * {@code (nconc (nreverse x) y)}, so the cons cells of {@code x} are reused; use the
	 * return value).
	 */
	public static final String NRECONC = "nreconc";

	/**
	 * The {@code maplist} built-in function (apply the function to successive cdrs of the
	 * list and collect the results; single-list only).
	 */
	public static final String MAPLIST = "maplist";

	/**
	 * The {@code mapcon} built-in function (apply the function to successive cdrs of the
	 * list and concatenate the result lists; single-list only).
	 */
	public static final String MAPCON = "mapcon";

	/**
	 * The {@code mapl} built-in function (apply the function to successive cdrs of the
	 * list for its side effects and return the original list; single-list only).
	 */
	public static final String MAPL = "mapl";

	/** The {@code rplaca} built-in function. */
	public static final String RPLACA = "rplaca";

	/** The {@code rplacd} built-in function. */
	public static final String RPLACD = "rplacd";

	/** The {@code %remf-tail} built-in function. */
	public static final String REMF_TAIL = "%remf-tail";

	// Hash tables

	/** The {@code make-hash-table} built-in function. */
	public static final String MAKE_HASH_TABLE = "make-hash-table";

	/** The {@code gethash} built-in function. */
	public static final String GETHASH = "gethash";

	/**
	 * The {@code %puthash} internal built-in function. The target of the {@code gethash}
	 * {@code setf} place: {@code (%puthash key table value)} stores and returns the
	 * value.
	 */
	public static final String PUTHASH = "%puthash";

	/** The {@code remhash} built-in function. */
	public static final String REMHASH = "remhash";

	/** The {@code clrhash} built-in function. */
	public static final String CLRHASH = "clrhash";

	/** The {@code hash-table-count} built-in function. */
	public static final String HASH_TABLE_COUNT = "hash-table-count";

	/** The {@code hash-table-p} predicate. */
	public static final String HASH_TABLE_P = "hash-table-p";

	/**
	 * The {@code maphash} built-in function (apply a function to each key/value pair).
	 */
	public static final String MAPHASH = "maphash";

	// Arrays

	/**
	 * The {@code make-array} built-in function. Supports arrays of any rank {@code >= 1}
	 * and the {@code :initial-element} keyword.
	 */
	public static final String MAKE_ARRAY = "make-array";

	/** The {@code aref} built-in function (array element access). */
	public static final String AREF = "aref";

	/**
	 * The {@code %aset} internal built-in function. The target of the {@code aref}
	 * {@code setf} place: {@code (%aset array subscript... value)} stores and returns the
	 * value.
	 */
	public static final String ASET = "%aset";

	/**
	 * The {@code row-major-aref} built-in function (flat row-major element access,
	 * independent of rank); also a {@code setf} place.
	 */
	public static final String ROW_MAJOR_AREF = "row-major-aref";

	/**
	 * The {@code %row-major-aset} internal built-in function. The target of the
	 * {@code row-major-aref} {@code setf} place:
	 * {@code (%row-major-aset array index value)} stores and returns the value.
	 */
	public static final String ROW_MAJOR_ASET = "%row-major-aset";

	/**
	 * The {@code array-row-major-index} built-in function (the flat row-major index of
	 * the given subscripts). Expanded by
	 * {@link LispMacroExpander#expandArrayRowMajorIndex} into a Horner fold over
	 * {@code array-dimensions}.
	 */
	public static final String ARRAY_ROW_MAJOR_INDEX = "array-row-major-index";

	/** The {@code :initial-element} keyword accepted by {@code make-array}. */
	public static final String INITIAL_ELEMENT_KEYWORD = ":initial-element";

	/** The {@code :initial-contents} keyword of {@code make-array}. */
	public static final String INITIAL_CONTENTS_KEYWORD = ":initial-contents";

	/** The {@code :fill-pointer} keyword accepted by {@code make-array}. */
	public static final String FILL_POINTER_KEYWORD = ":fill-pointer";

	/** The {@code :adjustable} keyword accepted by {@code make-array}. */
	public static final String ADJUSTABLE_KEYWORD = ":adjustable";

	/**
	 * The {@code :displaced-to} keyword accepted by {@code make-array}: the built array
	 * is a view sharing the target's storage. Cannot be combined with
	 * {@code :fill-pointer}/{@code :adjustable}/{@code :initial-element} (lite
	 * semantics).
	 */
	public static final String DISPLACED_TO_KEYWORD = ":displaced-to";

	/** The {@code :displaced-index-offset} keyword accepted by {@code make-array}. */
	public static final String DISPLACED_INDEX_OFFSET_KEYWORD = ":displaced-index-offset";

	/**
	 * The {@code adjust-array} built-in function: resize an array preserving the elements
	 * at common subscripts. Adjusts an {@code :adjustable} array in place (returning it),
	 * otherwise returns a fresh array. Expanded by
	 * {@link LispMacroExpander#expandAdjustArray} on the compile path.
	 */
	public static final String ADJUST_ARRAY = "adjust-array";

	/**
	 * The {@code array-displacement} built-in function: the {@code :displaced-to} target
	 * and offset as two values ({@code nil} and 0 for a non-displaced array). Expanded by
	 * {@link LispMacroExpander#expandArrayDisplacement} on the compile path.
	 */
	public static final String ARRAY_DISPLACEMENT = "array-displacement";

	/**
	 * The {@code %array-disp-target} internal built-in function: the displacement target
	 * of an array, or {@code nil} (the primary value of {@code array-displacement}).
	 */
	public static final String ARRAY_DISP_TARGET = "%array-disp-target";

	/**
	 * The {@code %array-disp-offset} internal built-in function: the displacement offset
	 * of an array, or 0 (the secondary value of {@code array-displacement}).
	 */
	public static final String ARRAY_DISP_OFFSET = "%array-disp-offset";

	/**
	 * The {@code %array-become} internal built-in function:
	 * {@code (%array-become old new)} replaces {@code old}'s dimensions, fill pointer and
	 * data with {@code new}'s in place and returns {@code old} (the in-place half of
	 * {@code adjust-array} on an adjustable array).
	 */
	public static final String ARRAY_BECOME = "%array-become";

	/**
	 * The {@code fill-pointer} built-in function (the fill pointer of a vector). Also a
	 * {@code setf} place (target {@link #SET_FILL_POINTER}).
	 */
	public static final String FILL_POINTER = "fill-pointer";

	/**
	 * The {@code %set-fill-pointer} internal built-in function. The target of the
	 * {@code fill-pointer} {@code setf} place: {@code (%set-fill-pointer vector value)}
	 * stores and returns the value.
	 */
	public static final String SET_FILL_POINTER = "%set-fill-pointer";

	/** The {@code array-has-fill-pointer-p} built-in function. */
	public static final String ARRAY_HAS_FILL_POINTER_P = "array-has-fill-pointer-p";

	/**
	 * The {@code array-element-type} built-in function. Element types are not tracked, so
	 * it always returns {@code t}.
	 */
	public static final String ARRAY_ELEMENT_TYPE = "array-element-type";

	/** The {@code adjustable-array-p} built-in function. */
	public static final String ADJUSTABLE_ARRAY_P = "adjustable-array-p";

	/**
	 * The {@code vector-push} built-in function: store an element at the fill pointer and
	 * increment it, returning the index used or {@code nil} when full.
	 */
	public static final String VECTOR_PUSH = "vector-push";

	/**
	 * The {@code vector-pop} built-in function: decrement the fill pointer and return the
	 * element below it.
	 */
	public static final String VECTOR_POP = "vector-pop";

	/**
	 * The {@code vector-push-extend} built-in function: like {@code vector-push} but
	 * grows the vector when it is full.
	 */
	public static final String VECTOR_PUSH_EXTEND = "vector-push-extend";

	/**
	 * The {@code vector} built-in function (build a fresh rank-1 array from the
	 * arguments). Expanded by {@link LispMacroExpander#expandVector} into
	 * {@code make-array} + {@code %aset}.
	 */
	public static final String VECTOR = "vector";

	/**
	 * The {@code svref} built-in function (simple-vector element access). Expanded by
	 * {@link LispMacroExpander#expandSvref} into {@code aref}; also a {@code setf} place.
	 */
	public static final String SVREF = "svref";

	/**
	 * The {@code array-dimensions} built-in function (the dimension sizes as a list). The
	 * only array introspection primitive with per-backend support;
	 * {@code array-rank}/{@code array-dimension}/{@code array-total-size} expand onto it.
	 */
	public static final String ARRAY_DIMENSIONS = "array-dimensions";

	/** The {@code array-dimension} built-in function (one dimension size). */
	public static final String ARRAY_DIMENSION = "array-dimension";

	/** The {@code array-rank} built-in function (1 for vectors, 2 for matrices). */
	public static final String ARRAY_RANK = "array-rank";

	/** The {@code array-total-size} built-in function (the element count). */
	public static final String ARRAY_TOTAL_SIZE = "array-total-size";

	/**
	 * The {@code coerce} built-in function. Supports the literal result types
	 * {@code 'list}, {@code 'vector}, and {@code 'string}; expanded by
	 * {@link LispMacroExpander#expandCoerce}.
	 */
	public static final String COERCE = "coerce";

	// Higher-order functions

	/** The {@code mapcar} built-in function. */
	public static final String MAPCAR = "mapcar";

	/**
	 * The {@code map} built-in function (map a function over arbitrary sequences,
	 * building a result of a requested type: {@code 'list}, {@code 'string}, or nil for
	 * effect).
	 */
	public static final String MAP = "map";

	/**
	 * The {@code map-into} built-in function (destructively store the results of applying
	 * a function to successive elements of the argument sequences into the result
	 * sequence).
	 */
	public static final String MAP_INTO = "map-into";

	/** The {@code mapc} built-in function (apply for effect, return the list). */
	public static final String MAPC = "mapc";

	/**
	 * The {@code mapcan} built-in function (apply over a list and concatenate the result
	 * lists).
	 */
	public static final String MAPCAN = "mapcan";

	/**
	 * The {@code apply} built-in function (apply a function to a spread argument list).
	 */
	public static final String APPLY = "apply";

	/** The {@code reduce} built-in function. */
	public static final String REDUCE = "reduce";

	/**
	 * The {@code every} built-in function (true if the predicate holds for every
	 * element).
	 */
	public static final String EVERY = "every";

	/**
	 * The {@code some} built-in function (the first non-nil predicate result, or nil).
	 */
	public static final String SOME = "some";

	/**
	 * The {@code notany} built-in function (true if the predicate holds for no element;
	 * the complement of {@code some}).
	 */
	public static final String NOTANY = "notany";

	/**
	 * The {@code notevery} built-in function (true if the predicate fails for some
	 * element; the complement of {@code every}).
	 */
	public static final String NOTEVERY = "notevery";

	// Macros

	/** The {@code setf} macro. */
	public static final String SETF = "setf";

	/** The {@code push} macro. */
	public static final String PUSH = "push";

	/** The {@code pop} macro. */
	public static final String POP = "pop";

	/** The {@code remf} macro. */
	public static final String REMF = "remf";

	/** The {@code let*} macro. */
	public static final String LET_STAR = "let*";

	/** The {@code dolist} macro. */
	public static final String DOLIST = "dolist";

	/** The {@code incf} macro. */
	public static final String INCF = "incf";

	/** The {@code decf} macro. */
	public static final String DECF = "decf";

	/** The {@code format} macro. */
	public static final String FORMAT = "format";

	/** The {@code defun} macro. */
	public static final String DEFUN = "defun";

	/**
	 * The {@code defmacro} special form. User macros are expanded by the interpreter at
	 * evaluation time and by a compile-time pass on the compilation path, so the JVM/WASM
	 * backends never see a macro call.
	 */
	public static final String DEFMACRO = "defmacro";

	/**
	 * The {@code defstruct} special form. Expanded into the defuns it generates
	 * (constructor, predicate, copier, accessors) by
	 * {@code LispMacroExpander.expandDefstruct}: the interpreter expands at evaluation
	 * time, the compilers splice top-level forms before Pass 1.
	 */
	public static final String DEFSTRUCT = "defstruct";

	/**
	 * The {@code defclass} special form (static CLOS subset). Expanded into the defuns it
	 * generates (keyword constructor, reader/accessor functions) by
	 * {@code LispMacroExpander.expandDefclass}: the interpreter expands at evaluation
	 * time, the compilers splice top-level forms before Pass 1.
	 */
	public static final String DEFCLASS = "defclass";

	/**
	 * The {@code defgeneric} special form (static CLOS subset). Registers a generic
	 * function and defines its dispatcher defun (see
	 * {@code LispMacroExpander.generateDispatcher}).
	 */
	public static final String DEFGENERIC = "defgeneric";

	/**
	 * The {@code defmethod} special form (static CLOS subset). Registers a method
	 * (optionally specialized on the FIRST parameter with an {@code eql} literal, a
	 * {@code defclass} class, or a built-in type) and regenerates the generic's
	 * dispatcher defun.
	 */
	public static final String DEFMETHOD = "defmethod";

	/**
	 * The {@code call-next-method} local operator (static CLOS subset, Stage 3). Valid
	 * only inside a {@code defmethod} body; {@code LispMacroExpander.expandDefmethod}
	 * rewrites it to a {@code funcall} of the method's next-method thunk, so it never
	 * reaches the evaluator/compilers as a symbol.
	 */
	public static final String CALL_NEXT_METHOD = "call-next-method";

	/**
	 * The {@code next-method-p} local operator (static CLOS subset, Stage 3). Valid only
	 * inside a {@code defmethod} body; rewritten by
	 * {@code LispMacroExpander.expandDefmethod} to a nil-test of the next-method thunk.
	 */
	public static final String NEXT_METHOD_P = "next-method-p";

	/**
	 * The {@code make-instance} macro (static CLOS subset). Requires a literal quoted
	 * class name; expands to the class's generated keyword constructor.
	 */
	public static final String MAKE_INSTANCE = "make-instance";

	/**
	 * The {@code slot-value} macro (static CLOS subset). Requires a literal quoted slot
	 * name; expands to the slot's {@code nth} position and is a {@code setf} place.
	 */
	public static final String SLOT_VALUE = "slot-value";

	/** The {@code &rest} lambda-list keyword. */
	public static final String LAMBDA_REST = "&rest";

	/**
	 * The {@code &body} lambda-list keyword ({@code defmacro} alias for {@code &rest}).
	 */
	public static final String LAMBDA_BODY = "&body";

	/** The {@code &optional} lambda-list keyword. */
	public static final String LAMBDA_OPTIONAL = "&optional";

	/** The {@code &key} lambda-list keyword. */
	public static final String LAMBDA_KEY = "&key";

	/** The {@code &aux} lambda-list keyword. */
	public static final String LAMBDA_AUX = "&aux";

	/** The {@code &allow-other-keys} lambda-list keyword. */
	public static final String LAMBDA_ALLOW_OTHER_KEYS = "&allow-other-keys";

	/** The {@code :allow-other-keys} call-site keyword argument. */
	public static final String ALLOW_OTHER_KEYS_KEYWORD = ":allow-other-keys";

	/**
	 * The {@code gensym} function. Returns a fresh symbol named
	 * {@code #:<prefix><counter>} (default prefix {@code g}). rontolisp symbols are plain
	 * strings, so the result is an ordinary symbol whose uniqueness rests on the
	 * {@code #:} prefix and a monotonically increasing counter.
	 */
	public static final String GENSYM = "gensym";

	/**
	 * The {@code macroexpand-1} function. Expands the top-level form once when its
	 * operator is a user macro or a built-in macro; returns the form unchanged otherwise
	 * (rontolisp has no multiple values, so no second {@code expanded-p} value).
	 */
	public static final String MACROEXPAND_1 = "macroexpand-1";

	/** The {@code macroexpand} function. Repeats {@code macroexpand-1} to a fixpoint. */
	public static final String MACROEXPAND = "macroexpand";

	/**
	 * The {@code symbol-name} function. Returns the symbol's stored name verbatim (the
	 * same spelling {@code princ} prints), so keywords keep their leading {@code :} and
	 * rontolisp's case-preserving lowercase names are NOT upcased like in CL.
	 */
	public static final String SYMBOL_NAME = "symbol-name";

	/**
	 * The {@code intern} function. Returns the symbol named by the argument string,
	 * verbatim. rontolisp symbols compare by name, so there is no intern table; the
	 * current package is ignored and a package argument is an error.
	 */
	public static final String INTERN = "intern";

	/**
	 * The {@code find-symbol} function. Like {@code intern} but never creates: returns
	 * the symbol when the name is known (a {@code cl} symbol, a keyword, or a
	 * user-defined function), nil otherwise.
	 */
	public static final String FIND_SYMBOL = "find-symbol";

	/**
	 * The {@code make-symbol} function. Returns a fresh uninterned symbol named
	 * {@code #:<name>} (the same {@code #:} convention gensym uses).
	 */
	public static final String MAKE_SYMBOL = "make-symbol";

	/** The {@code boundp} function. Whether a symbol names a bound global variable. */
	public static final String BOUNDP = "boundp";

	/**
	 * The {@code fboundp} function. Whether a symbol names a function, macro, or special
	 * operator.
	 */
	public static final String FBOUNDP = "fboundp";

	/** The {@code symbol-value} function. The global variable value named by a symbol. */
	public static final String SYMBOL_VALUE = "symbol-value";

	/** The {@code defvar} special form. */
	public static final String DEFVAR = "defvar";

	/** The {@code defparameter} special form (unconditional global assignment). */
	public static final String DEFPARAMETER = "defparameter";

	/**
	 * The {@code defconstant} special form. rontolisp does not enforce constancy; it
	 * behaves like {@code defparameter}.
	 */
	public static final String DEFCONSTANT = "defconstant";

	/** The {@code cond} macro. */
	public static final String COND = "cond";

	/** The {@code case} macro. */
	public static final String CASE = "case";

	/** The {@code otherwise} default-clause designator recognized by {@code case}. */
	public static final String OTHERWISE = "otherwise";

	/**
	 * The {@code ecase} macro (exhaustive {@code case}; signals an error when no key
	 * matches).
	 */
	public static final String ECASE = "ecase";

	/**
	 * The {@code etypecase} macro (exhaustive {@code typecase}; signals an error when no
	 * type matches).
	 */
	public static final String ETYPECASE = "etypecase";

	/**
	 * The {@code ccase} macro. Without a restart system this behaves like {@code ecase}
	 * (signals an error when no key matches).
	 */
	public static final String CCASE = "ccase";

	/**
	 * The {@code check-type} macro. Lite version: expands to a type test built from the
	 * {@code typecase} predicate map (plus compound specifiers) and an {@code error} call
	 * -- no restarts, no place re-storing.
	 */
	public static final String CHECK_TYPE = "check-type";

	/**
	 * The {@code assert} macro. Lite version: expands to {@code (unless test (error
	 * ...))} -- the optional places list is ignored (no restarts).
	 */
	public static final String ASSERT = "assert";

	/**
	 * The {@code declare} declaration marker. Parsed no-op: the whole form expands to
	 * {@code nil} and its arguments are never evaluated or validated.
	 */
	public static final String DECLARE = "declare";

	/**
	 * The {@code declaim} macro. Parsed no-op like {@link #DECLARE}.
	 */
	public static final String DECLAIM = "declaim";

	/**
	 * The {@code proclaim} operator. Parsed no-op like {@link #DECLARE} (classified as a
	 * macro here, not a function as in CL, so the argument is not evaluated either).
	 */
	public static final String PROCLAIM = "proclaim";

	/**
	 * The {@code special} declaration identifier. A {@code (special ...)} clause inside a
	 * top-level {@code declaim}/{@code proclaim} proclaims its variables special (dynamic
	 * binding), collected by {@code SpecialVarCollector}. Not a callable symbol; only
	 * meaningful inside a declaration specifier, so it is not registered as a cl symbol.
	 */
	public static final String SPECIAL = "special";

	/**
	 * The {@code the} operator. Expands to its value form (identity; no type checking).
	 */
	public static final String THE = "the";

	/**
	 * The {@code deftype} macro. Parsed no-op like {@link #DECLAIM}: the type name is NOT
	 * registered, so it is only useful where the defined type is never used in a runtime
	 * type test (e.g. inside {@code declaim ftype} declarations, themselves no-ops); a
	 * later use in {@code check-type}/{@code typecase} fails naming the unsupported
	 * specifier.
	 */
	public static final String DEFTYPE = "deftype";

	/**
	 * The {@code define-condition} macro. Expands into the equivalent {@code defclass}
	 * over the CLOS static subset, so a condition type is an ordinary class registered in
	 * the {@code ClosRegistry} (parent defaults to {@code condition}); its
	 * {@code :report} is registered there too. Lite: single inheritance, and no restart
	 * layer.
	 */
	public static final String DEFINE_CONDITION = "define-condition";

	/**
	 * The {@code define-modify-macro} macro. Lowers to a {@code defmacro} that expands
	 * {@code (name place args...)} into {@code (setf place (function place args...))}.
	 * Lite: the place subforms may be evaluated more than once (no
	 * {@code get-setf-expansion} single-evaluation protocol).
	 */
	public static final String DEFINE_MODIFY_MACRO = "define-modify-macro";

	/**
	 * The {@code define-setf-expander} macro. Parsed no-op returning nil (like
	 * {@link #DEFINE_CONDITION}): the full five-value setf-expansion protocol
	 * ({@code get-setf-expansion}/{@code &environment}) is unsupported, so
	 * {@code (setf (place ...) v)} for the newly defined place is not available.
	 */
	public static final String DEFINE_SETF_EXPANDER = "define-setf-expander";

	/**
	 * The {@code define-compiler-macro} macro. Parsed no-op returning nil, like
	 * {@link #DECLAIM}/{@link #DEFTYPE}: a compiler macro is only an optimization hint,
	 * so dropping it leaves the ordinary function definition authoritative (the
	 * observable behavior is identical, only slower). The {@code &whole} parameter and
	 * any body are ignored.
	 */
	public static final String DEFINE_COMPILER_MACRO = "define-compiler-macro";

	/**
	 * The {@code restart-case} macro. Lite lowering to its primary form only: there is no
	 * restart/condition system, so the restart clauses are dead code (they can only be
	 * reached by {@code invoke-restart}, which does not exist here). A signaling primary
	 * form therefore signals as usual; see {@code .kb/declarations-type-checks.md} for
	 * the lite semantics shared with {@code check-type}/{@code assert}.
	 */
	public static final String RESTART_CASE = "restart-case";

	/**
	 * The {@code macrolet} macro (local, lexically scoped macro definitions). Expands its
	 * body with the local macros active and drops the definitions (see
	 * {@code LispMacroExpander}/{@code UserMacroExpander}/{@code LispEvaluator} macrolet
	 * handling). Local macro bodies run at expansion time like {@code defmacro}.
	 */
	public static final String MACROLET = "macrolet";

	/**
	 * The {@code make-condition} operator. Lite expansion to the {@code :format-control}
	 * value (or the condition type name as a string), so the common
	 * {@code (error (make-condition 'type :format-control "..."))} idiom signals with the
	 * intended message. Classified as a macro here (in CL it is a function).
	 */
	public static final String MAKE_CONDITION = "make-condition";

	/**
	 * The {@code documentation} accessor. Lite: reads expand to nil and
	 * {@code (setf (documentation ...) "...")} discards the docstring (docstrings are not
	 * stored anywhere). Classified as a macro here (in CL it is a function).
	 */
	public static final String DOCUMENTATION = "documentation";

	/**
	 * The {@code pushnew} macro. Expands like {@link #PUSH} guarded by {@code member};
	 * extra {@code :test}/{@code :key} arguments are passed through to {@code member}.
	 */
	public static final String PUSHNEW = "pushnew";

	/**
	 * The {@code eval-when} operator. Expands to {@code progn} of its body; at top level
	 * the compile path additionally splices the body into top-level forms (see
	 * {@code LispMacroExpander.flattenTopLevel}).
	 */
	public static final String EVAL_WHEN = "eval-when";

	/**
	 * The {@code locally} operator. Expands to {@code progn} of its body with the leading
	 * declarations dropped (declarations are parsed no-ops).
	 */
	public static final String LOCALLY = "locally";

	/**
	 * The {@code flet} macro (local, non-recursive function bindings). Expands to
	 * let-bound lambdas plus a body rewrite of call position and {@code #'name} (see
	 * {@code LispMacroExpander.expandFlet}).
	 */
	public static final String FLET = "flet";

	/**
	 * The {@code labels} macro. Like {@link #FLET} but the definitions see each other
	 * (mutual recursion) via the nil-then-{@code setq} letrec lowering.
	 */
	public static final String LABELS = "labels";

	/**
	 * The {@code values} function. In an ordinary (single-value) context it expands like
	 * {@code prog1}: every argument is evaluated and the first is the result (zero
	 * arguments yield nil). The multiple-value consumers recognize a literal
	 * {@code (values ...)} producer syntactically and receive all of its values (see
	 * {@code LispMacroExpander.lowerMvProducer}); there is no runtime multiple-value
	 * representation.
	 */
	public static final String VALUES = "values";

	/**
	 * The {@code multiple-value-bind} macro. Binds the variables to the values of the
	 * producer form: a literal {@code (values ...)} call or a recognized two-value
	 * built-in ({@code floor}/{@code ceiling}/{@code round}/{@code truncate} and
	 * {@code gethash}); any other producer supplies a single value. Missing values bind
	 * to nil.
	 */
	public static final String MULTIPLE_VALUE_BIND = "multiple-value-bind";

	/**
	 * The {@code multiple-value-list} macro. Collects the producer's values (recognized
	 * like {@link #MULTIPLE_VALUE_BIND}) into a list.
	 */
	public static final String MULTIPLE_VALUE_LIST = "multiple-value-list";

	/**
	 * The {@code multiple-value-call} macro. Calls the function with all values of every
	 * producer form (recognized like {@link #MULTIPLE_VALUE_BIND}) as the arguments;
	 * lowered to a direct {@code funcall}.
	 */
	public static final String MULTIPLE_VALUE_CALL = "multiple-value-call";

	/**
	 * The {@code nth-value} macro. Returns the n-th (0-based) value of the producer form
	 * (recognized like {@link #MULTIPLE_VALUE_BIND}); expands to {@code nth} over
	 * {@code multiple-value-list}.
	 */
	public static final String NTH_VALUE = "nth-value";

	/**
	 * The {@code multiple-value-setq} macro. Assigns the multiple values of the producer
	 * form (recognized like {@link #MULTIPLE_VALUE_BIND}) to the existing variables via
	 * {@code setq} and returns the primary value; extra variables receive nil.
	 */
	public static final String MULTIPLE_VALUE_SETQ = "multiple-value-setq";

	/**
	 * The {@code rotatef} macro. Rotates the values of its setf-able places left (each
	 * place receives the old value of the next, the last receives the old value of the
	 * first) and returns nil.
	 */
	public static final String ROTATEF = "rotatef";

	/**
	 * The {@code destructuring-bind} macro. Binds the variables of a (possibly nested)
	 * pattern to the corresponding parts of the evaluated form, supporting
	 * {@code &optional}/{@code &rest}/{@code &body}/{@code &key}/{@code &aux} inside the
	 * pattern; expands to a {@code let*} of car/cdr chains (see
	 * {@code LispMacroExpander.expandDestructuringBind}). Lite semantics: a mismatch
	 * between the pattern and the value does not signal (missing positions bind to nil,
	 * surplus elements are ignored); {@code &whole}/{@code &environment} are unsupported.
	 */
	public static final String DESTRUCTURING_BIND = "destructuring-bind";

	/**
	 * The {@code error} macro (signal an error). It builds the message with the
	 * {@code format} machinery and delegates to {@link #ERROR_INTERNAL}. Like
	 * {@code format} it has no function value (classified as a macro).
	 */
	public static final String ERROR = "error";

	/**
	 * The {@code cerror} operator (lite): without restarts the error is not continuable,
	 * so {@code (cerror continue-format datum args...)} lowers to
	 * {@code (error datum args...)}.
	 */
	public static final String CERROR = "cerror";

	/**
	 * Internal single-argument primitive that throws/traps with a pre-built message
	 * string. Not part of the public API; produced by the {@code error} macro expansion.
	 */
	public static final String ERROR_INTERNAL = "%error";

	/**
	 * The {@code warn} macro (print a warning and continue). It builds the message with
	 * the {@code format} machinery like {@link #ERROR} and delegates to
	 * {@link #WARN_INTERNAL}; there is no condition system, so no condition object is
	 * created and nothing can handle or muffle the warning. Like {@code error} it has no
	 * function value (classified as a macro).
	 */
	public static final String WARN = "warn";

	/**
	 * Internal single-argument primitive that writes {@code WARNING: message} plus a
	 * newline to the standard error stream and returns nil. Not part of the public API;
	 * produced by the {@code warn} macro expansion.
	 */
	public static final String WARN_INTERNAL = "%warn";

	/**
	 * Internal two-argument primitive {@code (%error-cond condition message)} that
	 * signals a fatal error carrying a condition object (a CLOS-subset tagged-list
	 * instance) alongside the pre-built message string. Produced by the {@code error}
	 * macro expansion for the typed and condition-object designator forms; on the WASM
	 * backends it traps like {@link #ERROR_INTERNAL}.
	 */
	public static final String ERROR_COND_INTERNAL = "%error-cond";

	/**
	 * The {@code signal} macro (signal a non-fatal condition). Same designator surface as
	 * {@link #ERROR}; when no handler is established the signal returns nil (the CL
	 * fall-through), which is the only behavior on the WASM backends.
	 */
	public static final String SIGNAL = "signal";

	/**
	 * Internal two-argument primitive {@code (%signal-cond condition message)} behind
	 * {@link #SIGNAL}: raises the condition when a {@code handler-case} handler is
	 * established on the current thread of control, and returns nil otherwise.
	 */
	public static final String SIGNAL_COND_INTERNAL = "%signal-cond";

	/**
	 * The {@code with-slots} macro: binds variables to the slot values of a CLOS-subset
	 * instance for the body. Lite (read-only): the bindings are plain {@code let}
	 * variables over {@code slot-value} reads, not symbol macros, so assigning one does
	 * NOT write back to the slot.
	 */
	public static final String WITH_SLOTS = "with-slots";

	/**
	 * The {@code handler-case} operator: evaluates an expression, dispatching an error
	 * signaled during it to the first clause whose condition type matches (rethrowing
	 * when none does). Interpreter and JVM backends only; the WASM compilers reject it (a
	 * WASM error is an uncatchable trap).
	 */
	public static final String HANDLER_CASE = "handler-case";

	/**
	 * The {@code ignore-errors} macro: sugar over {@code (handler-case (progn forms...)
	 * (error (c) (values nil c)))}. Interpreter and JVM backends only, like
	 * {@link #HANDLER_CASE}.
	 */
	public static final String IGNORE_ERRORS = "ignore-errors";

	/**
	 * Internal zero-argument form that decrements the per-thread {@code handler-case}
	 * handler depth and yields nil. JVM backend only: emitted as the cleanup of the
	 * handler-depth bookkeeping when a {@code return} exits a {@code handler-case}
	 * protected region (the {@code UnwindScope} channel).
	 */
	public static final String HC_DEPTH_DEC_INTERNAL = "%hc-depth-dec";

	/** The {@code and} macro. */
	public static final String AND = "and";

	/** The {@code or} macro. */
	public static final String OR = "or";

	/** The {@code not} built-in function. */
	public static final String NOT = "not";

	/** The {@code when} macro. */
	public static final String WHEN = "when";

	/** The {@code dotimes} macro. */
	public static final String DOTIMES = "dotimes";

	/** The {@code prog1} macro. */
	public static final String PROG1 = "prog1";

	/**
	 * The {@code time} macro (evaluate a form, print the elapsed real time to standard
	 * output, and return the form's value).
	 */
	public static final String TIME = "time";

	/**
	 * The {@code prog2} macro (evaluate the forms in order and return the value of the
	 * second).
	 */
	public static final String PROG2 = "prog2";

	/**
	 * The {@code psetq} macro (parallel assignment: every right-hand side is evaluated
	 * before any variable is assigned).
	 */
	public static final String PSETQ = "psetq";

	/**
	 * The {@code typecase} macro (dispatch on the type of an object using the built-in
	 * type predicates).
	 */
	public static final String TYPECASE = "typecase";

	/** The {@code do} macro (parallel iteration). */
	public static final String DO = "do";

	/**
	 * The {@code do*} macro (sequential iteration; {@code let*}-style bindings/steps).
	 */
	public static final String DO_STAR = "do*";

	/**
	 * The {@code loop} macro. Only the "simple loop" subset is supported (numeric/list
	 * stepping, accumulation, simple control clauses); see
	 * {@link LispMacroExpander#expandLoop} for the exact grammar and limitations.
	 */
	public static final String LOOP = "loop";

	/** The {@code return} special form (non-local exit from the nearest loop block). */
	public static final String RETURN = "return";

	/**
	 * The {@code return-from} macro. Lite: the block name is ignored (there are no named
	 * blocks) -- inside a defun/lambda body it is rewritten to {@code (return value)} and
	 * the body is wrapped in the internal {@code %block}, so it returns from the
	 * function; a {@code return-from} nested inside a {@code do}/{@code loop} exits that
	 * loop's block instead (correct only when the loop is the function's final form). See
	 * {@link am.ik.rontolisp.LambdaLists}.
	 */
	public static final String RETURN_FROM = "return-from";

	/**
	 * The internal {@code %block} special form establishing the {@code return} boundary
	 * that the loop macros ({@code do}/{@code dolist}/{@code dotimes}/{@code loop}) wrap
	 * their expansion in. Not part of the public Lisp API.
	 */
	public static final String BLOCK_INTERNAL = "%block";

	// Type predicates

	/** The {@code null} built-in function. */
	public static final String NULL = "null";

	/** The {@code atom} built-in function. */
	public static final String ATOM = "atom";

	/** The {@code numberp} built-in function. */
	public static final String NUMBERP = "numberp";

	/** The {@code integerp} built-in function. */
	public static final String INTEGERP = "integerp";

	/** The {@code floatp} built-in function. */
	public static final String FLOATP = "floatp";

	/** The {@code rationalp} built-in function. */
	public static final String RATIONALP = "rationalp";

	/** The {@code symbolp} built-in function. */
	public static final String SYMBOLP = "symbolp";

	/** The {@code stringp} built-in function. */
	public static final String STRINGP = "stringp";

	/** The {@code listp} built-in function. */
	public static final String LISTP = "listp";

	/** The {@code consp} built-in function. */
	public static final String CONSP = "consp";

	/** The {@code keywordp} built-in function. */
	public static final String KEYWORDP = "keywordp";

	// Type conversion

	/** The {@code float} built-in function. */
	public static final String FLOAT = "float";

	/** The {@code truncate} built-in function. */
	public static final String TRUNCATE = "truncate";

	/** The {@code floor} built-in function. */
	public static final String FLOOR = "floor";

	/** The {@code ceiling} built-in function. */
	public static final String CEILING = "ceiling";

	/** The {@code round} built-in function. */
	public static final String ROUND = "round";

	/** The {@code numerator} built-in function. */
	public static final String NUMERATOR = "numerator";

	/** The {@code denominator} built-in function. */
	public static final String DENOMINATOR = "denominator";

	// Convenience macros

	/** The {@code 1+} macro. */
	public static final String ONE_PLUS = "1+";

	/** The {@code 1-} macro. */
	public static final String ONE_MINUS = "1-";

	/** The {@code zerop} macro. */
	public static final String ZEROP = "zerop";

	/** The {@code plusp} macro. */
	public static final String PLUSP = "plusp";

	/** The {@code minusp} macro. */
	public static final String MINUSP = "minusp";

	/** The {@code evenp} macro. */
	public static final String EVENP = "evenp";

	/** The {@code oddp} macro. */
	public static final String ODDP = "oddp";

	/** The {@code unless} macro. */
	public static final String UNLESS = "unless";

	/** The {@code first} macro. */
	public static final String FIRST = "first";

	/** The {@code rest} macro. */
	public static final String REST = "rest";

	/** The {@code second} macro. */
	public static final String SECOND = "second";

	/** The {@code third} macro. */
	public static final String THIRD = "third";

	/** The {@code fourth} macro. */
	public static final String FOURTH = "fourth";

	/** The {@code nth} macro. */
	public static final String NTH = "nth";

	// I/O

	/** The {@code print} built-in function. */
	public static final String PRINT = "print";

	/** The {@code prin1} built-in function. */
	public static final String PRIN1 = "prin1";

	/** The {@code princ} built-in function. */
	public static final String PRINC = "princ";

	/** The {@code terpri} built-in function. */
	public static final String TERPRI = "terpri";

	/** The {@code fresh-line} built-in function (newline only if not at line start). */
	public static final String FRESH_LINE = "fresh-line";

	/** The {@code princ-to-string} built-in function. */
	public static final String PRINC_TO_STRING = "princ-to-string";

	/** The {@code prin1-to-string} built-in function. */
	public static final String PRIN1_TO_STRING = "prin1-to-string";

	/** The {@code concatenate} built-in function (only {@code 'string} is supported). */
	public static final String CONCATENATE = "concatenate";

	/** The {@code %string-concat} built-in function. */
	public static final String STRING_CONCAT = "%string-concat";

	/** The {@code read-line} built-in function. */
	public static final String READ_LINE = "read-line";

	/**
	 * The {@code read-char} built-in function (read a single character:
	 * {@code (read-char [stream [eof-error-p [eof-value]]])}). Works on the same stream
	 * handles as {@code read-line} (standard input, file streams and string input
	 * streams).
	 */
	public static final String READ_CHAR = "read-char";

	// String operations

	/** The {@code string} built-in function (string-designator coercion). */
	public static final String STRING = "string";

	/** The {@code string-upcase} built-in function. */
	public static final String STRING_UPCASE = "string-upcase";

	/** The {@code string-downcase} built-in function. */
	public static final String STRING_DOWNCASE = "string-downcase";

	/** The {@code string-capitalize} built-in function. */
	public static final String STRING_CAPITALIZE = "string-capitalize";

	/** The {@code subseq} built-in function (strings only). */
	public static final String SUBSEQ = "subseq";

	/**
	 * The {@code make-string} built-in function ({@code (make-string n &key
	 * initial-element element-type)}). Lowered to a fill loop over {@code concatenate};
	 * {@code element-type} is parsed and ignored (single string representation).
	 */
	public static final String MAKE_STRING = "make-string";

	/**
	 * The {@code replace} built-in function ({@code (replace seq1 seq2 &key start1 end1
	 * start2 end2)}). String-aware; lowered to a {@code concatenate} of the untouched
	 * head/tail of {@code seq1} around the copied region of {@code seq2}. Since strings
	 * are immutable values, it returns a fresh string rather than mutating in place.
	 */
	public static final String REPLACE = "replace";

	/** The {@code string=} built-in function (case-sensitive string equality). */
	public static final String STRING_EQ = "string=";

	/**
	 * The {@code string<} built-in function (case-sensitive lexicographic less-than;
	 * returns the mismatch index or nil). Implemented as a rontolisp-source {@code defun}
	 * shared by every backend (see {@code LispPreludeLibrary}).
	 */
	public static final String STRING_LT = "string<";

	/** The {@code string-equal} built-in function (case-insensitive string equality). */
	public static final String STRING_EQUAL = "string-equal";

	/** The {@code string-trim} built-in function. */
	public static final String STRING_TRIM = "string-trim";

	/** The {@code string-left-trim} built-in function. */
	public static final String STRING_LEFT_TRIM = "string-left-trim";

	/** The {@code string-right-trim} built-in function. */
	public static final String STRING_RIGHT_TRIM = "string-right-trim";

	/** The {@code read} built-in function (interpreter only). */
	public static final String READ = "read";

	/** The {@code read-from-string} built-in function (parse one form from a string). */
	public static final String READ_FROM_STRING = "read-from-string";

	/** The {@code parse-integer} built-in function (parse an integer from a string). */
	public static final String PARSE_INTEGER = "parse-integer";

	/** The {@code functionp} built-in function (is the value a function?). */
	public static final String FUNCTIONP = "functionp";

	/**
	 * The {@code constantp} built-in function (true if the form is a constant object).
	 * Lite: true for self-evaluating objects (numbers, strings, characters, keywords,
	 * {@code t}/{@code nil}) and {@code (quote x)} forms; false otherwise (false
	 * negatives only push work to runtime).
	 */
	public static final String CONSTANTP = "constantp";

	/**
	 * The {@code streamp} built-in function (true if the argument is a stream). Streams
	 * are opaque integer handles across all backends, so this is lowered to
	 * {@code integerp} (lite).
	 */
	public static final String STREAMP = "streamp";

	/**
	 * The internal {@code %arrayp} predicate (is the value an array?). Used by the
	 * {@code vector}/{@code array}/{@code sequence} type specifiers in
	 * {@code check-type}/{@code typecase} tests; not a public function.
	 */
	public static final String ARRAYP_INTERNAL = "%arrayp";

	/**
	 * The {@code vectorp} built-in function (is the value a vector?). Strings are vectors
	 * in CL. Lite: like the {@code vector} type specifier, the rank is not checked (a
	 * rank-n array passes too).
	 */
	public static final String VECTORP = "vectorp";

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
	public static final String MV_SPILL = "%mv-spill";

	// Characters

	/** The {@code char} built-in function (the character at an index of a string). */
	public static final String CHAR = "char";

	/** The {@code schar} built-in function (a synonym for {@code char}). */
	public static final String SCHAR = "schar";

	/**
	 * The {@code %schar-set} internal helper: the {@code (setf (schar s i) c)} /
	 * {@code (setf (char s i) c)} lowering, mutating the string in place and returning
	 * the stored character.
	 */
	public static final String SCHAR_SET = "%schar-set";

	/** The {@code char-code} built-in function (the code point of a character). */
	public static final String CHAR_CODE = "char-code";

	/**
	 * The {@code code-char} built-in function (the character with a given code point).
	 */
	public static final String CODE_CHAR = "code-char";

	/** The {@code char=} built-in function (character equality). */
	public static final String CHAR_EQ = "char=";

	/** The {@code char<} built-in function (character less-than by code point). */
	public static final String CHAR_LT = "char<";

	/**
	 * The {@code char<=} built-in function (character less-than-or-equal by code point).
	 */
	public static final String CHAR_LE = "char<=";

	/** The {@code char-upcase} built-in function (the uppercase form of a character). */
	public static final String CHAR_UPCASE = "char-upcase";

	/**
	 * The {@code char-downcase} built-in function (the lowercase form of a character).
	 */
	public static final String CHAR_DOWNCASE = "char-downcase";

	/** The {@code characterp} built-in function (true if the argument is a character). */
	public static final String CHARACTERP = "characterp";

	/**
	 * The {@code alpha-char-p} built-in function (true if the character is alphabetic).
	 */
	public static final String ALPHA_CHAR_P = "alpha-char-p";

	/**
	 * The {@code lower-case-p} built-in function (true if the character is a lowercase
	 * letter). Lowered to {@code (not (char= c (char-upcase c)))} so it follows the
	 * platform's Unicode case tables.
	 */
	public static final String LOWER_CASE_P = "lower-case-p";

	/**
	 * The {@code upper-case-p} built-in function (true if the character is an uppercase
	 * letter). Lowered to {@code (not (char= c (char-downcase c)))}.
	 */
	public static final String UPPER_CASE_P = "upper-case-p";

	/**
	 * The {@code digit-char-p} built-in function (the weight of a digit character in the
	 * given radix, or nil).
	 */
	public static final String DIGIT_CHAR_P = "digit-char-p";

	/** The {@code :radix} keyword recognized by {@code parse-integer}. */
	public static final String RADIX_KEYWORD = ":radix";

	/** The {@code :junk-allowed} keyword recognized by {@code parse-integer}. */
	public static final String JUNK_ALLOWED_KEYWORD = ":junk-allowed";

	/** The {@code :start} keyword recognized by {@code parse-integer}. */
	public static final String START_KEYWORD = ":start";

	/** The {@code :end} keyword recognized by {@code parse-integer}. */
	public static final String END_KEYWORD = ":end";

	/** The {@code :start1} keyword recognized by {@code replace}. */
	public static final String START1_KEYWORD = ":start1";

	/** The {@code :end1} keyword recognized by {@code replace}. */
	public static final String END1_KEYWORD = ":end1";

	/** The {@code :start2} keyword recognized by {@code replace}. */
	public static final String START2_KEYWORD = ":start2";

	/** The {@code :end2} keyword recognized by {@code replace}. */
	public static final String END2_KEYWORD = ":end2";

	/** The {@code eval} built-in function (interpreter only). */
	public static final String EVAL = "eval";

	/** The {@code load} built-in function (interpreter only). */
	public static final String LOAD = "load";

	/**
	 * The {@code require} built-in function: loads a module once. A runtime function on
	 * the interpreter; a literal, top-level compile-time directive on the compile path
	 * (consumed by {@code LoadInliner}).
	 */
	public static final String REQUIRE = "require";

	/**
	 * The {@code provide} built-in function: marks a module as loaded so a later
	 * {@code require} of the same name is a no-op. A runtime function on the interpreter;
	 * a literal, top-level compile-time directive on the compile path (consumed by
	 * {@code LoadInliner}).
	 */
	public static final String PROVIDE = "provide";

	// File I/O

	/** The {@code open} built-in function. */
	public static final String OPEN = "open";

	/** The {@code close} built-in function. */
	public static final String CLOSE = "close";

	/** The {@code write-line} built-in function. */
	public static final String WRITE_LINE = "write-line";

	/** The {@code with-open-file} macro. */
	public static final String WITH_OPEN_FILE = "with-open-file";

	/**
	 * The {@code unwind-protect} special form: runs cleanup forms on every exit from the
	 * protected form (normal return, {@code error} unwind, {@code return}/
	 * {@code return-from}). Interpreter and JVM backends only; a WASM error is an
	 * uncatchable trap, so the WASM compilers reject it.
	 */
	public static final String UNWIND_PROTECT = "unwind-protect";

	/** The {@code :direction} keyword recognized by {@code with-open-file}. */
	public static final String DIRECTION_KEYWORD = ":direction";

	/** The {@code :initial-value} keyword recognized by {@code reduce}. */
	public static final String INITIAL_VALUE_KEYWORD = ":initial-value";

	/**
	 * The {@code :test} keyword recognized by {@code member} (the equality predicate).
	 */
	public static final String TEST_KEYWORD = ":test";

	/**
	 * The {@code :key} keyword recognized by the sequence and alist functions (a selector
	 * applied to each element before the equality test).
	 */
	public static final String KEY_KEYWORD = ":key";

	/**
	 * The {@code :test-not} keyword recognized by {@code position} (the negated equality
	 * predicate).
	 */
	public static final String TEST_NOT_KEYWORD = ":test-not";

	/**
	 * The {@code :from-end} keyword recognized by the {@code position} family (when true,
	 * the index of the last match is returned).
	 */
	public static final String FROM_END_KEYWORD = ":from-end";

	/** The {@code :input} keyword (open a file for reading). */
	public static final String INPUT_KEYWORD = ":input";

	/** The {@code :output} keyword (open a file for writing). */
	public static final String OUTPUT_KEYWORD = ":output";

	/**
	 * The {@code :element-type} keyword recognized by {@code with-open-file} (and as the
	 * optional third {@code open} argument): the literal {@code '(unsigned-byte 8)}
	 * selects a binary stream, the literal {@code 'character} the default text stream.
	 */
	public static final String ELEMENT_TYPE_KEYWORD = ":element-type";

	/** The {@code unsigned-byte} type specifier symbol used in {@code :element-type}. */
	public static final String UNSIGNED_BYTE = "unsigned-byte";

	/** The {@code character} type specifier symbol used in {@code :element-type}. */
	public static final String CHARACTER_TYPE = "character";

	/**
	 * The {@code double-float} type specifier symbol. As the {@code :element-type} of
	 * {@code make-array} (and the element type of a {@code #d(...)} literal) it selects
	 * the packed {@link am.ik.rontolisp.LispDoubleFloatArray} representation.
	 */
	public static final String DOUBLE_FLOAT = "double-float";

	/**
	 * The {@code single-float} type specifier symbol. As the {@code :element-type} of
	 * {@code make-array} (and the element type of a {@code #f(...)} literal) it selects
	 * the packed {@link am.ik.rontolisp.LispSingleFloatArray} representation (f32
	 * backing; scalars still read/write as {@code double}, widening on read and narrowing
	 * on write).
	 */
	public static final String SINGLE_FLOAT = "single-float";

	/** The {@code read-byte} built-in function (binary streams only). */
	public static final String READ_BYTE = "read-byte";

	/** The {@code write-byte} built-in function (binary streams only). */
	public static final String WRITE_BYTE = "write-byte";

	/** The {@code read-sequence} macro (fills a vector from a binary stream). */
	public static final String READ_SEQUENCE = "read-sequence";

	/** The {@code write-sequence} macro (writes a vector to a binary stream). */
	public static final String WRITE_SEQUENCE = "write-sequence";

	// String streams

	/** The {@code write-string} built-in function (a string to a stream, no newline). */
	public static final String WRITE_STRING = "write-string";

	/**
	 * The {@code write-char} operator: writes a single character, expanding to
	 * {@code write-string} of its one-character string on every backend.
	 */
	public static final String WRITE_CHAR = "write-char";

	/**
	 * The {@code write-to-string} built-in function (a {@code prin1-to-string} alias).
	 */
	public static final String WRITE_TO_STRING = "write-to-string";

	/** The {@code with-output-to-string} macro (collect output into a string). */
	public static final String WITH_OUTPUT_TO_STRING = "with-output-to-string";

	/** The {@code with-input-from-string} macro (read from a string as a stream). */
	public static final String WITH_INPUT_FROM_STRING = "with-input-from-string";

	/** The internal {@code %make-string-output-stream} helper (string-builder stream). */
	public static final String MAKE_STRING_OUTPUT_STREAM = "%make-string-output-stream";

	/** The internal {@code %make-string-input-stream} helper (read from a string). */
	public static final String MAKE_STRING_INPUT_STREAM = "%make-string-input-stream";

	/**
	 * The internal {@code %string-stream-contents} helper (the string accumulated by a
	 * {@code %make-string-output-stream} stream).
	 */
	public static final String STRING_STREAM_CONTENTS = "%string-stream-contents";

	// Packages

	/** The {@code in-package} directive that switches the current package. */
	public static final String IN_PACKAGE = "in-package";

	/**
	 * The {@code defpackage} directive that defines a new package. Like
	 * {@code in-package}, it is a literal, top-level, read/compile-time directive
	 * consumed by the {@code PackageResolver}.
	 */
	public static final String DEFPACKAGE = "defpackage";

	/**
	 * Internal marker inserted by {@code LoadInliner} before the spliced forms of a
	 * loaded file: it makes the {@code PackageResolver} save the current package so a
	 * file's internal {@code in-package} cannot leak past the load, mirroring Common Lisp
	 * binding {@code *package*} around a {@code load}. Consumed by the resolver, never
	 * reaching the backends. Paired with {@link #POP_PACKAGE}.
	 */
	public static final String PUSH_PACKAGE = "%push-package";

	/**
	 * Internal marker inserted by {@code LoadInliner} after the spliced forms of a loaded
	 * file: it makes the {@code PackageResolver} restore the package saved by the
	 * matching {@link #PUSH_PACKAGE}. Consumed by the resolver, never reaching the
	 * backends.
	 */
	public static final String POP_PACKAGE = "%pop-package";

	/** The {@code :use} clause keyword of {@code defpackage}. */
	public static final String USE_KEYWORD = ":use";

	/** The {@code :export} clause keyword of {@code defpackage}. */
	public static final String EXPORT_KEYWORD = ":export";

	/** The {@code :nicknames} clause keyword of {@code defpackage}. */
	public static final String NICKNAMES_KEYWORD = ":nicknames";

	/** The {@code :import-from} clause keyword of {@code defpackage}. */
	public static final String IMPORT_FROM_KEYWORD = ":import-from";

	/** The {@code *package*} variable holding the current package name. */
	public static final String PACKAGE_VAR = "*package*";

	/**
	 * The {@code *read-default-float-format*} variable. Every float shares the one double
	 * representation, so its value ({@code double-float}) is informational: it exists so
	 * library code that reads it (or rebinds it through a keyword argument, lexically
	 * here) loads and runs.
	 */
	public static final String READ_DEFAULT_FLOAT_FORMAT = "*read-default-float-format*";

	/**
	 * The {@code values-list} built-in function (spread a list as multiple values through
	 * the {@code %mv-spill} channel).
	 */
	public static final String VALUES_LIST = "values-list";

	/**
	 * The {@code complex} operator. Lite: no complex representation exists, so a zero
	 * imaginary part yields the real part and anything else signals (classified as a
	 * macro here, in CL it is a function).
	 */
	public static final String COMPLEX = "complex";

	/**
	 * The {@code *features*} variable, substituted at read time with the active feature
	 * list (see {@code reader.Features}).
	 */
	public static final String FEATURES_VAR = "*features*";

	/** The {@code version} function provided by the {@code rontolisp} package. */
	public static final String VERSION = "version";

	/** The {@code list-functions} function provided by the {@code rontolisp} package. */
	public static final String LIST_FUNCTIONS = "list-functions";

	/** The {@code list-macros} function provided by the {@code rontolisp} package. */
	public static final String LIST_MACROS = "list-macros";

	/**
	 * The {@code list-special-forms} function provided by the {@code rontolisp} package.
	 */
	public static final String LIST_SPECIAL_FORMS = "list-special-forms";

	/**
	 * The {@code fetch} function provided by the {@code rontolisp} package. Starts an
	 * outgoing HTTP request (JavaScript {@code fetch}-style) and immediately returns a
	 * <em>future</em> while the request runs asynchronously. The optional second argument
	 * is an options property list ({@code :method}, {@code :headers}, {@code :body}).
	 * Awaiting the future yields the result property list
	 * {@code (:status <int> :headers <alist> :body <stream>)} whose body is a stream of
	 * string chunks ({@code rontolisp:read-all} drains it).
	 */
	public static final String FETCH = "fetch";

	/**
	 * The {@code await} special form provided by the {@code rontolisp} package. Legal
	 * inside {@code rontolisp:async-defun}/{@code async-lambda} bodies and at top level
	 * (the top level is implicitly asynchronous). Given a future, suspends the current
	 * asynchronous function until it settles and returns its value; a future that settled
	 * with an error re-signals that condition. A settled future never suspends, nested
	 * futures are flattened, and any other value is returned unchanged, like a JavaScript
	 * {@code await} on a non-promise.
	 */
	public static final String AWAIT = "await";

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
	public static final String ASYNC = "async";

	/**
	 * The {@code async-defun} special form provided by the {@code rontolisp} package.
	 * Defines an asynchronous function: same surface as {@code defun} (full lambda-list
	 * support), but calling it runs the body only until its first suspension point (an
	 * {@code rontolisp:await} of an unsettled future) and returns a future that settles
	 * with the body's value (or its error).
	 */
	public static final String ASYNC_DEFUN = "async-defun";

	/**
	 * The {@code async-lambda} special form provided by the {@code rontolisp} package.
	 * The anonymous counterpart of {@code rontolisp:async-defun}: evaluates to a function
	 * value whose invocation returns a future.
	 */
	public static final String ASYNC_LAMBDA = "async-lambda";

	/**
	 * The {@code futurep} predicate provided by the {@code rontolisp} package. Returns
	 * {@code t} if the argument is a future (as returned by calling an
	 * {@code rontolisp:async-defun} function or {@code rontolisp:fetch}), {@code nil}
	 * otherwise.
	 */
	public static final String FUTUREP = "futurep";

	/**
	 * The {@code streamp} predicate provided by the {@code rontolisp} package. Returns
	 * {@code t} if the argument is an asynchronous stream value (as returned by
	 * {@code rontolisp:make-stream} or carried in a {@code rontolisp:fetch} response
	 * body), {@code nil} otherwise. The same spelling as the {@code cl:streamp}
	 * file-stream predicate ({@link #STREAMP}) but a different symbol: the packages
	 * disambiguate, and each predicate answers {@code nil} for the other's streams.
	 */
	public static final String ASYNC_STREAMP = "streamp";

	/**
	 * The {@code make-stream} function provided by the {@code rontolisp} package. Creates
	 * a fresh open asynchronous stream; one value owns both the read and the write end.
	 * Producers append chunks with {@code rontolisp:stream-write} and finish with
	 * {@code rontolisp:stream-close}; consumers take chunks with
	 * {@code rontolisp:stream-read}.
	 */
	public static final String MAKE_STREAM = "make-stream";

	/**
	 * The {@code stream-read} function provided by the {@code rontolisp} package. Returns
	 * a future that settles to the stream's next chunk, or {@code nil} once the stream is
	 * closed and drained (end of stream). Chunks are never {@code nil}.
	 */
	public static final String STREAM_READ = "stream-read";

	/**
	 * The {@code stream-write} function provided by the {@code rontolisp} package.
	 * Appends a chunk to a stream and returns a future that settles when the stream has
	 * accepted it, so producers can be flow-controlled with {@code rontolisp:await}.
	 */
	public static final String STREAM_WRITE = "stream-write";

	/**
	 * The {@code stream-close} function provided by the {@code rontolisp} package. Closes
	 * a stream's write end: pending and future reads drain the remaining chunks and then
	 * observe end of stream.
	 */
	public static final String STREAM_CLOSE = "stream-close";

	/**
	 * The {@code read-all} function provided by the {@code rontolisp} package. Returns a
	 * future that settles to the concatenation of all remaining string chunks of a stream
	 * (an error is signaled for a non-string chunk).
	 */
	public static final String READ_ALL = "read-all";

	/**
	 * The {@code wait-for} function provided by the {@code rontolisp} package. Returns a
	 * future that settles (to {@code nil}) after the given number of milliseconds -- the
	 * timer primitive of the async/await surface, mirroring WASI 0.3's
	 * {@code wasi:clocks/monotonic-clock.wait-for}. Deliberately NOT named {@code sleep}:
	 * Common Lisp's {@code sleep} is a blocking function taking seconds, while this one
	 * only starts a timer ({@code (rontolisp:await (rontolisp:wait-for
	 * 500))} is the sleeping form).
	 */
	public static final String WAIT_FOR = "wait-for";

	/**
	 * The internal {@code %async-run} primitive backing the
	 * {@code rontolisp:async-defun}/{@code async-lambda} lowering on the interpreter, JVM
	 * and Preview-1 WASM backends: takes a zero-argument function value, runs it under
	 * the backend's asynchronous mechanism and returns the resulting future. The
	 * {@code --component} backend compiles the async defining forms natively (state
	 * machines) and never sees this name.
	 */
	public static final String ASYNC_RUN = "%async-run";

	/**
	 * The internal {@code rontolisp::%future-new} test primitive of the
	 * {@code --component} async state machines: a fresh PENDING first-class future.
	 * Undocumented; it exists so the suspension machinery (spill/restore, waiter cascade)
	 * is exercisable end-to-end before the Phase-8 import layer produces pending futures
	 * of its own.
	 */
	public static final String FUTURE_NEW_INTERNAL = "%future-new";

	/**
	 * The internal {@code rontolisp::%future-settle} test primitive: settles a pending
	 * future with a value, resuming its waiters. See {@link #FUTURE_NEW_INTERNAL}.
	 */
	public static final String FUTURE_SETTLE_INTERNAL = "%future-settle";

	/**
	 * The internal {@code rontolisp::%future-reject} test primitive: rejects a pending
	 * future with a message (a {@code simple-error} re-signals at await). See
	 * {@link #FUTURE_NEW_INTERNAL}.
	 */
	public static final String FUTURE_REJECT_INTERNAL = "%future-reject";

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
	public static final String SUBTASK_FUTURE_INTERNAL = "%subtask-future";

	/**
	 * The internal {@code rontolisp::%wasi-stream-new} primitive of the
	 * {@code --component} async import layer: takes a read thunk and a close thunk
	 * (arity-0 function values over a wasi byte-stream handle) and returns a first-class
	 * stream value ({@code TYPE_WASI_STREAM}) that
	 * {@code rontolisp:stream-read}/{@code stream-close}/{@code streamp} operate on.
	 * Synthesized by http.lisp for request/response bodies; component-only.
	 */
	public static final String WASI_STREAM_NEW_INTERNAL = "%wasi-stream-new";

	/**
	 * The internal {@code rontolisp::%future-force} primitive of the {@code --component}
	 * synchronous surface: blocks on the module scheduler ({@code _sched_loop}) until the
	 * given future settles and yields its value (a rejection re-signals, like await). It
	 * is what lets a synchronous built-in surface (the tcp-* wrappers in sockets.lisp)
	 * sit on an asynchronous WIT import; async bodies get the await-shaped promotion
	 * instead. Component-only.
	 */
	public static final String FUTURE_FORCE_INTERNAL = "%future-force";

	/**
	 * The internal {@code rontolisp::%read-line-raw}/{@code %read-char-raw}/
	 * {@code %read-byte-raw}/{@code %write-line-raw}/{@code %write-byte-raw}/
	 * {@code %close-raw} aliases of the NATIVE stream built-ins on the
	 * {@code --component} backend: the socket-dispatch defuns sockets.lisp splices
	 * ({@code %io-read-line} &amp;c) fall back through these for a non-socket handle, so
	 * the compile-time socket rewrite of the public names cannot recurse. Component-only.
	 */
	public static final String READ_LINE_RAW_INTERNAL = "%read-line-raw";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String READ_CHAR_RAW_INTERNAL = "%read-char-raw";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String READ_BYTE_RAW_INTERNAL = "%read-byte-raw";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String WRITE_LINE_RAW_INTERNAL = "%write-line-raw";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String WRITE_BYTE_RAW_INTERNAL = "%write-byte-raw";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String WRITE_STRING_RAW_INTERNAL = "%write-string-raw";

	/** See {@link #READ_LINE_RAW_INTERNAL}. */
	public static final String CLOSE_RAW_INTERNAL = "%close-raw";

	/**
	 * The {@code json-parse} function provided by the {@code rontolisp} package. Parses a
	 * JSON document string into Lisp values (JavaScript {@code JSON.parse}-style). The
	 * optional second argument selects the object representation: {@code :plist} (the
	 * default; object keys become keywords) or {@code :hash-table} (object keys stay
	 * strings).
	 */
	public static final String JSON_PARSE = "json-parse";

	/**
	 * The {@code json-stringify} function provided by the {@code rontolisp} package.
	 * Serializes a Lisp value into a JSON document string (JavaScript
	 * {@code JSON.stringify}-style); accepts both the plist and the hash-table object
	 * representations produced by {@code rontolisp:json-parse}.
	 */
	public static final String JSON_STRINGIFY = "json-stringify";

	/**
	 * The {@code url-decode} function provided by the {@code rontolisp} package. Decodes
	 * a percent-encoded (URL-encoded) string: {@code %XX} byte sequences are decoded as
	 * UTF-8 and {@code +} becomes a space.
	 */
	public static final String URL_DECODE = "url-decode";

	/**
	 * The {@code url-encode} function provided by the {@code rontolisp} package. Encodes
	 * a string for use in a URL: unreserved characters (letters, digits, {@code -},
	 * {@code _}, {@code .}, {@code ~}) pass through and everything else becomes
	 * percent-encoded UTF-8 bytes (a space becomes {@code %20}).
	 */
	public static final String URL_ENCODE = "url-encode";

	/**
	 * The {@code query-params} function provided by the {@code rontolisp} package. Parses
	 * a query string such as {@code "a=1&b=two&flag"} into an alist of
	 * {@code (key . value)} string pairs with keys and values url-decoded, duplicates
	 * preserved in order; {@code nil} yields {@code nil}.
	 */
	public static final String QUERY_PARAMS = "query-params";

	/**
	 * The {@code query-param} function provided by the {@code rontolisp} package. Returns
	 * the url-decoded value of the first match of a name in a query string, or
	 * {@code nil}; {@code nil}-safe in the query argument.
	 */
	public static final String QUERY_PARAM = "query-param";

	/**
	 * The {@code url-path} function provided by the {@code rontolisp} package. Returns
	 * the part of a URL or request-target string before the first {@code ?}.
	 */
	public static final String URL_PATH = "url-path";

	/**
	 * The {@code url-query} function provided by the {@code rontolisp} package. Returns
	 * the raw query-string part of a URL or request-target string (after the first
	 * {@code ?}, possibly empty), or {@code nil} when there is no {@code ?}.
	 */
	public static final String URL_QUERY = "url-query";

	/**
	 * The {@code tcp-connect} function provided by the {@code rontolisp} package. Opens a
	 * blocking TCP connection to {@code host} (a hostname or IP literal; the WASM
	 * component backend supports IPv4 literals only) and {@code port}, and returns a
	 * bidirectional stream handle usable with {@code read-line}, {@code write-line},
	 * {@code read-byte}, {@code write-byte} and {@code close}.
	 */
	public static final String TCP_CONNECT = "tcp-connect";

	/**
	 * The {@code tcp-listen} function provided by the {@code rontolisp} package. Binds a
	 * listening TCP socket on {@code port} (0 picks an ephemeral port, see
	 * {@code rontolisp:tcp-local-port}) and an optional {@code host} (default: all
	 * interfaces) and returns a listener handle for {@code rontolisp:tcp-accept} /
	 * {@code close}.
	 */
	public static final String TCP_LISTEN = "tcp-listen";

	/**
	 * The {@code tcp-accept} function provided by the {@code rontolisp} package. Blocks
	 * until a client connects to the given listener handle and returns a bidirectional
	 * stream handle for the accepted connection (same stream operations as
	 * {@code rontolisp:tcp-connect}).
	 */
	public static final String TCP_ACCEPT = "tcp-accept";

	/**
	 * The {@code tcp-local-port} function provided by the {@code rontolisp} package.
	 * Returns the local port number bound to a listener or socket handle (useful after
	 * listening on port 0).
	 */
	public static final String TCP_LOCAL_PORT = "tcp-local-port";

	/**
	 * The {@code tcp-local-address} function provided by the {@code rontolisp} package.
	 * Returns the local/bound IP address of a listener or socket handle as a string.
	 * Interpreter and JVM backends only for a real value; the WASM component backend
	 * returns {@code nil}.
	 */
	public static final String TCP_LOCAL_ADDRESS = "tcp-local-address";

	/**
	 * The {@code tcp-peer-address} function provided by the {@code rontolisp} package.
	 * Returns the remote IP address of a connected socket handle as a string. Interpreter
	 * and JVM backends only for a real value; the WASM component backend returns
	 * {@code nil}.
	 */
	public static final String TCP_PEER_ADDRESS = "tcp-peer-address";

	/**
	 * The {@code tcp-peer-port} function provided by the {@code rontolisp} package.
	 * Returns the remote port number of a connected socket handle. Interpreter and JVM
	 * backends only for a real value; the WASM component backend returns {@code nil}.
	 */
	public static final String TCP_PEER_PORT = "tcp-peer-port";

	/**
	 * The {@code tls-connect} function provided by the {@code rontolisp} package. Opens a
	 * blocking TCP connection to {@code host} and {@code port}, performs a TLS handshake
	 * (the server certificate is validated against the JDK default trust store and the
	 * hostname is verified), and returns a bidirectional stream handle usable with
	 * {@code read-line}, {@code write-line}, {@code read-byte}, {@code write-byte} and
	 * {@code close}. Interpreter and JVM backends only; the WASM backend has no TLS host
	 * support.
	 */
	public static final String TLS_CONNECT = "tls-connect";

	/**
	 * The {@code tls-listen} function provided by the {@code rontolisp} package. Binds a
	 * listening TLS socket serving the certificate from a PKCS12 keystore file:
	 * {@code (tls-listen keystore password port &optional host)}. The listener handle
	 * works with {@code rontolisp:tcp-accept}, {@code rontolisp:tcp-local-port} and
	 * {@code close}; an accepted connection performs its TLS handshake on the first
	 * read/write. Interpreter and JVM backends only; the WASM backend has no TLS host
	 * support.
	 */
	public static final String TLS_LISTEN = "tls-listen";

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
	public static final String TLS_LISTEN_PEM = "tls-listen-pem";

	/**
	 * Internal helper the {@code tls-listen-pem} compile-time inliner rewrites to: binds
	 * a TLS listener from an in-memory PKCS12 keystore passed as a Base64 string
	 * ({@code (%tls-listen-p12 base64 password port &optional host)}). Not part of the
	 * public API -- programs call {@code tls-listen-pem}.
	 */
	public static final String TLS_LISTEN_P12 = "%tls-listen-p12";

	/** The {@code cl} package name (standard functions, macros and variables). */
	public static final String CL_PKG = "cl";

	/** The {@code cl-user} package name (default working package, uses {@code cl}). */
	public static final String CL_USER_PKG = "cl-user";

	/** The {@code rontolisp} package name (does not use {@code cl}). */
	public static final String RONTOLISP_PKG = "rontolisp";

	/**
	 * The {@code linalg} package name (numpy-style vector/matrix operations). Like the
	 * JSON functions, the package is implemented once in rontolisp itself
	 * ({@code linalg.lisp}, see {@code LinalgLibrary}) so a single implementation runs on
	 * every backend; the exported function names live in
	 * {@code PackageRegistry#linalgFunctionNames()}.
	 */
	public static final String LINALG_PKG = "linalg";

	// The linalg members an --simd build intercepts (see .kb/linalg-simd.md). The other
	// exported names exist only as linalg.lisp defuns, so PackageRegistry keeps them as
	// bare strings; these fifteen are dispatched on by name in three interceptors
	// (eval.LinalgSimd, codegen.jvm.JvmLinalgSimdCompiler,
	// codegen.wasm.WasmLinalgSimdCompiler) and so need constants.

	/** {@code linalg:add}: element-wise {@code a + b}; either operand may be a scalar. */
	public static final String LINALG_ADD = "add";

	/** {@code linalg:sub}: element-wise {@code a - b}; either operand may be a scalar. */
	public static final String LINALG_SUB = "sub";

	/**
	 * {@code linalg:mul}: element-wise (Hadamard) {@code a * b}, NOT a matrix product;
	 * either operand may be a scalar.
	 */
	public static final String LINALG_MUL = "mul";

	/** {@code linalg:div}: element-wise {@code a / b}; either operand may be a scalar. */
	public static final String LINALG_DIV = "div";

	/** {@code linalg:sum}: the sum of every element, at any rank. */
	public static final String LINALG_SUM = "sum";

	/** {@code linalg:amax}: the largest element, at any rank. */
	public static final String LINALG_AMAX = "amax";

	/** {@code linalg:amin}: the smallest element, at any rank. */
	public static final String LINALG_AMIN = "amin";

	/** {@code linalg:norm}: the Euclidean (L2 / Frobenius) norm. */
	public static final String LINALG_NORM = "norm";

	/**
	 * {@code linalg:dot}: the numpy dispatch -- vector.vector to a scalar, matrix.vector
	 * and vector.matrix to a vector, matrix.matrix to a matrix.
	 */
	public static final String LINALG_DOT = "dot";

	/**
	 * {@code linalg:outer}: the outer product of two vectors (inputs flattened first).
	 */
	public static final String LINALG_OUTER = "outer";

	/** {@code linalg:transpose}: a matrix transpose; a vector is returned unchanged. */
	public static final String LINALG_TRANSPOSE = "transpose";

	/** {@code linalg:trace}: the main-diagonal sum of a square matrix. */
	public static final String LINALG_TRACE = "trace";

	/** {@code linalg:argmax}: the index of the largest element of a vector. */
	public static final String LINALG_ARGMAX = "argmax";

	/** {@code linalg:argmin}: the index of the smallest element of a vector. */
	public static final String LINALG_ARGMIN = "argmin";

	/** {@code linalg:reshape}: a fresh array of the given shape, row-major elements. */
	public static final String LINALG_RESHAPE = "reshape";

	/** {@code linalg:exp}: element-wise {@code e^x} (numpy {@code np.exp}). */
	public static final String LINALG_EXP = "exp";

	/** {@code linalg:log}: element-wise natural log (numpy {@code np.log}). */
	public static final String LINALG_LOG = "log";

	/** {@code linalg:tanh}: element-wise hyperbolic tangent (numpy {@code np.tanh}). */
	public static final String LINALG_TANH = "tanh";

	/** {@code linalg:sin}: element-wise sine (numpy {@code np.sin}). */
	public static final String LINALG_SIN = "sin";

	/** {@code linalg:cos}: element-wise cosine (numpy {@code np.cos}). */
	public static final String LINALG_COS = "cos";

	/** {@code linalg:tan}: element-wise tangent (numpy {@code np.tan}). */
	public static final String LINALG_TAN = "tan";

	/** {@code linalg:asin}: element-wise arc sine (numpy {@code np.arcsin}). */
	public static final String LINALG_ASIN = "asin";

	/** {@code linalg:acos}: element-wise arc cosine (numpy {@code np.arccos}). */
	public static final String LINALG_ACOS = "acos";

	/** {@code linalg:atan}: element-wise arc tangent (numpy {@code np.arctan}). */
	public static final String LINALG_ATAN = "atan";

	/** {@code linalg:sinh}: element-wise hyperbolic sine (numpy {@code np.sinh}). */
	public static final String LINALG_SINH = "sinh";

	/** {@code linalg:cosh}: element-wise hyperbolic cosine (numpy {@code np.cosh}). */
	public static final String LINALG_COSH = "cosh";

	/** {@code linalg:sqrt}: element-wise square root (numpy {@code np.sqrt}). */
	public static final String LINALG_SQRT = "sqrt";

	/** {@code linalg:abs}: element-wise absolute value (numpy {@code np.abs}). */
	public static final String LINALG_ABS = "abs";

	/** {@code linalg:negative}: element-wise negation (numpy {@code np.negative}). */
	public static final String LINALG_NEGATIVE = "negative";

	/** {@code linalg:sign}: element-wise {@code signum} (numpy {@code np.sign}). */
	public static final String LINALG_SIGN = "sign";

	/**
	 * {@code linalg:maximum}: element-wise larger of two operands (numpy
	 * {@code np.maximum}); either operand may be a scalar. Defined by the strict
	 * comparison {@code (if (> x y) x y)}, so the second operand wins whenever the first
	 * is not strictly greater (ties, and unordered {@code NaN} comparisons).
	 */
	public static final String LINALG_MAXIMUM = "maximum";

	/**
	 * {@code linalg:minimum}: element-wise smaller of two operands (numpy
	 * {@code np.minimum}); either operand may be a scalar. Defined by
	 * {@code (if (< x y) x y)}, the mirror of {@link #LINALG_MAXIMUM}.
	 */
	public static final String LINALG_MINIMUM = "minimum";

	/**
	 * {@code linalg:clip}: element-wise {@code min(max(x, lo), hi)} (numpy
	 * {@code np.clip} with scalar bounds), defined as the composition
	 * {@code (linalg:minimum (linalg:maximum a lo) hi)}.
	 */
	public static final String LINALG_CLIP = "clip";

	/**
	 * {@code linalg:relu}: element-wise {@code max(x, 0.0)}, defined as
	 * {@code (linalg:maximum a 0.0)}.
	 */
	public static final String LINALG_RELU = "relu";

	/**
	 * {@code linalg::%la-im2col} (INTERNAL, note the double colon): unfolds a rank-4 NCHW
	 * array into the {@code (N*out-h*out-w, C*fh*fw)} window matrix behind the CNN
	 * examples (Deep Learning from Scratch {@code common/util.py}). Pure index
	 * arithmetic, intercepted under {@code --simd} because it dominates the accelerated
	 * convolution runs.
	 */
	public static final String LINALG_IM2COL = "%la-im2col";

	/**
	 * {@code linalg::%la-col2im} (INTERNAL): the {@code %la-im2col} adjoint --
	 * scatter-adds the window matrix back into a fresh zero rank-4 NCHW array
	 * (overlapping windows accumulate; the convolution backward pass).
	 */
	public static final String LINALG_COL2IM = "%la-col2im";

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
	public static final String VEC_PKG = "vec";

	/** {@code vec:zeros}: a fresh length-n vector of {@code 0.0}. */
	public static final String VEC_ZEROS = "zeros";

	/** {@code vec:ones}: a fresh length-n vector of {@code 1.0}. */
	public static final String VEC_ONES = "ones";

	/**
	 * {@code vec:arange}: a fresh vector {@code [0.0, 1.0, ..., n-1]} (numpy name, as in
	 * {@code linalg}).
	 */
	public static final String VEC_ARANGE = "arange";

	/**
	 * {@code vec:from-list}: a fresh vector from a Lisp list of numbers (portable
	 * backends only).
	 */
	public static final String VEC_FROM_LIST = "from-list";

	/**
	 * {@code vec:to-list}: a Lisp list of a vector's elements (portable backends only).
	 */
	public static final String VEC_TO_LIST = "to-list";

	/**
	 * {@code vec:aref}: read one element of a vector; a setf place via {@code vec:aset}.
	 */
	public static final String VEC_AREF = "aref";

	/**
	 * {@code vec:aset}: write one element of a vector, returning the stored value (the
	 * {@code setf} writer).
	 */
	public static final String VEC_ASET = "aset";

	/** {@code vec:length}: the element count of a vector. */
	public static final String VEC_LENGTH = "length";

	/** {@code vec:add}: element-wise {@code a + b} into a fresh vector. */
	public static final String VEC_ADD = "add";

	/** {@code vec:sub}: element-wise {@code a - b} into a fresh vector. */
	public static final String VEC_SUB = "sub";

	/** {@code vec:mul}: element-wise (Hadamard) {@code a * b} into a fresh vector. */
	public static final String VEC_MUL = "mul";

	/** {@code vec:scale}: {@code v * s} (scalar broadcast) into a fresh vector. */
	public static final String VEC_SCALE = "scale";

	/** {@code vec:sum}: horizontal sum of a vector, a scalar. */
	public static final String VEC_SUM = "sum";

	/** {@code vec:mean}: arithmetic mean of a vector, a scalar. */
	public static final String VEC_MEAN = "mean";

	/** {@code vec:dot}: dot product of two vectors, a scalar. */
	public static final String VEC_DOT = "dot";

	/** {@code vec:norm}: Euclidean norm {@code sqrt(dot(v, v))}, a scalar. */
	public static final String VEC_NORM = "norm";

	/**
	 * {@code vec:matvec}: GEMV -- a rank-2 matrix {@code W(d, n)} times a rank-1 vector
	 * {@code x(n)}, yielding a rank-1 vector {@code y(d)} with
	 * {@code y[i] = dot(row_i, x)}.
	 */
	public static final String VEC_MATVEC = "matvec";

	/**
	 * {@code vec:add-into}: element-wise {@code a + b} written into {@code out}, which is
	 * returned. The destination-passing sibling of {@link #VEC_ADD}: it allocates
	 * nothing, so a loop over it keeps the bump-allocated linear heap of the WASM
	 * backends flat. {@code out} may alias {@code a} and/or {@code b} (element {@code i}
	 * depends only on element {@code i}).
	 */
	public static final String VEC_ADD_INTO = "add-into";

	/** {@code vec:sub-into}: element-wise {@code a - b} into {@code out}. */
	public static final String VEC_SUB_INTO = "sub-into";

	/** {@code vec:mul-into}: element-wise {@code a * b} into {@code out}. */
	public static final String VEC_MUL_INTO = "mul-into";

	/** {@code vec:scale-into}: {@code v * s} (scalar broadcast) into {@code out}. */
	public static final String VEC_SCALE_INTO = "scale-into";

	/**
	 * {@code vec:matvec-into}: GEMV written into {@code out}. Unlike the element-wise
	 * kernels, {@code out[i]} depends on every element of {@code x}, so {@code out} must
	 * not be {@code eq} to {@code x} (nor to {@code w}); the call signals otherwise.
	 */
	public static final String VEC_MATVEC_INTO = "matvec-into";

	// The element-wise unary ufuncs. Each has a fresh-vector form and a
	// destination-passing -into sibling; out MAY alias the operand (element i depends
	// only
	// on element i, the add-into rule).

	/** {@code vec:exp}: element-wise {@code e^x} into a fresh vector. */
	public static final String VEC_EXP = "exp";

	/** {@code vec:log}: element-wise natural log into a fresh vector. */
	public static final String VEC_LOG = "log";

	/** {@code vec:tanh}: element-wise hyperbolic tangent into a fresh vector. */
	public static final String VEC_TANH = "tanh";

	/** {@code vec:sin}: element-wise sine into a fresh vector. */
	public static final String VEC_SIN = "sin";

	/** {@code vec:cos}: element-wise cosine into a fresh vector. */
	public static final String VEC_COS = "cos";

	/** {@code vec:tan}: element-wise tangent into a fresh vector. */
	public static final String VEC_TAN = "tan";

	/** {@code vec:asin}: element-wise arc sine into a fresh vector. */
	public static final String VEC_ASIN = "asin";

	/** {@code vec:acos}: element-wise arc cosine into a fresh vector. */
	public static final String VEC_ACOS = "acos";

	/** {@code vec:atan}: element-wise arc tangent into a fresh vector. */
	public static final String VEC_ATAN = "atan";

	/** {@code vec:sinh}: element-wise hyperbolic sine into a fresh vector. */
	public static final String VEC_SINH = "sinh";

	/** {@code vec:cosh}: element-wise hyperbolic cosine into a fresh vector. */
	public static final String VEC_COSH = "cosh";

	/** {@code vec:sqrt}: element-wise square root into a fresh vector. */
	public static final String VEC_SQRT = "sqrt";

	/** {@code vec:abs}: element-wise absolute value into a fresh vector. */
	public static final String VEC_ABS = "abs";

	/** {@code vec:square}: element-wise {@code x * x} into a fresh vector. */
	public static final String VEC_SQUARE = "square";

	/** {@code vec:negative}: element-wise negation into a fresh vector. */
	public static final String VEC_NEGATIVE = "negative";

	/** {@code vec:sign}: element-wise {@code signum} into a fresh vector. */
	public static final String VEC_SIGN = "sign";

	/** {@code vec:reciprocal}: element-wise {@code 1 / x} into a fresh vector. */
	public static final String VEC_RECIPROCAL = "reciprocal";

	// The comparison-select ufuncs. All are defined by the strict comparison the linalg:
	// siblings state ((if (> x y) x y) and its mirrors), so the second operand / the
	// bound
	// wins whenever the comparison is false -- including unordered NaN comparisons.

	/** {@code vec:maximum}: element-wise larger of two vectors into a fresh vector. */
	public static final String VEC_MAXIMUM = "maximum";

	/** {@code vec:minimum}: element-wise smaller of two vectors into a fresh vector. */
	public static final String VEC_MINIMUM = "minimum";

	/** {@code vec:relu}: element-wise {@code max(x, 0.0)} into a fresh vector. */
	public static final String VEC_RELU = "relu";

	/**
	 * {@code vec:clip}: element-wise {@code min(max(x, lo), hi)} (scalar bounds) into a
	 * fresh vector.
	 */
	public static final String VEC_CLIP = "clip";

	/** {@code vec:exp-into}: element-wise {@code e^x} into {@code out}. */
	public static final String VEC_EXP_INTO = "exp-into";

	/** {@code vec:log-into}: element-wise natural log into {@code out}. */
	public static final String VEC_LOG_INTO = "log-into";

	/** {@code vec:tanh-into}: element-wise hyperbolic tangent into {@code out}. */
	public static final String VEC_TANH_INTO = "tanh-into";

	/** {@code vec:sin-into}: element-wise sine into {@code out}. */
	public static final String VEC_SIN_INTO = "sin-into";

	/** {@code vec:cos-into}: element-wise cosine into {@code out}. */
	public static final String VEC_COS_INTO = "cos-into";

	/** {@code vec:tan-into}: element-wise tangent into {@code out}. */
	public static final String VEC_TAN_INTO = "tan-into";

	/** {@code vec:asin-into}: element-wise arc sine into {@code out}. */
	public static final String VEC_ASIN_INTO = "asin-into";

	/** {@code vec:acos-into}: element-wise arc cosine into {@code out}. */
	public static final String VEC_ACOS_INTO = "acos-into";

	/** {@code vec:atan-into}: element-wise arc tangent into {@code out}. */
	public static final String VEC_ATAN_INTO = "atan-into";

	/** {@code vec:sinh-into}: element-wise hyperbolic sine into {@code out}. */
	public static final String VEC_SINH_INTO = "sinh-into";

	/** {@code vec:cosh-into}: element-wise hyperbolic cosine into {@code out}. */
	public static final String VEC_COSH_INTO = "cosh-into";

	/** {@code vec:sqrt-into}: element-wise square root into {@code out}. */
	public static final String VEC_SQRT_INTO = "sqrt-into";

	/** {@code vec:abs-into}: element-wise absolute value into {@code out}. */
	public static final String VEC_ABS_INTO = "abs-into";

	/** {@code vec:square-into}: element-wise {@code x * x} into {@code out}. */
	public static final String VEC_SQUARE_INTO = "square-into";

	/** {@code vec:negative-into}: element-wise negation into {@code out}. */
	public static final String VEC_NEGATIVE_INTO = "negative-into";

	/** {@code vec:sign-into}: element-wise {@code signum} into {@code out}. */
	public static final String VEC_SIGN_INTO = "sign-into";

	/** {@code vec:reciprocal-into}: element-wise {@code 1 / x} into {@code out}. */
	public static final String VEC_RECIPROCAL_INTO = "reciprocal-into";

	/** {@code vec:maximum-into}: element-wise larger of two vectors into {@code out}. */
	public static final String VEC_MAXIMUM_INTO = "maximum-into";

	/** {@code vec:minimum-into}: element-wise smaller of two vectors into {@code out}. */
	public static final String VEC_MINIMUM_INTO = "minimum-into";

	/** {@code vec:relu-into}: element-wise {@code max(x, 0.0)} into {@code out}. */
	public static final String VEC_RELU_INTO = "relu-into";

	/**
	 * {@code vec:clip-into}: element-wise {@code min(max(x, lo), hi)} into {@code out}.
	 */
	public static final String VEC_CLIP_INTO = "clip-into";

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
	public static final String USOCKET_PKG = "usocket";

	/**
	 * {@code usocket:socket-connect}: the shim's TCP client entry point. Named as a
	 * constant because {@code UsocketLibrary}'s dedup guard checks whether a program
	 * already defines it (the ASDF built-in-system hook may have spliced the library
	 * before the generic {@code process()} pass runs).
	 */
	public static final String USOCKET_SOCKET_CONNECT = "socket-connect";

	/**
	 * The {@code usocket::%usock-guard} internal form: wraps a socket-operation body so
	 * an underlying failure is re-signaled as a typed {@code usocket:socket-error}. A
	 * {@code handler-case} wrap on the interpreter and the JVM; a plain pass-through on
	 * WASM, where errors are uncatchable traps (the shim source is parsed once and shared
	 * by every backend, so the branch lives in the expansion, not in reader features).
	 */
	public static final String USOCKET_GUARD = "%usock-guard";

	/** The package-qualified spelling of {@code usocket::%usock-guard}. */
	public static final String USOCKET_GUARD_QUALIFIED = USOCKET_PKG + "::" + USOCKET_GUARD;

	/**
	 * The {@code usocket:with-client-socket} macro:
	 * {@code (with-client-socket (socket stream host port &rest connect-args) body...)}
	 * connects, binds {@code socket} and its stream, runs the body and closes the socket
	 * on every exit on the interpreter/JVM ({@code unwind-protect}), on normal exit only
	 * on WASM.
	 */
	public static final String USOCKET_WITH_CLIENT_SOCKET = "with-client-socket";

	/** The canonical package-qualified spelling of {@code usocket:with-client-socket}. */
	public static final String USOCKET_WITH_CLIENT_SOCKET_QUALIFIED = USOCKET_PKG + ":" + USOCKET_WITH_CLIENT_SOCKET;

	/**
	 * The {@code usocket:with-connected-socket} macro:
	 * {@code (with-connected-socket (var socket-form) body...)} binds {@code var}, runs
	 * the body and closes the socket on normal exit.
	 */
	public static final String USOCKET_WITH_CONNECTED_SOCKET = "with-connected-socket";

	/**
	 * The canonical package-qualified spelling of {@code usocket:with-connected-socket}.
	 */
	public static final String USOCKET_WITH_CONNECTED_SOCKET_QUALIFIED = USOCKET_PKG + ":"
			+ USOCKET_WITH_CONNECTED_SOCKET;

	/**
	 * The {@code usocket:with-server-socket} macro: same expansion as
	 * {@code usocket:with-connected-socket} (usocket aliases the two).
	 */
	public static final String USOCKET_WITH_SERVER_SOCKET = "with-server-socket";

	/** The canonical package-qualified spelling of {@code usocket:with-server-socket}. */
	public static final String USOCKET_WITH_SERVER_SOCKET_QUALIFIED = USOCKET_PKG + ":" + USOCKET_WITH_SERVER_SOCKET;

	/**
	 * The {@code usocket:with-socket-listener} macro:
	 * {@code (with-socket-listener (var host port &rest listen-args) body...)} listens,
	 * binds {@code var}, runs the body and closes the listener on normal exit.
	 */
	public static final String USOCKET_WITH_SOCKET_LISTENER = "with-socket-listener";

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
	public static final String WASM_EXPORT = "wasm-export";

	/**
	 * The {@code wit-export} directive provided by the {@code rontolisp} package. Used as
	 * {@code (rontolisp:wit-export "world.wit" :world name)} to declare that the program
	 * implements a WIT world: the compiler checks every {@code defun} against the world's
	 * exports and lowers them into the equivalent {@link #WASM_EXPORT} directives. A
	 * contract check (but no export) on the interpreter and the JVM backend.
	 */
	public static final String WIT_EXPORT = "wit-export";

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
	public static final String HTTP_HANDLER = "http-handler";

	/**
	 * The {@code with-arena} macro provided by the {@code rontolisp} package. Used as
	 * {@code (rontolisp:with-arena () body...)} to name a reclamation boundary: on the
	 * interpreter, the JVM backend and wasm-GC it expands to a plain {@code progn} (a
	 * real GC already reclaims), while the {@code --no-gc} backend lowers it to a bump
	 * heap-pointer mark / body / reset with the body's value (a string or packed float
	 * array) copied down to the mark. Nothing allocated inside the body may be reachable
	 * after it, except the body's own value.
	 */
	public static final String WITH_ARENA = "with-arena";

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
	public static final String WASM_IMPORT = "wasm-import";

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
	public static final String WIT_IMPORT = "wit-import";

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
	public static final String WIT_PROVIDE = "wit-provide";

	/**
	 * The internal {@code rontolisp::%wit-call} dispatch primitive: the body every
	 * {@link #WIT_IMPORT} binding lowers to on the interpreter and the JVM backend.
	 * Defined in {@code wit.lisp} (see {@code WitLibrary}), not by a backend.
	 */
	public static final String WIT_CALL = "%wit-call";

	/**
	 * The {@code wit-error} condition provided by the {@code rontolisp} package: what the
	 * error arm of a WIT {@code result<T, E>} signals, carrying the mapped {@code E}
	 * payload in its {@code payload} slot (the settled type mapping, {@code .kb/wit.md}).
	 * Defined in {@code wit.lisp} (see {@code WitLibrary}).
	 */
	public static final String WIT_ERROR = "wit-error";

	/**
	 * The reader of {@link #WIT_ERROR}'s {@code payload} slot: the mapped {@code E} value
	 * of the WIT {@code result} whose error arm signaled.
	 */
	public static final String WIT_ERROR_PAYLOAD = "wit-error-payload";

	/**
	 * The internal form a {@link #WIT_IMPORT} directive lowers to under
	 * {@code --component}: {@code (rontolisp::%component-import "iface-id" "wit text"
	 * ("member" "lisp-name") ...)}. The WIT text travels inside the form so the WASM
	 * compiler reads no files (the browser playground has no filesystem). Consumed by
	 * {@code WasmComponentImportCompiler}; never user-written.
	 */
	public static final String COMPONENT_IMPORT = "%component-import";

	/**
	 * The internal envelope unwrapper a result-returning {@link #WIT_IMPORT} binding's
	 * public wrapper defun calls on the WASM backends: the raw import returns
	 * {@code (:ok . V)} / {@code (:error . E)} and {@code rontolisp::%wit-result} either
	 * yields the ok value or signals {@link #WIT_ERROR} with the error payload. Defined
	 * in {@code wit.lisp}.
	 */
	public static final String WIT_RESULT = "%wit-result";

	/**
	 * The {@code java} package name (interpreter-only Java interop by reflection). It
	 * does not use {@code cl}; its functions wrap arbitrary host objects as
	 * {@code LispJavaObject} and so run on the JVM interpreter only -- the JVM-class and
	 * WASM backends cannot lower a {@code LispJavaObject}.
	 */
	public static final String JAVA_PKG = "java";

	/**
	 * {@code java:new} -- constructs a host object: {@code (java:new "fqcn" args...)}.
	 */
	public static final String JAVA_NEW = "new";

	/** {@code java:call} -- invokes an instance method on a host object. */
	public static final String JAVA_CALL = "call";

	/**
	 * {@code java:static} -- invokes a static method:
	 * {@code (java:static "fqcn" "m" ...)}.
	 */
	public static final String JAVA_STATIC = "static";

	/** {@code java:field} -- reads a static or instance field (e.g. a constant). */
	public static final String JAVA_FIELD = "field";

	/**
	 * {@code java:proxy} -- makes a host interface instance from a rontolisp callable.
	 */
	public static final String JAVA_PROXY = "proxy";

	/**
	 * The {@code asdf} package name (a limited, API-compatible subset of ASDF: system
	 * definitions parsed from {@code .asd} files as plain data -- see
	 * {@code eval.AsdfSystems}). Real ASDF is not ported; only {@code defsystem} and
	 * {@code load-system} exist.
	 */
	public static final String ASDF_PKG = "asdf";

	/**
	 * {@code asdf:defsystem} -- defines a system (name, {@code :depends-on},
	 * {@code :serial}, {@code :components}) for a later {@code asdf:load-system}.
	 * Consumed at compile time by the {@code LoadInliner} pass; a special form (the
	 * options are data, not evaluated) on the interpreter.
	 */
	public static final String DEFSYSTEM = "defsystem";

	/**
	 * {@code asdf:load-system} -- loads a system by name: dependency systems first, then
	 * the component files in {@code :depends-on}/{@code :serial} order. The system comes
	 * from a prior {@code asdf:defsystem} or from {@code NAME.asd} found on the system
	 * search path. Loading the same system twice is a no-op. Spliced at compile time by
	 * the {@code LoadInliner} pass; a runtime function on the interpreter.
	 */
	public static final String LOAD_SYSTEM = "load-system";

	/**
	 * {@code %read-eval} -- the internal marker the tolerant reader wraps a {@code #.}
	 * read-time-eval datum in ({@code #.datum} lexes to {@code (%read-eval datum)}), so
	 * {@code .asd} consumers ({@code eval.AsdfSystems}) can resolve the datum against the
	 * file's top-level {@code defparameter} bindings (the cl-postgres
	 * {@code (:file #.*string-file*)} idiom). Never appears in evaluated/compiled ASTs:
	 * only the {@code .asd} reading path tolerates {@code #.} at all.
	 */
	public static final String READ_EVAL = "%read-eval";

	/** The canonical qualified spelling of {@code asdf:defsystem}. */
	public static final String ASDF_DEFSYSTEM = ASDF_PKG + ":" + DEFSYSTEM;

	/** The canonical qualified spelling of {@code asdf:load-system}. */
	public static final String ASDF_LOAD_SYSTEM = ASDF_PKG + ":" + LOAD_SYSTEM;

	/**
	 * The {@code ql} package name (a limited, API-compatible subset of Quicklisp). Its
	 * canonical spelling is {@code ql}; {@code quicklisp} is a nickname. Downloads a
	 * system (and its dependencies) from the real Quicklisp distribution into a local
	 * cache and then defers to the {@code asdf} subset to load it -- see
	 * {@code eval.QuicklispClient}.
	 */
	public static final String QL_PKG = "ql";

	/**
	 * {@code ql:quickload} -- downloads a system by name (with its dependencies) from the
	 * Quicklisp distribution into the local cache, then loads it like
	 * {@code asdf:load-system}. The download happens at interpret time or compile time
	 * (Java-side); a compiled program has the sources spliced in, so the WASM/JVM runtime
	 * never fetches. Spliced at compile time by the {@code LoadInliner} pass; a runtime
	 * function on the interpreter.
	 */
	public static final String QUICKLOAD = "quickload";

	/** The canonical qualified spelling of {@code ql:quickload}. */
	public static final String QL_QUICKLOAD = QL_PKG + ":" + QUICKLOAD;

	private LispNames() {
	}

}
