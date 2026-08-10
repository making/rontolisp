package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.ArgumentShapes.Shape;
import org.jspecify.annotations.Nullable;

/**
 * Takes the {@code typecase} clauses no call in the program can select OUT of the
 * program, so the tree-shakers stop paying for what is behind them.
 *
 * <p>
 * The motivating case is the whole reason {@code .kb/wasm-export-no-wasi.md} used to
 * carry a "known standing line": clack's {@code clackup} dispatches on what it was handed
 * -- {@code (typecase app ((or pathname string) (eval-file app)) (otherwise app))} -- and
 * every Worker hands it a function. The pathname clause is statically reachable and
 * dynamically dead, so {@code CLACK::%LOAD-FILE}, {@code CLACK:EVAL-FILE} and
 * {@code PROBE-FILE} rode into every clack Worker module, along with the
 * {@code NoWasiFilesystemStubs} error string behind them. Name reachability cannot see
 * it; {@link ArgumentShapes} can, and the same predicate keeps the build's load-path
 * warning off the branch ({@link NoWasiLoadPathRefusals}).
 *
 * <h2>What makes a prune sound</h2>
 *
 * This one REWRITES the program, so unlike the warning pass it needs the whole program to
 * agree, not one call chain:
 * <ul>
 * <li>the shapes bound to a function's parameters are the JOIN over EVERY call site -- a
 * program that calls {@code (clackup "app.lisp")} anywhere keeps the clause;</li>
 * <li>a name taken as a VALUE ({@code #'clackup}, or any occurrence inside quoted data,
 * which is how a designator reaches {@code funcall}) has no known call sites at all, so
 * its parameters stay unknown;</li>
 * <li>a name with more than one definition, or one that is also a
 * {@code defmacro}/{@code defmethod}/{@code defgeneric}, is left alone;</li>
 * <li>the pass declines entirely when the program can resolve a function name out of
 * runtime data ({@link RuntimeNameProducers#anyNameResolvable}) -- {@code (eval (read))}
 * can call anything with anything, and no scan of the source can bound it.</li>
 * </ul>
 *
 * <p>
 * Over-counting a call site is harmless -- a shape that is not really passed only widens
 * the join toward {@code UNKNOWN}, which prunes less -- so the call scan is deliberately
 * dumb: any cons whose head names a known function counts. MISSING one would be the
 * unsafe direction, and that is what the escape rule above covers.
 *
 * <h2>Scope of the rewrite</h2>
 *
 * Only {@code typecase}/{@code etypecase} clauses, and only by DELETION -- the surviving
 * form is the same form with fewer clauses, which is why this needs no evaluation-order
 * reasoning. An {@code (if (typep x 'pathname) ...)} is left alone here even though the
 * warning pass skips it: rewriting a test means deciding what happens to the operand's
 * own evaluation, and the branch has no bytes of its own to save.
 *
 * <p>
 * Variable shapes flow through {@code let}/{@code let*}/{@code do}/{@code do*} bindings,
 * a {@code lambda}'s parameters (unknown -- whoever calls it decides), and {@code flet}
 * locals, whose parameters are joined over the call sites in the {@code flet} body. That
 * last one is not a refinement but the case itself: clack's {@code typecase} is inside a
 * local {@code buildapp}. A {@code labels} local is left unknown -- its siblings can call
 * it, so the {@code flet} body is not the whole call set.
 *
 * <p>
 * Gated on {@code OptimizeLevel.eliminatesDeadCode()} and wired into the JVM and wasm-GC
 * compile paths, next to the tree-shakers whose blindness it fixes
 * ({@code .kb/optimize-dead-code-elimination.md}). On the wasm path it runs AFTER
 * {@link NoWasiFilesystemStubs}, which is what closes the funcall-dispatch gate on a
 * clack Worker in the first place.
 */
public final class DeadTypeBranchPruner {

	private DeadTypeBranchPruner() {
	}

	/** The definition heads that make a name something other than one plain function. */
	private static final Set<String> OTHER_DEFINITION_HEADS = Set.of(LispNames.DEFMACRO, LispNames.DEFMETHOD,
			LispNames.DEFGENERIC);

