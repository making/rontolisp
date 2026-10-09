package am.ik.rontolisp.eval;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureLowering;
import am.ik.rontolisp.clojure.ClojureMacroEvaluator;
import am.ik.rontolisp.reader.LispReadException;
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
 * <p>
 * {@code eval} and {@code resolve} of a computed symbol, which the run-time library
 * refuses, call back into the lowering here ({@link ClojureMacroEvaluator.Lowering}): the
 * form lowers in the namespace of the expansion and evaluates in this environment, like
 * the oracle's {@code eval} inside a macro body.
 */
public final class ClojureMacroTime {

	private ClojureMacroTime() {
	}

	/**
	 * A macro evaluator over a fresh macro-time evaluator, built on first use, its Java
	 * classes rontolisp's own.
	 * @return the evaluator
	 */
	public static ClojureMacroEvaluator create() {
		return new LazyEvaluator(SourceLoader.class.getClassLoader());
	}

	/**
	 * A macro evaluator over a fresh macro-time evaluator, built on first use, whose
	 * {@code java:} calls resolve through the program's Java class loader -- the one its
	 * lowering asks, which the jars of its dependencies join -- so a helper a macro body
	 * calls reaches the classes the program does.
	 * @param javaClasses the program's Java class loader
	 * @return the evaluator
	 */
	public static ClojureMacroEvaluator create(ClassLoader javaClasses) {
		return new LazyEvaluator(javaClasses);
	}

	/** One handed-over definition waiting for the next evaluation. */
	private sealed interface Pending {

	}

	private record Eager(LispVal form) implements Pending {
	}

	private record Lazy(LispSymbol var, LispVal value, boolean special, boolean once) implements Pending {
	}

	/** The run-time library's {@code eval}, refused there. */
	private static final LispSymbol EVAL = new LispSymbol("RONTOLISP::%CLOJURE-EVAL");

	/** The run-time library's {@code resolve} of a computed symbol, refused there. */
	private static final LispSymbol RESOLVE = new LispSymbol("RONTOLISP::%CLOJURE-RESOLVE");

	private static final class LazyEvaluator implements ClojureMacroEvaluator {

		private final ClassLoader javaClasses;

		private @Nullable LispEvaluator evaluator;

		private final List<Pending> pending = new ArrayList<>();

		private ClojureMacroEvaluator.@Nullable Lowering lowering;

		LazyEvaluator(ClassLoader javaClasses) {
			this.javaClasses = javaClasses;
		}

		@Override
		public synchronized LispVal evaluate(LispVal form) {
			LispEvaluator macroEval = this.evaluator;
			if (macroEval == null) {
				macroEval = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
				macroEval.setSourceLoader(SourceLoader.fileSystem(this.javaClasses));
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
				macroEval.eval(callingBack(EVAL, ClojureMacroEvaluator.Lowering::evalForm));
				macroEval.eval(callingBack(RESOLVE, ClojureMacroEvaluator.Lowering::resolveForm));
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

		/**
		 * The definition replacing a run-time library function that refuses at run time
		 * by one evaluating the form the lowering answers for its argument. A lowering
		 * error becomes an ordinary error, so the macro body may catch it.
		 */
		private LispVal callingBack(LispSymbol name,
				BiFunction<ClojureMacroEvaluator.Lowering, LispVal, LispVal> form) {
			LispFunction body = new LispFunction(name.name(), args -> {
				ClojureMacroEvaluator.Lowering current = this.lowering;
				if (current == null) {
					throw new LispEvalException(name.name() + ": no lowering drives this macro-time evaluator");
				}
				LispVal lowered;
				try {
					lowered = form.apply(current, args.get(0));
				}
				catch (LispReadException ex) {
					throw new LispEvalException(ex.getMessage() == null ? ex.toString() : ex.getMessage());
				}
				return evaluate(lowered);
			});
			LispSymbol arg = new LispSymbol("FORM");
			return list(new LispSymbol("DEFUN"), name, list(arg),
					list(new LispSymbol("FUNCALL"), list(new LispSymbol("QUOTE"), body), arg));
		}

		private static LispVal list(LispVal... items) {
			LispVal out = LispNil.INSTANCE;
			for (int i = items.length - 1; i >= 0; i--) {
				out = new LispCons(items[i], out);
			}
			return out;
		}

		@Override
		public synchronized void lowerThrough(ClojureMacroEvaluator.Lowering lowering) {
			this.lowering = lowering;
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
