package am.ik.rontolisp.macro;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anti-drift pin between the TWO renderings of a format control string: the static
 * expansion of a literal control ({@code LispMacroExpander.expandFormat}, which lowers to
 * string pieces) and the runtime renderer ({@code format-render.lisp}, which interprets
 * the control at run time).
 *
 * <p>
 * They are separate implementations on purpose -- the literal path compiles to
 * concatenation with no interpreter in the output, which is why every {@code format} in a
 * compiled program stays cheap -- so the only thing keeping them honest is a table
 * exercised through BOTH. Each case here is rendered twice from the same source text:
 * once with the control inline (static) and once through a variable (runtime). A
 * directive whose two implementations disagree fails here rather than in whichever
 * program happens to compute its control string.
 */
class FormatRendererTest {

	/**
	 * Each case is "control|arguments"; both are spliced into the two format calls
	 * verbatim, so a case reads exactly like the call a program would write.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "~a ~s ~d|1 \"s\" 42", "~5,'0d|7", "~10a|~5,'0d||\"foo\" 42", "~:d and ~@d|1000000 42",
			"~,2f and ~$|3.14159 3.14159", "~e and ~,4e|1234.5 3.14159265", "~x ~o ~b ~8r|255 64 5 4096",
			"~c ~@c ~:c|#\\a #\\b #\\Newline", "~(~a~) ~:(~a~) ~@(~a~)|\"FOO BAR\" \"foo bar\" \"foo bar\"",
			"~[zero~;one~:;many~] ~:[no~;yes~] ~@[x=~a~]|1 t 42", "~{<~a>~} ~:{(~a,~a)~}|'(1 2) '((x 1) (y 2))",
			"~{~a~^, ~}|'(1 2 3)", "~a ~:* ~a|1", "~?|\"[~a-~a]\" '(1 2)", "~g ~g|1234.5 1.0e20",
			"PostgreSQL warning: ~A~@[~%~A~]|\"relation exists\" nil", "~v,'*d|8 42", "~#[none~;one~:;many~]|'a 'b",
			"~a~a~0@*~a|1 2", "~a~*~a|1 2 3", "a~2%b|", "~3~|", "abc~&def~&~&ghi|", "~[a~;~[x~;y~]~]|1 1",
			"~:@{[~a ~a]~}|'(1 2) '(3 4)", "~2{~a,~}|'(1 2 3 4)", "~d ~:d ~@d|-5 -1234567 -7",
			"~,3f ~,2f|-3.14159 -0.005", "~e ~,3e|0.0 0.0", "~,3,2e|1234.5", "~$ ~,3$ ~2,4$|3.5 3.5 3.5",
			"~w ~:w ~@w|\"s\" '(1 2) 3", "Expect ~W to be ~:[true~;false~].|'(= (add 1 2) 3) nil", "~:a ~:s|nil nil",
			"[~10@a]|\"hi\"", "~,,,4:b|255", "~a~^~a|1", "~{~a~^-~}|'(1 2 3)", "~:{~a~^/~a~}|'((1 2) (3))",
			"&#x~x;|233",
			// ~f / ~$ past the point where the scaled magnitude leaves the signed 64-bit
			// range. Both paths call the ONE %fixed-decimal primitive now, so both
			// saturate identically; before it they scaled by different powers of ten and
			// answered different digits.
			"~,25F ~,30F|3.14159 1.5", "~,2F ~,0F|1e30 -1e30", "~,2F|-0.0", "~,3$|-0.5",
			// The logical block / justification family. The static expansion DECLINES
			// every one of these, so both columns are the runtime renderer -- the rows
			// are here so a future static implementation has to match it.
			"~@<a~:@_b~:>|", "~@<a~_b~:>|", "~@<~a and ~a~:>|1 2", "~<~@;~a~:>|'(\"x\")",
			"~<~@;~a-~a~:>|'(\"a\" \"b\")", "~<~a~;~a~>|1 2", "~@<x~;y~;z~:>|", "~@<~{~a~^, ~}~:>|'(1 2 3)",
			"before ~@<in~:> after|", "~@<x ~2@T~<~s~:>~:>|'(\"hi\")" })
	void staticAndRuntimeRenderingAgree(String testCase) {
		int split = testCase.lastIndexOf('|');
		String control = testCase.substring(0, split);
		String args = testCase.substring(split + 1);
		LispVal viaLiteral = eval("(format nil \"" + control + "\" " + args + ")");
		LispVal viaRuntime = eval("(let ((c \"" + control + "\")) (format nil c " + args + "))");
		assertThat(viaRuntime).as("runtime control \"%s\"", control).isEqualTo(viaLiteral);
	}

	/**
	 * The shape from the report that motivated the shared renderer: a mixed control
	 * string whose padded and conditional directives the cut-down fallback left LITERAL
	 * while still consuming their arguments, so everything after them came out shifted.
	 */
	@Test
	void aMixedControlStringNeitherLosesNorShiftsArguments() {
		String control = "A=~A S=~S D=~D ~5,'0D% ~{~A~^,~} ~@[cond=~A~] ~~ end";
		String args = "1 \"s\" 42 7 (list 1 2) \"c\"";
		assertThat(eval("(let ((c \"" + control + "\")) (format nil c " + args + "))"))
			.isEqualTo(new LispString("A=1 S=\"s\" D=42 00007% 1,2 cond=c ~ end"));
	}

