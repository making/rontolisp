package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.HostTimer;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * The {@code --host-timer} imports of a {@code --no-wasi} wasm-GC core module
 * ({@link HostTimer}): {@code rontolisp:wasm-import} directives over the host's waits,
 * appended where the program waits, so a program that does not imports nothing. A
 * {@code sleep} is lowered to {@code env.sleep} by the backend once its directive is
 * here; the Java front end's run time idles a request's call and rings another's through
 * {@code env.idle} and {@code env.ring} ({@code java.lisp}'s
 * {@code #+rontolisp-host-timer} arm, {@code .kb/java-frontend.md}, "A server as a
 * reactor").
 *
 * <p>
 * Directives rather than imports the backend appends: each rides the ordinary
 * synthetic-defun machinery -- its own type entry, an ordinal after the program's own, a
 * wrapper the tree shaker drops with its import where nothing calls it -- and the
 * {@code :async t} ones are the suspending imports every other analysis already reads
 * (the exports entered through {@code WebAssembly.promising}, the re-entry guard). What
 * the backend and the glue add is keyed on {@link HostTimer}'s names.
 */
public final class HostTimerLibrary {

	private static final List<LispVal> SLEEP = forms(
			directive(HostTimer.SLEEP_FUNCTION, HostTimer.SLEEP_FIELD, "(:float)", true));

	private static final List<LispVal> IDLE = forms(
			directive(HostTimer.IDLE_FUNCTION, HostTimer.IDLE_FIELD, "(:s32 :s64)", true)
					+ directive(HostTimer.RING_FUNCTION, HostTimer.RING_FIELD, "(:s32)", false));

	/** The names a program waits for a time by. */
	private static final Set<String> SLEEPING = Set.of(LispNames.SLEEP, HostTimer.SLEEP_FUNCTION);

	/** The names a program idles a call or rings one by. */
	private static final Set<String> IDLING = Set.of(HostTimer.IDLE_FUNCTION, HostTimer.RING_FUNCTION);

	private HostTimerLibrary() {
	}

	/**
	 * Appends the imports the program waits through: {@code env.sleep} where it names
	 * {@code sleep}, {@code env.idle} and {@code env.ring} where it names the functions
	 * over them. Quoted data counts: a designator that is never called costs the import
	 * alone, which the tree shaker drops.
	 * @param program the top-level forms, the run-time libraries spliced
	 * @return the program, with the directives it needs appended
	 */
	public static List<LispVal> process(List<LispVal> program) {
		boolean sleeps = false;
		boolean idles = false;
		for (LispVal form : program) {
			sleeps = sleeps || names(form, SLEEPING);
			idles = idles || names(form, IDLING);
		}
		if (!sleeps && !idles) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(program);
		if (sleeps) {
			out.addAll(SLEEP);
		}
		if (idles) {
			out.addAll(IDLE);
		}
		return out;
	}

	private static String directive(String function, String field, String params, boolean async) {
		return "(rontolisp:wasm-import '" + function.toLowerCase(java.util.Locale.ROOT) + " :from \"" + HostTimer.MODULE
				+ "\" :as \"" + field + "\" :params '" + params + " :returns :void" + (async ? " :async t" : "")
				+ ")\n";
	}

	private static List<LispVal> forms(String source) {
		return List.copyOf(LispReader.readAllFromString(source, Features.WASM_REACTOR));
	}

	private static boolean names(LispVal form, Set<String> names) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (names(cons.car(), names)) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && names.contains(symbol.name());
	}

}
