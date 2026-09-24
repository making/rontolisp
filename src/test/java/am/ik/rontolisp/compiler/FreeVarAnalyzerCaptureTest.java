package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The capture walk answers a nested scope from a shared memo exactly as a fresh walk
 * does: the memo records, per form, what the walk finds independent of the locals asked
 * about, and a lambda's parameters shadow only inside that lambda.
 */
class FreeVarAnalyzerCaptureTest {

	private static final String PROGRAM = """
			(let ((a 1) (b 2) (c 3))
			  (let ((d 4))
			    (list (lambda (a) (+ a b))
			          (lambda () (setq d c))
			          (let ((e 5)) (lambda (x) (lambda (b) (+ b e x))))
			          a)))
			""";

	private static List<LispVal> body(LispVal let) {
		List<LispVal> parts = ((LispCons) let).toList();
		return parts.subList(2, parts.size());
	}

	@Test
	void aNestedScopeGetsTheSameAnswerFromTheMemoAsFromAFreshWalk() {
		LispVal outer = LispReader.readAllFromString(PROGRAM).get(0);
		LispVal middle = body(outer).get(0);
		LispVal inner = ((LispCons) body(middle).get(0)).toList().get(3);
		FreeVarAnalyzer.CaptureMemo memo = new FreeVarAnalyzer.CaptureMemo();

		// The outer walk first, so the nested questions are answered from the memo.
		Set<String> outerCaptured = FreeVarAnalyzer.findCapturedVars(body(outer), Set.of("A", "B", "C"), Set.of(),
				memo);
		Set<String> middleCaptured = FreeVarAnalyzer.findCapturedVars(body(middle), Set.of("D"), Set.of(), memo);
		Set<String> innerCaptured = FreeVarAnalyzer.findCapturedVars(body(inner), Set.of("E"), Set.of(), memo);

		// a is only read outside a lambda or as the first lambda's own parameter; b is
		// read in the first lambda (the second shadows it); c is read in the second.
		assertThat(outerCaptured).containsExactlyInAnyOrder("B", "C")
			.isEqualTo(FreeVarAnalyzer.findCapturedVars(body(outer), Set.of("A", "B", "C"), Set.of()));
		assertThat(middleCaptured).containsExactly("D")
			.isEqualTo(FreeVarAnalyzer.findCapturedVars(body(middle), Set.of("D"), Set.of()));
		assertThat(innerCaptured).containsExactly("E")
			.isEqualTo(FreeVarAnalyzer.findCapturedVars(body(inner), Set.of("E"), Set.of()));
	}

}
