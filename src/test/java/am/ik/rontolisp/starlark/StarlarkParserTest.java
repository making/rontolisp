package am.ik.rontolisp.starlark;

import java.util.List;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.starlark.StarlarkSyntax.Assignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.Binary;
import am.ik.rontolisp.starlark.StarlarkSyntax.Call;
import am.ik.rontolisp.starlark.StarlarkSyntax.Dot;
import am.ik.rontolisp.starlark.StarlarkSyntax.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The reader against hand-picked trees and rejections. The parser was also compared with
 * Python's {@code ast} over the whole conformance corpus
 * ({@code .kb/starlark-frontend.md}, "Reader"); these cases pin what the corpus does not
 * exercise -- float, bytes and escape literals, line joining, indentation -- and what
 * Python accepts and Starlark rejects.
 */
class StarlarkParserTest {

	private static List<Statement> parse(String source) {
		return StarlarkParser.parse(source, "t.star");
	}

	static List<Arguments> trees() {
		return List.of(
				arguments("1 + 2 * 3 - 4 // 5 % 6",
						"(expr (- (+ (int 1) (* (int 2) (int 3))) (% (// (int 4) (int 5)) (int 6))))"),
				arguments("not a == b and c or d", "(expr (or (and (not (== (name a) (name b))) (name c)) (name d)))"),
				arguments("-x * +y - ~-z", "(expr (- (* (- (name x)) (+ (name y))) (~ (- (name z)))))"),
				arguments("a | b ^ c & d << e >> f + g",
						"(expr (| (name a) (^ (name b) (& (name c) (>> (<< (name d) (name e)) (+ (name f) (name g)))))))"),
				arguments("(x not in y) != (a < b)", "(expr (!= (not-in (name x) (name y)) (< (name a) (name b))))"),
				arguments("a if b else c if d else e",
						"(expr (if-expr (name b) (name a) (if-expr (name d) (name c) (name e))))"),
				arguments("f = lambda x, y=1, *a, z, **kw: x if y else lambda: z",
						"(= (name f) (lambda (x (= y (int 1)) (* a) z (** kw)) (if-expr (name y) (name x) (lambda () (name z)))))"),
				arguments("[x * y for (x, y), z in p if x if not y for q in r]",
						"(expr (list-comp (* (name x) (name y)) (for (tuple (tuple (name x) (name y)) (name z)) (name p)) (if (name x)) (if (not (name y))) (for (name q) (name r))))"),
				arguments("{k: v for k, v in d.items()}",
						"(expr (dict-comp (: (name k) (name v)) (for (tuple (name k) (name v)) (call (dot (name d) items)))))"),
				arguments("f(1, a=2, *b, **c)",
						"(expr (call (name f) (int 1) (= a (int 2)) (* (name b)) (** (name c))))"),
				arguments("a.b(c,)[d][1:][::-1][:2:][1, 2]",
						"(expr (index (slice (slice (slice (index (call (dot (name a) b) (name c)) (name d)) (int 1) _ _) _ _ (- (int 1))) _ (int 2) _) (tuple (int 1) (int 2))))"),
				arguments("x, [y, z.w], v[0] = (1,), (), (1, 2,)",
						"(= (tuple (name x) (list (name y) (dot (name z) w)) (index (name v) (int 0))) (tuple (tuple (int 1)) (tuple) (tuple (int 1) (int 2))))"),
				arguments("a += 1; b //= {'k': [], \"j\": 2,}; c <<= 3;",
						"(+= (name a) (int 1))\n(//= (name b) (dict (: (string \"k\") (list)) (: (string \"j\") (int 2))))\n(<<= (name c) (int 3))"),
				arguments("""
						def f(a, *, b=1):
						    if a:
						        pass
						    elif b:
						        return
						    else:
						        return a, b
						    for x, in a, b:
						        if x: continue; break
						def g(*args, **kwargs,): return 1
						""",
						"(def f (a * (= b (int 1))) (if (name a) ((pass)) ((if (name b) ((return)) ((return (tuple (name a) (name b))))))) (for (tuple (name x)) (tuple (name a) (name b)) (if (name x) ((continue) (break)) ())))\n(def g ((* args) (** kwargs)) (return (int 1)))"),
				arguments("load(\"m.star\", \"x\", y = \"z\",)", "(load \"m.star\" (x \"x\") (y \"z\"))"),
				arguments("x = [0, 0x7f, 0O17, 0b101, 12345678901234567890, 1.5e3, .5, 1., 0e0, 1E-2]",
						"(= (name x) (list (int 0) (int 127) (int 15) (int 5) (int 12345678901234567890) (float 1500.0) (float 0.5) (float 1.0) (float 0.0) (float 0.01)))"),
				arguments("""
						x = ["a\\x41\\101\\0\\u00e9\\U0001F600\\a\\v", r"a\\"b\\n\\
						", '''x"y
						z''', "j\\
						oin", b"\\xff\\377\\u00e9aé", rb'\\x']
						""",
						"(= (name x) (list (string \"aAA\\x00é😀\\x07\\x0b\") (string \"a\\\\\\\"b\\\\n\\\\\\n\") (string \"x\\\"y\\nz\") (string \"join\") (bytes \"\\xff\\xff\\xc3\\xa9a\\xc3\\xa9\") (bytes \"\\\\x\")))"),
				arguments("""
						# comment
						if x:   # comment

						      # odd comment indentation
						    y = (1 +
						  2) \\
						      + 3
						    z = [
						]
						""", "(if (name x) ((= (name y) (+ (+ (int 1) (int 2)) (int 3))) (= (name z) (list))) ())"));
	}

