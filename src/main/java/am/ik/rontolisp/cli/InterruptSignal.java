package am.ik.rontolisp.cli;

import am.ik.rontolisp.eval.LispEvaluator;
import sun.misc.Signal;

/**
 * SIGINT as {@code rontolisp:interactive-interrupt} on an interpreted program with a
 * handler naming it ({@code .kb/error-handling.md}, "SIGINT"). The host's own SIGINT
 * stands until that handler is established: it ends the process wherever the program is
 * blocked, and a request the program never reaches cannot.
 */
final class InterruptSignal {

	private static final int SIGINT_STATUS = 130;

	private InterruptSignal() {
	}

	/**
	 * Asks the evaluator to signal each SIGINT on the calling thread once a handler on
	 * that thread names the condition. A SIGINT arriving before the thread reached the
	 * last one, or after it ended, ends the process as the host would.
	 * @param evaluator the evaluator running the program
	 */
	static void listenFor(LispEvaluator evaluator) {
		evaluator.onInterruptHandler(() -> {
			Thread target = Thread.currentThread();
			try {
				Signal.handle(new Signal("INT"), (signal) -> {
					if (!target.isAlive() || !evaluator.signalInterrupt(target)) {
						System.exit(SIGINT_STATUS);
					}
				});
			}
			catch (IllegalArgumentException ex) {
				// The host keeps SIGINT to itself (-Xrs), so it ends the process as ever.
			}
		});
	}

}
