package am.ik.rontolisp.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispLexer;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.reader.Token;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LispFormatterTest {

	@Test
	void indentsADefunBodyByTwo() {
		assertThat(LispFormatter.format("""
				(defun f (x)
				(print x)
				(terpri))
				""")).isEqualTo("""
				(defun f (x)
				  (print x)
				  (terpri))
				""");
	}

	@Test
	void keepsAFormThatFitsOnOneLine() {
		assertThat(LispFormatter.format("(defun square (x)\n   (* x x))\n")).isEqualTo("(defun square (x) (* x x))\n");
	}

	@Test
	void neverJoinsTwoBodyForms() {
		// Two forms performed in order are two lines however short they are; only a
		// single-form body may be joined onto the header line.
		assertThat(LispFormatter.format("(when x (a) (b))\n")).isEqualTo("""
				(when x
				  (a)
				  (b))
				""");
		assertThat(LispFormatter.format("(when x\n  (a))\n")).isEqualTo("(when x (a))\n");
	}

	@Test
	void neverJoinsABodyNestedInsideAFormThatWouldFit() {
		// The whole defun fits on one line, but its when may not be flattened, so neither
		// may anything containing it.
		assertThat(LispFormatter.format("(defun f (x) (when x (a) (b)))\n")).isEqualTo("""
				(defun f (x)
				  (when x
				    (a)
				    (b)))
				""");
	}

	@Test
	void alignsIfBranchesUnderTheTest() {
		assertThat(LispFormatter
			.format("(if (long-predicate-name a b c) (then-branch-here a b c) (else-branch-here a b c))\n"))
			.isEqualTo("""
					(if (long-predicate-name a b c)
					    (then-branch-here a b c)
					    (else-branch-here a b c))
					""");
	}

	@Test
	void alignsCallArgumentsUnderTheFirstOne() {
		assertThat(LispFormatter
			.format("(some-function (first-argument aaaa) (second-argument bbbb) (third-argument cccc))\n"))
			.isEqualTo("""
					(some-function (first-argument aaaa) (second-argument bbbb)
					               (third-argument cccc))
					""");
	}

	@Test
	void alignsCondClausesUnderTheFirstOne() {
		assertThat(LispFormatter.format("""
				(cond ((null-check a) (result-a b))
				((null-check b) (result-b a))
				(t (both a b)))
				""")).isEqualTo("""
				(cond ((null-check a) (result-a b))
				      ((null-check b) (result-b a))
				      (t (both a b)))
				""");
		// A cond short enough for one line keeps it: clauses are alternatives, not a
		// sequence, so nothing forces them apart.
		assertThat(LispFormatter.format("(cond ((null a) b) (t a))\n")).isEqualTo("(cond ((null a) b) (t a))\n");
	}

	@Test
	void alignsLetBindingsAndIndentsTheBodyByTwo() {
		assertThat(LispFormatter.format("""
				(let ((alpha (compute-something 1)) (beta (compute-something 2)) (gamma (compute 3)))
				(use alpha beta)
				(use gamma beta))
				""")).isEqualTo("""
				(let ((alpha (compute-something 1))
				      (beta (compute-something 2))
				      (gamma (compute 3)))
				  (use alpha beta)
				  (use gamma beta))
				""");
	}

	@Test
	void indentsALocalFunctionLikeADefun() {
		// (rec (args) body) is structurally a function call; only labels knows it is a
		// definition, which is what the binding-list child style carries.
		assertThat(LispFormatter.format("""
				(labels ((rec (list acc) (if (endp list) acc (rec (cdr list) (cons (car list) acc)))))
				(rec x nil))
				""")).isEqualTo("""
				(labels ((rec (list acc)
				           (if (endp list) acc (rec (cdr list) (cons (car list) acc)))))
				  (rec x nil))
				""");
	}

	@Test
	void givesEachLoopClauseALine() {
		assertThat(LispFormatter
			.format("(loop for item in collection when (predicate item) collect (transform item) into results)\n"))
			.isEqualTo("""
					(loop for item in collection
					      when (predicate item)
					      collect (transform item) into results)
					""");
	}

	@Test
	void putsTheDoEndTestPastTheBody() {
		assertThat(LispFormatter.format("(do ((i 0 (1+ i))) ((= i n) result) (body i) (more i))\n")).isEqualTo("""
				(do ((i 0 (1+ i)))
				    ((= i n) result)
				  (body i)
				  (more i))
				""");
	}

	@Test
	void keepsKeywordArgumentsWithTheirValues() {
		assertThat(LispFormatter
			.format("(with-open-file (s path :direction :output :if-exists :supersede :if-does-not-exist :create))\n"))
			.isEqualTo("""
					(with-open-file (s path
					                   :direction :output
					                   :if-exists :supersede
					                   :if-does-not-exist :create))
					""");
	}

	@Test
	void keepsTheLambdaListOfADefmethodOnTheFirstLine() {
		assertThat(LispFormatter
			.format("(defmethod render :around ((s stream) (x thing)) (call-next-method) (flush s))\n")).isEqualTo("""
					(defmethod render :around ((s stream) (x thing))
					  (call-next-method)
					  (flush s))
					""");
	}

	@Test
	void guessesTheStyleOfAnUnknownWithMacro() {
		// Laid out as a call, the body would align under (socket-accept l) and every line
		// inside it would run past the margin.
		assertThat(LispFormatter.format("""
				(usocket:with-server-socket (sock (usocket:socket-accept listener))
				(handle sock)
				(close sock))
				""")).isEqualTo("""
				(usocket:with-server-socket (sock (usocket:socket-accept listener))
				  (handle sock)
				  (close sock))
				""");
	}

	@Test
	void keepsAnOwnLineCommentOnItsOwnLine() {
		assertThat(LispFormatter.format("""
				(defun f (x)
				;; why this is done
				(body x))
				""")).isEqualTo("""
				(defun f (x)
				  ;; why this is done
				  (body x))
				""");
	}

	@Test
	void keepsATrailingCommentOnItsCodeLine() {
		assertThat(LispFormatter.format("(defun f (x) ; the point of f\n(body x))\n")).isEqualTo("""
				(defun f (x) ; the point of f
				  (body x))
				""");
	}

	@Test
	void breaksBeforeAClosingParenThatWouldFollowAComment() {
		// A ; comment swallows the rest of its line, so a ) after one would be commented
		// out. This is the one place the formatter MUST add a line break.
		assertThat(LispFormatter.format("""
				(defun f (x)
				(body x)
				;; nothing more to do
				)
				""")).isEqualTo("""
				(defun f (x)
				  (body x)
				  ;; nothing more to do
				  )
				""");
	}

	@Test
	void alignsTheTrailingCommentsOfConsecutiveLines() {
		assertThat(LispFormatter.format("""
				(run 1) ; first
				(run 200) ; second
				(run 30) ; third
				""")).isEqualTo("""
				(run 1)   ; first
				(run 200) ; second
				(run 30)  ; third
				""");
	}

	@Test
	void keepsABlankLineAndCollapsesARunOfThem() {
		assertThat(LispFormatter.format("""
				(a)



				(b)
				""")).isEqualTo("""
				(a)

				(b)
				""");
	}

	@Test
	void keepsABlankLineInsideABody() {
		assertThat(LispFormatter.format("""
				(defun f ()
				  (one)

				  (two))
				""")).isEqualTo("""
				(defun f ()
				  (one)

				  (two))
				""");
	}

	@Test
	void neverAddsABlankLineBetweenTopLevelForms() {
		// trivial-formatter forces one; here the author's paragraphing is the author's.
		assertThat(LispFormatter.format("(a)\n(b)\n")).isEqualTo("(a)\n(b)\n");
	}

	@Test
	void reproducesEveryTokenVerbatim() {
		String source = """
				(list Foo |a b| #\\Space #xFF 1/3 1.5d0 1,000 #*1010 "a\\"b" 'q `(,x ,@y) #'f)
				""";
		assertThat(LispFormatter.format(source)).isEqualTo(source);
	}

	@Test
	void keepsAFeatureGuardButGivesALongOneItsOwnLine() {
		assertThat(LispFormatter.format("#+sbcl (declaim (optimize speed))\n"))
			.isEqualTo("#+sbcl (declaim (optimize speed))\n");
		assertThat(LispFormatter.format("""
				#-(or ccl (and ecl little-endian) (and sbcl little-endian)) (defun ref (v i)
				(the (unsigned-byte 16) (dpb (aref v (1+ i)) (byte 8 8) (aref v i))))
				""")).isEqualTo("""
				#-(or ccl (and ecl little-endian) (and sbcl little-endian))
				(defun ref (v i)
				  (the (unsigned-byte 16) (dpb (aref v (1+ i)) (byte 8 8) (aref v i))))
				""");
	}

	@Test
	void leavesAMultiLineStringLiteralAlone() {
		assertThat(LispFormatter.format("""
				(defun f ()
				"first line
				second line"
				(body))
				""")).isEqualTo("""
				(defun f ()
				  "first line
				second line"
				  (body))
				""");
	}

	@Test
	void leavesABlockCommentAlone() {
		// Its interior lines are content, so they are reproduced exactly -- which is also
		// why the form after it cannot go back onto its last line.
		assertThat(LispFormatter.format("(a) #| kept\n   as written |# (b)\n"))
			.isEqualTo("(a) #| kept\n   as written |#\n(b)\n");
	}

	@Test
	void wrapsToTheGivenWidth() {
		String source = "(some-function (aaaa 1) (bbbb 2) (cccc 3))\n";
		assertThat(LispFormatter.format(source, 80)).isEqualTo(source);
		assertThat(LispFormatter.format(source, 30)).isEqualTo("""
				(some-function (aaaa 1)
				               (bbbb 2)
				               (cccc 3))
				""");
	}

	@Test
	void normalizesLineEndingsAndTheFinalNewline() {
		assertThat(LispFormatter.format("(a)\r\n(b)")).isEqualTo("(a)\n(b)\n");
		assertThat(LispFormatter.format("")).isEmpty();
		assertThat(LispFormatter.format("\n\n  \n")).isEmpty();
	}

	@Test
	void reportsAnUnreadableSourceWithItsPosition() {
		assertThatThrownBy(() -> LispFormatter.format("(defun f (x)\n  (g x)\n")).isInstanceOf(FormatException.class)
			.hasMessageContaining("1:1")
			.hasMessageContaining("no matching ')'");
		assertThatThrownBy(() -> LispFormatter.format("(a))\n")).isInstanceOf(FormatException.class)
			.hasMessageContaining("1:4");
		assertThatThrownBy(() -> LispFormatter.format("'\n")).isInstanceOf(FormatException.class)
			.hasMessageContaining("is not followed by a form");
	}

	/**
	 * Every {@code .lisp} and {@code .asd} file in the repository, formatted. The corpus
	 * is the point: it is thousands of forms of real Common Lisp -- cl-ppcre, ironclad,
	 * esrap, trivia, sxql -- written by people who never saw this formatter, which is the
	 * only kind of input that finds the cases a hand-written fixture never will.
	 * @return the files
	 * @throws IOException if the tree cannot be walked
	 */
	static Stream<Path> repositoryLispSources() throws IOException {
		try (Stream<Path> walk = Files.walk(Path.of("."))) {
			return walk.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".lisp") || path.toString().endsWith(".asd"))
				.filter(path -> !path.toString().contains("/target/"))
				.sorted(Comparator.comparing(Path::toString))
				.toList()
				.stream();
		}
	}

	@ParameterizedTest
	@MethodSource("repositoryLispSources")
	void formattingIsIdempotentAndPreservesEveryToken(Path file) throws IOException {
		String source = Files.readString(file);
		String formatted = LispFormatter.format(source);
		// Formatting is a fixpoint: a formatted file formats to itself. Without this the
		// command could not be used as a CI gate, since --check would never go quiet.
		assertThat(LispFormatter.format(formatted)).as("formatting %s twice differs from once", file)
			.isEqualTo(formatted);
		// ...and it changes NO code. The lexer discards exactly what the formatter is
		// allowed to change (whitespace and comments) and keeps everything else, so an
		// identical token stream is the precise statement of "same program".
		List<Token> before = tokens(source);
		if (before != null) {
			assertThat(tokens(formatted)).as("formatting %s changed its token stream", file).isEqualTo(before);
		}
	}

	// The reader's own tokens, or null when the file does not read at all (a fixture that
	// deliberately uses syntax rontolisp does not support). The formatter still has to
	// reproduce such a file byte-for-byte in structure, but there is no token stream to
	// compare it against.
	@Nullable private static List<Token> tokens(String source) {
		try {
			return new LispLexer(source, Features.INTERPRETER, LispLexer.ReadEvalMode.MARKER).tokenize();
		}
		catch (LispReadException _) {
			return null;
		}
	}

}
