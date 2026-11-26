package am.ik.rontolisp.clojure;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		// every macro the shipped expanders expand is a core macro, and their namespace
		// defines exactly the table's expanders
		assertThat(ClojureCoreNames.MACROS).containsAll(ClojureCoreMacros.expanded());
		String source = ClojureBuiltinNamespaces.source(ClojureCoreMacros.NAMESPACE);
		assertThat(source).isNotNull();
		Set<String> defined = new TreeSet<>();
		Matcher expander = Pattern.compile("^\\(defn (expand-\\S+)", Pattern.MULTILINE).matcher(source);
		while (expander.find()) {
			defined.add(expander.group(1));
		}
		assertThat(defined).containsExactlyInAnyOrderElementsOf(ClojureCoreMacros.expanderVars().values());
	}

}
