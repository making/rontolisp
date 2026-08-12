package am.ik.rontolisp.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.ArgumentShapes.Shape;
import org.jspecify.annotations.Nullable;

/**
 * Names, at BUILD time, the {@code --no-wasi} primitives a module can reach while it
 * LOADS -- the refusals that kill the instance during {@code _initialize} (or, on a
 * reactor component, during instantiation) rather than at a call the caller could catch.
 *
 * <p>
 * The refusals themselves are call-time Lisp conditions
 * ({@code .kb/wasm-export-no-wasi.md} documents each one and why it refuses), which is
 * right for a call SITE: the site may be dead code. Reached from a TOP-LEVEL form there
 * is nothing to catch them and no output to report themselves in, so what the host sees
 * is a bare {@code RuntimeError: unreachable} naming nobody. Every one of those is a fact
 * the build already knows, so the build says it.
 *
 * <p>
 * <strong>The clock is why this is not simply "list the refusals"</strong>: since a host
 * can hand a time in through {@code __ronto_set_time}, a program that reads the clock
 * from a top-level form is perfectly loadable -- on a host that sets it first. That is a
 * build-time HOST OBLIGATION, not a build-time refusal, and it is exactly what nobody
 * could discover without running the module. Entropy and {@code --host-random} have the
 * same shape.
 *
 * <h2>What "the load path" means here</h2>
 *
 * The top-level forms, plus the bodies of the functions they call, transitively. Five
 * rules decide it, each chosen so the line is worth reading rather than merely true:
 * <ul>
 * <li><strong>A function VALUE is not a call.</strong> {@code #'app} handed to
 * {@code lack:builder} does not put {@code app}'s body on the load path -- on a reactor
 * that body is what the EXPORT runs, and following it would flag every handler in the
 * program. Only operator position (and an immediately-applied {@code (lambda ...)}) is an
 * edge, which is what keeps this quiet for a primitive only an export can reach.</li>
 * <li><strong>A caught refusal is not a load-time death.</strong> Everything except
 * standard input signals a CATCHABLE condition, so a site inside a {@code handler-case}
 * protected form or an {@code ignore-errors} is a program that already handles it --
 * upstream local-time opens {@code /etc/localtime} from a top-level form and falls back
 * to UTC when it cannot. {@code fd_read} traps instead of signalling, so standard input
 * is reported wherever it is.</li>
 * <li><strong>A slot default is load-time.</strong> A {@code defstruct}/{@code defclass}
 * initform is evaluated per construction, not at definition -- but it is exactly the
 * shape a name-based call graph cannot reach (upstream lack gets to
 * {@code (get-universal-time)} in a {@code cookie-state} slot default through
 * {@code find-middleware}, i.e. {@code intern} + {@code symbol-value}), and a library
 * that builds one while it loads is the case this exists for. The line names the
 * definition, so a reader can tell the two apart.</li>
 * <li><strong>A {@code with-open-file} body is not walked.</strong>
 * {@link NoWasiFilesystemStubs} rewrites the form to {@code (progn path (error ...))}
 * right after this runs, so the body is not in the module either -- walking it would
 * report clack's {@code (read in nil eof)} as a standard-input trap the module does not
 * contain.</li>
 * <li><strong>A branch the ARGUMENTS rule out is not reachable.</strong> A call edge
 * carries the {@link ArgumentShapes.Shape}s of the actual arguments and binds them to the
 * callee's parameters, so a {@code typecase}/{@code typep} branch whose type a known
 * shape cannot have is skipped. This is what retired the standing clack line: every
 * Worker calls {@code (clack:clackup #'app ...)}, {@code clackup} dispatches on
 * {@code (typecase app ((or pathname string) (eval-file app)) (otherwise app))}, and a
 * function is neither -- the file loader behind that clause was true statically and dead
 * dynamically, in EVERY clack program. The same lattice takes the branch out of the
 * module in {@link DeadTypeBranchPruner}.</li>
 * </ul>
 *
 * <h2>Why a shape can only ever silence a FALSE line</h2>
 *
 * A wrong narrowing is a missed refusal -- the failure mode the whole pass exists to end
 * -- so a shape is read only where the call site states it syntactically ({@code #'f}, a
 * literal {@code (lambda ...)}, a literal, a quoted datum) and everything else is
 * {@code UNKNOWN}, which satisfies every type and therefore prunes nothing. Three
 * consequences are load-bearing:
 * <ul>
 * <li>the memoization keys on the SHAPES as well as the name and the guard, or the first
 * call edge's arguments would silently answer for the second;</li>
 * <li>a {@code flet}/{@code labels} body is walked at its CALL SITES, with the shapes
 * bound -- clack's {@code typecase} is inside a local {@code buildapp}, so a local
 * function walked once with unknown parameters would keep the line. A local whose name is
 * taken as a value ({@code #'buildapp}) is additionally walked with everything unknown,
 * and one that is never called at all is not walked, which is the same "a value is not a
 * call" rule one level down;</li>
 * <li>a name the region REBINDS in a form this pass does not model scope for (a
 * {@code dolist} variable, a {@code loop} {@code with}, a {@code setq} target, ...) is
 * dropped to {@code UNKNOWN} for that whole region, so a shadow can never carry a
 * caller's shape into a binding that has nothing to do with it.</li>
 * </ul>
 *
 * <p>
 * WASM {@code --no-wasi} on the GC backend only, which is where the refusal set of
 * {@code .kb/wasm-export-no-wasi.md} lives; {@code --no-gc} answers a different (much
 * smaller) subset of the language and would need a table of its own.
 */
