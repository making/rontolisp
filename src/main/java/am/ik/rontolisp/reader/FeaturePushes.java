package am.ik.rontolisp.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * The feature names a source announces about ITSELF -- a top-level
 * {@code (pushnew :FEATURE *features*)}, alone or inside an {@code eval-when} /
 * {@code progn} -- collected so that the {@code #+}/{@code #-} conditionals in the SAME
 * source can see them.
 *
 * <p>
 * The gap this closes: a file is read whole before any of its forms runs, so a push that
 * only takes effect when the form is EVALUATED can never reach a conditional the reader
 * already resolved. Real Common Lisp has no such gap because {@code load} and
 * {@code compile-file} both process a file FORM AT A TIME, which is why the announcement
 * idiom -- push in the header, {@code #+} on it below -- is written at all (fast-io,
 * trivial-utf-16, cl-json's float-lattice probe). Here the answer is to let the READER
 * make the announcement, because the reader is the one layer the interpreter and all
 * three compile backends share: whatever it decides, they agree by construction, and no
 * backend needs an evaluator to reach the same conclusion.
 *
 * <p>
 * <b>What this does not see.</b> Only a LITERAL push is seen here -- the pushed value
 * must be a keyword written in the source, at top level. A push whose value or whose
 * firing the program COMPUTES ({@code (case char-code-limit (#x110000 (pushnew :utf-32
 * *features*)))}, trivial-utf-16's own header) takes running the program's code: the
 * forms below it read with what it pushed because reading goes form at a time, the
 * interpreter having run it and a compile path's read-time image running it for this
 * ({@link #computesAPush(String, Features, String)}, {@code .kb/reader-features.md}).
 *
 * <p>
 * The literal push is also a WHOLE-FILE widening rather than a positional one: a
 * conditional ABOVE the push sees it too. See {@code .kb/reader-features.md}.
 */
public final class FeaturePushes {

	/**
	 * The cap on widening rounds. Each round that changes anything strictly grows the
	 * feature set (a name is only ever added), so the loop terminates on its own for any
	 * real source; the cap is the backstop that keeps a pathological one from re-reading
	 * a file without end.
	 */
	private static final int MAX_ROUNDS = 8;

	/** The forms whose contents do not run where the form stands. */
	private static final Set<String> NOT_RUN = Set.of(LispNames.QUOTE, LispNames.FUNCTION, LispNames.DEFUN,
			LispNames.DEFMACRO, LispNames.DEFGENERIC, LispNames.DEFMETHOD, LispNames.LAMBDA);

	private FeaturePushes() {
	}

	/**
	 * Returns {@code features} widened by the feature names {@code input} announces about
	 * itself, or {@code features} unchanged when it announces none.
	 *
	 * <p>
	 * The scan costs a second, provenance-free parse of the source, so it is gated on the
	 * text mentioning {@code *features*} at all -- the same shape as the {@code #.} gate
	 * on the compile path. A source that announces a feature it already has (the common
	 * case: an upstream {@code (pushnew :thread-support *features*)} beside our static
	 * declaration) widens to the same set and is read once.
	 * @param input the source text
	 * @param features the feature set the read would otherwise use
	 * @param readEvalMode how the scan treats a {@code #.} datum (the real read's mode,
	 * so the scan sees the same forms)
	 * @param file the origin file, or {@code null} when unknown
	 * @return the effective feature set for reading {@code input}
	 */
	static Features widen(String input, Features features, LispLexer.ReadEvalMode readEvalMode, @Nullable String file) {
		if (!mentionsFeaturesVar(input)) {
			return features;
		}
		Features current = features;
		for (int round = 0; round < MAX_ROUNDS; round++) {
			List<String> announced = collect(LispReader.readAllForScan(input, current, readEvalMode, file));
			if (announced.isEmpty()) {
				return current;
			}
			Features widened = current.with(announced);
			if (widened == current) {
				return current;
			}
			current = widened;
		}
		return current;
	}

	/**
	 * Whether a source holds a top-level push onto {@code *features*} that {@link #widen}
	 * cannot see: one with a computed value, or under a conditional. A compile path opens
	 * its read-time image for such a source.
	 * @param input the source text
	 * @param features the features the source is read with
	 * @param file the origin file, or {@code null} when unknown
	 * @return whether the image has a push to run
	 */
	public static boolean computesAPush(String input, Features features, @Nullable String file) {
		if (!mentionsFeaturesVar(input)) {
			return false;
		}
		return LispReader.scanForms(input, features, file).stream().anyMatch(FeaturePushes::computesAPush);
	}

	/**
	 * Whether a top-level form runs a push onto {@code *features*} that {@link #widen}
	 * cannot see. A definition's body and quoted data do not run here.
	 * @param form the top-level form
	 * @return whether the form computes a push
	 */
	public static boolean computesAPush(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol op)) {
			return false;
		}
		String operator = memberName(op);
		List<LispVal> items = cons.toList();
		if (LispNames.EVAL_WHEN.equals(operator) || LispNames.PROGN.equals(operator)) {
			return items.subList(LispNames.PROGN.equals(operator) ? 1 : 2, items.size())
				.stream()
				.anyMatch(FeaturePushes::computesAPush);
		}
		if (isPush(operator, items) && items.get(1) instanceof LispSymbol value && value.isKeyword()) {
			return false;
		}
		return runsAPush(cons);
	}

	private static boolean runsAPush(LispVal form) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return false;
		}
		List<LispVal> items = cons.toList();
		if (cons.car() instanceof LispSymbol op) {
			String operator = memberName(op);
			if (NOT_RUN.contains(operator)) {
				return false;
			}
			if (isPush(operator, items)) {
				return true;
			}
		}
		return items.stream().anyMatch(FeaturePushes::runsAPush);
	}

	private static boolean isPush(String operator, List<LispVal> items) {
		return (LispNames.PUSHNEW.equals(operator) || LispNames.PUSH.equals(operator)) && items.size() >= 3
				&& isFeaturesVar(items.get(2));
	}

	/**
	 * Whether the source mentions {@code *features*} at all. Cheap enough to run over
	 * every source read, which is the point: the scan below is a second parse.
	 * @param input the source text
	 * @return {@code true} when a feature push is even possible
	 */
	private static boolean mentionsFeaturesVar(String input) {
		// Scanned in place rather than through toUpperCase().contains(): this runs on
		// EVERY source the frontend reads, and a spliced library source is megabytes --
		// an upcased copy of each of them would be the cost of the whole mechanism.
		for (int i = input.indexOf('*'); i >= 0; i = input.indexOf('*', i + 1)) {
			if (input.regionMatches(true, i, LispNames.FEATURES_VAR, 0, LispNames.FEATURES_VAR.length())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The feature names the top-level forms announce, in source order.
	 * @param forms the top-level forms
	 * @return the announced names, without the leading colon
	 */
	private static List<String> collect(List<LispVal> forms) {
		List<String> announced = new ArrayList<>();
		for (LispVal form : forms) {
			collectFrom(form, announced);
		}
		return announced;
	}

	private static void collectFrom(LispVal form, List<String> announced) {
		if (!(form instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol op)) {
			return;
		}
		List<LispVal> items = cons.toList();
		String operator = memberName(op);
		// eval-when's situations are not consulted: unlike a .asd (which ASDF only ever
		// LOADS, so a :compile-toplevel-only push really is inert), a source file goes
		// through whichever of the two CL passes the backend runs, and the announcement
		// idiom is written to fire in both.
		if (LispNames.EVAL_WHEN.equals(operator) || LispNames.PROGN.equals(operator)) {
			for (int i = LispNames.PROGN.equals(operator) ? 1 : 2; i < items.size(); i++) {
				collectFrom(items.get(i), announced);
			}
			return;
		}
		if (!LispNames.PUSHNEW.equals(operator) && !LispNames.PUSH.equals(operator)) {
			return;
		}
		// (push[new] VALUE *features* [keyword args]) -- pushnew's :test/:key are
		// irrelevant to which name lands in the list, so they are simply ignored.
		if (items.size() < 3 || !isFeaturesVar(items.get(2)) || !(items.get(1) instanceof LispSymbol value)
				|| !value.isKeyword()) {
			return;
		}
		announced.add(value.name().substring(1));
	}

	/** Whether the form is a reference to the {@code *features*} variable. */
	private static boolean isFeaturesVar(LispVal form) {
		return form instanceof LispSymbol sym && LispNames.FEATURES_VAR.equals(memberName(sym));
	}

	/** The symbol's name with any package qualifier removed ({@code cl:pushnew}). */
	private static String memberName(LispSymbol symbol) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(symbol.name());
		return qualified == null ? symbol.name() : qualified.member();
	}

}
