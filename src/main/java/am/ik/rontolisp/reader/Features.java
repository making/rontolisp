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
 * ({@code :rontolisp-interpreter}, {@code :rontolisp-jvm} or {@code :rontolisp-wasm});
 * {@code :common-lisp} is deliberately absent (rontolisp is not a conforming
 * implementation). Reading happens once, at the frontend, so the feature set of a
 * compiled program is fixed at compile time.
 */
public final class Features {

	/** The features active when interpreting (and in the REPL). */
	public static final Features INTERPRETER = new Features(List.of("rontolisp", "rontolisp-interpreter"));

	/** The features active when compiling to JVM bytecode. */
	public static final Features JVM = new Features(List.of("rontolisp", "rontolisp-jvm"));

	/** The features active when compiling to WASM (Preview 1, component and no-gc). */
	public static final Features WASM = new Features(List.of("rontolisp", "rontolisp-wasm"));

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
