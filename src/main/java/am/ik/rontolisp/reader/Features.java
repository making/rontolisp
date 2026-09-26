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
 * Every set here is machine-INDEPENDENT, and deliberately: the names describing the host
 * a program will run on ({@code :darwin}/{@code :linux}, {@code :arm64}/{@code :x86-64})
 * are carried by the trivial-features ANNOUNCEMENT instead
 * ({@code BuiltinSystems.hostFeatures}, {@code .kb/asdf.md}), so a {@code Features}
 * constant -- and the {@code *features*} list a program that asked for nothing sees -- is
 * the same on every build machine.
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

	/**
	 * The feature naming the RUNNER a {@code --native} executable's wasm-GC module runs
	 * inside, added to the WASM set exactly there ({@link #with}). A native output is
	 * still the Preview 1 module ({@link #WASM} as is: no reactor, no component, no
	 * scalar backend -- the CLI refuses those beside {@code --native}), but the runner
	 * around it answers imports Preview 1 alone does not have -- {@code rontolisp:fetch}
	 * through {@code rlrun-net}, and {@code objc:}/{@code appkit:}/{@code metal:}/
	 * {@code scene:} through {@code rlobjc} on {@code macos-aarch64} -- so no existing
	 * feature can tell the two builds apart and one source cannot say "fetch on native,
	 * fall back on Preview 1" without it. Deliberately silent on OS/architecture: those
	 * would have to come from {@code --native-target} and never the compile host's
	 * {@code os.name} (a cross build would lie), and they flip {@code #+unix}/
	 * {@code #+darwin} branches in shipped libraries ({@code .kb/uiop.md}).
	 */
	public static final String NATIVE = "rontolisp-native";

	private final List<String> names;

	/**
	 * Whether a name in {@link #names} is a SYMBOL DESIGNATOR that carries its own
	 * keyword-ness (a leading {@code :}) rather than a bare feature name. True only for
	 * {@link #ofRuntimeList(List)}: the static sets spell their names bare, and stripping
	 * a query's colon is the whole of the comparison there.
	 */
	private final boolean designators;

	private Features(List<String> names) {
		this(names, false);
	}

	private Features(List<String> names, boolean designators) {
		this.names = names;
		this.designators = designators;
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
	 * The feature set a RUNTIME {@code read} / {@code read-from-string} tests: the live
	 * value of the {@code *features*} variable, which a program may rebind or push onto
	 * at will. The names arrive as the list holds them -- {@code ":X"} for a keyword,
	 * {@code "X"} for a symbol read in some other package -- and the comparison KEEPS
	 * that distinction, because Common Lisp's does: a feature expression is read with
	 * {@code *package*} bound to {@code KEYWORD} (CLHS 24.1.2.1.1), so the unqualified
	 * {@code #+X} asks about {@code :X} and only a qualified {@code #+FOO::X} asks about
	 * a symbol in another package. The static sets above cannot make that distinction and
	 * do not need to: every name in them is a keyword.
	 * @param names the {@code *features*} entries, each printed as the symbol reads
	 * ({@code ":X"} or {@code "X"})
	 * @return the feature set
	 */
	public static Features ofRuntimeList(List<String> names) {
		return new Features(List.copyOf(names), true);
	}

	/**
	 * Returns this feature set widened by the given names, or {@code this} when they are
	 * all present already. Three callers. Two are a feature ANNOUNCEMENT the reader can
	 * see for itself: {@link FeaturePushes}, for a source's own literal top-level push,
	 * and {@code AsdfSystems}, for a {@code .asd}'s {@code :rontolisp-features}
	 * declaration -- the cross-FILE half, which the push cannot do (a push in a
	 * {@code .asd} reaches that file's own {@code #+} and not the component files of the
	 * systems it defines). The third is the USER, through the {@code --feature} command
	 * line option ({@code RontoLispCli.declaredFeatures}), for the names a portable
	 * library's {@code #+sbcl}/{@code #+clisp} chain expects from the host implementation
	 * and that no announcement of ours could honestly make. Deliberately additive only --
	 * nothing may switch OFF a backend feature and claim to be that backend, and
	 * {@code --feature} additionally refuses to name one at all.
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
		if (this.designators) {
			String name = queriedDesignator(feature);
			for (String candidate : this.names) {
				if (listedDesignator(candidate).equals(name)) {
					return true;
				}
			}
			return false;
		}
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

	/**
	 * A name out of the {@code *features*} list, normalized for comparison: the
	 * upper-cased member name, prefixed with {@code ":"} when the entry IS a keyword --
	 * which, for a list entry, is exactly when it prints with a leading colon. A package
	 * prefix is dropped because a rontolisp symbol does not carry its package
	 * ({@code .todo/156}), so {@code FOO::X} and {@code BAR::X} are one name here.
	 */
	private static String listedDesignator(String name) {
		String text = name.startsWith("#:") ? name.substring(2) : name;
		return text.startsWith(":") ? ":" + memberOf(text) : memberOf(text);
	}

	/**
	 * A name out of a {@code #+}/{@code #-} feature EXPRESSION, normalized the same way
	 * -- except that an UNQUALIFIED name is a keyword here, because the expression is
	 * read with {@code *package*} bound to {@code KEYWORD} (CLHS 24.1.2.1.1). That is the
	 * whole difference between the two sides, and the thing that makes {@code #+X} ask
	 * about {@code :X} while a {@code *features*} holding the symbol {@code X} answers
	 * no.
	 */
	private static String queriedDesignator(String feature) {
		String text = feature.startsWith("#:") ? feature.substring(2) : feature;
		return text.indexOf(':') < 0 || text.startsWith(":") ? ":" + memberOf(text) : memberOf(text);
	}

	private static String memberOf(String text) {
		return text.substring(text.lastIndexOf(':') + 1).toUpperCase(Locale.ROOT);
	}

	private static String featureName(String name) {
		String stripped = name.startsWith("#:") ? name.substring(2) : name.startsWith(":") ? name.substring(1) : name;
		return stripped.toLowerCase(Locale.ROOT);
	}

}
