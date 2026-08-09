package am.ik.rontolisp.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
 * The top-level forms, plus the bodies of the functions they call, transitively. Four
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
 * </ul>
 *
 * <p>
 * Reachability is static, so a branch that no run takes still counts: every {@code clack}
 * program prints the {@code %load-file} line, because {@code clackup} keeps a
 * {@code (clackup "app.lisp")} branch a reactor never takes. The reported CHAIN is what
 * makes that readable -- it names the path, so the reader can see which branch it is.
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
	private enum Kind {

		/** The clock: a host obligation, not a refusal, on a build that has the hook. */
		CLOCK,
		/** Cryptographic entropy: refused unless {@code --host-random} routes it. */
		ENTROPY,
		/** Standard input: the one refusal that traps, so no handler can catch it. */
		STDIN,
		/** {@code sleep}: no interval can elapse without a clock that moves. */
		SLEEP,
		/** The file-opening forms: a reactor has no filesystem. */
		FILESYSTEM

	}

	/**
	 * One reached primitive: what it is, where it is, and how the load path got there.
	 */
	private record Found(String operator, Kind kind, LispVal site, String origin) {
	}

	/** One function body, with the definition form that carries its source position. */
	private record Body(LispVal anchor, List<LispVal> forms) {
	}

	/**
	 * Forms that run at load time: what reached them, and whether a handler covers them.
	 */
	private record Region(Body body, String origin, boolean guarded) {
	}

	/**
	 * The walk state at one point in a region: whether a handler covers it, and the
	 * innermost enclosing form whose source position is known -- the position a refusal
	 * reports when its own cons has lost one (a pass that rebuilt a cons drops it, and
	 * that drops the whole subtree's, see {@code .kb/source-positions.md}).
	 */
	private record At(boolean guarded, LispVal located) {
	}

	/** The definition heads whose body only runs when something CALLS them. */
	private static final Set<String> DEFERRED_HEADS = Set.of(LispNames.DEFUN, LispNames.DEFMACRO, LispNames.DEFMETHOD,
			LispNames.DEFGENERIC);

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
	 * @param reactorComponent whether this is the {@code --component --no-wasi} reactor,
	 * which carries no host hooks at all (its top level runs at instantiation, so there
	 * is no window before the first read)
	 * @return one line per primitive, position prefix included, in the order the load
	 * path reaches them; empty when it reaches none
	 */
	public static List<String> report(List<LispVal> program, boolean hostRandom, boolean reactorComponent) {
		Map<String, List<Body>> definitions = collectDefinitions(program);
		Map<String, Found> found = new LinkedHashMap<>();
		// name -> whether the visit that claimed it was guarded. A later UNGUARDED
		// path to the same function is a different answer, so it is walked again.
		Map<String, Boolean> visited = new HashMap<>();
		Deque<Region> queue = new ArrayDeque<>();
		for (LispVal form : program) {
			if (!isDeferredDefinition(form)) {
				queue.addLast(new Region(new Body(form, List.of(form)), originLabel(form), false));
			}
		}
		// Breadth first, so the path a line reports is the shortest one that reaches it.
		while (!queue.isEmpty()) {
			Region region = queue.removeFirst();
			Map<String, Boolean> called = new LinkedHashMap<>();
			Scan scan = new Scan(hostRandom, region.origin(), found, called);
			At at = new At(region.guarded(), region.body().anchor());
			region.body().forms().forEach(form -> scan.form(form, at));
			called.forEach((name, guarded) -> {
				Boolean claimed = visited.get(name);
				if (claimed == null || (claimed && !guarded)) {
					visited.put(name, guarded);
					for (Body body : definitions.getOrDefault(name, List.of())) {
						queue.addLast(new Region(body, region.origin() + " -> " + name, guarded));
					}
				}
			});
		}
		List<String> lines = new ArrayList<>(found.size());
		found.values().forEach(f -> lines.add(line(f, reactorComponent)));
		return lines;
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
				case LispNames.DEFUN, LispNames.DEFMACRO -> 3;
				// (defmethod name qualifier* (specialized-ll) body...)
				case LispNames.DEFMETHOD -> bodyStart(parts, 2);
				// A defgeneric has no body of its own; its :method clauses are below.
				default -> parts.size();
			};
			if (body <= parts.size()) {
				define(definitions, name, cons, parts.subList(body, parts.size()));
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
							define(definitions, name, optCons, optParts.subList(start, optParts.size()));
						}
					}
				}
			}
		}
		return definitions;
	}

	private static void define(Map<String, List<Body>> definitions, String name, LispVal anchor, List<LispVal> forms) {
		if (!forms.isEmpty()) {
			definitions.computeIfAbsent(name, k -> new ArrayList<>()).add(new Body(anchor, forms));
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
	 * names it calls, which the caller turns into the next regions.
	 */
	private static final class Scan {

		private final boolean hostRandom;

		private final String origin;

		private final Map<String, Found> found;

		/** Called name -> whether every path to it from this region was guarded. */
		private final Map<String, Boolean> called;

		private Scan(boolean hostRandom, String origin, Map<String, Found> found, Map<String, Boolean> called) {
			this.hostRandom = hostRandom;
			this.origin = origin;
			this.found = found;
			this.called = called;
		}

		private void form(LispVal val, At at) {
			if (!(val instanceof LispCons cons)) {
				return;
			}
			At here = SourceProvenance.locate(cons) == null ? at : new At(at.guarded(), cons);
			if (cons.car() instanceof LispSymbol head) {
				this.operatorForm(cons, head.name(), here);
				return;
			}
			if (cons.car() instanceof LispCons inner && inner.car() instanceof LispSymbol innerHead
					&& LispNames.LAMBDA.equals(innerHead.name()) && inner.cdr() instanceof LispCons innerRest) {
				// ((lambda (x) body...) arg...): applied right here, so its body runs
				// here.
				this.args(innerRest.cdr(), here);
			}
			else {
				this.form(cons.car(), here);
			}
			this.args(cons.cdr(), here);
		}

		private void operatorForm(LispCons cons, String name, At at) {
			// Quoted data is not code; a declaration names nothing that runs; a lambda
			// (or
			// a #'f) is a VALUE -- whoever receives it decides when it is called; and a
			// nested definition's body waits for its own caller.
			if (LispNames.QUOTE.equals(name) || LispNames.DECLARE.equals(name) || LispNames.LAMBDA.equals(name)
					|| LispNames.FUNCTION.equals(name) || DEFERRED_HEADS.contains(name)) {
				return;
			}
			Kind kind = kindOf(cons, name, this.hostRandom);
			// A guarded site is a program that already handles the refusal -- except for
			// standard input, which traps rather than signalling, so no handler covers
			// it.
			if (kind != null && (!at.guarded() || kind == Kind.STDIN)) {
				this.found.computeIfAbsent(reportedName(name),
						operator -> new Found(operator, kind, at.located(), this.origin));
			}
			if (LispNames.HANDLER_CASE.equals(name) && cons.cdr() instanceof LispCons protectedForm) {
				// (handler-case FORM clause...): only FORM is covered -- a clause body
				// runs
				// with whatever protection surrounds the handler-case itself.
				this.form(protectedForm.car(), new At(true, at.located()));
				this.args(protectedForm.cdr(), at);
				return;
			}
			if (LispNames.IGNORE_ERRORS.equals(name)) {
				this.args(cons.cdr(), new At(true, at.located()));
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
			if (BINDING_HEADS.contains(name) && cons.cdr() instanceof LispCons rest) {
				this.bindings(rest.car(), at);
				this.args(rest.cdr(), at);
				return;
			}
			if (LOCAL_FUNCTION_HEADS.contains(name) && cons.cdr() instanceof LispCons rest) {
				this.localFunctions(rest.car(), at);
				this.args(rest.cdr(), at);
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
			this.called.merge(name, at.guarded(), (a, b) -> a && b);
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

		/**
		 * {@code ((var init) ...)} / {@code ((var init step) ...)}: the head only binds.
		 */
		private void bindings(LispVal list, At at) {
			LispVal rest = list;
			while (rest instanceof LispCons cons) {
				if (cons.car() instanceof LispCons binding) {
					this.args(binding.cdr(), at);
				}
				rest = cons.cdr();
			}
		}

		/** {@code ((name (args) body...) ...)}: the body runs here, the name is local. */
		private void localFunctions(LispVal list, At at) {
			LispVal rest = list;
			while (rest instanceof LispCons cons) {
				if (cons.car() instanceof LispCons local && local.cdr() instanceof LispCons afterName) {
					this.args(afterName.cdr(), at);
				}
				rest = cons.cdr();
			}
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

	/** Which refusal this operator is, or {@code null} when it is not one. */
	private static @Nullable Kind kindOf(LispCons cons, String name, boolean hostRandom) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(name);
		if (qualified != null) {
			if (!LispNames.RONTOLISP_PKG.equals(qualified.pkg())) {
				return null;
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
		return PackageRegistry.splitQualified(name) == null ? name : "rontolisp:" + LispNames.RANDOM_BYTES;
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
		};
		return SourceProvenance.prefix(found.site()) + "warning: " + found.operator()
				+ " is reachable from a top-level form of this --no-wasi module (" + found.origin()
				+ "), so it can run while the module LOADS -- where nothing catches it and the host sees only"
				+ " RuntimeError: unreachable. " + remedy;
	}

}