public final class NoWasiLoadPathRefusals {

	private NoWasiLoadPathRefusals() {
	}

	/** What a reached primitive costs, and the way out where there is one. */
	enum Kind {

		/** The clock: a host obligation, not a refusal, on a build that has the hook. */
		CLOCK,
		/** Cryptographic entropy: refused unless {@code --host-random} routes it. */
		ENTROPY,
		/** Standard input: the one refusal that traps, so no handler can catch it. */
		STDIN,
		/** {@code sleep}: no interval can elapse without a clock that moves. */
		SLEEP,
		/** The file-opening forms: a reactor has no filesystem. */
		FILESYSTEM,
		/**
		 * {@code fetch} under {@code --host-fetch}: a host obligation, not a refusal -- a
		 * SUSPENDING {@code env.fetch} may only run on a stack entered through
		 * {@code WebAssembly.promising}, which {@code _initialize} is not.
		 */
		FETCH,
		/**
		 * A host import declared {@code :async t} ({@link SuspendingImports}): like
		 * {@link #STDIN} a suspension is a TRAP, not a condition, so no handler covers
		 * it. Never produced by {@link #report}; only the {@code SuspendingImports} entry
		 * points ask for it.
		 */
		SUSPEND

	}

	/**
	 * One reached primitive: what it is, where it is, and how the load path got there.
	 */
	record Found(String operator, Kind kind, LispVal site, String origin) {
	}

	/**
	 * One function body: the definition form that carries its source position, the lambda
	 * list a call edge binds its argument shapes to, and the forms themselves.
	 */
	private record Body(LispVal anchor, @Nullable LispVal lambdaList, List<LispVal> forms) {
	}

	/**
	 * Forms that run at load time: what reached them, whether a handler covers them, and
	 * what the call edge said about the parameters.
	 */
	private record Region(Body body, String origin, boolean guarded, Map<String, Shape> shapes) {
	}

	/** One call edge: the callee and what the site states about its arguments. */
	private record Call(String name, List<Shape> argShapes) {
	}

	/** The memoization key: the same body under different shapes is a different walk. */
	private record Visit(String name, List<Shape> argShapes) {
	}

	/**
	 * A {@code flet}/{@code labels} function: its lambda list, its body, and the lexical
	 * environment it closes over. {@code scope} is the local-function map visible inside
	 * it -- for {@code labels} that map contains the function itself, which is why it is
	 * held by reference and filled in after.
	 */
	private record Local(LispVal lambdaList, LispVal bodyTail, Map<String, Shape> env, Map<String, Local> scope) {
	}

	/**
	 * The walk state at one point in a region: whether a handler covers it, the innermost
	 * enclosing form whose source position is known -- the position a refusal reports
	 * when its own cons has lost one (a pass that rebuilt a cons drops it, and that drops
	 * the whole subtree's, see {@code .kb/source-positions.md}) -- and the lexical
	 * environment: variable shapes plus the local functions in scope.
	 */
	private record At(boolean guarded, LispVal located, Map<String, Shape> env, Map<String, Local> locals) {
	}

	/**
	 * The definition heads whose body only runs when something CALLS them. This pass runs
	 * BEFORE the async lowering, so {@code rontolisp:async-defun} is still spelled as
	 * itself -- and it is a defun whose body runs at the call (eagerly, but at the call).
	 */
	private static final Set<String> DEFERRED_HEADS = Set.of(LispNames.DEFUN, LispNames.DEFMACRO, LispNames.DEFMETHOD,
			LispNames.DEFGENERIC, LispNames.ASYNC_DEFUN_QUALIFIED);

	/** The heads whose name makes a better origin label than "a top-level form". */
	private static final Set<String> NAMED_HEADS = Set.of(LispNames.DEFVAR, LispNames.DEFPARAMETER,
			LispNames.DEFCONSTANT, LispNames.DEFSTRUCT, LispNames.DEFCLASS, LispNames.DEFINE_CONDITION);

	/** {@code (head (name init...) ...)}: each element's head binds, it does not call. */
	private static final Set<String> BINDING_HEADS = Set.of(LispNames.LET, LispNames.LET_STAR, LispNames.DO,
			LispNames.DO_STAR);

