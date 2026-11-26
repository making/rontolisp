package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureLibrary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run-time class-name canon behind the multimethod value arms ({@code clojure.lisp},
 * {@code %clojure-dispatch-class-names}) mirrors the lowering's static map
 * ({@link ClojureDispatchLowering#DISPATCH_CLASS_KEYWORDS}) entry for entry, so a
 * run-time class object or designator reads back as the keyword a class spelling stores.
 * {@code java.lang.Object} stays out of both: the lowering special-cases it to
 * {@code :object}, and the canon tests the full name.
 */
class ClojureDispatchKindsTest {

	@Test
	void theRunTimeCanonMirrorsTheLoweringMap() {
		Map<String, String> names = new LinkedHashMap<>();
		for (LispVal form : ClojureLibrary.forms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("DEFVAR")
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& name.name().equals("RONTOLISP::%CLOJURE-DISPATCH-CLASS-NAMES")
					&& rest.cdr() instanceof LispCons value && value.car() instanceof LispCons quote
					&& quote.car() instanceof LispSymbol quoted && quoted.name().equals("QUOTE")
					&& quote.cdr() instanceof LispCons quotedList) {
				for (LispVal entry : conses(quotedList.car())) {
					LispCons pair = (LispCons) entry;
					names.put(((LispString) pair.car()).value(), ((LispString) pair.cdr()).value());
				}
			}
		}
		assertThat(names).isNotEmpty();
		assertThat(names).containsExactlyInAnyOrderEntriesOf(ClojureDispatchLowering.DISPATCH_CLASS_KEYWORDS);
		assertThat(ClojureDispatchLowering.DISPATCH_CLASS_KEYWORDS).doesNotContainKey("Object");
	}

	private static List<LispVal> conses(LispVal list) {
		List<LispVal> out = new ArrayList<>();
		while (list instanceof LispCons cons) {
			out.add(cons.car());
			list = cons.cdr();
		}
		return out;
	}

}
