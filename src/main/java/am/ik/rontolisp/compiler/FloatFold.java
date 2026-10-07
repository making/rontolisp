package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import am.ik.rontolisp.LispVal;

/**
 * Where a float site's raw fold may start, and which of its operands have to wait: the
 * backend-shared half of the compiled {@code + - * /} over a float
 * (`.kb/jvm-double-arithmetic.md`, "The exact prefix").
 *
 * <p>
 * Common Lisp's n-ary arithmetic is a left fold of its two-argument step, and a step is a
 * float step only once one of its two operands is a float, so the arguments ahead of the
 * first float fold exactly: {@code (+ 1/10 1/5 0.0)} is {@code 0.3}, where converting
 * every argument first answers {@code 0.30000000000000004}. The raw {@code f64} fold
 * converts every operand where it stands, which is that fold exactly when one of the
 * first two operands is a float -- the first step is then a float step, and so is every
 * step after it. A site that proves this keeps the raw fold from its first operand; any
 * other site folds its operands ahead of the first proven float through the generic
 * helpers, and converts only their result.
 */
public final class FloatFold {

	private FloatFold() {
	}

	/**
	 * The number of leading operands a float site folds through the generic helpers
	 * before its raw fold: {@code 0} when one of the first two operands is proven a float
	 * (the raw fold then runs from the first operand), else the index of the first
	 * operand proven a float, or every operand when none is.
	 * @param operands the operand forms, in source order
	 * @param provenFloat whether the backend proves an operand's value a float
	 * @return the length of the exact prefix, {@code 0} or at least {@code 2}
	 */
	public static int exactPrefix(List<LispVal> operands, Predicate<LispVal> provenFloat) {
		int count = operands.size();
		for (int i = 0; i < count; i++) {
			if (provenFloat.test(operands.get(i))) {
				return i <= 1 ? 0 : i;
			}
		}
		return count < 2 ? 0 : count;
	}

	/**
	 * Whether the generic step that folds operand {@code i} into the exact prefix ahead
	 * of it may signal: a division may divide by an exact zero, and any step may meet a
	 * value that is not a number unless every operand so far is certainly real
	 * ({@link ArgumentOrder#isRealValued}).
	 * @param operands the operand forms, in source order
	 * @param i the step's operand index, at least 1
	 * @param dividing whether the operator divides ({@code /}, {@code mod}, {@code rem})
	 * @return whether the step may signal
	 */
	public static boolean stepMayFail(List<LispVal> operands, int i, boolean dividing) {
		if (dividing) {
			return true;
		}
		for (int j = 0; j <= i; j++) {
			if (!ArgumentOrder.isRealValued(operands.get(j))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Which operands of a site wait in temporaries: an operation applies, and signals,
	 * only once every argument is evaluated (`.kb/argument-evaluation-order.md`, "An
	 * operation applies after its operands"), so an action that may signal -- a
	 * conversion, a generic step -- must not run before a later operand whose evaluation
	 * can be observed. The operands from the first such action up to the last observable
	 * operand are evaluated in order first, and their actions run afterwards; every other
	 * operand is acted on where it stands.
	 * @param count the number of operands
	 * @param observable whether evaluating operand {@code i} can be observed by a later
	 * one
	 * @param mayFail whether the action that follows operand {@code i} may signal
	 * @return the waiting range
	 */
	public static Waiting waiting(int count, IntPredicate observable, IntPredicate mayFail) {
		int lastObservable = -1;
		for (int i = 0; i < count; i++) {
			if (observable.test(i)) {
				lastObservable = i;
			}
		}
		for (int i = 0; i < lastObservable; i++) {
			if (mayFail.test(i)) {
				return new Waiting(i, lastObservable);
			}
		}
		return new Waiting(-1, -1);
	}

	/**
	 * The operands that wait: {@code first} to {@code last} inclusive, none when
	 * {@code first} is negative.
	 *
	 * @param first the first waiting operand, or -1
	 * @param last the last waiting operand, the last observable one
	 */
	public record Waiting(int first, int last) {

		/**
		 * Whether operand {@code i} waits.
		 * @param i the operand's index
		 * @return whether it is evaluated into a temporary and acted on later
		 */
		public boolean waits(int i) {
			return this.first >= 0 && i >= this.first && i <= this.last;
		}

	}

}
