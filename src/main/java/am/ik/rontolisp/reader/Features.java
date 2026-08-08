package am.ik.rontolisp.reader;

import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The set of active reader features, consulted by the {@code #+}/{@code #-} feature
 * conditionals in {@link LispLexer}, the {@code *features*} read-time substitution in
 * {@link LispReader}, and the {@code :if-feature} component option of the ASDF subset.
 * Every backend has {@code :rontolisp} plus one backend-identifying feature
 * ({@code :rontolisp-interpreter}, {@code :rontolisp-jvm} or {@code :rontolisp-wasm}) and
 * {@code :unicode}; {@code :common-lisp} is deliberately absent (rontolisp is not a
 * conforming implementation). Reading happens once, at the frontend, so the feature set
 * of a compiled program is fixed at compile time.
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
			List.of("rontolisp", "rontolisp-interpreter", "unicode", "thread-support"), false);

	/** The features active when compiling to JVM bytecode. */
	public static final Features JVM = new Features(List.of("rontolisp", "rontolisp-jvm", "unicode", "thread-support"),
			true);

	/** The features active when compiling to WASM (Preview 1, component and no-gc). */
	public static final Features WASM = new Features(List.of("rontolisp", "rontolisp-wasm", "unicode"), true);

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
			List.of("rontolisp", "rontolisp-wasm", "unicode", "rontolisp-reactor"), true);

	private final List<String> names;

	private final boolean substituteFeaturesVar;

	private Features(List<String> names, boolean substituteFeaturesVar) {
		this.names = names;
		this.substituteFeaturesVar = substituteFeaturesVar;
	}

	/**
	 * Whether the reader substitutes the {@code *features*} symbol with the quoted
	 * feature list. The compile backends do (a compiled program's feature set is fixed at
	 * compile time); the interpreter does not -- it binds {@code *features*} as a global
	 * variable instead, so the symbol survives in binding positions (a
	 * {@code (&optional (*features* *features*))} rebinding idiom must not lose the
	 * parameter name to the substitution).
	 * @return {@code true} when the reader substitutes the symbol
	 */
	public boolean substituteFeaturesVar() {
		return this.substituteFeaturesVar;
	}

	/**
	 * Creates a custom feature set (primarily for tests).
	 * @param names the feature names, without the leading colon
	 * @return the feature set
	 */
	public static Features of(String... names) {
		return new Features(List.of(names), true);
	}

	/**
	 * Returns this feature set widened by the given names, or {@code this} when they are
	 * all present already. This is the static encoding of a {@code .asd} that pushes onto
	 * {@code *features*} from an {@code eval-when} before its {@code defsystem}: such a
	 * push never reaches the reader here, so a replacement {@code .asd} declares the
	 * features with {@code :rontolisp-features} and the system's component files are read
	 * with the widened set (see {@code AsdfSystems}). Deliberately additive only --
	 * nothing may switch OFF a backend feature and claim to be that backend.
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
		return widened.size() == this.names.size() ? this
				: new Features(List.copyOf(widened), this.substituteFeaturesVar);
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
