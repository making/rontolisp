package am.ik.rontolisp.eval;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureLowering;
import am.ik.rontolisp.clojure.ClojureMacroEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * The Clojure front end's macro-time evaluator: what a {@code defmacro} body runs against
 * when a call site expands at lower time. One evaluator per file or session, created
 * lazily on the first expansion (a macro-free program never pays for it), holding the
 * false value, the spliced {@code clojure.lisp} library and every per-program runtime
 * beside the core builtins -- the same environment every backend's expansion agrees on,
 * since expansion happens before the backends.
 * <p>
 * The program's top-level definitions join it as the lowering hands them over, like the
 * oracle's form-by-form load: they queue, and run in order before the next evaluation, so
 * a macro body calls a helper {@code defn} above it -- its own file's, a required
 * namespace's, an earlier session buffer's. A {@code def}'s value form runs only when an
 * expansion reads the var.
 */
public final class ClojureMacroTime {

	private ClojureMacroTime() {
	}

	/**
	 * A macro evaluator over a fresh macro-time evaluator, built on first use.
	 * @return the evaluator
	 */
	public static ClojureMacroEvaluator create() {
		return new LazyEvaluator();
	}

	/** One handed-over definition waiting for the next evaluation. */
	private sealed interface Pending {

	}

	private record Eager(LispVal form) implements Pending {
	}

	private record Lazy(LispSymbol var, LispVal value, boolean special, boolean once) implements Pending {
	}

	private static final class LazyEvaluator implements ClojureMacroEvaluator {

		private @Nullable LispEvaluator evaluator;

		private final List<Pending> pending = new ArrayList<>();

		@Override
		public synchronized LispVal evaluate(LispVal form) {
			LispEvaluator macroEval = this.evaluator;
			if (macroEval == null) {
				macroEval = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
				for (LispVal library : ClojureLibrary.forms()) {
					macroEval.eval(library);
				}
				macroEval.eval(ClojureLowering.falseBindingForm());
				for (LispVal counter : ClojureLowering.coreSpecialForms()) {
					macroEval.eval(counter);
				}
				for (LispVal runtime : ClojureLowering.macroTimeRuntimeForms()) {
					macroEval.eval(runtime);
				}
				this.evaluator = macroEval;
			}
			for (Pending definition : this.pending) {
				switch (definition) {
					case Eager eager -> {
						try {
							macroEval.eval(eager.form());
						}
						catch (RuntimeException ex) {
							// only the run time can make it; an expansion reading it
							// reports what it misses
						}
					}
					case Lazy lazy ->
						macroEval.defineLazyGlobal(lazy.var().name(), lazy.value(), lazy.special(), lazy.once());
				}
			}
			this.pending.clear();
			return macroEval.eval(form);
		}

		@Override
		public synchronized void define(LispVal form) {
			this.pending.add(new Eager(form));
		}

		@Override
		public synchronized void defineLazy(LispSymbol var, LispVal value, boolean special, boolean once) {
			this.pending.add(new Lazy(var, value, special, once));
		}

	}

}
