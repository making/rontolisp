package am.ik.rontolisp.eval;

import java.util.Random;

/**
 * A measurement of what {@code --parallel}'s thread count costs when the box is not idle.
 * The pool's workers spin on an epoch and the caller spins until the LAST leaf of a call
 * is done, so a worker descheduled while it holds a leaf stalls the whole call for a
 * scheduler quantum -- and with {@code threads == availableProcessors} and anything else
 * runnable, that happens on nearly every call.
 *
 * <p>
 * The arm is the shipped f32 GEMV ({@link VecSimdKernels#matvecIntoF}, parallel) run in a
 * decode-shaped loop: a call, then a stretch of scalar work standing in for the boxed
 * Lisp a real decode loop runs between its matrix products (the gap is what lets a worker
 * be preempted between calls and still hold a leaf on the next one). Beside it run
 * {@code busy} pure-CPU threads, the "another lane's build" of the report.
 *
 * <p>
 * The figure of merit is the MEAN over the rounds, not the best of them: a decode loop
 * pays every call, and a best-of-N summary hides exactly the stall being measured. The
 * best round is printed beside it so the spread is visible.
 *
 * <pre>{@code
 * ./mvnw -o test-compile
 * CP=target/classes:target/test-classes
 * RONTOLISP_THREADS=64 java --add-modules jdk.incubator.vector -cp $CP \
 *   am.ik.rontolisp.eval.ParallelContentionBench <size> <busy> <gap-us>
 * }</pre>
 *
 * The thread count is read once per JVM ({@code VecSimd}'s pool is a static), so a sweep
 * over counts is a sweep over processes. Results and conditions are in
 * {@code .todo/artefacts/697-the-parallel-default-thread-count-on-a-shared-box/README.md},
 * not here -- including the two caller-side straggler fixes this bench rejected, which is
 * what to read before proposing a third.
 */
public final class ParallelContentionBench {

	private ParallelContentionBench() {
	}

	private static volatile long sink;

	/** Burns roughly {@code us} microseconds of scalar work on the calling thread. */
	private static void gap(long us) {
		if (us <= 0) {
			return;
		}
		long deadline = System.nanoTime() + us * 1000L;
		long x = sink;
		while (System.nanoTime() < deadline) {
			for (int i = 0; i < 64; i++) {
				x = x * 6364136223846793005L + 1442695040888963407L;
			}
		}
		sink = x;
	}

	public static void main(String[] args) throws Exception {
		int n = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
		int busy = args.length > 1 ? Integer.parseInt(args[1]) : 0;
		long gapUs = args.length > 2 ? Long.parseLong(args[2]) : 100;
		int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 5;

		Thread[] hogs = new Thread[busy];
		for (int i = 0; i < busy; i++) {
			hogs[i] = new Thread(() -> {
				long x = 1;
				while (!Thread.currentThread().isInterrupted()) {
					for (int k = 0; k < 4096; k++) {
						x = x * 6364136223846793005L + 1442695040888963407L;
					}
					sink = x;
				}
			}, "bench-hog-" + i);
			hogs[i].setDaemon(true);
			hogs[i].start();
		}

		long elements = (long) n * n;
		Random random = new Random(11);
		float[] w = new float[(int) elements];
		for (int i = 0; i < elements; i++) {
			w[i] = (float) (random.nextGaussian() * 0.02);
		}
		float[] x = new float[n];
		for (int i = 0; i < n; i++) {
			x[i] = (float) random.nextGaussian();
		}
		float[] r = new float[n];

		int iterations = elements <= (1 << 20) ? 200 : elements <= (1 << 22) ? 50 : 20;
		for (int i = 0; i < 40; i++) {
			VecSimdKernels.matvecIntoF(r, w, n, n, x, true);
		}
		long total = 0;
		long best = Long.MAX_VALUE;
		for (int round = 0; round < rounds; round++) {
			long start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				VecSimdKernels.matvecIntoF(r, w, n, n, x, true);
				gap(gapUs);
			}
			long ns = (System.nanoTime() - start) / iterations;
			total += ns;
			best = Math.min(best, ns);
		}
		long mean = total / rounds;
		double call = mean - gapUs * 1000.0;
		System.out.printf("threads=%-8s busy=%-3d gap=%dus shape=%dx%d  mean %.3f ms/call  best %.3f  %.2f Gelem/s%n",
				System.getenv().getOrDefault("RONTOLISP_THREADS", "default"), busy, gapUs, n, n, call / 1e6,
				(best - gapUs * 1000.0) / 1e6, elements / call);
		for (Thread hog : hogs) {
			hog.interrupt();
		}
	}

}
