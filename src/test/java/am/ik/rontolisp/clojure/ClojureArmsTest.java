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
	void theIoFamilyIsMadeByClojureJavaIoAndFoldsTheArmsOfAProgramMakingNone() {
		// only the namespace's kernels and the java.io constructions make a File, a URL
		// or a byte stream: an instance call's arm, a java: argument's view, class's and
		// instance?'s arms, and slurp's opening through the namespace go from a program
		// making none
		List<LispVal> forms = read("(let ((r x)) (if (rontolisp::%clojure-io-p r) (rontolisp::%clojure-io-m-close r)"
				+ " (close r)))" + " (java:call o \"m\" (rontolisp::%clojure-io-host v))"
				+ " (or (rontolisp::%clojure-io-instance-p v \"java.io.File\") (stringp v))"
				+ " (cond ((rontolisp::%clojure-io-p c) (rontolisp::%clojure-io-class-key c)) (t :other))"
				+ " (if (rontolisp::%clojure-io-openable-p s) (rontolisp::%clojure-io-slurp s nil) (open-path s))");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.IO);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.IO).stream().map(LispVal::print)).containsExactly(
				"(LET ((R X)) (CLOSE R))", "(JAVA:CALL O \"m\" V)", "(STRINGP V)", "(COND (T :OTHER))",
				"(OPEN-PATH S)");
		for (String producer : List.of("(rontolisp::%clojure-io-file \"a\")", "(rontolisp::%clojure-io-file-2 p c)",
				"(rontolisp::%clojure-io-open-input x)", "(rontolisp::%clojure-io-url-found \"file:/a\")")) {
			assertThat(ClojureArms.scan(read(producer), ClojureArms.Family.IO).builds()).as(producer).isTrue();
		}
	}

	@Test
	void theJarFamilyIsMadeByALookupOverAJarAndTakesTheJarReaderWithItsArms() {
		// only a resource lookup of a name computed at run time over a source path
		// holding
		// a jar makes a jar: URL whose entry is read from its archive: the reads' arms
		// go,
		// and the jar reader's definitions with them -- they name read-sequence and a
		// two-argument file-position, which passes ahead of the pruner read by name
		List<LispVal> forms = read(
				"(cond ((rontolisp::%clojure-io-jar-p x) (rontolisp::%clojure-io-jar-input x)) (t (open-path x)))"
						+ " (defun rontolisp::%clojure-io-jar-read (s at n) (file-position s at) (read-sequence n s))"
						+ " (defvar rontolisp::%clojure-io-jar-cache nil)"
						+ " (defun rontolisp::%clojure-io-kept (x) x)");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.JAR);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.JAR).stream().map(LispVal::print))
			.containsExactly("(COND (T (OPEN-PATH X)))", "(DEFUN RONTOLISP::%CLOJURE-IO-KEPT (X) X)");
		assertThat(ClojureArms
			.scan(read("(rontolisp::%clojure-io-jar-resource n '(\"/p/src\" (\"/p/a.jar\")))"), ClojureArms.Family.JAR)
			.builds()).isTrue();
		// the lookup makes a URL too
		assertThat(ClojureArms
			.scan(read("(rontolisp::%clojure-io-jar-resource n '((\"/p/a.jar\")))"), ClojureArms.Family.IO)
			.builds()).isTrue();
	}

	@Test
	void theReplyReaderFamilyIsMadeByAReaderOpenedAndTakesItsDefinitionsWithItsArms() {
		// a reader over a fetched reply is opened by a kernel opening a reader over a
		// byte stream; where none is, the reads' arms fold to the decode at once, and the
		// family's own definitions go with them -- they name the prelude's Gray class
		List<LispVal> forms = read(
				"(if (rontolisp::%clojure-reply-input-p in) (rontolisp::%clojure-reply-reader in c) (decode in c))"
						+ " (if (or (%obj-is x '%stream) (rontolisp::%clojure-reply-reader-p x)) (gethash x h))"
						+ " (defun rontolisp::%clojure-reply-lines (r close) (read-line r))"
						+ " (defun rontolisp::%clojure-io-kept (x) x)");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.REPLY_READER);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.REPLY_READER).stream().map(LispVal::print))
			.containsExactly("(DECODE IN C)", "(IF (%OBJ-IS X '%STREAM) (GETHASH X H))",
					"(DEFUN RONTOLISP::%CLOJURE-IO-KEPT (X) X)");
		for (String producer : List.of("(rontolisp::%clojure-io-open-reader x nil)",
				"(rontolisp::%clojure-io-decoding-reader x nil)", "(rontolisp::%clojure-io-line-seq x)")) {
			assertThat(ClojureArms.scan(read(producer), ClojureArms.Family.REPLY_READER).builds()).as(producer)
				.isTrue();
		}
		// it is made only beside a fetched value
		assertThat(ClojureArms.madeBeside(ClojureArms.Family.REPLY_READER, java.util.Set.of())).isFalse();
		assertThat(ClojureArms.madeBeside(ClojureArms.Family.REPLY_READER, java.util.Set.of(ClojureArms.Family.FETCH)))
			.isTrue();
		assertThat(ClojureArms.madeBeside(ClojureArms.Family.FETCH, java.util.Set.of())).isTrue();
	}

	@Test
	void theByteArrayFamilyFoldsItsArmsAliasesAndSetterToTheFormsBeforeByteArrays() {
		// aget, alength, bytes? and a String construction (its arguments' host values
		// too) are aliases of what they lowered to before, aset a setter of the place it
		// stored into; the tests go like any family's
		List<LispVal> forms = read("(rontolisp::%clojure-aget a i) (rontolisp::%clojure-aset (f a) i (g v))"
				+ " (rontolisp::%clojure-alength a 0) (rontolisp::%clojure-is-bytes (f) false)"
				+ " (rontolisp::%clojure-string-new \"java.lang.String\" (rontolisp::%clojure-bytes-host-value x) :java-false)"
				+ " (cond ((rontolisp::%clojure-bytes-p c) (length (car (cdr c)))) (t 0))"
				+ " (if (rontolisp::%clojure-io-array-stream-p s) nil (close s))");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.BYTES);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.BYTES).stream().map(LispVal::print)).containsExactly(
				"(AREF A I)", "(SETF (AREF (F A) I) (G V))", "(ARRAY-DIMENSION A 0)", "(PROGN (F) FALSE)",
				"(JAVA:NEW \"java.lang.String\" (RONTOLISP::%CLOJURE-HOST-VALUE X) :JAVA-FALSE)", "(COND (T 0))",
				"(CLOSE S)");
		for (String producer : List.of("(rontolisp::%clojure-byte-array 3)", "(rontolisp::%clojure-byte-array-2 3 x)",
				"(function rontolisp::%clojure-byte-array-v)", "(rontolisp::%clojure-string-bytes s nil)",
				"(rontolisp::%clojure-io-m-read-all-bytes in)", "(rontolisp::%clojure-io-bytes-output 32)",
				"(rontolisp::%clojure-io-bytes-input b)", "(rontolisp::%clojure-ring-base64-decode s)",
				"(rontolisp::%clojure-http-request opts f)")) {
			assertThat(ClojureArms.scan(read(producer), ClojureArms.Family.BYTES).builds()).as(producer).isTrue();
		}
		assertThatThrownBy(() -> ClojureArms.strip(read("(rontolisp::%clojure-aset a)"), ClojureArms.Family.BYTES))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("a setter takes");
	}

	@Test
	void theTransientFamilyIsMadeByTransientAndFoldsItsArmsAndIndexedsHelper() {
		// a read of a transient, a kind's instance? test and indexed?'s helper go from a
		// program making none, which a bang verb alone does not make
		List<LispVal> forms = read(
				"(cond ((rontolisp::%clojure-transient-p c) (rontolisp::%clojure-transient-count c)) (t (length c)))"
						+ " (or (a x) (rontolisp::%clojure-transient-vector-p x))"
						+ " (rontolisp::%clojure-is-indexed-transient x) (rontolisp::%clojure-transient-conj tr x)");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.TRANSIENT);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.TRANSIENT).stream().map(LispVal::print)).containsExactly(
				"(COND (T (LENGTH C)))", "(A X)", "(RONTOLISP::%CLOJURE-IS-INDEXED X)",
				"(RONTOLISP::%CLOJURE-TRANSIENT-CONJ TR X)");
		for (String producer : List.of("(rontolisp::%clojure-transient v)",
				"(function rontolisp::%clojure-transient-v)", "(function rontolisp::%clojure-transient-conj-v)")) {
			assertThat(ClojureArms.scan(read(producer), ClojureArms.Family.TRANSIENT).builds()).as(producer).isTrue();
		}
	}

	@Test
	void theReaderValueFamilyFoldsThePredicatesAndTheReaderArmsOfAProgramReadingWithoutOptions() {
		// only a read that may take {:read-cond :preserve} and the two constructors make
		// a reader conditional or a tagged literal: the predicates are (progn x false)
		// again, get's arm goes and the reader's preserve clauses with it
		List<LispVal> forms = read("(rontolisp::%clojure-is-reader-conditional (f) false)"
				+ " (lambda (x) (rontolisp::%clojure-is-tagged-literal x false))"
				+ " (cond ((rontolisp::%clojure-reader-value-p c) (rontolisp::%clojure-reader-value-get c k d)) (t d))"
				+ " (if (rontolisp::%clojure-rd-preserve-p mode) (preserved) (allowed))"
				+ " (cond (suppress (s)) ((rontolisp::%clojure-rd-preserving-p) (tagged)) (t (record)))"
				+ " (rontolisp::%clojure-read-string s ctx)");
		ClojureArms.Scan scan = ClojureArms.scan(forms, ClojureArms.Family.READER_VALUE);
		assertThat(scan.builds()).isFalse();
		assertThat(scan.strips()).isTrue();
		assertThat(ClojureArms.strip(forms, ClojureArms.Family.READER_VALUE).stream().map(LispVal::print))
			.containsExactly("(PROGN (F) FALSE)", "(LAMBDA (X) (PROGN X FALSE))", "(COND (T D))", "(ALLOWED)",
					"(COND (SUPPRESS (S)) (T (RECORD)))", "(RONTOLISP::%CLOJURE-READ-STRING S CTX)");
		for (String producer : List.of("(rontolisp::%clojure-read-string-opts o s ctx)",
				"(rontolisp::%clojure-read-opts o r ctx)", "(function rontolisp::%clojure-read-string-v)",
				"(rontolisp::%clojure-reader-conditional f s)", "(rontolisp::%clojure-tagged-literal-v t f)")) {
			assertThat(ClojureArms.scan(read(producer), ClojureArms.Family.READER_VALUE).builds()).as(producer)
				.isTrue();
		}
	}

	@Test
	void theInstantAndUuidFamiliesFoldTheirArmsInAProgramMakingNeither() {
		// only an #inst or #uuid literal, a read, the clojure.instant kernels,
		// random-uuid and parse-uuid make one: uuid? is the host test again, and the
		// class branch, the instance? arms and an instance call's clause go
		List<LispVal> forms = read("(rontolisp::%clojure-is-uuid x \"java.util.UUID\")"
				+ " (cond ((rontolisp::%clojure-instant-p c) (rontolisp::%clojure-instant-class c))"
				+ " ((rontolisp::%clojure-uuid-p c) (list :c%keyword \"java.util.UUID\")) (t c))"
				+ " (or (rontolisp::%clojure-date-p v) (rontolisp::%clojure-timestamp-p v) (stringp v))"
				+ " (cond ((rontolisp::%clojure-inst-p r) (rontolisp::%clojure-inst-ms r)) (t (refuse r)))");
		for (ClojureArms.Family family : List.of(ClojureArms.Family.INSTANT, ClojureArms.Family.UUID)) {
			ClojureArms.Scan scan = ClojureArms.scan(forms, family);
			assertThat(scan.builds()).isFalse();
			assertThat(scan.strips()).isTrue();
		}
		List<LispVal> stripped = ClojureArms.strip(ClojureArms.strip(forms, ClojureArms.Family.INSTANT),
				ClojureArms.Family.UUID);
		assertThat(stripped.stream().map(LispVal::print)).containsExactly(
				"(RONTOLISP::%CLOJURE-HOST-INSTANCE-P X \"java.util.UUID\")", "(COND (T C))", "(STRINGP V)",
				"(COND (T (REFUSE R)))");
		for (String producer : List.of("(rontolisp::%clojure-make-inst 0)", "(rontolisp::%clojure-read-string s ctx)",
				"(function rontolisp::%clojure-edn-read-string-v)", "(rontolisp::%clojure-instant-read-calendar s)")) {
			assertThat(ClojureArms.scan(read(producer), ClojureArms.Family.INSTANT).builds()).as(producer).isTrue();
		}
		for (String producer : List.of("(rontolisp::%clojure-make-uuid 0 0)", "(rontolisp::%clojure-random-uuid)",
				"(function rontolisp::%clojure-parse-uuid-v)", "(rontolisp::%clojure-read s nil nil ctx)")) {
			assertThat(ClojureArms.scan(read(producer), ClojureArms.Family.UUID).builds()).as(producer).isTrue();
		}
		// inst-ms reads one, it makes none
		assertThat(ClojureArms.scan(read("(rontolisp::%clojure-inst-ms x)"), ClojureArms.Family.INSTANT).builds())
			.isFalse();
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

	@Test
	void theReducibleFamilyIsMadeByATypedRowOfCollReduceOrIKVReduce() {
		ClojureArms.Family family = ClojureArms.Family.REDUCIBLE;
		// reduce's and reduce-kv's arms fold to their own walk, the view of group-by and
		// frequencies to the collection
		String arms = "(if (rontolisp::%clojure-coll-reducible-p coll) (r coll) (walk coll))"
				+ " (if (rontolisp::%clojure-kv-reducible-p (car colls)) (kv m) (pairs m))"
				+ " (rontolisp::%clojure-seq-all (rontolisp::%clojure-reducible-items (f x)))";
		assertThat(ClojureArms.scan(read(arms), family).strips()).isTrue();
		assertThat(ClojureArms.strip(read(arms), family).stream().map(LispVal::print)).containsExactly("(WALK COLL)",
				"(PAIRS M)", "(RONTOLISP::%CLOJURE-SEQ-ALL (F X))");
		// only the store of a record's, deftype's or reify's row makes one
		for (String producer : List.of("(rontolisp::%clojure-coll-reducer-row table (cadr self) f)",
				"(rontolisp::%clojure-kv-reducer-row table (list :c%keyword \"R\") f)")) {
			assertThat(ClojureArms.scan(read(arms + producer), family).builds()).as(producer).isTrue();
		}
	}

	/**
	 * One interface family: arms of each shape it has, what they fold to, and the store
	 * of a row of its interfaces that makes a value of it.
	 */
	private record InterfaceCase(ClojureArms.Family family, String arms, List<String> folded, String store) {
	}

	@Test
	void anInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces() {
		List<InterfaceCase> cases = List.of(
				new InterfaceCase(ClojureArms.Family.REDUCE_INTERFACE,
						"(if (rontolisp::%clojure-reduce-init-p coll) (i coll) (walk coll))"
								+ " (or (row x) (rontolisp::%clojure-kvreduce-p x))"
								+ " (vec-arg (rontolisp::%clojure-reduce-init-items (f x)))",
						List.of("(WALK COLL)", "(ROW X)", "(VEC-ARG (F X))"),
						"(rontolisp::%clojure-reduce-interface-row (cadr self)"
								+ " '(\"clojure.lang.IReduceInit\") (list \"reduce\" f))"),
				new InterfaceCase(ClojureArms.Family.SEQABLE,
						"(cond ((rontolisp::%clojure-seqable-p coll) (s coll)) (t (e coll)))"
								+ " (if (rontolisp::%clojure-lazy-input-p coll) (l coll) (w coll))",
						List.of("(COND (T (E COLL)))", "(IF (RONTOLISP::%CLOJURE-LAZY-P COLL) (L COLL) (W COLL))"),
						"(rontolisp::%clojure-seqable-row (cadr self) '(\"clojure.lang.Seqable\") (list \"seq\" f))"),
				new InterfaceCase(ClojureArms.Family.COUNTED, "(or (v x) (rontolisp::%clojure-counted-p x))",
						List.of("(V X)"),
						"(rontolisp::%clojure-counted-row (list :c%keyword \"T\") '(\"clojure.lang.Counted\")"
								+ " (list \"count\" f))"),
				new InterfaceCase(ClojureArms.Family.INDEXED,
						"(cond ((rontolisp::%clojure-indexed-p coll) (n coll)) (t (e coll)))"
								+ " (rontolisp::%clojure-nth-2 v 1 nil) (rontolisp::%clojure-is-indexed v)",
						List.of("(COND (T (E COLL)))", "(RONTOLISP::%CLOJURE-NTH V 1 NIL)",
								"(RONTOLISP::%CLOJURE-IS-VECTOR V)"),
						"(rontolisp::%clojure-indexed-row (cadr self) '(\"clojure.lang.Indexed\") (list \"nth\" f))"),
				new InterfaceCase(ClojureArms.Family.LOOKUP,
						"(cond ((rontolisp::%clojure-lookup-p (car args)) (g args)) (t d))", List.of("(COND (T D))"),
						"(rontolisp::%clojure-lookup-row (cadr self) '(\"clojure.lang.ILookup\") (list \"valAt\" f))"),
				new InterfaceCase(ClojureArms.Family.INVOKABLE,
						"(if (rontolisp::%clojure-invokable-p f) (i f) (c f))"
								+ " (apply (rontolisp::%clojure-applied-fn g) args)",
						List.of("(C F)", "(APPLY G ARGS)"),
						"(rontolisp::%clojure-invokable-row (cadr self) '(\"clojure.lang.IFn\") (list \"invoke\" f))"),
				new InterfaceCase(ClojureArms.Family.DEREFABLE,
						"(cond ((rontolisp::%clojure-derefable-p x) (d x)) (t (e x)))", List.of("(COND (T (E X)))"),
						"(rontolisp::%clojure-derefable-row (cadr self) '(\"clojure.lang.IDeref\") (list \"deref\" f))"),
				new InterfaceCase(ClojureArms.Family.META_INTERFACE,
						"(if (rontolisp::%clojure-imeta-p x) (m x) (side x))", List.of("(SIDE X)"),
						"(rontolisp::%clojure-meta-row (cadr self) '(\"clojure.lang.IMeta\") (list \"meta\" f))"),
				new InterfaceCase(ClojureArms.Family.OBJECT_METHODS,
						"(cond ((rontolisp::%clojure-to-string-p x) (s x)) (t (p x)))", List.of("(COND (T (P X)))"),
						"(rontolisp::%clojure-object-row (cadr self) nil (list \"toString\" f))"),
				// a CharSequence: count's clause, the string view of the regex verbs and
				// clojure.string, and instance?'s test, the host one without the type
				new InterfaceCase(ClojureArms.Family.CHAR_SEQUENCE,
						"(cond ((rontolisp::%clojure-char-sequence-p x) (l x)) (t (e x)))"
								+ " (upcase (rontolisp::%clojure-char-sequence-text (f x)))"
								+ " (rontolisp::%clojure-char-sequence-instance-p (f x))",
						List.of("(COND (T (E X)))", "(UPCASE (F X))",
								"(RONTOLISP::%CLOJURE-HOST-CHAR-SEQUENCE-P (F X))"),
						"(rontolisp::%clojure-char-sequence-row (cadr self) '(\"java.lang.CharSequence\")"
								+ " (list \"length\" f))"),
				// a face: %clojure-host-member's clause, made by the registration of a
				// type's maker around the tag of its row store
				new InterfaceCase(ClojureArms.Family.JAVA_FACE,
						"(cond ((rontolisp::%clojure-java-face-p x) (rontolisp::%clojure-java-face x)) (t (h x)))",
						List.of("(COND (T (H X)))"),
						"(rontolisp::%clojure-invokable-row (rontolisp::%clojure-java-face-tag (cadr self) maker)"
								+ " '(\"java.lang.Runnable\") (list \"run\" f))"));
		for (InterfaceCase one : cases) {
			assertThat(ClojureArms.scan(read(one.arms()), one.family()).strips()).as(one.family().name()).isTrue();
			assertThat(ClojureArms.strip(read(one.arms()), one.family()).stream().map(LispVal::print))
				.as(one.family().name())
				.containsExactlyElementsOf(one.folded());
			assertThat(ClojureArms.scan(read(one.arms() + one.store()), one.family()).builds()).as(one.family().name())
				.isTrue();
		}
		// a reduce-interface row makes a reducible value too, whose verbs hold its arms
		assertThat(ClojureArms
			.scan(read("(rontolisp::%clojure-reduce-interface-row tag nil nil)"), ClojureArms.Family.REDUCIBLE)
			.builds()).isTrue();
	}

	@Test
	void aCollectionInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces() {
		// each collection interface group folds its arms -- a cond clause, an if's test,
		// an
		// or's disjunct, a predicate's alias -- unless the program stores a row of it
		List<InterfaceCase> cases = List.of(
				new InterfaceCase(ClojureArms.Family.COLLECTION,
						"(cond ((rontolisp::%clojure-icollection-p c) (k c)) (t (e c)))"
								+ " (rontolisp::%clojure-is-coll-type x)",
						List.of("(COND (T (E C)))", "(RONTOLISP::%CLOJURE-IS-COLL X)"),
						"(rontolisp::%clojure-collection-row tag '(\"clojure.lang.IPersistentCollection\") (list))"),
				new InterfaceCase(ClojureArms.Family.ASSOCIATIVE,
						"(if (rontolisp::%clojure-iassociative-p m) (a m) (v m))", List.of("(V M)"),
						"(rontolisp::%clojure-associative-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.PERSISTENT_MAP,
						"(if (or (rontolisp::%clojure-imap-p m) (j m)) (k m) (s m)) (rontolisp::%clojure-is-map-type m)",
						List.of("(IF (J M) (K M) (S M))", "(RONTOLISP::%CLOJURE-IS-MAP M)"),
						"(rontolisp::%clojure-persistent-map-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.PERSISTENT_SET,
						"(cond ((rontolisp::%clojure-iset-p s) (d s)) (t (e s))) (rontolisp::%clojure-is-set-type s)",
						List.of("(COND (T (E S)))", "(RONTOLISP::%CLOJURE-IS-SET S)"),
						"(rontolisp::%clojure-persistent-set-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.STACK, "(cond ((rontolisp::%clojure-istack-p s) (p s)) (t (e s)))",
						List.of("(COND (T (E S)))"), "(rontolisp::%clojure-stack-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.PERSISTENT_VECTOR,
						"(or (and (vectorp v) (not (stringp v))) (rontolisp::%clojure-ivector-p v))",
						List.of("(AND (VECTORP V) (NOT (STRINGP V)))"),
						"(rontolisp::%clojure-persistent-vector-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.ISEQ,
						"(cond ((rontolisp::%clojure-iseq-p s) (w s)) (t (e s))) (rontolisp::%clojure-is-seq-type s)",
						List.of("(COND (T (E S)))", "(RONTOLISP::%CLOJURE-IS-SEQ S)"),
						"(rontolisp::%clojure-iseq-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.SEQUENTIAL,
						"(rontolisp::%clojure-is-list-type s) (rontolisp::%clojure-is-sequential-type s)",
						List.of("(RONTOLISP::%CLOJURE-IS-LIST S)", "(RONTOLISP::%CLOJURE-IS-SEQUENTIAL S)"),
						"(rontolisp::%clojure-sequential-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.REVERSIBLE, "(rontolisp::%clojure-is-reversible-type v)",
						List.of("(RONTOLISP::%CLOJURE-IS-REVERSIBLE V)"),
						"(rontolisp::%clojure-reversible-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.PENDING,
						"(cond ((rontolisp::%clojure-ipending-p x) (r x)) (t (e x)))", List.of("(COND (T (E X)))"),
						"(rontolisp::%clojure-pending-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.SORTED_INTERFACE,
						"(if (rontolisp::%clojure-isorted-p s) (w s) (v s))", List.of("(V S)"),
						"(rontolisp::%clojure-sorted-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.COMPARABLE,
						"(cond ((rontolisp::%clojure-icomparable-p a) (c a)) (t (e a)))", List.of("(COND (T (E A)))"),
						"(rontolisp::%clojure-comparable-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.ITERABLE,
						"(cond ((rontolisp::%clojure-iterable-p c) (i c)) (t (e c)))", List.of("(COND (T (E C)))"),
						"(rontolisp::%clojure-iterable-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.ITERATOR,
						"(cond ((rontolisp::%clojure-iterator-p it) (h it)) (t (e it)))", List.of("(COND (T (E IT)))"),
						"(rontolisp::%clojure-iterator-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.JAVA_COLLECTION,
						"(cond ((rontolisp::%clojure-jcollection-p c) (s c)) ((rontolisp::%clojure-jset-p c) (m c))"
								+ " (t (e c)))",
						List.of("(COND (T (E C)))"), "(rontolisp::%clojure-java-collection-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.JAVA_MAP,
						"(cond ((rontolisp::%clojure-jmap-p c) (s c)) (t (e c)))", List.of("(COND (T (E C)))"),
						"(rontolisp::%clojure-java-map-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.HASHEQ,
						"(cond ((rontolisp::%clojure-hasheq-p x) (h x)) (t (o x)))", List.of("(COND (T (O X)))"),
						"(rontolisp::%clojure-hasheq-row tag nil (list))"),
				new InterfaceCase(ClojureArms.Family.MARKER,
						"(if (or (rontolisp::%clojure-editable-p x) (rontolisp::%clojure-serializable-p x)) t nil)",
						List.of("NIL"), "(rontolisp::%clojure-marker-row tag nil (list))"));
		for (InterfaceCase one : cases) {
			assertThat(ClojureArms.scan(read(one.arms()), one.family()).strips()).as(one.family().name()).isTrue();
			assertThat(ClojureArms.strip(read(one.arms()), one.family()).stream().map(LispVal::print))
				.as(one.family().name())
				.containsExactlyElementsOf(one.folded());
			assertThat(ClojureArms.scan(read(one.arms() + one.store()), one.family()).builds()).as(one.family().name())
				.isTrue();
		}
		// an Iterable seqs and reduces through its iterator, so its row makes the seqable
		// and reducible families too, and a java.util.Map's the seqable one
		for (ClojureArms.Family family : List.of(ClojureArms.Family.SEQABLE, ClojureArms.Family.REDUCIBLE)) {
			assertThat(ClojureArms.scan(read("(rontolisp::%clojure-iterable-row tag nil nil)"), family).builds())
				.as(family.name())
				.isTrue();
		}
		assertThat(ClojureArms.scan(read("(rontolisp::%clojure-java-map-row tag nil nil)"), ClojureArms.Family.SEQABLE)
			.builds()).isTrue();
	}

	@Test
	void theTypedKeyFamilyIsMadeByTheStoreOfAnIHashEqOrAnObjectRow() {
		// a structural key's typed arms fold to the forms before typed keys unless a
		// type may have a hash of its own: either store makes one
		String arms = "(or (eq h :c%set) (rontolisp::%clojure-typed-key-p k))"
				+ " (cond ((rontolisp::%clojure-typed-key-p x) (rontolisp::%clojure-typed-key-hash x)) (t 0))";
		assertThat(ClojureArms.scan(read(arms), ClojureArms.Family.TYPED_KEY).strips()).isTrue();
		assertThat(ClojureArms.strip(read(arms), ClojureArms.Family.TYPED_KEY).stream().map(LispVal::print))
			.containsExactly("(EQ H :C%SET)", "(COND (T 0))");
		for (String store : List.of("(rontolisp::%clojure-hasheq-row tag '(\"clojure.lang.IHashEq\") (list))",
				"(rontolisp::%clojure-object-row tag nil (list \"hashCode\" f))")) {
			assertThat(ClojureArms.scan(read(arms + store), ClojureArms.Family.TYPED_KEY).builds()).as(store).isTrue();
		}
		assertThat(ClojureArms
			.scan(read(arms + "(rontolisp::%clojure-collection-row tag nil (list))"), ClojureArms.Family.TYPED_KEY)
			.builds()).isFalse();
	}

}
