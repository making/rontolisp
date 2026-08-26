package am.ik.rontolisp.reader;

import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The set of active READER features, consulted by the {@code #+}/{@code #-} feature
 * conditionals in {@link LispLexer}, the {@code :if-feature} component option of the ASDF
 * subset, and -- as its starting point -- the {@code *features*} global every backend
 * seeds at run time. Every backend has {@code :rontolisp} plus one backend-identifying
 * feature ({@code :rontolisp-interpreter}, {@code :rontolisp-jvm} or
 * {@code :rontolisp-wasm}) and {@code :unicode}; {@code :common-lisp} is deliberately
 * absent (rontolisp is not a conforming implementation).
 * <p>
 * A read-time feature set and the run-time {@code *features*} list are two different
 * things, and keeping them apart is the whole point: this set is what {@code #+} tests
 * while the source is being READ, and it is fixed for the duration of that read --
 * widened only by a declaration the reader can see for itself ({@link #with}: an ASDF
 * {@code :rontolisp-features} option, or the file's own literal top-level push, see
 * {@link FeaturePushes}). {@code *features*} is an ordinary special variable holding a
 * list, initialized to these names on every backend, and a program may
 * {@code push}/{@code setq} it at run time like any other. See
 * {@code .kb/reader-features.md}.
 * <p>
 * {@code :unicode} is the portable spelling (CLISP / ECL / CMUCL / LispWorks) of "this
 * implementation's characters are Unicode code points, not octets", which is true of
 * every rontolisp backend. A library that branches on it selects its UTF-8 string path --
 * cl-postgres, whose non-unicode branch would talk {@code SQL_ASCII} to the server and
 * send one octet per code point.
 */
public final class Features {

	/**
	 * The features active when interpreting (and in the REPL). {@code :thread-support} is
	 * the ecosystem's portable spelling of "this image spawns threads" -- upstream
	 * bordeaux-threads pushes it from its {@code .asd} at load time, and the push can
	 * never reach a read-time conditional here, so the backends that really spawn threads
	 * ({@code rontolisp:make-thread} on the interpreter and the JVM,
	 * {@code .kb/threads.md}) declare it statically like {@code :unicode}. Both WASM
	 * backends are single-threaded by construction and stay without it -- which is what
	 * makes {@code clack:clackup}'s {@code #+thread-support} default {@code :use-thread}
	 * to t here and to nil there.
	 */
	public static final Features INTERPRETER = new Features(
			List.of("rontolisp", "rontolisp-interpreter", "unicode", "thread-support"));

	/** The features active when compiling to JVM bytecode. */
	public static final Features JVM = new Features(List.of("rontolisp", "rontolisp-jvm", "unicode", "thread-support"));

	/**
	 * The features active when compiling to JVM bytecode in SERVLET mode -- a
	 * {@code -o app.war} output, where the servlet container owns the port and the
	 * {@code rontolisp:http-handler} directive registers its handler and RETURNS instead
	 * of binding and blocking. {@code :rontolisp-servlet} is a target-describing feature
	 * exactly like {@code :rontolisp-reactor}: it is what lets the
	 * {@code clack-handler-rontolisp} shim choose the register-and-return transport per
	 * target and on nothing else, so one {@code clackup} source runs on every host
	 * ({@code .kb/clack.md}).
	 */
	public static final Features JVM_SERVLET = new Features(
			List.of("rontolisp", "rontolisp-jvm", "unicode", "thread-support", "rontolisp-servlet"));

	/** The features active when compiling to WASM (Preview 1, component and no-gc). */
	public static final Features WASM = new Features(List.of("rontolisp", "rontolisp-wasm", "unicode"));

	/**
	 * The features active when compiling to WASM in REACTOR mode -- {@code --no-wasi}
	 * (Preview 1 only: the compiler ignores that flag under {@code --component}, so this
	 * set is not selected there either), and {@code --no-gc}, which is a pure-compute
	 * reactor with or without the component wrap. The module owns no WASI world; its
	 * entry points are exports a host calls. {@code :rontolisp-reactor} is a
	 * target-describing feature like {@code :thread-support}, not a flag echo: it is what
	 * lets a shim choose its transport per target -- the {@code clack-handler-rontolisp}
	 * backend's {@code run} serves through the {@code rontolisp:http-handler} directive
	 * on the WASI targets and leaves the {@code rontolisp::%http-reactor} marker (the
	 * synthesized {@code handle-request} export) here, which is what makes ONE
	 * {@code clackup} source run on every host ({@code .kb/clack.md}).
	 */
	public static final Features WASM_REACTOR = new Features(
			List.of("rontolisp", "rontolisp-wasm", "unicode", "rontolisp-reactor"));

	/**
	 * The feature naming the component BOUNDARY, added to whichever WASM set is in force
	 * under {@code --component} ({@link #with}). It describes a boundary rather than a
	 * backend, and that is why the reactor features above cannot stand in for it: a
	 * {@code --component --no-wasi} build IS a reactor and carries
	 * {@code :rontolisp-reactor} too, while its host functions cross the canonical ABI
	 * instead of core imports -- so {@code rontolisp:wasm-import} (and the {@code :bytes}
	 * boundary type) is refused there, and a source that declares one says
	 * {@code #-rontolisp-component} to keep compiling as a component
	 * ({@code examples/cloudflare-workers/httpbin/worker.lisp}, built both ways).
	 */
	public static final String COMPONENT = "rontolisp-component";

	/**
	 * The feature naming the {@code :bytes} BODY IMPORTS a reactor's HTTP boundary can
	 * take its request and response bodies through, added to the WASM set exactly where
	 * they exist: a {@code --no-wasi} wasm-GC core module built with
	 * {@code --host-boundary=streaming} (the default; {@link #with}). It is what a
	 * HAND-WRITTEN reactor -- one that spells out its own envelope adapter instead of
	 * going through {@code clack:clackup} -- guards its own
	 * {@code env.readRequestBody}/{@code env.writeResponseBody} declarations with, so a
	 * source can follow the boundary the build chose.
	 *
	 * <p>
	 * It replaces the double negative such a source had to write before:
	 * {@code #+(and rontolisp-reactor (not rontolisp-component))} spelled out the two
	 * targets that CANNOT carry the imports (a component's host functions cross the
	 * canonical ABI; a WASI command module has no host to import from) and quietly got
	 * {@code --no-gc} wrong, which is a reactor with no packed-array representation for
	 * {@code :bytes} at all. Naming the thing itself makes the guard mean what it says --
	 * and makes it follow the flag, which no combination of target features could.
	 */
	public static final String BODY_IMPORTS = "rontolisp-body-imports";

	private final List<String> names;

	private Features(List<String> names) {
		this.names = names;
	}

	/**
	 * Creates a custom feature set (primarily for tests).
	 * @param names the feature names, without the leading colon
	 * @return the feature set
	 */
	public static Features of(String... names) {
		return new Features(List.of(names));
	}

	/**
	 * Returns this feature set widened by the given names, or {@code this} when they are
	 * all present already. Two callers, both of them a feature ANNOUNCEMENT the reader
	 * can see for itself: {@link FeaturePushes}, for a source's own literal top-level
	 * push, and {@code AsdfSystems}, for a {@code .asd}'s {@code :rontolisp-features}
	 * declaration -- the cross-FILE half, which the push cannot do (a push in a
	 * {@code .asd} reaches that file's own {@code #+} and not the component files of the
	 * systems it defines). Deliberately additive only -- nothing may switch OFF a backend
	 * feature and claim to be that backend.
	 * @param extra the feature names to add, without the leading colon
	 * @return the widened feature set
	 */
	public Features with(List<String> extra) {
		List<String> widened = new java.util.ArrayList<>(this.names);
		for (String name : extra) {
			String canonical = featureName(name);
			if (widened.stream().noneMatch(canonical::equalsIgnoreCase)) {
				widened.add(canonical);
			}
		}
		return widened.size() == this.names.size() ? this : new Features(List.copyOf(widened));
	}

	/**
	 * Returns the active feature names (without the leading colon), in a fixed order.
	 * @return the feature names
	 */
	public List<String> names() {
		return this.names;
	}

	/**
	 * Returns whether the named feature is active. The comparison is case-insensitive and
	 * tolerates a leading {@code :} or {@code #:} (feature expressions are keywords in
	 * Common Lisp source).
	 * @param feature the feature name
	 * @return {@code true} if the feature is active
	 */
	public boolean contains(String feature) {
		String name = featureName(feature);
		for (String candidate : this.names) {
			if (candidate.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Evaluates a feature expression against this feature set: a symbol tests a single
	 * feature, and {@code (and ...)}/{@code (or ...)}/{@code (not X)} (spelled bare or as
	 * keywords) combine sub-expressions, mirroring Common Lisp.
	 * @param expr the feature expression, as plain data
	 * @return {@code true} if the expression is satisfied
	 * @throws LispReadException if the expression is not a valid feature expression
	 */
	public boolean isEnabled(LispVal expr) {
		return switch (expr) {
			case LispSymbol sym -> contains(sym.name());
			// nil never names an active feature (the #+nil comment idiom), and () is
			// an empty (or).
			case LispNil ignored -> false;
			case LispCons cons -> isCompoundEnabled(cons);
			default -> throw new LispReadException("Invalid feature expression: " + expr.print());
		};
	}

	private boolean isCompoundEnabled(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			throw new LispReadException("Invalid feature expression: " + cons.print());
		}
		List<LispVal> items = cons.toList();
		List<LispVal> args = items.subList(1, items.size());
		return switch (featureName(op.name())) {
			case "and" -> args.stream().allMatch(this::isEnabled);
			case "or" -> args.stream().anyMatch(this::isEnabled);
			case "not" -> {
				if (args.size() != 1) {
					throw new LispReadException("not expects exactly one feature expression: " + cons.print());
				}
				yield !isEnabled(args.get(0));
			}
			default ->
				throw new LispReadException("Unknown feature expression operator " + op.name() + ": " + cons.print());
		};
	}

	private static String featureName(String name) {
		String stripped = name.startsWith("#:") ? name.substring(2) : name.startsWith(":") ? name.substring(1) : name;
		return stripped.toLowerCase(Locale.ROOT);
	}

}