	/**
	 * {@code (head ((name (args) body...)) body...)}: a local name is not a global one.
	 */
	private static final Set<String> LOCAL_FUNCTION_HEADS = Set.of(LispNames.FLET, LispNames.LABELS, LispNames.MACROLET,
			LispNames.SYMBOL_MACROLET);

	/**
	 * The build lines for every refusing primitive this program's load path reaches.
	 * @param program the resolved, flattened top-level forms, before
	 * {@link NoWasiFilesystemStubs#rewrite(List)} has replaced the file-opening forms
	 * @param hostRandom whether {@code --host-random} routes {@code random_get} at a host
	 * import, which makes the entropy API sound again
	 * @param hostFetch whether {@code --host-fetch} routes {@code rontolisp:fetch} at the
	 * {@code env.fetch} host import -- a load-path fetch then states the synchronous-host
	 * obligation (a suspending import cannot serve {@code _initialize}); without the flag
	 * a {@code --no-wasi} fetch is a compile error, so there is nothing to report
	 * @param reactorComponent whether this is the {@code --component --no-wasi} reactor,
	 * which carries no host hooks at all (its top level runs at instantiation, so there
	 * is no window before the first read)
	 * @return one line per primitive, position prefix included, in the order the load
	 * path reaches them; empty when it reaches none
	 */
	public static List<String> report(List<LispVal> program, boolean hostRandom, boolean hostFetch,
			boolean reactorComponent) {
		Map<String, Found> found = walk(program, hostRandom, hostFetch, Set.of(), null);
		List<String> lines = new ArrayList<>(found.size());
		found.values().forEach(f -> lines.add(line(f, reactorComponent)));
		return lines;
	}

	/**
	 * The walk itself, seeded either with the LOAD PATH (every non-deferred top-level
	 * form; {@code rootFunction} null) or with one function's bodies under unknown
	 * arguments ({@code rootFunction} set) -- the latter is how {@link SuspendingImports}
	 * asks which exports can reach a suspending import.
	 * @param program the resolved, flattened top-level forms
	 * @param hostRandom whether {@code --host-random} routes {@code random_get}
	 * @param hostFetch whether {@code --host-fetch} routes {@code rontolisp:fetch}
	 * @param suspendingImports the {@code :async t} host-import names; a call of one is a
	 * {@link Kind#SUSPEND} finding (reported through a handler, like {@link Kind#STDIN}
	 * -- a suspension traps rather than signalling)
	 * @param rootFunction the function whose bodies seed the walk, or {@code null} for
	 * the load path
	 * @return the findings, keyed by reported operator, in reach order
	 */
	static Map<String, Found> walk(List<LispVal> program, boolean hostRandom, boolean hostFetch,
			Set<String> suspendingImports, @Nullable String rootFunction) {
		Map<String, List<Body>> definitions = collectDefinitions(program);
		// (clackup *app* ...) over a (defvar *app* (make-instance 'ningle:app)) states
		// its argument as plainly as (clackup #'app) does, one indirection further out.
		Map<String, Shape> returns = ArgumentShapes.returnShapes(program);
		Map<String, Shape> globals = ArgumentShapes.globals(program, returns);
		Map<String, Found> found = new LinkedHashMap<>();
		// (name, argument shapes) -> whether the visit that claimed it was guarded. A
		// later UNGUARDED path to the same function is a different answer, and so is the
		// same function under different arguments, so either is walked again.
		Map<Visit, Boolean> visited = new HashMap<>();
		Deque<Region> queue = new ArrayDeque<>();
		if (rootFunction == null) {
			for (LispVal form : program) {
				if (!isDeferredDefinition(form)) {
					queue.addLast(new Region(new Body(form, null, List.of(form)), originLabel(form), false, Map.of()));
				}
			}
		}
		else {
			// The caller decides the arguments, so nothing is known about them.
			for (Body body : definitions.getOrDefault(rootFunction, List.of())) {
				queue.addLast(new Region(body, rootFunction, false, Map.of()));
			}
		}
		// Breadth first, so the path a line reports is the shortest one that reaches it.
		while (!queue.isEmpty()) {
			Region region = queue.removeFirst();
			Map<Call, Boolean> called = new LinkedHashMap<>();
			Set<String> shadowed = ArgumentShapes.shadowedNames(region.body().forms());
			Scan scan = new Scan(hostRandom, hostFetch, suspendingImports, region.origin(), found, called, shadowed,
					returns);
			Map<String, Shape> env = new HashMap<>(globals);
			env.putAll(region.shapes());
			At at = new At(region.guarded(), region.body().anchor(), scan.narrow(env), Map.of());
			region.body().forms().forEach(form -> scan.form(form, at));
			called.forEach((call, guarded) -> {
				Visit visit = new Visit(call.name(), call.argShapes());
				Boolean claimed = visited.get(visit);
				if (claimed == null || (claimed && !guarded)) {
					visited.put(visit, guarded);
					for (Body body : definitions.getOrDefault(call.name(), List.of())) {
						queue.addLast(new Region(body, region.origin() + " -> " + call.name(), guarded,
								ArgumentShapes.bind(body.lambdaList(), call.argShapes())));
					}
				}
			});
		}
		return found;
	}

