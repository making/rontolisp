package am.ik.rontolisp.starlark;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.starlark.StarlarkBuiltins.Signature;
import am.ik.rontolisp.starlark.StarlarkSyntax.Argument;
import am.ik.rontolisp.starlark.StarlarkSyntax.ArgumentKind;
import am.ik.rontolisp.starlark.StarlarkSyntax.Assignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.AugmentedAssignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.Binary;
import am.ik.rontolisp.starlark.StarlarkSyntax.BinaryOperator;
import am.ik.rontolisp.starlark.StarlarkSyntax.Break;
import am.ik.rontolisp.starlark.StarlarkSyntax.BytesLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Call;
import am.ik.rontolisp.starlark.StarlarkSyntax.Clause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Conditional;
import am.ik.rontolisp.starlark.StarlarkSyntax.Continue;
import am.ik.rontolisp.starlark.StarlarkSyntax.Def;
import am.ik.rontolisp.starlark.StarlarkSyntax.DictComprehension;
import am.ik.rontolisp.starlark.StarlarkSyntax.DictLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Dot;
import am.ik.rontolisp.starlark.StarlarkSyntax.Entry;
import am.ik.rontolisp.starlark.StarlarkSyntax.Expression;
import am.ik.rontolisp.starlark.StarlarkSyntax.ExpressionStatement;
import am.ik.rontolisp.starlark.StarlarkSyntax.FloatLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.For;
import am.ik.rontolisp.starlark.StarlarkSyntax.ForClause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Identifier;
import am.ik.rontolisp.starlark.StarlarkSyntax.If;
import am.ik.rontolisp.starlark.StarlarkSyntax.IfClause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Index;
import am.ik.rontolisp.starlark.StarlarkSyntax.IntLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Lambda;
import am.ik.rontolisp.starlark.StarlarkSyntax.ListComprehension;
import am.ik.rontolisp.starlark.StarlarkSyntax.ListLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Load;
import am.ik.rontolisp.starlark.StarlarkSyntax.Parameter;
import am.ik.rontolisp.starlark.StarlarkSyntax.Pass;
import am.ik.rontolisp.starlark.StarlarkSyntax.Return;
import am.ik.rontolisp.starlark.StarlarkSyntax.Slice;
import am.ik.rontolisp.starlark.StarlarkSyntax.Statement;
import am.ik.rontolisp.starlark.StarlarkSyntax.StringLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.TupleLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Unary;
import am.ik.rontolisp.starlark.StarlarkSyntax.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Lowers a Starlark syntax tree to the Common Lisp core forms the pipeline already
 * consumes, so a Starlark program joins {@code CompileFrontend.expand} and
 * {@code LispEvaluator} unchanged and no backend learns a new operator. The lowering
 * table and the decisions behind each row: {@code .kb/starlark-frontend.md}.
 *
 * <p>
 * The shape of the pass:
 * <ul>
 * <li><b>One file at a time, whole file at once.</b> A name is resolved statically: to
 * the innermost function or comprehension that binds it anywhere in its body, else to the
 * file's globals, else to a built-in. A name bound nowhere is an error at its position
 * before anything runs.</li>
 * <li><b>Two call tiers.</b> A call whose callee and argument shape are known while
 * lowering -- a top-level {@code def} bound once, a built-in function, a method -- is a
 * direct call of a Common Lisp function with every parameter in place. Any other call
 * hands the callee, the positional arguments and the named ones to the run-time binder
 * ({@code rontolisp::%starlark-call}). Both tiers evaluate the arguments in source
 * order.</li>
 * <li><b>Control flow is lexical.</b> A function body is a {@code block} around a
 * {@code let} of every name it assigns, a {@code for} a {@code tagbody} over the
 * iterable's vector, {@code return} / {@code break} / {@code continue} a
 * {@code return-from}; no exit crosses a {@code lambda}. A {@code return} inside loops
 * ends each loop's count on the iterable itself before it leaves, so no
 * {@code unwind-protect} is needed.</li>
 * <li><b>A read is checked only where it can come before the assignment.</b> A
 * definite-assignment pass runs with the lowering: a name assigned on every path to a
 * read is read as it stands, any other read goes through
 * {@code rontolisp::%starlark-local} or {@code -global}, which refuses the unassigned
 * marker. A function's body starts with what was assigned where the function value was
 * made: a variable is never unassigned again.</li>
 * <li><b>Every statement and expression form is located</b> at its node's position, as a
 * reader locates what it reads: recorded in the compile path's table, a
 * {@link LocatedCons} for the interpreter, so an uncaught error names the line it
 * happened on.</li>
 * <li><b>Truth is fused into tests.</b> A comparison, {@code not} and the Boolean
 * constants answer the Common Lisp booleans, so a condition built of them is tested as it
 * stands; only a value of unknown type goes through
 * {@code rontolisp::%starlark-truth}.</li>
 * </ul>
 */
final class StarlarkLowering {

	private static final LispSymbol QUOTE = new LispSymbol("QUOTE");

	private static final LispSymbol FUNCTION = new LispSymbol("FUNCTION");

	private static final LispSymbol LAMBDA = new LispSymbol("LAMBDA");

	private static final LispSymbol DEFUN = new LispSymbol("DEFUN");

	private static final LispSymbol SETQ = new LispSymbol("SETQ");

	private static final LispSymbol LET = new LispSymbol("LET");

	private static final LispSymbol LET_STAR = new LispSymbol("LET*");

	private static final LispSymbol PROGN = new LispSymbol("PROGN");

	private static final LispSymbol IF = new LispSymbol("IF");

	private static final LispSymbol AND = new LispSymbol("AND");

	private static final LispSymbol OR = new LispSymbol("OR");

	private static final LispSymbol NOT = new LispSymbol("NOT");

	private static final LispSymbol BLOCK = new LispSymbol("BLOCK");

	private static final LispSymbol RETURN_FROM = new LispSymbol("RETURN-FROM");

	private static final LispSymbol TAGBODY = new LispSymbol("TAGBODY");

	private static final LispSymbol GO = new LispSymbol("GO");

	private static final LispSymbol LIST = new LispSymbol("LIST");

	private static final LispSymbol APPEND = new LispSymbol("APPEND");

	private static final LispSymbol AREF = new LispSymbol("AREF");

	private static final LispSymbol LENGTH = new LispSymbol("LENGTH");

	private static final LispSymbol LESS = new LispSymbol("<");

	private static final LispSymbol PLUS = new LispSymbol("+");

	private static final LispSymbol VECTOR_PUSH_EXTEND = new LispSymbol("VECTOR-PUSH-EXTEND");

	/** The block a function body is: what {@code return} leaves. */
	private static final LispSymbol FUNCTION_BLOCK = new LispSymbol("%SL-FN");

	/** The block a loop is: what {@code break} leaves. */
	private static final LispSymbol BREAK_BLOCK = new LispSymbol("%SL-BREAK");

	/** The block a loop's body is: what {@code continue} leaves. */
	private static final LispSymbol CONTINUE_BLOCK = new LispSymbol("%SL-CONTINUE");

	private static final LispSymbol TRUTH = runtime("TRUTH");

	private static final LispSymbol CALL = runtime("CALL");

	private static final LispSymbol STAR_ELEMENTS = runtime("STAR-ELEMENTS");

	private static final LispSymbol KEYWORDS = runtime("KEYWORDS");

	private static final LispSymbol MAKE_FUNCTION = runtime("MAKE-FUNCTION");

	private static final LispSymbol BUILTIN = runtime("BUILTIN");

