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

	private static final SequencedMap<String, Core> SYNTAX = syntaxTable();

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
		table.put("cond-expand", Core.UNSUPPORTED);
		return table;
	}

	/** The keywords of {@code (scheme lazy)}. */
	private static final SequencedMap<String, Core> LAZY_SYNTAX = orderedMap("delay", Core.DELAY, "delay-force",
			Core.DELAY_FORCE);

	/** The keyword of {@code (scheme case-lambda)}. */
	private static final SequencedMap<String, Core> CASE_LAMBDA_SYNTAX = orderedMap("case-lambda", Core.CASE_LAMBDA);

	/** The SICP keyword no R7RS library exports: {@code (cons-stream a b)}. */
	private static final SequencedMap<String, Core> SICP_SYNTAX = orderedMap("cons-stream", Core.CONS_STREAM);

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
	private static final Map<LispSymbol, Core> CORE_SYMBOLS = new IdentityHashMap<>();

	private static final Map<Core, LispSymbol> CORE_BY_CORE = new java.util.EnumMap<>(Core.class);

	private static final LispSymbol CORE_IF = core("if", Core.IF);

	private static final LispSymbol CORE_LET = core("let", Core.LET);

	private static final LispSymbol CORE_BEGIN = core("begin", Core.BEGIN);

	private static final LispSymbol CORE_AND = core("and", Core.AND);

	private static final LispSymbol CORE_OR = core("or", Core.OR);

	private static final LispSymbol CORE_COND = core("cond", Core.COND);

	private static final LispSymbol CORE_LAMBDA = core("lambda", Core.LAMBDA);

	private static final LispSymbol CORE_RAW_PREDICATE = core("raw-predicate", Core.RAW_PREDICATE);

	// (raw form): a Common Lisp form a desugaring puts where an expression stands.
	private static final LispSymbol CORE_RAW = core("raw", Core.RAW);

	// Stands where a desugaring has no expression to put: the missing arm of an if, a
	// cond or case no clause of which is taken. Lowered to the unspecified object.
	private static final LispSymbol CORE_UNSPECIFIED = core("unspecified", Core.UNSPECIFIED);

	private static final LispSymbol CORE_DELAY = core("delay", Core.DELAY);

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
	private sealed interface Binding {

	}

	/** A variable: referenced bare, called through {@code funcall}. */
	private record Variable(LispSymbol symbol) implements Binding {
	}

	/** A top-level procedure lowered to a {@code defun}: called directly. */
	private record GlobalFunction(LispSymbol symbol) implements Binding {
	}

	/** A record type's predicate: a {@code defun} answering {@code T}/{@code NIL}. */
	private record GlobalPredicate(LispSymbol symbol) implements Binding {
	}

	private record Builtin(SchemeBuiltins.Entry entry) implements Binding {
	}

	/**
	 * A bare value, not a procedure: {@code true}, {@code false}, {@code nil},
	 * {@code user-initial-environment} ({@code SchemeBuiltins.constants()}). Not an R7RS
	 * export of {@code (scheme base)}, so unreachable by name through {@code import} --
	 * only the no-import default merges it (a REPL, and a file with no import at all).
	 */
	private record Constant(LispVal form) implements Binding {
	}

	private record Syntax(Core core, String name) implements Binding {
	}

	/**
	 * The name of a named {@code let} being tried as a PURE loop: every reference must be
	 * a jump. Any other use -- a non-tail call, a call from inside a {@code lambda}, a
	 * bare reference -- sets {@link #escaped}, and the loop is lowered again as a
	 * procedure.
	 */
	private static final class LoopName implements Binding {

		private boolean escaped;

	}

	private static final class Scope {

		private final @Nullable Scope parent;

		private final Map<String, Binding> bindings = new HashMap<>();

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
	private static final class Target {

		private final Binding binding;

		private final LispSymbol label;

		private final List<LispSymbol> assigned;

		private final boolean rest;

		private final boolean parallel;

		private final Scope home;

		private final List<String> names;

		private boolean used;

		private boolean shadowed;

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
	private record LoopShape(LispSymbol label, List<LispSymbol> assigned, boolean rest, boolean parallel) {
	}

	/**
	 * Where a statement puts its value, and the labels it may jump to.
	 *
	 * @param result the variable a leaf assigns
	 * @param targets the loops a tail call may jump to
	 * @param exit the outermost loop's block, for a leaf that may answer other than one
	 * value
	 */
	private record Destination(LispSymbol result, List<Target> targets, Exit exit) {
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
	private static final class Exit {

		private final LispSymbol block;

		private boolean used;

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
	private record Context(Scope scope, @Nullable Destination destination, boolean discarded) {

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
	private record Test(LispVal form, boolean negated) {

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
	private record Formals(List<LispSymbol> required, @Nullable LispSymbol rest) {

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
	 */
	private record ProcedureSpec(Formals formals, List<LispVal> body, @Nullable Binding self,
			@Nullable LispSymbol selfName) {
	}

	private record Lowered(LispVal lambdaList, List<LispVal> body) {
	}

	// The text being lowered: the file, or the buffer a session is reading now.
	private SchemeReader reader;

	private List<LispVal> datums = List.of();

	// A session lowers one buffer at a time against a global scope that outlives each of
	// them, so nothing may depend on having seen the whole program.
	private final boolean interactive;

	private final SchemeStandard standard;

	private boolean falseBound;

	private final Set<LispSymbol> generated = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	private final Set<String> assignedNames = new HashSet<>();

	// Each case-lambda datum's desugaring, so every pre-scan and the lowering see one.
	private final Map<LispCons, LispVal> caseLambdas = new IdentityHashMap<>();

	// What a file's names read before their definition hold until then.
	private final SequencedMap<LispSymbol, LispVal> initialValues = new LinkedHashMap<>();

	private final Scope global = new Scope(null);

	// The user libraries this lowering and the ones it imports know.
	private final SchemeLibraries<Binding> libraries;

	// What this lowering's top-level names start with: nothing for a program or a
	// session, the library's private prefix for a library (SchemeNames.libraryPrefix).
	private final String prefix;

	// The bindings imported from a user library: an importer may not assign them, and
	// under r7rs may not redefine them either.
	private final Set<Binding> libraryImports = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	private final LispSymbol falseVariable = symbol(SchemeBuiltins.FALSE_VARIABLE);

	private final LispSymbol unspecifiedVariable = symbol(SchemeBuiltins.UNSPECIFIED_VARIABLE);

	/**
	 * The catch tag {@code exit} throws its code to, in canonical spelling: the template
	 * spells it lowercase and the reader upcases it.
	 */
	static final String EXIT_TAG_NAME = "RONTOLISP::%SCHEME-EXIT-TAG";

	private static final String EXIT_FUNCTION_NAME = "RONTOLISP::%SCHEME-EXIT";

	private int counter;

	private int closures;

	// Each internal define-record-type datum's hoisted type, so a body lowered twice (a
	// loop tried as a pure loop first) defines it once and binds the same names.
	private final Map<LispCons, InternalRecord> internalRecords = new IdentityHashMap<>();

	// Every internal record type name taken so far; a session keeps them, so a type
	// typed again in a later buffer is a new one and the old instances keep their
	// layout.
	private final Set<String> internalRecordNames = new HashSet<>();

	// What the top-level form being lowered hoists ahead of itself: the defstruct and
	// modifier defuns of the internal record types it holds.
	private final List<LispVal> hoisted = new ArrayList<>();

	// The mangled name of the top-level definition being lowered, "" for any other form:
	// what an internal record type's name is qualified by.
	private String enclosing = "";

	// Created by the first file or buffer that defines a macro; a session keeps it, and
	// with it the macros of earlier buffers.
	private @Nullable SchemeExpander expander;

	private SchemeLowering(SchemeReader reader, boolean interactive, SchemeStandard standard,
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
		lowering.imports(0);
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
		this.datums = buffer.readAll();
		// Temporaries are recognized by identity while their datum is lowered; the
		// COUNTER is what must outlive the buffer, a record's generated slot being
		// global.
		this.generated.clear();
		this.internalRecords.clear();
		// A library typed at a prompt is declared, not lowered: a later import lowers it.
		List<LispVal> program = new ArrayList<>();
		for (LispVal datum : this.datums) {
			if (isLibraryDefinition(datum)) {
				declareLibrary((LispCons) datum, buffer, null);
			}
			else {
				program.add(datum);
			}
		}
		List<LispVal> forms = new ArrayList<>();
		for (LispVal datum : expanded(includes(program, null))) {
			spliceBegins(datum, forms);
		}
		for (LispVal form : forms) {
			collectAssigned(form);
			// At a prompt every import is a leading one, and it only ever ADDS names.
			if (form instanceof LispCons cons && syntaxOf(cons, this.global) == Core.IMPORT) {
				for (LispVal set : elements(cons.cdr(), cons)) {
					importSet(set, cons)
						.forEach((name, binding) -> this.global.bindings.put(SchemeNames.mangle(name), binding));
				}
			}
		}
		declareGlobals(forms);
		List<SchemeTopLevel> out = new ArrayList<>();
		if (!this.falseBound) {
			out.add(new SchemeTopLevel(List.of(falseBinding()), false));
		}
		List<LispVal> libraryForms = this.libraries.drain();
		if (!libraryForms.isEmpty()) {
			out.add(new SchemeTopLevel(libraryForms, false));
		}
		for (LispVal form : forms) {
			List<LispVal> lowered = new ArrayList<>();
			boolean echoes = topLevel(form, lowered);
			out.add(new SchemeTopLevel(List.copyOf(exitGuardEntry(lowered)), echoes));
		}
		// Only now: a buffer that failed to lower evaluated nothing, the binding
		// included.
		this.falseBound = true;
		return out;
	}

	/**
	 * Lowers the whole file.
	 * @return the Common Lisp top-level forms
	 */
	List<LispVal> lower() {
		this.datums = this.reader.readAll();
		List<LispVal> forms = new ArrayList<>();
		int start = imports(declareLibraries());
		for (LispVal datum : expanded(includes(this.datums.subList(start, this.datums.size()), this.reader.file()))) {
			spliceBegins(datum, forms);
		}
		for (LispVal form : forms) {
			collectAssigned(form);
		}
		declareGlobals(forms);
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
			topLevel(form, out);
		}
		if (mayThrowExit(forms)) {
			for (int i = bodyFrom; i < out.size(); i++) {
				out.set(i, exitGuard(out.get(i)));
			}
		}
		return out;
	}

	// The datums with every macro expanded, when the program defines any: a program that
	// spells no syntax definition is lowered exactly as before.
	private List<LispVal> expanded(List<LispVal> datums) {
		if (this.expander == null && !SchemeExpander.needed(datums, this::globalKeyword)) {
			return datums;
		}
		if (this.expander == null) {
			this.expander = new SchemeExpander(new ExpanderHost());
		}
		return this.expander.topLevel(datums);
	}

	private @Nullable Core globalKeyword(LispSymbol identifier) {
		return this.global.find(SchemeNames.mangle(identifier.name())) instanceof Syntax syntax ? syntax.core() : null;
	}

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
			if (rewritten instanceof LispCons cons) {
				SchemeLowering.this.reader.inherit(original, cons);
			}
			return SchemeLowering.inherit(original, rewritten);
		}

		@Override
		public void checkTopLevelDefinition(LispSymbol identifier, LispCons form) {
			refuseRedefiningAnImport(identifier, form);
		}

	}

	private LispVal falseBinding() {
		return list(symbol("SETQ"), this.falseVariable, list(symbol("QUOTE"), symbol("#f")), this.unspecifiedVariable,
				list(symbol("QUOTE"), symbol(SchemeNames.UNSPECIFIED_NAME)));
	}

	// Whether any top-level datum can reach the throwing exit: it spells exit -- as a
	// call, a first-class value or quoted data an eval may take apart -- or a string
	// one may read it out of (the same line the run-time procedure table draws), or it
	// spells eval, whose run-time data may name exit. Anything else cannot throw to
	// the tag, so it is emitted exactly as before and a program that never quits
	// compiles to the same bytes.
	private static boolean mayThrowExit(List<LispVal> forms) {
		for (LispVal form : forms) {
			if (spellsExitOrEval(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean spellsExitOrEval(LispVal form) {
		return switch (form) {
			case LispSymbol symbol -> symbol.name().equals("exit") || symbol.name().equals("eval");
			case LispString string -> string.value().contains("exit");
			case LispCons cons -> spellsExitOrEval(cons.car()) || spellsExitOrEval(cons.cdr());
			case LispArray array -> {
				for (LispVal element : array.data()) {
					if (spellsExitOrEval(element)) {
						yield true;
					}
				}
				yield false;
			}
			case null, default -> false;
		};
	}

	private LispVal quotedExitTag() {
		return list(symbol("QUOTE"), symbol(EXIT_TAG_NAME));
	}

	// Wraps one file top-level form -- a STATEMENT nobody reads the value of -- so an
	// exit inside it unwinds through the outstanding dynamic-wind afters to this
	// catch, which ends the process through %scheme-exit with the thrown code. A defun
	// or defstruct stays bare: the backends only hoist one that is a direct child of
	// the program, and defining never throws -- a body only runs inside some value
	// form's extent. The fresh cell tells a throw from normal completion, whatever the
	// code is: (exit '()) throws NIL, which a literal marker could not tell apart.
	private LispVal exitGuard(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& ("DEFUN".equals(head.name()) || "DEFSTRUCT".equals(head.name()))) {
			return form;
		}
		LispSymbol done = fresh("EXIT-DONE");
		LispSymbol code = fresh("EXIT-CODE");
		LispVal caught = list(symbol("CATCH"), quotedExitTag(), list(symbol("PROGN"), form, done));
		LispVal guarded = list(symbol("LET"), listOf(List.of(list(done, list(symbol("LIST"), LispNil.INSTANCE)))),
				list(symbol("LET"), listOf(List.of(list(code, caught))), list(symbol("IF"),
						list(symbol("EQ"), code, done), LispNil.INSTANCE, list(symbol(EXIT_FUNCTION_NAME), code))));
		return form instanceof LispCons lowered ? inherit(lowered, guarded) : guarded;
	}

	// Wraps one session entry the same way. A session has no whole file to wrap and no
	// artifact to keep small, so every entry is wrapped unconditionally -- including a
	// definition, whose value may throw -- while a defun or defstruct stays bare like
	// in a file. Every form but the last takes the statement guard (the session
	// discards their values); the last answers the entry's value, which is what the
	// prompt echoes. A last form that is itself a syntactic multiple-value producer --
	// in lowered code only (VALUES ...) can stand there alone -- keeps its shape with
	// its ARGUMENTS guarded instead: the prompt echoes through evalValues, which takes
	// the multi-value path only for that shape, so (values) echoes nothing and
	// (values 1 'a) echoes both.
	private List<LispVal> exitGuardEntry(List<LispVal> forms) {
		if (forms.isEmpty()) {
			return forms;
		}
		List<LispVal> out = new ArrayList<>();
		for (int i = 0; i < forms.size() - 1; i++) {
			out.add(exitGuard(forms.get(i)));
		}
		LispVal last = forms.get(forms.size() - 1);
		if (last instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& ("DEFUN".equals(head.name()) || "DEFSTRUCT".equals(head.name()))) {
			out.add(last);
		}
		else if (isMvProducer(last)) {
			out.add(guardProducerArgs((LispCons) last));
		}
		else {
			out.add(exitGuardValue(last));
		}
		return List.copyOf(out);
	}

	// The session's value guard: like the file's, but answers the form's VALUES -- all
	// of them, held as a list while the catch and the exit test run, because a value
	// that went through a variable and an (eq ...) is one value in Common Lisp
	// (.kb/multiple-values.md): (values 1 2) from a procedure echoes both lines, a
	// (values) echoes none.
	private LispVal exitGuardValue(LispVal form) {
		LispSymbol done = fresh("EXIT-DONE");
		LispSymbol values = fresh("EXIT-VALUES");
		LispSymbol code = fresh("EXIT-CODE");
		LispVal caught = list(symbol("CATCH"), quotedExitTag(),
				list(symbol("PROGN"), list(symbol("SETQ"), values, list(symbol("MULTIPLE-VALUE-LIST"), form)), done));
		LispVal guarded = list(symbol("LET"),
				listOf(List.of(list(done, list(symbol("LIST"), LispNil.INSTANCE)), list(values, LispNil.INSTANCE))),
				list(symbol("LET"), listOf(List.of(list(code, caught))),
						list(symbol("IF"), list(symbol("EQ"), code, done), list(symbol("VALUES-LIST"), values),
								list(symbol(EXIT_FUNCTION_NAME), code))));
		return form instanceof LispCons lowered ? inherit(lowered, guarded) : guarded;
	}

	// Guards the arguments of a syntactic multiple-value producer in place, keeping
	// the producer's shape (and a zero-argument (VALUES) bare). Mirrors
	// LispMacroExpander's producer recognition, which the scheme package may not
	// import; in lowered code a producer head is always the real operator -- a user
	// binding of values lowers to a distinct lowercase symbol.
	private LispVal guardProducerArgs(LispCons form) {
		if (!form.isProperList()) {
			return exitGuardValue(form);
		}
		List<LispVal> parts = form.toList();
		List<LispVal> guarded = new ArrayList<>();
		guarded.add(parts.get(0));
		for (int i = 1; i < parts.size(); i++) {
			guarded.add(exitGuardValue(parts.get(i)));
		}
		return inherit(form, listOf(guarded));
	}

	private static boolean isMvProducer(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return false;
		}
		int size = cons.toList().size();
		return switch (op.name()) {
			case "VALUES" -> true;
			case "FLOOR", "CEILING", "ROUND", "TRUNCATE", "FFLOOR", "FCEILING", "FROUND", "FTRUNCATE" ->
				size == 2 || size == 3;
			case "GETHASH" -> size == 3 || size == 4;
			case "ARRAY-DISPLACEMENT" -> size == 2;
			case "SUBTYPEP" -> size == 3;
			case "FIND-SYMBOL", "INTERN" -> size == 2 || size == 3;
			case "READ-FROM-STRING" -> size == 2;
			default -> false;
		};
	}

	// ------------------------------------------------------------------ imports

	/** The R7RS libraries {@code (import (scheme <name>))} accepts. */
	private static final List<String> IMPORTABLE_LIBRARIES = List.of("base", "write", "read", "char", "inexact", "cxr",
			"lazy", "case-lambda", "process-context", "eval", "repl");

	/**
	 * {@code (defun rontolisp::%scheme-library-p (name) ...)}: whether
	 * {@code (scheme name)} is one of {@link #IMPORTABLE_LIBRARIES}, for what a run-time
	 * {@code (environment '(scheme base))} checks its import sets against. Generated so
	 * the list is spelled once.
	 * @return the definition, in the library's canonical shape
	 */
	static LispVal libraryPredicateForm() {
		List<LispVal> names = new ArrayList<>();
		for (String library : IMPORTABLE_LIBRARIES) {
			names.add(symbol(library));
		}
		LispSymbol name = symbol("NAME");
		return list(symbol("DEFUN"), symbol("RONTOLISP::%SCHEME-LIBRARY-P"), list(name),
				list(symbol("IF"), list(symbol("MEMBER"), name, list(symbol("QUOTE"), listOf(names))),
						LispTrue.INSTANCE, LispNil.INSTANCE));
	}

	/**
	 * {@code (defun rontolisp::%scheme-eval-extension-keyword-p (name) ...)}: whether
	 * {@code name} is a keyword {@code eval} knows beyond R7RS -- the {@code sicp} syntax
	 * ({@code cons-stream}), none under {@link SchemeStandard#R7RS}. Generated from the
	 * same table the lowering reads, so the list is spelled once.
	 * @param standard the standard the program is read against
	 * @return the definition, in the library's canonical shape
	 */
	static LispVal extensionKeywordForm(SchemeStandard standard) {
		List<LispVal> names = new ArrayList<>();
		if (standard == SchemeStandard.RONTOLISP) {
			for (String keyword : SICP_SYNTAX.keySet()) {
				names.add(symbol(SchemeNames.mangle(keyword)));
			}
		}
		LispSymbol name = symbol("NAME");
		return list(symbol("DEFUN"), symbol("RONTOLISP::%SCHEME-EVAL-EXTENSION-KEYWORD-P"), list(name),
				list(symbol("IF"), list(symbol("MEMBER"), name, list(symbol("QUOTE"), listOf(names))),
						LispTrue.INSTANCE, LispNil.INSTANCE));
	}

	// Leading (import ...) forms -- after the leading define-library forms, at `start` --
	// pick what the global scope holds; a program with none sees everything, like a REPL.
	private int imports(int start) {
		int index = start;
		Map<String, Binding> imported = new LinkedHashMap<>();
		while (index < this.datums.size() && this.datums.get(index) instanceof LispCons form
				&& form.car() instanceof LispSymbol head && head.name().equals("import")) {
			for (LispVal set : elements(form.cdr(), form)) {
				imported.putAll(importSet(set, form));
			}
			index++;
		}
		if (index == start) {
			// R7RS 5.1: a program begins with an import declaration. A session has no
			// program to begin, and starts with every library instead; a file of
			// libraries alone has no program.
			if (this.standard == SchemeStandard.R7RS && !this.interactive
					&& (start == 0 || start < this.datums.size())) {
				SourceLocation first = start < this.datums.size() ? this.reader.locate(this.datums.get(start)) : null;
				throw new LispReadException("an R7RS program begins with an import declaration",
						first != null ? first : this.reader.locateFirstDatum());
			}
			imported.putAll(everything());
		}
		for (Map.Entry<String, Binding> entry : imported.entrySet()) {
			this.global.bindings.put(SchemeNames.mangle(entry.getKey()), entry.getValue());
		}
		return index;
	}

	// What a program, a library or a session with no import declaration sees.
	private Map<String, Binding> everything() {
		Map<String, Binding> imported = new LinkedHashMap<>();
		for (String library : IMPORTABLE_LIBRARIES) {
			imported.putAll(library(library));
		}
		// Not R7RS exports, so not reachable by name through (import ...): a REPL, and a
		// file with no import at all, sees them anyway, the way an unqualified SICP
		// sample -- written against an implementation that already had them -- expects.
		// r5rs is the same shape: (scheme r5rs) would promise all of R5RS. Strict R7RS
		// sees neither.
		if (this.standard == SchemeStandard.RONTOLISP) {
			imported.putAll(library("sicp"));
			imported.putAll(library("r5rs"));
		}
		return imported;
	}

	private Map<String, Binding> importSet(LispVal set, LispCons form) {
		List<LispVal> parts = elements(set, form);
		if (parts.isEmpty() || !(parts.get(0) instanceof LispSymbol head)) {
			throw error("malformed import set", form);
		}
		boolean modifier = parts.size() >= 2 && parts.get(1) instanceof LispCons;
		if (!modifier) {
			if (parts.size() == 2 && head.name().equals("scheme") && parts.get(1) instanceof LispSymbol name
					&& IMPORTABLE_LIBRARIES.contains(name.name())) {
				return library(name.name());
			}
			if (!head.name().equals("scheme")) {
				return userLibrary(libraryName(set, form), form);
			}
			List<String> names = IMPORTABLE_LIBRARIES.stream().map(library -> "(scheme " + library + ")").toList();
			throw error("library " + set.print() + " is not available: this experimental front end has "
					+ String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast() + " only",
					form);
		}
		Map<String, Binding> base = importSet(parts.get(1), form);
		Map<String, Binding> result = new LinkedHashMap<>();
		List<LispVal> arguments = parts.subList(2, parts.size());
		switch (head.name()) {
			case "only" -> {
				for (LispVal argument : arguments) {
					String name = identifier(argument, form).name();
					result.put(name, imported(base, name, form));
				}
			}
			case "except" -> {
				result.putAll(base);
				for (LispVal argument : arguments) {
					String name = identifier(argument, form).name();
					imported(base, name, form);
					result.remove(name);
				}
			}
			case "prefix" -> {
				String prefix = identifier(arguments.size() == 1 ? arguments.get(0) : LispNil.INSTANCE, form).name();
				base.forEach((name, binding) -> result.put(prefix + name, binding));
			}
			case "rename" -> {
				result.putAll(base);
				for (LispVal argument : arguments) {
					List<LispVal> pair = elements(argument, form);
					if (pair.size() != 2) {
						throw error("malformed rename", form);
					}
					String from = identifier(pair.get(0), form).name();
					Binding binding = imported(base, from, form);
					result.remove(from);
					result.put(identifier(pair.get(1), form).name(), binding);
				}
			}
			default -> throw error("unknown import set: " + head.name(), form);
		}
		return result;
	}

	private Binding imported(Map<String, Binding> base, String name, LispCons form) {
		Binding binding = base.get(name);
		if (binding == null) {
			throw error("the library does not export " + name, form);
		}
		return binding;
	}

	private Map<String, Binding> library(String library) {
		Map<String, Binding> exports = new LinkedHashMap<>();
		if (library.equals("base")) {
			SYNTAX.forEach((name, core) -> exports.put(name, new Syntax(core, name)));
		}
		if (library.equals("lazy")) {
			LAZY_SYNTAX.forEach((name, core) -> exports.put(name, new Syntax(core, name)));
		}
		if (library.equals("case-lambda")) {
			CASE_LAMBDA_SYNTAX.forEach((name, core) -> exports.put(name, new Syntax(core, name)));
		}
		if (library.equals("sicp")) {
			SICP_SYNTAX.forEach((name, core) -> exports.put(name, new Syntax(core, name)));
			SchemeBuiltins.constants().forEach((name, form) -> exports.put(name, new Constant(form)));
		}
		for (SchemeBuiltins.Entry entry : SchemeBuiltins.entries(this.standard).values()) {
			if (entry.library().equals(library)) {
				exports.put(entry.name(), new Builtin(entry));
			}
		}
		return exports;
	}

	// ------------------------------------------------------------------ libraries

	private static boolean isLibraryDefinition(LispVal datum) {
		return datum instanceof LispCons form && form.car() instanceof LispSymbol head
				&& head.name().equals("define-library");
	}

	// The leading define-library forms of a file: declared here, lowered when imported.
	private int declareLibraries() {
		int index = 0;
		while (index < this.datums.size() && isLibraryDefinition(this.datums.get(index))) {
			declareLibrary((LispCons) this.datums.get(index), this.reader, this.reader.file());
			index++;
		}
		return index;
	}

	private void declareLibrary(LispCons form, SchemeReader reader, @Nullable String file) {
		List<String> name = libraryName(second(form), form);
		if (name.getFirst().equals("scheme")) {
			throw error("library names beginning with scheme are reserved: " + printed(name), form);
		}
		if (!this.libraries.declare(name, new SchemeLibraries.Declaration(form, reader, file))) {
			throw error("library " + printed(name) + " is defined twice", form);
		}
	}

	// A library name, R7RS 5.6.1: identifiers and exact non-negative integers.
	private List<String> libraryName(LispVal datum, LispCons form) {
		List<String> name = new ArrayList<>();
		LispVal rest = datum;
		while (rest instanceof LispCons cell) {
			switch (cell.car()) {
				case LispSymbol part when !part.equals(SchemeReader.TRUE) && !part.equals(SchemeReader.FALSE) ->
					name.add(part.name());
				case LispInteger integer when integer.value() >= 0 -> name.add(Long.toString(integer.value()));
				default -> throw error("malformed library name: " + SchemeExpander.written(datum), form);
			}
			rest = cell.cdr();
		}
		if (name.isEmpty() || rest != LispNil.INSTANCE) {
			throw error("malformed library name: " + SchemeExpander.written(datum), form);
		}
		return name;
	}

	private static String printed(List<String> name) {
		return "(" + String.join(" ", name) + ")";
	}

	// An import of a user library: lowered the first time, its exports every time.
	private Map<String, Binding> userLibrary(List<String> name, LispCons form) {
		Map<String, Binding> exports = this.libraries.exports(name);
		if (exports == null) {
			exports = instantiate(name, form);
		}
		for (Binding binding : exports.values()) {
			if (binding instanceof Variable || binding instanceof GlobalFunction
					|| binding instanceof GlobalPredicate) {
				this.libraryImports.add(binding);
			}
		}
		return exports;
	}

	private Map<String, Binding> instantiate(List<String> name, LispCons form) {
		SchemeLibraries.Declaration declaration = this.libraries.declared(name);
		if (declaration == null) {
			declaration = libraryFile(name, form);
		}
		List<List<String>> cycle = this.libraries.enter(name);
		if (cycle != null) {
			throw error("library import cycle: "
					+ String.join(" -> ", cycle.stream().map(SchemeLowering::printed).toList()), form);
		}
		boolean done = false;
		try {
			SchemeLowering library = new SchemeLowering(declaration.reader(), false, this.standard, this.libraries,
					SchemeNames.libraryPrefix(name));
			Map<String, Binding> exports = library.lowerLibrary(declaration);
			this.libraries.leave(name, exports, library.libraryForms);
			done = true;
			return exports;
		}
		finally {
			if (!done) {
				this.libraries.abandon(name);
			}
		}
	}

	// (a b) is a/b.sld (else a/b.scm) beside the file the lowering started from, the way
	// Gauche finds it on its load path; every define-library in that file is declared.
	private SchemeLibraries.Declaration libraryFile(List<String> name, LispCons form) {
		String stem = String.join("/", name);
		for (String extension : List.of(".sld", ".scm")) {
			SchemeFiles.Source source = this.libraries.files().find(this.libraries.root(), stem + extension);
			if (source == null) {
				continue;
			}
			SchemeReader reader = new SchemeReader(source.text(), source.path());
			for (LispVal datum : reader.readAll()) {
				if (isLibraryDefinition(datum)) {
					LispCons definition = (LispCons) datum;
					this.libraries.declare(libraryName(second(definition), definition),
							new SchemeLibraries.Declaration(definition, reader, source.path()));
				}
			}
			SchemeLibraries.Declaration declaration = this.libraries.declared(name);
			if (declaration == null) {
				throw error(source.path() + " does not define library " + printed(name), form);
			}
			return declaration;
		}
		throw error("library " + printed(name) + " is not available: no define-library of it precedes the program"
				+ " and there is no " + stem + ".sld", form);
	}

	// The lowered forms of a library: set by lowerLibrary.
	private List<LispVal> libraryForms = List.of();

	/**
	 * Lowers a library's body as a whole file of its own: its imports are its scope, its
	 * top-level names are private ({@link SchemeNames#libraryPrefix}), and what it
	 * exports reaches an importer as the bindings themselves -- a {@code defun} stays a
	 * direct call there.
	 */
	private Map<String, Binding> lowerLibrary(SchemeLibraries.Declaration declaration) {
		List<LispCons> importForms = new ArrayList<>();
		List<LispCons> exportForms = new ArrayList<>();
		List<Chunk> chunks = new ArrayList<>();
		LispCons form = declaration.form();
		List<LispVal> parts = elements(form, form);
		libraryDeclarations(parts.subList(2, parts.size()), declaration.file(), form,
				new Declarations(importForms, exportForms, chunks), new java.util.ArrayDeque<>());
		Map<String, Binding> imported = new LinkedHashMap<>();
		for (LispCons importForm : importForms) {
			for (LispVal set : elements(importForm.cdr(), importForm)) {
				imported.putAll(importSet(set, importForm));
			}
		}
		if (importForms.isEmpty()) {
			if (this.standard == SchemeStandard.R7RS && !chunks.isEmpty()) {
				throw error("a library that imports nothing binds nothing, not even define:"
						+ " add (import (scheme base))", form);
			}
			imported.putAll(everything());
		}
		for (Map.Entry<String, Binding> entry : imported.entrySet()) {
			this.global.bindings.put(SchemeNames.mangle(entry.getKey()), entry.getValue());
		}
		List<LispVal> body = new ArrayList<>();
		for (Chunk chunk : chunks) {
			body.addAll(includes(chunk.datums(), chunk.file()));
		}
		List<LispVal> forms = new ArrayList<>();
		for (LispVal datum : expanded(body)) {
			spliceBegins(datum, forms);
		}
		for (LispVal datum : forms) {
			collectAssigned(datum);
		}
		declareGlobals(forms);
		List<LispVal> out = new ArrayList<>();
		for (LispVal datum : forms) {
			topLevel(datum, out);
		}
		if (mayThrowExit(forms)) {
			out.replaceAll(this::exitGuard);
		}
		this.libraryForms = instantiationGuarded(out);
		return exports(exportForms);
	}

	/**
	 * Body datums and the file they were read from, what an {@code include} in them is
	 * relative to.
	 *
	 * @param datums the datums
	 * @param file the file, or {@code null} for a session buffer
	 */
	private record Chunk(List<LispVal> datums, @Nullable String file) {
	}

	/**
	 * What a library's declarations collect, in order.
	 *
	 * @param imports the {@code import} declarations
	 * @param exports the {@code export} declarations
	 * @param body the {@code begin} and {@code include} bodies
	 */
	private record Declarations(List<LispCons> imports, List<LispCons> exports, List<Chunk> body) {
	}

	private void libraryDeclarations(List<LispVal> declarations, @Nullable String file, LispCons library,
			Declarations out, java.util.Deque<String> reading) {
		for (LispVal datum : declarations) {
			if (!(datum instanceof LispCons declaration) || !(declaration.car() instanceof LispSymbol head)) {
				throw error("malformed library declaration: " + SchemeExpander.written(datum), library);
			}
			switch (head.name()) {
				case "export" -> out.exports().add(declaration);
				case "import" -> out.imports().add(declaration);
				case "begin" -> out.body().add(new Chunk(elements(declaration.cdr(), declaration), file));
				case "include", "include-ci" -> {
					for (Included included : readIncluded(declaration, file, head.name().equals("include-ci"),
							reading)) {
						out.body().add(new Chunk(included.datums(), included.file()));
					}
				}
				case "include-library-declarations" -> {
					for (Included included : readIncluded(declaration, file, false, reading)) {
						reading.push(included.file());
						libraryDeclarations(included.datums(), included.file(), library, out, reading);
						reading.pop();
					}
				}
				case "cond-expand" ->
					throw error("cond-expand is not supported by this experimental front end yet", declaration);
				default -> throw error("unknown library declaration: " + head.name(), declaration);
			}
		}
	}

	// The exports: (export id (rename internal external) ...), each resolved in the
	// library's own scope after its body is lowered.
	private Map<String, Binding> exports(List<LispCons> exportForms) {
		Map<String, Binding> exports = new LinkedHashMap<>();
		for (LispCons exportForm : exportForms) {
			for (LispVal spec : elements(exportForm.cdr(), exportForm)) {
				LispSymbol internal;
				LispSymbol external;
				if (spec instanceof LispCons rename && rename.car() instanceof LispSymbol head
						&& head.name().equals("rename")) {
					List<LispVal> pair = elements(rename.cdr(), exportForm);
					if (pair.size() != 2) {
						throw error("malformed export rename: " + SchemeExpander.written(spec), exportForm);
					}
					internal = identifier(pair.get(0), exportForm);
					external = identifier(pair.get(1), exportForm);
				}
				else {
					internal = identifier(spec, exportForm);
					external = internal;
				}
				Binding binding = this.global.find(name(internal));
				if (binding == null) {
					if (this.expander != null && this.expander.definesSyntax(internal.name())) {
						throw error("exporting syntax from a library is not supported by this experimental front end"
								+ " yet: " + internal.name(), exportForm);
					}
					throw error("the library exports " + internal.name() + ", which it neither defines nor imports",
							exportForm);
				}
				if (exports.put(external.name(), binding) != null) {
					throw error("the library exports " + external.name() + " twice", exportForm);
				}
			}
		}
		return exports;
	}

	// A library runs once per program, however many separately lowered files import it
	// (R7RS 5.6.1): its definitions are idempotent and stay top-level forms (the
	// backends hoist a defun or defstruct only as a direct child of the program), its
	// statements run behind a flag the first instantiation sets.
	private List<LispVal> instantiationGuarded(List<LispVal> forms) {
		List<LispVal> definitions = new ArrayList<>();
		List<LispVal> statements = new ArrayList<>();
		if (!this.initialValues.isEmpty()) {
			List<LispVal> assignment = new ArrayList<>(List.of(symbol("SETQ")));
			this.initialValues.forEach((variable, value) -> {
				assignment.add(variable);
				assignment.add(value);
			});
			statements.add(listOf(assignment));
		}
		for (LispVal form : forms) {
			boolean definition = form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& ("DEFUN".equals(head.name()) || "DEFSTRUCT".equals(head.name()));
			(definition ? definitions : statements).add(form);
		}
		if (statements.isEmpty()) {
			return List.copyOf(definitions);
		}
		LispSymbol flag = symbol(this.prefix + "%SCM-INSTANTIATED");
		List<LispVal> guarded = new ArrayList<>();
		guarded.add(list(symbol("DEFVAR"), flag, LispNil.INSTANCE));
		guarded.addAll(definitions);
		List<LispVal> body = new ArrayList<>(List.of(symbol("PROGN"), list(symbol("SETQ"), flag, LispTrue.INSTANCE)));
		body.addAll(statements);
		guarded.add(list(symbol("IF"), flag, LispNil.INSTANCE, listOf(body)));
		return List.copyOf(guarded);
	}

	/**
	 * A file an {@code include} read.
	 *
	 * @param datums the file's datums
	 * @param file the resolved path
	 */
	private record Included(List<LispVal> datums, String file) {
	}

	private List<Included> readIncluded(LispCons form, @Nullable String from, boolean foldCase,
			java.util.Deque<String> reading) {
		List<LispVal> names = elements(form.cdr(), form);
		if (names.isEmpty()) {
			throw error("include needs a file name", form);
		}
		List<Included> files = new ArrayList<>();
		for (LispVal name : names) {
			if (!(name instanceof LispString path)) {
				throw error("include takes file names as strings, got " + SchemeExpander.written(name), form);
			}
			SchemeFiles.Source source = this.libraries.files().find(from, path.value());
			if (source == null) {
				throw error("include: cannot read " + path.value(), form);
			}
			if (reading.contains(source.path())) {
				throw error("include: " + path.value() + " includes itself", form);
			}
			files.add(new Included(this.reader.other(source.text(), source.path(), foldCase).readAll(), source.path()));
		}
		return files;
	}

	// Splices every (include "file" ...) the datums spell as a (begin datums...) of the
	// files' contents, recursively, BEFORE macros are expanded -- so an included
	// definition or syntax definition is seen by every pre-scan. Quoted data is left
	// alone. Datums that include nothing are returned as they are, the same objects.
	private List<LispVal> includes(List<LispVal> datums, @Nullable String file) {
		List<LispVal> out = new ArrayList<>(datums.size());
		boolean changed = false;
		for (LispVal datum : datums) {
			LispVal resolved = included(datum, file, new java.util.ArrayDeque<>());
			changed |= resolved != datum;
			out.add(resolved);
		}
		return changed ? out : datums;
	}

	private LispVal included(LispVal datum, @Nullable String file, java.util.Deque<String> reading) {
		if (!(datum instanceof LispCons form)) {
			return datum;
		}
		Core core = syntaxOf(form, this.global);
		if (core == Core.QUOTE || core == Core.QUASIQUOTE) {
			return datum;
		}
		if (core == Core.INCLUDE || core == Core.INCLUDE_CI) {
			List<LispVal> spliced = new ArrayList<>();
			for (Included included : readIncluded(form, file, core == Core.INCLUDE_CI, reading)) {
				reading.push(included.file());
				for (LispVal inner : included.datums()) {
					spliced.add(included(inner, included.file(), reading));
				}
				reading.pop();
			}
			LispCons begin = new LispCons(CORE_BEGIN, listOf(spliced));
			this.reader.inherit(form, begin);
			return inherit(form, begin);
		}
		List<LispVal> elements = new ArrayList<>();
		boolean changed = false;
		LispVal rest = form;
		while (rest instanceof LispCons cell) {
			LispVal element = included(cell.car(), file, reading);
			changed |= element != cell.car();
			elements.add(element);
			rest = cell.cdr();
		}
		if (!changed) {
			return datum;
		}
		LispVal rebuilt = rest;
		for (int i = elements.size() - 1; i >= 0; i--) {
			rebuilt = new LispCons(elements.get(i), rebuilt);
		}
		LispCons head = (LispCons) rebuilt;
		this.reader.inherit(form, head);
		return inherit(form, head);
	}

	// ------------------------------------------------------------------ top level

	private void spliceBegins(LispVal datum, List<LispVal> out) {
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
	private void collectAssigned(LispVal datum) {
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

	private void declareGlobals(List<LispVal> forms) {
		Map<String, Integer> definitions = new HashMap<>();
		Map<String, LispSymbol> procedures = new LinkedHashMap<>();
		Map<String, LispSymbol> variables = new LinkedHashMap<>();
		List<LispCons> records = new ArrayList<>();
		for (LispVal datum : forms) {
			if (!(datum instanceof LispCons form)) {
				continue;
			}
			Core core = syntaxOf(form, this.global);
			if (core == Core.DEFINE) {
				Definition definition = definition(form);
				String name = name(definition.name());
				refuseARecordProcedure(definition.name(), form);
				refuseRedefiningAnImport(definition.name(), form);
				definitions.merge(name, 1, Integer::sum);
				(definition.procedure() ? procedures : variables).putIfAbsent(name, definition.name());
			}
			else if (core == Core.DEFINE_VALUES) {
				for (LispSymbol variable : formals(second(form), form).all()) {
					refuseARecordProcedure(variable, form);
					refuseRedefiningAnImport(variable, form);
					definitions.merge(name(variable), 2, Integer::sum);
					variables.putIfAbsent(name(variable), variable);
				}
			}
			else if (core == Core.DEFINE_RECORD_TYPE) {
				RecordType type = recordType(form);
				refuseRedefiningAnImport(type.name(), form);
				for (LispSymbol procedure : recordProcedures(type)) {
					refuseRedefiningAnImport(procedure, form);
				}
				records.add(form);
			}
		}
		Set<String> readEarly = this.interactive ? Set.of() : readBeforeDefinition(forms);
		variables.forEach((name, identifier) -> this.global.bindings.put(name, new Variable(global(identifier))));
		procedures.forEach((name, identifier) -> {
			boolean direct = !this.interactive && definitions.getOrDefault(name, 0) == 1
					&& !this.assignedNames.contains(name) && !variables.containsKey(name) && !readEarly.contains(name);
			this.global.bindings.put(name,
					direct ? new GlobalFunction(global(identifier)) : new Variable(global(identifier)));
		});
		for (LispCons record : records) {
			declareRecord(record, definitions);
		}
	}

	/**
	 * The names this file defines over an imported binding -- a builtin, a SICP constant
	 * -- that a form may READ before the first definition. Those become variables holding
	 * the imported value until then ({@link #initialValues}): a {@code defun} is
	 * position-blind, so the interpreter has no function yet and the compile path hoists
	 * the user's. A read counts when it is in a form run at the top level (anything but a
	 * procedure definition and a record type), directly or through what anything defined
	 * before that form mentions. Scope-blind, like {@link #collectAssigned}:
	 * over-approximating only costs the direct call.
	 */
	private Set<String> readBeforeDefinition(List<LispVal> forms) {
		Map<String, Integer> firstDefinition = new HashMap<>();
		List<List<String>> defined = new ArrayList<>();
		List<Boolean> run = new ArrayList<>();
		// What each form mentions: a definition's value or body, never its own target.
		List<Set<String>> mentioned = new ArrayList<>();
		for (int i = 0; i < forms.size(); i++) {
			List<String> names = new ArrayList<>();
			Set<String> referenced = new HashSet<>();
			boolean runs = true;
			if (forms.get(i) instanceof LispCons form && syntaxOf(form, this.global) == Core.DEFINE) {
				Definition definition = definition(form);
				names.add(name(definition.name()));
				definition.body().forEach(expression -> collectNames(expression, referenced));
				runs = !definition.procedure();
			}
			else if (forms.get(i) instanceof LispCons form && syntaxOf(form, this.global) == Core.DEFINE_VALUES) {
				formals(second(form), form).all().forEach(variable -> names.add(name(variable)));
				collectNames(form.cdr() instanceof LispCons rest ? rest.cdr() : LispNil.INSTANCE, referenced);
			}
			else if (forms.get(i) instanceof LispCons form && syntaxOf(form, this.global) == Core.DEFINE_RECORD_TYPE) {
				runs = false;
			}
			else {
				collectNames(forms.get(i), referenced);
			}
			mentioned.add(referenced);
			for (String name : names) {
				Binding imported = this.global.bindings.get(name);
				if (imported instanceof Builtin || imported instanceof Constant
						|| this.libraryImports.contains(imported)) {
					firstDefinition.putIfAbsent(name, i);
				}
			}
			defined.add(names);
			run.add(runs);
		}
		Set<String> early = new HashSet<>();
		if (firstDefinition.isEmpty()) {
			return early;
		}
		Map<String, Set<String>> mentions = new HashMap<>();
		for (int i = 0; i < forms.size(); i++) {
			Set<String> referenced = mentioned.get(i);
			if (run.get(i)) {
				List<String> pending = new ArrayList<>(referenced);
				Set<String> reached = new HashSet<>(referenced);
				while (!pending.isEmpty()) {
					String name = pending.removeLast();
					Integer definedAt = firstDefinition.get(name);
					if (definedAt != null && definedAt >= i) {
						early.add(name);
					}
					for (String next : mentions.getOrDefault(name, Set.of())) {
						if (reached.add(next)) {
							pending.add(next);
						}
					}
				}
			}
			for (String name : defined.get(i)) {
				mentions.computeIfAbsent(name, key -> new HashSet<>()).addAll(referenced);
			}
		}
		for (String name : early) {
			LispVal value = switch (this.global.bindings.get(name)) {
				case Builtin builtin -> builtin.entry().function();
				case Constant constant -> constant.form();
				case Variable variable -> variable.symbol();
				case GlobalFunction function -> list(symbol("FUNCTION"), function.symbol());
				case GlobalPredicate predicate -> predicateValue(predicate);
				case null, default -> throw new IllegalStateException("not an imported value: " + name);
			};
			this.initialValues.put(symbol(this.prefix + name), value);
		}
		return early;
	}

	private void collectNames(LispVal datum, Set<String> out) {
		switch (datum) {
			case LispSymbol identifier -> out.add(name(identifier));
			case LispCons cons -> {
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					collectNames(cell.car(), out);
					rest = cell.cdr();
				}
				collectNames(rest, out);
			}
			case LispArray array -> {
				for (LispVal element : array.data()) {
					collectNames(element, out);
				}
			}
			default -> {
			}
		}
	}

	// R7RS 5.6.1: in a program it is an error to redefine an imported binding. Strict
	// mode reports it where the default lets a user definition win; a session may
	// redefine, as an R7RS REPL does. Before declareGlobals overwrites anything, the
	// global scope holds exactly the imports.
	private void refuseRedefiningAnImport(LispSymbol identifier, LispCons form) {
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
	private void refuseARecordProcedure(LispSymbol identifier, LispCons form) {
		Binding known = this.global.bindings.get(name(identifier));
		if ((known instanceof GlobalFunction || known instanceof GlobalPredicate)
				&& !this.libraryImports.contains(known)) {
			throw error("cannot redefine " + identifier.name() + ", a record procedure", form);
		}
	}

	// Answers whether the datum has a value at all: a definition, an import and a record
	// type have none. What an expression answers is the value's to say -- an effect's is
	// the unspecified object, which a session does not echo.
	private boolean topLevel(LispVal datum, List<LispVal> out) {
		this.hoisted.clear();
		this.enclosing = "";
		if (datum instanceof LispCons form && syntaxOf(form, this.global) == Core.DEFINE
				&& form.cdr() instanceof LispCons rest) {
			// Malformed or not: definition() reports that, positioned, below.
			LispVal target = rest.car() instanceof LispCons signature ? signature.car() : rest.car();
			if (target instanceof LispSymbol name) {
				this.enclosing = name(name);
			}
		}
		List<LispVal> lowered = new ArrayList<>();
		boolean echoes = topLevelForm(datum, lowered);
		// The internal record types stand before the form that uses them: the
		// interpreter runs the forms in order.
		out.addAll(this.hoisted);
		out.addAll(lowered);
		this.hoisted.clear();
		return echoes;
	}

	private boolean topLevelForm(LispVal datum, List<LispVal> out) {
		if (datum instanceof LispCons form) {
			try {
				switch (syntaxOf(form, this.global)) {
					case DEFINE -> {
						out.add(inherit(form, topLevelDefine(form)));
						trampoline(definition(form).name(), out);
					}
					case DEFINE_VALUES -> {
						out.add(inherit(form, defineValues(form, this.global)));
						for (LispSymbol variable : formals(second(form), form).all()) {
							trampoline(variable, out);
						}
					}
					case DEFINE_RECORD_TYPE -> recordType(form, out);
					case IMPORT -> {
						if (!this.interactive) {
							throw error("import must come before everything else", form);
						}
					}
					case null, default -> {
						out.add(topLevelValue(form));
						return true;
					}
				}
				return false;
			}
			catch (LispReadException ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				throw SourceProvenance.noteFailure(form, ex);
			}
		}
		out.add(topLevelValue(datum));
		return true;
	}

	// A file never reads a top-level form's value; a session echoes it, and so needs the
	// unspecified object to know what not to show.
	private LispVal topLevelValue(LispVal datum) {
		return lower(datum, this.interactive ? Context.of(this.global) : Context.discarding(this.global));
	}

	// (defun f (&rest a) (apply f a)): what a call lowered BEFORE the session defined f
	// reaches. It reads the variable on every call, so it follows set! and redefinition.
	private void trampoline(LispSymbol identifier, List<LispVal> out) {
		if (this.interactive) {
			LispSymbol name = cl(identifier);
			LispSymbol arguments = fresh("A");
			out.add(list(symbol("DEFUN"), name, list(symbol("&REST"), arguments),
					list(symbol("APPLY"), name, arguments)));
		}
	}

	/**
	 * A parsed {@code define}.
	 *
	 * @param name the defined identifier
	 * @param formals the parameters when the value is a syntactic {@code lambda}, else
	 * {@code null}
	 * @param body the procedure body, or the single value expression
	 */
	private record Definition(LispSymbol name, @Nullable LispVal formals, List<LispVal> body) {

		boolean procedure() {
			return this.formals != null;
		}

	}

	private Definition definition(LispCons form) {
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
		if (value instanceof LispCons dispatch && syntaxOf(dispatch, this.global) == Core.CASE_LAMBDA) {
			// A procedure all the same: a defun when the file defines it once.
			value = caseLambda(dispatch);
		}
		if (value instanceof LispCons lambda && syntaxOf(lambda, this.global) == Core.LAMBDA
				&& lambda.cdr() instanceof LispCons rest && rest.cdr() instanceof LispCons) {
			return new Definition(name, rest.car(), elements(rest.cdr(), lambda));
		}
		return new Definition(name, null, List.of(value));
	}

	private LispVal topLevelDefine(LispCons form) {
		Definition definition = definition(form);
		Binding binding = this.global.find(name(definition.name()));
		if (binding instanceof GlobalFunction function && definition.formals() != null) {
			Lowered lowered = procedure(new ProcedureSpec(formals(definition.formals(), form), definition.body(),
					function, definition.name()), this.global);
			return new LispCons(symbol("DEFUN"),
					new LispCons(function.symbol(), new LispCons(lowered.lambdaList(), listOf(lowered.body()))));
		}
		if (!(binding instanceof Variable variable)) {
			throw error("cannot redefine " + definition.name().name() + ", a record procedure", form);
		}
		// A session's procedure keeps its self tail calls a loop, like the defun a file
		// would make of it, unless the session has assigned the name so far. A set! typed
		// LATER cannot reach back into it: only a saved old value would tell.
		Binding self = this.interactive && !this.assignedNames.contains(name(definition.name())) ? variable : null;
		return list(symbol("SETQ"), variable.symbol(),
				definedValue(definition, new DefinedIn(form, this.global, self)));
	}

	private record DefinedIn(LispCons form, Scope scope, @Nullable Binding self) {
	}

	private LispVal definedValue(Definition definition, DefinedIn where) {
		if (definition.formals() == null) {
			return value(definition.body().get(0), where.scope());
		}
		return lambda(new ProcedureSpec(formals(definition.formals(), where.form()), definition.body(), where.self(),
				definition.name()), where.scope());
	}

	private LispVal defineValues(LispCons form, Scope scope) {
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

	private void declareRecord(LispCons form, Map<String, Integer> definitions) {
		RecordType type = recordType(form);
		for (LispSymbol procedure : recordProcedures(type)) {
			String name = name(procedure);
			if (definitions.merge(name, 1, Integer::sum) != 1 || this.assignedNames.contains(name)) {
				throw error("a record procedure cannot be redefined or assigned: " + procedure.name(), form);
			}
			this.global.bindings.put(name, procedure == type.predicate() ? new GlobalPredicate(global(procedure))
					: new GlobalFunction(global(procedure)));
		}
	}

	private static List<LispSymbol> recordProcedures(RecordType type) {
		List<LispSymbol> procedures = new ArrayList<>(type.accessors().values());
		procedures.addAll(type.modifiers().values());
		if (type.constructor() != null) {
			procedures.add(type.constructor());
		}
		procedures.add(type.predicate());
		return procedures;
	}

	/**
	 * A parsed {@code define-record-type}.
	 *
	 * @param name the type name
	 * @param constructor the constructor name, or {@code null}
	 * @param constructorFields the fields the constructor takes, in order
	 * @param predicate the predicate name
	 * @param fields every field, in order
	 * @param accessors field to accessor name
	 * @param modifiers field to modifier name
	 */
	private record RecordType(LispSymbol name, @Nullable LispSymbol constructor, List<String> constructorFields,
			LispSymbol predicate, List<String> fields, SequencedMap<String, LispSymbol> accessors,
			SequencedMap<String, LispSymbol> modifiers) {
	}

	private RecordType recordType(LispCons form) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 4) {
			throw error("malformed define-record-type", form);
		}
		LispSymbol name = parts.get(1) instanceof LispCons named ? identifier(named.car(), form)
				: identifier(parts.get(1), form);
		List<String> fields = new ArrayList<>();
		SequencedMap<String, LispSymbol> accessors = new LinkedHashMap<>();
		SequencedMap<String, LispSymbol> modifiers = new LinkedHashMap<>();
		for (LispVal spec : parts.subList(4, parts.size())) {
			List<LispVal> field = spec instanceof LispSymbol ? List.of(spec) : elements(spec, form);
			if (field.isEmpty() || field.size() > 3) {
				throw error("malformed record field", form);
			}
			String fieldName = identifier(field.get(0), form).name();
			if (fields.contains(fieldName)) {
				throw error("duplicate record field: " + fieldName, form);
			}
			fields.add(fieldName);
			if (field.size() >= 2) {
				accessors.put(fieldName, identifier(field.get(1), form));
			}
			if (field.size() == 3) {
				modifiers.put(fieldName, identifier(field.get(2), form));
			}
		}
		LispSymbol constructor = null;
		List<String> constructorFields = new ArrayList<>();
		if (parts.get(2) instanceof LispCons spec) {
			List<LispVal> constructorSpec = elements(spec, form);
			constructor = identifier(constructorSpec.get(0), form);
			for (LispVal field : constructorSpec.subList(1, constructorSpec.size())) {
				String fieldName = identifier(field, form).name();
				if (!fields.contains(fieldName)) {
					throw error("the constructor names an unknown field: " + fieldName, form);
				}
				constructorFields.add(fieldName);
			}
		}
		else if (parts.get(2) instanceof LispSymbol bare && !bare.equals(SchemeReader.FALSE)) {
			constructor = bare;
			constructorFields.addAll(fields);
		}
		return new RecordType(name, constructor, constructorFields, identifier(parts.get(3), form), fields, accessors,
				modifiers);
	}

	// A record type is a defstruct: that is what registers the instance layout on every
	// backend (.kb/defstruct.md). Each slot is NAMED after its accessor and the conc-name
	// is empty, so the generated accessor IS the Scheme accessor -- no wrapper call.
	private void recordType(LispCons form, List<LispVal> out) {
		RecordType type = recordType(form);
		Map<String, LispSymbol> slots = new HashMap<>();
		List<LispVal> slotList = new ArrayList<>();
		for (String field : type.fields()) {
			LispSymbol accessor = type.accessors().get(field);
			LispSymbol slot = accessor != null ? global(accessor) : fresh("SLOT");
			slots.put(field, slot);
			slotList.add(slot);
		}
		List<LispVal> constructorParams = new ArrayList<>();
		for (String field : type.constructorFields()) {
			constructorParams.add(slots.get(field));
		}
		LispSymbol constructor = type.constructor() != null ? global(type.constructor()) : fresh("MAKE");
		LispVal options = list(global(type.name()),
				list(symbol(":CONSTRUCTOR"), constructor, listOf(constructorParams)),
				list(symbol(":PREDICATE"), global(type.predicate())), list(symbol(":COPIER"), LispNil.INSTANCE),
				list(symbol(":CONC-NAME"), LispNil.INSTANCE));
		out.add(inherit(form, new LispCons(symbol("DEFSTRUCT"), new LispCons(options, listOf(slotList)))));
		type.modifiers().forEach((field, modifier) -> {
			LispSymbol record = fresh("R");
			LispSymbol newValue = fresh("V");
			LispSymbol slot = slots.get(field);
			if (slot == null || !type.accessors().containsKey(field)) {
				throw error("a modifier needs an accessor for its field: " + field, form);
			}
			// A modifier answers the unspecified object, like set-car!, so a REPL does
			// not
			// echo the stored value.
			out.add(inherit(form, list(symbol("DEFUN"), global(modifier), list(record, newValue),
					list(symbol("SETF"), list(slot, record), newValue), this.unspecifiedVariable)));
		});
	}

	/**
	 * An internal record type, hoisted to the top level.
	 *
	 * @param procedures what the body binds: each record procedure and the top-level
	 * procedure it means
	 */
	private record InternalRecord(List<LocalProcedure> procedures) {
	}

	private record LocalProcedure(LispSymbol identifier, Binding binding) {
	}

	private void refuseAgainInBody(LispSymbol identifier, Set<String> recordNames, LispCons form) {
		if (recordNames.contains(name(identifier))) {
			throw error("a body defines " + identifier.name() + " twice", form);
		}
	}

	// An internal define-record-type is a TOP-LEVEL defstruct, hoisted ahead of the
	// top-level form holding it: a defstruct is what registers the layout on every
	// backend, and the compile path refuses one anywhere else. Its name,
	// s%%[<enclosing definition> <type>], is one no identifier mangles to
	// (SchemeNames.libraryPrefix's argument), qualified by the top-level definition it
	// stands in so two procedures' types never meet. The slots are named after the
	// FIELDS -- the expander may have renamed the accessors -- and the accessors are the
	// generated ones through a conc-name; the other procedures are defuns named after
	// the type. The body binds its names to these, so every call stays direct. One type
	// per occurrence, not per evaluation (R7RS leaves generativity unspecified).
	private InternalRecord internalRecord(LispCons form) {
		InternalRecord known = this.internalRecords.get(form);
		if (known != null) {
			return known;
		}
		RecordType type = recordType(form);
		String base = this.prefix + SchemeNames.PREFIX + "%[" + this.enclosing + " " + name(type.name());
		String candidate = base + "]";
		for (int ordinal = 2; !this.internalRecordNames.add(candidate); ordinal++) {
			candidate = base + " " + ordinal + "]";
		}
		String structName = candidate;
		String concName = structName + " ";
		Map<String, LispSymbol> slots = new HashMap<>();
		List<LispVal> slotList = new ArrayList<>();
		for (String field : type.fields()) {
			LispSymbol slot = symbol(SchemeNames.mangle(field));
			slots.put(field, slot);
			slotList.add(slot);
		}
		List<LocalProcedure> procedures = new ArrayList<>();
		type.accessors()
			.forEach((field, accessor) -> procedures
				.add(new LocalProcedure(accessor, new GlobalFunction(symbol(concName + SchemeNames.mangle(field))))));
		List<LispVal> constructorParams = new ArrayList<>();
		for (String field : type.constructorFields()) {
			constructorParams.add(slots.get(field));
		}
		LispSymbol constructor;
		if (type.constructor() != null) {
			constructor = symbol(structName + "(" + name(type.constructor()) + ")");
			procedures.add(new LocalProcedure(type.constructor(), new GlobalFunction(constructor)));
		}
		else {
			constructor = fresh("MAKE");
		}
		LispSymbol predicate = symbol(structName + "(" + name(type.predicate()) + ")");
		procedures.add(new LocalProcedure(type.predicate(), new GlobalPredicate(predicate)));
		LispVal options = list(symbol(structName), list(symbol(":CONSTRUCTOR"), constructor, listOf(constructorParams)),
				list(symbol(":PREDICATE"), predicate), list(symbol(":COPIER"), LispNil.INSTANCE),
				list(symbol(":CONC-NAME"), new LispString(concName)));
		this.hoisted.add(inherit(form, new LispCons(symbol("DEFSTRUCT"), new LispCons(options, listOf(slotList)))));
		type.modifiers().forEach((field, modifier) -> {
			LispSymbol name = symbol(structName + "(" + name(modifier) + ")");
			LispSymbol record = fresh("R");
			LispSymbol newValue = fresh("V");
			this.hoisted.add(inherit(form,
					list(symbol("DEFUN"), name, list(record, newValue),
							list(symbol("SETF"), list(symbol(concName + SchemeNames.mangle(field)), record), newValue),
							this.unspecifiedVariable)));
			procedures.add(new LocalProcedure(modifier, new GlobalFunction(name)));
		});
		InternalRecord record = new InternalRecord(List.copyOf(procedures));
		this.internalRecords.put(form, record);
		return record;
	}

	// ------------------------------------------------------------------ expressions

	private LispVal value(LispVal expression, Scope scope) {
		return lower(expression, Context.of(scope));
	}

	/**
	 * Lowers one expression: to a form answering its value when the context has no
	 * destination, else to a statement that stores the value in the destination or jumps.
	 */
	private LispVal lower(LispVal expression, Context context) {
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

	private LispVal leaf(LispVal valueForm, Context context) {
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
	private static LispVal exiting(Exit exit, LispVal loop) {
		return exit.used ? list(symbol("BLOCK"), exit.block, loop) : loop;
	}

	// A form run for its effect, whose Scheme value is the unspecified object: the raw
	// form alone where nobody reads the value.
	private LispVal effect(LispVal effectForm, Context context) {
		if (context.discarded()) {
			return effectForm;
		}
		Destination destination = context.destination();
		if (destination == null) {
			return list(symbol("PROGN"), effectForm, this.unspecifiedVariable);
		}
		return list(symbol("PROGN"), effectForm, list(symbol("SETQ"), destination.result(), this.unspecifiedVariable));
	}

	private LispVal atom(LispVal expression, Scope scope) {
		return switch (expression) {
			case LispSymbol identifier -> {
				if (identifier.equals(SchemeReader.TRUE)) {
					yield LispTrue.INSTANCE;
				}
				yield identifier.equals(SchemeReader.FALSE) ? this.falseVariable : reference(identifier, scope);
			}
			case LispArray vector -> list(symbol("QUOTE"), datum(vector));
			default -> expression;
		};
	}

	// A record predicate as a first-class Scheme procedure: #t/#f, not T/NIL.
	private LispVal predicateValue(GlobalPredicate predicate) {
		LispSymbol argument = fresh("X");
		return list(symbol("LAMBDA"), list(argument),
				SchemeBuiltins.toSchemeValue(SchemeBuiltins.Result.PREDICATE, list(predicate.symbol(), argument)));
	}

	private LispVal reference(LispSymbol identifier, Scope scope) {
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
			// Not defined in this file: a variable some other file defines.
			case null -> cl(identifier);
		};
	}

	private LispVal lowerForm(LispCons form, Context context) {
		if (form.car() instanceof LispSymbol head) {
			Binding binding = lookup(head, context.scope());
			if (binding instanceof Syntax syntax) {
				return syntax(syntax, form, context);
			}
		}
		return application(form, context);
	}

	private LispVal syntax(Syntax syntax, LispCons form, Context context) {
		Scope scope = context.scope();
		return switch (syntax.core()) {
			case QUOTE -> leaf(quoted(single(form)), context);
			case QUASIQUOTE -> leaf(quasi(single(form), new Quasi(1, scope, form)), context);
			case LAMBDA -> {
				List<LispVal> parts = elements(form, form);
				if (parts.size() < 3) {
					throw error("a lambda needs formals and a body", form);
				}
				yield leaf(lambda(
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
						: progn(body(parts, context.in(new Scope(scope))));
			}
			case LET -> let(form, context);
			case LET_STAR -> letStar(form, context);
			case LETREC, LETREC_STAR -> letrec(form, context);
			case LET_VALUES -> letValues(form, context, false);
			case LET_STAR_VALUES -> letValues(form, context, true);
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
	private LispVal guard(LispCons form, Scope scope) {
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
	private LispVal caseLambda(LispCons form) {
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
	private LispVal parameterize(LispCons form, List<LispVal> bindings, List<LispVal> body, Scope scope) {
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
	private LispVal promise(int state, LispCons form, Scope scope) {
		LispVal thunk = value(inherit(form, list(CORE_LAMBDA, LispNil.INSTANCE, single(form))), scope);
		return list(symbol("RONTOLISP::%SCHEME-DELAY"), new LispInteger(state), thunk);
	}

	// ------------------------------------------------------------------ application

	private LispVal application(LispCons form, Context context) {
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
	private LispVal builtinEffect(Call call, Builtin builtin, Scope scope) {
		LispVal literal = displayOfALiteral(call, builtin);
		return literal != null ? literal : builtinCall(call, builtin, scope);
	}

	private record Call(LispCons form, LispVal operator, List<LispVal> operands, @Nullable Binding binding) {
	}

	private LispVal call(Call call, Scope scope) {
		if (call.binding() instanceof Builtin builtin) {
			LispVal multipleValues = callWithValues(call, scope);
			if (multipleValues != null) {
				return multipleValues;
			}
			return SchemeBuiltins.toSchemeValue(builtin.entry().result(), builtinCall(call, builtin, scope));
		}
		List<LispVal> arguments = values(call.operands(), scope);
		return switch (call.binding()) {
			case GlobalFunction function -> new LispCons(function.symbol(), listOf(arguments));
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
					if (head.equals(SchemeReader.FALSE)) {
						yield new LispCons(symbol("FUNCALL"),
								new LispCons(ensure(this.falseVariable), listOf(arguments)));
					}
					if (head.equals(SchemeReader.TRUE)) {
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

	private LispVal builtinCall(Call call, Builtin builtin, Scope scope) {
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
		LispVal produced = progn(body(producerParts.subList(2, producerParts.size()), Context.of(new Scope(scope))));
		Scope inner = new Scope(scope);
		List<LispVal> variables = new ArrayList<>();
		for (LispSymbol formal : formals.required()) {
			variables.add(bind(formal, inner));
		}
		return new LispCons(symbol("MULTIPLE-VALUE-BIND"), new LispCons(listOf(variables), new LispCons(produced,
				listOf(body(consumerParts.subList(2, consumerParts.size()), Context.of(inner))))));
	}

	private List<LispVal> values(List<LispVal> expressions, Scope scope) {
		List<LispVal> lowered = new ArrayList<>();
		for (LispVal expression : expressions) {
			lowered.add(value(expression, scope));
		}
		return lowered;
	}

	private LispVal jump(Target target, List<LispVal> arguments) {
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

	private Test test(LispVal expression, Scope scope) {
		if (expression.equals(SchemeReader.FALSE)) {
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
				return new Test(single(form).equals(SchemeReader.FALSE) ? LispNil.INSTANCE : LispTrue.INSTANCE, false);
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
	private Test generic(LispVal expression, Scope scope) {
		return new Test(list(symbol("EQ"), value(expression, scope), this.falseVariable), true);
	}

	// Whether the expression can only answer #t or #f, so its VALUE is (if test t false)
	// rather than a temporary per `or` operand.
	private boolean isBoolean(LispVal expression, Scope scope) {
		if (expression.equals(SchemeReader.TRUE) || expression.equals(SchemeReader.FALSE)) {
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

	private static LispVal desugarAnd(List<LispVal> operands) {
		if (operands.isEmpty()) {
			return SchemeReader.TRUE;
		}
		if (operands.size() == 1) {
			return operands.get(0);
		}
		return list(CORE_IF, operands.get(0), new LispCons(CORE_AND, listOf(operands.subList(1, operands.size()))),
				SchemeReader.FALSE);
	}

	private LispVal desugarOr(List<LispVal> operands) {
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

	private LispVal desugarCond(List<LispVal> clauses, Scope scope, LispCons form) {
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

	private boolean isArrow(LispVal datum, Scope scope) {
		return datum instanceof LispSymbol keyword && lookup(keyword, scope) instanceof Syntax syntax
				&& syntax.core() == Core.ARROW;
	}

	// (case key ((d...) e...)... (else e...)): the key once, then eqv? tests spelled
	// straight in Common Lisp, so they fuse into the ifs.
	private LispVal desugarCase(LispCons form, Scope scope) {
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
	private LispVal desugarDo(LispCons form) {
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

	// ------------------------------------------------------------------ binding forms

	private record Bindings(List<LispSymbol> variables, List<LispVal> inits) {
	}

	private Bindings bindings(LispVal specs, LispCons form) {
		List<LispSymbol> variables = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		for (LispVal spec : elements(specs, form)) {
			List<LispVal> binding = elements(spec, form);
			if (binding.size() != 2) {
				throw error("a binding is (variable init)", form);
			}
			variables.add(identifier(binding.get(0), form));
			inits.add(binding.get(1));
		}
		return new Bindings(variables, inits);
	}

	private LispVal let(LispCons form, Context context) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol) {
			return namedLet(form, context);
		}
		if (parts.size() < 3) {
			throw error("malformed let", form);
		}
		Bindings bindings = bindings(parts.get(1), form);
		Scope inner = new Scope(context.scope());
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.variables().size(); i++) {
			LispVal init = value(bindings.inits().get(i), context.scope());
			pairs.add(list(bind(bindings.variables().get(i), inner), init));
		}
		return new LispCons(symbol("LET"),
				new LispCons(listOf(pairs), listOf(body(parts.subList(2, parts.size()), context.in(inner)))));
	}

	private LispVal letStar(LispCons form, Context context) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 3) {
			throw error("malformed let*", form);
		}
		Bindings bindings = bindings(parts.get(1), form);
		Scope inner = context.scope();
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.variables().size(); i++) {
			LispVal init = value(bindings.inits().get(i), inner);
			inner = new Scope(inner);
			pairs.add(list(bind(bindings.variables().get(i), inner), init));
		}
		return new LispCons(symbol("LET*"), new LispCons(listOf(pairs),
				listOf(body(parts.subList(2, parts.size()), context.in(new Scope(inner))))));
	}

	// letrec and letrec*: bind to nil, then assign in order -- the shape `labels` itself
	// lowers to (.kb/flet-labels.md), so mutual recursion works on every backend.
	private LispVal letrec(LispCons form, Context context) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 3) {
			throw error("malformed letrec", form);
		}
		Bindings bindings = bindings(parts.get(1), form);
		Scope inner = new Scope(context.scope());
		List<LispVal> pairs = new ArrayList<>();
		for (LispSymbol variable : bindings.variables()) {
			pairs.add(list(bind(variable, inner), LispNil.INSTANCE));
		}
		List<LispVal> forms = new ArrayList<>();
		for (int i = 0; i < bindings.variables().size(); i++) {
			LispSymbol variable = bindings.variables().get(i);
			forms.add(list(symbol("SETQ"), variableSymbol(variable, inner),
					recursiveValue(variable, bindings.inits().get(i), inner)));
		}
		forms.addAll(body(parts.subList(2, parts.size()), context.in(new Scope(inner))));
		return new LispCons(symbol("LET"), new LispCons(listOf(pairs), listOf(forms)));
	}

	// The value of a variable that may be a procedure calling itself: a syntactic lambda
	// bound to a never-assigned variable gets its self tail calls turned into a loop.
	private LispVal recursiveValue(LispSymbol variable, LispVal init, Scope scope) {
		if (init instanceof LispCons lambda && syntaxOf(lambda, scope) == Core.LAMBDA
				&& !this.assignedNames.contains(name(variable))) {
			List<LispVal> parts = elements(lambda, lambda);
			if (parts.size() >= 3) {
				return inherit(lambda, lambda(new ProcedureSpec(formals(parts.get(1), lambda),
						parts.subList(2, parts.size()), lookup(variable, scope), variable), scope));
			}
		}
		return value(init, scope);
	}

	private LispVal namedLet(LispCons form, Context context) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 4) {
			throw error("malformed named let", form);
		}
		LispSymbol loopName = (LispSymbol) parts.get(1);
		Bindings bindings = bindings(parts.get(2), form);
		List<LispVal> inits = values(bindings.inits(), context.scope());
		return namedLet(new NamedLet(form, loopName, bindings.variables(), inits, parts.subList(3, parts.size())),
				context);
	}

	private LispVal namedLet(NamedLet loop, Context context) {
		LispSymbol loopName = loop.name();
		List<LispVal> inits = loop.inits();
		if (!this.assignedNames.contains(name(loopName))) {
			LispVal pure = pureLoop(loop, context, false);
			if (pure != null) {
				return pure;
			}
		}
		// The name escapes: a real procedure, bound letrec-style and called once.
		Scope inner = new Scope(context.scope());
		LispSymbol procedure = bind(loopName, inner);
		LispVal lambda = recursiveValue(loopName,
				new LispCons(CORE_LAMBDA, new LispCons(listOf(new ArrayList<>(loop.variables())), listOf(loop.body()))),
				inner);
		LispVal call = new LispCons(symbol("FUNCALL"), new LispCons(procedure, listOf(inits)));
		return leaf(list(symbol("LET"), list(list(procedure, LispNil.INSTANCE)),
				list(symbol("SETQ"), procedure, lambda), call), context);
	}

	private record NamedLet(LispCons form, LispSymbol name, List<LispSymbol> variables, List<LispVal> inits,
			List<LispVal> body) {
	}

	/**
	 * A named {@code let} as {@code tagbody}/{@code go}, or {@code null} when the name is
	 * used as anything but a tail call. The loop variables are assigned in place -- the
	 * shape the backends' typed loops recognize -- unless the body creates a closure: a
	 * closure must capture THIS iteration's variables, so that loop rebinds them per
	 * iteration from carriers ({@code fresh}).
	 */
	private @Nullable LispVal pureLoop(NamedLet loop, Context context, boolean fresh) {
		LoopName binding = new LoopName();
		Scope inner = new Scope(context.scope());
		inner.bindings.put(name(loop.name()), binding);
		Scope bodyScope = new Scope(inner);
		List<LispSymbol> variables = new ArrayList<>();
		List<LispSymbol> carriers = new ArrayList<>();
		for (LispSymbol variable : loop.variables()) {
			variables.add(bind(variable, bodyScope));
			carriers.add(fresh("C"));
		}
		Target target = new Target(binding, new LoopShape(fresh("L"), fresh ? carriers : variables, false, !fresh),
				bodyScope, loop.variables().stream().map(this::name).toList());
		Destination outer = context.destination();
		LispSymbol result = outer != null ? outer.result() : fresh("R");
		List<Target> targets = new ArrayList<>(outer != null ? outer.targets() : List.of());
		targets.add(target);
		// An inner loop stores into the enclosing loop's destination, and leaves through
		// the enclosing loop's block.
		Exit exit = outer != null ? outer.exit() : new Exit(result);
		int closuresBefore = this.closures;
		List<LispVal> statements = body(loop.body(),
				Context.storing(bodyScope, new Destination(result, targets, exit)));
		if (binding.escaped) {
			return null;
		}
		if (!fresh && target.used && (this.closures != closuresBefore || target.shadowed)) {
			return pureLoop(loop, context, true);
		}
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < variables.size(); i++) {
			pairs.add(list(fresh ? carriers.get(i) : variables.get(i), loop.inits().get(i)));
		}
		if (outer == null) {
			pairs.add(list(result, LispNil.INSTANCE));
		}
		List<LispVal> forms = new ArrayList<>();
		forms.add(loopBody(new LoopBody(target, fresh ? variables : List.of(), carriers, statements)));
		if (outer == null) {
			forms.add(result);
		}
		LispVal lowered = new LispCons(symbol("LET"), new LispCons(listOf(pairs), listOf(forms)));
		return outer == null ? exiting(exit, lowered) : lowered;
	}

	private record LoopBody(Target target, List<LispSymbol> rebound, List<LispSymbol> carriers,
			List<LispVal> statements) {
	}

	// (tagbody L statements...), the statements inside a per-iteration (let ((v c)...))
	// when the loop rebinds; just the statements when nothing ever jumps.
	private static LispVal loopBody(LoopBody loop) {
		List<LispVal> statements = loop.statements();
		if (!loop.rebound().isEmpty()) {
			List<LispVal> pairs = new ArrayList<>();
			for (int i = 0; i < loop.rebound().size(); i++) {
				pairs.add(list(loop.rebound().get(i), loop.carriers().get(i)));
			}
			statements = List.of(new LispCons(symbol("LET"), new LispCons(listOf(pairs), listOf(statements))));
		}
		if (!loop.target().used) {
			return progn(statements);
		}
		return new LispCons(symbol("TAGBODY"), new LispCons(loop.target().label, listOf(statements)));
	}

	private LispVal letValues(LispCons form, Context context, boolean sequential) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() < 3) {
			throw error("malformed let-values", form);
		}
		List<LispVal> clauses = elements(parts.get(1), form);
		Scope inner = new Scope(context.scope());
		// Parallel clauses bind temporaries first, so no init sees another clause's
		// variables; one clause (the common case) and let*-values bind directly.
		boolean direct = sequential || clauses.size() <= 1;
		List<ValuesClause> lowered = new ArrayList<>();
		List<LispVal> renames = new ArrayList<>();
		for (LispVal clauseDatum : clauses) {
			List<LispVal> clause = elements(clauseDatum, form);
			if (clause.size() != 2) {
				throw error("a let-values binding is (formals init)", form);
			}
			LispVal init = value(clause.get(1), direct ? inner : context.scope());
			Formals formals = formals(clause.get(0), form);
			if (direct) {
				inner = new Scope(inner);
			}
			List<LispSymbol> names = new ArrayList<>();
			for (LispSymbol formal : formals.all()) {
				if (direct) {
					names.add(bind(formal, inner));
				}
				else {
					LispSymbol temporary = fresh("V");
					names.add(temporary);
					renames.add(list(cl(formal), temporary));
				}
			}
			lowered.add(new ValuesClause(names, formals.rest() != null, init));
		}
		if (!direct) {
			inner = new Scope(inner);
			for (LispVal clauseDatum : clauses) {
				for (LispSymbol formal : formals(elements(clauseDatum, form).get(0), form).all()) {
					bind(formal, inner);
				}
			}
		}
		List<LispVal> body = body(parts.subList(2, parts.size()), context.in(new Scope(inner)));
		LispVal result = direct ? progn(body)
				: new LispCons(symbol("LET"), new LispCons(listOf(renames), listOf(body)));
		for (int i = lowered.size() - 1; i >= 0; i--) {
			result = lowered.get(i).around(result, this);
		}
		return result;
	}

	private record ValuesClause(List<LispSymbol> names, boolean rest, LispVal init) {

		LispVal around(LispVal body, SchemeLowering lowering) {
			if (!this.rest) {
				return list(symbol("MULTIPLE-VALUE-BIND"), listOf(new ArrayList<>(this.names)), this.init, body);
			}
			LispSymbol all = lowering.fresh("M");
			List<LispVal> pairs = new ArrayList<>();
			int last = this.names.size() - 1;
			for (int i = 0; i < last; i++) {
				pairs.add(list(this.names.get(i), list(symbol("NTH"), new LispInteger(i), all)));
			}
			pairs.add(list(this.names.get(last), list(symbol("NTHCDR"), new LispInteger(last), all)));
			return list(symbol("LET"), list(list(all, list(symbol("MULTIPLE-VALUE-LIST"), this.init))),
					list(symbol("LET"), listOf(pairs), body));
		}

	}

	// ------------------------------------------------------------------ procedures and
	// bodies

	private LispVal lambda(ProcedureSpec spec, Scope scope) {
		this.closures++;
		Lowered lowered = procedure(spec, scope);
		return new LispCons(symbol("LAMBDA"), new LispCons(lowered.lambdaList(), listOf(lowered.body())));
	}

	private Lowered procedure(ProcedureSpec spec, Scope scope) {
		if (spec.self() != null && spec.selfName() != null && mentionsCall(spec.body(), spec.selfName().name())) {
			Lowered loop = selfLoop(spec, scope, false);
			if (loop != null) {
				return loop;
			}
		}
		Scope inner = new Scope(scope);
		List<LispSymbol> parameters = new ArrayList<>();
		for (LispSymbol formal : spec.formals().all()) {
			parameters.add(bind(formal, inner));
		}
		return new Lowered(lambdaList(parameters, spec.formals().rest() != null),
				body(spec.body(), Context.of(new Scope(inner))));
	}

	// A procedure whose tail calls to itself jump: the parameters arrive in carriers and
	// the body runs inside (tagbody L ...), storing its value in a result variable. Null
	// when no call turned out to be a self tail call, so the plain shape is used.
	private @Nullable Lowered selfLoop(ProcedureSpec spec, Scope scope, boolean fresh) {
		Scope inner = new Scope(scope);
		List<LispSymbol> variables = new ArrayList<>();
		List<LispSymbol> carriers = new ArrayList<>();
		for (LispSymbol formal : spec.formals().all()) {
			variables.add(bind(formal, inner));
			carriers.add(fresh("C"));
		}
		boolean rest = spec.formals().rest() != null;
		Target target = new Target(java.util.Objects.requireNonNull(spec.self()),
				new LoopShape(fresh("L"), fresh ? carriers : variables, rest, !fresh), inner,
				spec.formals().all().stream().map(this::name).toList());
		LispSymbol result = fresh("R");
		Exit exit = new Exit(result);
		int closuresBefore = this.closures;
		List<LispVal> statements = body(spec.body(),
				Context.storing(new Scope(inner), new Destination(result, List.of(target), exit)));
		if (!target.used) {
			return null;
		}
		if (!fresh && (this.closures != closuresBefore || target.shadowed)) {
			return selfLoop(spec, scope, true);
		}
		List<LispVal> pairs = new ArrayList<>();
		if (!fresh) {
			for (int i = 0; i < variables.size(); i++) {
				pairs.add(list(variables.get(i), carriers.get(i)));
			}
		}
		pairs.add(list(result, LispNil.INSTANCE));
		LispVal loop = loopBody(new LoopBody(target, fresh ? variables : List.of(), carriers, statements));
		return new Lowered(lambdaList(carriers, rest),
				List.of(exiting(exit, list(symbol("LET"), listOf(pairs), loop, result))));
	}

	private static LispVal lambdaList(List<LispSymbol> parameters, boolean rest) {
		List<LispVal> lambdaList = new ArrayList<>(parameters);
		if (rest) {
			lambdaList.add(lambdaList.size() - 1, symbol("&REST"));
		}
		return listOf(lambdaList);
	}

	// Whether some call-shaped datum is headed by the name: the cheap reason to even try
	// the loop shape.
	private static boolean mentionsCall(List<LispVal> body, String name) {
		for (LispVal datum : body) {
			LispVal rest = datum;
			boolean head = true;
			while (rest instanceof LispCons cell) {
				if (head && cell.car() instanceof LispSymbol symbol && symbol.name().equals(name)) {
					return true;
				}
				if (cell.car() instanceof LispCons && mentionsCall(List.of(cell.car()), name)) {
					return true;
				}
				head = false;
				rest = cell.cdr();
			}
		}
		return false;
	}

	/**
	 * Lowers a body: internal definitions become {@code letrec*} -- bound to nil around
	 * the whole body, assigned where they stand -- and the last form takes the context's
	 * destination.
	 */
	private List<LispVal> body(List<LispVal> forms, Context context) {
		List<LispVal> flat = new ArrayList<>();
		for (LispVal form : forms) {
			spliceBodyBegins(form, context.scope(), flat);
		}
		if (flat.isEmpty()) {
			// Every caller checked its form has a body; (begin) splices to nothing.
			throw new IllegalArgumentException("an empty body");
		}
		Scope scope = context.scope();
		List<LispVal> pairs = new ArrayList<>();
		// An internal record type binds no variable at all: its names mean the hoisted
		// top-level procedures, called directly. Such a name may not be defined twice.
		Set<String> variables = new HashSet<>();
		Set<String> recordNames = new HashSet<>();
		for (LispVal form : flat) {
			if (form instanceof LispCons cons) {
				Core core = syntaxOf(cons, scope);
				if (core == Core.DEFINE) {
					LispSymbol name = definition(cons).name();
					refuseAgainInBody(name, recordNames, cons);
					variables.add(name(name));
					pairs.add(list(bind(name, scope), LispNil.INSTANCE));
				}
				else if (core == Core.DEFINE_VALUES) {
					for (LispSymbol variable : formals(second(cons), cons).all()) {
						refuseAgainInBody(variable, recordNames, cons);
						variables.add(name(variable));
						pairs.add(list(bind(variable, scope), LispNil.INSTANCE));
					}
				}
				else if (core == Core.DEFINE_RECORD_TYPE) {
					for (LocalProcedure procedure : internalRecord(cons).procedures()) {
						String name = name(procedure.identifier());
						if (variables.contains(name) || !recordNames.add(name)) {
							throw error("a body defines " + procedure.identifier().name() + " twice", cons);
						}
						scope.bindings.put(name, procedure.binding());
					}
				}
			}
		}
		List<LispVal> lowered = new ArrayList<>();
		for (int i = 0; i < flat.size(); i++) {
			LispVal form = flat.get(i);
			boolean last = i == flat.size() - 1;
			Core core = form instanceof LispCons cons ? syntaxOf(cons, scope) : null;
			if (core == Core.DEFINE && form instanceof LispCons cons) {
				Definition definition = definition(cons);
				Binding self = this.assignedNames.contains(name(definition.name())) ? null
						: lookup(definition.name(), scope);
				lowered.add(inherit(cons, list(symbol("SETQ"), variableSymbol(definition.name(), scope),
						definedValue(definition, new DefinedIn(cons, scope, self)))));
			}
			else if (core == Core.DEFINE_VALUES && form instanceof LispCons cons) {
				lowered.add(inherit(cons, defineValues(cons, scope)));
			}
			else if (core == Core.DEFINE_RECORD_TYPE) {
				// Defined at the top level already; a body ending in one answers the
				// unspecified object.
				if (last) {
					lowered.add(lower(CORE_UNSPECIFIED, context));
				}
			}
			else {
				lowered.add(lower(form, last ? context : Context.discarding(scope)));
			}
		}
		if (pairs.isEmpty()) {
			return lowered;
		}
		return List.of(new LispCons(symbol("LET"), new LispCons(listOf(pairs), listOf(lowered))));
	}

	private void spliceBodyBegins(LispVal datum, Scope scope, List<LispVal> out) {
		if (datum instanceof LispCons form && syntaxOf(form, scope) == Core.BEGIN && form.cdr() instanceof LispCons) {
			for (LispVal inner : elements(form.cdr(), form)) {
				spliceBodyBegins(inner, scope, out);
			}
		}
		else {
			out.add(datum);
		}
	}

	// ------------------------------------------------------------------ data

	private LispVal quoted(LispVal datum) {
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
	private LispVal datum(LispVal datum) {
		return switch (datum) {
			case LispSymbol symbol -> {
				if (symbol.equals(SchemeReader.TRUE)) {
					yield LispTrue.INSTANCE;
				}
				yield symbol.equals(SchemeReader.FALSE) ? symbol("#f") : symbol(SchemeNames.mangle(symbol.name()));
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

	private LispVal quasi(LispVal template, Quasi quasi) {
		if (template instanceof LispArray vector) {
			if (!hasUnquote(template)) {
				return quoted(template);
			}
			return list(symbol("COERCE"), quasi(listOf(List.of(vector.data())), quasi),
					list(symbol("QUOTE"), symbol("VECTOR")));
		}
		if (!(template instanceof LispCons cons) || !hasUnquote(template)) {
			return quoted(template);
		}
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
		if (cons.car() instanceof LispCons splice && splice.car() instanceof LispSymbol head
				&& head.name().equals("unquote-splicing") && splice.cdr() instanceof LispCons rest
				&& rest.cdr() == LispNil.INSTANCE) {
			LispVal tail = quasi(cons.cdr(), quasi);
			if (quasi.depth() == 1) {
				return list(symbol("APPEND"), value(rest.car(), quasi.scope()), tail);
			}
			return list(symbol("CONS"), list(symbol("LIST"), quoted(head), quasi(rest.car(), quasi.shallower())), tail);
		}
		return list(symbol("CONS"), quasi(cons.car(), quasi), quasi(cons.cdr(), quasi));
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

	private @Nullable Binding lookup(LispSymbol identifier, Scope scope) {
		Core core = CORE_SYMBOLS.get(identifier);
		if (core != null) {
			return new Syntax(core, identifier.name());
		}
		return scope.find(name(identifier));
	}

	private @Nullable Core syntaxOf(LispCons form, Scope scope) {
		return form.car() instanceof LispSymbol head && lookup(head, scope) instanceof Syntax syntax ? syntax.core()
				: null;
	}

	// The scope key AND the emitted spelling: a generated temporary verbatim (uppercase,
	// which no mangled user identifier can be), a user identifier mangled.
	private String name(LispSymbol identifier) {
		return this.generated.contains(identifier) ? identifier.name() : SchemeNames.mangle(identifier.name());
	}

	private LispSymbol cl(LispSymbol identifier) {
		return this.generated.contains(identifier) ? identifier : symbol(name(identifier));
	}

	// A top-level name this lowering defines: the user's spelling in a program, private
	// to the library in a library (SchemeNames.libraryPrefix).
	private LispSymbol global(LispSymbol identifier) {
		return this.generated.contains(identifier) ? identifier : symbol(this.prefix + name(identifier));
	}

	private LispSymbol bind(LispSymbol identifier, Scope scope) {
		LispSymbol variable = cl(identifier);
		scope.bindings.put(name(identifier), new Variable(variable));
		return variable;
	}

	private LispSymbol variableSymbol(LispSymbol identifier, Scope scope) {
		return switch (lookup(identifier, scope)) {
			case Variable variable when this.libraryImports.contains(variable) -> throw new IllegalArgumentException(
					"cannot assign " + identifier.name() + ": it is imported from a library");
			case Variable variable -> variable.symbol();
			case null -> cl(identifier);
			default -> throw new IllegalArgumentException(
					"cannot assign " + identifier.name() + ": it is not a variable in this file");
		};
	}

	private LispSymbol fresh(String kind) {
		LispSymbol temporary = new LispSymbol(this.prefix + "%SCM-" + kind + (++this.counter));
		this.generated.add(temporary);
		return temporary;
	}

	private Formals formals(LispVal datum, LispCons form) {
		List<LispSymbol> required = new ArrayList<>();
		LispVal rest = datum;
		while (rest instanceof LispCons cell) {
			required.add(identifier(cell.car(), form));
			rest = cell.cdr();
		}
		return new Formals(required, rest == LispNil.INSTANCE ? null : identifier(rest, form));
	}

	private LispSymbol identifier(LispVal datum, LispCons form) {
		if (datum instanceof LispSymbol symbol && !symbol.equals(SchemeReader.TRUE)
				&& !symbol.equals(SchemeReader.FALSE)) {
			return symbol;
		}
		throw error("expected an identifier, got " + datum.print(), form);
	}

	private LispVal single(LispCons form) {
		List<LispVal> parts = elements(form, form);
		if (parts.size() != 2) {
			throw error("expected exactly one operand", form);
		}
		return parts.get(1);
	}

	private LispVal second(LispCons form) {
		return form.cdr() instanceof LispCons rest ? rest.car() : LispNil.INSTANCE;
	}

	private List<LispVal> elements(LispVal list, LispCons form) {
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

	private LispReadException error(String message, LispCons form) {
		return new LispReadException(message, this.reader.locate(form));
	}

	private static <T extends LispVal> T inherit(LispCons original, T lowered) {
		return SourceProvenance.inherit(original, lowered);
	}

	private static LispVal progn(List<LispVal> forms) {
		return forms.size() == 1 ? forms.get(0) : new LispCons(symbol("PROGN"), listOf(forms));
	}

	private static LispSymbol symbol(String name) {
		return new LispSymbol(name);
	}

	private static LispVal list(LispVal... elements) {
		return SchemeBuiltins.list(elements);
	}

	private static LispVal listOf(List<? extends LispVal> elements) {
		return SchemeBuiltins.listOf(new ArrayList<>(elements));
	}

}
