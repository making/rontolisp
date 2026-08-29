package am.ik.rontolisp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order contracts of {@link PackageRegistry} helpers whose result is carried around
 * in a data model -- see {@code .kb/emitted-output-determinism.md}.
 */
class PackageRegistryTest {

	@Test
	void bothSpellingsOfAQualifiedNameComeBackInAFixedOrder() {
		// A List, not a Set: a two-element Set.of iterates in a per-JVM-run order, and
		// the pruners that ask this question keep the answer in their data model.
		assertThat(PackageRegistry.spellings("uiop:getenv")).containsExactly("uiop:getenv", "uiop::getenv");
		assertThat(PackageRegistry.spellings("uiop::getenv")).containsExactly("uiop:getenv", "uiop::getenv");
	}

	@Test
	void anUnqualifiedNameIsItsOwnAndOnlySpelling() {
		assertThat(PackageRegistry.spellings("car")).containsExactly("car");
	}

}