	/**
	 * A runtime control is DATA -- a condition's format-control slot, a server's message
	 * -- so the renderer must answer a string for anything, never signal. (The literal
	 * path signals at expansion time, which is a compile-time diagnostic, not a crash in
	 * the middle of reporting an error.)
	 */
	@Test
	void aMalformedRuntimeControlRendersInsteadOfSignalling() {
		assertThat(eval("(let ((c \"abc~\")) (format nil c))")).isEqualTo(new LispString("abc~"));
		assertThat(eval("(let ((c \"a~Qb\")) (format nil c 1))")).isEqualTo(new LispString("a~Qb"));
		assertThat(eval("(let ((c \"~{~a\")) (format nil c '(1 2)))")).isEqualTo(new LispString("12"));
		assertThat(eval("(let ((c \"~a ~a\")) (format nil c 1))")).isEqualTo(new LispString("1 NIL"));
		assertThat(eval("(let ((c \"~d\")) (format nil c \"xyz\"))")).isEqualTo(new LispString("xyz"));
	}

	/**
	 * {@code ~/name/} calls a user function as {@code (name stream object colon-p at-p)}.
	 * The name resolves as if by {@code find-symbol}, where a single and a double colon
	 * are equivalent -- so a package-qualified spelling reaches an INTERNAL symbol too,
	 * which is what esrap's {@code ~/esrap:print-terminal/} needs.
	 */
	@Test
	void userFunctionDirectiveCallsTheNamedFunction() {
		String define = "(defun fmt-brackets (s x &optional c a) (princ (if c \"[\" \"<\") s) (princ x s)"
				+ " (princ (if a \"]\" \">\") s))";
		assertThat(evalAll(define + " (let ((c \"~/fmt-brackets/\")) (format nil c 42))"))
			.isEqualTo(new LispString("<42>"));
		assertThat(evalAll(define + " (let ((c \"~:@/fmt-brackets/\")) (format nil c 42))"))
			.isEqualTo(new LispString("[42]"));
		assertThat(evalAll(define + " (let ((c \"a ~/cl-user::fmt-brackets/ b\")) (format nil c 1))"))
			.isEqualTo(new LispString("a <1> b"));
		assertThat(evalAll(define + " (let ((c \"~{~/fmt-brackets/~}\")) (format nil c '(1 2)))"))
			.isEqualTo(new LispString("<1><2>"));
	}

