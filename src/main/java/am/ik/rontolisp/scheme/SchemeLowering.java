package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
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

	/**
	 * The syntactic keywords the lowering implements, plus the ones it refuses by name.
	 */
	private enum Core {

		QUOTE, QUASIQUOTE, UNQUOTE, UNQUOTE_SPLICING, LAMBDA, IF, SET, BEGIN, LET, LET_STAR, LETREC, LETREC_STAR, DO,
		COND, CASE, AND, OR, WHEN, UNLESS, DEFINE, DEFINE_VALUES, DEFINE_RECORD_TYPE, LET_VALUES, LET_STAR_VALUES,
		IMPORT, ELSE, ARROW, RAW, RAW_PREDICATE, UNSPECIFIED, UNSUPPORTED

	}

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
		for (String unsupported : List.of("define-syntax", "let-syntax", "letrec-syntax", "syntax-rules",
				"syntax-error", "define-library", "guard", "parameterize", "case-lambda", "delay", "delay-force",
				"make-promise", "include", "include-ci", "cond-expand")) {
			table.put(unsupported, Core.UNSUPPORTED);
		}
		return table;
	}

	// The keywords a desugaring spells. Compared by IDENTITY before any scope lookup, so
	// (define (f if) (or a b)) still expands `or` into the real `if`.
	private static final Map<LispSymbol, Core> CORE_SYMBOLS = new IdentityHashMap<>();

	private static final LispSymbol CORE_IF = core("if", Core.IF);

	private static final LispSymbol CORE_LET = core("let", Core.LET);

	private static final LispSymbol CORE_BEGIN = core("begin", Core.BEGIN);

	private static final LispSymbol CORE_AND = core("and", Core.AND);

	private static final LispSymbol CORE_OR = core("or", Core.OR);

	private static final LispSymbol CORE_COND = core("cond", Core.COND);

	private static final LispSymbol CORE_LAMBDA = core("lambda", Core.LAMBDA);

	private static final LispSymbol CORE_RAW_PREDICATE = core("raw-predicate", Core.RAW_PREDICATE);

	// Stands where a desugaring has no expression to put: the missing arm of an if, a
	// cond or case no clause of which is taken. Lowered to the unspecified object.
	private static final LispSymbol CORE_UNSPECIFIED = core("unspecified", Core.UNSPECIFIED);

	private static LispSymbol core(String name, Core core) {
		LispSymbol symbol = new LispSymbol(name);
		CORE_SYMBOLS.put(symbol, core);
		return symbol;
	}

	/** What an identifier means at a point in the program. */
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
	 * A bare value, not a procedure: {@code true}, {@code false}, {@code nil}. Not an
	 * R7RS export of {@code (scheme base)}, so unreachable by name through {@code import}
	 * -- only the no-import default merges it (a REPL, and a file with no import at all).
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

		private boolean used;

		Target(Binding binding, LoopShape shape) {
			this.binding = binding;
			this.label = shape.label();
			this.assigned = shape.assigned();
			this.rest = shape.rest();
			this.parallel = shape.parallel();
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

	/** Where a statement puts its value, and the labels it may jump to. */
	private record Destination(LispSymbol result, List<Target> targets) {
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

	private boolean falseBound;

	private final Set<LispSymbol> generated = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	private final Set<String> assignedNames = new HashSet<>();

	private final Scope global = new Scope(null);

	private final LispSymbol falseVariable = symbol(SchemeBuiltins.FALSE_VARIABLE);

	private final LispSymbol unspecifiedVariable = symbol(SchemeBuiltins.UNSPECIFIED_VARIABLE);

	private int counter;

	private int closures;

	private SchemeLowering(SchemeReader reader, boolean interactive) {
		this.reader = reader;
		this.interactive = interactive;
	}

	/**
	 * A lowering of one whole file.
	 * @param reader the file's reader
	 * @return the lowering
	 */
	static SchemeLowering ofFile(SchemeReader reader) {
		return new SchemeLowering(reader, false);
	}

	/**
	 * A lowering that reads buffer after buffer ({@link #interact}): everything
	 * {@code (scheme base)} and {@code (scheme write)} export is visible from the start,
	 * as in any R7RS REPL.
	 * @return the lowering
	 */
	static SchemeLowering ofSession() {
		SchemeLowering lowering = new SchemeLowering(new SchemeReader("", null), true);
		lowering.imports();
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
		List<LispVal> forms = new ArrayList<>();
		for (LispVal datum : this.datums) {
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
		for (LispVal form : forms) {
			List<LispVal> lowered = new ArrayList<>();
			boolean echoes = topLevel(form, lowered);
			out.add(new SchemeTopLevel(List.copyOf(lowered), echoes));
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
		int start = imports();
		for (LispVal datum : this.datums.subList(start, this.datums.size())) {
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
		for (LispVal form : forms) {
			topLevel(form, out);
		}
		return out;
	}

	private LispVal falseBinding() {
		return list(symbol("SETQ"), this.falseVariable, list(symbol("QUOTE"), symbol("#f")), this.unspecifiedVariable,
				list(symbol("QUOTE"), symbol(SchemeBuiltins.UNSPECIFIED_NAME)));
	}

	// ------------------------------------------------------------------ imports

	/** The R7RS libraries {@code (import (scheme <name>))} accepts. */
	private static final List<String> IMPORTABLE_LIBRARIES = List.of("base", "write", "inexact", "cxr",
			"process-context");

	// Leading (import ...) forms pick what the global scope holds; a program with none
	// sees everything, like a REPL.
	private int imports() {
		int index = 0;
		Map<String, Binding> imported = new LinkedHashMap<>();
		while (index < this.datums.size() && this.datums.get(index) instanceof LispCons form
				&& form.car() instanceof LispSymbol head && head.name().equals("import")) {
			for (LispVal set : elements(form.cdr(), form)) {
				imported.putAll(importSet(set, form));
			}
			index++;
		}
		if (index == 0) {
			for (String library : IMPORTABLE_LIBRARIES) {
				imported.putAll(library(library));
			}
			// Not R7RS exports, so not reachable by name through (import ...): a REPL,
			// and a file with no import at all, sees them anyway, the way an unqualified
			// SICP sample -- written against an implementation that already had them --
			// expects.
			imported.putAll(library("sicp"));
		}
		for (Map.Entry<String, Binding> entry : imported.entrySet()) {
			this.global.bindings.put(SchemeNames.mangle(entry.getKey()), entry.getValue());
		}
		return index;
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
			throw error(
					"library " + set.print() + " is not available: this experimental front end has (scheme base),"
							+ " (scheme write), (scheme inexact), (scheme cxr) and (scheme process-context) only",
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
		if (library.equals("sicp")) {
			exports.put("true", new Constant(LispTrue.INSTANCE));
			exports.put("false", new Constant(this.falseVariable));
			exports.put("nil", new Constant(LispNil.INSTANCE));
		}
		for (SchemeBuiltins.Entry entry : SchemeBuiltins.entries().values()) {
			if (entry.library().equals(library)) {
				exports.put(entry.name(), new Builtin(entry));
			}
		}
		return exports;
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
				definitions.merge(name, 1, Integer::sum);
				(definition.procedure() ? procedures : variables).putIfAbsent(name, definition.name());
			}
			else if (core == Core.DEFINE_VALUES) {
				for (LispSymbol variable : formals(second(form), form).all()) {
					refuseARecordProcedure(variable, form);
					definitions.merge(name(variable), 2, Integer::sum);
					variables.putIfAbsent(name(variable), variable);
				}
			}
			else if (core == Core.DEFINE_RECORD_TYPE) {
				records.add(form);
			}
		}
		variables.forEach((name, identifier) -> this.global.bindings.put(name, new Variable(cl(identifier))));
		procedures.forEach((name, identifier) -> {
			boolean direct = !this.interactive && definitions.getOrDefault(name, 0) == 1
					&& !this.assignedNames.contains(name) && !variables.containsKey(name);
			this.global.bindings.put(name, direct ? new GlobalFunction(cl(identifier)) : new Variable(cl(identifier)));
		});
		for (LispCons record : records) {
			declareRecord(record, definitions);
		}
	}

	// A file refuses a redefined record procedure in declareRecord, which sees both
	// definitions; a session meets the second one alone, against the scope it kept.
	private void refuseARecordProcedure(LispSymbol identifier, LispCons form) {
		Binding known = this.global.bindings.get(name(identifier));
		if (known instanceof GlobalFunction || known instanceof GlobalPredicate) {
			throw error("cannot redefine " + identifier.name() + ", a record procedure", form);
		}
	}

	// Answers whether the datum has a value at all: a definition, an import and a record
	// type have none. What an expression answers is the value's to say -- an effect's is
	// the unspecified object, which a session does not echo.
	private boolean topLevel(LispVal datum, List<LispVal> out) {
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
		List<LispSymbol> procedures = new ArrayList<>(type.accessors().values());
		procedures.addAll(type.modifiers().values());
		if (type.constructor() != null) {
			procedures.add(type.constructor());
		}
		procedures.add(type.predicate());
		for (LispSymbol procedure : procedures) {
			String name = name(procedure);
			if (definitions.merge(name, 1, Integer::sum) != 1 || this.assignedNames.contains(name)) {
				throw error("a record procedure cannot be redefined or assigned: " + procedure.name(), form);
			}
			this.global.bindings.put(name, procedure == type.predicate() ? new GlobalPredicate(cl(procedure))
					: new GlobalFunction(cl(procedure)));
		}
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
			LispSymbol slot = accessor != null ? cl(accessor) : fresh("SLOT");
			slots.put(field, slot);
			slotList.add(slot);
		}
		List<LispVal> constructorParams = new ArrayList<>();
		for (String field : type.constructorFields()) {
			constructorParams.add(slots.get(field));
		}
		LispSymbol constructor = type.constructor() != null ? cl(type.constructor()) : fresh("MAKE");
		LispVal options = list(cl(type.name()), list(symbol(":CONSTRUCTOR"), constructor, listOf(constructorParams)),
				list(symbol(":PREDICATE"), cl(type.predicate())), list(symbol(":COPIER"), LispNil.INSTANCE),
				list(symbol(":CONC-NAME"), LispNil.INSTANCE));
		out.add(inherit(form, new LispCons(symbol("DEFSTRUCT"), new LispCons(options, listOf(slotList)))));
		type.modifiers().forEach((field, modifier) -> {
			LispSymbol record = fresh("R");
			LispSymbol newValue = fresh("V");
			LispSymbol slot = slots.get(field);
			if (slot == null || !type.accessors().containsKey(field)) {
				throw error("a modifier needs an accessor for its field: " + field, form);
			}
			out.add(inherit(form, list(symbol("DEFUN"), cl(modifier), list(record, newValue),
					list(symbol("SETF"), list(slot, record), newValue))));
		});
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
		return destination == null ? valueForm : list(symbol("SETQ"), destination.result(), valueForm);
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

	private LispVal reference(LispSymbol identifier, Scope scope) {
		Binding binding = lookup(identifier, scope);
		return switch (binding) {
			case Variable variable -> variable.symbol();
			case GlobalFunction function -> list(symbol("FUNCTION"), function.symbol());
			case GlobalPredicate predicate -> {
				LispSymbol argument = fresh("X");
				yield list(symbol("LAMBDA"), list(argument), SchemeBuiltins
					.toSchemeValue(SchemeBuiltins.Result.PREDICATE, list(predicate.symbol(), argument)));
			}
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
			case RAW -> leaf(single(form), context);
			case UNSPECIFIED -> throw error("misplaced unspecified", form);
			case RAW_PREDICATE ->
				leaf(SchemeBuiltins.toSchemeValue(SchemeBuiltins.Result.PREDICATE, single(form)), context);
			case DEFINE, DEFINE_VALUES ->
				throw error("a definition is only allowed at the top level or at the head of a body", form);
			case DEFINE_RECORD_TYPE -> throw error("define-record-type is only supported at the top level", form);
			case IMPORT -> throw error("import must come before everything else", form);
			case ELSE, ARROW, UNQUOTE, UNQUOTE_SPLICING -> throw error("misplaced " + syntax.name(), form);
			case UNSUPPORTED ->
				throw error(syntax.name() + " is not supported by this experimental front end yet", form);
		};
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
				new LispCons(symbol("FUNCALL"), new LispCons(variable.symbol(), listOf(arguments)));
			case null, default -> {
				if (call.operator() instanceof LispSymbol head) {
					// Not defined in this file: a procedure some other file defines.
					yield new LispCons(cl(head), listOf(arguments));
				}
				yield new LispCons(symbol("FUNCALL"), new LispCons(value(call.operator(), scope), listOf(arguments)));
			}
		};
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
		Target target = new Target(binding, new LoopShape(fresh("L"), fresh ? carriers : variables, false, !fresh));
		Destination outer = context.destination();
		LispSymbol result = outer != null ? outer.result() : fresh("R");
		List<Target> targets = new ArrayList<>(outer != null ? outer.targets() : List.of());
		targets.add(target);
		int closuresBefore = this.closures;
		List<LispVal> statements = body(loop.body(), Context.storing(bodyScope, new Destination(result, targets)));
		if (binding.escaped) {
			return null;
		}
		if (!fresh && target.used && this.closures != closuresBefore) {
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
		return new LispCons(symbol("LET"), new LispCons(listOf(pairs), listOf(forms)));
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
				new LoopShape(fresh("L"), fresh ? carriers : variables, rest, !fresh));
		LispSymbol result = fresh("R");
		int closuresBefore = this.closures;
		List<LispVal> statements = body(spec.body(),
				Context.storing(new Scope(inner), new Destination(result, List.of(target))));
		if (!target.used) {
			return null;
		}
		if (!fresh && this.closures != closuresBefore) {
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
		return new Lowered(lambdaList(carriers, rest), List.of(list(symbol("LET"), listOf(pairs), loop, result)));
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
		for (LispVal form : flat) {
			if (form instanceof LispCons cons) {
				Core core = syntaxOf(cons, scope);
				if (core == Core.DEFINE) {
					pairs.add(list(bind(definition(cons).name(), scope), LispNil.INSTANCE));
				}
				else if (core == Core.DEFINE_VALUES) {
					for (LispSymbol variable : formals(second(cons), cons).all()) {
						pairs.add(list(bind(variable, scope), LispNil.INSTANCE));
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

	private LispSymbol bind(LispSymbol identifier, Scope scope) {
		LispSymbol variable = cl(identifier);
		scope.bindings.put(name(identifier), new Variable(variable));
		return variable;
	}

	private LispSymbol variableSymbol(LispSymbol identifier, Scope scope) {
		return switch (lookup(identifier, scope)) {
			case Variable variable -> variable.symbol();
			case null -> cl(identifier);
			default -> throw new IllegalArgumentException(
					"cannot assign " + identifier.name() + ": it is not a variable in this file");
		};
	}

	private LispSymbol fresh(String kind) {
		LispSymbol temporary = new LispSymbol("%SCM-" + kind + (++this.counter));
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