	/** The heads whose second element is a {@code ((var init) ...)} binding list. */
	private static final Set<String> PAIR_BINDING_HEADS = Set.of(LispNames.LET, LispNames.LET_STAR, LispNames.DO,
			LispNames.DO_STAR);

	/** The heads that introduce a parameter list nothing here can constrain. */
	private static final Set<String> OPAQUE_PARAMETER_HEADS = Set.of(LispNames.LAMBDA, LispNames.ASYNC_LAMBDA);

	/**
	 * Heads whose subforms belong to a scope this walk is not in: a nested definition's
	 * body sees its own parameters, and a user MACRO's arguments are fragments the
	 * expansion may drop inside a binding of its own. Both are rewritten from the
	 * top-level scope, where the only shapes are the globals.
	 */
	private static final Set<String> FOREIGN_SCOPE_HEADS = Set.of(LispNames.DEFUN, LispNames.ASYNC_DEFUN,
			LispNames.DEFMACRO, LispNames.DEFMETHOD, LispNames.DEFGENERIC);

	/**
	 * Rewrites the program with every unselectable {@code typecase} clause removed.
	 * @param program the resolved, flattened top-level forms
	 * @return the rewritten program, or {@code program} itself when nothing was prunable
	 */
	public static List<LispVal> prune(List<LispVal> program) {
		if (RuntimeNameProducers.anyNameResolvable(program)) {
			return program;
		}
		Map<String, Shape> returns = ArgumentShapes.returnShapes(program);
		Map<String, Shape> globals = ArgumentShapes.globals(program, returns);
		Map<String, LispVal> lambdaLists = functionLambdaLists(program);
		if (lambdaLists.isEmpty()) {
			return program;
		}
		Uses uses = new Uses(Set.copyOf(lambdaLists.keySet()), globals, returns);
		program.forEach(uses::scan);
		lambdaLists.keySet().removeAll(uses.escaped);
		Pruner pruner = new Pruner(globals, returns, uses.callShapes, macroNames(program));
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			LispVal rewritten = pruner.topLevel(form, lambdaLists);
			changed |= rewritten != form;
			out.add(rewritten);
		}
		return changed ? out : program;
	}

	/**
	 * The lambda list of every name defined by exactly ONE {@code defun} and nothing
	 * else. A second definition, or a macro/method of the same name, means the call sites
	 * do not decide one body's parameters.
	 */
	private static Map<String, LispVal> functionLambdaLists(List<LispVal> program) {
		Map<String, LispVal> lambdaLists = new HashMap<>();
		Set<String> excluded = new HashSet<>();
		for (LispVal form : program) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispSymbol name)) {
				continue;
			}
			if (OTHER_DEFINITION_HEADS.contains(head.name())) {
				excluded.add(name.name());
			}
			else if ((LispNames.DEFUN.equals(head.name()) || LispNames.ASYNC_DEFUN.equals(head.name()))
					&& rest.cdr() instanceof LispCons afterName
					&& lambdaLists.put(name.name(), afterName.car()) != null) {
				excluded.add(name.name());
			}
		}
		lambdaLists.keySet().removeAll(excluded);
		return lambdaLists;
	}

	/**
	 * The whole-program census: which names escape as values, and what every call site
	 * says about the arguments of the ones that do not.
	 */
	private static final class Uses {

		private final Set<String> functions;

		private final Map<String, Shape> globals;

		private final Map<String, Shape> returns;

		private final Set<String> escaped = new HashSet<>();

		private final Map<String, List<Shape>> callShapes = new HashMap<>();

		private Uses(Set<String> functions, Map<String, Shape> globals, Map<String, Shape> returns) {
			this.functions = functions;
			this.globals = globals;
			this.returns = returns;
		}

		private void scan(LispVal form) {
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol head && cons.cdr() instanceof LispCons rest) {
				if (LispNames.QUOTE.equals(head.name())) {
					// A quoted symbol is how a designator reaches funcall/apply, so every
					// name in the datum is a name something could call with anything.
					collectSymbols(rest.car(), this.escaped);
					return;
				}
				if (LispNames.FUNCTION.equals(head.name()) && rest.car() instanceof LispSymbol referenced) {
					this.escaped.add(referenced.name());
					return;
				}
				if (this.functions.contains(head.name())) {
					this.callShapes.merge(head.name(), this.shapes(rest), Uses::join);
				}
			}
			this.scan(cons.car());
			LispVal rest = cons.cdr();
			while (rest instanceof LispCons cell) {
				this.scan(cell.car());
				rest = cell.cdr();
			}
		}

		/** What one call site states about each argument, from the top-level scope. */
		private List<Shape> shapes(LispVal tail) {
			List<Shape> shapes = new ArrayList<>();
			LispVal rest = tail;
			while (rest instanceof LispCons cons) {
				shapes.add(ArgumentShapes.of(cons.car(), this.globals, this.returns));
				rest = cons.cdr();
			}
			return shapes;
		}

		/** Two call sites agree about an argument, or nothing is known about it. */
		private static List<Shape> join(List<Shape> a, List<Shape> b) {
			List<Shape> joined = new ArrayList<>(Math.max(a.size(), b.size()));
			for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
				joined.add(ArgumentShapes.join(i < a.size() ? a.get(i) : Shape.UNKNOWN,
						i < b.size() ? b.get(i) : Shape.UNKNOWN));
			}
			return joined;
		}

	}

	/** Every symbol anywhere in a tree. */
	private static void collectSymbols(LispVal form, Set<String> out) {
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
	 * A {@code flet}'s local functions while its body is being rewritten: what they are,
	 * and what the body turns out to call them with.
	 */
	private static final class LocalScope {

		private final Map<String, LispVal> lambdaLists;

		private final Map<String, List<Shape>> callShapes = new HashMap<>();

		private LocalScope(Map<String, LispVal> lambdaLists) {
			this.lambdaLists = lambdaLists;
		}

	}

	/** The lexical state of the rewrite at one point. */
	private record Env(Map<String, Shape> vars, Set<String> shadowed, List<LocalScope> locals) {

		private Env withVars(Map<String, Shape> extra) {
			Map<String, Shape> merged = new HashMap<>(this.vars);
			extra.forEach((name, shape) -> merged.put(name, this.shadowed.contains(name) ? Shape.UNKNOWN : shape));
			return new Env(merged, this.shadowed, this.locals);
		}

		private Env withLocal(LocalScope scope) {
			List<LocalScope> nested = new ArrayList<>(this.locals);
			nested.add(scope);
			return new Env(this.vars, this.shadowed, nested);
		}

		private @Nullable LocalScope localFor(String name) {
			for (int i = this.locals.size() - 1; i >= 0; i--) {
				if (this.locals.get(i).lambdaLists.containsKey(name)) {
					return this.locals.get(i);
				}
			}
			return null;
		}

	}

	/** The rewrite itself. */
	private static final class Pruner {

		private final Map<String, Shape> globals;

		private final Map<String, Shape> returns;

		private final Map<String, List<Shape>> callShapes;

		private final Set<String> macros;

		private Pruner(Map<String, Shape> globals, Map<String, Shape> returns, Map<String, List<Shape>> callShapes,
				Set<String> macros) {
			this.globals = globals;
			this.returns = returns;
			this.callShapes = callShapes;
			this.macros = macros;
		}

		/**
		 * A top-level form: a {@code defun} whose call sites are known is rewritten with
		 * its parameters bound to them, everything else with the globals alone.
		 */
		private LispVal topLevel(LispVal form, Map<String, LispVal> lambdaLists) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& (LispNames.DEFUN.equals(head.name()) || LispNames.ASYNC_DEFUN.equals(head.name()))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& rest.cdr() instanceof LispCons afterName && lambdaLists.containsKey(name.name())) {
				List<Shape> shapes = this.callShapes.getOrDefault(name.name(), List.of());
				Env env = this.bodyEnv(afterName.cdr(), afterName.car(), shapes, this.rootEnv());
				return LispCons.rebuilt(cons, head, LispCons.rebuilt(rest, name,
						LispCons.rebuilt(afterName, afterName.car(), this.forms(afterName.cdr(), env))));
			}
			return this.form(form, this.rootEnv());
		}

		private Env rootEnv() {
			return new Env(this.globals, Set.of(), List.of());
		}

		/**
		 * The state inside a function body: its own shadow census, then its parameters.
		 */
		private Env bodyEnv(LispVal bodyTail, @Nullable LispVal lambdaList, List<Shape> shapes, Env outer) {
			List<LispVal> forms = new ArrayList<>();
			LispVal rest = bodyTail;
			while (rest instanceof LispCons cons) {
				forms.add(cons.car());
				rest = cons.cdr();
			}
			Set<String> shadowed = new HashSet<>(outer.shadowed());
			shadowed.addAll(ArgumentShapes.shadowedNames(forms));
			return new Env(outer.vars(), shadowed, outer.locals()).withVars(ArgumentShapes.bind(lambdaList, shapes));
		}

		private LispVal form(LispVal val, Env env) {
			if (!(val instanceof LispCons cons)) {
				return val;
			}
			if (cons.car() instanceof LispSymbol head && cons.cdr() instanceof LispCons rest) {
				String name = head.name();
				if (LispNames.QUOTE.equals(name)) {
					return cons;
				}
				if (FOREIGN_SCOPE_HEADS.contains(name) || this.macros.contains(name)) {
					Env root = this.rootEnv();
					return LispCons.rebuilt(cons, head, this.forms(cons.cdr(), root));
				}
				if (OPAQUE_PARAMETER_HEADS.contains(name)) {
					// (lambda (x) body...): whoever receives it decides the arguments.
					Env inner = this.bodyEnv(rest.cdr(), rest.car(), List.of(), env);
					return LispCons.rebuilt(cons, head,
							LispCons.rebuilt(rest, rest.car(), this.forms(rest.cdr(), inner)));
				}
				if (LispNames.TYPECASE.equals(name) || LispNames.ETYPECASE.equals(name)) {
					return this.typecase(cons, head, rest, env);
				}
				if (PAIR_BINDING_HEADS.contains(name)) {
					return this.bindingForm(cons, head, rest, env);
				}
				if (LispNames.FLET.equals(name) || LispNames.LABELS.equals(name)) {
					return this.localFunctions(cons, head, rest, LispNames.FLET.equals(name), env);
				}
				LocalScope scope = env.localFor(name);
				if (scope != null) {
					scope.callShapes.merge(name, this.shapes(rest, env), Uses::join);
				}
			}
			return LispCons.rebuilt(cons, this.form(cons.car(), env), this.forms(cons.cdr(), env));
		}

		/** Rewrites every element of a list, improper tail included. */
		private LispVal forms(LispVal tail, Env env) {
			if (!(tail instanceof LispCons cons)) {
				return tail;
			}
			return LispCons.rebuilt(cons, this.form(cons.car(), env), this.forms(cons.cdr(), env));
		}

		private List<Shape> shapes(LispVal tail, Env env) {
			List<Shape> shapes = new ArrayList<>();
			LispVal rest = tail;
			while (rest instanceof LispCons cons) {
				shapes.add(ArgumentShapes.of(cons.car(), env.vars(), this.returns));
				rest = cons.cdr();
			}
			return shapes;
		}

		/**
		 * {@code (typecase key (type body...) ...)}: the clauses the key's shape rules
		 * out are deleted, and what they held goes with them.
		 */
		private LispVal typecase(LispCons cons, LispSymbol head, LispCons rest, Env env) {
			Shape shape = ArgumentShapes.of(rest.car(), env.vars(), this.returns);
			LispVal key = this.form(rest.car(), env);
			List<LispVal> kept = new ArrayList<>();
			boolean dropped = false;
			LispVal clauses = rest.cdr();
			while (clauses instanceof LispCons cell) {
				if (cell.car() instanceof LispCons clause && !ArgumentShapes.maySatisfy(shape, clause.car())) {
					dropped = true;
				}
				else {
					kept.add(cell.car() instanceof LispCons clause
							? LispCons.rebuilt(clause, clause.car(), this.forms(clause.cdr(), env)) : cell.car());
				}
				clauses = cell.cdr();
			}
			if (!dropped) {
				return LispCons.rebuilt(cons, head, LispCons.rebuilt(rest, key, this.forms(rest.cdr(), env)));
			}
			List<LispVal> parts = new ArrayList<>(kept.size() + 2);
			parts.add(head);
			parts.add(key);
			parts.addAll(kept);
			return SourceProvenance.inherit(cons, list(parts));
		}

		/** {@code (let ((var init) ...) body...)} and its three siblings. */
		private LispVal bindingForm(LispCons cons, LispSymbol head, LispCons rest, Env env) {
			boolean sequential = LispNames.LET_STAR.equals(head.name()) || LispNames.DO_STAR.equals(head.name());
			boolean stepped = LispNames.DO.equals(head.name()) || LispNames.DO_STAR.equals(head.name());
			Map<String, Shape> bound = new HashMap<>();
			Env evalEnv = env;
			List<LispVal> rewrittenBindings = new ArrayList<>();
			LispVal bindings = rest.car();
			while (bindings instanceof LispCons cell) {
				LispVal binding = cell.car();
				if (binding instanceof LispCons pair) {
					rewrittenBindings.add(LispCons.rebuilt(pair, pair.car(), this.forms(pair.cdr(), evalEnv)));
					LispVal init = pair.cdr() instanceof LispCons initCell ? initCell.car() : null;
					if (pair.car() instanceof LispSymbol var) {
						bound.put(var.name(), stepped || init == null ? Shape.UNKNOWN
								: ArgumentShapes.of(init, evalEnv.vars(), this.returns));
					}
				}
				else {
					rewrittenBindings.add(binding);
					if (binding instanceof LispSymbol var) {
						bound.put(var.name(), Shape.UNKNOWN);
					}
				}
				if (sequential) {
					evalEnv = env.withVars(bound);
				}
				bindings = cell.cdr();
			}
			Env inner = env.withVars(bound);
			LispVal newBindings = bindings instanceof LispNil && rest.car() instanceof LispCons original
					? LispCons.rebuiltList(original, rewrittenBindings) : rest.car();
			return LispCons.rebuilt(cons, head, LispCons.rebuilt(rest, newBindings, this.forms(rest.cdr(), inner)));
		}

		/**
		 * {@code (flet ((name (args) body...) ...) body...)}: the body is rewritten
		 * first, because rewriting it is what discovers the shapes each local is called
		 * with.
		 */
		private LispVal localFunctions(LispCons cons, LispSymbol head, LispCons rest, boolean collecting, Env env) {
			Map<String, LispVal> lambdaLists = new HashMap<>();
			if (collecting) {
				LispVal locals = rest.car();
				while (locals instanceof LispCons cell) {
					if (cell.car() instanceof LispCons local && local.car() instanceof LispSymbol name
							&& local.cdr() instanceof LispCons afterName) {
						lambdaLists.put(name.name(), afterName.car());
					}
					locals = cell.cdr();
				}
			}
			LocalScope scope = new LocalScope(lambdaLists);
			LispVal newBody = this.forms(rest.cdr(), env.withLocal(scope));
			List<LispVal> rewrittenLocals = new ArrayList<>();
			LispVal locals = rest.car();
			while (locals instanceof LispCons cell) {
				LispVal rewritten = cell.car();
				if (cell.car() instanceof LispCons local && local.car() instanceof LispSymbol name
						&& local.cdr() instanceof LispCons afterName) {
					List<Shape> shapes = scope.callShapes.getOrDefault(name.name(), List.of());
					Env inner = this.bodyEnv(afterName.cdr(), afterName.car(), shapes, env);
					rewritten = LispCons.rebuilt(local, name,
							LispCons.rebuilt(afterName, afterName.car(), this.forms(afterName.cdr(), inner)));
				}
				rewrittenLocals.add(rewritten);
				locals = cell.cdr();
			}
			LispVal newLocals = locals instanceof LispNil && rest.car() instanceof LispCons original
					? LispCons.rebuiltList(original, rewrittenLocals) : rest.car();
			return LispCons.rebuilt(cons, head, LispCons.rebuilt(rest, newLocals, newBody));
		}

	}

	/** Every name the program defines as a macro. */
	private static Set<String> macroNames(List<LispVal> program) {
		Set<String> names = new HashSet<>();
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFMACRO.equals(head.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name) {
				names.add(name.name());
			}
		}
		return names;
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
