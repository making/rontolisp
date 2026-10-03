package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The strip of a family's arms ({@link ClojureArms}): what a program that makes no value
 * of the kind (no sorted collection, no unbound root) is left with is the form each verb
 * lowered to before.
 */
class ClojureArmsTest {

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	private static String stripped(String source) {
		return ClojureArms.strip(read(source), ClojureArms.Family.SORTED)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

	@Test
	void aTestFoldsAwayItsClauseItsBranchAndItsDisjunct() {
		assertThat(stripped("(cond ((consp x) 1) ((rontolisp::%clojure-sorted-p x) (f x)) (t 2))"))
			.isEqualTo("(COND ((CONSP X) 1) (T 2))");
		assertThat(stripped("(if (rontolisp::%clojure-sorted-map-p m) (g m) (h m))")).isEqualTo("(H M)");
		assertThat(stripped("(if (rontolisp::%clojure-sorted-set-p m) (g m))")).isEqualTo("NIL");
		assertThat(stripped("(or (a x) (b x) (rontolisp::%clojure-sorted-p x))")).isEqualTo("(OR (A X) (B X))");
		assertThat(stripped("(or (a x) (rontolisp::%clojure-sorted-p x))")).isEqualTo("(A X)");
		assertThat(stripped("(cond ((or (rontolisp::%clojure-sorted-p a) (rontolisp::%clojure-sorted-p b)) 1) (t 2))"))
			.isEqualTo("(COND (T 2))");
		// a test over a car/cdr read of a variable folds too
		assertThat(stripped("(if (rontolisp::%clojure-sorted-map-p (car maps)) (car maps) nil)")).isEqualTo("NIL");
	}

	@Test
	void aViewIsItsFirstArgumentAndAnAliasItsPlainHelper() {
		assertThat(stripped("(remhash (rontolisp::%clojure-table-key (rontolisp::%clojure-sorted-key k m) c) c)"))
			.isEqualTo("(REMHASH (RONTOLISP::%CLOJURE-TABLE-KEY K C) C)");
		assertThat(stripped("(cadr (rontolisp::%clojure-sorted-hashed s))")).isEqualTo("(CADR S)");
		assertThat(stripped("(rontolisp::%clojure-sorted-shrunk (list :c%set table) s)"))
			.isEqualTo("(LIST :C%SET TABLE)");
		assertThat(stripped("(lambda (x) (rontolisp::%clojure-is-set x) (rontolisp::%clojure-is-reversible x))"))
			.isEqualTo("(LAMBDA (X) (RONTOLISP::%CLOJURE-SET-P X) (RONTOLISP::%CLOJURE-IS-VECTOR X))");
	}

	@Test
	void formsWithoutAnArmAreAnsweredThemselves() {
		List<LispVal> forms = read("(defun f (x) (cond ((consp x) 1) (t (or x 2)))) '(rontolisp::%clojure-sorted-p x)");
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.SORTED)).isSameAs(forms);
		List<LispVal> mixed = read(
				"(defun f (x) (if (g x) 1 2)) (defun h (x) (or (rontolisp::%clojure-sorted-p x) x))");
		List<LispVal> out = ClojureArms.strip(mixed, ClojureArms.Family.SORTED);
		assertThat(out.get(0)).isSameAs(mixed.get(0));
		assertThat(out.get(1).print()).isEqualTo("(DEFUN H (X) X)");
	}

	@Test
	void anArmTheStripCannotFoldIsRefused() {
		// a test anywhere else would survive the strip and splice the sorted runtime
		// after all; one over an argument with an effect would lose that effect
		assertThatThrownBy(() -> ClojureArms.strip(read("(and (a x) (rontolisp::%clojure-sorted-p x))"),
				ClojureArms.Family.SORTED))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cannot fold");
		assertThatThrownBy(() -> ClojureArms.strip(read("(if (rontolisp::%clojure-sorted-p (pop xs)) 1 2)"),
				ClojureArms.Family.SORTED))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("an argument with an effect");
		assertThatThrownBy(
				() -> ClojureArms.strip(read("(rontolisp::%clojure-sorted-key k (next m))"), ClojureArms.Family.SORTED))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("an argument with an effect");
	}

	@Test
	void theScanTellsAProducerFromAnArm() {
		assertThat(ClojureArms.scan(read("(if (rontolisp::%clojure-sorted-p x) 1 2)"), ClojureArms.Family.SORTED))
			.isEqualTo(new ClojureArms.Scan(false, true));
		assertThat(ClojureArms.scan(read("(rontolisp::%clojure-sorted-make t nil (list 1))"), ClojureArms.Family.SORTED)
			.builds()).isTrue();
		assertThat(ClojureArms.scan(read("#'rontolisp::%clojure-sorted-set-by-v"), ClojureArms.Family.SORTED).builds())
			.isTrue();
		assertThat(ClojureArms.scan(read("(princ 1)"), ClojureArms.Family.SORTED).strips()).isFalse();
		assertThat(ClojureArms.scan(read("(rontolisp::%clojure-is-set x)"), ClojureArms.Family.SORTED).strips())
			.isTrue();
	}

	@Test
	void theUnboundRootFamilyStripsOnlyItsOwnArms() {
		List<LispVal> forms = read(
				"(cond ((rontolisp::%clojure-unbound-p x) 1) ((rontolisp::%clojure-sorted-p x) 2) (t 3))"
						+ " (and (boundp 'v) (if (rontolisp::%clojure-unbound-p v) nil t))");
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.UNBOUND).stream().map(LispVal::print))
			.containsExactly("(COND ((RONTOLISP::%CLOJURE-SORTED-P X) 2) (T 3))", "(AND (BOUNDP 'V) T)");
		assertThat(
				ClojureArms.scan(read("(setq x (rontolisp::%clojure-unbound \"user/x\"))"), ClojureArms.Family.UNBOUND)
					.builds())
			.isTrue();
		assertThat(ClojureArms.scan(forms, ClojureArms.Family.UNBOUND).strips()).isTrue();
		assertThatThrownBy(() -> ClojureArms.strip(read("(and (a x) (rontolisp::%clojure-unbound-p x))"),
				ClojureArms.Family.UNBOUND))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unbound-root test where it cannot fold");
	}

	@Test
	void theStreamDepthFamilyDropsTheRebindingPairsOfAProgramReadingNoCounter() {
		List<LispVal> forms = read("(defvar rontolisp::%clojure-out-depth 0)"
				+ " (let* ((s (make-string-output-stream)) (*standard-output* s)"
				+ " (rontolisp::%clojure-out-depth (+ rontolisp::%clojure-out-depth 1))) (f s))"
				+ " (let ((c%agent a) (rontolisp::%clojure-agent-depth (+ rontolisp::%clojure-agent-depth 1))) a)"
				+ " (let ((rontolisp::%clojure-in-depth (+ rontolisp::%clojure-in-depth 1))) (g))");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.STREAM_DEPTH);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.STREAM_DEPTH).stream().map(LispVal::print))
			.containsExactly("(LET* ((S (MAKE-STRING-OUTPUT-STREAM)) (*STANDARD-OUTPUT* S)) (F S))",
					"(LET ((C%AGENT A)) A)", "(LET NIL (G))");
		// a read of a counter anywhere else is what keeps the pairs
		List<LispVal> read = read("(let ((rontolisp::%clojure-out-depth (+ rontolisp::%clojure-out-depth 1))) 1)"
				+ " (lambda () rontolisp::%clojure-out-depth)");
		assertThat(ClojureArms.scan(read, ClojureArms.Family.STREAM_DEPTH).builds()).isTrue();
		// a let without a counter pair keeps its identity
		List<LispVal> plain = read("(let* ((x 1) (y (+ x 1))) (h x y))");
		assertThat(ClojureArms.strip(plain, ClojureArms.Family.STREAM_DEPTH)).isSameAs(plain);
	}

	@Test
	void theExceptionFamilyFoldsClassReadingAConditionWhereNoReaderIsDefined() {
		// class reads a condition's class only where the program defines the exception
		// reader: without it no condition can reach class, and the arm goes
		List<LispVal> forms = read("(let* ((one x)) (cond ((stringp one) 1)"
				+ " ((rontolisp::%clojure-exception-p one) (rontolisp::%clojure-exception-class one)) (t 2)))");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.EXCEPTION);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.EXCEPTION).stream().map(LispVal::print))
			.containsExactly("(LET* ((ONE X)) (COND ((STRINGP ONE) 1) (T 2)))");
		List<LispVal> reader = read("(defun c%e-parts (c) (declare (ignore c)) nil)");
		assertThat(ClojureArms.scan(reader, ClojureArms.Family.EXCEPTION).builds()).isTrue();
	}

	@Test
	void thePrintFlagFamilyFoldsTheCutTheLevelTheDepthAndTheReadableSwitch() {
		List<LispVal> forms = read("(cond ((rontolisp::%clojure-print-deep-p x) (a))"
				+ " ((rontolisp::%clojure-print-cut-p x) (b)) (t (rontolisp::%clojure-write-nested x r)))"
				+ " (rontolisp::%clojure-write x (rontolisp::%clojure-print-readable readable))");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.PRINT_FLAGS);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.PRINT_FLAGS).stream().map(LispVal::print))
			.containsExactly("(COND (T (RONTOLISP::%CLOJURE-WRITE X R)))", "(RONTOLISP::%CLOJURE-WRITE X READABLE)");
		// a flag's special anywhere is what keeps them
		List<LispVal> flagged = read("(let* ((rontolisp::%clojure-print-level 1)) (f))");
		assertThat(ClojureArms.scan(flagged, ClojureArms.Family.PRINT_FLAGS).builds()).isTrue();
	}

}