	private static final LispSymbol METHOD = runtime("METHOD");

	private static final LispSymbol NO_ATTRIBUTE = runtime("NO-ATTRIBUTE");

	private static final LispSymbol SET_ATTRIBUTE = runtime("SET-ATTRIBUTE");

	private static final LispSymbol MAKE_LIST = runtime("MAKE-LIST");

	private static final LispSymbol MAKE_TUPLE = runtime("MAKE-TUPLE");

	private static final LispSymbol MAKE_DICT = runtime("MAKE-DICT");

	private static final LispSymbol EMPTY_DICT = runtime("EMPTY-DICT");

	private static final LispSymbol VECTOR = runtime("VECTOR");

	private static final LispSymbol LIST_FROM = runtime("LIST-FROM");

	private static final LispSymbol INDEX = runtime("INDEX");

	private static final LispSymbol SET_INDEX = runtime("SET-INDEX");

	private static final LispSymbol SLICE = runtime("SLICE");

	private static final LispSymbol UNPACK = runtime("UNPACK");

	private static final LispSymbol ITERATE = runtime("ITERATE");

	private static final LispSymbol ITERATED = runtime("ITERATED");

	private static final LispSymbol EQUAL = runtime("EQUAL");

	private static final LispSymbol IN = runtime("IN");

	private static final LispSymbol ADD_IN_PLACE = runtime("ADD-IN-PLACE");

	private static final LispSymbol LOCAL = runtime("LOCAL");

	private static final LispSymbol GLOBAL = runtime("GLOBAL");

	private static final LispSymbol RECURSIVE = runtime("RECURSIVE");

	/** What a variable holds until it is assigned: a value no program can make. */
	private static final LispSymbol UNBOUND = new LispSymbol(":%UNBOUND");

	private static final Map<String, LispVal> CONSTANTS = Map.of("None", StarlarkBuiltins.NONE, "True",
			LispTrue.INSTANCE, "False", LispNil.INSTANCE);

	/** The names the file binds at its top level. */
	private final Set<String> globals = new LinkedHashSet<>();

	/**
	 * The top-level functions with a direct entry: a {@code def} standing at the top
	 * level, the only binding of its name in the file, with no {@code *} parameter.
	 */
	private final Map<String, Def> direct = new LinkedHashMap<>();

	private final StarlarkStandard standard;

	/** The direct entries' {@code defun}s, which go ahead of every statement. */
	private final List<LispVal> hoisted = new ArrayList<>();

	/** The globals some read checks: each starts as {@link #UNBOUND}. */
	private final Set<String> checkedGlobals = new LinkedHashSet<>();

	/**
	 * Under {@link StarlarkStandard#SPEC}, one variable per function declaration, true
	 * while a call of it runs.
	 */
	private final List<LispSymbol> active = new ArrayList<>();

	/** What is assigned where the lowering stands. */
	private Flow flow = new Flow(new HashSet<>(), false);

	private int counter;

	/** The compile path's unit the forms are recorded in, or {@code null}. */
	private final SourceProvenance.@Nullable Unit unit;

	/**
	 * The file each form is located in as a {@link LocatedCons}, or {@code null}: a named
	 * file lowered with no compile-path scope open, i.e. the interpreter's.
	 */
	private final @Nullable String runtimeFile;

	/** The offset each line of the source starts at, for {@link #unit}. */
	private final int[] lineStarts;