	/**
	 * Every named function body in the program, by name. A {@code defmethod} is keyed by
	 * its GENERIC function name and every method of that name is a body of it: the call
	 * site names the generic, and which method a dispatch picks is a runtime fact.
	 */
	private static Map<String, List<Body>> collectDefinitions(List<LispVal> program) {
		Map<String, List<Body>> definitions = new HashMap<>();
		for (LispVal form : program) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
				continue;
			}
			List<LispVal> parts = cons.toList();
			String name = definedName(parts);
			if (name == null) {
				continue;
			}
			int body = switch (head.name()) {
				case LispNames.DEFUN, LispNames.DEFMACRO, LispNames.ASYNC_DEFUN_QUALIFIED -> 3;
				// (defmethod name qualifier* (specialized-ll) body...)
				case LispNames.DEFMETHOD -> bodyStart(parts, 2);
				// A defgeneric has no body of its own; its :method clauses are below.
				default -> parts.size();
			};
			if (body <= parts.size()) {
				define(definitions, name, cons, body >= 1 ? parts.get(body - 1) : null,
						parts.subList(body, parts.size()));
			}
			if (LispNames.DEFGENERIC.equals(head.name())) {
				// (defgeneric name (ll) (:method qualifier* (ll) body...) ...): a :method
				// clause is an ordinary body of the generic, spelled inside the
				// definition.
				for (LispVal option : parts.subList(Math.min(3, parts.size()), parts.size())) {
					if (option instanceof LispCons optCons && optCons.car() instanceof LispSymbol optName
							&& ":METHOD".equals(optName.name()) && optCons.isProperList()) {
						List<LispVal> optParts = optCons.toList();
						int start = bodyStart(optParts, 1);
						if (start <= optParts.size()) {
							define(definitions, name, optCons, optParts.get(start - 1),
									optParts.subList(start, optParts.size()));
						}
					}
				}
			}
		}
		return definitions;
	}

	private static void define(Map<String, List<Body>> definitions, String name, LispVal anchor,
			@Nullable LispVal lambdaList, List<LispVal> forms) {
		if (!forms.isEmpty()) {
			definitions.computeIfAbsent(name, k -> new ArrayList<>()).add(new Body(anchor, lambdaList, forms));
		}
	}

	/**
	 * The name a deferred definition form defines, or {@code null} when it is not one.
	 */
	private static @Nullable String definedName(List<LispVal> parts) {
		if (parts.size() < 2 || !(parts.get(0) instanceof LispSymbol head) || !DEFERRED_HEADS.contains(head.name())) {
			return null;
		}
		// (defun (setf place) ...) has no plain name; its body simply stays invisible.
		return parts.get(1) instanceof LispSymbol name ? name.name() : null;
	}

	/**
	 * Where a method's body starts, given the index its qualifiers could start at: past
	 * any number of qualifiers and the specialized lambda list, which is the first
	 * non-symbol ({@code ()} makes a {@code LispNil} rather than a cons, so the same test
	 * finds it either way).
	 * @param parts the whole definition form
	 * @param from the first index that may hold a qualifier (past the name, if any)
	 * @return the index of the first body form, which may be past the end
	 */
	private static int bodyStart(List<LispVal> parts, int from) {
		int i = from;
		while (i < parts.size() && parts.get(i) instanceof LispSymbol) {
			i++;
		}
		return i + 1;
	}

	/** Whether this top-level form's body only runs when something calls it. */
	private static boolean isDeferredDefinition(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& DEFERRED_HEADS.contains(head.name());
	}

	/** How a line spells where the load path started: the definition, or just a form. */
	private static String originLabel(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && NAMED_HEADS.contains(head.name())
				&& cons.cdr() instanceof LispCons rest) {
			// (defstruct (name option...) ...) names itself through its option header.
			LispVal named = rest.car() instanceof LispCons header ? header.car() : rest.car();
			if (named instanceof LispSymbol name) {
				return "the top-level (" + head.name() + " " + name.name() + ")";
			}
		}
		return "a top-level form";
	}

	/**
	 * The walk of one load-time region: it records the refusals it meets and collects the
	 * calls it makes, which the caller turns into the next regions.
	 */
	private static final class Scan {

		private final boolean hostRandom;

		private final boolean hostFetch;

		/** The {@code :async t} host-import names; a call of one is a SUSPEND finding. */
		private final Set<String> suspendingImports;

		private final String origin;

		private final Map<String, Found> found;

		/** Call edge -> whether every path to it from this region was guarded. */
		private final Map<Call, Boolean> called;

		/** Names this region rebinds outside the modelled scoping. */
		private final Set<String> shadowed;

		/** Local functions already walked in this region, by name + guard + shapes. */
		private final Set<String> walkedLocals = new HashSet<>();

		/**
		 * The program's function return shapes; see {@code ArgumentShapes.returnShapes}.
		 */
		private final Map<String, Shape> returns;

		private Scan(boolean hostRandom, boolean hostFetch, Set<String> suspendingImports, String origin,
				Map<String, Found> found, Map<Call, Boolean> called, Set<String> shadowed, Map<String, Shape> returns) {
			this.hostRandom = hostRandom;
			this.hostFetch = hostFetch;
			this.suspendingImports = suspendingImports;
			this.origin = origin;
			this.found = found;
			this.called = called;
			this.shadowed = shadowed;
			this.returns = returns;
		}

		/** What one form states about its value, here. */
		private Shape shapeOf(LispVal form, Map<String, Shape> env) {
			return ArgumentShapes.of(form, env, this.returns);
		}

		/**
		 * Drops the names this region rebinds; what is left is what a shape may narrow.
		 */
		private Map<String, Shape> narrow(Map<String, Shape> env) {
			if (this.shadowed.isEmpty()) {
				return env;
			}
			Map<String, Shape> out = new HashMap<>(env);
			this.shadowed.forEach(name -> out.computeIfPresent(name, (k, v) -> Shape.UNKNOWN));
			return out;
		}

		private void form(LispVal val, At at) {
			if (!(val instanceof LispCons cons)) {
				return;
			}
			At here = SourceProvenance.locate(cons) == null ? at : new At(at.guarded(), cons, at.env(), at.locals());
			if (cons.car() instanceof LispSymbol head) {
				this.operatorForm(cons, head.name(), here);
				return;
			}
			if (cons.car() instanceof LispCons inner && inner.car() instanceof LispSymbol innerHead
					&& LispNames.LAMBDA.equals(innerHead.name()) && inner.cdr() instanceof LispCons innerRest) {
				// ((lambda (x) body...) arg...): applied right here, so its body runs
				// here, with the arguments' shapes bound to its parameters.
				this.args(innerRest.cdr(), this.bound(here, innerRest.car(), this.argShapes(cons.cdr(), here)));
			}
			else {
				this.form(cons.car(), here);
			}
			this.args(cons.cdr(), here);
		}

		private void operatorForm(LispCons cons, String name, At at) {
			// Quoted data is not code; a declaration names nothing that runs; a lambda
			// (or
			// a #'f) is a VALUE -- whoever receives it decides when it is called (an
			// async-lambda equally); and a nested definition's body waits for its own
			// caller.
			if (LispNames.QUOTE.equals(name) || LispNames.DECLARE.equals(name) || LispNames.LAMBDA.equals(name)
					|| LispNames.ASYNC_LAMBDA_QUALIFIED.equals(name) || LispNames.FUNCTION.equals(name)
					|| DEFERRED_HEADS.contains(name)) {
				return;
			}
			Local local = at.locals().get(name);
			if (local != null) {
				// A local name shadows the global one, and its body runs HERE -- with
				// what this site says about its arguments.
				this.args(cons.cdr(), at);
				this.walkLocal(name, local, this.argShapes(cons.cdr(), at), at);
				return;
			}
			Kind kind = this.suspendingImports.contains(name) ? Kind.SUSPEND
					: kindOf(cons, name, this.hostRandom, this.hostFetch);
			// A guarded site is a program that already handles the refusal -- except for
			// standard input and a suspending import, which trap rather than signalling,
			// so no handler covers them.
			if (kind != null && (!at.guarded() || kind == Kind.STDIN || kind == Kind.SUSPEND)) {
				// A suspending import reports under its own (declared) name; the table
				// kinds map to their public API.
				this.found.computeIfAbsent(kind == Kind.SUSPEND ? name : reportedName(name),
						operator -> new Found(operator, kind, at.located(), this.origin));
			}
			if (LispNames.HANDLER_CASE.equals(name) && cons.cdr() instanceof LispCons protectedForm) {
				// (handler-case FORM clause...): only FORM is covered -- a clause body
				// runs with whatever protection surrounds the handler-case itself.
				this.form(protectedForm.car(), new At(true, at.located(), at.env(), at.locals()));
				this.args(protectedForm.cdr(), at);
				return;
			}
			if (LispNames.IGNORE_ERRORS.equals(name)) {
				this.args(cons.cdr(), new At(true, at.located(), at.env(), at.locals()));
				return;
			}
			if (LispNames.WITH_OPEN_FILE.equals(name) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispCons spec && spec.cdr() instanceof LispCons pathCell) {
				// The open can only fail, so only the path expression ever runs --
				// exactly
				// what NoWasiFilesystemStubs leaves of the form.
				this.form(pathCell.car(), at);
				return;
			}
			if ((LispNames.TYPECASE.equals(name) || LispNames.ETYPECASE.equals(name))
					&& cons.cdr() instanceof LispCons rest) {
				this.typecase(rest, at);
				return;
			}
			if (LispNames.IF.equals(name) && cons.cdr() instanceof LispCons rest) {
				// (if (typep app 'pathname) A B) with a function in app: only B runs.
				boolean refuted = this.test(rest.car(), at);
				if (rest.cdr() instanceof LispCons thenCell) {
					if (!refuted) {
						this.form(thenCell.car(), at);
					}
					this.args(thenCell.cdr(), at);
				}
				return;
			}
			if (LispNames.WHEN.equals(name) && cons.cdr() instanceof LispCons rest) {
				if (!this.test(rest.car(), at)) {
					this.args(rest.cdr(), at);
				}
				return;
			}
			if (LispNames.COND.equals(name)) {
				LispVal clauses = cons.cdr();
				while (clauses instanceof LispCons cell) {
					if (cell.car() instanceof LispCons clause && !this.test(clause.car(), at)) {
						this.args(clause.cdr(), at);
					}
					clauses = cell.cdr();
				}
				return;
			}
			if (BINDING_HEADS.contains(name) && cons.cdr() instanceof LispCons rest) {
				this.args(rest.cdr(), this.bindings(name, rest.car(), at));
				return;
			}
			if (LOCAL_FUNCTION_HEADS.contains(name) && cons.cdr() instanceof LispCons rest) {
				this.localFunctions(cons, name, rest, at);
				return;
			}
			if (LispNames.DEFSTRUCT.equals(name) && cons.cdr() instanceof LispCons rest) {
				// (defstruct name-or-header slot...): the header is options, and a slot
				// is (name initform option...) -- its initform is the only code in it.
				this.slots(rest.cdr(), at);
				return;
			}
			if ((LispNames.DEFCLASS.equals(name) || LispNames.DEFINE_CONDITION.equals(name))
					&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispCons supers
					&& supers.cdr() instanceof LispCons slotCell) {
				// (defclass name (supers) (slot...) option...): the same, plus the
				// options, which carry the :default-initargs forms.
				this.slots(slotCell.car(), at);
				this.args(slotCell.cdr(), at);
				return;
			}
			this.called.merge(new Call(name, this.argShapes(cons.cdr(), at)), at.guarded(), (a, b) -> a && b);
			this.args(cons.cdr(), at);
		}

		/** Walks the elements of an argument/body list (an improper tail included). */
		private void args(LispVal tail, At at) {
			LispVal rest = tail;
			while (rest instanceof LispCons cons) {
				this.form(cons.car(), at);
				rest = cons.cdr();
			}
			this.form(rest, at);
		}

		/** What this call site states, syntactically, about each of its arguments. */
		private List<Shape> argShapes(LispVal tail, At at) {
			List<Shape> shapes = new ArrayList<>();
			LispVal rest = tail;
			while (rest instanceof LispCons cons) {
				shapes.add(this.shapeOf(cons.car(), at.env()));
				rest = cons.cdr();
			}
			return List.copyOf(shapes);
		}

		/** The state inside a lambda list's scope, with the argument shapes bound. */
		private At bound(At at, LispVal lambdaList, List<Shape> argShapes) {
			Map<String, Shape> env = new HashMap<>(at.env());
			env.putAll(this.narrow(ArgumentShapes.bind(lambdaList, argShapes)));
			return new At(at.guarded(), at.located(), env, at.locals());
		}

		/**
		 * {@code (typecase key (type body...) ...)}: a clause whose type the key's shape
		 * cannot have is not reachable, so its body is not on the load path.
		 */
		private void typecase(LispCons rest, At at) {
			this.form(rest.car(), at);
			Shape shape = this.shapeOf(rest.car(), at.env());
			LispVal clauses = rest.cdr();
			while (clauses instanceof LispCons cell) {
				if (cell.car() instanceof LispCons clause && ArgumentShapes.maySatisfy(shape, clause.car())) {
					this.args(clause.cdr(), at);
				}
				clauses = cell.cdr();
			}
		}

		/**
		 * Walks a branch's test and answers whether the shapes REFUTE it -- the only
		 * direction that prunes. Proving a test instead would need the shape to establish
		 * the type, and a wrong prune here is a missed refusal.
		 */
		private boolean test(LispVal test, At at) {
			this.form(test, at);
			return this.refuted(test, at);
		}

		/** Whether a {@code (typep x 'type)} test is false for every value x can hold. */
		private boolean refuted(LispVal test, At at) {
			if (!(test instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !LispNames.TYPEP.equals(head.name()) || !(cons.cdr() instanceof LispCons valueCell)
					|| !(valueCell.cdr() instanceof LispCons typeCell)) {
				return false;
			}
			LispVal typeSpec = typeCell.car();
			if (typeSpec instanceof LispCons quoted && quoted.car() instanceof LispSymbol quote
					&& LispNames.QUOTE.equals(quote.name()) && quoted.cdr() instanceof LispCons datum) {
				typeSpec = datum.car();
			}
			return !ArgumentShapes.maySatisfy(this.shapeOf(valueCell.car(), at.env()), typeSpec);
		}

		/**
		 * {@code ((var init) ...)} / {@code ((var init step) ...)}: the head only binds.
		 * Returns the state inside the body -- a {@code let}/{@code let*} variable
		 * carries its initializer's shape, a stepped {@code do} variable carries none.
		 */
		private At bindings(String head, LispVal list, At at) {
			boolean sequential = LispNames.LET_STAR.equals(head) || LispNames.DO_STAR.equals(head);
			boolean stepped = LispNames.DO.equals(head) || LispNames.DO_STAR.equals(head);
			Map<String, Shape> env = new HashMap<>(at.env());
			At evalAt = at;
			LispVal rest = list;
			while (rest instanceof LispCons cons) {
				LispVal binding = cons.car();
				LispVal var = binding instanceof LispCons pair ? pair.car() : binding;
				LispVal init = binding instanceof LispCons pair && pair.cdr() instanceof LispCons initCell
						? initCell.car() : null;
				if (binding instanceof LispCons pair) {
					this.args(pair.cdr(), evalAt);
				}
				if (var instanceof LispSymbol sym) {
					Shape shape = stepped || init == null ? Shape.UNKNOWN : this.shapeOf(init, evalAt.env());
					env.put(sym.name(), this.shadowed.contains(sym.name()) ? Shape.UNKNOWN : shape);
					if (sequential) {
						evalAt = new At(at.guarded(), at.located(), Map.copyOf(env), at.locals());
					}
				}
				rest = cons.cdr();
			}
			return new At(at.guarded(), at.located(), env, at.locals());
		}

		/**
		 * {@code ((name (args) body...) ...)}: the name is local, and the body runs where
		 * something CALLS it -- with that site's argument shapes, which is what makes
		 * clack's {@code buildapp} readable. A local taken as a value is walked once with
		 * nothing known; a local nobody reaches is not walked, the same rule the global
		 * graph applies to a {@code defun}.
		 */
		private void localFunctions(LispCons whole, String head, LispCons rest, At at) {
			if (!LispNames.FLET.equals(head) && !LispNames.LABELS.equals(head)) {
				// macrolet/symbol-macrolet: nothing here is a function body to defer.
				LispVal list = rest.car();
				while (list instanceof LispCons cons) {
					if (cons.car() instanceof LispCons localDef && localDef.cdr() instanceof LispCons afterName) {
						this.args(afterName.cdr(), at);
					}
					list = cons.cdr();
				}
				this.args(rest.cdr(), at);
				return;
			}
			Map<String, Local> scope = new HashMap<>(at.locals());
			// A labels function sees itself and its siblings; an flet function does not.
			Map<String, Local> innerScope = LispNames.LABELS.equals(head) ? scope : at.locals();
			List<String> names = new ArrayList<>();
			LispVal list = rest.car();
			while (list instanceof LispCons cons) {
				if (cons.car() instanceof LispCons localDef && localDef.car() instanceof LispSymbol localName
						&& localDef.cdr() instanceof LispCons afterName) {
					scope.put(localName.name(), new Local(afterName.car(), afterName.cdr(), at.env(), innerScope));
					names.add(localName.name());
				}
				list = cons.cdr();
			}
			At inner = new At(at.guarded(), at.located(), at.env(), scope);
			for (String name : names) {
				Local local = scope.get(name);
				if (local != null && takenAsValue(whole, name)) {
					// #'name: whoever received it decides the arguments, so nothing is
					// known about them.
					this.walkLocal(name, local, List.of(), inner);
				}
			}
			this.args(rest.cdr(), inner);
		}

		/**
		 * Walks a local function's body once per (guard, argument shapes) combination.
		 */
		private void walkLocal(String name, Local local, List<Shape> argShapes, At at) {
			String key = name + "|" + at.guarded() + "|" + argShapes;
			if (!this.walkedLocals.add(key)) {
				return;
			}
			Map<String, Shape> env = new HashMap<>(local.env());
			env.putAll(this.narrow(ArgumentShapes.bind(local.lambdaList(), argShapes)));
			this.args(local.bodyTail(), new At(at.guarded(), at.located(), env, local.scope()));
		}

		/**
		 * {@code ((slot-name initform option...) ...)}: the head names a slot, not a
		 * call.
		 */
		private void slots(LispVal list, At at) {
			LispVal rest = list;
			while (rest instanceof LispCons cons) {
				if (cons.car() instanceof LispCons slot) {
					this.args(slot.cdr(), at);
				}
				rest = cons.cdr();
			}
		}

	}

	/**
	 * Whether {@code #'name} appears anywhere in a form -- a local escaping as a value.
	 */
	static boolean takenAsValue(LispVal form, String name) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.FUNCTION.equals(head.name())
				&& cons.cdr() instanceof LispCons target && target.car() instanceof LispSymbol referenced
				&& name.equals(referenced.name())) {
			return true;
		}
		return takenAsValue(cons.car(), name) || takenAsValue(cons.cdr(), name);
	}

	/** Which refusal this operator is, or {@code null} when it is not one. */
	private static @Nullable Kind kindOf(LispCons cons, String name, boolean hostRandom, boolean hostFetch) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		if (qualified != null) {
			if (!LispNames.RONTOLISP_PKG.equals(qualified.pkg())) {
				return null;
			}
			// Without --host-fetch, a --no-wasi fetch is a COMPILE error (dead code
			// included), so only the flagged build has a load-path fact to state.
			if (LispNames.FETCH.equals(qualified.member()) && hostFetch) {
				return Kind.FETCH;
			}
			boolean entropy = LispNames.RANDOM_BYTES.equals(qualified.member())
					|| LispNames.RANDOM_BYTE_INTERNAL.equals(qualified.member());
			return entropy && !hostRandom ? Kind.ENTROPY : null;
		}
		return switch (name) {
			case LispNames.GET_UNIVERSAL_TIME, LispNames.GET_INTERNAL_REAL_TIME, LispNames.GET_INTERNAL_RUN_TIME ->
				Kind.CLOCK;
			case LispNames.SLEEP -> Kind.SLEEP;
			case LispNames.OPEN, LispNames.WITH_OPEN_FILE, LispNames.LOAD -> Kind.FILESYSTEM;
			// Only the standard-input shape traps: given a stream the call reads THAT
			// stream, and a module with no filesystem and no sockets has none to give it,
			// so a (read-line in nil nil) is dead code rather than a load-time death.
			case LispNames.READ, LispNames.READ_LINE, LispNames.READ_CHAR ->
				cons.cdr() instanceof LispCons ? null : Kind.STDIN;
			default -> null;
		};
	}

	/** The operator a line names: the entropy pair reports as its public API. */
	private static String reportedName(String name) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		if (qualified == null) {
			return name;
		}
		return LispNames.FETCH.equals(qualified.member()) ? "rontolisp:" + LispNames.FETCH
				: "rontolisp:" + LispNames.RANDOM_BYTES;
	}

	private static String line(Found found, boolean reactorComponent) {
		String remedy = switch (found.kind()) {
			case CLOCK -> reactorComponent
					? "A --no-wasi reactor component imports no clock and exposes no way to hand one in -- its top"
							+ " level runs at instantiation, so there is no window before the first read. Compile"
							+ " without --component for the __ronto_set_time hook"
					: "The module imports no clock: its time is whatever the host writes through the exported"
							+ " __ronto_set_time hook (nanoseconds since the Unix epoch), so call that BEFORE"
							+ " _initialize -- until something does, reading it signals";
			case ENTROPY -> "A --no-wasi module's random is a deterministic generator and must not stand in for"
					+ " cryptographic entropy, so this signals; add --host-random to route random_get at a host"
					+ " import (env.random_get)";
			case STDIN -> "A --no-wasi module has no standard input, and the fd_read slot TRAPS rather than"
					+ " signalling -- the one refusal no handler can cover, and there is no host hook for it";
			case SLEEP -> "A --no-wasi module imports no timer, and its clock cannot advance while a call is running,"
					+ " so no interval could elapse and this signals";
			case FILESYSTEM -> "A --no-wasi module has no filesystem, so this signals";
			case FETCH -> "Under --host-fetch this fetch crosses the env.fetch host import DURING _initialize"
					+ " -- a stack no WebAssembly.promising entered, so a SUSPENDING host fetch"
					+ " (WebAssembly.Suspending / JSPI) traps here; the host's env.fetch must answer"
					+ " synchronously, or the fetch must move out of the load path";
			// A load-path :async t call is an ERROR, not a line: SuspendingImports
			// formats it, and report() never produces the kind.
			case SUSPEND -> throw new IllegalStateException("SUSPEND is not a reportable line");
		};
		return SourceProvenance.prefix(found.site()) + "warning: " + found.operator()
				+ " is reachable from a top-level form of this --no-wasi module (" + found.origin()
				+ "), so it can run while the module LOADS -- where nothing catches it and the host sees only"
				+ " RuntimeError: unreachable. " + remedy;
	}

}
