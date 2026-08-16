package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The literal spellings a compiled program can hold that still RESOLVE a function name at
 * run time -- the probe list behind both backends' funcall-dispatch gate
 * ({@code Jvm/WasmLispCompiler.dispatchableFuncIds},
 * {@code .kb/optimize-dead-code-elimination.md}).
 *
 * <p>
 * The gate arms a function's dispatcher case and registry row only when the program
 * already emits one of these spellings as a runtime VALUE ({@code Ctx.spelledLiterals}),
 * because {@code _lookup} matches interned offsets (WASM) / string constants (JVM) and a
 * name nothing spells can never be compared equal to a row. The list lives here, in
 * {@code compiler}, for the reason {@link RuntimeNameProducers} does: a spelling that
 * resolves on one backend and not the other is exactly the divergence one shared answer
 * exists to prevent, and the two gates used to carry the same nested ternary twice.
 *
 * <p>
 * The widened spellings -- everything a symbol BUILDER has to be present to reach -- are
 * the ones that are not symbol syntax for the name itself: a framed string literal
 * ({@code "RUN"}, quotes included, how {@code LispString.literal()} interns), and the two
 * package-less symbol spellings whose NAME is the member, {@code :member} and
 * {@code #:member}. Both of the latter reach a function only through
 * {@code (string designator)} + {@code intern}, which is precisely what a builder is, so
 * without one in the program a defun whose member name merely collides with an unrelated
 * literal stays call-only.
 */
public final class DesignatorSpellings {

	private DesignatorSpellings() {
	}

	/**
	 * The spellings that resolve {@code name}, in probe order (most specific first, so
	 * the {@code -Drontolisp.debug.dispatchgate} report names the narrowest match).
	 * @param name the function's canonical name, package-qualified as the backend records
	 * it
	 * @param symbolBuilders whether the program contains a symbol BUILDER
	 * ({@link RuntimeNameProducers#anySymbolBuilder}) -- only then are the widened
	 * spellings included
	 * @return the spellings to probe against the program's spelled literals
	 */
	public static List<String> of(String name, boolean symbolBuilders) {
		List<String> spellings = new ArrayList<>();
		spellings.add(name);
		// The registry's alias row spells an internal name with ONE colon, and a
		// runtime-interned symbol carries that spelling, so an interned alias reaches
		// the function just as its canonical name does.
		int q = name.indexOf("::");
		if (q > 0) {
			spellings.add(name.substring(0, q) + name.substring(q + 1));
		}
		// (intern "EX-FN" :pkg) spells only the MEMBER name at compile time; the run
		// time assembles the qualified one.
		int colon = name.lastIndexOf(':');
		String member = name.substring(colon + 1);
		if (colon >= 0) {
			spellings.add(member);
		}
		if (symbolBuilders) {
			// (intern "RUN" pkg) -- clack's handler discovery -- spells "RUN" framed.
			spellings.add("\"" + name + "\"");
			spellings.add("\"" + member + "\"");
			// (uiop:symbol-call :pkg :member) spells both halves as keywords; the
			// uninterned #:member is the same designator in the spelling a .asd-style
			// caller writes it, (uiop:symbol-call '#:pkg '#:member).
			spellings.add(":" + member);
			spellings.add("#:" + member);
		}
		return spellings;
	}

	/**
	 * Whether any spelling of {@code name} occurs among the program's spelled literals.
	 * @param name the function's canonical name
	 * @param spelledLiterals the spellings Pass 2 emitted as runtime values
	 * @param symbolBuilders whether the program contains a symbol builder
	 * @return true when a runtime designator can resolve the name
	 */
	public static boolean anySpelled(String name, Set<String> spelledLiterals, boolean symbolBuilders) {
		return matched(name, spelledLiterals, symbolBuilders) != null;
	}

	/**
	 * The first spelling of {@code name} the program actually holds, for the
	 * {@code -Drontolisp.debug.dispatchgate} report.
	 * @param name the function's canonical name
	 * @param spelledLiterals the spellings Pass 2 emitted as runtime values
	 * @param symbolBuilders whether the program contains a symbol builder
	 * @return the matching spelling, or null when none is held
	 */
	public static @Nullable String matched(String name, Set<String> spelledLiterals, boolean symbolBuilders) {
		for (String spelling : of(name, symbolBuilders)) {
			if (spelledLiterals.contains(spelling)) {
				return spelling;
			}
		}
		return null;
	}

}
