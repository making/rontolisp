package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Binding forms and bodies of the Scheme lowering: {@code let}/{@code let*}/
 * {@code letrec}/{@code letrec*} and the {@code let-values} family, and the body lowering
 * that turns internal definitions into {@code letrec*} bindings around the whole body.
 *
 * <p>
 * One slice of {@link SchemeLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class SchemeBindingLowering {

	private SchemeBindingLowering() {
	}

	private record Bindings(List<LispSymbol> variables, List<LispVal> inits) {
	}

	record NamedLetBindings(List<LispSymbol> variables, List<LispVal> inits) {
	}

	static NamedLetBindings bindingsOf(SchemeLowering s, LispVal specs, LispCons form) {
		Bindings parsed = bindings(s, specs, form);
		return new NamedLetBindings(parsed.variables(), parsed.inits());
	}

	private static Bindings bindings(SchemeLowering s, LispVal specs, LispCons form) {
		List<LispSymbol> variables = new ArrayList<>();
		List<LispVal> inits = new ArrayList<>();
		for (LispVal spec : s.elements(specs, form)) {
			List<LispVal> binding = s.elements(spec, form);
			if (binding.size() != 2) {
				throw s.error("a binding is (variable init)", form);
			}
			variables.add(s.identifier(binding.get(0), form));
			inits.add(binding.get(1));
		}
		return new Bindings(variables, inits);
	}

	static LispVal let(SchemeLowering s, LispCons form, SchemeLowering.Context context) {
		List<LispVal> parts = s.elements(form, form);
		if (parts.size() >= 2 && parts.get(1) instanceof LispSymbol) {
			return SchemeLambdaLowering.namedLet(s, form, context);
		}
		if (parts.size() < 3) {
			throw s.error("malformed let", form);
		}
		Bindings bindings = bindings(s, parts.get(1), form);
		SchemeLowering.Scope inner = new SchemeLowering.Scope(context.scope());
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.variables().size(); i++) {
			LispVal init = s.value(bindings.inits().get(i), context.scope());
			pairs.add(SchemeLowering.list(s.bind(bindings.variables().get(i), inner), init));
		}
		return new LispCons(SchemeLowering.symbol("LET"), new LispCons(SchemeLowering.listOf(pairs),
				SchemeLowering.listOf(body(s, parts.subList(2, parts.size()), context.in(inner)))));
	}

	static LispVal letStar(SchemeLowering s, LispCons form, SchemeLowering.Context context) {
		List<LispVal> parts = s.elements(form, form);
		if (parts.size() < 3) {
			throw s.error("malformed let*", form);
		}
		Bindings bindings = bindings(s, parts.get(1), form);
		SchemeLowering.Scope inner = context.scope();
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.variables().size(); i++) {
			LispVal init = s.value(bindings.inits().get(i), inner);
			inner = new SchemeLowering.Scope(inner);
			pairs.add(SchemeLowering.list(s.bind(bindings.variables().get(i), inner), init));
		}
		return new LispCons(SchemeLowering.symbol("LET*"), new LispCons(SchemeLowering.listOf(pairs), SchemeLowering
			.listOf(body(s, parts.subList(2, parts.size()), context.in(new SchemeLowering.Scope(inner))))));
	}

	// letrec and letrec*: bind to nil, then assign in order -- the shape `labels` itself
	// lowers to (.kb/flet-labels.md), so mutual recursion works on every backend.
	static LispVal letrec(SchemeLowering s, LispCons form, SchemeLowering.Context context) {
		List<LispVal> parts = s.elements(form, form);
		if (parts.size() < 3) {
			throw s.error("malformed letrec", form);
		}
		Bindings bindings = bindings(s, parts.get(1), form);
		SchemeLowering.Scope inner = new SchemeLowering.Scope(context.scope());
		List<LispVal> pairs = new ArrayList<>();
		for (LispSymbol variable : bindings.variables()) {
			pairs.add(SchemeLowering.list(s.bind(variable, inner), LispNil.INSTANCE));
		}
		List<SchemeLowering.Member> candidates = new ArrayList<>();
		for (int i = 0; i < bindings.variables().size(); i++) {
			LispSymbol variable = bindings.variables().get(i);
			if (bindings.inits().get(i) instanceof LispCons lambda && s.syntaxOf(lambda, inner) == Core.LAMBDA) {
				List<LispVal> lambdaParts = s.elements(lambda, lambda);
				if (lambdaParts.size() >= 3) {
					candidates.add(new SchemeLowering.Member(lambda,
							new SchemeLowering.Definition(variable, lambdaParts.get(1),
									lambdaParts.subList(2, lambdaParts.size())),
							Objects.requireNonNull(s.lookup(variable, inner))));
				}
			}
		}
		Map<LispCons, SchemeLowering.Group> groups = SchemeGroupLowering.internalGroups(s, candidates, Set.of(), inner);
		List<LispVal> forms = new ArrayList<>();
		for (int i = 0; i < bindings.variables().size(); i++) {
			LispSymbol variable = bindings.variables().get(i);
			LispVal init = bindings.inits().get(i);
			SchemeLowering.Group group = init instanceof LispCons lambda ? groups.get(lambda) : null;
			if (group != null) {
				forms.addAll(SchemeGroupLowering.internalGroupDefinition(s, group,
						SchemeGroupLowering.member(group, init), inner, pairs));
			}
			else {
				forms.add(SchemeLowering.list(SchemeLowering.symbol("SETQ"), s.variableSymbol(variable, inner),
						SchemeLambdaLowering.recursiveValue(s, variable, init, inner)));
			}
		}
		forms.addAll(body(s, parts.subList(2, parts.size()), context.in(new SchemeLowering.Scope(inner))));
		return new LispCons(SchemeLowering.symbol("LET"),
				new LispCons(SchemeLowering.listOf(pairs), SchemeLowering.listOf(forms)));
	}

	static LispVal letValues(SchemeLowering s, LispCons form, SchemeLowering.Context context, boolean sequential) {
		List<LispVal> parts = s.elements(form, form);
		if (parts.size() < 3) {
			throw s.error("malformed let-values", form);
		}
		List<LispVal> clauses = s.elements(parts.get(1), form);
		SchemeLowering.Scope inner = new SchemeLowering.Scope(context.scope());
		// Parallel clauses bind temporaries first, so no init sees another clause's
		// variables; one clause (the common case) and let*-values bind directly.
		boolean direct = sequential || clauses.size() <= 1;
		List<ValuesClause> lowered = new ArrayList<>();
		List<LispVal> renames = new ArrayList<>();
		for (LispVal clauseDatum : clauses) {
			List<LispVal> clause = s.elements(clauseDatum, form);
			if (clause.size() != 2) {
				throw s.error("a let-values binding is (formals init)", form);
			}
			LispVal init = s.value(clause.get(1), direct ? inner : context.scope());
			SchemeLowering.Formals formals = s.formals(clause.get(0), form);
			if (direct) {
				inner = new SchemeLowering.Scope(inner);
			}
			List<LispSymbol> names = new ArrayList<>();
			for (LispSymbol formal : formals.all()) {
				if (direct) {
					names.add(s.bind(formal, inner));
				}
				else {
					LispSymbol temporary = s.fresh("V");
					names.add(temporary);
					renames.add(SchemeLowering.list(s.cl(formal), temporary));
				}
			}
			lowered.add(new ValuesClause(names, formals.rest() != null, init));
		}
		if (!direct) {
			inner = new SchemeLowering.Scope(inner);
			for (LispVal clauseDatum : clauses) {
				for (LispSymbol formal : s.formals(s.elements(clauseDatum, form).get(0), form).all()) {
					s.bind(formal, inner);
				}
			}
		}
		List<LispVal> body = body(s, parts.subList(2, parts.size()), context.in(new SchemeLowering.Scope(inner)));
		LispVal result = direct ? SchemeLowering.progn(body) : new LispCons(SchemeLowering.symbol("LET"),
				new LispCons(SchemeLowering.listOf(renames), SchemeLowering.listOf(body)));
		for (int i = lowered.size() - 1; i >= 0; i--) {
			result = lowered.get(i).around(result, s);
		}
		return result;
	}

	private record ValuesClause(List<LispSymbol> names, boolean rest, LispVal init) {

		LispVal around(LispVal body, SchemeLowering lowering) {
			if (!this.rest) {
				return SchemeLowering.list(SchemeLowering.symbol("MULTIPLE-VALUE-BIND"),
						SchemeLowering.listOf(new ArrayList<>(this.names)), this.init, body);
			}
			LispSymbol all = lowering.fresh("M");
			List<LispVal> pairs = new ArrayList<>();
			int last = this.names.size() - 1;
			for (int i = 0; i < last; i++) {
				pairs.add(SchemeLowering.list(this.names.get(i),
						SchemeLowering.list(SchemeLowering.symbol("NTH"), new LispInteger(i), all)));
			}
			pairs.add(SchemeLowering.list(this.names.get(last),
					SchemeLowering.list(SchemeLowering.symbol("NTHCDR"), new LispInteger(last), all)));
			return SchemeLowering.list(SchemeLowering.symbol("LET"),
					SchemeLowering.list(SchemeLowering.list(all,
							SchemeLowering.list(SchemeLowering.symbol("MULTIPLE-VALUE-LIST"), this.init))),
					SchemeLowering.list(SchemeLowering.symbol("LET"), SchemeLowering.listOf(pairs), body));
		}

	}

	/**
	 * Lowers a body: internal definitions become {@code letrec*} -- bound to nil around
	 * the whole body, assigned where they stand -- and the last form takes the context's
	 * destination.
	 */
	static List<LispVal> body(SchemeLowering s, List<LispVal> forms, SchemeLowering.Context context) {
		List<LispVal> flat = new ArrayList<>();
		for (LispVal form : forms) {
			spliceBodyBegins(s, form, context.scope(), flat);
		}
		if (flat.isEmpty()) {
			// Every caller checked its form has a body; (begin) splices to nothing.
			throw new IllegalArgumentException("an empty body");
		}
		SchemeLowering.Scope scope = context.scope();
		List<LispVal> pairs = new ArrayList<>();
		// An internal record type binds no variable at all: its names mean the hoisted
		// top-level procedures, called directly. Such a name may not be defined twice.
		Set<String> variables = new HashSet<>();
		Set<String> recordNames = new HashSet<>();
		// What a define-values binds: no tail-call group member.
		Set<String> valuesNames = new HashSet<>();
		for (LispVal form : flat) {
			if (form instanceof LispCons cons) {
				Core core = s.syntaxOf(cons, scope);
				if (core == Core.DEFINE) {
					LispSymbol name = s.definition(cons).name();
					SchemeRecordLowering.refuseAgainInBody(s, name, recordNames, cons);
					variables.add(s.name(name));
					pairs.add(SchemeLowering.list(s.bind(name, scope), LispNil.INSTANCE));
				}
				else if (core == Core.DEFINE_VALUES) {
					for (LispSymbol variable : s.formals(s.second(cons), cons).all()) {
						SchemeRecordLowering.refuseAgainInBody(s, variable, recordNames, cons);
						variables.add(s.name(variable));
						valuesNames.add(variable.name());
						pairs.add(SchemeLowering.list(s.bind(variable, scope), LispNil.INSTANCE));
					}
				}
				else if (core == Core.DEFINE_RECORD_TYPE) {
					for (SchemeRecordLowering.LocalProcedure procedure : SchemeRecordLowering.internalRecord(s, cons)
						.procedures()) {
						String name = s.name(procedure.identifier());
						if (variables.contains(name) || !recordNames.add(name)) {
							throw s.error("a body defines " + procedure.identifier().name() + " twice", cons);
						}
						scope.bindings.put(name, procedure.binding());
					}
				}
			}
		}
		List<SchemeLowering.Member> candidates = new ArrayList<>();
		for (LispVal form : flat) {
			if (form instanceof LispCons cons && s.syntaxOf(cons, scope) == Core.DEFINE) {
				SchemeLowering.Definition definition = s.definition(cons);
				if (definition.procedure() && definition.caseLambda() == null) {
					candidates.add(new SchemeLowering.Member(cons, definition,
							Objects.requireNonNull(s.lookup(definition.name(), scope))));
				}
			}
		}
		Map<LispCons, SchemeLowering.Group> groups = SchemeGroupLowering.internalGroups(s, candidates, valuesNames,
				scope);
		List<LispVal> lowered = new ArrayList<>();
		for (int i = 0; i < flat.size(); i++) {
			LispVal form = flat.get(i);
			boolean last = i == flat.size() - 1;
			Core core = form instanceof LispCons cons ? s.syntaxOf(cons, scope) : null;
			SchemeLowering.Group group = form instanceof LispCons cons ? groups.get(cons) : null;
			if (group != null) {
				lowered.addAll(SchemeGroupLowering.internalGroupDefinition(s, group,
						SchemeGroupLowering.member(group, form), scope, pairs));
			}
			else if (core == Core.DEFINE && form instanceof LispCons cons) {
				SchemeLowering.Definition definition = s.definition(cons);
				SchemeLowering.Binding self = s.assignedNames.contains(s.name(definition.name())) ? null
						: s.lookup(definition.name(), scope);
				lowered.add(SchemeLowering.inherit(cons,
						SchemeLowering.list(SchemeLowering.symbol("SETQ"), s.variableSymbol(definition.name(), scope),
								SchemeDefinitionLowering.definedValue(s, definition,
										new SchemeLowering.DefinedIn(cons, scope, self)))));
			}
			else if (core == Core.DEFINE_VALUES && form instanceof LispCons cons) {
				lowered.add(SchemeLowering.inherit(cons, s.defineValues(cons, scope)));
			}
			else if (core == Core.DEFINE_RECORD_TYPE) {
				// Defined at the top level already; a body ending in one answers the
				// unspecified object.
				if (last) {
					lowered.add(s.lower(SchemeLowering.CORE_UNSPECIFIED, context));
				}
			}
			else {
				lowered.add(s.lower(form, last ? context : SchemeLowering.Context.discarding(scope)));
			}
		}
		if (pairs.isEmpty()) {
			return lowered;
		}
		return List.of(new LispCons(SchemeLowering.symbol("LET"),
				new LispCons(SchemeLowering.listOf(pairs), SchemeLowering.listOf(lowered))));
	}

	private static void spliceBodyBegins(SchemeLowering s, LispVal datum, SchemeLowering.Scope scope,
			List<LispVal> out) {
		if (datum instanceof LispCons form && s.syntaxOf(form, scope) == Core.BEGIN && form.cdr() instanceof LispCons) {
			for (LispVal inner : s.elements(form.cdr(), form)) {
				spliceBodyBegins(s, inner, scope, out);
			}
		}
		else {
			out.add(datum);
		}
	}

}
