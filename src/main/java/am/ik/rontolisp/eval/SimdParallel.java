package am.ik.rontolisp.eval;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.jspecify.annotations.Nullable;

/**
 * The row-parallel dispatch behind the interpreter's {@code --parallel}: the GEMV and
 * GEMM kernels of {@link VecSimdKernels} / {@link LinalgSimdKernels} are independent row
 * chains, so {@link #rows} runs such a kernel over a row range per thread and the result
 * is bit-identical to the serial call -- which is the whole contract, and why no
 * reduction goes through here (there the fold order IS the value). The twin of the
 * "row-parallel dispatch" section of {@code codegen.jvm.JvmSimdVectorTemplate}, operation
 * for operation: {@code RONTOLISP_THREADS - 1} daemon workers that spin on an epoch for
 * the next call and park only after {@link #SPIN_NANOS} idle (a {@code ForkJoinPool}
 * worker parks within microseconds, and a program that runs Lisp between its products
 * then paid the unpark chain on every call and lost to the serial kernel); the rows of a
 * call are claimed in grain-sized leaves off one counter by the caller and the workers
 * alike, and the caller spins until the last leaf is done. The thresholds are the
 * template's -- the dispatch floor is ~3 us, so a call below {@link #MIN_WORK}
 * multiply-adds is not split, a leaf holds at least {@link #GRAIN} of them and at most
 * {@link #LEAVES_PER_THREAD} leaves per thread exist. No thread exists until the first
 * call worth splitting, and none at {@code RONTOLISP_THREADS=1}. The measurements are in
 * {@code .kb/simd-parallel.md}.
 */
final class SimdParallel {

	/** A kernel over the rows {@code [from, to)} of a call. */
	@FunctionalInterface
	interface RowKernel {

		void rows(int from, int to);

	}

	/**
	 * One call: its kernel, its shape and its own counters, read once per call by each
	 * worker.
	 */
	private record Job(RowKernel body, int rows, int grain, AtomicInteger next, AtomicInteger pending,
			AtomicReference<@Nullable Throwable> failure) {
	}

	/**
	 * Multiply-adds below which a call is not split: a 288x288 GEMV pays, a 128x128 one
	 * does not.
	 */
	static final long MIN_WORK = 1L << 15;

	/** Multiply-adds a leaf holds at least. */
	static final long GRAIN = 1L << 13;

	/** Leaves per thread at most: bounds the claim-counter traffic of a large call. */
	static final int LEAVES_PER_THREAD = 4;

	/** How long an idle worker keeps spinning for the next call before it parks. */
	static final long SPIN_NANOS = 1_000_000L;

	private static volatile int threads;

	private static Thread @Nullable [] workers;

	private static @Nullable AtomicIntegerArray parked;

	private static volatile long epoch;

	private static volatile @Nullable Job job;

	private SimdParallel() {
	}

	/**
	 * Whether a call of {@code rows} rows of {@code workPerRow} multiply-adds each is
	 * worth splitting: enough work, more than one row, more than one thread.
	 * @param rows the row count
	 * @param workPerRow the multiply-adds of one row
	 * @return {@code true} when {@link #rows} should run it
	 */
	static boolean worth(int rows, long workPerRow) {
		return rows >= 2 && rows * workPerRow >= MIN_WORK && threads() > 1;
	}

	/**
	 * The thread count {@code RONTOLISP_THREADS} asked for (the calling thread included),
	 * defaulting to the available processors; read once, and the workers start then.
	 * @return the thread count, at least 1
	 */
	static int threads() {
		int t = threads;
		if (t == 0) {
			synchronized (SimdParallel.class) {
				t = threads;
				if (t == 0) {
					t = Runtime.getRuntime().availableProcessors();
					String env = System.getenv("RONTOLISP_THREADS");
					if (env != null && !env.isBlank()) {
						try {
							t = Integer.parseInt(env.trim());
						}
						catch (NumberFormatException ex) {
							System.err
								.println("RONTOLISP_THREADS=" + env + " is not a number; using " + t + " threads");
						}
					}
					t = Math.max(1, t);
					if (t > 1) {
						Thread[] started = new Thread[t - 1];
						parked = new AtomicIntegerArray(started.length);
						for (int i = 0; i < started.length; i++) {
							int id = i;
							Thread worker = new Thread(() -> worker(id), "rontolisp-parallel-" + i);
							worker.setDaemon(true);
							started[i] = worker;
						}
						workers = started;
						for (Thread worker : started) {
							worker.start();
						}
					}
					threads = t;
				}
			}
		}
		return t;
	}

	/**
	 * Runs {@code body} over {@code [0, rows)} in leaves claimed by the calling thread
	 * and the workers together. Only to be called when {@link #worth} said so. One call
	 * at a time: a second calling thread waits for the first call to finish.
	 * @param rows the row count
	 * @param workPerRow the multiply-adds of one row (sizes the leaves)
	 * @param body the kernel
	 */
	static void rows(int rows, long workPerRow, RowKernel body) {
		synchronized (SimdParallel.class) {
			int t = threads();
			int grain = (int) Math.max(Math.max(1, GRAIN / workPerRow), rows / (LEAVES_PER_THREAD * t));
			Job current = new Job(body, rows, grain, new AtomicInteger(), new AtomicInteger(rows),
					new AtomicReference<>());
			job = current;
			epoch++;
			Thread[] all = java.util.Objects.requireNonNull(workers);
			AtomicIntegerArray flags = java.util.Objects.requireNonNull(parked);
			for (int i = 0; i < all.length; i++) {
				if (flags.get(i) != 0) {
					LockSupport.unpark(all[i]);
				}
			}
			claim(current);
			while (current.pending().get() != 0) {
				Thread.onSpinWait();
			}
			Throwable failed = current.failure().get();
			if (failed != null) {
				throw failed instanceof RuntimeException re ? re : new IllegalStateException(failed);
			}
		}
	}

	/** Claims and runs grain-sized leaves of the job until none is left. */
	private static void claim(Job job) {
		while (true) {
			int from = job.next().getAndAdd(job.grain());
			if (from >= job.rows()) {
				return;
			}
			int to = Math.min(job.rows(), from + job.grain());
			try {
				job.body().rows(from, to);
			}
			catch (Throwable ex) {
				job.failure().compareAndSet(null, ex);
			}
			finally {
				job.pending().addAndGet(from - to);
			}
		}
	}

	/**
	 * A worker: spin on the epoch, park after the spin budget, claim the call's leaves.
	 */
	private static void worker(int id) {
		AtomicIntegerArray flags = java.util.Objects.requireNonNull(parked);
		long seen = 0;
		int spins = 0;
		while (true) {
			long deadline = System.nanoTime() + SPIN_NANOS;
			long now;
			while ((now = epoch) == seen) {
				// Spinning workers must not crowd out the caller, the JIT, the GC or a
				// device driver's threads on a box they fill: yield every few dozen
				// spins, which costs nothing on an idle core and gives way on a busy one.
				if ((++spins & 63) == 0) {
					Thread.yield();
				}
				else {
					Thread.onSpinWait();
				}
				if (System.nanoTime() > deadline) {
					// Publish "parked" before the last look at the epoch: the caller
					// bumps the epoch before it scans the flags, so one of the two
					// sides always sees the other (Dekker), and a stale permit only
					// costs one more look.
					flags.set(id, 1);
					if (epoch == seen) {
						LockSupport.park();
					}
					flags.set(id, 0);
					deadline = System.nanoTime() + SPIN_NANOS;
				}
			}
			seen = now;
			Job current = job;
			if (current != null) {
				claim(current);
			}
		}
	}

}
