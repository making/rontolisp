package am.ik.rontolisp.compiler;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelLetStagingTest {

	private static LispCons read(String source) {
		return (LispCons) LispReader.readAllFromString(source).get(0);
	}

	@Test
	void aLaterInitReadingAnEarlierVariableIsStagedThroughTemporaries() {
		assertThat(ParallelLetStaging.stage(read("(let ((a b) (b a)) (list a b))")).print())
			.isEqualTo("(LET ((%LET-INIT-0 B) (%LET-INIT-1 A)) (LET ((A %LET-INIT-0) (B %LET-INIT-1)) (LIST A B)))");
	}

	@Test
	void aLiteralInitBindsDirectly() {
		assertThat(ParallelLetStaging.stage(read("(let ((n (+ n 1)) (k 0) (m (* n 2))) (+ n m k))")).print())
			.isEqualTo("(LET ((%LET-INIT-0 (+ N 1)) (%LET-INIT-2 (* N 2))) "
					+ "(LET ((N %LET-INIT-0) (K 0) (M %LET-INIT-2)) (+ N M K)))");
	}

	@Test
	void aSpecialVariableReadByALaterInitIsStagedToo() {
		assertThat(ParallelLetStaging.stage(read("(let ((*x* 1) (y *x*)) y)")).print())
			.isEqualTo("(LET ((%LET-INIT-1 *X*)) (LET ((*X* 1) (Y %LET-INIT-1)) Y))");
	}

	@Test
	void aLetNoInitCanObserveComesBackAsTheSameObject() {
		// The same object, not an equal one: that is what keeps the emitted bytes of
		// every other let unchanged.
		for (String source : new String[] { "(let ((a 1) (b 2)) (list a b))", "(let ((a b)) a)",
				"(let ((b a) (c a)) (list b c))", "(let ((list (f)) (x (list 1 2))) x)",
				"(let ((a (g)) (b (quote a))) b)", "(let ((a 1) (f (lambda (a) a))) f)", "(let (a b) a)" }) {
			LispCons form = read(source);
			assertThat(ParallelLetStaging.stage(form)).as(source).isSameAs(form);
		}
	}

}