	@ParameterizedTest
	@MethodSource("trees")
	void theGrammarsFormsReadAsTheirTrees(String source, String tree) {
		assertThat(StarlarkTrees.dump(parse(source))).isEqualTo(tree);
	}

	static List<Arguments> rejections() {
		return List.of(arguments("0 <= i < n", "1:8: '<' does not associate with '<=' (use parentheses)"),
				arguments("a == b != c", "1:8: '!=' does not associate with '==' (use parentheses)"),
				arguments("while x: pass", "1:1: reserved keyword 'while' is not supported"),
				arguments("class C: pass", "1:1: reserved keyword 'class' is not supported"),
				arguments("import os", "1:1: reserved keyword 'import' is not supported"),
				arguments("x = y is None", "1:7: reserved keyword 'is' is not supported"),
				arguments("try: pass", "1:1: reserved keyword 'try' is not supported"),
				arguments("with x: pass", "1:1: reserved keyword 'with' is not supported"),
				arguments("def f(): yield 1", "1:10: reserved keyword 'yield' is not supported"),
				arguments("global x", "1:1: reserved keyword 'global' is not supported"),
				arguments("nonlocal x", "1:1: reserved keyword 'nonlocal' is not supported"),
				arguments("for x in y:\n  pass\nelse:\n  pass", "3:1: for/else is not supported"),
				arguments("x = f\"{y}\"", "1:5: unsupported string prefix 'f'"),
				arguments("a = b = 1", "1:7: chained assignment is not supported"),
				arguments("a = b += 1", "1:7: chained assignment is not supported"),
				arguments("a[1:2] = 3", "1:2: cannot assign to a slice"),
				arguments("f() = 3", "1:2: cannot assign to a function call"),
				arguments("a, b += 1", "1:1: cannot augment-assign to a tuple"),
				arguments("for f() in y: pass", "1:6: cannot assign to a function call"),
				arguments("x = 1,", "1:6: unparenthesized tuple with trailing comma"),
				arguments("f(*a, b)", "1:7: positional argument may not follow *args"),
				arguments("f(a=1, b)", "1:8: positional argument may not follow keyword argument"),
				arguments("f(*a, b=1)", "1:7: keyword argument may not follow *args"),
				arguments("f(**a, *b)", "1:8: *args may not follow **kwargs"),
				arguments("f(*a, *b)", "1:7: *args may not follow *args"),
				arguments("f(a=1, a=2)", "1:8: keyword argument a repeated"),
				arguments("def f(a=1, b): pass", "1:12: required parameter may not follow optional"),
				arguments("def f(a, a): pass", "1:10: duplicate parameter: a"),
				arguments("def f(*): pass", "1:7: bare * must be followed by keyword-only parameters"),
				arguments("def f(*, **k): pass", "1:7: bare * must be followed by keyword-only parameters"),
				arguments("def f(*a, *b): pass", "1:11: multiple * parameters not allowed"),
				arguments("def f(**k, a): pass", "1:12: parameter may not follow **kwargs"),
				arguments("return 1", "1:1: return statement not within a function"),
				arguments("def f():\n  break", "2:3: 'break' not in a loop"),
				arguments("for x in y:\n  def f(): continue", "2:12: 'continue' not in a loop"),
				arguments("def f():\n  load(\"m\", \"x\")", "2:3: load statement within a function"),
				arguments("load(\"m\")", "1:1: load statement must import at least 1 symbol"),
				arguments("2 ** 3", "1:3: got '**', want newline"),
				arguments("x = y if z", "1:11: conditional expression without else clause"),
				arguments("x = {1, 2}", "1:7: got ',', want ':'"),
				arguments("x = (y for y in z)", "1:8: got 'for', want ')'"),
				arguments("x = \"a\" \"b\"", "1:9: got string literal, want newline"),
				arguments("x = y.load", "1:7: got 'load', want identifier"),
				arguments("6burgle", "1:2: got identifier, want newline"),
				arguments("x = a not b", "1:11: got identifier, want 'in'"),
				arguments("x = a == not b", "1:10: got 'not', want primary expression"),
				arguments("x = @y", "1:5: unexpected input character '@'"),
				arguments("x = 1 \\ + 2", "1:7: unexpected input character '\\'"),
				arguments("  x = 1", "1:3: unexpected indent"),
				arguments("def f():\npass", "2:1: expected an indented block, got 'pass'"),
				arguments("if x:\n\ty = 1", "2:1: tab characters are not allowed in indentation"),
				arguments("if x:\n    y\n  z", "3:3: unindent does not match any outer indentation level"),
				arguments("x = 012", "1:5: invalid int literal 012: leading zeros are not allowed (0o prefixes octal)"),
				arguments("x = 0x", "1:5: invalid 0x literal: no digits"),
				arguments("x = 1e400", "1:5: floating-point literal 1e400 is too large"),
				arguments("x = 'a\\qb'", "1:7: invalid escape sequence \\q"),
				arguments("x = '\\200'",
						"1:6: non-ASCII octal escape \\200 is out of range (use \\u for a code point)"),
				arguments("x = '\\x80'", "1:6: non-ASCII hex escape \\x80 is out of range (use \\u for a code point)"),
				arguments("x = b'\\400'", "1:7: octal escape \\400 is out of range"),
				arguments("x = '\\x4'", "1:6: truncated escape sequence \\x4"),
				arguments("x = '\\ud800'", "1:6: invalid Unicode code point U+D800 in escape \\ud800"),
				arguments("x = 'abc\ny'", "1:5: unterminated string literal"));
	}

