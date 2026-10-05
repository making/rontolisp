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
	void theStreamFamilyFoldsThePrinterArmOfAProgramMakingNoStream() {
		// a stream reaches a value only through a read of *out*/*in*/*err*, a
		// StringWriter, a reader over a StringReader or a clojure.java.io/reader
		List<LispVal> forms = read("(cond ((rontolisp::%clojure-stream-p x) (w x)) ((functionp x) 1) (t 2))");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.STREAM);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.STREAM).stream().map(LispVal::print))
			.containsExactly("(COND ((FUNCTIONP X) 1) (T 2))");
		for (String producer : List.of("(rontolisp::%clojure-out)", "(rontolisp::%clojure-in)",
				"(rontolisp::%clojure-err)", "(rontolisp::%clojure-string-writer)",
				"(rontolisp::%clojure-string-reader \"s\")", "(rontolisp::%clojure-reader \"f\")")) {
			assertThat(ClojureArms.scan(read("(print " + producer + ")"), ClojureArms.Family.STREAM).builds())
				.as(producer)
				.isTrue();
		}
		// the Common Lisp constructors are no producer: naming one would splice the
		// library into a Common Lisp program
		assertThat(ClojureArms.isFamilyName("OPEN")).isFalse();
		assertThat(ClojureArms.isFamilyName("MAKE-STRING-OUTPUT-STREAM")).isFalse();
	}

	@Test
	void theSwitchFamiliesDropTheSwitchesOfAProgramReadingNoLoadSpecial() {
		List<LispVal> forms = read("(defvar rontolisp::%clojure-ns (rontolisp::%clojure-ns-object \"user\"))"
				+ " (defvar rontolisp::%clojure-file \"/p/m.clj\")"
				+ " (setq rontolisp::%clojure-ns (rontolisp::%clojure-ns-object \"m\"))"
				+ " (setq i (lambda () (setq rontolisp::%clojure-ns (rontolisp::%clojure-ns-object \"a\")) (f)"
				+ " (setq rontolisp::%clojure-ns (rontolisp::%clojure-ns-object \"b\"))))"
				+ " (unless l (let ((rontolisp::%clojure-ns rontolisp::%clojure-ns) (rontolisp::%clojure-file \"a.clj\"))"
				+ " (funcall i)) (setq l t))"
				+ " (progn (setq rontolisp::%clojure-ns (rontolisp::%clojure-ns-object \"c\")) nil)");
		List<LispVal> stripped = forms;
		for (ClojureArms.Family family : List.of(ClojureArms.Family.NS_SWITCH, ClojureArms.Family.FILE_SWITCH)) {
			ClojureArms.Scan scan = ClojureArms.scan(stripped, family);
			assertThat(scan.builds()).isFalse();
			assertThat(scan.strips()).isTrue();
			stripped = ClojureArms.strip(stripped, family);
		}
		// a namespace's init ends in a switch only where nothing reads its value
		assertThat(stripped.stream().map(LispVal::print)).containsExactly("(SETQ I (LAMBDA NIL (F)))",
				"(UNLESS L (FUNCALL I) (SETQ L T))", "NIL");
		// a read of the special anywhere else is what keeps them
		List<LispVal> read = read("(setq rontolisp::%clojure-ns (rontolisp::%clojure-ns-object \"m\"))"
				+ " (print rontolisp::%clojure-ns)");
		assertThat(ClojureArms.scan(read, ClojureArms.Family.NS_SWITCH).builds()).isTrue();
		assertThat(ClojureArms.scan(read, ClojureArms.Family.FILE_SWITCH).arms()).isFalse();
		// a set! is no switch: its value is the program's
		List<LispVal> assigned = read("(f (setq rontolisp::%clojure-ns (rontolisp::%clojure-the-ns x k)))");
		assertThat(ClojureArms.scan(assigned, ClojureArms.Family.NS_SWITCH).builds()).isTrue();
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
	void theRefusalFamilyFoldsEachRefusalToThePlainErrorOfItsMessage() {
		// a program reading no condition's class signals what it signalled before
		// refusals carried one: a literal (each ~ doubled, so the report stays the
		// text), a format over its control, anything else through ~A; the culprit,
		// which only picks the class, goes; subs is subseq again, .charAt char
		List<LispVal> forms = read("(f (rontolisp::%clojure-illegal-argument-exception \"seq needs a collection\"))"
				+ " (rontolisp::%clojure-class-cast-exception-of \"name needs a name\" x)"
				+ " (rontolisp::%clojure-assertion-error \"Assert failed: (= x \\\"~a\\\")\")"
				+ " (rontolisp::%clojure-index-out-of-bounds-exception (format nil \"Index ~D of ~D\" i (length v)))"
				+ " (rontolisp::%clojure-arity-exception (concatenate 'string \"a\" b))"
				+ " (rontolisp::%clojure-map-entry-refusal \"conj needs a map entry\" (car item))"
				+ " (rontolisp::%clojure-subs s 1 e) (rontolisp::%clojure-char-at s i)"
				+ " (rontolisp::%clojure-subs-by-reflection s 1 e) (rontolisp::%clojure-char-at-by-reflection s i) (if (rontolisp::%clojure-refusal-p c) (%obj-ref c 2) nil)");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.REFUSAL);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.REFUSAL).stream().map(LispVal::print)).containsExactly(
				"(F (ERROR \"seq needs a collection\"))", "(ERROR \"name needs a name\")",
				"(ERROR \"Assert failed: (= x \\\"~~a\\\")\")", "(ERROR \"Index ~D of ~D\" I (LENGTH V))",
				"(ERROR \"~A\" (CONCATENATE 'STRING \"a\" B))", "(ERROR \"conj needs a map entry\")", "(SUBSEQ S 1 E)",
				"(CHAR S I)", "(SUBSEQ S 1 E)", "(CHAR S I)", "NIL");
		// the condition class goes with them
		List<LispVal> condition = read(
				"(define-condition rontolisp::%clojure-refusal (simple-error) ((c :initarg :chain))) (f)");
		assertThat(ClojureArms.strip(condition, ClojureArms.Family.REFUSAL).stream().map(LispVal::print))
			.containsExactly("(F)");
		// a culprit with an effect cannot go
		assertThatThrownBy(() -> ClojureArms.strip(read("(rontolisp::%clojure-class-cast-exception-of \"m\" (pop xs))"),
				ClojureArms.Family.REFUSAL))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("an argument with an effect");
	}

	@Test
	void theRefusalFamilyIsMadeByAReaderOfAConditionsClass() {
		// a catch by class, class and instance? of a condition read the class a refusal
		// carries; a catch of Throwable, which takes every condition untested, does not
		String refusal = "(rontolisp::%clojure-illegal-argument-exception \"m\")";
		for (String reader : List.of("(defun c%e-catches-x (c) (rontolisp::%clojure-catches c '(\"x\")))",
				"(rontolisp::%clojure-exception-class e)", "(rontolisp::%clojure-instance-of e '(\"x\"))")) {
			assertThat(ClojureArms.scan(read(refusal + reader), ClojureArms.Family.REFUSAL).builds()).as(reader)
				.isTrue();
		}
		assertThat(ClojureArms.scan(read("(handler-case " + refusal + " (error (e) e))"), ClojureArms.Family.REFUSAL)
			.strips()).isTrue();
	}

	@Test
	void theMatcherFamilyIsMadeByReMatcherAndFoldsNthsGroupArm() {
		// only re-matcher makes a matcher: nth's arm goes from a program naming none
		List<LispVal> forms = read("(cond ((rontolisp::%clojure-matcher-value-p coll) (rontolisp::%clojure-matcher-nth"
				+ " coll i dflt)) (t (f)))");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.MATCHER);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.MATCHER).stream().map(LispVal::print))
			.containsExactly("(COND (T (F)))");
		assertThat(ClojureArms.scan(read("(rontolisp::%clojure-re-matcher p s)"), ClojureArms.Family.MATCHER).builds())
			.isTrue();
	}

	@Test
	void theRefusalFamilyFoldsVecsArgumentCheckToTheArgument() {
		// vec's RuntimeException for a non-collection is read only where a class is
		List<LispVal> forms = read(
				"(coerce (rontolisp::%clojure-realize-all (rontolisp::%clojure-vec-arg (f x)))" + " 'vector)");
		assertThat(ClojureArms.scan(forms, ClojureArms.Family.REFUSAL).strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.REFUSAL).stream().map(LispVal::print))
			.containsExactly("(COERCE (RONTOLISP::%CLOJURE-REALIZE-ALL (F X)) 'VECTOR)");
	}

	@Test
	void theHostFamilyFoldsInstanceOfAHostClassInAProgramNamingNoJavaOperator() {
		// no host object exists without a java: operator: the host arm goes, and a core
		// class a host object may be tests the kind alone, as before host objects counted
		List<LispVal> forms = read("(if (or (rontolisp::%clojure-is-vector x) (rontolisp::%clojure-host-object-p x"
				+ " \"java.util.List\")) t f) (if (rontolisp::%clojure-host-object-p x \"java.io.File\") t f)"
				+ " (if (rontolisp::%clojure-host-number-p (g)) t f) (rontolisp::%clojure-host-char-sequence-p y)");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.HOST);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.HOST).stream().map(LispVal::print)).containsExactly(
				"(IF (RONTOLISP::%CLOJURE-IS-VECTOR X) T F)", "F", "(IF (NUMBERP (G)) T F)", "(STRINGP Y)");
		assertThat(ClojureArms.scan(read("(java:new \"java.io.File\" \"x\")"), ClojureArms.Family.HOST).builds())
			.isTrue();
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

	@Test
	void thePrintMetaFamilyFoldsTheMetadataClauseOfAProgramNamingNoFlag() {
		List<LispVal> forms = read("(cond ((null x) (a)) ((rontolisp::%clojure-print-meta-p x r s l))"
				+ " ((rontolisp::%clojure-print-deep-p x) (b)) (t (c)))");
		assertThat(ClojureArms.scan(forms, ClojureArms.Family.PRINT_META).strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.PRINT_META).stream().map(LispVal::print))
			.containsExactly("(COND ((NULL X) (A)) ((RONTOLISP::%CLOJURE-PRINT-DEEP-P X) (B)) (T (C)))");
		List<LispVal> flagged = read("(let* ((rontolisp::%clojure-print-meta t)) (f))");
		assertThat(ClojureArms.scan(flagged, ClojureArms.Family.PRINT_META).builds()).isTrue();
		assertThat(ClojureArms.scan(flagged, ClojureArms.Family.PRINT_FLAGS).builds()).isFalse();
	}

	@Test
	void theNamespaceMapFamilyIsMadeByAQualifiedKeywordOrSymbol() {
		ClojureArms.Family family = ClojureArms.Family.NAMESPACE_MAP;
		String arm = "(cond ((rontolisp::%clojure-print-ns-map-p x) (a)) (t (b)))";
		assertThat(ClojureArms.strip(read(arm), family).stream().map(LispVal::print)).containsExactly("(COND (T (B)))");
		// a built or quoted keyword wrapper over a spelling with a namespace, a quoted
		// qualified symbol, and a function building one from a computed spelling
		for (String producer : List.of("(f (list :c%keyword \"a/b\"))", "(f '(1 (:c%keyword \"a/b\")))", "(f '|c%a/b|)",
				"(f '(|c%x| |c%a/b|))", "(rontolisp::%clojure-keyword-1 s)", "(rontolisp::%clojure-read-string s c)")) {
			assertThat(ClojureArms.scan(read(arm + producer), family).builds()).as(producer).isTrue();
		}
		// an unqualified keyword or symbol, the lone slash, and a qualified name in code
		// (a call of another namespace's function) make none
		for (String plain : List.of("(f (list :c%keyword \"b\"))", "(f '(:c%keyword \"/\"))", "(f '|c%/|)",
				"(f '|c%b|)", "(|c%a/b| 1)", "(f \"a/b\")")) {
			assertThat(ClojureArms.scan(read(arm + plain), family).strips()).as(plain).isTrue();
		}
	}

}
