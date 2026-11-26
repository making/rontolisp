package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;

import org.jspecify.annotations.Nullable;

/**
 * Top level and definitions of the Scheme lowering: what a top-level form lowers to (a
 * definition, a record type, or a value), the {@code define}/{@code define-values} shapes
 * including a whole-file procedure's {@code defun} and a case-lambda's per-clause defuns,
 * the globals a file pre-declares, and the imported names a form may read before their
 * definition.
 *
 * <p>
 * One slice of {@link SchemeLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class SchemeDefinitionLowering {

	private SchemeDefinitionLowering() {
	}

	// Answers whether the datum has a value at all: a definition, an import and a record
	// type have none. What an expression answers is the value's to say -- an effect's is
	// the unspecified object, which a session does not echo.
	static boolean topLevel(SchemeLowering s, LispVal datum, List<LispVal> out) {
		s.hoisted.clear();
		s.enclosing = "";
		if (datum instanceof LispCons form && s.syntaxOf(form, s.global) == Core.DEFINE
				&& form.cdr() instanceof LispCons rest) {
			// Malformed or not: definition() reports that, positioned, below.
			LispVal target = rest.car() instanceof LispCons signature ? signature.car() : rest.car();
			if (target instanceof LispSymbol name) {
				s.enclosing = s.name(name);
			}
		}
		List<LispVal> lowered = new ArrayList<>();
		boolean echoes = topLevelForm(s, datum, lowered);
		// The internal record types stand before the form that uses them: the
		// interpreter runs the forms in order.
		out.addAll(s.hoisted);
		out.addAll(lowered);
		s.hoisted.clear();
		return echoes;
	}

	static boolean topLevelForm(SchemeLowering s, LispVal datum, List<LispVal> out) {
		if (datum instanceof LispCons form) {
			try {
				switch (s.syntaxOf(form, s.global)) {
					case DEFINE -> {
						for (LispVal defined : topLevelDefine(s, form)) {
							out.add(defined instanceof LispCons cons ? SchemeLowering.inherit(form, cons) : defined);
						}
						trampoline(s, s.definition(form).name(), out);
					}
					case DEFINE_VALUES -> {
						out.add(SchemeLowering.inherit(form, s.defineValues(form, s.global)));
						for (LispSymbol variable : s.formals(s.second(form), form).all()) {
							trampoline(s, variable, out);
						}
					}
					case DEFINE_RECORD_TYPE -> SchemeRecordLowering.recordType(s, form, out);
					case IMPORT -> {
						if (!s.interactive) {
							throw s.error("import must come before everything else", form);
						}
					}
					case null, default -> {
						out.add(topLevelValue(s, datum));
						return true;
					}
				}
				return false;
			}
			catch (am.ik.rontolisp.reader.LispReadException ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				throw SourceProvenance.noteFailure(form, ex);
			}
		}
		out.add(topLevelValue(s, datum));
		return true;
	}

	// A file never reads a top-level form's value; a session echoes it, and so needs the
	// unspecified object to know what not to show.
	private static LispVal topLevelValue(SchemeLowering s, LispVal datum) {
		return s.lower(datum,
				s.interactive ? SchemeLowering.Context.of(s.global) : SchemeLowering.Context.discarding(s.global));
	}

	// (defun f (&rest a) (apply f a)): what a call lowered BEFORE the session defined f
	// reaches. It reads the variable on every call, so it follows set! and redefinition.
	private static void trampoline(SchemeLowering s, LispSymbol identifier, List<LispVal> out) {
		if (s.interactive) {
			LispSymbol name = s.cl(identifier);
			LispSymbol arguments = s.fresh("A");
			out.add(SchemeLowering.list(SchemeLowering.symbol("DEFUN"), name,
					SchemeLowering.list(SchemeLowering.symbol("&REST"), arguments),
					SchemeLowering.list(SchemeLowering.symbol("APPLY"), name, arguments)));
		}
	}

	private static List<LispVal> topLevelDefine(SchemeLowering s, LispCons form) {
		SchemeLowering.Definition definition = s.definition(form);
		SchemeLowering.Group group = s.groups.get(s.name(definition.name()));
		if (group != null) {
			return groupDefinition(s, group, form);
		}
		SchemeLowering.Binding binding = s.global.find(s.name(definition.name()));
		if (binding instanceof SchemeLowering.GlobalFunction function && !function.clauses().isEmpty()
				&& definition.caseLambda() != null) {
			return caseLambdaDefuns(s, function, definition, definition.caseLambda());
		}
		return List.of(topLevelDefinition(s, form, definition, binding));
	}

	// The first member's define emits the group's defun ahead of its own entry.
	private static List<LispVal> groupDefinition(SchemeLowering s, SchemeLowering.Group group, LispCons form) {
		SchemeLowering.Member member = group.members.stream()
			.filter(candidate -> candidate.form() == form)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("not a member of its group"));
		List<LispVal> out = new ArrayList<>();
		if (group.function == null) {
			SchemeGroupLowering.LoweredGroup lowered = SchemeGroupLowering.lowerGroup(s, group.members, s.global);
			group.function = lowered.function();
			out.add(SchemeLowering.inherit(group.members.get(0).form(), SchemeLowering
				.list(SchemeLowering.symbol("DEFUN"), lowered.function(), lowered.lambdaList(), lowered.body())));
		}
		out.add(SchemeGroupLowering.groupEntry(s, group, member));
		return out;
	}

	private static LispVal topLevelDefinition(SchemeLowering s, LispCons form, SchemeLowering.Definition definition,
			SchemeLowering.@Nullable Binding binding) {
		if (binding instanceof SchemeLowering.GlobalFunction function && definition.formals() != null) {
			SchemeLowering.Lowered lowered = SchemeLambdaLowering.procedure(s, new SchemeLowering.ProcedureSpec(
					s.formals(definition.formals(), form), definition.body(), function, definition.name()), s.global);
			return new LispCons(SchemeLowering.symbol("DEFUN"), new LispCons(function.symbol(),
					new LispCons(lowered.lambdaList(), SchemeLowering.listOf(lowered.body()))));
		}
		if (!(binding instanceof SchemeLowering.Variable variable)) {
			throw s.error("cannot redefine " + definition.name().name() + ", a record procedure", form);
		}
		// A session's procedure keeps its self tail calls a loop, like the defun a file
		// would make of it, unless the session has assigned the name so far. A set! typed
		// LATER cannot reach back into it: only a saved old value would tell.
		SchemeLowering.Binding self = s.interactive && !s.assignedNames.contains(s.name(definition.name())) ? variable
				: null;
		return SchemeLowering.list(SchemeLowering.symbol("SETQ"), variable.symbol(),
				definedValue(s, definition, new SchemeLowering.DefinedIn(form, s.global, self)));
	}

	// A top-level procedure defined once by a case-lambda: one defun per clause, named
	// s%%{<name> <n>} -- no identifier mangles to it (SchemeNames.libraryPrefix's
	// argument) -- which a direct call picks by its argument count, and the procedure's
	// own defun dispatching on the count at run time for everything else: a first-class
	// use, apply, and a count no clause accepts, which it reports. Empty -- the
	// procedure is lowered as one dispatching lambda -- when a clause may call ANOTHER
	// clause that may call it back: the single procedure keeps every tail call among
	// its clauses a jump, and separate defuns would turn such a cycle into recursion.
	// The scan is by name and blind to scope, like the hub's collectAssigned:
	// over-approximating only keeps the dispatch.
	private static List<SchemeLowering.Clause> clauses(SchemeLowering s, LispSymbol identifier, LispCons caseLambda) {
		List<SchemeLowering.Clause> clauses = new ArrayList<>();
		List<List<LispVal>> bodies = new ArrayList<>();
		String base = s.prefix + SchemeNames.PREFIX + "%{" + s.name(identifier) + " ";
		for (LispVal clause : s.elements(caseLambda.cdr(), caseLambda)) {
			// caseLambda() has checked every clause's shape by now.
			List<LispVal> parts = s.elements(clause, (LispCons) clause);
			SchemeLowering.Formals formals = s.formals(parts.get(0), (LispCons) clause);
			clauses.add(new SchemeLowering.Clause(SchemeLowering.symbol(base + (clauses.size() + 1) + "}"),
					formals.required().size(), formals.rest() != null));
			bodies.add(parts.subList(1, parts.size()));
		}
		SchemeLowering.GlobalFunction probe = new SchemeLowering.GlobalFunction(identifier, clauses);
		List<Set<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < bodies.size(); i++) {
			Set<Integer> callees = new HashSet<>();
			for (LispVal datum : bodies.get(i)) {
				collectClauseCalls(datum, s.name(identifier), probe, callees);
			}
			callees.remove(i);
			calls.add(callees);
		}
		for (int start = 0; start < clauses.size(); start++) {
			List<Integer> pending = new ArrayList<>(calls.get(start));
			Set<Integer> reached = new HashSet<>();
			while (!pending.isEmpty()) {
				int next = pending.removeLast();
				if (next == start) {
					return List.of();
				}
				if (reached.add(next)) {
					pending.addAll(calls.get(next));
				}
			}
		}
		return List.copyOf(clauses);
	}

	// The clauses the calls headed by the name pick, by argument count, as indexes.
	private static void collectClauseCalls(LispVal datum, String name, SchemeLowering.GlobalFunction function,
			Set<Integer> out) {
		if (datum instanceof am.ik.rontolisp.LispArray array) {
			for (LispVal element : array.data()) {
				collectClauseCalls(element, name, function, out);
			}
		}
		if (!(datum instanceof LispCons form)) {
			return;
		}
		int operands = -1;
		LispVal rest = form;
		while (rest instanceof LispCons cell) {
			collectClauseCalls(cell.car(), name, function, out);
			operands++;
			rest = cell.cdr();
		}
		if (form.car() instanceof LispSymbol head && head.name().equals(name) && rest == LispNil.INSTANCE) {
			SchemeLowering.Clause clause = function.clauseFor(operands);
			if (clause != null) {
				out.add(function.clauses().indexOf(clause));
			}
		}
	}

	// The clauses' defuns, then the dispatching one: the shape caseLambda() desugars to,
	// with each clause body a call of the clause's defun.
	private static List<LispVal> caseLambdaDefuns(SchemeLowering s, SchemeLowering.GlobalFunction function,
			SchemeLowering.Definition definition, LispCons caseLambda) {
		List<LispVal> defuns = new ArrayList<>();
		List<LispVal> clauses = s.elements(caseLambda.cdr(), caseLambda);
		for (int i = 0; i < clauses.size(); i++) {
			LispCons where = (LispCons) clauses.get(i);
			List<LispVal> parts = s.elements(where, where);
			SchemeLowering.Clause clause = function.clauses().get(i);
			SchemeLowering.Lowered lowered = SchemeLambdaLowering
				.procedure(
						s, new SchemeLowering.ProcedureSpec(s.formals(parts.get(0), where),
								parts.subList(1, parts.size()), function, definition.name(), clause.symbol()),
						s.global);
			defuns.add(SchemeLowering.inherit(where,
					new LispCons(SchemeLowering.symbol("DEFUN"), new LispCons(clause.symbol(),
							new LispCons(lowered.lambdaList(), SchemeLowering.listOf(lowered.body()))))));
		}
		LispSymbol arguments = s.fresh("A");
		LispSymbol count = s.fresh("N");
		boolean counted = false;
		LispVal chain = SchemeLowering.list(SchemeLowering.symbol("RONTOLISP::%SCHEME-CASE-LAMBDA-ARITY"), arguments);
		for (int i = clauses.size() - 1; i >= 0; i--) {
			SchemeLowering.Clause clause = function.clauses().get(i);
			LispVal call;
			if (clause.rest()) {
				call = SchemeLowering.list(SchemeLowering.symbol("APPLY"),
						SchemeLowering.list(SchemeLowering.symbol("FUNCTION"), clause.symbol()), arguments);
			}
			else {
				List<LispVal> taken = new ArrayList<>();
				for (int j = 0; j < clause.required(); j++) {
					taken.add(SchemeLowering.list(SchemeLowering.symbol("NTH"), new LispInteger(j), arguments));
				}
				call = new LispCons(clause.symbol(), SchemeLowering.listOf(taken));
			}
			if (clause.rest() && clause.required() == 0) {
				// Takes every count: nothing after it is reachable.
				chain = call;
				continue;
			}
			counted = true;
			chain = SchemeLowering.list(SchemeLowering.symbol("IF"), SchemeLowering
				.list(SchemeLowering.symbol(clause.rest() ? ">=" : "="), count, new LispInteger(clause.required())),
					call, chain);
		}
		LispVal dispatch = counted ? SchemeLowering.list(SchemeLowering.symbol("LET"),
				SchemeLowering
					.list(SchemeLowering.list(count, SchemeLowering.list(SchemeLowering.symbol("LENGTH"), arguments))),
				chain) : chain;
		defuns.add(SchemeLowering.list(SchemeLowering.symbol("DEFUN"), function.symbol(),
				SchemeLowering.list(SchemeLowering.symbol("&REST"), arguments), dispatch));
		return defuns;
	}

	static LispVal definedValue(SchemeLowering s, SchemeLowering.Definition definition,
			SchemeLowering.DefinedIn where) {
		if (definition.formals() == null) {
			return s.value(definition.body().get(0), where.scope());
		}
		return SchemeLambdaLowering.lambda(s,
				new SchemeLowering.ProcedureSpec(s.formals(definition.formals(), where.form()), definition.body(),
						where.self(), definition.name()),
				where.scope());
	}

	static void declareGlobals(SchemeLowering s, List<LispVal> forms) {
		Map<String, Integer> definitions = new HashMap<>();
		Map<String, LispSymbol> procedures = new LinkedHashMap<>();
		Map<String, LispCons> caseLambdas = new HashMap<>();
		Map<String, LispSymbol> variables = new LinkedHashMap<>();
		List<LispCons> records = new ArrayList<>();
		for (LispVal datum : forms) {
			if (!(datum instanceof LispCons form)) {
				continue;
			}
			Core core = s.syntaxOf(form, s.global);
			if (core == Core.DEFINE) {
				SchemeLowering.Definition definition = s.definition(form);
				String name = s.name(definition.name());
				s.refuseARecordProcedure(definition.name(), form);
				s.refuseRedefiningAnImport(definition.name(), form);
				definitions.merge(name, 1, Integer::sum);
				(definition.procedure() ? procedures : variables).putIfAbsent(name, definition.name());
				if (definition.caseLambda() != null) {
					caseLambdas.putIfAbsent(name, definition.caseLambda());
				}
			}
			else if (core == Core.DEFINE_VALUES) {
				for (LispSymbol variable : s.formals(s.second(form), form).all()) {
					s.refuseARecordProcedure(variable, form);
					s.refuseRedefiningAnImport(variable, form);
					definitions.merge(s.name(variable), 2, Integer::sum);
					variables.putIfAbsent(s.name(variable), variable);
				}
			}
			else if (core == Core.DEFINE_RECORD_TYPE) {
				SchemeRecordLowering.RecordType type = SchemeRecordLowering.recordType(s, form);
				s.refuseRedefiningAnImport(type.name(), form);
				for (LispSymbol procedure : SchemeRecordLowering.recordProcedures(type)) {
					s.refuseRedefiningAnImport(procedure, form);
				}
				records.add(form);
			}
		}
		Set<String> readEarly = s.interactive ? Set.of() : readBeforeDefinition(s, forms);
		variables.forEach(
				(name, identifier) -> s.global.bindings.put(name, new SchemeLowering.Variable(s.global(identifier))));
		procedures.forEach((name, identifier) -> {
			boolean direct = !s.interactive && definitions.getOrDefault(name, 0) == 1 && !s.assignedNames.contains(name)
					&& !variables.containsKey(name) && !readEarly.contains(name);
			LispCons caseLambda = caseLambdas.get(name);
			s.global.bindings.put(name,
					!direct ? new SchemeLowering.Variable(s.global(identifier)) : new SchemeLowering.GlobalFunction(
							s.global(identifier), caseLambda == null ? List.of() : clauses(s, identifier, caseLambda)));
		});
		for (LispCons record : records) {
			SchemeRecordLowering.declareRecord(s, record, definitions);
		}
		if (!s.interactive) {
			SchemeGroupLowering.declareGroups(s, forms);
		}
	}

	/**
	 * The names this file defines over an imported binding -- a builtin, a SICP constant
	 * -- that a form may READ before the first definition. Those become variables holding
	 * the imported value until then (the hub's {@code initialValues}): a {@code defun} is
	 * position-blind, so the interpreter has no function yet and the compile path hoists
	 * the user's. A read counts when it is in a form run at the top level (anything but a
	 * procedure definition and a record type), directly or through what anything defined
	 * before that form mentions. Scope-blind, like the hub's {@code collectAssigned}:
	 * over-approximating only costs the direct call.
	 */
	private static Set<String> readBeforeDefinition(SchemeLowering s, List<LispVal> forms) {
		Map<String, Integer> firstDefinition = new HashMap<>();
		List<List<String>> defined = new ArrayList<>();
		List<Boolean> run = new ArrayList<>();
		// What each form mentions: a definition's value or body, never its own target.
		List<Set<String>> mentioned = new ArrayList<>();
		for (int i = 0; i < forms.size(); i++) {
			List<String> names = new ArrayList<>();
			Set<String> referenced = new HashSet<>();
			boolean runs = true;
			if (forms.get(i) instanceof LispCons form && s.syntaxOf(form, s.global) == Core.DEFINE) {
				SchemeLowering.Definition definition = s.definition(form);
				names.add(s.name(definition.name()));
				definition.body().forEach(expression -> collectNames(expression, referenced, s));
				runs = !definition.procedure();
			}
			else if (forms.get(i) instanceof LispCons form && s.syntaxOf(form, s.global) == Core.DEFINE_VALUES) {
				s.formals(s.second(form), form).all().forEach(variable -> names.add(s.name(variable)));
				collectNames(form.cdr() instanceof LispCons rest ? rest.cdr() : LispNil.INSTANCE, referenced, s);
			}
			else if (forms.get(i) instanceof LispCons form && s.syntaxOf(form, s.global) == Core.DEFINE_RECORD_TYPE) {
				runs = false;
			}
			else {
				collectNames(forms.get(i), referenced, s);
			}
			mentioned.add(referenced);
			for (String name : names) {
				SchemeLowering.Binding imported = s.global.bindings.get(name);
				if (imported instanceof SchemeLowering.Builtin || imported instanceof SchemeLowering.Constant
						|| s.libraryImports.contains(imported)) {
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
			LispVal value = switch (s.global.bindings.get(name)) {
				case SchemeLowering.Builtin builtin -> builtin.entry().function();
				case SchemeLowering.Constant constant -> constant.form();
				case SchemeLowering.Variable variable -> variable.symbol();
				case SchemeLowering.GlobalFunction function ->
					SchemeLowering.list(SchemeLowering.symbol("FUNCTION"), function.symbol());
				case SchemeLowering.GlobalPredicate predicate -> s.predicateValue(predicate);
				case null, default -> throw new IllegalStateException("not an imported value: " + name);
			};
			s.initialValues.put(SchemeLowering.symbol(s.prefix + name), value);
		}
		return early;
	}

	private static void collectNames(LispVal datum, Set<String> out, SchemeLowering s) {
		switch (datum) {
			case LispSymbol identifier -> out.add(s.name(identifier));
			case LispCons cons -> {
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					collectNames(cell.car(), out, s);
					rest = cell.cdr();
				}
				collectNames(rest, out, s);
			}
			case am.ik.rontolisp.LispArray array -> {
				for (LispVal element : array.data()) {
					collectNames(element, out, s);
				}
			}
			default -> {
			}
		}
	}

}
