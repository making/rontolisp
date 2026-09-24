package am.ik.rontolisp.testsupport;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;

import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.support.hierarchical.ParallelExecutionConfiguration;
import org.junit.platform.engine.support.hierarchical.ParallelExecutionConfigurationStrategy;

/**
 * Derives the intra-class parallelism from the machine the build runs on instead of
 * hard-coding it, wired in from {@code src/test/resources/junit-platform.properties}.
 *
 * <p>
 * Neither built-in strategy can express what this build wants. {@code fixed} pins one
 * number, which is either wasteful on a laptop or leaves a 64-core machine idle, and
 * {@code dynamic} multiplies the core count without an upper bound -- its
 * {@code max-pool-size-factor} bounds the ForkJoinPool's compensation threads, not the
 * parallelism. The rule here is a clamped core count, which reproduces both numbers the
 * build was previously tuned to by hand: {@value #MAXIMUM} on the 64-core development
 * machine, and 4 on the 4 vCPU CI runner.
 *
 * <p>
 * The core count is what is FREE when the fork starts -- the processors minus the
 * 1-minute load average -- because several builds share one development machine and a
 * count taken from the hardware alone oversubscribes it the moment a second suite runs.
 * The load average is -1 where the OS has none, which reads as an idle machine. The
 * surefire fork count is sized the same way ({@code size-test-forks} in {@code pom.xml}).
 *
 * <p>
 * The bounds. The work these threads do is a compile plus a {@code wasmtime} run per
 * method, so it is only partly CPU-bound and one thread per core across two surefire
 * forks -- twice oversubscribed -- was measured as no worse than half that on CI. That is
 * why the factor is 1 rather than something smaller. {@value #MAXIMUM} is where more
 * threads stopped helping on the development machine; {@value #MINIMUM} keeps a
 * single-core box from serializing behind one guest process.
 *
 * <p>
 * {@code junit.jupiter.execution.parallel.config.fixed.parallelism} still wins when set,
 * so a run can be pinned to one value on the command line -- both to reproduce a
 * thread-count-sensitive failure and to compare two machines on equal terms.
 */
public final class CoreCountParallelismStrategy implements ParallelExecutionConfigurationStrategy {

	/** Floor, so a one- or two-core machine still overlaps guest process waits. */
	static final int MINIMUM = 2;

	/**
	 * Ceiling; beyond this the Docker daemon and the guest runs, not the JVM, are the
	 * limit.
	 */
	static final int MAXIMUM = 16;

	/**
	 * Pins the parallelism, bypassing the core count -- spelled in full,
	 * {@code junit.jupiter.execution.parallel.config.fixed.parallelism}, on the command
	 * line. Named for the {@code fixed} strategy on purpose: it is the property every
	 * JUnit user already reaches for, and the build gains nothing from a second spelling
	 * of the same knob.
	 *
	 * <p>
	 * The key is relative because the {@link ConfigurationParameters} a strategy receives
	 * is scoped to the {@code junit.jupiter.execution.parallel.config.} prefix -- looking
	 * up the full name here finds nothing and silently ignores the override, which is how
	 * JUnit's own strategies read {@code fixed.parallelism} and {@code dynamic.factor}.
	 */
	static final String OVERRIDE_PROPERTY = "fixed.parallelism";

	@Override
	public ParallelExecutionConfiguration createConfiguration(ConfigurationParameters configurationParameters) {
		int availableProcessors = Runtime.getRuntime().availableProcessors();
		double loadAverage = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
		int parallelism = configurationParameters.get(OVERRIDE_PROPERTY, Integer::valueOf)
			.orElseGet(() -> parallelismFor(availableProcessors, loadAverage));
		// The value now differs per machine and per moment, and the CI notes warn against
		// comparing runs that disagree on it, so every run has to say which one it used.
		System.out.println("[rontolisp] JUnit parallelism = %d (availableProcessors = %d, load average = %.2f)"
			.formatted(parallelism, availableProcessors, loadAverage));
		return new CoreCountConfiguration(parallelism);
	}

	static int parallelismFor(int availableProcessors, double loadAverage) {
		long free = availableProcessors - Math.round(Math.max(loadAverage, 0.0));
		return Math.clamp(free, MINIMUM, MAXIMUM);
	}

	/**
	 * The same shape JUnit's own {@code fixed} strategy builds -- a ForkJoinPool whose
	 * core size is the parallelism, with room above it for compensation threads.
	 */
	private record CoreCountConfiguration(int parallelism) implements ParallelExecutionConfiguration {

		@Override
		public int getParallelism() {
			return this.parallelism;
		}

		@Override
		public int getMinimumRunnable() {
			return this.parallelism;
		}

		@Override
		public int getMaxPoolSize() {
			return 256 + this.parallelism;
		}

		@Override
		public int getCorePoolSize() {
			return this.parallelism;
		}

		@Override
		public int getKeepAliveSeconds() {
			return 30;
		}

		@Override
		public Predicate<? super ForkJoinPool> getSaturatePredicate() {
			return pool -> true;
		}
	}

}