	private StarlarkLowering(StarlarkStandard standard, String source, @Nullable String file) {
		this.standard = standard;
		this.unit = SourceProvenance.unit(file, source);
		this.runtimeFile = this.unit == null ? file : null;
		List<Integer> starts = new ArrayList<>(List.of(0));
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Lowers a file.
	 * @param file the file's statements
	 * @param standard what the file is read against
	 * @param source the file's text, which the positions are in
	 * @param name the origin file, or {@code null} when unknown
	 * @return the top-level forms
	 * @throws LispReadException on a name no binding or built-in answers, on a construct
	 * the front end does not have yet and on one the standard refuses, at its position
	 */
	static List<LispVal> lower(List<Statement> file, StarlarkStandard standard, String source, @Nullable String name) {
		return new StarlarkLowering(standard, source, name).file(file);
	}

	/**
	 * A form located at a node's position: recorded in the compile path's table, or for
	 * the interpreter answered as a located copy -- so the caller uses the value
	 * returned.
	 */
	private LispVal located(LispVal form, SourceLocation at) {
		if (!(form instanceof LispCons cons) || form instanceof LocatedCons) {
			return form;
		}
		if (this.runtimeFile != null) {
			return new LocatedCons(cons.car(), cons.cdr(), this.runtimeFile, at.line());
		}
		if (this.unit != null && at.line() >= 1 && at.line() <= this.lineStarts.length) {
			SourceProvenance.record(cons, this.unit, this.lineStarts[at.line() - 1] + at.column() - 1);
		}
		return form;
	}

	private List<LispVal> file(List<Statement> statements) {
		if (this.standard == StarlarkStandard.SPEC) {
			specTopLevel(statements);
		}
		Map<String, Integer> bound = new LinkedHashMap<>();
		bindings(statements, bound);
		this.globals.addAll(bound.keySet());
		for (Statement statement : statements) {
			if (statement instanceof Def def && bound.getOrDefault(def.name(), 0) == 1
					&& new Parameters(def.parameters()).isPlain()) {
				this.direct.put(def.name(), def);
			}
		}
		List<LispVal> body = statements(statements, new Frame(Scope.TOP));
		// Every direct entry ahead of the first statement: a defun exists from the start
		// in a compiled program and only once evaluated in the interpreter, so a call
		// written above its def would otherwise run on three backends and fail on one.
		// Such a call checks the global its def assigns, as any read before it does.
		List<LispVal> forms = new ArrayList<>(this.hoisted);
		for (String name : this.checkedGlobals) {
			forms.add(list(SETQ, StarlarkNames.symbol(name), UNBOUND));
		}
		for (LispSymbol flag : this.active) {
			forms.add(list(SETQ, flag, LispNil.INSTANCE));
		}
		forms.addAll(body);
		return List.copyOf(forms);
	}

	/**
	 * The specification's top-level rules: no {@code if} or {@code for}, and a global
	 * bound once.
	 */
	private static void specTopLevel(List<Statement> statements) {
		Map<String, SourceLocation> declared = new HashMap<>();
		for (Statement statement : statements) {
			switch (statement) {
				case If conditional -> throw error("if statement not within a function", conditional.at());
				case For loop -> throw error("for loop not within a function", loop.at());
				case Assignment assignment -> declare(assignment.target(), declared);
				case AugmentedAssignment assignment -> declare(assignment.target(), declared);
				case Def def -> declare(def.name(), def.at(), declared);
				default -> {
				}
			}
		}
	}

	private static void declare(Expression target, Map<String, SourceLocation> declared) {
		switch (target) {
			case Identifier identifier -> declare(identifier.name(), identifier.at(), declared);
			case TupleLiteral tuple -> tuple.elements().forEach(element -> declare(element, declared));
			case ListLiteral list -> list.elements().forEach(element -> declare(element, declared));
			default -> {
			}
		}
	}

	private static void declare(String name, SourceLocation at, Map<String, SourceLocation> declared) {
		SourceLocation first = declared.putIfAbsent(name, at);
		if (first != null) {
			throw error(
					"cannot reassign global " + name + " declared at "
							+ (first.file() == null ? "" : first.file() + ":") + first.line() + ":" + first.column(),
					at);
		}
	}

	// ---- definite assignment ----

	/**
	 * The names assigned on every path to where the lowering stands, or {@code dead} when
	 * no path reaches it (after a {@code return}, {@code break} or {@code continue}),
	 * where every name counts as assigned. Names, not bindings: a function or
	 * comprehension takes its own names off when it starts.
	 */
	private static final class Flow {

		private final Set<String> assigned;

		private boolean dead;

		Flow(Set<String> assigned, boolean dead) {
			this.assigned = assigned;
			this.dead = dead;
		}

		Flow copy() {
			return new Flow(new HashSet<>(this.assigned), this.dead);
		}

		boolean has(String name) {
			return this.dead || this.assigned.contains(name);
		}

		/** Where two paths join: what both assigned, a dead one contributing nothing. */
		static Flow join(Flow a, Flow b) {
			if (a.dead) {
				return b;
			}
			if (!b.dead) {
				a.assigned.retainAll(b.assigned);
			}
			return a;
		}

	}

	/**
	 * A read of a variable: as it stands where it is assigned on every path, else through
	 * the check that refuses {@link #UNBOUND}.
	 */
	private LispVal read(String name, Scope scope) {
		LispSymbol symbol = StarlarkNames.symbol(name);
		if (this.flow.has(name)) {
			return symbol;
		}
		boolean local = scope.binds(name);
		if (!local) {
			this.checkedGlobals.add(name);
		}
		return list(local ? LOCAL : GLOBAL, symbol, string(name));
	}

	/**
	 * Enters a function or comprehension body: what is assigned there at first is what is
	 * assigned where it is made, less the names it binds itself, plus those it is handed.
	 * @return the flow to restore when the body is done
	 */
	private Flow enter(Set<String> own, List<String> handed) {
		Flow outer = this.flow;
		this.flow = outer.copy();
		this.flow.assigned.removeAll(own);
		this.flow.assigned.addAll(handed);
		return outer;
	}

	// ---- scopes ----

	/** The names a function or a comprehension binds, inside the scope it stands in. */
	private record Scope(@Nullable Scope parent, Set<String> names) {

		/** The file's top level: its names are the globals, no local. */
		static final Scope TOP = new Scope(null, Set.of());

		boolean binds(String name) {
			for (Scope scope = this; scope != null; scope = scope.parent) {
				if (scope.names.contains(name)) {
					return true;
				}
			}
			return false;
		}

		Scope inner(Set<String> names) {
			return new Scope(this, names);
		}

	}

	/**
	 * Where a statement stands: the scope its names resolve in and the loops of its own
	 * function around it, innermost first, by the variable holding each one's iterable.
	 */
	private static final class Frame {

		private final Scope scope;

		private final Deque<LispSymbol> loops = new ArrayDeque<>();

		Frame(Scope scope) {
			this.scope = scope;
		}

	}

	/** Counts, per name, the bindings these statements make in their own block. */
	private static void bindings(List<Statement> statements, Map<String, Integer> out) {
		for (Statement statement : statements) {
			switch (statement) {
				case Assignment assignment -> targetNames(assignment.target(), out);
				case AugmentedAssignment assignment -> targetNames(assignment.target(), out);
				case For loop -> {
					targetNames(loop.target(), out);
					bindings(loop.body(), out);
				}
				case If conditional -> {
					bindings(conditional.then(), out);
					bindings(conditional.otherwise(), out);
				}
				case Def def -> out.merge(def.name(), 1, Integer::sum);
				case Load load -> load.bindings().forEach(binding -> out.merge(binding.local(), 1, Integer::sum));
				default -> {
				}
			}
		}
	}

	private static void targetNames(Expression target, Map<String, Integer> out) {
		switch (target) {
			case Identifier identifier -> out.merge(identifier.name(), 1, Integer::sum);
			case TupleLiteral tuple -> tuple.elements().forEach(element -> targetNames(element, out));
			case ListLiteral list -> list.elements().forEach(element -> targetNames(element, out));
			default -> {
			}
		}
	}

	private LispVal reference(Identifier identifier, Scope scope) {
		String name = identifier.name();
		if (isVariable(name, scope)) {
			return read(name, scope);
		}
		LispVal constant = CONSTANTS.get(name);
		if (constant != null) {
			return constant;
		}
		Signature builtin = StarlarkBuiltins.function(name);
		if (builtin != null) {
			return builtinValue(builtin);
		}
		throw error("undefined: " + name, identifier.at());
	}

	private boolean isVariable(String name, Scope scope) {
		return scope.binds(name) || this.globals.contains(name);
	}

	// ---- functions ----

	/**
	 * A parameter list by what each parameter takes: positional (or named) arguments,
	 * named ones only, the surplus positional ones, the surplus named ones.
	 */
	private static final class Parameters {

		private final List<String> positional = new ArrayList<>();

		private final List<String> keywordOnly = new ArrayList<>();

		/** Parallel to {@link #filled()}: {@code null} where an argument is needed. */
		private final List<@Nullable Expression> defaults = new ArrayList<>();

		private @Nullable String varargs;

		private @Nullable String kwargs;

		Parameters(List<Parameter> declared) {
			boolean star = false;
			for (Parameter parameter : declared) {
				switch (parameter.kind()) {
					case STAR -> {
						star = true;
						this.varargs = parameter.name();
					}
					case STAR_STAR -> this.kwargs = parameter.name();
					case REQUIRED, OPTIONAL -> {
						(star ? this.keywordOnly : this.positional)
							.add(Objects.requireNonNull(parameter.name(), "a parameter has a name"));
						this.defaults.add(parameter.defaultValue());
					}
				}
			}
		}

		/** No {@code *}: every parameter can be filled by position. */
		boolean isPlain() {
			return this.keywordOnly.isEmpty() && this.varargs == null && this.kwargs == null;
		}

		/** The parameters an argument fills, positional first. */
		List<String> filled() {
			List<String> names = new ArrayList<>(this.positional);
			names.addAll(this.keywordOnly);
			return names;
		}

		/** Every name the function's body is handed, in the order it takes them. */
		List<String> names() {
			List<String> names = filled();
			if (this.varargs != null) {
				names.add(this.varargs);
			}
			if (this.kwargs != null) {
				names.add(this.kwargs);
			}
			return names;
		}

		LispVal lambdaList() {
			return listOf(names().stream().map(StarlarkNames::symbol).toList());
		}

	}

	/**
	 * A {@code def} as a Common Lisp function: its lambda list, then its body -- a
	 * {@code let} of every name the statements assign, inside a {@code block} when one of
	 * them is a {@code return} that has to leave from the middle.
	 * @param parameters the function's parameters
	 * @param statements its statements
	 * @param enclosing the scope the function is written in
	 * @param name the function's name, which its body finds assigned
	 * @return the lambda list and the body forms, what follows {@code lambda} or a
	 * {@code defun}'s name
	 */
	private LispVal function(Parameters parameters, List<Statement> statements, Scope enclosing, String name) {
		Set<String> names = new LinkedHashSet<>(parameters.names());
		Map<String, Integer> assigned = new LinkedHashMap<>();
		bindings(statements, assigned);
		List<LispVal> locals = new ArrayList<>();
		for (String local : assigned.keySet()) {
			if (names.add(local)) {
				locals.add(list(StarlarkNames.symbol(local), UNBOUND));
			}
		}
		Flow outer = enter(names, parameters.names());
		// The body runs only once the def statement assigned the name.
		if (!names.contains(name)) {
			this.flow.assigned.add(name);
		}
		Scope scope = enclosing.inner(names);
		// A return ending the body is the body's value: it needs no block to leave.
		List<Statement> block = statements;
		Expression last = null;
		if (!block.isEmpty() && block.get(block.size() - 1) instanceof Return returned) {
			last = returned.value();
			block = block.subList(0, block.size() - 1);
		}
		List<LispVal> forms = new ArrayList<>(statements(block, new Frame(scope)));
		forms.add(last == null ? StarlarkBuiltins.NONE : expression(last, scope));
		if (!locals.isEmpty()) {
			forms = List.of(cons(LET, cons(listOf(locals), listOf(forms))));
		}
		if (returns(block)) {
			forms = List.of(cons(BLOCK, cons(FUNCTION_BLOCK, listOf(forms))));
		}
		this.flow = outer;
		return cons(parameters.lambdaList(), listOf(once(forms, name)));
	}

	/**
	 * A function's body forms, under {@link StarlarkStandard#SPEC} refusing a call while
	 * a call of the same declaration runs: a variable of the declaration's own is true
	 * meanwhile. It is set back on every normal exit and not on an error, which ends the
	 * program.
	 */
	private List<LispVal> once(List<LispVal> forms, String name) {
		if (this.standard != StarlarkStandard.SPEC) {
			return forms;
		}
		LispSymbol flag = fresh("ACTIVE");
		this.active.add(flag);
		LispSymbol result = fresh("RESULT");
		return List.of(list(IF, flag, list(RECURSIVE, string(name))), list(SETQ, flag, LispTrue.INSTANCE),
				list(LET, list(list(result, progn(forms))), list(SETQ, flag, LispNil.INSTANCE), result));
	}

	/** Whether a {@code return} of this function stands in the statements. */
	private static boolean returns(List<Statement> statements) {
		for (Statement statement : statements) {
			boolean found = switch (statement) {
				case Return _ -> true;
				case If conditional -> returns(conditional.then()) || returns(conditional.otherwise());
				case For loop -> returns(loop.body());
				default -> false;
			};
			if (found) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The function value a {@code def} or a {@code lambda} evaluates to: its name, its
	 * parameters and their defaults -- evaluated here, once, in the scope the function is
	 * written in -- around the Common Lisp function {@code implementation}.
	 */
	private LispVal functionValue(String name, Parameters parameters, LispVal implementation, Scope enclosing) {
		List<LispVal> defaults = new ArrayList<>();
		boolean anyDefault = false;
		for (Expression value : parameters.defaults) {
			anyDefault |= value != null;
			defaults.add(value == null ? StarlarkBuiltins.REQUIRED : expression(value, enclosing));
		}
		return list(MAKE_FUNCTION, string(name), quotedStrings(parameters.filled()),
				anyDefault ? cons(LIST, listOf(defaults)) : LispNil.INSTANCE,
				new LispInteger(parameters.positional.size()), bool(parameters.varargs != null),
				bool(parameters.kwargs != null), implementation);
	}

	private LispVal definition(Def def, Scope scope) {
		Parameters parameters = new Parameters(def.parameters());
		LispVal implementation;
		if (scope == Scope.TOP && this.direct.get(def.name()) == def) {
			LispSymbol symbol = StarlarkNames.symbol(def.name());
			this.hoisted
				.add(located(cons(DEFUN, cons(symbol, function(parameters, def.body(), scope, def.name()))), def.at()));
			implementation = list(FUNCTION, symbol);
		}
		else {
			implementation = cons(LAMBDA, function(parameters, def.body(), scope, def.name()));
		}
		return functionValue(def.name(), parameters, implementation, scope);
	}

	private LispVal lambda(Lambda lambda, Scope scope) {
		Parameters parameters = new Parameters(lambda.parameters());
		Set<String> names = new LinkedHashSet<>(parameters.names());
		Scope inner = scope.inner(names);
		Flow outer = enter(names, parameters.names());
		LispVal body = expression(lambda.body(), inner);
		this.flow = outer;
		return functionValue("lambda", parameters,
				cons(LAMBDA, cons(parameters.lambdaList(), listOf(once(List.of(body), "lambda")))), scope);
	}

	// ---- statements ----

	private List<LispVal> statements(List<Statement> statements, Frame frame) {
		List<LispVal> forms = new ArrayList<>();
		for (Statement statement : statements) {
			LispVal form = statement(statement, frame);
			if (form != null) {
				forms.add(located(form, statement.at()));
			}
		}
		return forms;
	}

	/** One statement as one form; {@code null} for a statement that does nothing. */
	private @Nullable LispVal statement(Statement statement, Frame frame) {
		Scope scope = frame.scope;
		return switch (statement) {
			case ExpressionStatement expression ->
				isLiteral(expression.expression()) ? null : expression(expression.expression(), scope);
			case Assignment assignment ->
				assign(assignment.target(), Value.computed(expression(assignment.value(), scope)), scope);
			case AugmentedAssignment assignment -> augmented(assignment, scope);
			case Def def -> {
				LispVal form = list(SETQ, StarlarkNames.symbol(def.name()), definition(def, scope));
				this.flow.assigned.add(def.name());
				yield form;
			}
			case If conditional -> {
				List<LispVal> form = new ArrayList<>(List.of(IF, test(conditional.condition(), scope)));
				Flow before = this.flow;
				this.flow = before.copy();
				form.add(progn(statements(conditional.then(), frame)));
				Flow then = this.flow;
				this.flow = before.copy();
				List<LispVal> otherwise = statements(conditional.otherwise(), frame);
				this.flow = Flow.join(then, this.flow);
				if (!otherwise.isEmpty()) {
					form.add(progn(otherwise));
				}
				yield listOf(form);
			}
			case For iteration -> {
				Loop loop = new Loop(iteration.target(), expression(iteration.iterable(), scope))
					.exits(iteration.body());
				// The body may run no time: what it assigns is not assigned after it.
				Flow before = this.flow;
				this.flow = before.copy();
				LispVal assignment = loop.assignment(scope);
				frame.loops.push(loop.iterable);
				List<LispVal> body = statements(iteration.body(), frame);
				frame.loops.pop();
				this.flow = before;
				yield loop.over(assignment, body);
			}
			case Return returned -> {
				LispVal form = returned(returned, frame);
				this.flow.dead = true;
				yield form;
			}
			case Break _ -> {
				this.flow.dead = true;
				yield list(RETURN_FROM, BREAK_BLOCK, LispNil.INSTANCE);
			}
			case Continue _ -> {
				this.flow.dead = true;
				yield list(RETURN_FROM, CONTINUE_BLOCK, LispNil.INSTANCE);
			}
			case Pass _ -> null;
			case Load load -> throw error("load is not supported yet", load.at());
		};
	}

	/** A literal standing as a statement: a docstring. */
	private static boolean isLiteral(Expression expression) {
		return expression instanceof StringLiteral || expression instanceof IntLiteral
				|| expression instanceof FloatLiteral;
	}

	/**
	 * {@code return}: the function's block is left with the value, after every loop of
	 * the function it stands in gave its iterable back. The value is computed first: it
	 * may still read what the loops hold.
	 */
	private LispVal returned(Return returned, Frame frame) {
		Expression operand = returned.value();
		LispVal value = operand == null ? StarlarkBuiltins.NONE : expression(operand, frame.scope);
		if (frame.loops.isEmpty()) {
			return list(RETURN_FROM, FUNCTION_BLOCK, value);
		}
		List<LispVal> forms = new ArrayList<>();
		LispVal result = value;
		if (isSimple(value)) {
			forms.add(PROGN);
		}
		else {
			result = fresh("RESULT");
			forms.add(LET);
			forms.add(list(list(result, value)));
		}
		for (LispSymbol iterable : frame.loops) {
			forms.add(list(ITERATED, iterable));
		}
		forms.add(list(RETURN_FROM, FUNCTION_BLOCK, result));
		return listOf(forms);
	}

	/**
	 * A loop over an iterable: its elements are read off one vector by index, and the
	 * iterable counts the loop from {@code %starlark-iterate} to
	 * {@code %starlark-iterated}, refusing a mutation meanwhile. Only a loop whose body
	 * leaves it gets the block to leave.
	 */
	private final class Loop {

		/** The variable holding the iterable while the loop runs. */
		private final LispSymbol iterable = fresh("SEQ");

		private final LispSymbol items = fresh("ITEMS");

		private final LispSymbol count = fresh("COUNT");

		private final LispSymbol index = fresh("INDEX");

		private final LispSymbol top = fresh("TOP");

		private final Expression target;

		private final LispVal iterated;

		private boolean breaks;

		private boolean continues;

		/**
		 * A loop.
		 * @param target what each element is assigned to
		 * @param iterated the iterable's form
		 */
		Loop(Expression target, LispVal iterated) {
			this.target = target;
			this.iterated = iterated;
		}

		/** Notes the {@code break} and {@code continue} of this loop in its body. */
		Loop exits(List<Statement> body) {
			this.breaks = leaves(body, true);
			this.continues = leaves(body, false);
			return this;
		}

		private static boolean leaves(List<Statement> statements, boolean byBreak) {
			for (Statement statement : statements) {
				boolean found = switch (statement) {
					case Break _ -> byBreak;
					case Continue _ -> !byBreak;
					case If conditional ->
						leaves(conditional.then(), byBreak) || leaves(conditional.otherwise(), byBreak);
					default -> false;
				};
				if (found) {
					return true;
				}
			}
			return false;
		}

		/**
		 * The assignment of each element to the target, lowered before the body, which
		 * finds the target assigned.
		 * @param scope where the target's names resolve
		 * @return the assignment
		 */
		LispVal assignment(Scope scope) {
			return assign(this.target, Value.element(list(AREF, this.items, this.index)), scope);
		}

		/**
		 * The loop around its body.
		 * @param assignment what {@link #assignment} answered
		 * @param body the body's forms
		 * @return the loop
		 */
		LispVal over(LispVal assignment, List<LispVal> body) {
			LispSymbol items = this.items;
			LispSymbol count = this.count;
			LispSymbol index = this.index;
			LispSymbol top = this.top;
			List<LispVal> step = new ArrayList<>(
					List.of(PROGN, assignment, list(SETQ, index, list(PLUS, index, new LispInteger(1)))));
			if (this.continues) {
				step.add(cons(BLOCK, cons(CONTINUE_BLOCK, listOf(body))));
			}
			else {
				step.addAll(body);
			}
			step.add(list(GO, top));
			LispVal walk = list(TAGBODY, top, list(IF, list(LESS, index, count), listOf(step)));
			return list(LET_STAR,
					list(list(this.iterable, this.iterated), list(items, list(ITERATE, this.iterable)),
							list(count, list(LENGTH, items)), list(index, new LispInteger(0))),
					this.breaks ? list(BLOCK, BREAK_BLOCK, walk) : walk, list(ITERATED, this.iterable));
		}

	}

	/**
	 * What a target is assigned.
	 *
	 * @param form the value's form
	 * @param pure whether the form is a read that can stand after the target's operands
	 * unobserved (an element of a vector the lowering holds)
	 */
	private record Value(LispVal form, boolean pure) {

		/**
		 * A value the statement computes: it is computed before the target's operands.
		 */
		static Value computed(LispVal form) {
			return new Value(form, false);
		}

		/** An element read off a vector nothing else writes. */
		static Value element(LispVal form) {
			return new Value(form, true);
		}

	}

	/**
	 * Assigns a value to a target: a name, an element, or a tuple or list of targets
	 * taking the value's elements.
	 */
	private LispVal assign(Expression target, Value value, Scope scope) {
		return switch (target) {
			case Identifier identifier -> {
				this.flow.assigned.add(identifier.name());
				yield list(SETQ, StarlarkNames.symbol(identifier.name()), value.form());
			}
			case Index index -> {
				List<LispVal> operands = List.of(value.form(), expression(index.receiver(), scope),
						expression(index.index(), scope));
				yield inSourceOrder(operands, value.pure() ? List.of(1, 2) : List.of(1, 2, 0),
						placed -> list(SET_INDEX, placed.get(1), placed.get(2), placed.get(0)));
			}
			case Dot dot -> {
				List<LispVal> operands = List.of(value.form(), expression(dot.receiver(), scope));
				yield inSourceOrder(operands, value.pure() ? List.of(1) : List.of(1, 0),
						placed -> list(SET_ATTRIBUTE, placed.get(1), string(dot.name()), placed.get(0)));
			}
			case TupleLiteral tuple -> unpack(tuple.elements(), value, scope);
			case ListLiteral list -> unpack(list.elements(), value, scope);
			default -> throw error("cannot assign to this expression", target.at());
		};
	}

	private LispVal unpack(List<Expression> targets, Value value, Scope scope) {
		LispSymbol elements = fresh("UNPACKED");
		List<LispVal> forms = new ArrayList<>(
				List.of(LET, list(list(elements, list(UNPACK, value.form(), new LispInteger(targets.size()))))));
		for (int i = 0; i < targets.size(); i++) {
			forms.add(assign(targets.get(i), Value.element(list(AREF, elements, new LispInteger(i))), scope));
		}
		return listOf(forms);
	}

	/**
	 * {@code target op= value}: the target's operands are evaluated once, before the
	 * value. {@code +=} extends a list in place.
	 */
	private LispVal augmented(AugmentedAssignment assignment, Scope scope) {
		LispSymbol operator = assignment.operator() == BinaryOperator.PLUS ? ADD_IN_PLACE
				: arithmetic(assignment.operator());
		LispVal value = expression(assignment.value(), scope);
		return switch (assignment.target()) {
			case Identifier identifier -> {
				LispVal current = read(identifier.name(), scope);
				this.flow.assigned.add(identifier.name());
				yield list(SETQ, StarlarkNames.symbol(identifier.name()), list(operator, current, value));
			}
			case Index index -> {
				LispVal receiver = expression(index.receiver(), scope);
				LispVal key = expression(index.index(), scope);
				List<LispVal> bindings = new ArrayList<>();
				if (!isSimple(receiver)) {
					LispSymbol held = fresh("OBJECT");
					bindings.add(list(held, receiver));
					receiver = held;
				}
				if (!isSimple(key)) {
					LispSymbol held = fresh("KEY");
					bindings.add(list(held, key));
					key = held;
				}
				LispVal store = list(SET_INDEX, receiver, key, list(operator, list(INDEX, receiver, key), value));
				yield bindings.isEmpty() ? store : list(LET_STAR, listOf(bindings), store);
			}
			case Dot dot -> list(SET_ATTRIBUTE, expression(dot.receiver(), scope), string(dot.name()), value);
			default -> throw error("cannot augment-assign to this expression", assignment.target().at());
		};
	}

	// ---- expressions ----

	private LispVal expression(Expression expression, Scope scope) {
		return located(lowered(expression, scope), expression.at());
	}

	private LispVal lowered(Expression expression, Scope scope) {
		return switch (expression) {
			case Identifier identifier -> reference(identifier, scope);
			case IntLiteral literal -> integer(literal.value());
			case FloatLiteral literal -> new LispDouble(literal.value());
			case StringLiteral literal -> string(literal.value());
			case BytesLiteral literal -> throw error("bytes literals are not supported yet", literal.at());
			case Unary unary -> unary(unary, scope);
			case Binary binary -> binary(binary, scope);
			case Conditional conditional -> list(IF, test(conditional.condition(), scope),
					expression(conditional.then(), scope), expression(conditional.otherwise(), scope));
			case Lambda lambda -> lambda(lambda, scope);
			case ListLiteral literal -> cons(MAKE_LIST, expressions(literal.elements(), scope));
			case TupleLiteral literal -> cons(MAKE_TUPLE, expressions(literal.elements(), scope));
			case DictLiteral literal -> {
				List<LispVal> operands = new ArrayList<>();
				for (Entry entry : literal.entries()) {
					operands.add(expression(entry.key(), scope));
					operands.add(expression(entry.value(), scope));
				}
				yield cons(MAKE_DICT, listOf(operands));
			}
			case ListComprehension comprehension ->
				comprehension(List.of(comprehension.element()), comprehension.clauses(), scope);
			case DictComprehension comprehension ->
				comprehension(List.of(comprehension.entry().key(), comprehension.entry().value()),
						comprehension.clauses(), scope);
			case Call call -> call(call, scope);
			case Dot dot -> {
				LispVal receiver = expression(dot.receiver(), scope);
				Signature method = StarlarkBuiltins.method(dot.name());
				yield method == null ? list(NO_ATTRIBUTE, receiver, string(dot.name())) : methodValue(method, receiver);
			}
			case Index index -> list(INDEX, expression(index.receiver(), scope), expression(index.index(), scope));
			case Slice slice -> list(SLICE, expression(slice.receiver(), scope), bound(slice.start(), scope),
					bound(slice.stop(), scope), bound(slice.step(), scope));
		};
	}

	private LispVal expressions(List<Expression> expressions, Scope scope) {
		return listOf(expressions.stream().map(expression -> expression(expression, scope)).toList());
	}

	private LispVal bound(@Nullable Expression operand, Scope scope) {
		return operand == null ? StarlarkBuiltins.NONE : expression(operand, scope);
	}

	private static LispVal integer(BigInteger value) {
		return value.bitLength() < Long.SIZE ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	private LispVal unary(Unary unary, Scope scope) {
		Expression operand = unary.operand();
		return switch (unary.operator()) {
			case NOT -> list(NOT, test(operand, scope));
			case MINUS -> switch (operand) {
				case IntLiteral literal -> integer(literal.value().negate());
				case FloatLiteral literal -> new LispDouble(-literal.value());
				default -> list(runtime("NEGATE"), expression(operand, scope));
			};
			case PLUS -> list(runtime("PLUS"), expression(operand, scope));
			case INVERT -> list(runtime("INVERT"), expression(operand, scope));
		};
	}

	private LispVal binary(Binary binary, Scope scope) {
		BinaryOperator operator = binary.operator();
		if (operator == BinaryOperator.AND || operator == BinaryOperator.OR) {
			// `a and b` is a when a is false, else b; `a or b` the other way round.
			boolean and = operator == BinaryOperator.AND;
			LispVal right = expression(binary.right(), scope);
			if (isBoolean(binary.left(), scope)) {
				LispVal left = test(binary.left(), scope);
				return and ? list(IF, left, right, LispNil.INSTANCE) : list(IF, left, LispTrue.INSTANCE, right);
			}
			LispSymbol held = fresh("VALUE");
			return list(LET, list(list(held, expression(binary.left(), scope))),
					and ? list(IF, list(TRUTH, held), right, held) : list(IF, list(TRUTH, held), held, right));
		}
		LispVal left = expression(binary.left(), scope);
		LispVal right = expression(binary.right(), scope);
		return switch (operator) {
			case EQUALS -> list(EQUAL, left, right);
			case NOT_EQUALS -> list(NOT, list(EQUAL, left, right));
			case LESS -> list(runtime("LESS"), left, right);
			case LESS_EQUALS -> list(runtime("LESS-EQUAL"), left, right);
			case GREATER -> list(runtime("GREATER"), left, right);
			case GREATER_EQUALS -> list(runtime("GREATER-EQUAL"), left, right);
			case IN -> list(IN, left, right);
			case NOT_IN -> list(NOT, list(IN, left, right));
			default -> list(arithmetic(operator), left, right);
		};
	}

	private static LispSymbol arithmetic(BinaryOperator operator) {
		return runtime(switch (operator) {
			case PLUS -> "ADD";
			case MINUS -> "SUB";
			case MULTIPLY -> "MUL";
			case DIVIDE -> "DIV";
			case FLOOR_DIVIDE -> "FLOORDIV";
			case MODULO -> "MOD";
			case BIT_OR -> "BIT-OR";
			case BIT_AND -> "BIT-AND";
			case BIT_XOR -> "BIT-XOR";
			case SHIFT_LEFT -> "SHIFT-LEFT";
			case SHIFT_RIGHT -> "SHIFT-RIGHT";
			default -> throw new IllegalStateException("not an arithmetic operator: " + operator);
		});
	}

	/**
	 * Whether the expression's value is known to be {@code True} or {@code False}, which
	 * are the Common Lisp booleans: a comparison, {@code not}, a Boolean constant.
	 */
	private boolean isBoolean(Expression expression, Scope scope) {
		return switch (expression) {
			case Unary unary -> unary.operator() == UnaryOperator.NOT;
			case Binary binary -> switch (binary.operator()) {
				case EQUALS, NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS, IN, NOT_IN -> true;
				default -> false;
			};
			case Identifier identifier -> !isVariable(identifier.name(), scope)
					&& (identifier.name().equals("True") || identifier.name().equals("False"));
			default -> false;
		};
	}

	/**
	 * An expression as a condition: the Common Lisp boolean of its truth. {@code and} and
	 * {@code or} of conditions are {@code and} and {@code or} of their tests.
	 */
	private LispVal test(Expression expression, Scope scope) {
		if (expression instanceof Binary binary
				&& (binary.operator() == BinaryOperator.AND || binary.operator() == BinaryOperator.OR)) {
			return list(binary.operator() == BinaryOperator.AND ? AND : OR, test(binary.left(), scope),
					test(binary.right(), scope));
		}
		LispVal value = expression(expression, scope);
		return isBoolean(expression, scope) ? value : list(TRUTH, value);
	}

	/**
	 * A comprehension: the loops and conditions of its clauses nested in order, around
	 * what it produces -- one element pushed onto a vector, or a key and a value stored
	 * in a dict. Its variables are its own, and the first clause's iterable is evaluated
	 * outside them.
	 * @param produced the element of a list comprehension, or the key and the value of a
	 * dict comprehension
	 * @param clauses the clauses
	 * @param scope where the comprehension stands
	 * @return the list or the dict
	 */
	private LispVal comprehension(List<Expression> produced, List<Clause> clauses, Scope scope) {
		Map<String, Integer> bound = new LinkedHashMap<>();
		for (Clause clause : clauses) {
			if (clause instanceof ForClause loop) {
				targetNames(loop.target(), bound);
			}
		}
		Scope inner = scope.inner(bound.keySet());
		boolean dict = produced.size() == 2;
		LispSymbol accumulator = fresh("ACC");
		// The clauses in order, each loop's target assigned for the clauses after it;
		// the first iterable is the enclosing block's.
		LispVal first = expression(((ForClause) clauses.get(0)).iterable(), scope);
		Flow outer = enter(bound.keySet(), List.of());
		// per clause: its loop and the target's assignment, or no loop and its test
		List<@Nullable Loop> loops = new ArrayList<>();
		List<LispVal> heads = new ArrayList<>();
		for (int i = 0; i < clauses.size(); i++) {
			switch (clauses.get(i)) {
				case IfClause condition -> {
					loops.add(null);
					heads.add(test(condition.condition(), inner));
				}
				case ForClause clause -> {
					Loop loop = new Loop(clause.target(), i == 0 ? first : expression(clause.iterable(), inner));
					loops.add(loop);
					heads.add(loop.assignment(inner));
				}
			}
		}
		LispVal body = dict
				? list(SET_INDEX, accumulator, expression(produced.get(0), inner), expression(produced.get(1), inner))
				: list(VECTOR_PUSH_EXTEND, expression(produced.get(0), inner), accumulator);
		this.flow = outer;
		for (int i = clauses.size() - 1; i >= 0; i--) {
			Loop loop = loops.get(i);
			body = loop == null ? list(IF, heads.get(i), body) : loop.over(heads.get(i), List.of(body));
		}
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(accumulator, list(dict ? EMPTY_DICT : VECTOR)));
		for (String name : bound.keySet()) {
			bindings.add(list(StarlarkNames.symbol(name), UNBOUND));
		}
		return list(LET, listOf(bindings), body, dict ? accumulator : list(LIST_FROM, accumulator));
	}

	// ---- calls ----

	private LispVal call(Call call, Scope scope) {
		Expression callee = call.function();
		List<Argument> arguments = call.arguments();
		if (callee instanceof Dot dot) {
			Signature method = StarlarkBuiltins.method(dot.name());
			if (method == null) {
				// no value has it: the receiver is evaluated, the arguments never are
				return list(NO_ATTRIBUTE, expression(dot.receiver(), scope), string(dot.name()));
			}
			// the helper takes the receiver as its first parameter
			List<Argument> withReceiver = new ArrayList<>();
			withReceiver.add(new Argument(ArgumentKind.POSITIONAL, null, dot.receiver(), dot.at()));
			withReceiver.addAll(arguments);
			LispVal direct = direct(method.withReceiver(), withReceiver, scope);
			return direct != null ? direct
					: general(methodValue(method, expression(dot.receiver(), scope)), arguments, scope);
		}
		if (callee instanceof Identifier identifier) {
			String name = identifier.name();
			if (!isVariable(name, scope) && !CONSTANTS.containsKey(name)) {
				Signature builtin = StarlarkBuiltins.function(name);
				if (builtin != null) {
					LispVal direct = direct(builtin, arguments, scope);
					return direct != null ? direct : general(builtinValue(builtin), arguments, scope);
				}
			}
			Def def = scope.binds(name) ? null : this.direct.get(name);
			if (def != null && def.parameters().size() == arguments.size()
					&& arguments.stream().allMatch(argument -> argument.kind() == ArgumentKind.POSITIONAL)) {
				// the callee first: a call above the def reads the global unassigned
				LispVal global = read(name, scope);
				LispVal direct = cons(StarlarkNames.symbol(name),
						listOf(arguments.stream().map(argument -> expression(argument.value(), scope)).toList()));
				return isSimple(global) ? direct : list(PROGN, global, direct);
			}
		}
		return general(expression(callee, scope), arguments, scope);
	}

	/**
	 * The direct tier: binds the arguments to a built-in's parameters while lowering and
	 * calls its helper with every parameter in place.
	 * @param signature the built-in
	 * @param arguments the call's arguments
	 * @param scope where the arguments' names resolve
	 * @return the call, or {@code null} when the arguments do not fit and the run-time
	 * binder has to answer (with a {@code *} or {@code **} argument, or with the error)
	 */
	private @Nullable LispVal direct(Signature signature, List<Argument> arguments, Scope scope) {
		int[] filledBy = new int[signature.params().size()];
		Arrays.fill(filledBy, -1);
		List<Integer> surplus = new ArrayList<>();
		List<Integer> named = new ArrayList<>();
		int position = 0;
		for (int i = 0; i < arguments.size(); i++) {
			Argument argument = arguments.get(i);
			if (argument.kind() == ArgumentKind.POSITIONAL) {
				if (position < signature.positional()) {
					filledBy[position++] = i;
				}
				else if (signature.varargs()) {
					surplus.add(i);
				}
				else {
					return null;
				}
			}
			else if (argument.kind() == ArgumentKind.KEYWORD) {
				int parameter = signature.named(keyword(argument));
				if (parameter >= 0 && filledBy[parameter] < 0) {
					filledBy[parameter] = i;
				}
				else if (parameter < 0 && signature.kwargs()) {
					named.add(i);
				}
				else {
					return null;
				}
			}
			else {
				return null;
			}
		}
		for (int parameter = 0; parameter < filledBy.length; parameter++) {
			if (filledBy[parameter] < 0 && signature.defaults().get(parameter) == null) {
				return null;
			}
		}
		List<LispVal> operands = arguments.stream().map(argument -> expression(argument.value(), scope)).toList();
		List<Integer> order = new ArrayList<>();
		for (int source : filledBy) {
			if (source >= 0) {
				order.add(source);
			}
		}
		order.addAll(surplus);
		order.addAll(named);
		return inSourceOrder(operands, order, placed -> {
			List<LispVal> form = new ArrayList<>();
			form.add(new LispSymbol(signature.helper()));
			for (int parameter = 0; parameter < filledBy.length; parameter++) {
				form.add(Objects.requireNonNull(filledBy[parameter] >= 0 ? placed.get(filledBy[parameter])
						: signature.defaults().get(parameter)));
			}
			if (signature.varargs()) {
				form.add(cons(MAKE_TUPLE, listOf(surplus.stream().map(placed::get).toList())));
			}
			if (signature.kwargs()) {
				List<LispVal> entries = new ArrayList<>();
				for (int source : named) {
					entries.add(string(keyword(arguments.get(source))));
					entries.add(placed.get(source));
				}
				form.add(cons(MAKE_DICT, listOf(entries)));
			}
			return listOf(form);
		});
	}

	/**
	 * The general tier: the callee, the list of its positional arguments and the list of
	 * its named ones (name, value, name, value ...), bound when the call runs.
	 */
	private LispVal general(LispVal callee, List<Argument> arguments, Scope scope) {
		List<LispVal> operands = new ArrayList<>();
		operands.add(callee);
		List<Integer> positional = new ArrayList<>();
		List<Integer> named = new ArrayList<>();
		List<Integer> star = new ArrayList<>();
		List<Integer> starStar = new ArrayList<>();
		for (int i = 0; i < arguments.size(); i++) {
			Argument argument = arguments.get(i);
			operands.add(expression(argument.value(), scope));
			(switch (argument.kind()) {
				case POSITIONAL -> positional;
				case KEYWORD -> named;
				case STAR -> star;
				case STAR_STAR -> starStar;
			}).add(i + 1);
		}
		List<Integer> order = new ArrayList<>(List.of(0));
		order.addAll(positional);
		order.addAll(star);
		order.addAll(named);
		order.addAll(starStar);
		return inSourceOrder(operands, order, placed -> {
			List<LispVal> entries = new ArrayList<>();
			for (int source : named) {
				entries.add(string(keyword(arguments.get(source - 1))));
				entries.add(placed.get(source));
			}
			return list(CALL, placed.get(0),
					spliced(listOf(positional.stream().map(placed::get).toList()),
							star.isEmpty() ? null : list(STAR_ELEMENTS, placed.get(star.get(0)))),
					spliced(listOf(entries), starStar.isEmpty() ? null : list(KEYWORDS, placed.get(starStar.get(0)))));
		});
	}

	/** The list of {@code elements}, then of what {@code rest} answers. */
	private static LispVal spliced(LispVal elements, @Nullable LispVal rest) {
		if (elements == LispNil.INSTANCE) {
			return rest == null ? LispNil.INSTANCE : rest;
		}
		LispVal listed = cons(LIST, elements);
		return rest == null ? listed : list(APPEND, listed, rest);
	}

	private static String keyword(Argument argument) {
		return Objects.requireNonNull(argument.name(), "a named argument has a name");
	}

	/**
	 * Places operands in a form that evaluates them in another order than the source
	 * wrote them, keeping the source's order of evaluation: when the form would evaluate
	 * two operands that are more than a constant or a variable the other way round, each
	 * such operand is bound first, in source order. A variable is read where it is
	 * placed: nothing an expression calls can assign it.
	 * @param operands the operands' forms, in source order
	 * @param order the operands' indices in the order the built form evaluates them
	 * @param build the form over the placed operands, by source index
	 * @return the form, inside the bindings when it needs them
	 */
	private LispVal inSourceOrder(List<LispVal> operands, List<Integer> order, Function<List<LispVal>, LispVal> build) {
		int latest = -1;
		boolean inOrder = true;
		for (int index : order) {
			if (!isSimple(operands.get(index))) {
				inOrder &= index > latest;
				latest = index;
			}
		}
		if (inOrder) {
			return build.apply(operands);
		}
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> placed = new ArrayList<>(operands);
		for (int i = 0; i < operands.size(); i++) {
			if (!isSimple(operands.get(i))) {
				LispSymbol held = fresh("ARG");
				bindings.add(list(held, operands.get(i)));
				placed.set(i, held);
			}
		}
		return list(LET_STAR, listOf(bindings), build.apply(placed));
	}

	/** A constant or a variable: evaluating it does nothing and cannot fail. */
	private static boolean isSimple(LispVal form) {
		return !(form instanceof LispCons cons) || QUOTE.equals(cons.car()) || FUNCTION.equals(cons.car());
	}

	/** A built-in function as a value. */
	private static LispVal builtinValue(Signature signature) {
		return cons(BUILTIN, listOf(description(signature)));
	}

	/** {@code receiver.name} as a value: the method bound to its receiver. */
	private static LispVal methodValue(Signature signature, LispVal receiver) {
		return cons(METHOD, cons(receiver, cons(quotedStrings(signature.kinds()), listOf(description(signature)))));
	}

	/** What the run-time binder needs of a signature, as constants. */
	private static List<LispVal> description(Signature signature) {
		List<LispVal> defaults = new ArrayList<>();
		boolean anyDefault = false;
		for (LispVal value : signature.defaults()) {
			anyDefault |= value != null;
			defaults.add(value == null ? StarlarkBuiltins.REQUIRED : value);
		}
		return List.of(string(signature.name()), quotedStrings(signature.params()),
				anyDefault ? list(QUOTE, listOf(defaults)) : LispNil.INSTANCE, new LispInteger(signature.positional()),
				new LispInteger(signature.namedFrom()), bool(signature.varargs()), bool(signature.kwargs()),
				list(FUNCTION, new LispSymbol(signature.helper())));
	}

	// ---- the run-time library's generated half ----

	/**
	 * The definitions {@code starlark.lisp} takes from the tables here, generated so each
	 * is written once: the attributes of each type, which {@code dir}, {@code getattr}
	 * and {@code hasattr} read, and the code points a string's {@code repr} escapes.
	 * @return the forms, in the library's canonical shape
	 */
	static List<LispVal> runtimeForms() {
		LispSymbol type = new LispSymbol("TYPE");
		LispSymbol x = new LispSymbol("X");
		LispSymbol name = new LispSymbol("NAME");
		LispSymbol cond = new LispSymbol("COND");
		LispSymbol stringEquals = new LispSymbol("STRING=");
		Map<String, List<String>> names = new TreeMap<>();
		List<LispVal> attributes = new ArrayList<>();
		for (Signature method : StarlarkBuiltins.methods()) {
			for (String kind : method.kinds()) {
				names.computeIfAbsent(kind, k -> new ArrayList<>()).add(method.name());
			}
			attributes.add(list(list(stringEquals, name, string(method.name())), methodValue(method, x)));
		}
		attributes.add(list(LispTrue.INSTANCE, list(NO_ATTRIBUTE, x, name)));
		List<LispVal> byType = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : names.entrySet()) {
			byType.add(list(list(stringEquals, type, string(entry.getKey())),
					quotedStrings(entry.getValue().stream().sorted().toList())));
		}
		byType.add(list(LispTrue.INSTANCE, LispNil.INSTANCE));
		List<LispVal> ranges = new ArrayList<>();
		int start = -1;
		for (int code = 0xA0; code <= Character.MAX_CODE_POINT + 1; code++) {
			boolean unprintable = code <= Character.MAX_CODE_POINT && isUnprintable(code);
			if (unprintable && start < 0) {
				start = code;
			}
			else if (!unprintable && start >= 0) {
				ranges.add(new LispInteger(start));
				ranges.add(new LispInteger(code - 1));
				start = -1;
			}
		}
		return List.of(list(DEFUN, runtime("ATTRIBUTE-NAMES"), list(type), cons(cond, listOf(byType))),
				list(DEFUN, runtime("ATTRIBUTE"), list(x, name), cons(cond, listOf(attributes))),
				list(DEFUN, runtime("UNPRINTABLE-RANGES"), LispNil.INSTANCE, list(QUOTE, listOf(ranges))));
	}

	/**
	 * Whether a string's {@code repr} escapes the code point, as Go's
	 * {@code strconv.IsPrint} answers past U+00A0: a control, format, private-use,
	 * surrogate or separator character. An unassigned code point prints as itself here.
	 */
	private static boolean isUnprintable(int code) {
		return switch (Character.getType(code)) {
			case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE,
					Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR ->
				true;
			default -> false;
		};
	}

	// ---- forms ----

	private LispSymbol fresh(String kind) {
		return new LispSymbol("%SL-" + kind + (++this.counter));
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%STARLARK-" + name);
	}

	private static LispVal string(String value) {
		return LispString.literal(value);
	}

	private static LispVal bool(boolean value) {
		return value ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	private static LispVal quotedStrings(List<String> strings) {
		if (strings.isEmpty()) {
			return LispNil.INSTANCE;
		}
		return list(QUOTE, listOf(strings.stream().map(StarlarkLowering::string).toList()));
	}

	private static LispVal progn(List<LispVal> forms) {
		if (forms.isEmpty()) {
			return LispNil.INSTANCE;
		}
		return forms.size() == 1 ? forms.get(0) : cons(PROGN, listOf(forms));
	}

	private static LispVal cons(LispVal car, LispVal cdr) {
		return new LispCons(car, cdr);
	}

	private static LispVal list(LispVal... elements) {
		return listOf(List.of(elements));
	}

	private static LispVal listOf(List<? extends LispVal> elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			list = new LispCons(elements.get(i), list);
		}
		return list;
	}

	private static LispReadException error(String message, SourceLocation at) {
		return new LispReadException(message, at);
	}

}
