package am.ik.rontolisp.compiler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The one predicate behind "a {@code typecase} clause whose type this argument cannot
 * have is dead": a three-point lattice over what a CALL SITE says about a value, and the
 * test asking whether a value of that shape could satisfy a type specifier.
 *
 * <p>
 * Two analyses read it and they must agree, which is why it is here rather than inside
 * either: {@link NoWasiLoadPathRefusals} uses it to keep a build line off a branch no
 * reaching call can take, and {@link DeadTypeBranchPruner} uses it to take that branch
 * out of the program so the tree-shaker stops paying for it. Both symptoms are one
 * blindness -- clack's {@code clackup} dispatches on whether it was handed a pathname or
 * a function, and a reactor only ever hands it a function, so the file loader behind the
 * pathname clause is statically reachable and dynamically dead.
 *
 * <p>
 * <strong>Every approximation leans the same way</strong>: {@link #maySatisfy} answers
 * "yes, possibly" whenever it is not certain of the answer, so an unknown shape, an
 * unrecognized type name, a {@code (satisfies ...)} and a user {@code deftype} all keep
 * their branch. A wrong "no" is a branch deleted out of a live program (and, in the
 * refusals pass, a refusal never named) -- the failure mode both callers exist to prevent
 * -- while a wrong "yes" only costs what the state before this class cost anyway.
 */
public final class ArgumentShapes {

	private ArgumentShapes() {
	}

	/**
	 * What a call site states, syntactically, about one argument. Three points, as the
	 * clack case needs: a function, a literal's type, and "anything".
	 */
	public enum Shape {

		/** Nothing is known: satisfies every type. */
		UNKNOWN,
		/** {@code #'f} or a literal {@code (lambda ...)}. */
		FUNCTION,
		/** A string literal. */
		STRING,
		/** An integer literal (fixnum or bignum). */
		INTEGER,
		/** A float literal. */
		FLOAT,
		/** A ratio literal. */
		RATIO,
		/** A character literal. */
		CHARACTER,
		/** A quoted symbol that is neither a keyword nor {@code nil}/{@code t}. */
		SYMBOL,
		/** A keyword, self-evaluating. */
		KEYWORD,
		/** The {@code t} literal. */
		TRUE,
		/** {@code nil} -- the empty list AND the false value AND a symbol. */
		NULL,
		/** A quoted non-empty list. */
		CONS,
		/**
		 * A rank-1 non-string, non-bit array: the result of a {@code (make-array n ...)}
		 * whose element type is numeric or unstated, of {@code (vector ...)}, or of a
		 * {@code subseq}/{@code copy-seq} over a value already of this shape. Strings and
		 * bit vectors are deliberately NOT this shape -- each has type memberships this
		 * one must not claim ({@code string}) or rule out ({@code bit-vector}).
		 */
		VECTOR,
		/**
		 * {@code (make-instance 'some-class)}: an instance of a class this lattice has no
		 * point for. It is a shape rather than {@code UNKNOWN} because the instance TYPES
		 * are mutually exclusive -- a ningle app is not a pathname, which is an instance
		 * of its own fixed layout here -- while any name this class does not decide (the
		 * class itself, its superclasses) stays satisfiable.
		 */
		INSTANCE

	}

	/**
	 * Every type name a shape satisfies. Read against
	 * {@code LispMacroExpander.makeTypeTest}, which is what a surviving clause actually
	 * compiles to: {@code pathname} is an instance type that a STRING is deliberately
	 * not, a {@code package} value is a keyword naming a registered package, and
	 * {@code vector}/{@code array} include strings.
	 */
	private static final Map<Shape, Set<String>> SUPERTYPES = supertypeTable();

	private static Map<Shape, Set<String>> supertypeTable() {
		Map<Shape, Set<String>> table = new java.util.EnumMap<>(Shape.class);
		table.put(Shape.FUNCTION, Set.of("FUNCTION", "ATOM", "T"));
		table.put(Shape.STRING, Set.of("STRING", "SIMPLE-STRING", "BASE-STRING", "SIMPLE-BASE-STRING", "VECTOR",
				"SIMPLE-VECTOR", "ARRAY", "SIMPLE-ARRAY", "SEQUENCE", "ATOM", "T"));
		table.put(Shape.INTEGER, Set.of("INTEGER", "FIXNUM", "BIGNUM", "SIGNED-BYTE", "UNSIGNED-BYTE", "RATIONAL",
				"REAL", "NUMBER", "ATOM", "T"));
		table.put(Shape.FLOAT, Set.of("FLOAT", "SINGLE-FLOAT", "DOUBLE-FLOAT", "SHORT-FLOAT", "LONG-FLOAT", "REAL",
				"NUMBER", "ATOM", "T"));
		table.put(Shape.RATIO, Set.of("RATIO", "RATIONAL", "REAL", "NUMBER", "ATOM", "T"));
		table.put(Shape.CHARACTER, Set.of("CHARACTER", "BASE-CHAR", "STANDARD-CHAR", "ATOM", "T"));
		table.put(Shape.SYMBOL, Set.of("SYMBOL", "ATOM", "T"));
		table.put(Shape.KEYWORD, Set.of("KEYWORD", "SYMBOL", "PACKAGE", "ATOM", "T"));
		table.put(Shape.TRUE, Set.of("SYMBOL", "BOOLEAN", "ATOM", "T"));
		table.put(Shape.NULL, Set.of("NULL", "SYMBOL", "BOOLEAN", "LIST", "SEQUENCE", "ATOM", "T"));
		// A cons is the one shape that is not an atom.
		table.put(Shape.CONS, Set.of("CONS", "LIST", "SEQUENCE", "T"));
		// SIMPLE-VECTOR is CL-strictly (simple-array t (*)), but the runtime type
		// tests here do not discriminate element types, so it stays satisfiable --
		// the same lean the STRING row takes.
		table.put(Shape.VECTOR, Set.of("VECTOR", "SIMPLE-VECTOR", "ARRAY", "SIMPLE-ARRAY", "SEQUENCE", "ATOM", "T"));
		// An instance answers to its own class name and to its superclasses' -- none of
		// which this class decides, so all of them stay satisfiable through the default
		// arm of maySatisfyNamed. What it must NOT answer to is another instance type,
		// pathname above all.
		table.put(Shape.INSTANCE, Set.of("STANDARD-OBJECT", "STRUCTURE-OBJECT", "ATOM", "T"));
		return java.util.Collections.unmodifiableMap(table);
	}

	/**
	 * The type names whose membership this class DECIDES. A name outside it -- a
	 * {@code defclass}/{@code defstruct}/{@code deftype} name, anything a later release
	 * adds -- is never used to prune: {@link #maySatisfy} answers yes for it.
	 */
	private static final Set<String> DECIDED_TYPES = decidedTypes();

	private static Set<String> decidedTypes() {
		Set<String> names = new java.util.HashSet<>();
		SUPERTYPES.values().forEach(names::addAll);
		// Types with no shape in the lattice at all: naming one is what prunes a
		// FUNCTION out of a (or pathname string) clause. Each is a value shape this
		// class can never produce, so "no shape satisfies it" is exact rather than
		// an omission -- the metaclass and bit-vector rows are additionally types
		// makeTypeTest answers NIL for, i.e. types with no instances here at all.
		names.addAll(Set.of("PATHNAME", "HASH-TABLE", "STREAM", "STRUCTURE-CLASS", "BUILT-IN-CLASS", "GENERIC-FUNCTION",
				"STANDARD-GENERIC-FUNCTION", "BIT-VECTOR", "SIMPLE-BIT-VECTOR"));
		return Set.copyOf(names);
	}

	private static Set<String> supertypes(Shape shape) {
		return SUPERTYPES.getOrDefault(shape, Set.of());
	}

	/**
	 * Whether a value of this shape could be of this type -- "could", so the answer is
	 * yes whenever the class is not certain.
	 * @param shape what the call site said about the value
	 * @param typeSpec a type specifier, in the {@code typecase}-clause-head vocabulary
	 * ({@code t} and {@code otherwise} included)
	 * @return false only when no value of that shape is of that type
	 */
	public static boolean maySatisfy(Shape shape, LispVal typeSpec) {
		if (shape == Shape.UNKNOWN) {
			return true;
		}
		if (typeSpec instanceof LispTrue) {
			return true;
		}
		if (typeSpec instanceof LispNil) {
			// The empty type: no object is of type nil.
			return false;
		}
		if (typeSpec instanceof LispCons compound && compound.isProperList()
				&& compound.car() instanceof LispSymbol head) {
			List<LispVal> parts = compound.toList();
			switch (plainName(head)) {
				case LispNames.OR: {
					for (int i = 1; i < parts.size(); i++) {
						if (maySatisfy(shape, parts.get(i))) {
							return true;
						}
					}
					return parts.size() == 1;
				}
				case LispNames.AND: {
					for (int i = 1; i < parts.size(); i++) {
						if (!maySatisfy(shape, parts.get(i))) {
							return false;
						}
					}
					return true;
				}
				// (not x) is satisfiable by everything this lattice can rule out of x,
				// and (member ...)/(eql ...)/(satisfies ...) test VALUES, not shapes.
				case LispNames.NOT, "MEMBER", "EQL", "SATISFIES":
					return true;
				default:
					// (integer 0 10), (simple-array character (*)): the bounds narrow the
					// type, so ignoring them can only widen the answer.
					return maySatisfyNamed(shape, plainName(head));
			}
		}
		if (typeSpec instanceof LispSymbol sym) {
			String name = plainName(sym);
			return LispNames.OTHERWISE.equals(name) || maySatisfyNamed(shape, name);
		}
		// Not a type specifier this class understands.
		return true;
	}

	private static boolean maySatisfyNamed(Shape shape, String name) {
		return !DECIDED_TYPES.contains(name) || supertypes(shape).contains(name);
	}

	/** A type-specifier symbol's name with any package qualifier stripped. */
	private static String plainName(LispSymbol sym) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(sym.name());
		return qualified == null ? sym.name() : qualified.member();
	}

	/**
	 * The shape a form states SYNTACTICALLY, which is the only kind either caller may act
	 * on: a literal, a function reference, or a variable whose shape the environment
	 * already carries. Anything computed -- a call, an arithmetic form, a variable nobody
	 * bound -- is {@link Shape#UNKNOWN}.
	 * @param form the argument form
	 * @param env variable name to shape, empty when the caller tracks no variables
	 * @return the shape, never null
	 */
	public static Shape of(LispVal form, Map<String, Shape> env) {
		return of(form, env, Map.of());
	}

	/**
	 * {@link #of(LispVal, Map)} with the program's function RETURN shapes as well, so a
	 * value one indirection out is still a value the site states: tiny-routes spells its
	 * application {@code (defparameter *app* (routes ...))} over a
	 * {@code (defun routes (&rest handlers) (lambda (request) ...))}.
	 * @param form the argument form
	 * @param env variable name to shape
	 * @param returns function name to return shape, from {@link #returnShapes}
	 * @return the shape, never null
	 */
	public static Shape of(LispVal form, Map<String, Shape> env, Map<String, Shape> returns) {
		if (form instanceof LispSymbol sym) {
			return sym.isKeyword() ? Shape.KEYWORD : env.getOrDefault(sym.name(), Shape.UNKNOWN);
		}
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head) {
			if (LispNames.LAMBDA.equals(head.name()) || LispNames.ASYNC_LAMBDA.equals(head.name())) {
				return Shape.FUNCTION;
			}
			if (LispNames.FUNCTION.equals(head.name())) {
				return Shape.FUNCTION;
			}
			if (LispNames.QUOTE.equals(head.name()) && cons.cdr() instanceof LispCons quoted) {
				return ofDatum(quoted.car());
			}
			if (LispNames.MAKE_INSTANCE.equals(head.name()) && cons.cdr() instanceof LispCons classCell) {
				return ofClassName(classCell.car());
			}
			if (LispNames.MAKE_ARRAY.equals(head.name())) {
				return ofMakeArray(cons);
			}
			if (LispNames.VECTOR.equals(head.name())) {
				return Shape.VECTOR;
			}
			if (LispNames.MAKE_STRING.equals(head.name())) {
				return Shape.STRING;
			}
			if ((LispNames.SUBSEQ.equals(head.name()) || LispNames.COPY_SEQ.equals(head.name()))
					&& cons.cdr() instanceof LispCons seqCell) {
				// Both preserve a string/vector argument's type. A LIST argument is NOT
				// propagated: an empty subseq of a cons is nil, a different shape.
				Shape seq = of(seqCell.car(), env, returns);
				return seq == Shape.STRING || seq == Shape.VECTOR ? seq : Shape.UNKNOWN;
			}
			return returns.getOrDefault(head.name(), Shape.UNKNOWN);
		}
		return ofDatum(form);
	}

	/**
	 * How many rounds the return-shape fixpoint runs; a chain deeper than this stays
	 * open.
	 */
	private static final int RETURN_SHAPE_ROUNDS = 8;

	/**
	 * The shape each of the program's functions RETURNS, for the ones whose answer is one
	 * form the reader can see.
	 *
	 * <p>
	 * The rule is deliberately narrow, because a wrong answer here is a wrong answer at
	 * every call site at once: a name defined by exactly one {@code defun}, whose body's
	 * LAST form has a shape, and which contains no {@code return-from} naming itself --
	 * an early exit is a second answer, and the last form says nothing about it.
	 * Everything else is absent from the map, i.e. {@code UNKNOWN}. The fixpoint is what
	 * lets one such function be spelled in terms of another.
	 * @param program the resolved, flattened top-level forms
	 * @return function name to return shape, for the names that have one
	 */
	public static Map<String, Shape> returnShapes(List<LispVal> program) {
		Map<String, LispVal> lastForms = new HashMap<>();
		java.util.Set<String> excluded = new java.util.HashSet<>();
		for (LispVal form : program) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !LispNames.DEFUN.equals(head.name()) || !cons.isProperList()) {
				continue;
			}
			List<LispVal> parts = cons.toList();
			if (parts.size() < 4 || !(parts.get(1) instanceof LispSymbol name)) {
				continue;
			}
			if (lastForms.put(name.name(), parts.get(parts.size() - 1)) != null || returnsEarly(cons, name.name())) {
				excluded.add(name.name());
			}
		}
		lastForms.keySet().removeAll(excluded);
		Map<String, Shape> shapes = new HashMap<>();
		for (int round = 0; round < RETURN_SHAPE_ROUNDS; round++) {
			boolean changed = false;
			for (Map.Entry<String, LispVal> entry : lastForms.entrySet()) {
				Shape shape = of(entry.getValue(), Map.of(), shapes);
				if (shape != Shape.UNKNOWN && shapes.put(entry.getKey(), shape) != shape) {
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
		return Map.copyOf(shapes);
	}

	/**
	 * Whether a body exits through {@code (return-from name ...)} rather than its end.
	 */
	private static boolean returnsEarly(LispVal form, String name) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.RETURN_FROM.equals(head.name())
				&& cons.cdr() instanceof LispCons target && target.car() instanceof LispSymbol block
				&& name.equals(block.name())) {
			return true;
		}
		return returnsEarly(cons.car(), name) || returnsEarly(cons.cdr(), name);
	}

	/**
	 * {@code (make-instance 'name)}: {@link Shape#INSTANCE} for a class this lattice does
	 * not otherwise decide, and nothing for a literal name it does -- a
	 * {@code (make-instance 'string)} would be a value with a shape of its own, so
	 * claiming INSTANCE for it would be a claim about a different value.
	 */
	private static Shape ofClassName(LispVal classForm) {
		if (classForm instanceof LispCons quoted && quoted.car() instanceof LispSymbol head
				&& LispNames.QUOTE.equals(head.name()) && quoted.cdr() instanceof LispCons datum
				&& datum.car() instanceof LispSymbol className) {
			return DECIDED_TYPES.contains(plainName(className)) ? Shape.UNKNOWN : Shape.INSTANCE;
		}
		return Shape.UNKNOWN;
	}

	/**
	 * {@code (make-array dims ...)}: {@link Shape#VECTOR} only when the shape is certain
	 * -- a literal integer dimension (rank 1) and an element type that cannot make the
	 * result a string ({@code character}) or a bit vector ({@code bit}). A computed
	 * dimension form, a dimension LIST, or an unrecognized element-type spelling stays
	 * {@link Shape#UNKNOWN}.
	 */
	private static Shape ofMakeArray(LispCons cons) {
		if (!(cons.cdr() instanceof LispCons dimsCell) || !(dimsCell.car() instanceof LispInteger)) {
			return Shape.UNKNOWN;
		}
		LispVal rest = dimsCell.cdr();
		while (rest instanceof LispCons keyCell) {
			if (keyCell.car() instanceof LispSymbol key && ":ELEMENT-TYPE".equals(key.name())
					&& keyCell.cdr() instanceof LispCons valueCell) {
				return safeVectorElementType(valueCell.car()) ? Shape.VECTOR : Shape.UNKNOWN;
			}
			rest = keyCell.cdr();
		}
		return Shape.VECTOR;
	}

	/**
	 * Whether a literal {@code :element-type} argument certainly yields a non-string,
	 * non-bit vector: a quoted numeric type -- {@code (unsigned-byte 8)},
	 * {@code single-float}, plain {@code integer} -- or {@code t}.
	 */
	private static boolean safeVectorElementType(LispVal spec) {
		LispVal datum = spec;
		if (spec instanceof LispCons quoted && quoted.car() instanceof LispSymbol head
				&& LispNames.QUOTE.equals(head.name()) && quoted.cdr() instanceof LispCons cell) {
			datum = cell.car();
		}
		else if (spec instanceof LispTrue) {
			return true;
		}
		else if (!(spec instanceof LispCons)) {
			return false;
		}
		String name = switch (datum) {
			case LispSymbol sym -> plainName(sym);
			case LispTrue ignored -> "T";
			case LispCons compound when compound.car() instanceof LispSymbol head -> plainName(head);
			default -> null;
		};
		return name != null && Set
			.of("T", "UNSIGNED-BYTE", "SIGNED-BYTE", "INTEGER", "FIXNUM", "FLOAT", "SINGLE-FLOAT", "DOUBLE-FLOAT",
					"SHORT-FLOAT", "LONG-FLOAT", "REAL", "NUMBER")
			.contains(name);
	}

	/** The shape of a self-evaluating or quoted datum. */
	private static Shape ofDatum(LispVal datum) {
		return switch (datum) {
			case LispString ignored -> Shape.STRING;
			case LispInteger ignored -> Shape.INTEGER;
			case LispBigInteger ignored -> Shape.INTEGER;
			case LispDouble ignored -> Shape.FLOAT;
			case LispRatio ignored -> Shape.RATIO;
			case LispChar ignored -> Shape.CHARACTER;
			case LispTrue ignored -> Shape.TRUE;
			case LispNil ignored -> Shape.NULL;
			case LispCons ignored -> Shape.CONS;
			case LispSymbol sym -> sym.isKeyword() ? Shape.KEYWORD : Shape.SYMBOL;
			default -> Shape.UNKNOWN;
		};
	}

	/**
	 * The shapes of the program's top-level variables -- the other half of "what the call
	 * site states", since a Worker as often writes {@code (clackup *app* ...)} over a
	 * {@code (defvar *app* (make-instance 'ningle:app))} as it writes {@code #'app}
	 * inline.
	 *
	 * <p>
	 * A name is in the answer only when the whole program pins it: defined ONCE, with an
	 * initializer of a known shape, and never assigned as a bare symbol anywhere
	 * ({@code setq}/{@code setf}/{@code push}/{@code incf}/...). Mutating a PLACE the
	 * variable holds -- ningle's {@code (setf (ningle:route *app* "/") ...)} -- leaves
	 * the variable itself alone and so leaves its shape standing, which is the
	 * distinction that makes this worth having.
	 * @param program the resolved, flattened top-level forms
	 * @param returns the function return shapes, from {@link #returnShapes}
	 * @return variable name to shape, for the names that have one
	 */
	public static Map<String, Shape> globals(List<LispVal> program, Map<String, Shape> returns) {
		Map<String, Shape> defined = new HashMap<>();
		java.util.Set<String> redefined = new java.util.HashSet<>();
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& (LispNames.DEFVAR.equals(head.name()) || LispNames.DEFPARAMETER.equals(head.name())
							|| LispNames.DEFCONSTANT.equals(head.name()))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				Shape shape = rest.cdr() instanceof LispCons initCell ? of(initCell.car(), Map.of(), returns)
						: Shape.UNKNOWN;
				if (defined.put(name.name(), shape) != null) {
					redefined.add(name.name());
				}
			}
		}
		java.util.Set<String> assigned = new java.util.HashSet<>();
		program.forEach(form -> collectAssigned(form, assigned));
		defined.keySet().removeAll(redefined);
		defined.keySet().removeAll(assigned);
		defined.keySet().removeAll(boundAnywhere(program));
		defined.values().removeIf(shape -> shape == Shape.UNKNOWN);
		return Map.copyOf(defined);
	}

	/** Every name assigned as a bare symbol anywhere in a form. */
	private static void collectAssigned(LispVal form, java.util.Set<String> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			switch (head.name()) {
				case LispNames.QUOTE:
					return;
				case LispNames.SETQ, LispNames.SETF, LispNames.PSETQ, LispNames.PSETF: {
					for (int i = 1; i < parts.size(); i += 2) {
						addSymbol(parts.get(i), out);
					}
					break;
				}
				case LispNames.INCF, LispNames.DECF, LispNames.POP: {
					if (parts.size() > 1) {
						addSymbol(parts.get(1), out);
					}
					break;
				}
				case LispNames.PUSH, LispNames.PUSHNEW: {
					if (parts.size() > 2) {
						addSymbol(parts.get(2), out);
					}
					break;
				}
				case LispNames.ROTATEF, LispNames.SHIFTF: {
					for (int i = 1; i < parts.size(); i++) {
						addSymbol(parts.get(i), out);
					}
					break;
				}
				case LispNames.MULTIPLE_VALUE_SETQ: {
					LispVal targets = parts.size() > 1 ? parts.get(1) : LispNil.INSTANCE;
					while (targets instanceof LispCons cell) {
						addSymbol(cell.car(), out);
						targets = cell.cdr();
					}
					break;
				}
				default:
			}
		}
		collectAssigned(cons.car(), out);
		LispVal rest = cons.cdr();
		while (rest instanceof LispCons cell) {
			collectAssigned(cell.car(), out);
			rest = cell.cdr();
		}
	}

	private static void addSymbol(LispVal place, java.util.Set<String> out) {
		if (place instanceof LispSymbol sym) {
			out.add(sym.name());
		}
	}

	// ---------------------------------------------------------------------------
	// The names a body rebinds outside the scoping its walker models
	// ---------------------------------------------------------------------------

	/**
	 * Assignment operators: a variable one of these touches has no single shape, whatever
	 * the caller said, because neither reader of this class is flow-sensitive.
	 */
	private static final Set<String> ASSIGNING_HEADS = Set.of(LispNames.SETQ, LispNames.SETF, LispNames.PSETQ,
			LispNames.PSETF, LispNames.INCF, LispNames.DECF, LispNames.PUSH, LispNames.PUSHNEW, LispNames.POP,
			LispNames.ROTATEF, LispNames.SHIFTF, LispNames.MULTIPLE_VALUE_SETQ, LispNames.REMF);

	/**
	 * Binding forms whose SCOPE the shape walkers do not model, so every name they
	 * mention in their binding position leaves the shape environment for the whole body.
	 * Precision is all that is lost: the name simply becomes {@code UNKNOWN}, which
	 * prunes nothing. Completeness is not a hope -- a binding form outside this list and
	 * the ones modelled properly
	 * ({@code let}/{@code let*}/{@code do}/{@code do*}/{@code flet}/{@code labels} and
	 * {@code lambda}) is one this compiler does not implement, so it cannot appear in a
	 * program it compiles.
	 */
	private static final Set<String> UNMODELLED_BINDING_HEADS = Set.of(LispNames.MULTIPLE_VALUE_BIND,
			LispNames.DESTRUCTURING_BIND, LispNames.DOLIST, LispNames.DOTIMES, LispNames.WITH_SLOTS,
			LispNames.WITH_ACCESSORS, LispNames.PROG, LispNames.PROG_STAR, LispNames.WITH_OPEN_FILE,
			LispNames.WITH_OPEN_STREAM, LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING,
			LispNames.PPRINT_LOGICAL_BLOCK, LispNames.DO_EXTERNAL_SYMBOLS, LispNames.MACROLET,
			LispNames.SYMBOL_MACROLET);

	/** {@code (head form (type (var) body...) ...)}: the clause variable binds. */
	private static final Set<String> CLAUSE_BINDING_HEADS = Set.of(LispNames.HANDLER_CASE, LispNames.RESTART_CASE);

	/** The definition heads whose parameters belong to a scope of their own. */
	private static final Set<String> DEFINITION_HEADS = Set.of(LispNames.DEFUN, LispNames.ASYNC_DEFUN,
			LispNames.DEFMACRO, LispNames.DEFMETHOD, LispNames.DEFGENERIC);

	/** The binding heads whose second element is a {@code ((var init) ...)} list. */
	private static final Set<String> PAIR_BINDING_HEADS = Set.of(LispNames.LET, LispNames.LET_STAR, LispNames.DO,
			LispNames.DO_STAR, LispNames.PROG, LispNames.PROG_STAR);

	/**
	 * Every name a body rebinds or assigns outside the modelled scoping. A shape must not
	 * survive into one of these: a caller's {@code #'app} says nothing about the
	 * {@code app} some {@code dolist} inside the callee binds.
	 * @param forms the body forms
	 * @return the names that must read as {@link Shape#UNKNOWN} throughout
	 */
	public static Set<String> shadowedNames(List<LispVal> forms) {
		java.util.Set<String> out = new java.util.HashSet<>();
		forms.forEach(form -> collectShadowed(form, out, true));
		return out;
	}

	/**
	 * @param assignments whether an assignment operator's whole form counts. It does for
	 * a BODY, where a shape must survive no {@code setq}; it must not for
	 * {@link #boundAnywhere}, whose caller already has an exact assignment scan and whose
	 * question is binding, not mutation -- ningle's
	 * {@code (setf (ningle:route *app* "/") ...)} mutates a place the variable holds and
	 * mentions the variable while doing it.
	 */
	private static void collectShadowed(LispVal form, java.util.Set<String> out, boolean assignments) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head) {
			String name = head.name();
			if (LispNames.QUOTE.equals(name) || DEFINITION_HEADS.contains(name)) {
				// Data, and a nested definition's parameters, are a different scope.
				return;
			}
			if (LispNames.LOOP.equals(name) || (assignments && ASSIGNING_HEADS.contains(name))) {
				// A loop's own bindings are spread over its keywords, so every symbol in
				// it counts -- the alternative is parsing the loop grammar twice.
				collectSymbols(cons.cdr(), out);
				return;
			}
			if (UNMODELLED_BINDING_HEADS.contains(name) && cons.cdr() instanceof LispCons rest) {
				collectSymbols(rest.car(), out);
			}
			else if (CLAUSE_BINDING_HEADS.contains(name) && cons.cdr() instanceof LispCons rest) {
				LispVal clauses = rest.cdr();
				while (clauses instanceof LispCons clauseCell) {
					if (clauseCell.car() instanceof LispCons clause && clause.cdr() instanceof LispCons varCell) {
						collectSymbols(varCell.car(), out);
					}
					clauses = clauseCell.cdr();
				}
			}
		}
		collectShadowed(cons.car(), out, assignments);
		LispVal rest = cons.cdr();
		while (rest instanceof LispCons cell) {
			collectShadowed(cell.car(), out, assignments);
			rest = cell.cdr();
		}
	}

	/** Every symbol anywhere in a tree. */
	private static void collectSymbols(LispVal form, java.util.Set<String> out) {
		if (form instanceof LispSymbol sym) {
			out.add(sym.name());
			return;
		}
		if (form instanceof LispCons cons) {
			collectSymbols(cons.car(), out);
			collectSymbols(cons.cdr(), out);
		}
	}

	/**
	 * Every name the program binds ANYWHERE, in any binding form. What this subtracts
	 * from {@link #globals} is the dynamic rebinding a lexical walk cannot see: a special
	 * variable's global initializer says nothing inside a callee some caller entered
	 * through {@code (let ((*app* "x")) ...)}, and the callee's own body is where the
	 * walk is looking.
	 */
	private static java.util.Set<String> boundAnywhere(List<LispVal> program) {
		java.util.Set<String> out = new java.util.HashSet<>();
		program.forEach(form -> collectShadowed(form, out, false));
		program.forEach(form -> collectBound(form, out));
		return out;
	}

	private static void collectBound(LispVal form, java.util.Set<String> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && cons.cdr() instanceof LispCons rest) {
			String name = head.name();
			if (LispNames.QUOTE.equals(name)) {
				return;
			}
			if (PAIR_BINDING_HEADS.contains(name)) {
				LispVal bindings = rest.car();
				while (bindings instanceof LispCons cell) {
					collectSymbols(cell.car() instanceof LispCons pair ? pair.car() : cell.car(), out);
					bindings = cell.cdr();
				}
			}
			else if (LispNames.LAMBDA.equals(name) || LispNames.ASYNC_LAMBDA.equals(name)) {
				collectSymbols(rest.car(), out);
			}
			else if (DEFINITION_HEADS.contains(name) && rest.cdr() instanceof LispCons afterName) {
				// (defun name (ll) ...) / (defmethod name qualifier* (ll) ...)
				LispVal cursor = afterName;
				while (cursor instanceof LispCons cell && cell.car() instanceof LispSymbol) {
					cursor = cell.cdr();
				}
				if (cursor instanceof LispCons cell) {
					collectSymbols(cell.car(), out);
				}
			}
			else if (LispNames.FLET.equals(name) || LispNames.LABELS.equals(name)) {
				LispVal locals = rest.car();
				while (locals instanceof LispCons cell) {
					if (cell.car() instanceof LispCons local && local.cdr() instanceof LispCons afterName) {
						collectSymbols(afterName.car(), out);
					}
					locals = cell.cdr();
				}
			}
		}
		collectBound(cons.car(), out);
		LispVal rest = cons.cdr();
		while (rest instanceof LispCons cell) {
			collectBound(cell.car(), out);
			rest = cell.cdr();
		}
	}

	/**
	 * The join of two shapes: they agree, or nothing is known.
	 * @param a one shape
	 * @param b the other
	 * @return the shape both states satisfy
	 */
	public static Shape join(Shape a, Shape b) {
		return a == b ? a : Shape.UNKNOWN;
	}

	/**
	 * Binds positional argument shapes to a lambda list's parameters. EVERY parameter the
	 * list names is in the answer, {@link Shape#UNKNOWN} where nothing is known: a
	 * parameter missing from the map would read through to an enclosing binding of the
	 * same name, which is exactly the shadowing bug that turns a narrowing into a wrong
	 * one.
	 * @param lambdaList the parameter list ({@code &optional} positional; a specialized
	 * {@code (var class)} element names {@code var}), or null where there is none
	 * @param argShapes the shapes the call site stated, in order
	 * @return parameter name to shape
	 */
	public static Map<String, Shape> bind(@Nullable LispVal lambdaList, List<Shape> argShapes) {
		Map<String, Shape> bound = new HashMap<>();
		int positional = 0;
		boolean positionalSection = true;
		LispVal rest = lambdaList;
		while (rest instanceof LispCons cons) {
			LispVal element = cons.car();
			if (element instanceof LispSymbol sym && sym.name().startsWith("&")) {
				// &optional keeps consuming positions; every other keyword ends them.
				positionalSection = "&OPTIONAL".equals(sym.name());
			}
			else {
				String name = parameterName(element);
				if (name != null) {
					Shape shape = Shape.UNKNOWN;
					if (positionalSection && positional < argShapes.size()) {
						shape = argShapes.get(positional);
					}
					bound.put(name, shape);
				}
				if (positionalSection) {
					positional++;
				}
			}
			rest = cons.cdr();
		}
		if (rest instanceof LispSymbol tail) {
			// A dotted lambda list's tail is a rest parameter.
			bound.put(tail.name(), Shape.UNKNOWN);
		}
		return bound;
	}

	/**
	 * The variable one lambda-list element names: the symbol itself, or the first symbol
	 * of {@code (var init)} / {@code (var class)} / {@code ((:key var) init)}.
	 */
	private static @Nullable String parameterName(LispVal element) {
		if (element instanceof LispSymbol sym) {
			return sym.name();
		}
		if (element instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol sym) {
				return sym.name();
			}
			if (cons.car() instanceof LispCons keyed && keyed.cdr() instanceof LispCons varCell
					&& varCell.car() instanceof LispSymbol var) {
				return var.name();
			}
		}
		return null;
	}

}
