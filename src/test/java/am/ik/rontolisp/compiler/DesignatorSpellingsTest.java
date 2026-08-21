package am.ik.rontolisp.compiler;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spellings both backends' funcall-dispatch gate probes. The list is shared for one
 * reason -- a designator that resolves on the JVM and not on WASM is a divergence -- so
 * these assertions are the pin for both.
 */
class DesignatorSpellingsTest {

	@Test
	void withoutASymbolBuilderOnlyTheSymbolSpellingsCount() {
		assertThat(DesignatorSpellings.of("PKG::FN", false)).containsExactly("PKG::FN", "PKG:FN", "FN");
		// No colon: the canonical spelling is the member spelling, and it is listed
		// once.
		assertThat(DesignatorSpellings.of("FN", false)).containsExactly("FN");
	}

	@Test
	void aSymbolBuilderWidensToTheStringAndPackagelessSpellings() {
		assertThat(DesignatorSpellings.of("PKG:FN", true)).containsExactly("PKG:FN", "FN", "\"PKG:FN\"", "\"FN\"",
				":FN", "#:FN");
	}

	@Test
	void theUninternedSpellingArmsTheName() {
		// (uiop:symbol-call '#:pkg '#:fn ...) -- dexador's spelling -- reaches the
		// function through (string '#:FN), exactly as the keyword one does, so it has
		// to probe the same way. Before this, the '#: form compiled and
		// then died undefined at run time.
		assertThat(DesignatorSpellings.matched("PKG:FN", Set.of("#:FN"), true)).isEqualTo("#:FN");
		assertThat(DesignatorSpellings.anySpelled("PKG:FN", Set.of("#:FN"), false)).isFalse();
	}

	@Test
	void anUnspelledNameIsNotArmed() {
		assertThat(DesignatorSpellings.anySpelled("PKG:FN", Set.of("OTHER", ":OTHER", "#:OTHER"), true)).isFalse();
		assertThat(DesignatorSpellings.matched("PKG:FN", Set.of(), true)).isNull();
	}

}
