package am.ik.rontolisp.clojure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code clojure.core} name tables, taken from the oracle's {@code ns-publics}.
 */
class ClojureCoreNamesTest {

	@Test
	void everyCoreMacroIsACoreName() {
		assertThat(ClojureCoreNames.NAMES).hasSize(679).containsAll(ClojureCoreNames.MACROS);
		assertThat(ClojureCoreNames.MACROS).hasSize(79);
		assertThat(ClojureCoreNames.isMacro("when")).isTrue();
		assertThat(ClojureCoreNames.isMacro("inc")).isFalse();
	}

}
