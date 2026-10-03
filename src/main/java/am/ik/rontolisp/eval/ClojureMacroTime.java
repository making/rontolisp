package am.ik.rontolisp.eval;

import java.io.OutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureLowering;
import am.ik.rontolisp.clojure.ClojureMacroEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * The Clojure front end's macro-time evaluator: what a {@code defmacro} body runs against
 * when a call site expands at lower time. One evaluator per file or session, created
 * lazily on the first expansion (a macro-free program never pays for it), holding the
 * false value and the spliced {@code clojure.lisp} library beside the core builtins --
 * the same environment every backend's expansion agrees on, since expansion happens
 * before the backends. A macro body sees the core builtins and the library, not the
 * program's own definitions.
 */
public final class ClojureMacroTime {

	private ClojureMacroTime() {
	}

	/**
	 * A macro evaluator over a fresh macro-time evaluator, built on first use.
	 * @return the evaluator
	 */
	public static ClojureMacroEvaluator create() {
		return new LazyEvaluator()::evaluate;
	}

	private static final class LazyEvaluator {

		private @Nullable LispEvaluator evaluator;

		synchronized LispVal evaluate(LispVal form) {
			if (this.evaluator == null) {
				LispEvaluator macroEval = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
				for (LispVal library : ClojureLibrary.forms()) {
					macroEval.eval(library);
				}
				macroEval.eval(ClojureLowering.falseBindingForm());
				for (LispVal counter : ClojureLowering.coreSpecialForms()) {
					macroEval.eval(counter);
				}
				this.evaluator = macroEval;
			}
			return this.evaluator.eval(form);
		}

	}

}