	/** The renderer is injected once per program, and only when it can be reached. */
	@Test
	void theRendererIsInjectedOnlyForAProgramThatReachesIt() {
		assertThat(rendererDefunCount("(format t \"~a~%\" 1)")).isZero();
		assertThat(rendererDefunCount("(let ((c \"~a\")) (format nil c 1))")).isOne();
		assertThat(rendererDefunCount("(princ (funcall #'format nil \"~a\" 1))")).isOne();
		assertThat(rendererDefunCount("(format nil \"~?\" \"~a\" '(1))")).isOne();
		// Twice reachable, still one copy.
		assertThat(rendererDefunCount("(let ((c \"~a\")) (format nil c 1) (format nil c 2))")).isOne();
	}

	/**
	 * The {@code ~/name/} arm rides along only when some control string the compile can
	 * SEE spells the directive. It is the one part of the renderer that resolves a
	 * function out of runtime data, and carrying it costs every program that formats a
	 * computed control the {@code --optimize} funcall-dispatch gate
	 * ({@code .kb/optimize-dead-code-elimination.md}). A program that spells none gets
	 * the stub, which signals if a control assembled at run time ever renders one.
	 */
	@Test
	void theFunctionDesignatorArmIsInjectedOnlyForAProgramThatSpellsTheDirective() {
		// Reaches the renderer, spells no ~/name/ anywhere: the stub.
		assertThat(definesFunctionDesignator("(let ((c \"~a\")) (format nil c 1))")).isFalse();
		assertThat(definesFunctionDesignator("(princ (funcall #'format nil \"~a\" 1))")).isFalse();
		// The directive in the control at the call site.
		assertThat(definesFunctionDesignator("(format nil \"~/f/\" 1)")).isTrue();
		// ...and in ANY string literal, wherever it sits: the control is runtime data, so
		// the literal is the only thing the compile can go on.
		assertThat(definesFunctionDesignator("(let ((c \"~/f/\")) (format nil c 1))")).isTrue();
		assertThat(definesFunctionDesignator("(defvar *c* \"x ~:@/pkg::f/ y\") (let ((c \"~a\")) (format nil c 1))"))
			.isTrue();
		// A lone tilde-slash spells no directive; a slash without a tilde is text.
		assertThat(definesFunctionDesignator("(defvar *p* \"a/b/c\") (let ((c \"~a\")) (format nil c 1))")).isFalse();
	}

	/** Under {@code --dynamic} any name resolves at run time, so the arm always rides. */
	@Test
	void theFunctionDesignatorArmAlwaysRidesUnderDynamic() {
		assertThat(definesFunctionDesignator("(let ((c \"~a\")) (format nil c 1))", true)).isTrue();
	}

	/** Whether the expanded program carries the REAL arm rather than its stub. */
	private boolean definesFunctionDesignator(String source) {
		return definesFunctionDesignator(source, false);
	}

	private boolean definesFunctionDesignator(String source, boolean dynamic) {
		return expand(source, dynamic).stream().anyMatch(form -> "%FMT-FUNCTION-DESIGNATOR".equals(definedName(form)));
	}

	/** How many times the renderer's entry point is DEFINED in the expanded program. */
	private long rendererDefunCount(String source) {
		return expand(source, false).stream().filter(form -> FormatRenderer.RENDER.equals(definedName(form))).count();
	}

	private List<LispVal> expand(String source, boolean dynamic) {
		return LispMacroExpander.expandTopLevelDefinitions(LispReader.readAllFromString(source),
				new java.util.HashMap<>(), new am.ik.rontolisp.ClosRegistry(), null, dynamic);
	}

	@org.jspecify.annotations.Nullable
	private static String definedName(LispVal form) {
		return form instanceof am.ik.rontolisp.LispCons defun && defun.cdr() instanceof am.ik.rontolisp.LispCons rest
				&& rest.car() instanceof am.ik.rontolisp.LispSymbol name ? name.name() : null;
	}

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		return evaluator.eval(LispReader.readFromString(input));
	}

	/** Evaluates several top-level forms in ONE evaluator and answers the last value. */
	private LispVal evalAll(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = am.ik.rontolisp.LispNil.INSTANCE;
		for (LispVal form : LispReader.readAllFromString(input)) {
			result = evaluator.eval(form);
		}
		return result;
	}

}
