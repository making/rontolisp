package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Lowers Scheme datums to the Common Lisp core forms the pipeline already consumes, so a
 * Scheme program joins {@code CompileFrontend.expand} and {@code LispEvaluator} unchanged
 * and no backend learns a new operator. The lowering table, the traps behind each row and
 * the measurements that picked them: {@code .kb/scheme-frontend.md}.
 *
 * <p>
 * The shape of the pass:
 * <ul>
 * <li><b>One file at a time, whole file at once.</b> Whether a top-level name is a
 * {@code defun} (defined once, by a {@code lambda}, never {@code set!}) or a variable
 * called through {@code funcall} is decided by a pre-scan, because Scheme has one
 * namespace and Common Lisp has two.</li>
 * <li><b>Destination-driven.</b> {@link #lower} answers an expression when the context
 * has no {@link Destination} and a STATEMENT when it has one: a statement stores its
 * value in the destination's variable, or jumps to a loop label. That is what turns a
 * named {@code let}, a {@code do} and a self tail call into {@code tagbody}/{@code go}
 * with every {@code go} in statement position.</li>
 * <li><b>Derived forms desugar to core Scheme forms first</b> ({@code cond},
 * {@code case}, {@code and}, {@code or}, {@code when}, {@code unless}, {@code do}),
 * spelled with the identity-compared {@code CORE_*} symbols so a user binding of
 * {@code if} cannot capture them.</li>
 * <li><b>Positions survive.</b> Every lowered cons inherits the position of the datum it
 * came from ({@code .kb/source-positions.md}), and a syntax error is a positioned
 * {@link LispReadException} on the interpreter and the compile path alike.</li>
 * </ul>
 */
final class SchemeLowering {

	static final SequencedMap<String, Core> SYNTAX = syntaxTable();

	private static SequencedMap<String, Core> syntaxTable() {
		SequencedMap<String, Core> table = new LinkedHashMap<>();
		table.put("quote", Core.QUOTE);
		table.put("quasiquote", Core.QUASIQUOTE);
		table.put("unquote", Core.UNQUOTE);
		table.put("unquote-splicing", Core.UNQUOTE_SPLICING);
		table.put("lambda", Core.LAMBDA);
		table.put("if", Core.IF);
		table.put("set!", Core.SET);
		table.put("begin", Core.BEGIN);
		table.put("let", Core.LET);
		table.put("let*", Core.LET_STAR);
		table.put("letrec", Core.LETREC);
		table.put("letrec*", Core.LETREC_STAR);
		table.put("do", Core.DO);
		table.put("cond", Core.COND);
		table.put("case", Core.CASE);
		table.put("and", Core.AND);
		table.put("or", Core.OR);
		table.put("when", Core.WHEN);
		table.put("unless", Core.UNLESS);
		table.put("define", Core.DEFINE);
		table.put("define-values", Core.DEFINE_VALUES);
		table.put("define-record-type", Core.DEFINE_RECORD_TYPE);
		table.put("let-values", Core.LET_VALUES);
		table.put("let*-values", Core.LET_STAR_VALUES);
		table.put("import", Core.IMPORT);
		table.put("else", Core.ELSE);
		table.put("=>", Core.ARROW);
		table.put("guard", Core.GUARD);
		table.put("parameterize", Core.PARAMETERIZE);
		// Consumed by SchemeExpander before the lowering sees the program.
		table.put("define-syntax", Core.DEFINE_SYNTAX);
		table.put("let-syntax", Core.LET_SYNTAX);
		table.put("letrec-syntax", Core.LETREC_SYNTAX);
		table.put("syntax-rules", Core.SYNTAX_RULES);
		table.put("syntax-error", Core.SYNTAX_ERROR);
		table.put("...", Core.ELLIPSIS);
		table.put("_", Core.UNDERSCORE);
		table.put("define-library", Core.DEFINE_LIBRARY);
		table.put("include", Core.INCLUDE);
		table.put("include-ci", Core.INCLUDE_CI);
		table.put("cond-expand", Core.COND_EXPAND);
		return table;
	}

	/** The keywords of {@code (scheme lazy)}. */
	static final SequencedMap<String, Core> LAZY_SYNTAX = orderedMap("delay", Core.DELAY, "delay-force",
			Core.DELAY_FORCE);

	/** The keyword of {@code (scheme case-lambda)}. */
	static final SequencedMap<String, Core> CASE_LAMBDA_SYNTAX = orderedMap("case-lambda", Core.CASE_LAMBDA);

	/** The SICP keyword no R7RS library exports: {@code (cons-stream a b)}. */
	static final SequencedMap<String, Core> SICP_SYNTAX = orderedMap("cons-stream", Core.CONS_STREAM);

	/**
	 * The syntactic keywords a file may spell without defining: every implemented
	 * keyword, not the refused-by-name ones. For {@link Scheme#providedNames()}.
	 * @return the keyword spellings
	 */
	static SequencedSet<String> syntaxNames() {
		SequencedSet<String> names = new LinkedHashSet<>();
		for (Map.Entry<String, Core> entry : SYNTAX.entrySet()) {
			if (entry.getValue() != Core.UNSUPPORTED) {
				names.add(entry.getKey());
			}
		}
		names.addAll(LAZY_SYNTAX.keySet());
		names.addAll(CASE_LAMBDA_SYNTAX.keySet());
		names.addAll(SICP_SYNTAX.keySet());
		return names;
	}

	private static SequencedMap<String, Core> orderedMap(Object... namesAndCores) {
		SequencedMap<String, Core> map = new LinkedHashMap<>();
		for (int i = 0; i < namesAndCores.length; i += 2) {
			map.put((String) namesAndCores[i], (Core) namesAndCores[i + 1]);
		}
		return map;
	}

	// The keywords a desugaring spells. Compared by IDENTITY before any scope lookup, so
	// (define (f if) (or a b)) still expands `or` into the real `if`.
	static final Map<LispSymbol, Core> CORE_SYMBOLS = new IdentityHashMap<>();

	static final Map<Core, LispSymbol> CORE_BY_CORE = new java.util.EnumMap<>(Core.class);

	static final LispSymbol CORE_IF = core("if", Core.IF);

	static final LispSymbol CORE_LET = core("let", Core.LET);

	static final LispSymbol CORE_BEGIN = core("begin", Core.BEGIN);

	static final LispSymbol CORE_AND = core("and", Core.AND);

	static final LispSymbol CORE_OR = core("or", Core.OR);

	static final LispSymbol CORE_COND = core("cond", Core.COND);

	static final LispSymbol CORE_LAMBDA = core("lambda", Core.LAMBDA);

	static final LispSymbol CORE_RAW_PREDICATE = core("raw-predicate", Core.RAW_PREDICATE);

	// (raw form): a Common Lisp form a desugaring puts where an expression stands.
	static final LispSymbol CORE_RAW = core("raw", Core.RAW);

	// Stands where a desugaring has no expression to put: the missing arm of an if, a
	// cond or case no clause of which is taken. Lowered to the unspecified object.
	static final LispSymbol CORE_UNSPECIFIED = core("unspecified", Core.UNSPECIFIED);

	static final LispSymbol CORE_DELAY = core("delay", Core.DELAY);

	private static LispSymbol core(String name, Core core) {
		LispSymbol symbol = new LispSymbol(name);
		CORE_SYMBOLS.put(symbol, core);
		CORE_BY_CORE.putIfAbsent(core, symbol);
		return symbol;
	}

	// One identity symbol per implemented keyword, for what a macro template spells: the
	// expander hands the lowering these instead of the template's own aliases.
	static {
		for (Map<String, Core> table : List.of(SYNTAX, LAZY_SYNTAX, CASE_LAMBDA_SYNTAX, SICP_SYNTAX)) {
			table.forEach((name, core) -> {
				if (core != Core.UNSUPPORTED && !CORE_BY_CORE.containsKey(core)) {
					core(name, core);
				}
			});
		}
	}

	/**
	 * The identity-compared symbol that means a keyword wherever it stands.
	 * @param core the keyword
	 * @return its symbol, or {@code null} for a keyword refused by name
	 */
	static @Nullable LispSymbol coreSymbol(Core core) {
		return CORE_BY_CORE.get(core);
	}

	/**
	 * What an identifier means at a point in the program; what a user library exports
	 * ({@link SchemeLibraries}).
	 */
	sealed interface Binding {

	}

	/** A variable: referenced bare, called through {@code funcall}. */
	record Variable(LispSymbol symbol) implements Binding {
	}

	/**
	 * A top-level procedure lowered to a {@code defun}: called directly.
	 *
	 * @param symbol the {@code defun}'s name
	 * @param clauses a {@code case-lambda}'s clauses, each its own {@code defun}, in
	 * order; empty for any other procedure
	 */
	record GlobalFunction(LispSymbol symbol, List<Clause> clauses) implements Binding {

		GlobalFunction(LispSymbol symbol) {
			this(symbol, List.of());
		}

		// The first clause accepting the count, as the dispatching defun would pick it.
		@Nullable Clause clauseFor(int argumentCount) {
			for (Clause clause : this.clauses) {
				if (clause.accepts(argumentCount)) {
					return clause;
				}
			}
			return null;
		}

	}

	/**
	 * One clause of a {@code case-lambda} a top-level procedure is defined by, lowered to
	 * a {@code defun} of its own that a direct call reaches without the dispatch.
	 *
	 * @param symbol the clause's {@code defun}
	 * @param required how many required formals it has
	 * @param rest whether it has a rest formal
	 */
	record Clause(LispSymbol symbol, int required, boolean rest) {

		boolean accepts(int argumentCount) {
			return this.rest ? argumentCount >= this.required : argumentCount == this.required;
		}

	}

	/** A record type's predicate: a {@code defun} answering {@code T}/{@code NIL}. */
	record GlobalPredicate(LispSymbol symbol) implements Binding {
	}

	record Builtin(SchemeBuiltins.Entry entry) implements Binding {
	}

	/**
	 * A bare value, not a procedure: {@code true}, {@code false}, {@code nil},
	 * {@code user-initial-environment} ({@code SchemeBuiltins.constants()}). Not an R7RS
	 * export of {@code (scheme base)}, so unreachable by name through {@code import} --
	 * only the no-import default merges it (a REPL, and a file with no import at all).
	 */
	record Constant(LispVal form) implements Binding {
	}

	record Syntax(Core core, String name) implements Binding {
	}

	/**
	 * A {@code syntax-rules} macro a user library exports: only the expander reads it
	 * ({@link SchemeExpander#exportedMacro}), a program that names one is always
	 * expanded, so the lowering meets it only where the expander already refused it.
	 *
	 * @param macro the expander's macro, opaque here
	 * @param name its name where it was defined
	 */
	record ImportedSyntax(Object macro, String name) implements Binding {
	}

	/**
	 * The name of a named {@code let} being tried as a PURE loop: every reference must be
	 * a jump. Any other use -- a non-tail call, a call from inside a {@code lambda}, a
	 * bare reference -- sets {@link #escaped}, and the loop is lowered again as a
	 * procedure.
	 */
	static final class LoopName implements Binding {

		boolean escaped;

	}

	static final class Scope {

		private final @Nullable Scope parent;

		final Map<String, Binding> bindings = new HashMap<>();

		Scope(@Nullable Scope parent) {
			this.parent = parent;
		}

		@Nullable Binding find(String name) {
			for (Scope scope = this; scope != null; scope = scope.parent) {
				Binding binding = scope.bindings.get(name);
				if (binding != null) {
					return binding;
				}
			}
			return null;
		}

	}

	/** A label a tail call may jump to instead of calling. */
	static final class Target {

		private final Binding binding;

		final LispSymbol label;

		private final List<LispSymbol> assigned;

		private final boolean rest;

		private final boolean parallel;

		private final Scope home;

		private final List<String> names;

		boolean used;

		boolean shadowed;

		// The case-lambda clause whose defun this loop is, or null.
		@Nullable LispSymbol clause;

		// What a jump assigns besides the arguments, as variable-value pairs: a
		// tail-call group's member selector.
		List<LispVal> presets = List.of();

		Target(Binding binding, LoopShape shape, Scope home, List<String> names) {
			this.binding = binding;
			this.label = shape.label();
			this.assigned = shape.assigned();
			this.rest = shape.rest();
			this.parallel = shape.parallel();
			this.home = home;
			this.names = names;
		}

		// Whether a jump from this scope would assign an inner variable of the same name
		// instead of the loop variable: only the in-place shape assigns the variables
		// themselves, and a carrier is a fresh name no binding can spell.
		boolean shadowedFrom(Scope scope) {
			if (!this.parallel) {
				return false;
			}
			for (String name : this.names) {
				if (scope.find(name) != this.home.bindings.get(name)) {
					return true;
				}
			}
			return false;
		}

		boolean accepts(int argumentCount) {
			if (this.binding instanceof GlobalFunction function && !function.clauses().isEmpty()) {
				// A case-lambda clause's defun: only a count that picks THIS clause is a
				// jump; any other is a direct call of the clause it picks.
				Clause clause = function.clauseFor(argumentCount);
				return clause != null && clause.symbol().equals(this.clause);
			}
			int required = this.rest ? this.assigned.size() - 1 : this.assigned.size();
			return this.rest ? argumentCount >= required : argumentCount == required;
		}

	}

	/**
	 * How a jump rebinds the loop variables.
	 *
	 * @param label the {@code tagbody} label
	 * @param assigned what a jump assigns, in parameter order: the loop variables
	 * themselves ({@code parallel}) or their carriers
	 * @param rest whether the last one collects the remaining arguments in a list
	 * @param parallel whether the assignment must be a {@code psetq}
	 */
	record LoopShape(LispSymbol label, List<LispSymbol> assigned, boolean rest, boolean parallel) {
	}

	/**
	 * Where a statement puts its value, and the labels it may jump to.
	 *
	 * @param result the variable a leaf assigns
	 * @param targets the loops a tail call may jump to
	 * @param exit the outermost loop's block, for a leaf that may answer other than one
	 * value
	 */
	record Destination(LispSymbol result, List<Target> targets, Exit exit) {
	}

	/**
	 * The block a loop's leaf leaves through when its value may be other than ONE value.
	 * A result variable holds one value -- a {@code (setq R (two))} keeps the first, in
	 * Common Lisp and on every backend (.kb/multiple-values.md) -- so such a leaf is a
	 * {@code (return-from B form)} instead, which carries every value the form answers
	 * and is a jump on the compiled backends. Emitted only when used: a loop none of
	 * whose leaves needs it keeps the plain shape. Named after the result variable rather
	 * than by the counter, so a loop attempt that is discarded (an escaping name, a
	 * procedure with no self tail call) numbers nothing differently. Shared by the loops
	 * nested in the one that owns the result variable.
	 */
	static final class Exit {

		private final LispSymbol block;

		boolean used;

		Exit(LispSymbol result) {
			this.block = new LispSymbol(result.name().replace("%SCM-R", "%SCM-B"));
		}

	}

	/**
	 * Where an expression is lowered.
	 *
	 * @param scope the bindings in effect
	 * @param destination where a statement puts its value, or {@code null} for an
	 * expression
	 * @param discarded whether nobody reads the expression's value (a body form before
	 * the last, a top-level form of a file): an effect then answers its raw Common Lisp
	 * value instead of the unspecified object, which saves loading it
	 */
	record Context(Scope scope, @Nullable Destination destination, boolean discarded) {

		static Context of(Scope scope) {
			return new Context(scope, null, false);
		}

		static Context discarding(Scope scope) {
			return new Context(scope, null, true);
		}

		static Context storing(Scope scope, Destination destination) {
			return new Context(scope, destination, false);
		}

		Context in(Scope inner) {
			return new Context(inner, this.destination, this.discarded);
		}

	}

	/**
	 * A test lowered for an {@code if}.
	 *
	 * @param form a Common Lisp boolean
	 * @param negated whether the form is true exactly when the Scheme test is FALSE (the
	 * generic {@code (eq v false)} shape, which saves a {@code not})
	 */
	record Test(LispVal form, boolean negated) {

		LispVal positive() {
			return this.negated ? list(symbol("NOT"), this.form) : this.form;
		}

	}

	/**
	 * A parsed formals list.
	 *
	 * @param required the required parameters
	 * @param rest the rest parameter, or {@code null}
	 */
	record Formals(List<LispSymbol> required, @Nullable LispSymbol rest) {

		List<LispSymbol> all() {
			List<LispSymbol> all = new ArrayList<>(this.required);
			if (this.rest != null) {
				all.add(this.rest);
			}
			return all;
		}

	}

	/**
	 * A procedure to lower.
	 *
	 * @param formals the parameters
	 * @param body the body forms
	 * @param self the binding a tail call to which is a jump, or {@code null}
	 * @param selfName the identifier {@code self} is bound to, for the cheap pre-scan
	 * @param clause the {@code case-lambda} clause of {@code self} this procedure is, or
	 * {@code null}: a self call is a jump only when its count picks this clause
	 */
	record ProcedureSpec(Formals formals, List<LispVal> body, @Nullable Binding self, @Nullable LispSymbol selfName,
			@Nullable LispSymbol clause) {

		ProcedureSpec(Formals formals, List<LispVal> body, @Nullable Binding self, @Nullable LispSymbol selfName) {
			this(formals, body, self, selfName, null);
		}

	}

	record Lowered(LispVal lambdaList, List<LispVal> body) {
	}

	/**
	 * A procedure a tail-call group may hold: one defined once, by a {@code lambda},
	 * never assigned. At the top level a {@link GlobalFunction} with no
	 * {@code case-lambda} clauses; in a body (an internal {@code define}, a
	 * {@code letrec} binding) the {@link Variable} the body binds it to.
	 * @param form the {@code define}, or a {@code letrec} binding's {@code lambda}
	 * @param definition its parse
	 * @param binding its binding: a tail call to it is a jump
	 */
	/**
	 * A parsed {@code define}.
	 *
	 * @param name the defined identifier
	 * @param formals the parameters when the value is a syntactic {@code lambda}, else
	 * {@code null}
	 * @param body the procedure body, or the single value expression
	 * @param caseLambda the {@code case-lambda} datum the value is, desugared into
	 * {@code formals} and {@code body}, or {@code null}
	 */
	record Definition(LispSymbol name, @Nullable LispVal formals, List<LispVal> body, @Nullable LispCons caseLambda) {

		Definition(LispSymbol name, @Nullable LispVal formals, List<LispVal> body) {
			this(name, formals, body, null);
		}

		boolean procedure() {
			return this.formals != null;
		}

	}

	Definition definition(LispCons form) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 2) {
			throw error("malformed define", form);
		}
		if (parts.get(1) instanceof LispCons target) {
			if (!(target.car() instanceof LispSymbol name)) {
				throw error("curried define is not supported", form);
			}
			if (parts.size() < 3) {
				throw error("a procedure definition needs a body", form);
			}
			return new Definition(name, target.cdr(), parts.subList(2, parts.size()));
		}
		LispSymbol name = identifier(parts.get(1), form);
		if (parts.size() > 3) {
			throw error("malformed define", form);
		}
		LispVal value = parts.size() == 3 ? parts.get(2) : LispNil.INSTANCE;
		LispCons caseLambda = null;
		if (value instanceof LispCons dispatch && syntaxOf(dispatch, this.global) == Core.CASE_LAMBDA) {
			// A procedure all the same: a defun when the file defines it once.
			caseLambda = dispatch;
			value = caseLambda(dispatch);
		}
		if (value instanceof LispCons lambda && syntaxOf(lambda, this.global) == Core.LAMBDA
				&& lambda.cdr() instanceof LispCons rest && rest.cdr() instanceof LispCons) {
			return new Definition(name, rest.car(), elements(rest.cdr(), lambda), caseLambda);
		}
		return new Definition(name, null, List.of(value));
	}

	record Member(LispCons form, Definition definition, Binding binding) {

		GlobalFunction function() {
			return (GlobalFunction) this.binding;
		}

	}

	/**
	 * Procedures whose tail calls to each other form a cycle, lowered as ONE function
	 * holding each member's body under a label, so every tail call among them is a jump;
	 * each member itself calls it with its index (.kb/scheme-frontend.md, "Tail-call
	 * groups"). At the top level the function is a {@code defun} and each member its own
	 * {@code defun}; in a body it is a {@code lambda} in a variable of the body and each
	 * member a {@code lambda} calling it.
	 */
	static final class Group {

		// In definition order; the first one's definition emits the group's function.
		final List<Member> members;

		// The group's function, once the first member's definition has emitted it.
		@Nullable LispSymbol function;

		Group(List<Member> members) {
			this.members = members;
		}

	}

	/**
	 * A group's function and, per member, the members its body jumps to.
	 *
	 * @param function its name: the {@code defun}'s, or the variable holding the
	 * {@code lambda}
	 * @param lambdaList {@code (W C1 .. Cn)}
	 * @param body the function's one body form
	 * @param jumps per member, the indexes of the members a tail call in its body jumps
	 * to
	 */
	private record LoweredGroup(LispSymbol function, LispVal lambdaList, LispVal body, List<Set<Integer>> jumps) {
	}

	/** What a lowering attempt changes that a discarded one must put back. */
	private record Snapshot(int counter, int closures, List<LispVal> hoisted,
			Map<LispCons, SchemeRecordLowering.InternalRecord> internalRecords, Set<String> internalRecordNames,
			Map<LispCons, LispVal> caseLambdas, String enclosing) {
	}

	// The text being lowered: the file, or the buffer a session is reading now.
	SchemeReader reader;

	List<LispVal> datums = List.of();

	// A session lowers one buffer at a time against a global scope that outlives each of
	// them, so nothing may depend on having seen the whole program.
	final boolean interactive;

	final SchemeStandard standard;

	boolean falseBound;

	final Set<LispSymbol> generated = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	final Set<String> assignedNames = new HashSet<>();

	// Each case-lambda datum's desugaring, so every pre-scan and the lowering see one.
	final Map<LispCons, LispVal> caseLambdas = new IdentityHashMap<>();

	// What a file's names read before their definition hold until then.
	final SequencedMap<LispSymbol, LispVal> initialValues = new LinkedHashMap<>();

	final Scope global = new Scope(null);

	// The user libraries this lowering and the ones it imports know.
	final SchemeLibraries<Binding> libraries;

	// What this lowering's top-level names start with: nothing for a program or a
	// session, the library's private prefix for a library (SchemeNames.libraryPrefix).
	final String prefix;

	// The bindings imported from a user library: an importer may not assign them, and
	// under r7rs may not redefine them either.
	final Set<Binding> libraryImports = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	// The lowered forms of a library: set by lowerLibrary.
	List<LispVal> libraryForms = List.of();

	final LispSymbol falseVariable = symbol(SchemeBuiltins.FALSE_VARIABLE);

	final LispSymbol unspecifiedVariable = symbol(SchemeBuiltins.UNSPECIFIED_VARIABLE);

	/**
	 * The catch tag {@code exit} throws its code to, in canonical spelling: the template
	 * spells it lowercase and the reader upcases it.
	 */
	static final String EXIT_TAG_NAME = "RONTOLISP::%SCHEME-EXIT-TAG";

	static final String EXIT_FUNCTION_NAME = "RONTOLISP::%SCHEME-EXIT";

	int counter;

	int closures;

	// The tail-call groups of a file, by member name (declareGroups).
	final Map<String, Group> groups = new HashMap<>();

	// Each internal define-record-type datum's hoisted type, so a body lowered twice (a
	// loop tried as a pure loop first) defines it once and binds the same names.
	final Map<LispCons, SchemeRecordLowering.InternalRecord> internalRecords = new IdentityHashMap<>();

	// Every internal record type name taken so far; a session keeps them, so a type
	// typed again in a later buffer is a new one and the old instances keep their
	// layout.
	final Set<String> internalRecordNames = new HashSet<>();

	// What the top-level form being lowered hoists ahead of itself: the defstruct and
	// modifier defuns of the internal record types it holds.
	final List<LispVal> hoisted = new ArrayList<>();

	// The mangled name of the top-level definition being lowered, "" for any other form:
	// what an internal record type's name is qualified by.
	String enclosing = "";

	// Created by the first file or buffer that defines a macro; a session keeps it, and
	// with it the macros of earlier buffers.
	@Nullable SchemeExpander expander;

	SchemeLowering(SchemeReader reader, boolean interactive, SchemeStandard standard,
			SchemeLibraries<Binding> libraries, String prefix) {
		this.reader = reader;
		this.interactive = interactive;
		this.standard = standard;
		this.libraries = libraries;
		this.prefix = prefix;
	}

	/**
	 * A lowering of one whole file.
	 * @param reader the file's reader
	 * @param standard the standard the file is read against
	 * @param files where the files it includes and the libraries it imports are read from
	 * @return the lowering
	 */
	static SchemeLowering ofFile(SchemeReader reader, SchemeStandard standard, SchemeFiles files) {
		return new SchemeLowering(reader, false, standard, new SchemeLibraries<>(files, reader.file()), "");
	}

	/**
	 * A lowering that reads buffer after buffer ({@link #interact}): every importable
	 * library is visible from the start, as in any R7RS REPL -- plus the {@code sicp} and
	 * {@code r5rs} names under {@link SchemeStandard#RONTOLISP}.
	 * @param standard the standard the session is read against
	 * @param files where the files it includes and the libraries it imports are read
	 * from, relative to the working directory
	 * @return the lowering
	 */
	static SchemeLowering ofSession(SchemeStandard standard, SchemeFiles files) {
		SchemeLowering lowering = new SchemeLowering(new SchemeReader("", null), true, standard,
				new SchemeLibraries<>(files, null), "");
		SchemeLibraryLowering.imports(lowering, 0);
		return lowering;
	}

	/**
	 * Lowers one buffer of a session, one entry per top-level datum.
	 *
	 * <p>
	 * No pre-scan can see the forms still to be typed, so EVERY top-level definition is a
	 * variable, called through {@code funcall} by the forms that follow it. A form typed
	 * BEFORE the definition lowered its call as a direct one (a name nobody defined yet),
	 * so each definition also leaves a {@code defun} trampoline applying the variable's
	 * current value: a forward reference, a later {@code set!} and a redefinition all
	 * land on the same procedure a whole file would call.
	 * @param buffer the reader over the typed text
	 * @return the lowered datums, in order
	 */
	List<SchemeTopLevel> interact(SchemeReader buffer) {
		this.reader = buffer;
		this.datums = new ArrayList<>(buffer.readAll());
		// Temporaries are recognized by identity while their datum is lowered; the
		// COUNTER is what must outlive the buffer, a record's generated slot being
		// global.
		this.generated.clear();
		this.foreignSymbols.clear();
		this.internalRecords.clear();
		// A library typed at a prompt is declared, not lowered: a later import lowers it.
		List<LispVal> program = new ArrayList<>();
		for (int index = 0; SchemeLibraryLowering.resolveTopLevelCondExpand(this, index); index++) {
			LispVal datum = this.datums.get(index);
			if (SchemeLibraryLowering.isLibraryDefinition(datum)) {
				SchemeLibraryLowering.declareLibrary(this, (LispCons) datum, buffer, null);
			}
			else {
				program.add(datum);
			}
		}
		List<LispVal> included = SchemeLibraryLowering.includes(this, program, null);
		// Before the expansion too, so a macro imported at this prompt expands here.
		for (LispVal datum : included) {
			sessionImport(datum);
		}
		// Grouped by which typed datum each spliced form came from: a top-level begin
		// (typed, or a macro's template) must splice its definitions to the top level,
		// but the splicing must still answer as ONE session step, echoing only the
		// group's last form -- as a whole file's own progn/begin would (.kb/
		// scheme-frontend.md, "A session").
		List<List<LispVal>> groups = new ArrayList<>();
		for (List<LispVal> expandedGroup : expandedGrouped(included)) {
			List<LispVal> group = new ArrayList<>();
			for (LispVal datum : expandedGroup) {
				spliceBegins(datum, group);
			}
			groups.add(group);
		}
		List<LispVal> forms = new ArrayList<>();
		for (List<LispVal> group : groups) {
			forms.addAll(group);
		}
		for (LispVal form : forms) {
			collectAssigned(form);
			sessionImport(form);
		}
		SchemeDefinitionLowering.declareGlobals(this, forms);
		List<SchemeTopLevel> out = new ArrayList<>();
		if (!this.falseBound) {
			out.add(new SchemeTopLevel(List.of(falseBinding()), false));
		}
		List<LispVal> libraryForms = this.libraries.drain();
		if (!libraryForms.isEmpty()) {
			out.add(new SchemeTopLevel(libraryForms, false));
		}
		for (List<LispVal> group : groups) {
			if (group.isEmpty()) {
				// A typed datum consumed entirely (a define-syntax) leaves nothing to
				// run or echo.
				continue;
			}
			List<LispVal> lowered = new ArrayList<>();
			boolean echoes = false;
			for (LispVal form : group) {
				echoes = SchemeDefinitionLowering.topLevel(this, form, lowered);
			}
			out.add(new SchemeTopLevel(List.copyOf(SchemeExitGuardLowering.exitGuardEntry(this, lowered)), echoes));
		}
		// Only now: a buffer that failed to lower evaluated nothing, the binding
		// included.
		this.falseBound = true;
		return out;
	}

	// At a prompt every import is a leading one, and it only ever ADDS names; importing
	// the same set again changes nothing.
	void sessionImport(LispVal form) {
		if (form instanceof LispCons cons && syntaxOf(cons, this.global) == Core.IMPORT) {
			for (LispVal set : elements(cons.cdr(), cons)) {
				SchemeLibraryLowering.importSet(this, set, cons)
					.forEach((name, binding) -> this.global.bindings.put(SchemeNames.mangle(name), binding));
			}
		}
	}

	/**
	 * Lowers the whole file.
	 * @return the Common Lisp top-level forms
	 */
	List<LispVal> lower() {
		this.datums = new ArrayList<>(this.reader.readAll());
		List<LispVal> forms = new ArrayList<>();
		int start = SchemeLibraryLowering.imports(this, SchemeLibraryLowering.declareLibraries(this));
		for (LispVal datum : expanded(SchemeLibraryLowering.includes(this,
				this.datums.subList(start, this.datums.size()), this.reader.file()))) {
			spliceBegins(datum, forms);
		}
		for (LispVal form : forms) {
			collectAssigned(form);
		}
		SchemeDefinitionLowering.declareGlobals(this, forms);
		List<LispVal> out = new ArrayList<>();
		// #f is a DISTINCT non-NIL value, so '() stays NIL and every list primitive keeps
		// working. It lives in a variable because a quoted symbol costs a lookup per
		// evaluation on wasm (3x on a test-heavy loop, .kb/scheme-frontend.md).
		out.add(falseBinding());
		// The imported libraries, lowered on import, in dependency order; each guards its
		// own statements, so they run once however many files import it.
		out.addAll(this.libraries.drain());
		if (!this.initialValues.isEmpty()) {
			List<LispVal> assignment = new ArrayList<>(List.of(symbol("SETQ")));
			this.initialValues.forEach((variable, value) -> {
				assignment.add(variable);
				assignment.add(value);
			});
			out.add(listOf(assignment));
		}
		// The leading setqs bind quoted values and cannot throw; every top-level form
		// after them runs inside the exit catch when the file can reach it.
		int bodyFrom = out.size();
		for (LispVal form : forms) {
			SchemeDefinitionLowering.topLevel(this, form, out);
		}
		if (SchemeExitGuardLowering.mayThrowExit(forms) || this.foreignExitOrEval) {
			for (int i = bodyFrom; i < out.size(); i++) {
				out.set(i, SchemeExitGuardLowering.exitGuard(this, out.get(i)));
			}
		}
		return out;
	}

	// The datums with every macro expanded, when the program defines any: a program that
	// spells no syntax definition is lowered exactly as before.
	List<LispVal> expanded(List<LispVal> datums) {
		if (this.expander == null
				&& !SchemeExpander.needed(datums, this::globalKeyword, name -> importedMacro(name) != null)) {
			return datums;
		}
		if (this.expander == null) {
			this.expander = new SchemeExpander(new ExpanderHost(), this.libraries.aliases());
		}
		return this.expander.topLevel(datums);
	}

	// Like expanded, but grouped by which input datum each result form came from: what
	// interact needs so a session step wraps and echoes one typed datum's forms
	// together, however many a spliced begin turned it into.
	private List<List<LispVal>> expandedGrouped(List<LispVal> datums) {
		if (this.expander == null
				&& !SchemeExpander.needed(datums, this::globalKeyword, name -> importedMacro(name) != null)) {
			List<List<LispVal>> groups = new ArrayList<>(datums.size());
			for (LispVal datum : datums) {
				groups.add(List.of(datum));
			}
			return groups;
		}
		if (this.expander == null) {
			this.expander = new SchemeExpander(new ExpanderHost(), this.libraries.aliases());
		}
		return this.expander.topLevelGrouped(datums);
	}

	private @Nullable Core globalKeyword(LispSymbol identifier) {
		return this.global.find(SchemeNames.mangle(identifier.name())) instanceof Syntax syntax ? syntax.core() : null;
	}

	private @Nullable Object importedMacro(LispSymbol identifier) {
		return this.global.find(SchemeNames.mangle(identifier.name())) instanceof ImportedSyntax syntax ? syntax.macro()
				: null;
	}

	// The identifiers a macro exported by another library emits for that library's
	// bindings (SchemeExpander.Host.foreign), one per binding; and whether one of them
	// is exit or eval, which mayThrowExit cannot see by spelling.
	final Map<Binding, LispSymbol> foreignSymbols = new IdentityHashMap<>();

	final Set<LispSymbol> foreignIdentifiers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	boolean foreignExitOrEval;

	private final class ExpanderHost implements SchemeExpander.Host {

		@Override
		public @Nullable Core keyword(LispSymbol identifier) {
			return globalKeyword(identifier);
		}

		@Override
		public @Nullable LispSymbol coreSymbol(Core core) {
			return SchemeLowering.coreSymbol(core);
		}

		@Override
		public @Nullable Core coreOf(LispSymbol identifier) {
			return CORE_SYMBOLS.get(identifier);
		}

		@Override
		public LispSymbol fresh(String kind) {
			return SchemeLowering.this.fresh(kind);
		}

		@Override
		public LispReadException error(String message, LispCons form) {
			return SchemeLowering.this.error(message, form);
		}

		@Override
		public <T extends LispVal> T inherit(LispCons original, T rewritten) {
			return positioned(original, rewritten);
		}

		@Override
		public void checkTopLevelDefinition(LispSymbol identifier, LispCons form) {
			refuseRedefiningAnImport(identifier, form);
		}

		@Override
		public int condExpandClause(LispCons form) {
			return SchemeFeatures.clause(form, SchemeLibraryLowering.featureHost(SchemeLowering.this));
		}

		@Override
		public @Nullable Object importedMacro(LispSymbol identifier) {
			return SchemeLowering.this.importedMacro(identifier);
		}

		@Override
		public @Nullable Object binding(LispSymbol identifier) {
			return lookup(identifier, SchemeLowering.this.global);
		}

		@Override
		public LispSymbol foreign(Object binding) {
			return SchemeLowering.this.foreignSymbols.computeIfAbsent((Binding) binding, key -> {
				LispSymbol identifier = fresh("L");
				SchemeLowering.this.global.bindings.put(identifier.name(), key);
				SchemeLowering.this.foreignIdentifiers.add(identifier);
				if (key instanceof Builtin builtin
						&& (builtin.entry().name().equals("exit") || builtin.entry().name().equals("eval"))) {
					SchemeLowering.this.foreignExitOrEval = true;
				}
				return identifier;
			});
		}

	}

	LispVal falseBinding() {
		return list(symbol("SETQ"), this.falseVariable, list(symbol("QUOTE"), symbol("#f")), this.unspecifiedVariable,
				list(symbol("QUOTE"), symbol(SchemeNames.UNSPECIFIED_NAME)));
	}

	// ------------------------------------------------------------------ top level

	void spliceBegins(LispVal datum, List<LispVal> out) {
		if (datum instanceof LispCons form && syntaxOf(form, this.global) == Core.BEGIN) {
			for (LispVal inner : elements(form.cdr(), form)) {
				spliceBegins(inner, out);
			}
		}
		else {
			out.add(datum);
		}
	}

	// Every (set! name ...) anywhere, by name and blind to scope: over-approximating is
	// safe, it only costs the direct call or the loop.
	void collectAssigned(LispVal datum) {
		if (datum instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head && head.name().endsWith("set!")
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				this.assignedNames.add(name(name));
			}
			LispVal rest = cons;
			while (rest instanceof LispCons cell) {
				collectAssigned(cell.car());
				rest = cell.cdr();
			}
		}
		else if (datum instanceof LispArray array) {
			for (LispVal element : array.data()) {
				collectAssigned(element);
			}
		}
	}

	// R7RS 5.6.1: in a program it is an error to redefine an imported binding. Strict
	// mode reports it where the default lets a user definition win; a session may
	// redefine, as an R7RS REPL does. Before declareGlobals overwrites anything, the
	// global scope holds exactly the imports.
	void refuseRedefiningAnImport(LispSymbol identifier, LispCons form) {
		if (this.standard != SchemeStandard.R7RS || this.interactive) {
			return;
		}
		Binding imported = this.global.bindings.get(name(identifier));
		if (imported instanceof Builtin || imported instanceof Syntax || this.libraryImports.contains(imported)) {
			throw error("cannot redefine " + identifier.name() + ": it is imported (R7RS 5.6.1)", form);
		}
	}

	// A file refuses a redefined record procedure in declareRecord, which sees both
	// definitions; a session meets the second one alone, against the scope it kept.
	void refuseARecordProcedure(LispSymbol identifier, LispCons form) {
		Binding known = this.global.bindings.get(name(identifier));
		if ((known instanceof GlobalFunction || known instanceof GlobalPredicate)
				&& !this.libraryImports.contains(known)) {
			throw error("cannot redefine " + identifier.name() + ", a record procedure", form);
		}
	}

	record DefinedIn(LispCons form, Scope scope, @Nullable Binding self) {
	}

	LispVal defineValues(LispCons form, Scope scope) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() != 3) {
			throw error("malformed define-values", form);
		}
		Formals formals = formals(parts.get(1), form);
		if (formals.rest() != null) {
			throw error("define-values with a rest formal is not supported", form);
		}
		List<LispVal> temporaries = new ArrayList<>();
		List<LispVal> assignments = new ArrayList<>();
		for (LispSymbol variable : formals.required()) {
			LispSymbol temporary = fresh("V");
			temporaries.add(temporary);
			assignments.add(list(symbol("SETQ"), variableSymbol(variable, scope), temporary));
		}
		return new LispCons(symbol("MULTIPLE-VALUE-BIND"),
				new LispCons(listOf(temporaries), new LispCons(value(parts.get(2), scope), listOf(assignments))));
	}

	// ------------------------------------------------------------------ records

	// ------------------------------------------------------------------ expressions

	LispVal value(LispVal expression, Scope scope) {
		return lower(expression, Context.of(scope));
	}

	/**
	 * Lowers one expression: to a form answering its value when the context has no
	 * destination, else to a statement that stores the value in the destination or jumps.
	 */
	LispVal lower(LispVal expression, Context context) {
		if (expression == CORE_UNSPECIFIED) {
			return context.discarded() ? LispNil.INSTANCE : leaf(this.unspecifiedVariable, context);
		}
		if (expression instanceof LispCons form) {
			try {
				return inherit(form, lowerForm(form, context));
			}
			catch (LispReadException ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				throw SourceProvenance.noteFailure(form, ex);
			}
		}
		return leaf(atom(expression, context.scope()), context);
	}

	LispVal leaf(LispVal valueForm, Context context) {
		Destination destination = context.destination();
		if (destination == null) {
			return valueForm;
		}
		if (SchemeValueCount.mayAnswerSeveral(valueForm)) {
			Exit exit = destination.exit();
			exit.used = true;
			return list(symbol("RETURN-FROM"), exit.block, valueForm);
		}
		return list(symbol("SETQ"), destination.result(), valueForm);
	}

	// The loop form, inside the block its leaves return from when any does.
	static LispVal exiting(Exit exit, LispVal loop) {
		return exit.used ? list(symbol("BLOCK"), exit.block, loop) : loop;
	}

	// A form run for its effect, whose Scheme value is the unspecified object: the raw
	// form alone where nobody reads the value.
	LispVal effect(LispVal effectForm, Context context) {
		if (context.discarded()) {
			return effectForm;
		}
		Destination destination = context.destination();
		if (destination == null) {
			return list(symbol("PROGN"), effectForm, this.unspecifiedVariable);
		}
		return list(symbol("PROGN"), effectForm, list(symbol("SETQ"), destination.result(), this.unspecifiedVariable));
	}

	LispVal atom(LispVal expression, Scope scope) {
		return switch (expression) {
			case LispSymbol identifier -> {
				if (identifier == SchemeReader.TRUE) {
					yield LispTrue.INSTANCE;
				}
				yield identifier == SchemeReader.FALSE ? this.falseVariable : reference(identifier, scope);
			}
			case LispArray vector -> list(symbol("QUOTE"), datum(vector));
			default -> expression;
		};
	}

	// A record predicate as a first-class Scheme procedure: #t/#f, not T/NIL.
	LispVal predicateValue(GlobalPredicate predicate) {
		LispSymbol argument = fresh("X");
		return list(symbol("LAMBDA"), list(argument),
				SchemeBuiltins.toSchemeValue(SchemeBuiltins.Result.PREDICATE, list(predicate.symbol(), argument)));
	}

	LispVal reference(LispSymbol identifier, Scope scope) {
		Binding binding = lookup(identifier, scope);
		return switch (binding) {
			case Variable variable -> variable.symbol();
			case GlobalFunction function -> list(symbol("FUNCTION"), function.symbol());
			case GlobalPredicate predicate -> predicateValue(predicate);
			case Builtin builtin -> builtin.entry().function();
			case Constant constant -> constant.form();
			case LoopName loop -> {
				loop.escaped = true;
				yield LispNil.INSTANCE;
			}
			case Syntax syntax ->
				throw new IllegalArgumentException("the keyword " + syntax.name() + " is not a variable");
			case ImportedSyntax macro ->
				throw new IllegalArgumentException("the macro " + macro.name() + " is not a variable");
			// Not defined in this file: a variable some other file defines.
			case null -> cl(identifier);
		};
	}

	LispVal lowerForm(LispCons form, Context context) {
		if (form.car() instanceof LispSymbol head) {
			Binding binding = lookup(head, context.scope());
			if (binding instanceof Syntax syntax) {
				return syntax(syntax, form, context);
			}
		}
		return application(form, context);
	}

	LispVal syntax(Syntax syntax, LispCons form, Context context) {
		Scope scope = context.scope();
		return switch (syntax.core()) {
			case QUOTE -> leaf(quoted(single(form)), context);
			case QUASIQUOTE -> leaf(quasi(single(form), new Quasi(1, scope, form)), context);
			case LAMBDA -> {
				List<LispVal> parts = elements(form, form);
				if (parts.size() < 3) {
					throw error("a lambda needs formals and a body", form);
				}
				yield leaf(SchemeLambdaLowering.lambda(this,
						new ProcedureSpec(formals(parts.get(1), form), parts.subList(2, parts.size()), null, null),
						scope), context);
			}
			case IF -> {
				List<LispVal> parts = elements(form, form);
				if (parts.size() < 3 || parts.size() > 4) {
					throw error("malformed if", form);
				}
				Test test = test(parts.get(1), scope);
				LispVal consequent = lower(parts.get(2), context);
				LispVal alternative = lower(parts.size() == 4 ? parts.get(3) : CORE_UNSPECIFIED, context);
				yield test.negated() ? list(symbol("IF"), test.form(), alternative, consequent)
						: list(symbol("IF"), test.form(), consequent, alternative);
			}
			case SET -> {
				List<LispVal> parts = elements(form, form);
				if (parts.size() != 3) {
					throw error("malformed set!", form);
				}
				LispSymbol assigned = identifier(parts.get(1), form);
				if (this.standard == SchemeStandard.R7RS && !this.interactive
						&& lookup(assigned, scope) instanceof Builtin) {
					throw error("cannot assign " + assigned.name() + ": it is imported (R7RS 5.6.1)", form);
				}
				yield effect(list(symbol("SETQ"), variableSymbol(identifier(parts.get(1), form), scope),
						value(parts.get(2), scope)), context);
			}
			case BEGIN -> {
				List<LispVal> parts = elements(form.cdr(), form);
				yield parts.isEmpty() ? lower(CORE_UNSPECIFIED, context)
						: progn(SchemeBindingLowering.body(this, parts, context.in(new Scope(scope))));
			}
			case LET -> SchemeBindingLowering.let(this, form, context);
			case LET_STAR -> SchemeBindingLowering.letStar(this, form, context);
			case LETREC, LETREC_STAR -> SchemeBindingLowering.letrec(this, form, context);
			case LET_VALUES -> SchemeBindingLowering.letValues(this, form, context, false);
			case LET_STAR_VALUES -> SchemeBindingLowering.letValues(this, form, context, true);
			case DO -> lower(desugarDo(form), context);
			case COND -> lower(desugarCond(elements(form.cdr(), form), context.scope(), form), context);
			case CASE -> lower(desugarCase(form, context.scope()), context);
			case AND, OR -> {
				if (context.destination() == null && isBoolean(form, scope)) {
					yield SchemeBuiltins.toSchemeValue(SchemeBuiltins.Result.PREDICATE, test(form, scope).positive());
				}
				yield lower(syntax.core() == Core.AND ? desugarAnd(elements(form.cdr(), form))
						: desugarOr(elements(form.cdr(), form)), context);
			}
			case WHEN, UNLESS -> {
				List<LispVal> parts = elements(form, form);
				if (parts.size() < 3) {
					throw error("malformed " + syntax.name(), form);
				}
				LispVal body = new LispCons(CORE_BEGIN, listOf(parts.subList(2, parts.size())));
				yield lower(syntax.core() == Core.WHEN ? list(CORE_IF, parts.get(1), body, CORE_UNSPECIFIED)
						: list(CORE_IF, parts.get(1), CORE_UNSPECIFIED, body), context);
			}
			case GUARD -> leaf(guard(form, scope), context);
			case CASE_LAMBDA -> lower(caseLambda(form), context);
			case PARAMETERIZE -> {
				List<LispVal> parts = elements(form, form);
				if (parts.size() < 3) {
					throw error("a parameterize needs ((parameter value) ...) and a body", form);
				}
				List<LispVal> bindings = elements(parts.get(1), form);
				List<LispVal> body = parts.subList(2, parts.size());
				yield bindings.isEmpty()
						? lower(new LispCons(CORE_LET, new LispCons(LispNil.INSTANCE, listOf(body))), context)
						: leaf(parameterize(form, bindings, body, scope), context);
			}
			case DELAY, DELAY_FORCE -> leaf(promise(syntax.core() == Core.DELAY ? 0 : 1, form, scope), context);
			case CONS_STREAM -> {
				List<LispVal> parts = elements(form, form);
				if (parts.size() != 3) {
					throw error("cons-stream takes a head and a tail", form);
				}
				yield leaf(list(symbol("CONS"), value(parts.get(1), scope),
						value(inherit(form, list(CORE_DELAY, parts.get(2))), scope)), context);
			}
			case RAW -> leaf(single(form), context);
			case UNSPECIFIED -> throw error("misplaced unspecified", form);
			case RAW_PREDICATE ->
				leaf(SchemeBuiltins.toSchemeValue(SchemeBuiltins.Result.PREDICATE, single(form)), context);
			case DEFINE, DEFINE_VALUES ->
				throw error("a definition is only allowed at the top level or at the head of a body", form);
			case DEFINE_RECORD_TYPE ->
				throw error("define-record-type is only allowed at the top level or in a body", form);
			case IMPORT -> throw error("import must come before everything else", form);
			case DEFINE_LIBRARY ->
				throw error("define-library must come before the program's import declarations", form);
			// Spliced before the lowering wherever the program spells one; only a macro
			// can
			// produce one after that.
			case INCLUDE, INCLUDE_CI -> throw error(syntax.name() + " cannot be the expansion of a macro", form);
			// Spliced before the lowering too, except where no walk enters: a
			// quasiquote's
			// unquoted expression.
			case COND_EXPAND -> lower(SchemeLibraryLowering.condExpanded(this, form), context);
			case ELSE, ARROW, UNQUOTE, UNQUOTE_SPLICING, ELLIPSIS, UNDERSCORE ->
				throw error("misplaced " + syntax.name(), form);
			// SchemeExpander consumes these before the lowering; one reaches here only
			// through a program the expander never saw a syntax definition in.
			case DEFINE_SYNTAX, LET_SYNTAX, LETREC_SYNTAX, SYNTAX_ERROR ->
				throw error("misplaced " + syntax.name(), form);
			case SYNTAX_RULES -> throw error("syntax-rules is only allowed as a syntax definition's transformer", form);
			case UNSUPPORTED ->
				throw error(syntax.name() + " is not supported by this experimental front end yet", form);
		};
	}

	// (guard (var clause...) body...) (R7RS 4.2.7): %scheme-guard runs the body as a
	// thunk under a handler-case and a guard entry on the Scheme handler stack, then the
	// clauses as a procedure of the raised object, as a cond whose missing else raises
	// the object again -- from the guard, whose body has been unwound by then
	// (.kb/scheme-frontend.md, "Exceptions").
	LispVal guard(LispCons form, Scope scope) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 3 || !(parts.get(1) instanceof LispCons spec)) {
			throw error("a guard needs (variable clause...) and a body", form);
		}
		List<LispVal> specParts = elements(spec, form);
		LispSymbol variable = identifier(specParts.get(0), form);
		LispSymbol raised = fresh("C");
		List<LispVal> clauses = new ArrayList<>(specParts.subList(1, specParts.size()));
		clauses.add(list(Objects.requireNonNull(coreSymbol(Core.ELSE)),
				list(CORE_RAW, list(symbol("RONTOLISP::%SCHEME-RAISE"), raised))));
		LispVal handler = list(CORE_LAMBDA, list(raised),
				list(CORE_LET, list(list(variable, raised)), new LispCons(CORE_COND, listOf(clauses))));
		LispVal body = new LispCons(CORE_LAMBDA,
				new LispCons(LispNil.INSTANCE, listOf(parts.subList(2, parts.size()))));
		return list(symbol("RONTOLISP::%SCHEME-GUARD"), value(inherit(form, body), scope),
				value(inherit(form, handler), scope));
	}

	// (case-lambda (formals body...) ...) (R7RS 4.2.9): one rest-argument lambda whose
	// body takes the first clause whose formals accept the argument count, each formal
	// bound from the argument list by a let -- so a clause body is a <body>, and a self
	// tail call of a procedure defined by one is a jump like any lambda's. No clause
	// accepting the count is a Scheme error naming the arguments. Desugared once per
	// datum: the pre-scans ask for it before the lowering does.
	LispVal caseLambda(LispCons form) {
		LispVal cached = this.caseLambdas.get(form);
		if (cached != null) {
			return cached;
		}
		LispSymbol arguments = fresh("A");
		LispSymbol count = fresh("N");
		boolean counted = false;
		LispVal chain = list(CORE_RAW, list(symbol("RONTOLISP::%SCHEME-CASE-LAMBDA-ARITY"), arguments));
		List<LispVal> clauses = elements(form.cdr(), form);
		for (int i = clauses.size() - 1; i >= 0; i--) {
			LispVal clause = clauses.get(i);
			if (!(clause instanceof LispCons where) || elements(where, form).size() < 2) {
				throw error("a case-lambda clause is (formals body...)", clause instanceof LispCons cons ? cons : form);
			}
			List<LispVal> parts = elements(where, where);
			Formals formals = formals(parts.get(0), where);
			int required = formals.required().size();
			List<LispVal> bindings = new ArrayList<>();
			for (int j = 0; j < required; j++) {
				bindings.add(list(formals.required().get(j),
						list(CORE_RAW, list(symbol("NTH"), new LispInteger(j), arguments))));
			}
			if (formals.rest() != null) {
				bindings.add(list(formals.rest(), list(CORE_RAW,
						required == 0 ? arguments : list(symbol("NTHCDR"), new LispInteger(required), arguments))));
			}
			LispVal body = inherit(where,
					new LispCons(CORE_LET, new LispCons(listOf(bindings), listOf(parts.subList(1, parts.size())))));
			if (formals.rest() != null && required == 0) {
				chain = body;
				continue;
			}
			counted = true;
			LispVal test = list(symbol(formals.rest() == null ? "=" : ">="), count, new LispInteger(required));
			chain = list(CORE_IF, list(CORE_RAW_PREDICATE, test), body, chain);
		}
		LispVal dispatch = counted
				? list(CORE_LET, list(list(count, list(CORE_RAW, list(symbol("LENGTH"), arguments)))), chain) : chain;
		LispVal lambda = inherit(form, list(CORE_LAMBDA, arguments, dispatch));
		this.caseLambdas.put(form, lambda);
		return lambda;
	}

	// (parameterize ((param value) ...) body...) (R7RS 4.2.6): %scheme-parameterize takes
	// the parameters and values alternating, in the order written, converts every value
	// before binding any, and runs the body thunk with them bound -- a special let inside
	// the helper, so every exit restores them (.kb/scheme-frontend.md, "Parameters").
	LispVal parameterize(LispCons form, List<LispVal> bindings, List<LispVal> body, Scope scope) {
		List<LispVal> operands = new ArrayList<>();
		operands.add(symbol("LIST"));
		for (LispVal binding : bindings) {
			if (!(binding instanceof LispCons where) || elements(where, where).size() != 2) {
				throw error("a parameterize binding is (parameter value)",
						binding instanceof LispCons cons ? cons : form);
			}
			List<LispVal> pair = elements(where, where);
			operands.add(value(pair.get(0), scope));
			operands.add(value(pair.get(1), scope));
		}
		LispVal thunk = new LispCons(CORE_LAMBDA, new LispCons(LispNil.INSTANCE, listOf(body)));
		return list(symbol("RONTOLISP::%SCHEME-PARAMETERIZE"), listOf(operands), value(inherit(form, thunk), scope));
	}

	// (delay e) and (delay-force e): a promise record around the state and a thunk
	// lowered like any lambda, so a loop that delays rebinds its variables per iteration.
	LispVal promise(int state, LispCons form, Scope scope) {
		LispVal thunk = value(inherit(form, list(CORE_LAMBDA, LispNil.INSTANCE, single(form))), scope);
		return list(symbol("RONTOLISP::%SCHEME-DELAY"), new LispInteger(state), thunk);
	}

	// ------------------------------------------------------------------ application

	LispVal application(LispCons form, Context context) {
		List<LispVal> parts = elements(form, form);
		Scope scope = context.scope();
		LispVal operator = parts.get(0);
		List<LispVal> operands = parts.subList(1, parts.size());
		Binding binding = operator instanceof LispSymbol head ? lookup(head, scope) : null;
		Destination destination = context.destination();
		if (destination != null && binding != null) {
			for (Target target : destination.targets()) {
				if (target.binding == binding && target.accepts(operands.size())) {
					if (target.shadowedFrom(scope)) {
						target.shadowed = true;
					}
					return jump(target, values(operands, scope));
				}
			}
		}
		Call call = new Call(form, operator, operands, binding);
		if (binding instanceof Builtin builtin && builtin.entry().result() == SchemeBuiltins.Result.EFFECT) {
			return effect(builtinEffect(call, builtin, scope), context);
		}
		return leaf(call(call, scope), context);
	}

	// An effect builtin's raw form: what a call lowers to before the unspecified object
	// is added.
	LispVal builtinEffect(Call call, Builtin builtin, Scope scope) {
		LispVal literal = displayOfALiteral(call, builtin);
		return literal != null ? literal : builtinCall(call, builtin, scope);
	}

	private record Call(LispCons form, LispVal operator, List<LispVal> operands, @Nullable Binding binding) {
	}

	LispVal call(Call call, Scope scope) {
		if (call.binding() instanceof Builtin builtin) {
			LispVal multipleValues = callWithValues(call, scope);
			if (multipleValues != null) {
				return multipleValues;
			}
			return SchemeBuiltins.toSchemeValue(builtin.entry().result(), builtinCall(call, builtin, scope));
		}
		List<LispVal> arguments = values(call.operands(), scope);
		return switch (call.binding()) {
			case GlobalFunction function -> {
				// A case-lambda's clause is picked here when the count picks one; a count
				// none accepts reaches the dispatch, which reports it at run time.
				Clause clause = function.clauseFor(arguments.size());
				yield new LispCons(clause != null ? clause.symbol() : function.symbol(), listOf(arguments));
			}
			case GlobalPredicate predicate -> SchemeBuiltins.toSchemeValue(SchemeBuiltins.Result.PREDICATE,
					new LispCons(predicate.symbol(), listOf(arguments)));
			case LoopName loop -> {
				loop.escaped = true;
				yield LispNil.INSTANCE;
			}
			case Variable variable ->
				new LispCons(symbol("FUNCALL"), new LispCons(ensure(variable.symbol()), listOf(arguments)));
			case null, default -> {
				if (call.operator() instanceof LispSymbol head) {
					if (head == SchemeReader.FALSE) {
						yield new LispCons(symbol("FUNCALL"),
								new LispCons(ensure(this.falseVariable), listOf(arguments)));
					}
					if (head == SchemeReader.TRUE) {
						yield new LispCons(symbol("FUNCALL"),
								new LispCons(ensure(LispTrue.INSTANCE), listOf(arguments)));
					}
					// Not defined in this file: a procedure some other file defines.
					yield new LispCons(cl(head), listOf(arguments));
				}
				yield new LispCons(symbol("FUNCALL"),
						new LispCons(ensure(value(call.operator(), scope)), listOf(arguments)));
			}
		};
	}

	// A combination's operator, checked to be a procedure as eval checks it
	// (%scheme-eval-apply): a Scheme application applies a procedure value, never a
	// symbol designator, so #f, the unspecified object, a number or a symbol reports
	// "The object is not applicable" with the value printed as Scheme prints it, on
	// every backend, without any backend learning a Scheme name.
	private static LispVal ensure(LispVal operator) {
		return list(symbol("RONTOLISP::%SCHEME-ENSURE-PROCEDURE"), operator);
	}

	// (display "text") needs no printer: the generic one dispatches on every type there
	// is,
	// so it is the most expensive helper a program can reach, and a literal's type is
	// known
	// here (.kb/scheme-frontend.md, "Size").
	private static @Nullable LispVal displayOfALiteral(Call call, Builtin builtin) {
		if (!builtin.entry().name().equals("display") || call.operands().size() != 1) {
			return null;
		}
		return switch (call.operands().get(0)) {
			case LispString text -> list(symbol("WRITE-STRING"), text);
			case LispChar character -> list(symbol("WRITE-CHAR"), character);
			case LispInteger integer -> list(symbol("PRINC"), integer);
			default -> null;
		};
	}

	LispVal builtinCall(Call call, Builtin builtin, Scope scope) {
		LispVal raw = builtin.entry().call(values(call.operands(), scope), () -> fresh("A"));
		if (raw == null) {
			throw error("wrong number of arguments to " + builtin.entry().name() + ": " + call.operands().size(),
					call.form());
		}
		return raw;
	}

	// (call-with-values (lambda () producer...) (lambda (a b) consumer...)) is the one
	// shape where both halves are visible: a multiple-value-bind, no list in between
	// (.kb/multiple-values.md).
	private @Nullable LispVal callWithValues(Call call, Scope scope) {
		if (!((Builtin) java.util.Objects.requireNonNull(call.binding())).entry().name().equals("call-with-values")
				|| call.operands().size() != 2) {
			return null;
		}
		if (!(call.operands().get(0) instanceof LispCons producer && syntaxOf(producer, scope) == Core.LAMBDA
				&& call.operands().get(1) instanceof LispCons consumer && syntaxOf(consumer, scope) == Core.LAMBDA)) {
			return null;
		}
		List<LispVal> producerParts = elements(producer, producer);
		List<LispVal> consumerParts = elements(consumer, consumer);
		if (producerParts.size() < 3 || consumerParts.size() < 3 || producerParts.get(1) != LispNil.INSTANCE) {
			return null;
		}
		Formals formals = formals(consumerParts.get(1), consumer);
		if (formals.rest() != null) {
			return null;
		}
		LispVal produced = progn(SchemeBindingLowering.body(this, producerParts.subList(2, producerParts.size()),
				Context.of(new SchemeLowering.Scope(scope))));
		Scope inner = new Scope(scope);
		List<LispVal> variables = new ArrayList<>();
		for (LispSymbol formal : formals.required()) {
			variables.add(bind(formal, inner));
		}
		return new LispCons(symbol("MULTIPLE-VALUE-BIND"),
				new LispCons(listOf(variables), new LispCons(produced, listOf(SchemeBindingLowering.body(this,
						consumerParts.subList(2, consumerParts.size()), Context.of(inner))))));
	}

	List<LispVal> values(List<LispVal> expressions, Scope scope) {
		List<LispVal> lowered = new ArrayList<>();
		for (LispVal expression : expressions) {
			lowered.add(value(expression, scope));
		}
		return lowered;
	}

	LispVal jump(Target target, List<LispVal> arguments) {
		target.used = true;
		List<LispVal> assignments = new ArrayList<>();
		int required = target.rest ? target.assigned.size() - 1 : target.assigned.size();
		for (int i = 0; i < required; i++) {
			assignments.add(target.assigned.get(i));
			assignments.add(arguments.get(i));
		}
		if (target.rest) {
			assignments.add(target.assigned.get(required));
			assignments.add(new LispCons(symbol("LIST"), listOf(arguments.subList(required, arguments.size()))));
		}
		assignments.addAll(target.presets);
		List<LispVal> forms = new ArrayList<>();
		if (!assignments.isEmpty()) {
			// The new values are computed from the OLD variables: a psetq when the jump
			// assigns the loop variables themselves, plain setqs when it assigns
			// carriers no argument can mention.
			boolean sequential = !target.parallel || assignments.size() == 2;
			forms.add(new LispCons(symbol(sequential ? "SETQ" : "PSETQ"), listOf(assignments)));
		}
		forms.add(list(symbol("GO"), target.label));
		return progn(forms);
	}

	// ------------------------------------------------------------------ tests

	Test test(LispVal expression, Scope scope) {
		if (expression == SchemeReader.FALSE) {
			return new Test(LispNil.INSTANCE, false);
		}
		if (!(expression instanceof LispCons form)) {
			return expression instanceof LispSymbol ? generic(expression, scope) : new Test(LispTrue.INSTANCE, false);
		}
		if (!(form.car() instanceof LispSymbol head)) {
			return generic(expression, scope);
		}
		Binding binding = lookup(head, scope);
		List<LispVal> operands = elements(form.cdr(), form);
		switch (binding) {
			case Syntax syntax when syntax.core() == Core.AND || syntax.core() == Core.OR -> {
				List<LispVal> tests = new ArrayList<>();
				for (LispVal operand : operands) {
					tests.add(test(operand, scope).positive());
				}
				return new Test(new LispCons(symbol(syntax.core() == Core.AND ? "AND" : "OR"), listOf(tests)), false);
			}
			case Syntax syntax when syntax.core() == Core.RAW_PREDICATE -> {
				return new Test(single(form), false);
			}
			case Syntax syntax when syntax.core() == Core.QUOTE -> {
				return new Test(single(form) == SchemeReader.FALSE ? LispNil.INSTANCE : LispTrue.INSTANCE, false);
			}
			case Builtin builtin when builtin.entry().name().equals("not") && operands.size() == 1 -> {
				Test inner = test(operands.get(0), scope);
				return new Test(inner.form(), !inner.negated());
			}
			case Builtin builtin when builtin.entry().result() == SchemeBuiltins.Result.PREDICATE
					|| builtin.entry().result() == SchemeBuiltins.Result.OR_FALSE -> {
				return new Test(inherit(form, builtinCall(new Call(form, head, operands, builtin), builtin, scope)),
						false);
			}
			case GlobalPredicate predicate -> {
				return new Test(inherit(form, new LispCons(predicate.symbol(), listOf(values(operands, scope)))),
						false);
			}
			case null, default -> {
				return generic(expression, scope);
			}
		}
	}

	// (if c a b) over an arbitrary value: (if (eq c false) b a).
	Test generic(LispVal expression, Scope scope) {
		return new Test(list(symbol("EQ"), value(expression, scope), this.falseVariable), true);
	}

	// Whether the expression can only answer #t or #f, so its VALUE is (if test t false)
	// rather than a temporary per `or` operand.
	boolean isBoolean(LispVal expression, Scope scope) {
		if (expression == SchemeReader.TRUE || expression == SchemeReader.FALSE) {
			return true;
		}
		if (!(expression instanceof LispCons form && form.car() instanceof LispSymbol head)) {
			return false;
		}
		return switch (lookup(head, scope)) {
			case Syntax syntax when syntax.core() == Core.AND || syntax.core() == Core.OR -> {
				for (LispVal operand : elements(form.cdr(), form)) {
					if (!isBoolean(operand, scope)) {
						yield false;
					}
				}
				yield true;
			}
			case Syntax syntax -> syntax.core() == Core.RAW_PREDICATE;
			case Builtin builtin -> builtin.entry().result() == SchemeBuiltins.Result.PREDICATE;
			case GlobalPredicate predicate -> true;
			case null, default -> false;
		};
	}

	// ------------------------------------------------------------------ derived forms

	static LispVal desugarAnd(List<LispVal> operands) {
		if (operands.isEmpty()) {
			return SchemeReader.TRUE;
		}
		if (operands.size() == 1) {
			return operands.get(0);
		}
		return list(CORE_IF, operands.get(0), new LispCons(CORE_AND, listOf(operands.subList(1, operands.size()))),
				SchemeReader.FALSE);
	}

	LispVal desugarOr(List<LispVal> operands) {
		if (operands.isEmpty()) {
			return SchemeReader.FALSE;
		}
		if (operands.size() == 1) {
			return operands.get(0);
		}
		LispVal rest = new LispCons(CORE_OR, listOf(operands.subList(1, operands.size())));
		LispSymbol temporary = fresh("T");
		return list(CORE_LET, list(list(temporary, operands.get(0))), list(CORE_IF, temporary, temporary, rest));
	}

	LispVal desugarCond(List<LispVal> clauses, Scope scope, LispCons form) {
		if (clauses.isEmpty()) {
			return CORE_UNSPECIFIED;
		}
		List<LispVal> clause = elements(clauses.get(0), form);
		if (clause.isEmpty()) {
			throw error("an empty cond clause", form);
		}
		LispVal rest = new LispCons(CORE_COND, listOf(clauses.subList(1, clauses.size())));
		LispVal test = clause.get(0);
		if (test instanceof LispSymbol keyword && lookup(keyword, scope) instanceof Syntax syntax
				&& syntax.core() == Core.ELSE) {
			return new LispCons(CORE_BEGIN, listOf(clause.subList(1, clause.size())));
		}
		if (clause.size() == 1) {
			return list(CORE_OR, test, rest);
		}
		if (isArrow(clause.get(1), scope)) {
			if (clause.size() != 3) {
				throw error("a => clause takes exactly one receiver", form);
			}
			LispSymbol temporary = fresh("T");
			return list(CORE_LET, list(list(temporary, test)),
					list(CORE_IF, temporary, list(clause.get(2), temporary), rest));
		}
		return list(CORE_IF, test, new LispCons(CORE_BEGIN, listOf(clause.subList(1, clause.size()))), rest);
	}

	boolean isArrow(LispVal datum, Scope scope) {
		return datum instanceof LispSymbol keyword && lookup(keyword, scope) instanceof Syntax syntax
				&& syntax.core() == Core.ARROW;
	}

	// (case key ((d...) e...)... (else e...)): the key once, then eqv? tests spelled
	// straight in Common Lisp, so they fuse into the ifs.
	LispVal desugarCase(LispCons form, Scope scope) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 2) {
			throw error("malformed case", form);
		}
		LispSymbol key = fresh("K");
		LispVal chain = CORE_UNSPECIFIED;
		for (int i = parts.size() - 1; i >= 2; i--) {
			List<LispVal> clause = elements(parts.get(i), form);
			if (clause.size() < 2) {
				throw error("malformed case clause", form);
			}
			LispVal body = isArrow(clause.get(1), scope) && clause.size() == 3 ? list(clause.get(2), key)
					: new LispCons(CORE_BEGIN, listOf(clause.subList(1, clause.size())));
			if (clause.get(0) instanceof LispSymbol keyword && lookup(keyword, scope) instanceof Syntax syntax
					&& syntax.core() == Core.ELSE) {
				chain = body;
				continue;
			}
			List<LispVal> tests = new ArrayList<>();
			for (LispVal datum : elements(clause.get(0), form)) {
				tests.add(list(symbol("EQL"), key, quoted(datum)));
			}
			LispVal test = tests.size() == 1 ? tests.get(0) : new LispCons(symbol("OR"), listOf(tests));
			chain = list(CORE_IF, list(CORE_RAW_PREDICATE, test), body, chain);
		}
		return list(CORE_LET, list(list(key, parts.get(1))), chain);
	}

	// (do ((var init step)...) (test result...) body...) is a named let whose only
	// reference to its name is the tail call, so it always lowers to a pure loop.
	LispVal desugarDo(LispCons form) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 3) {
			throw error("malformed do", form);
		}
		LispSymbol loop = fresh("DO");
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> steps = new ArrayList<>();
		for (LispVal spec : elements(parts.get(1), form)) {
			List<LispVal> binding = elements(spec, form);
			if (binding.size() < 2 || binding.size() > 3) {
				throw error("malformed do binding", form);
			}
			bindings.add(list(binding.get(0), binding.get(1)));
			steps.add(binding.size() == 3 ? binding.get(2) : binding.get(0));
		}
		List<LispVal> exit = elements(parts.get(2), form);
		if (exit.isEmpty()) {
			throw error("a do loop needs a test", form);
		}
		List<LispVal> body = new ArrayList<>(parts.subList(3, parts.size()));
		body.add(new LispCons(loop, listOf(steps)));
		return new LispCons(CORE_LET,
				new LispCons(loop,
						new LispCons(listOf(bindings),
								list(list(CORE_IF, exit.get(0),
										new LispCons(CORE_BEGIN, listOf(exit.subList(1, exit.size()))),
										new LispCons(CORE_BEGIN, listOf(body)))))));
	}

	static LispVal lambdaList(List<LispSymbol> parameters, boolean rest) {
		List<LispVal> lambdaList = new ArrayList<>(parameters);
		if (rest) {
			lambdaList.add(lambdaList.size() - 1, symbol("&REST"));
		}
		return listOf(lambdaList);
	}

	// ------------------------------------------------------------------ data

	LispVal quoted(LispVal datum) {
		LispVal converted = datum(datum);
		return switch (converted) {
			case LispSymbol symbol -> list(symbol("QUOTE"), symbol);
			case LispCons cons -> list(symbol("QUOTE"), cons);
			case LispArray array -> list(symbol("QUOTE"), array);
			default -> converted;
		};
	}

	// A Scheme datum as the Common Lisp datum it denotes: '() is NIL, #t is T, #f is the
	// false value's symbol, an identifier its mangled spelling.
	LispVal datum(LispVal datum) {
		return switch (datum) {
			case LispSymbol symbol -> {
				if (symbol == SchemeReader.TRUE) {
					yield LispTrue.INSTANCE;
				}
				yield symbol == SchemeReader.FALSE ? symbol("#f") : symbol(SchemeNames.mangle(symbol.name()));
			}
			case LispCons cons -> {
				List<LispVal> elements = new ArrayList<>();
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					elements.add(datum(cell.car()));
					rest = cell.cdr();
				}
				LispVal list = datum(rest);
				for (int i = elements.size() - 1; i >= 0; i--) {
					list = new LispCons(elements.get(i), list);
				}
				yield list;
			}
			case LispArray array -> {
				LispVal[] elements = new LispVal[array.data().length];
				for (int i = 0; i < elements.length; i++) {
					elements[i] = datum(array.data()[i]);
				}
				yield new LispArray(new int[] { elements.length }, elements);
			}
			default -> datum;
		};
	}

	private record Quasi(int depth, Scope scope, LispCons form) {

		Quasi deeper() {
			return new Quasi(this.depth + 1, this.scope, this.form);
		}

		Quasi shallower() {
			return new Quasi(this.depth - 1, this.scope, this.form);
		}

	}

	// Walks the cdr spine in a loop, so a long template costs no stack per element: an
	// ordinary cell's car is expanded on the way down, as the recursive walk did before
	// descending, and a splice's own element on the way back up, after its tail.
	LispVal quasi(LispVal template, Quasi quasi) {
		List<LispCons> cells = new ArrayList<>();
		List<@Nullable LispVal> cars = new ArrayList<>();
		LispVal node = template;
		LispVal result;
		while (true) {
			if (node instanceof LispArray vector) {
				result = !hasUnquote(node) ? quoted(node) : list(symbol("COERCE"),
						quasi(listOf(List.of(vector.data())), quasi), list(symbol("QUOTE"), symbol("VECTOR")));
				break;
			}
			if (!(node instanceof LispCons cons) || !hasUnquote(node)) {
				result = quoted(node);
				break;
			}
			LispVal unquoted = quasiUnquote(cons, quasi);
			if (unquoted != null) {
				result = unquoted;
				break;
			}
			cells.add(cons);
			cars.add(spliceOf(cons) != null ? null : quasi(cons.car(), quasi));
			node = cons.cdr();
		}
		for (int i = cells.size() - 1; i >= 0; i--) {
			LispCons cons = cells.get(i);
			LispVal car = cars.get(i);
			LispCons splice = spliceOf(cons);
			if (car != null || splice == null) {
				result = list(symbol("CONS"), Objects.requireNonNull(car), result);
				continue;
			}
			LispSymbol head = (LispSymbol) splice.car();
			LispVal element = ((LispCons) splice.cdr()).car();
			result = quasi.depth() == 1 ? list(symbol("APPEND"), value(element, quasi.scope()), result) : list(
					symbol("CONS"), list(symbol("LIST"), quoted(head), quasi(element, quasi.shallower())), result);
		}
		return result;
	}

	// An (unquote x) or (quasiquote x) cell -- the whole cell, which ends the walk down a
	// template's spine -- or null for any other cell.
	private @Nullable LispVal quasiUnquote(LispCons cons, Quasi quasi) {
		if (cons.car() instanceof LispSymbol head && cons.cdr() instanceof LispCons rest
				&& rest.cdr() == LispNil.INSTANCE) {
			if (head.name().equals("unquote")) {
				return quasi.depth() == 1 ? value(rest.car(), quasi.scope())
						: list(symbol("LIST"), quoted(head), quasi(rest.car(), quasi.shallower()));
			}
			if (head.name().equals("quasiquote")) {
				return list(symbol("LIST"), quoted(head), quasi(rest.car(), quasi.deeper()));
			}
		}
		return null;
	}

	// The (unquote-splicing x) a cell holds as its car, or null.
	private static @Nullable LispCons spliceOf(LispCons cons) {
		if (cons.car() instanceof LispCons splice && splice.car() instanceof LispSymbol head
				&& head.name().equals("unquote-splicing") && splice.cdr() instanceof LispCons rest
				&& rest.cdr() == LispNil.INSTANCE) {
			return splice;
		}
		return null;
	}

	private static boolean hasUnquote(LispVal template) {
		if (template instanceof LispArray vector) {
			for (LispVal element : vector.data()) {
				if (hasUnquote(element)) {
					return true;
				}
			}
			return false;
		}
		LispVal rest = template;
		while (rest instanceof LispCons cell) {
			if (cell.car() instanceof LispSymbol head
					&& (head.name().equals("unquote") || head.name().equals("unquote-splicing"))) {
				return true;
			}
			if (hasUnquote(cell.car())) {
				return true;
			}
			rest = cell.cdr();
		}
		return false;
	}

	// ------------------------------------------------------------------ names and
	// helpers

	@Nullable Binding lookup(LispSymbol identifier, Scope scope) {
		Core core = CORE_SYMBOLS.get(identifier);
		if (core != null) {
			return new Syntax(core, identifier.name());
		}
		return scope.find(name(identifier));
	}

	@Nullable Core syntaxOf(LispCons form, Scope scope) {
		return form.car() instanceof LispSymbol head && lookup(head, scope) instanceof Syntax syntax ? syntax.core()
				: null;
	}

	// The scope key AND the emitted spelling: a generated temporary verbatim (uppercase,
	// which no mangled user identifier can be), a user identifier mangled.
	String name(LispSymbol identifier) {
		return this.generated.contains(identifier) ? identifier.name() : SchemeNames.mangle(identifier.name());
	}

	LispSymbol cl(LispSymbol identifier) {
		return this.generated.contains(identifier) ? identifier : symbol(name(identifier));
	}

	// A top-level name this lowering defines: the user's spelling in a program, private
	// to the library in a library (SchemeNames.libraryPrefix).
	LispSymbol global(LispSymbol identifier) {
		return this.generated.contains(identifier) ? identifier : symbol(this.prefix + name(identifier));
	}

	LispSymbol bind(LispSymbol identifier, Scope scope) {
		LispSymbol variable = cl(identifier);
		scope.bindings.put(name(identifier), new Variable(variable));
		return variable;
	}

	LispSymbol variableSymbol(LispSymbol identifier, Scope scope) {
		return switch (lookup(identifier, scope)) {
			// A library's own macro may assign the library's variable.
			case Variable variable when this.libraryImports.contains(variable)
					&& !this.foreignIdentifiers.contains(identifier) ->
				throw new IllegalArgumentException(
						"cannot assign " + identifier.name() + ": it is imported from a library");
			case Variable variable -> variable.symbol();
			case null -> cl(identifier);
			default -> throw new IllegalArgumentException(
					"cannot assign " + identifier.name() + ": it is not a variable in this file");
		};
	}

	LispSymbol fresh(String kind) {
		LispSymbol temporary = new LispSymbol(this.prefix + "%SCM-" + kind + (++this.counter));
		this.generated.add(temporary);
		return temporary;
	}

	Formals formals(LispVal datum, LispCons form) {
		List<LispSymbol> required = new ArrayList<>();
		LispVal rest = datum;
		while (rest instanceof LispCons cell) {
			required.add(identifier(cell.car(), form));
			rest = cell.cdr();
		}
		return new Formals(required, rest == LispNil.INSTANCE ? null : identifier(rest, form));
	}

	LispSymbol identifier(LispVal datum, LispCons form) {
		if (datum instanceof LispSymbol symbol && symbol != SchemeReader.TRUE && symbol != SchemeReader.FALSE) {
			return symbol;
		}
		throw error("expected an identifier, got " + datum.print(), form);
	}

	LispVal single(LispCons form) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() != 2) {
			throw error("expected exactly one operand", form);
		}
		return parts.get(1);
	}

	LispVal second(LispCons form) {
		return form.cdr() instanceof LispCons rest ? rest.car() : LispNil.INSTANCE;
	}

	List<LispVal> elements(LispVal list, LispCons form) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = list;
		while (rest instanceof LispCons cell) {
			elements.add(cell.car());
			rest = cell.cdr();
		}
		if (rest != LispNil.INSTANCE) {
			throw error("expected a proper list, got " + list.print(), form);
		}
		return elements;
	}

	LispReadException error(String message, LispCons form) {
		return new LispReadException(message, this.reader.locate(form));
	}

	// A datum rewrite standing where `original` stands, positioned in both tables: the
	// reader's (syntax errors) and SourceProvenance's -- whose answer, a located copy on
	// the interpreter, is the cell the reader must know.
	<T extends LispVal> T positioned(LispCons original, T rewritten) {
		T answer = inherit(original, rewritten);
		if (answer instanceof LispCons cons) {
			this.reader.inherit(original, cons);
		}
		return answer;
	}

	static <T extends LispVal> T inherit(LispCons original, T lowered) {
		return SourceProvenance.inherit(original, lowered);
	}

	static LispVal progn(List<LispVal> forms) {
		return forms.size() == 1 ? forms.get(0) : new LispCons(symbol("PROGN"), listOf(forms));
	}

	static LispSymbol symbol(String name) {
		return new LispSymbol(name);
	}

	static LispVal list(LispVal... elements) {
		return SchemeBuiltins.list(elements);
	}

	static LispVal listOf(List<? extends LispVal> elements) {
		return SchemeBuiltins.listOf(new ArrayList<>(elements));
	}

}
