package am.ik.rontolisp.testsupport;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.support.config.PrefixedConfigurationParameters;
import org.junit.platform.engine.support.hierarchical.ParallelExecutionConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class CoreCountParallelismStrategyTest {

	/** What JUnit scopes the parameters to before handing them to a strategy. */
	private static final String CONFIG_PREFIX = "junit.jupiter.execution.parallel.config.";

	@ParameterizedTest
	@CsvSource({ "1, 2", "2, 2", "3, 3", "4, 4", "8, 8", "16, 16", "17, 16", "64, 16", "256, 16" })
	void scalesWithTheCoreCountBetweenTheBounds(int availableProcessors, int expected) {
		assertThat(CoreCountParallelismStrategy.parallelismFor(availableProcessors)).isEqualTo(expected);
	}

	@Test
	void defaultsToTheCoreCountOfThisMachine() {
		ParallelExecutionConfiguration configuration = new CoreCountParallelismStrategy()
			.createConfiguration(parameters(Map.of()));
		assertThat(configuration.getParallelism())
			.isEqualTo(CoreCountParallelismStrategy.parallelismFor(Runtime.getRuntime().availableProcessors()));
	}

	/**
	 * The properties are named in full here, and the prefix scoping the strategy sees is
	 * applied on top, so this fails if the lookup key is ever "corrected" to the full
	 * property name -- which reads fine and silently drops the override.
	 */
	@Test
	void anExplicitParallelismWinsOverTheCoreCount() {
		ParallelExecutionConfiguration configuration = new CoreCountParallelismStrategy()
			.createConfiguration(parameters(Map.of(CONFIG_PREFIX + "fixed.parallelism", "3")));
		assertThat(configuration.getParallelism()).isEqualTo(3);
	}

	@Test
	void sizesThePoolLikeTheFixedStrategy() {
		ParallelExecutionConfiguration configuration = new CoreCountParallelismStrategy()
			.createConfiguration(parameters(Map.of(CONFIG_PREFIX + "fixed.parallelism", "5")));
		assertThat(configuration.getCorePoolSize()).isEqualTo(5);
		assertThat(configuration.getMinimumRunnable()).isEqualTo(5);
		assertThat(configuration.getMaxPoolSize()).isEqualTo(261);
		assertThat(configuration.getKeepAliveSeconds()).isEqualTo(30);
		Predicate<? super ForkJoinPool> saturate = Objects.requireNonNull(configuration.getSaturatePredicate());
		assertThat(saturate.test(ForkJoinPool.commonPool())).isTrue();
	}

	private static ConfigurationParameters parameters(Map<String, String> values) {
		return new PrefixedConfigurationParameters(new ConfigurationParameters() {
			@Override
			public Optional<String> get(String key) {
				return Optional.ofNullable(values.get(key));
			}

			@Override
			public Optional<Boolean> getBoolean(String key) {
				return this.get(key).map(Boolean::valueOf);
			}

			@Override
			public Set<String> keySet() {
				return values.keySet();
			}
		}, CONFIG_PREFIX);
	}

}
