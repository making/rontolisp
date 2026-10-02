package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * Procedures and loops of the Scheme lowering: {@code lambda} lowering, the self tail
 * call that becomes a {@code tagbody} loop, a named {@code let} as a pure loop or a real
 * procedure, and the value of a variable that may be a procedure calling itself.
 *
 * <p>
 * One slice of {@link SchemeLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class SchemeLambdaLowering {

	private SchemeLambdaLowering() {
	}

	static LispVal lambda(SchemeLowering s, SchemeLowering.ProcedureSpec spec, SchemeLowering.Scope scope) {
		s.closures++;
		SchemeLowering.Lowered lowered = procedure(s, spec, scope);
		return new LispCons(SchemeLowering.symbol("LAMBDA"),
				new LispCons(lowered.lambdaList(), SchemeLowering.listOf(lowered.body())));
	}

	static SchemeLowering.Lowered procedure(SchemeLowering s, SchemeLowering.ProcedureSpec spec,
			SchemeLowering.Scope scope) {
		if (spec.self() != null && spec.selfName() != null && mentionsCall(spec.body(), spec.selfName().name())) {
			SchemeLowering.Lowered loop = selfLoop(s, spec, scope, false);
			if (loop != null) {
				return loop;
			}
		}
		SchemeLowering.Scope inner = new SchemeLowering.Scope(scope);
		List<LispSymbol> parameters = new ArrayList<>();
		for (LispSymbol formal : spec.formals().all()) {
			parameters.add(s.bind(formal, inner));
		}
		return new SchemeLowering.Lowered(SchemeLowering.lambdaList(parameters, spec.formals().rest() != null),
				SchemeBindingLowering.body(s, spec.body(), SchemeLowering.Context.of(new SchemeLowering.Scope(inner))));
	}

	// A procedure whose tail calls to itself jump: the parameters arrive in carriers and
	// the body runs inside (tagbody L ...), storing its value in a result variable. Null
	// when no call turned out to be a self tail call, so the plain shape is used.
	private static SchemeLowering.@Nullable Lowered selfLoop(SchemeLowering s, SchemeLowering.ProcedureSpec spec,
			SchemeLowering.Scope scope, boolean fresh) {
		SchemeLowering.Scope inner = new SchemeLowering.Scope(scope);
		List<LispSymbol> variables = new ArrayList<>();
		List<LispSymbol> carriers = new ArrayList<>();
		for (LispSymbol formal : spec.formals().all()) {
			variables.add(s.bind(formal, inner));
			carriers.add(s.fresh("C"));
		}
		boolean rest = spec.formals().rest() != null;
		SchemeLowering.Target target = new SchemeLowering.Target(Objects.requireNonNull(spec.self()),
				new SchemeLowering.LoopShape(s.fresh("L"), fresh ? carriers : variables, rest, !fresh), inner,
				spec.formals().all().stream().map(s::name).toList());
		target.clause = spec.clause();
		LispSymbol result = s.fresh("R");
		SchemeLowering.Exit exit = new SchemeLowering.Exit(result);
		int closuresBefore = s.closures;
		List<LispVal> statements = SchemeBindingLowering.body(s, spec.body(), SchemeLowering.Context
			.storing(new SchemeLowering.Scope(inner), new SchemeLowering.Destination(result, List.of(target), exit)));
		if (!target.used) {
			return null;
		}
		if (!fresh && (s.closures != closuresBefore || target.shadowed)) {
			return selfLoop(s, spec, scope, true);
		}
		List<LispVal> pairs = new ArrayList<>();
		if (!fresh) {
			for (int i = 0; i < variables.size(); i++) {
				pairs.add(SchemeLowering.list(variables.get(i), carriers.get(i)));
			}
		}
		pairs.add(SchemeLowering.list(result, LispNil.INSTANCE));
		LispVal loop = loopBody(new LoopBody(target, fresh ? variables : List.of(), carriers, statements));
		return new SchemeLowering.Lowered(SchemeLowering.lambdaList(carriers, rest), List.of(SchemeLowering.exiting(
				exit, SchemeLowering.list(SchemeLowering.symbol("LET"), SchemeLowering.listOf(pairs), loop, result))));
	}

	// The value of a variable that may be a procedure calling itself: a syntactic lambda
	// bound to a never-assigned variable gets its self tail calls turned into a loop.
	static LispVal recursiveValue(SchemeLowering s, LispSymbol variable, LispVal init, SchemeLowering.Scope scope) {
		if (init instanceof LispCons lambda && s.syntaxOf(lambda, scope) == Core.LAMBDA
				&& !s.assignedNames.contains(s.name(variable))) {
			List<LispVal> parts = s.elements(lambda, lambda);
			if (parts.size() >= 3) {
				return SchemeLowering
					.inherit(lambda,
							lambda(s,
									new SchemeLowering.ProcedureSpec(s.formals(parts.get(1), lambda),
											parts.subList(2, parts.size()), s.lookup(variable, scope), variable),
									scope));
			}
		}
		return s.value(init, scope);
	}

	static LispVal namedLet(SchemeLowering s, LispCons form, SchemeLowering.Context context) {
		List<LispVal> parts = s.elements(form, form);
		if (parts.size() < 4) {
			throw s.error("malformed named let", form);
		}
		LispSymbol loopName = (LispSymbol) parts.get(1);
		SchemeBindingLowering.NamedLetBindings bindings = SchemeBindingLowering.bindingsOf(s, parts.get(2), form);
		List<LispVal> inits = s.values(bindings.inits(), context.scope());
		return namedLet(s, new NamedLet(form, loopName, bindings.variables(), inits, parts.subList(3, parts.size())),
				context);
	}

	private static LispVal namedLet(SchemeLowering s, NamedLet loop, SchemeLowering.Context context) {
		LispSymbol loopName = loop.name();
		List<LispVal> inits = loop.inits();
		if (!s.assignedNames.contains(s.name(loopName))) {
			LispVal pure = pureLoop(s, loop, context, false);
			if (pure != null) {
				return pure;
			}
		}
		// The name escapes: a real procedure, bound letrec-style and called once.
		SchemeLowering.Scope inner = new SchemeLowering.Scope(context.scope());
		LispSymbol procedure = s.bind(loopName, inner);
		LispVal lambda = recursiveValue(s, loopName,
				new LispCons(SchemeLowering.CORE_LAMBDA, new LispCons(
						SchemeLowering.listOf(new ArrayList<>(loop.variables())), SchemeLowering.listOf(loop.body()))),
				inner);
		LispVal call = new LispCons(SchemeLowering.symbol("FUNCALL"),
				new LispCons(procedure, SchemeLowering.listOf(inits)));
		return s.leaf(SchemeLowering.list(SchemeLowering.symbol("LET"),
				SchemeLowering.list(SchemeLowering.list(procedure, LispNil.INSTANCE)),
				SchemeLowering.list(SchemeLowering.symbol("SETQ"), procedure, lambda), call), context);
	}

	record NamedLet(LispCons form, LispSymbol name, List<LispSymbol> variables, List<LispVal> inits,
			List<LispVal> body) {
	}

	/**
	 * A named {@code let} as {@code tagbody}/{@code go}, or {@code null} when the name is
	 * used as anything but a tail call. The loop variables are assigned in place -- the
	 * shape the backends' typed loops recognize -- unless the body creates a closure: a
	 * closure must capture THIS iteration's variables, so that loop rebinds them per
	 * iteration from carriers ({@code fresh}).
	 */
	private static @Nullable LispVal pureLoop(SchemeLowering s, NamedLet loop, SchemeLowering.Context context,
			boolean fresh) {
		SchemeLowering.LoopName binding = new SchemeLowering.LoopName();
		SchemeLowering.Scope inner = new SchemeLowering.Scope(context.scope());
		inner.bindings.put(s.name(loop.name()), binding);
		SchemeLowering.Scope bodyScope = new SchemeLowering.Scope(inner);
		List<LispSymbol> variables = new ArrayList<>();
		List<LispSymbol> carriers = new ArrayList<>();
		for (LispSymbol variable : loop.variables()) {
			variables.add(s.bind(variable, bodyScope));
			carriers.add(s.fresh("C"));
		}
		SchemeLowering.Target target = new SchemeLowering.Target(binding,
				new SchemeLowering.LoopShape(s.fresh("L"), fresh ? carriers : variables, false, !fresh), bodyScope,
				loop.variables().stream().map(s::name).toList());
		SchemeLowering.Destination outer = context.destination();
		LispSymbol result = outer != null ? outer.result() : s.fresh("R");
		List<SchemeLowering.Target> targets = new ArrayList<>(outer != null ? outer.targets() : List.of());
		targets.add(target);
		// An inner loop stores into the enclosing loop's destination, and leaves through
		// the enclosing loop's block.
		SchemeLowering.Exit exit = outer != null ? outer.exit() : new SchemeLowering.Exit(result);
		int closuresBefore = s.closures;
		List<LispVal> statements = SchemeBindingLowering.body(s, loop.body(),
				SchemeLowering.Context.storing(bodyScope, new SchemeLowering.Destination(result, targets, exit)));
		if (binding.escaped) {
			return null;
		}
		if (!fresh && target.used && (s.closures != closuresBefore || target.shadowed)) {
			return pureLoop(s, loop, context, true);
		}
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < variables.size(); i++) {
			pairs.add(SchemeLowering.list(fresh ? carriers.get(i) : variables.get(i), loop.inits().get(i)));
		}
		if (outer == null) {
			pairs.add(SchemeLowering.list(result, LispNil.INSTANCE));
		}
		List<LispVal> forms = new ArrayList<>();
		forms.add(loopBody(new LoopBody(target, fresh ? variables : List.of(), carriers, statements)));
		if (outer == null) {
			forms.add(result);
		}
		LispVal lowered = new LispCons(SchemeLowering.symbol("LET"),
				new LispCons(SchemeLowering.listOf(pairs), SchemeLowering.listOf(forms)));
		return outer == null ? SchemeLowering.exiting(exit, lowered) : lowered;
	}

	record LoopBody(SchemeLowering.Target target, List<LispSymbol> rebound, List<LispSymbol> carriers,
			List<LispVal> statements) {
	}

	// (tagbody L statements...), the statements inside a per-iteration (let ((v c)...))
	// when the loop rebinds; just the statements when nothing ever jumps.
	private static LispVal loopBody(LoopBody loop) {
		List<LispVal> statements = loop.statements();
		if (!loop.rebound().isEmpty()) {
			List<LispVal> pairs = new ArrayList<>();
			for (int i = 0; i < loop.rebound().size(); i++) {
				pairs.add(SchemeLowering.list(loop.rebound().get(i), loop.carriers().get(i)));
			}
			statements = List.of(new LispCons(SchemeLowering.symbol("LET"),
					new LispCons(SchemeLowering.listOf(pairs), SchemeLowering.listOf(statements))));
		}
		if (!loop.target().used) {
			return SchemeLowering.progn(statements);
		}
		return new LispCons(SchemeLowering.symbol("TAGBODY"),
				new LispCons(loop.target().label, SchemeLowering.listOf(statements)));
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

}
