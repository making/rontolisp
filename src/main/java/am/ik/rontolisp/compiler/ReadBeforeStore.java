package am.ik.rontolisp.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * The globals that are no special and that a read can reach before their first store: the
 * compile paths start each as the UNBOUND marker and pass its every read through the
 * check that signals the {@code unbound-variable} naming it, as a special without a value
 * already does ({@code SpecialVarCollector.collectValueless}). Every other global keeps
 * the plain variable, which starts as nil, and the plain read, so a program without such
 * a global compiles as it always did.
 *
 * <p>
 * A compiled program runs its top-level forms in order, so a global whose first top-level
 * occurrence is an unconditional {@code setq} / {@code setf} / {@code psetq} /
 * {@code psetf} of it is exempt when nothing that can run before that store reads it:
 * <ul>
 * <li>the top-level forms before it (none names it, or it would be the first) and the
 * values the store form evaluates before its store of it;</li>
 * <li>every function those can call. While that code and every function it reaches call
 * only a defun, a local function or an operator that runs no code but a function the text
 * hands it ({@link #INERT}), those functions are the defuns the text names -- in code or
 * quoted data, a {@code (setf name)} function through a place's accessor, a
 * {@code satisfies} predicate through the {@code deftype} a type names -- closed over
 * their own text. Any other operator (printing, {@code make-instance}, {@code error},
 * {@code intern}, {@code eval}, a host call, a function the program does not define) can
 * run code no text names -- a method, a condition report, a function found by name at run
 * time -- so past one every defun and every report counts.</li>
 * </ul>
 * A lambda counts where it is written: one in a later top-level form does not exist
 * before the store, one in a function body is read with that body. A global no read names
 * outside a binding of its name -- the collectors are blind to scope, so a {@code let}
 * variable a {@code setq} assigns is a global too -- is never read as one and needs no
 * marker.
 */
public final class ReadBeforeStore {

	private ReadBeforeStore() {
	}

	/**
	 * The globals the compilers write for their own bookkeeping or seed themselves: never
	 * the program's variables.
	 */
	private static final Set<String> COMPILER_GLOBALS = Set.of(LispNames.MV_SPILL, LispNames.HANDLER_CLUSTERS_VAR,
			LispNames.RESTART_CLUSTERS_VAR, LispNames.STANDARD_OUTPUT_VAR, LispNames.STANDARD_INPUT_VAR,
			LispNames.ERROR_OUTPUT_VAR);

	/**
	 * The operators that run no code of the program's but a function it hands them (which
	 * the text names, so the walk follows it): the binding and control forms, and the
	 * built-ins over numbers, lists, sequences, arrays, hash tables, strings and
	 * characters. Printing (a {@code print-object} method, a condition report),
	 * {@code make-instance} (an {@code initialize-instance} method), signalling (a
	 * condition's initforms), reading, {@code intern} and {@code eval} (a function named
	 * at run time) are out, as is every other operator a backend lowers.
	 */
	static final Set<String> INERT = Set.of(
			// definitions, bindings and control
			"QUOTE", "FUNCTION", "IF", "PROGN", "PROG1", "PROG2", "LET", "LET*", "SETQ", "PSETQ", "SETF", "PSETF",
			"BLOCK", "RETURN-FROM", "RETURN", "TAGBODY", "GO", "THE", "LOCALLY", "DECLARE", "WHEN", "UNLESS", "COND",
			"CASE", "ECASE", "TYPECASE", "ETYPECASE", "AND", "OR", "NOT", "DOLIST", "DOTIMES", "DO", "DO*", "LOOP",
			"FLET", "LABELS", "LAMBDA", "DEFUN", "MULTIPLE-VALUE-BIND", "MULTIPLE-VALUE-LIST", "MULTIPLE-VALUE-PROG1",
			"MULTIPLE-VALUE-SETQ", "MULTIPLE-VALUE-CALL", "VALUES", "VALUES-LIST", "NTH-VALUE", "DESTRUCTURING-BIND",
			"CATCH", "THROW", "UNWIND-PROTECT", "HANDLER-CASE", "HANDLER-BIND", "IGNORE-ERRORS", "INCF", "DECF", "PUSH",
			"POP", "PUSHNEW", "DECLAIM", "PROCLAIM", "DEFVAR", "DEFPARAMETER", "DEFCONSTANT", "PROG", "PROG*",
			"ROTATEF", "SHIFTF", "FUNCALL", "APPLY", "IDENTITY", "COMPLEMENT", "CONSTANTLY", "EQ", "EQL", "EQUAL",
			"EQUALP", "TYPEP", "FUNCTIONP", "COERCE",
			// numbers
			"+", "-", "*", "/", "1+", "1-", "=", "/=", "<", ">", "<=", ">=", "MIN", "MAX", "ABS", "MOD", "REM", "FLOOR",
			"CEILING", "TRUNCATE", "ROUND", "FFLOOR", "FCEILING", "FTRUNCATE", "FROUND", "EXPT", "EXP", "LOG", "SQRT",
			"ISQRT", "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN", "SINH", "COSH", "TANH", "SIGNUM", "FLOAT", "RATIONAL",
			"RATIONALIZE", "NUMERATOR", "DENOMINATOR", "GCD", "LCM", "ASH", "LOGAND", "LOGIOR", "LOGXOR", "LOGNOT",
			"LOGBITP", "LOGCOUNT", "LOGTEST", "INTEGER-LENGTH", "BYTE", "LDB", "DPB", "ZEROP", "PLUSP", "MINUSP",
			"EVENP", "ODDP", "NUMBERP", "INTEGERP", "RATIONALP", "FLOATP", "REALP", "COMPLEXP", "COMPLEX", "REALPART",
			"IMAGPART", "RANDOM", "PARSE-INTEGER",
			// conses and lists
			"CONS", "CAR", "CDR", "FIRST", "SECOND", "THIRD", "FOURTH", "FIFTH", "SIXTH", "SEVENTH", "EIGHTH", "NINTH",
			"TENTH", "REST", "LIST", "LIST*", "APPEND", "NCONC", "REVAPPEND", "NRECONC", "REVERSE", "NREVERSE",
			"LENGTH", "LIST-LENGTH", "NTH", "NTHCDR", "LAST", "BUTLAST", "NBUTLAST", "COPY-LIST", "COPY-TREE",
			"COPY-ALIST", "MAKE-LIST", "ENDP", "NULL", "CONSP", "LISTP", "ATOM", "RPLACA", "RPLACD", "MEMBER",
			"MEMBER-IF", "MEMBER-IF-NOT", "ASSOC", "ASSOC-IF", "RASSOC", "GETF", "ACONS", "PAIRLIS", "SUBST", "SUBLIS",
			"ADJOIN", "UNION", "INTERSECTION", "SET-DIFFERENCE", "SUBSETP", "LDIFF", "TAILP",
			// sequences
			"ELT", "SUBSEQ", "COPY-SEQ", "CONCATENATE", "MAP", "MAP-INTO", "MAPCAR", "MAPC", "MAPCAN", "MAPLIST",
			"MAPL", "MAPCON", "REDUCE", "FIND", "FIND-IF", "FIND-IF-NOT", "POSITION", "POSITION-IF", "POSITION-IF-NOT",
			"COUNT", "COUNT-IF", "COUNT-IF-NOT", "REMOVE", "REMOVE-IF", "REMOVE-IF-NOT", "DELETE", "DELETE-IF",
			"DELETE-IF-NOT", "REMOVE-DUPLICATES", "DELETE-DUPLICATES", "SUBSTITUTE", "SUBSTITUTE-IF", "NSUBSTITUTE",
			"SORT", "STABLE-SORT", "MERGE", "SEARCH", "MISMATCH", "REPLACE", "FILL", "EVERY", "SOME", "NOTANY",
			"NOTEVERY",
			// arrays and hash tables
			"MAKE-ARRAY", "AREF", "SVREF", "VECTOR", "ARRAY-DIMENSION", "ARRAY-DIMENSIONS", "ARRAY-TOTAL-SIZE",
			"ARRAY-RANK", "FILL-POINTER", "VECTOR-PUSH", "VECTOR-PUSH-EXTEND", "VECTOR-POP", "ADJUST-ARRAY", "ARRAYP",
			"VECTORP", "SIMPLE-VECTOR-P", "BIT", "SBIT", "ROW-MAJOR-AREF", "MAKE-HASH-TABLE", "GETHASH", "REMHASH",
			"CLRHASH", "HASH-TABLE-COUNT", "HASH-TABLE-P", "MAPHASH",
			// strings, characters and symbols
			"MAKE-STRING", "STRING", "CHAR", "SCHAR", "STRING=", "STRING/=", "STRING<", "STRING>", "STRING<=",
			"STRING>=", "STRING-EQUAL", "STRING-UPCASE", "STRING-DOWNCASE", "STRING-CAPITALIZE", "STRING-TRIM",
			"STRING-LEFT-TRIM", "STRING-RIGHT-TRIM", "STRINGP", "CHARACTERP", "CHAR-CODE", "CODE-CHAR", "CHAR-UPCASE",
			"CHAR-DOWNCASE", "CHAR=", "CHAR/=", "CHAR<", "CHAR>", "CHAR<=", "CHAR>=", "CHAR-EQUAL", "ALPHA-CHAR-P",
			"DIGIT-CHAR-P", "DIGIT-CHAR", "UPPER-CASE-P", "LOWER-CASE-P", "ALPHANUMERICP", "CHARACTER", "SYMBOLP",
			"KEYWORDP", "SYMBOL-NAME", "GENSYM", "MAKE-SYMBOL", "GET-INTERNAL-REAL-TIME", "GET-INTERNAL-RUN-TIME",
			"GET-UNIVERSAL-TIME",
			// the compile paths' own forms
			LispNames.FN_BLOCK_INTERNAL, "%OBJ-NEW", "%OBJ-REF", "%OBJ-SET", "%OBJ-IS", "%OBJ-P", "%UNSPELLED-QUOTE",
			"WHILE");

	/**
	 * The globals that are no special and that a read can reach before their first store,
	 * in the order the compilers collect their globals. A program without one answers an
	 * empty set and compiles as before.
	 * @param program the program's forms, top-level {@code defun}s included, as the
	 * compilers collect their globals from them
	 * @param reports the condition {@code :report} lambdas, which a printed condition
	 * runs
	 * @param specials the program's special variables, which have the marker of their own
	 * where they need it
	 * @param closRegistry the registry whose {@code deftype}s a type test expands
	 * @return those globals
	 */
	public static LinkedHashSet<String> collect(List<LispVal> program, Collection<LispVal> reports,
			Set<String> specials, ClosRegistry closRegistry) {
		List<LispVal> topLevel = new ArrayList<>();
		Map<String, LispVal> defuns = new HashMap<>();
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
					&& nameCell.car() instanceof LispSymbol name && nameCell.cdr() instanceof LispCons lambdaListCell) {
				// The defun as the lambda it is, its nested defuns as lambdas: the
				// free-variable walk skips a defun.
				defuns.put(name.name(), new LispCons(new LispSymbol(LispNames.LAMBDA),
						GlobalVarCollector.nestedDefunsAsLambdas(lambdaListCell)));
			}
			else {
				topLevel.add(form);
			}
		}
		LinkedHashSet<String> candidates = new LinkedHashSet<>(GlobalVarCollector.collect(topLevel));
		candidates.addAll(GlobalVarCollector.collectNestedInDefunBodies(program));
		candidates.addAll(GlobalVarCollector.collectFreeAssigned(program));
		candidates.removeAll(specials);
		candidates.removeAll(GlobalVarCollector.collectAllNestedDefunNames(program));
		candidates.removeIf(name -> COMPILER_GLOBALS.contains(name) || PackageRegistry.isClSymbol(name)
				|| PackageRegistry.isClSymbol(member(name)));
		if (candidates.isEmpty()) {
			return candidates;
		}
		// Who reads each candidate as a global: a defun, a report, a top-level form.
		Map<String, Set<String>> defunReads = new HashMap<>();
		Set<String> readByDefun = new HashSet<>();
		for (Map.Entry<String, LispVal> defun : defuns.entrySet()) {
			Set<String> reads = freeReads(defun.getValue(), candidates);
			defunReads.put(defun.getKey(), reads);
			readByDefun.addAll(reads);
		}
		Set<String> readByReport = new HashSet<>();
		for (LispVal report : reports) {
			readByReport.addAll(freeReads(report, candidates));
		}
		Set<String> read = new HashSet<>(readByDefun);
		read.addAll(readByReport);
		// The first top-level form each candidate occurs in outside a binding of it.
		Map<String, Integer> first = new HashMap<>();
		for (int i = 0; i < topLevel.size(); i++) {
			LispVal lambda = asLambda(topLevel.get(i));
			Set<String> named = new HashSet<>();
			occurrences(lambda, candidates, named, false);
			if (named.isEmpty()) {
				continue;
			}
			Set<String> reads = new HashSet<>();
			occurrences(lambda, candidates, reads, true);
			Set<String> free = free(lambda, candidates, named);
			reads.retainAll(free);
			read.addAll(reads);
			for (String name : free) {
				first.putIfAbsent(name, i);
			}
		}
		candidates.retainAll(read);
		if (candidates.isEmpty()) {
			return candidates;
		}
		// The exempt ones, decided in the order of their first form: what can run before
		// form i only grows with i.
		List<Map.Entry<String, Integer>> byFirst = new ArrayList<>();
		for (String name : candidates) {
			Integer index = first.get(name);
			if (index != null) {
				byFirst.add(Map.entry(name, index));
			}
		}
		byFirst.sort(Map.Entry.comparingByValue());
		Set<String> hostFunctions = new HashSet<>();
		for (LispVal form : topLevel) {
			hostFunctions(form, hostFunctions);
		}
		Region before = new Region(defuns, defunReads, closRegistry, hostFunctions);
		int walked = 0;
		Set<String> exempt = new HashSet<>();
		for (Map.Entry<String, Integer> entry : byFirst) {
			String name = entry.getKey();
			int index = entry.getValue();
			for (; walked < index; walked++) {
				before.add(topLevel.get(walked));
			}
			List<LispVal> values = valuesBeforeStore(topLevel.get(index), name);
			if (values == null) {
				continue;
			}
			Region region = before.copy();
			boolean readByValue = false;
			for (LispVal value : values) {
				readByValue |= freeReads(asLambda(value), candidates).contains(name);
				region.add(value);
			}
			if (readByValue) {
				continue;
			}
			if (region.danger != null ? !readByDefun.contains(name) && !readByReport.contains(name)
					: !region.reads.contains(name)) {
				exempt.add(name);
			}
		}
		candidates.removeAll(exempt);
		return candidates;
	}

	/**
	 * What the code of the top-level forms walked so far can run: the defuns their text
	 * names, closed over the defuns' own text, the candidates those read, and the first
	 * operator that can run code the text does not name.
	 */
	private static final class Region {

		private final Map<String, LispVal> defuns;

		private final Map<String, Set<String>> defunReads;

		private final ClosRegistry closRegistry;

		private final Set<String> hostFunctions;

		private final Set<String> reached;

		private final Set<String> reads;

		private @Nullable String danger;

		Region(Map<String, LispVal> defuns, Map<String, Set<String>> defunReads, ClosRegistry closRegistry,
				Set<String> hostFunctions) {
			this(defuns, defunReads, closRegistry, hostFunctions, new HashSet<>(), new HashSet<>(), null);
		}

		private Region(Map<String, LispVal> defuns, Map<String, Set<String>> defunReads, ClosRegistry closRegistry,
				Set<String> hostFunctions, Set<String> reached, Set<String> reads, @Nullable String danger) {
			this.defuns = defuns;
			this.defunReads = defunReads;
			this.closRegistry = closRegistry;
			this.hostFunctions = hostFunctions;
			this.reached = reached;
			this.reads = reads;
			this.danger = danger;
		}

		Region copy() {
			return new Region(this.defuns, this.defunReads, this.closRegistry, this.hostFunctions,
					new HashSet<>(this.reached), new HashSet<>(this.reads), this.danger);
		}

		/** Adds the code of a form, and every defun it can call. */
		void add(LispVal form) {
			if (isDirective(form)) {
				// Declares a host function or an entry point: runs nothing.
				return;
			}
			Deque<String> work = new ArrayDeque<>();
			scan(form, work);
			while (!work.isEmpty()) {
				String defun = work.poll();
				this.reads.addAll(this.defunReads.getOrDefault(defun, Set.of()));
				scan(java.util.Objects.requireNonNull(this.defuns.get(defun)), work);
			}
		}

		private void scan(LispVal form, Deque<String> work) {
			CodeWalk walk = new CodeWalk(this.defuns.keySet(), this.closRegistry, this.hostFunctions);
			walk.code(form, Set.of());
			for (String defun : walk.refs) {
				if (this.reached.add(defun)) {
					work.add(defun);
				}
			}
			if (this.danger == null) {
				this.danger = walk.danger;
			}
		}

	}

	/**
	 * One walk of a form's code: the defuns it names and the first operator in it that
	 * can run code it does not name.
	 */
	private static final class CodeWalk {

		private final Set<String> defuns;

		private final ClosRegistry closRegistry;

		private final Set<String> hostFunctions;

		final Set<String> refs = new HashSet<>();

		@Nullable String danger;

		private final Set<String> expandedTypes = new HashSet<>();

		CodeWalk(Set<String> defuns, ClosRegistry closRegistry, Set<String> hostFunctions) {
			this.defuns = defuns;
			this.closRegistry = closRegistry;
			this.hostFunctions = hostFunctions;
		}

		/** A symbol anywhere: a defun, the setf function it names, a deftype. */
		private void symbol(String name) {
			if (this.defuns.contains(name)) {
				this.refs.add(name);
			}
			String setf = LispMacroExpander.setfFunctionName(name);
			if (this.defuns.contains(setf)) {
				this.refs.add(setf);
			}
			LispVal expansion = this.closRegistry.findDeftype(name);
			if (expansion != null && this.expandedTypes.add(name)) {
				// A type test of the name runs the predicates its expansion names.
				type(expansion);
			}
		}

		/**
		 * A type specifier: the predicate a {@code satisfies} names is called, the
		 * objects of {@code member} and {@code eql} are data, every other symbol names a
		 * type.
		 */
		private void type(LispVal spec) {
			if (spec instanceof LispSymbol sym) {
				symbol(sym.name());
				return;
			}
			if (!(spec instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
				return;
			}
			switch (head.name()) {
				case "SATISFIES" -> {
					if (cons.cdr() instanceof LispCons predicate) {
						quoted(predicate.car());
					}
				}
				case "MEMBER", "EQL" -> {
					// Data.
				}
				default -> {
					for (LispVal element : elements(cons.cdr())) {
						type(element);
					}
				}
			}
		}

		/** A type argument: a quoted specifier, or a form computing one. */
		private void typeArgument(LispVal form, Set<String> local) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.QUOTE.equals(head.name()) && cons.cdr() instanceof LispCons spec) {
				type(spec.car());
			}
			else {
				code(form, local);
			}
		}

		/**
		 * The operator of a call, or a function a {@code function} form names: anything
		 * but a defun, a local function and an {@link #INERT} operator can run code the
		 * text does not name.
		 */
		private void operator(String name, Set<String> local) {
			symbol(name);
			if (this.danger == null && !this.defuns.contains(name) && !local.contains(name) && !INERT.contains(name)
					&& !LispNames.isCarCdrComposition(name)) {
				this.danger = name;
			}
		}

		/**
		 * Quoted data: a symbol in it is a designator a {@code funcall} may call, so a
		 * standard function outside {@link #INERT} and a host function there can run code
		 * the text does not name.
		 */
		private void quoted(LispVal datum) {
			LispVal node = datum;
			while (node instanceof LispCons cell) {
				quoted(cell.car());
				node = cell.cdr();
			}
			if (node instanceof LispSymbol sym && !sym.isKeyword()) {
				String name = sym.name();
				symbol(name);
				if (this.danger == null && !this.defuns.contains(name) && designatesHiddenCode(name)) {
					this.danger = name;
				}
			}
		}

		private boolean designatesHiddenCode(String name) {
			if (INERT.contains(name) || LispNames.isCarCdrComposition(name)) {
				return false;
			}
			if (PackageRegistry.isClFunctionName(name) || BuiltinFunctionWrappers.isWrappedBuiltin(name)
					|| this.hostFunctions.contains(name)) {
				return true;
			}
			PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
			return qualified != null && switch (qualified.pkg()) {
				case "JAVA", "OBJC", "FFI", "RONTOLISP" -> true;
				default -> false;
			};
		}

		void code(LispVal form, Set<String> local) {
			if (form instanceof LispSymbol sym) {
				symbol(sym.name());
				return;
			}
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (!(cons.car() instanceof LispSymbol head)) {
				body(cons, local);
				return;
			}
			switch (head.name()) {
				case LispNames.QUOTE -> {
					if (cons.cdr() instanceof LispCons datum) {
						quoted(datum.car());
					}
				}
				case LispNames.FUNCTION -> {
					if (cons.cdr() instanceof LispCons fn) {
						LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(fn.car());
						if (fn.car() instanceof LispSymbol name) {
							operator(name.name(), local);
						}
						else if (setfPlace != null) {
							// #'(setf name): the writer, not a setf form.
							operator(LispMacroExpander.setfFunctionName(setfPlace.name()), local);
						}
						else {
							code(fn.car(), local);
						}
					}
				}
				case LispNames.DECLARE, LispNames.DECLAIM -> {
					// Runs nothing.
				}
				case LispNames.COND -> {
					// Each clause is a test and the forms it guards.
					for (LispVal clause : elements(cons.cdr())) {
						body(clause, local);
					}
				}
				case LispNames.DEFUN -> {
					// A nested definition: its body, as a lambda's.
					if (cons.cdr() instanceof LispCons name && name.cdr() instanceof LispCons lambdaList) {
						lambdaList(lambdaList.car(), local);
						body(lambdaList.cdr(), local);
					}
				}
				case LispNames.HANDLER_BIND -> {
					if (cons.cdr() instanceof LispCons bindings) {
						for (LispVal binding : elements(bindings.car())) {
							if (binding instanceof LispCons b) {
								type(b.car());
								body(b.cdr(), local);
							}
						}
						body(bindings.cdr(), local);
					}
				}
				case LispNames.SETF, LispNames.PSETF -> {
					// Place / value pairs.
					LispVal node = cons.cdr();
					while (node instanceof LispCons placeCell) {
						place(placeCell.car(), local);
						if (!(placeCell.cdr() instanceof LispCons valueCell)) {
							break;
						}
						code(valueCell.car(), local);
						node = valueCell.cdr();
					}
				}
				case LispNames.INCF, LispNames.DECF, LispNames.POP -> {
					if (cons.cdr() instanceof LispCons target) {
						place(target.car(), local);
						body(target.cdr(), local);
					}
				}
				case LispNames.PUSH, LispNames.PUSHNEW -> {
					if (cons.cdr() instanceof LispCons item && item.cdr() instanceof LispCons target) {
						code(item.car(), local);
						place(target.car(), local);
						body(target.cdr(), local);
					}
				}
				case LispNames.MULTIPLE_VALUE_SETQ -> {
					// (multiple-value-setq (vars) form)
					if (cons.cdr() instanceof LispCons vars) {
						body(vars.cdr(), local);
					}
				}
				case LispNames.THE -> {
					// (the type form): the type is a specifier.
					if (cons.cdr() instanceof LispCons type) {
						type(type.car());
						body(type.cdr(), local);
					}
				}
				case LispNames.TYPEP, LispNames.COERCE -> {
					// (typep object 'type) / (coerce object 'type)
					if (cons.cdr() instanceof LispCons object) {
						code(object.car(), local);
						if (object.cdr() instanceof LispCons type) {
							typeArgument(type.car(), local);
							body(type.cdr(), local);
						}
					}
				}
				case LispNames.LET, LispNames.LET_STAR, "PROG", "PROG*" -> {
					if (cons.cdr() instanceof LispCons bindings) {
						for (LispVal binding : elements(bindings.car())) {
							if (binding instanceof LispCons pair && pair.cdr() instanceof LispCons init) {
								code(init.car(), local);
							}
						}
						body(bindings.cdr(), local);
					}
				}
				case LispNames.LAMBDA -> {
					if (cons.cdr() instanceof LispCons lambdaList) {
						lambdaList(lambdaList.car(), local);
						body(lambdaList.cdr(), local);
					}
				}
				case LispNames.FLET, LispNames.LABELS -> {
					if (cons.cdr() instanceof LispCons definitions) {
						Set<String> inner = new HashSet<>(local);
						for (LispVal definition : elements(definitions.car())) {
							if (definition instanceof LispCons def && def.car() instanceof LispSymbol name) {
								inner.add(name.name());
							}
						}
						Set<String> definitionScope = LispNames.LABELS.equals(head.name()) ? inner : local;
						for (LispVal definition : elements(definitions.car())) {
							if (definition instanceof LispCons def && def.cdr() instanceof LispCons lambdaList) {
								lambdaList(lambdaList.car(), definitionScope);
								body(lambdaList.cdr(), definitionScope);
							}
						}
						body(definitions.cdr(), inner);
					}
				}
				case LispNames.DO, LispNames.DO_STAR -> {
					if (cons.cdr() instanceof LispCons specs) {
						for (LispVal spec : elements(specs.car())) {
							if (spec instanceof LispCons var) {
								body(var.cdr(), local);
							}
						}
						body(specs.cdr(), local);
					}
				}
				case LispNames.DOLIST, LispNames.DOTIMES, LispNames.MULTIPLE_VALUE_BIND,
						LispNames.DESTRUCTURING_BIND -> {
					if (cons.cdr() instanceof LispCons spec) {
						if (LispNames.DOLIST.equals(head.name()) || LispNames.DOTIMES.equals(head.name())) {
							// (var form [result]): the forms after the variable.
							if (spec.car() instanceof LispCons var) {
								body(var.cdr(), local);
							}
						}
						body(spec.cdr(), local);
					}
				}
				case LispNames.HANDLER_CASE -> {
					if (cons.cdr() instanceof LispCons protectedForm) {
						code(protectedForm.car(), local);
						for (LispVal clause : elements(protectedForm.cdr())) {
							if (clause instanceof LispCons c && c.cdr() instanceof LispCons vars) {
								type(c.car());
								body(vars.cdr(), local);
							}
						}
					}
				}
				case LispNames.CASE, LispNames.ECASE, LispNames.TYPECASE, LispNames.ETYPECASE -> {
					if (cons.cdr() instanceof LispCons key) {
						code(key.car(), local);
						boolean types = LispNames.TYPECASE.equals(head.name())
								|| LispNames.ETYPECASE.equals(head.name());
						for (LispVal clause : elements(key.cdr())) {
							if (clause instanceof LispCons c) {
								if (types) {
									type(c.car());
								}
								body(c.cdr(), local);
							}
						}
					}
				}
				default -> {
					operator(head.name(), local);
					body(cons.cdr(), local);
				}
			}
		}

		/**
		 * A place an assignment reads or writes: a variable is no call, and a call place
		 * reads through its accessor and writes through the {@code (setf name)} function
		 * it names, a defun either way when the program defines one; any other accessor
		 * is a call like any other.
		 */
		private void place(LispVal place, Set<String> local) {
			if (!(place instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol head
					&& this.defuns.contains(LispMacroExpander.setfFunctionName(head.name()))) {
				symbol(head.name());
				body(cons.cdr(), local);
				return;
			}
			code(place, local);
		}

		private void body(LispVal forms, Set<String> local) {
			LispVal node = forms;
			while (node instanceof LispCons cell) {
				code(cell.car(), local);
				node = cell.cdr();
			}
		}

		/** A lambda list's default forms. */
		private void lambdaList(LispVal list, Set<String> local) {
			for (LispVal parameter : elements(list)) {
				if (parameter instanceof LispCons spec && spec.cdr() instanceof LispCons init) {
					code(init.car(), local);
				}
			}
		}

	}

	/**
	 * Whether a top-level form is a directive -- {@code rontolisp:wasm-import},
	 * {@code rontolisp:wasm-export}, {@code rontolisp:jvm-export}, the
	 * {@code --component} lowering of {@code rontolisp:wit-import} -- which declares and
	 * runs nothing where it stands.
	 */
	private static boolean isDirective(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		if (head.name().equals(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.COMPONENT_IMPORT))) {
			return true;
		}
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(head.name());
		return qualified != null && LispNames.RONTOLISP_PKG.equals(qualified.pkg())
				&& (LispNames.WASM_IMPORT.equals(qualified.member()) || LispNames.WASM_EXPORT.equals(qualified.member())
						|| LispNames.JVM_EXPORT.equals(qualified.member()));
	}

	/**
	 * Adds the names an import directive binds to host functions: the
	 * {@code (quote name)} of a {@code rontolisp:wasm-import}, every string of a
	 * component import's member bindings.
	 */
	private static void hostFunctions(LispVal form, Set<String> out) {
		if (!isDirective(form) || !(form instanceof LispCons cons)) {
			return;
		}
		List<LispVal> parts = elements(cons);
		if (WasmImportDirective.isImportForm(form)) {
			if (parts.size() > 1 && parts.get(1) instanceof LispCons quote && quote.cdr() instanceof LispCons named
					&& named.car() instanceof LispSymbol name) {
				out.add(name.name());
			}
			return;
		}
		for (int i = 3; i < parts.size(); i++) {
			for (LispVal member : elements(parts.get(i))) {
				if (member instanceof am.ik.rontolisp.LispString name) {
					out.add(name.value());
				}
			}
		}
	}

	/**
	 * The value forms a top-level store form evaluates before it stores {@code name} -- a
	 * non-symbol place before it included -- or null when the form is no assignment with
	 * {@code name} as a bare place.
	 */
	private static @Nullable List<LispVal> valuesBeforeStore(LispVal form, String name) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		boolean parallel = LispNames.PSETQ.equals(head.name()) || LispNames.PSETF.equals(head.name());
		if (!parallel && !LispNames.SETQ.equals(head.name()) && !LispNames.SETF.equals(head.name())) {
			return null;
		}
		List<LispVal> before = new ArrayList<>();
		boolean stored = false;
		LispVal node = cons.cdr();
		while (node instanceof LispCons placeCell && placeCell.cdr() instanceof LispCons valueCell) {
			if (!stored || parallel) {
				before.add(valueCell.car());
				if (!(placeCell.car() instanceof LispSymbol)) {
					before.add(placeCell.car());
				}
			}
			stored |= placeCell.car() instanceof LispSymbol place && place.name().equals(name);
			if (stored && !parallel) {
				break;
			}
			node = valueCell.cdr();
		}
		return stored ? before : null;
	}

	/**
	 * The form as the body of a lambda of no parameters, its nested defuns as lambdas.
	 */
	private static LispVal asLambda(LispVal form) {
		return new LispCons(new LispSymbol(LispNames.LAMBDA), GlobalVarCollector
			.nestedDefunsAsLambdas(new LispCons(LispNil.INSTANCE, new LispCons(form, LispNil.INSTANCE))));
	}

	/** The candidates the lambda reads outside a binding of them. */
	private static Set<String> freeReads(LispVal lambda, Set<String> candidates) {
		Set<String> read = new HashSet<>();
		occurrences(lambda, candidates, read, true);
		return free(lambda, candidates, read);
	}

	/**
	 * {@code named} less the ones every occurrence of which a binding in the lambda
	 * covers. The walk is the one the backends capture closures by; a form it cannot read
	 * keeps every name.
	 */
	private static Set<String> free(LispVal lambda, Set<String> candidates, Set<String> named) {
		if (named.isEmpty()) {
			return named;
		}
		try {
			named.retainAll(FreeVarAnalyzer.findFreeVars(List.of(lambda), Set.of(), Set.of(), Set.of(), candidates));
		}
		catch (RuntimeException ex) {
			// Keep them all: answering "free" only costs a check.
		}
		return named;
	}

	/**
	 * Adds each candidate the form names outside quoted data -- with {@code readsOnly},
	 * not counting the bare place of an assignment.
	 */
	private static void occurrences(LispVal form, Set<String> candidates, Set<String> out, boolean readsOnly) {
		if (form instanceof LispSymbol sym) {
			if (candidates.contains(sym.name())) {
				out.add(sym.name());
			}
			return;
		}
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return;
			}
			if (readsOnly && GlobalVarCollector.isAssignmentHead(head.name())) {
				if (LispNames.MULTIPLE_VALUE_SETQ.equals(head.name())) {
					if (cons.cdr() instanceof LispCons vars) {
						occurrences(vars.cdr(), candidates, out, true);
					}
					return;
				}
				LispVal node = cons.cdr();
				while (node instanceof LispCons placeCell) {
					if (!(placeCell.car() instanceof LispSymbol)) {
						occurrences(placeCell.car(), candidates, out, true);
					}
					if (!(placeCell.cdr() instanceof LispCons valueCell)) {
						break;
					}
					occurrences(valueCell.car(), candidates, out, true);
					node = valueCell.cdr();
				}
				return;
			}
		}
		LispVal node = cons;
		while (node instanceof LispCons cell) {
			occurrences(cell.car(), candidates, out, readsOnly);
			node = cell.cdr();
		}
		occurrences(node, candidates, out, readsOnly);
	}

	private static List<LispVal> elements(LispVal list) {
		List<LispVal> out = new ArrayList<>();
		LispVal node = list;
		while (node instanceof LispCons cell) {
			out.add(cell.car());
			node = cell.cdr();
		}
		return out;
	}

	/**
	 * Strips a package qualifier: {@code pkg::*x*} is a {@code cl} symbol like
	 * {@code *x*}.
	 */
	private static String member(String name) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		return qualified == null ? name : qualified.member();
	}

}
