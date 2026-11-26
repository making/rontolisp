package am.ik.rontolisp.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a test's INDEPENDENT evaluations at once -- the flag-on and flag-off runs of one
 * program, or a table of programs that share nothing -- and answers their results in
 * submission order, so the assertions after it read exactly as the sequential loop did.
 *
 * <p>
 * The tasks run on at most one platform thread per core (default stack, which is what the
 * JUnit worker they replace had): they are CPU-bound, so more threads buy nothing and
 * would only hold more evaluators' heaps at once on a small CI runner. Only for work that
 * owns every piece of state it touches: its own {@code LispEvaluator}, its own output
 * stream, no {@code System.setOut}, no relative path, no fixed file name
 * (.kb/test-execution.md).
 */
public final class Concurrently {

	private Concurrently() {
	}

	/**
	 * Runs every task, waits for all of them, and answers their results in order. The
	 * first task (in submission order) that failed has its failure rethrown -- an
	 * {@link Error} or unchecked exception as itself, so an AssertJ failure inside a task
	 * surfaces unchanged.
	 * @param <T> the result type
	 * @param tasks the tasks
	 * @return each task's result, in the order the tasks were given
	 */
	public static <T> List<T> all(List<? extends Callable<? extends T>> tasks) {
		int threads = Math.max(1, Math.min(tasks.size(), Runtime.getRuntime().availableProcessors()));
		try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
			List<Future<? extends T>> futures = new ArrayList<>();
			for (Callable<? extends T> task : tasks) {
				futures.add(executor.submit(task));
			}
			List<T> results = new ArrayList<>();
			for (Future<? extends T> future : futures) {
				try {
					results.add(future.get());
				}
				catch (ExecutionException ex) {
					Throwable cause = ex.getCause();
					if (cause instanceof Error error) {
						throw error;
					}
					if (cause instanceof RuntimeException runtime) {
						throw runtime;
					}
					throw new IllegalStateException(cause);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
			}
			return results;
		}
	}

}
