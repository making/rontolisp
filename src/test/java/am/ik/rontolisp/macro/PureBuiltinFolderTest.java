package am.ik.rontolisp.macro;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PureBuiltinFolderTest {

	@AfterEach
	void stopRecording() {
		SourceProvenance.stopRecording();
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	private static List<LispVal> fold(String source) {
		List<LispVal> program = read(source);
		return PureBuiltinFolder.foldProgram(program, false, LispMacroExpander.usesPrintCase(program));
	}

	/** The last form of a folded program. */
	private static LispVal folded(String source) {
		List<LispVal> program = fold(source);
		return program.get(program.size() - 1);
	}

	/**
	 * Whether a form is a literal value: an atom, or the {@code (quote sym)} a symbol
	 * needs.
	 */
	private static boolean isLiteral(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return true;
		}
		// (%str-fresh "...") is a folded CONSTANT too: the fresh-string producers fold
		// to it so each evaluation answers a fresh mutable string rather than one
		// shared literal (.kb/pure-builtin-fold.md).
		return cons.car() instanceof LispSymbol op
				&& (LispNames.QUOTE.equals(op.name()) || LispNames.STR_FRESH.equals(op.name()));
	}

	// ------------------------------------------------------------- the table's rows

	@Test
	void everyTableEntryHasADifferentialRow() {
		// "An entry with no row does not ship": a fold whose value no backend was ever
		// compared against is exactly the failure mode the whole harness exists for.
		assertThat(FoldDifferential.coveredOperators()).containsAll(PureBuiltinFolder.foldedOperators());
		assertThat(PureBuiltinFolder.foldedOperators()).containsAll(FoldDifferential.coveredOperators());
	}

	@Test
	void everyProbeActuallyFolds() {
		// Without this the differential is vacuous: two identical runtime calls agree
		// whether or not the fold exists.
		for (FoldDifferential.Probe probe : FoldDifferential.PROBES) {
			assertThat(isLiteral(folded(probe.call()))).as("%s folds: %s", probe.operator(), probe.call()).isTrue();
			assertThat(isLiteral(folded("(defun %id (x) x) " + probe.control())))
				.as("%s control does NOT fold: %s", probe.operator(), probe.control())
				.isFalse();
		}
	}

	@Test
	void foldsThroughNestingAndIntoEveryEvaluatedPosition() {
		assertThat(folded("(length (symbol-name :abcd))")).isEqualTo(new LispInteger(4));
		// A fold-fresh constant COMPOSES: the outer fold reads the value through the
		// (%str-fresh ...) spelling, so the nested reduction still happens in one pass.
		assertThat(folded("(length (concatenate 'string \"ab\" \"cd\"))")).isEqualTo(new LispInteger(4));
		assertThat(folded("(defun f () (princ (* 6 7)))").print()).isEqualTo("(DEFUN F NIL (PRINC 42))");
		assertThat(folded("(let ((x (+ 1 2))) x)").print()).isEqualTo("(LET ((X 3)) X)");
		assertThat(folded("(if (< 1 2) (+ 1 1) (+ 2 2))").print()).isEqualTo("(IF T 2 4)");
		assertThat(folded("(cond ((= 1 2) (+ 1 1)))").print()).isEqualTo("(COND (NIL 2))");
		assertThat(folded("(do ((i (* 2 3) (1+ i))) ((> i (+ 8 1)) (- 4 1)))").print())
			.isEqualTo("(DO ((I 6 (1+ I))) ((> I 9) 3))");
	}

	// ------------------------------------------- positions that are NOT evaluated

	@Test
	void aNonEvaluatedPositionIsNeverFolded() {
		// Each of these holds a cons whose head is a table name and whose arguments are
		// literals, and none of them is a call. The pass must hand the program back
		// unchanged -- object-identical, which is also the cons-identity rule.
		// A `do` termination clause and a `cond` clause are LISTS OF FORMS: the leading
		// element is the test and the rest is the result, so the clause is not a call
		// however call-shaped it looks.
		for (String source : List.of("(let ((max 3) (min 4)) (list max min))", "(let* ((length 7)) length)",
				"(do ((i 0) (max 3)) ((>= i max) i))", "(do ((i 0)) (max 3))", "(cond (max 3))",
				"(case x ((min 2) 1) (t 2))", "(defstruct box (length 0) (max 5))",
				"(defclass c () ((length :initform 0)))", "(defun f (&optional (max 9)) max)",
				"(lambda (&key (min 1)) min)", "(flet ((max (a b) a)) (max 1 2))",
				"(labels ((length (x) x)) (length 1))", "(macrolet ((min (a b) a)) (min 1 2))",
				"(defmacro m () (list '+ 1 2))", "(quote (+ 1 2))", "(setf (max 1) 2)",
				"(handler-case x ((max 1) (e) e))", "(multiple-value-bind (max min) v max)",
				"(destructuring-bind (max 3) v max)", "(with-slots (max) o max)", "(pop (max 1))", "(declare (max 1))",
				"(the (mod 8) x)", "(loop for (car cdr) in x collect car)")) {
			List<LispVal> program = read(source);
			assertThat(PureBuiltinFolder.foldProgram(program, false, LispMacroExpander.usesPrintCase(program)))
				.as("unchanged: %s", source)
				.isSameAs(program);
		}
	}

	@Test
	void aFreshStringProducerFoldsToAStrFreshConstantAndNotASharedLiteral() {
		// The fresh-string producers still fold -- the constant is computed at compile
		// time -- but the substituted form is (%str-fresh "..."), which the backends
		// compile as the literal plus one mutable-copy wrap: each evaluation answers a
		// FRESH MUTABLE string, so the fold cannot forge aliasing
		// (.kb/pure-builtin-fold.md, "The fresh-string producers fold to a
		// per-evaluation copy"). A plain-literal substitution here is that forgery
		// coming back.
		assertThat(folded("(string-upcase \"abc\")").print()).isEqualTo("(%STR-FRESH \"ABC\")");
		assertThat(folded("(string-downcase \"ABC\")").print()).isEqualTo("(%STR-FRESH \"abc\")");
		assertThat(folded("(concatenate 'string \"ab\" \"cd\")").print()).isEqualTo("(%STR-FRESH \"abcd\")");
		assertThat(folded("(subseq \"abcdef\" 1 3)").print()).isEqualTo("(%STR-FRESH \"bc\")");
		// The non-fresh string producers keep the plain literal: their runtime answers
		// are immutable values, so a shared constant forges nothing.
		assertThat(folded("(symbol-name :bar)")).isEqualTo(new LispString("BAR"));
	}

	@Test
	void aUserDefinitionOfATableNameBlocksTheFold() {
		// The same question ShadowedBuiltins and LispEvaluator.defineDispatcher answer,
		// asked more conservatively: a plain defun blocks it too, and a local function
		// blocks it for the whole program.
		for (String definition : List.of("(defun length (x) 99)", "(defmethod length ((x t)) 99)",
				"(defgeneric length (x))", "(defmacro length (x) 99)", "(defun cl-user::length (x) 99)",
				"(defun f () (flet ((length (x) 99)) (length \"ab\")))")) {
			List<LispVal> program = read(definition + " (length \"abc\")");
			List<LispVal> result = PureBuiltinFolder.foldProgram(program, false,
					LispMacroExpander.usesPrintCase(program));
			assertThat(result.get(result.size() - 1).print()).as("blocked by %s", definition)
				.isEqualTo("(LENGTH \"abc\")");
		}
		// A name it does NOT define still folds in the same program.
		List<LispVal> mixed = PureBuiltinFolder.foldProgram(read("(defun length (x) 99) (+ 1 2) (length \"abc\")"),
				false, false);
		assertThat(mixed.get(1)).isEqualTo(new LispInteger(3));
	}

	@Test
	void aComputedFunctionBindingStandsTheWholePassDown() {
		// (setf (symbol-function <computed>) ...) can install anything under any name,
		// so no table entry can be assumed to still be the built-in.
		List<LispVal> program = read("(setf (symbol-function name) #'f) (+ 1 2)");
		assertThat(PureBuiltinFolder.foldProgram(program, false, LispMacroExpander.usesPrintCase(program)))
			.isSameAs(program);
		// A LITERAL name blocks only that name.
		List<LispVal> named = PureBuiltinFolder.foldProgram(read("(setf (symbol-function 'max) #'f) (max 1 2) (+ 1 2)"),
				false, false);
		assertThat(named.get(1).print()).isEqualTo("(MAX 1 2)");
		assertThat(named.get(2)).isEqualTo(new LispInteger(3));
	}

	@Test
	void dynamicModeFoldsNothing() {
		// Under --dynamic every name resolves at run time, so the compile path may not
		// decide what a call means -- the same bail the funcall-dispatch gate takes.
		List<LispVal> program = read("(princ (+ 1 2))");
		assertThat(PureBuiltinFolder.foldProgram(program, true, LispMacroExpander.usesPrintCase(program)))
			.isSameAs(program);
		assertThat(PureBuiltinFolder.foldProgram(program, false, LispMacroExpander.usesPrintCase(program)))
			.isNotSameAs(program);
	}

	// --------------------------------------------------------------- declining

	@Test
	void aFoldThatWouldSignalDeclines() {
		// A cold branch may hold a call that errors; the fold must leave it for the
		// runtime rather than fail the compile.
		for (String source : List.of("(length 5)", "(mod 1 0)", "(rem 1 0)", "(char \"ab\" 9)", "(char-code 5)",
				"(code-char -1)", "(car 'foo)", "(subseq \"ab\" 1 9)", "(+ 1 \"x\")", "(expt 2 -1)")) {
			assertThat(folded(source).print()).as("declines: %s", source).startsWith("(");
		}
	}

	@Test
	void whatIsOutOfTheTableStaysOutOfIt() {
		// A ratio or float result, a value with identity, and a multiple-value producer
		// are each excluded for their own reason (.kb/pure-builtin-fold.md).
		for (String source : List.of("(/ 7 2)", "(+ 1.5 2.5)", "(list 1 2)", "(cdr '(1 2 3))", "(floor 7 2)",
				"(make-array 3)", "(vector 1 2)", "(nth 0 '((1) (2)))", "(char-equal #\\a #\\A)",
				"(string-equal \"a\" \"A\")", "(alpha-char-p #\\a)")) {
			assertThat(folded(source).print()).as("not folded: %s", source).startsWith("(");
		}
	}

	@Test
	void anUnboundedResultDeclines() {
		// A fold must not make the COMPILER do the unbounded work, nor bake a megabyte
		// of digits into the output.
		assertThat(folded("(expt 2 1000000)").print()).startsWith("(");
		assertThat(folded("(ash 1 1000000)").print()).startsWith("(");
		// Just inside the ceiling still folds.
		assertThat(folded("(expt 2 100)")).isEqualTo(LispReader.readFromString("1267650600228229401496703205376"));
	}

	@Test
	void aLiteralLookupTableFoldsToItsPackedVector() {
		// The shape every CL library spells a constant table as, in both its spellings
		// and at every width. The result is a LispIntVector, i.e. data the backends bake
		// rather than a cons list they build at run time.
		for (String source : List.of("(coerce '(1 2 3) '(vector (unsigned-byte 8)))",
				"(coerce #(1 2 3) '(simple-array (unsigned-byte 16) (*)))",
				"(coerce '(1 2 3) '(array (unsigned-byte 32) (*)))",
				"(make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3))",
				"(make-array '(3) :element-type '(unsigned-byte 8) :initial-contents #(1 2 3))")) {
			assertThat(folded(source)).as("folds: %s", source).isInstanceOf(LispIntVector.class);
			assertThat(folded(source).print()).as("value: %s", source).isEqualTo("#(1 2 3)");
		}
		assertThat(((LispIntVector) folded("(coerce '(1) '(vector (unsigned-byte 16)))")).width()).isEqualTo(16);
	}

	@Test
	void aPackedTableFoldIsElementTypeExact() {
		// A value that does not fit the declared width is the PROGRAM's bug, not the
		// folder's to mask: it declines and the run-time builder gives its own (masked)
		// answer, which is the one the interpreter gives too. The same for a
		// non-integer element, an improper list, and a designator with no packed width.
		for (String source : List.of("(coerce '(1 256) '(vector (unsigned-byte 8)))",
				"(coerce '(-1) '(vector (unsigned-byte 8)))", "(coerce '(65536) '(vector (unsigned-byte 16)))",
				"(coerce '(4294967296) '(vector (unsigned-byte 32)))", "(coerce '(1 #\\a) '(vector (unsigned-byte 8)))",
				"(coerce '(1 . 2) '(vector (unsigned-byte 8)))", "(coerce '(1) '(vector (unsigned-byte 4)))",
				"(coerce '(1) '(simple-vector 1))", "(coerce '(1) 'vector)",
				"(coerce x '(vector (unsigned-byte 8)))")) {
			assertThat(folded(source).print()).as("declines: %s", source).startsWith("(");
		}
	}

	@Test
	void aMakeArrayWithoutLiteralContentsIsNotFolded() {
		// :initial-contents is what makes the call a TABLE. Without it the size is the
		// only thing known, and folding (make-array 8192 :element-type '(unsigned-byte
		// 8)) would bake 8 KB of zeros in place of one array.new_default. Every other
		// keyword declines too -- a fill pointer or an :adjustable flag is not the
		// packed representation at all.
		for (String source : List.of("(make-array 8192 :element-type '(unsigned-byte 8))",
				"(make-array 3 :element-type '(unsigned-byte 8) :initial-element 0)",
				"(make-array 4 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3))",
				"(make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3) :fill-pointer 0)",
				"(make-array '(2 2) :element-type '(unsigned-byte 8) :initial-contents '((1 2) (3 4)))",
				"(make-array 3 :initial-contents '(1 2 3))")) {
			assertThat(folded(source).print()).as("declines: %s", source).startsWith("(");
		}
	}

	@Test
	void aZeroArgumentNaryCallIsNotFolded() {
		// (+) is worth nothing, and a one-element list whose head is a table name is
		// exactly what a non-evaluated position can hand the walker by mistake.
		for (String source : List.of("(+)", "(*)", "(gcd)", "(lcm)", "(logand)", "(logior)", "(logxor)")) {
			assertThat(folded(source).print()).as("not folded: %s", source).isEqualTo(source.toUpperCase());
		}
	}

	// ------------------------------------------------------------- bookkeeping

	@Test
	void aProgramWithNothingToFoldIsHandedBackUnchanged() {
		// The cons-identity rule: a rebuilt parent forces rebuilt children, so one
		// gratuitous copy drops the source position of the whole program below the top
		// level (.kb/source-positions.md).
		List<LispVal> program = read("(defun f (x) (g x)) (print (f 1))");
		assertThat(PureBuiltinFolder.foldProgram(program, false, LispMacroExpander.usesPrintCase(program)))
			.isSameAs(program);
	}

	@Test
	void aFoldedFormKeepsTheSourcePositionItReplaced() {
		// A rewriting pass would otherwise blank out the file:line:column of every cons
		// between the top-level form and the fold.
		SourceProvenance.startRecording();
		List<LispVal> program = LispReader.readAllFromString("(defun f (x)\n  (g x (+ 1 2)))\n", Features.JVM,
				"prog.lisp");
		List<LispVal> result = PureBuiltinFolder.foldProgram(program, false, LispMacroExpander.usesPrintCase(program));
		assertThat(result.get(0).print()).isEqualTo("(DEFUN F (X) (G X 3))");
		assertThat(SourceProvenance.locate(result.get(0))).isEqualTo(new SourceLocation("prog.lisp", 1, 1));
		LispVal body = ((LispCons) ((LispCons) ((LispCons) result.get(0)).cdr()).cdr()).cdr();
		assertThat(SourceProvenance.locate(((LispCons) body).car())).isEqualTo(new SourceLocation("prog.lisp", 2, 3));
	}

	@Test
	void aFoldedSymbolIsRequoted() {
		// Bare, it would read as a variable reference.
		assertThat(folded("(nth 1 '(a b c))").print()).isEqualTo("(QUOTE B)");
		// A keyword evaluates to itself and needs no quote.
		assertThat(folded("(nth 0 '(:k))")).isEqualTo(new LispSymbol(":K"));
		assertThat(folded("(symbol-name 'foo)")).isEqualTo(new LispString("FOO"));
	}

}