	@ParameterizedTest
	@MethodSource("rejections")
	void whatStarlarkRejectsFailsAtItsPosition(String source, String message) {
		assertThatThrownBy(() -> parse(source)).isInstanceOf(LispReadException.class).hasMessage("t.star:" + message);
	}

	@Test
	void nodesCarryTheirPositionAndAnUnfinishedInputSaysSo() {
		Assignment assignment = (Assignment) parse("x = foo(1,\n        bar.baz + 2)").getFirst();
		Call call = (Call) assignment.value();
		Binary sum = (Binary) call.arguments().get(1).value();
		assertThat(List.of(assignment.at(), call.at(), call.function().at(), sum.at(), ((Dot) sum.left()).at(),
				sum.right().at()))
			.containsExactly(new SourceLocation("t.star", 1, 1), new SourceLocation("t.star", 1, 8),
					new SourceLocation("t.star", 1, 5), new SourceLocation("t.star", 2, 17),
					new SourceLocation("t.star", 2, 13), new SourceLocation("t.star", 2, 19));
		assertThat(List.of("f(1,", "x = '''abc", "def f():\n"))
			.allSatisfy(source -> assertThatThrownBy(() -> parse(source))
				.isInstanceOfSatisfying(LispReadException.class, e -> assertThat(e.isEndOfFile()).isTrue()));
		assertThatThrownBy(() -> parse("x = )")).isInstanceOfSatisfying(LispReadException.class,
				e -> assertThat(e.isEndOfFile()).isFalse());
		assertThatThrownBy(() -> StarlarkParser.parse("x = )", null)).hasMessage("got ')', want primary expression");
	}

}
