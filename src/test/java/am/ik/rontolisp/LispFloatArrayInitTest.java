package am.ik.rontolisp;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LispFloatArrayInitTest {

	/**
	 * A sealed supertype with default methods is initialized BEFORE any of its permits
	 * (JLS 12.4.2), so a static field on it that constructs a permit is a class
	 * initialization cycle: one thread initializing {@code LispFloatArray} waits for a
	 * permit that a second thread, first to touch that permit, holds while it waits for
	 * {@code LispFloatArray}. Both wait forever. It took concurrent tests reading a
	 * {@code #bf16(...)} literal and compiling a {@code concatenate} at once to find it.
	 * Every round starts from a fresh loader so each one races an UNINITIALIZED pair.
	 */
	@Test
	void theSupertypeAndAPermitInitializeConcurrentlyWithoutDeadlock() throws Exception {
		URL classes = LispFloatArray.class.getProtectionDomain().getCodeSource().getLocation();
		String[] permits = { "am.ik.rontolisp.LispDoubleFloatArray", "am.ik.rontolisp.LispSingleFloatArray",
				"am.ik.rontolisp.LispBFloat16Array" };
		for (int round = 0; round < 200; round++) {
			try (URLClassLoader loader = new URLClassLoader(new URL[] { classes },
					ClassLoader.getPlatformClassLoader())) {
				List<String> names = new ArrayList<>(List.of(permits));
				names.add(round % names.size(), "am.ik.rontolisp.LispFloatArray");
				CyclicBarrier start = new CyclicBarrier(names.size());
				List<Thread> threads = new ArrayList<>();
				for (String name : names) {
					Thread thread = Thread.ofPlatform().daemon().start(() -> {
						try {
							start.await();
							Class.forName(name, true, loader);
						}
						catch (Exception ex) {
							throw new IllegalStateException(ex);
						}
					});
					threads.add(thread);
				}
				for (Thread thread : threads) {
					thread.join(10_000);
					assertThat(thread.isAlive()).as("round %d: class initialization of %s deadlocked", round, names)
						.isFalse();
				}
			}
		}
	}

}
