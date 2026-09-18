package am.ik.rontolisp.scheme;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemeBuiltinsTest {

	@Test
	void everyProcedureOfTheSubsetIsKnown() {
		assertThat(SchemeBuiltins.entries().keySet()).contains("eq?", "eqv?", "equal?", "+", "-", "*", "/", "=", "<",
				">", "<=", ">=", "quotient", "remainder", "modulo", "abs", "min", "max", "zero?", "positive?",
				"negative?", "odd?", "even?", "number?", "integer?", "exact?", "inexact?", "exact", "inexact",
				"number->string", "string->number", "not", "boolean?", "cons", "car", "cdr", "set-car!", "set-cdr!",
				"caar", "cadr", "cdar", "cddr", "list", "length", "append", "reverse", "list-tail", "list-ref", "memq",
				"memv", "member", "assq", "assv", "assoc", "null?", "pair?", "list?", "symbol?", "symbol->string",
				"string->symbol", "char?", "char->integer", "integer->char", "char=?", "char<?", "string?",
				"make-string", "string-length", "string-ref", "string-set!", "string=?", "string<?", "substring",
				"string-append", "string-copy", "string->list", "list->string", "vector?", "make-vector", "vector",
				"vector-length", "vector-ref", "vector-set!", "vector->list", "list->vector", "vector-fill!",
				"procedure?", "apply", "map", "for-each", "call/cc", "call-with-current-continuation", "dynamic-wind",
				"values", "call-with-values", "error", "display", "write", "newline", "write-char", "write-string",
				"read", "eof-object", "eof-object?", "read-char", "peek-char", "read-line", "char-ready?", "caaar",
				"caadr", "cadar", "caddr", "cdaar", "cdadr", "cddar", "cdddr", "caaaar", "caaadr", "caadar", "caaddr",
				"cadaar", "cadadr", "caddar", "cadddr", "cdaaar", "cdaadr", "cdadar", "cdaddr", "cddaar", "cddadr",
				"cdddar", "cddddr", "filter", "reduce", "fold-left", "fold-right", "delete", "last-pair", "append!",
				"list-index", "1+", "-1+", "random", "runtime", "eval", "environment", "interaction-environment",
				"scheme-report-environment");
	}

	@Test
	void theRunTimeTableAnswersEveryProcedureAndConstantByItsMangledName() {
		// What eval resolves a name against, generated from the same entries: the
		// interpreter's copy holds every one (SchemeLibrary.forms), so a typo in the
		// generator fails here rather than inside some program's eval.
		LispEvaluator evaluator = new LispEvaluator(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		for (LispVal form : Scheme.read("", null)) {
			evaluator.eval(form);
		}
		for (SchemeBuiltins.Entry entry : SchemeBuiltins.entries().values()) {
			LispVal value = lookup(evaluator, entry.name());
			assertThat(value instanceof LispFunction || value instanceof LispLambda).as(entry.name()).isTrue();
		}
		SchemeBuiltins.constants()
			.forEach((name, form) -> assertThat(lookup(evaluator, name).print()).as(name)
				.isEqualTo(evaluator.eval(form).print()));
		assertThat(lookup(evaluator, "no-such-procedure").print()).isEqualTo("RONTOLISP::%SCHEME-UNBOUND");
	}

	private static LispVal lookup(LispEvaluator evaluator, String name) {
		return evaluator.eval(LispReader
			.readAllFromString("(rontolisp::%scheme-builtin '|" + SchemeNames.mangle(name) + "|)", Features.INTERPRETER)
			.get(0));
	}

	@Test
	void noParameterIsSpelledLikeAConstant() {
		for (SchemeBuiltins.Entry entry : SchemeBuiltins.entries().values()) {
			for (SchemeBuiltins.Alternative alternative : entry.alternatives()) {
				assertThat(alternative.required()).as(entry.name()).doesNotContain("T", "NIL");
			}
		}
	}

	@Test
	void everyFirstClassValueEvaluatesToAFunction() {
		// A typo in a :function form, or a helper scheme.lisp does not define, fails
		// here rather than at some user's call site.
		LispEvaluator evaluator = new LispEvaluator(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		for (LispVal form : Scheme.read("", null)) {
			evaluator.eval(form);
		}
		for (SchemeBuiltins.Entry entry : SchemeBuiltins.entries().values()) {
			LispVal value = evaluator.eval(entry.function());
			assertThat(value instanceof LispFunction || value instanceof LispLambda).as(entry.name()).isTrue();
		}
	}

	@Test
	void everyHelperATemplateNamesIsDefinedByTheLibrary() {
		for (SchemeBuiltins.Entry entry : SchemeBuiltins.entries().values()) {
			for (SchemeBuiltins.Alternative alternative : entry.alternatives()) {
				assertHelpersDefined(entry.name(), alternative.template());
			}
			assertHelpersDefined(entry.name(), entry.function());
		}
	}

	private static void assertHelpersDefined(String entry, LispVal form) {
		LispVal rest = form;
		while (rest instanceof am.ik.rontolisp.LispCons cell) {
			assertHelpersDefined(entry, cell.car());
			rest = cell.cdr();
		}
		if (rest instanceof am.ik.rontolisp.LispSymbol symbol && symbol.name().startsWith("RONTOLISP::%SCHEME-")
				&& !symbol.name().equals(SchemeBuiltins.FALSE_VARIABLE)
				&& !symbol.name().equals(SchemeBuiltins.UNSPECIFIED_VARIABLE)
				// A catch tag, not a function: quoted in the exit template and the
				// wrapper, defined nowhere.
				&& !symbol.name().equals(SchemeLowering.EXIT_TAG_NAME)) {
			assertThat(am.ik.rontolisp.eval.SchemeLibrary.isSchemeFunction(symbol.name()))
				.as("%s names %s", entry, symbol.name())
				.isTrue();
		}
	}

	@Test
	void eofObjectWorksAsTheFirstThingAFreshEvaluatorRuns() {
		// The interpreter loads scheme.lisp on the first resolution of one of its
		// FUNCTIONS; a template that reads one of its variables directly would be
		// unbound until some other helper had been called.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		LispVal eof = null;
		for (SchemeTopLevel topLevel : Scheme.session(SchemeStandard.RONTOLISP).read("(eof-object)")) {
			for (LispVal form : topLevel.forms()) {
				eof = evaluator.eval(form);
			}
		}
		assertThat(eof).isNotNull();
		LispEvaluator file = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		for (LispVal form : Scheme.read("(define e (eof-object)) (display (eof-object? e))", null)) {
			file.eval(form);
		}
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("#t");
	}

	@Test
	void stringToSymbolAnswersOneValue() {
		// Common Lisp's intern answers a second value; a Scheme procedure must not.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		for (LispVal form : Scheme.read("(write (call-with-values (lambda () (string->symbol \"abc\")) list))"
				+ " (write (call-with-values (lambda () (string->symbol \"a b\")) list))", null)) {
			evaluator.eval(form);
		}
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("(abc)(a b)");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			(odd? 1.5)                  | odd?: not an integer: 1.5
			(even? 1/2)                 | even?: not an integer: 1/2
			(quotient 7.5 2)            | quotient: not an integer: 7.5
			(floor-quotient 7 2.5)      | floor-quotient: not an integer: 2.5
			(gcd 2.5 4)                 | gcd: not an integer: 2.5
			(lcm 4 'a)                  | lcm: not an integer: a
			(string #\\a 1)             | string: not a character: 1
			(list->string (list #\\a 1)) | list->string: not a character: 1
			(stream-car '())            | stream-car: not a stream pair: ()
			(stream-cdr '())            | stream-cdr: not a stream pair: ()
			""")
	void anArgumentR7rsMakesAnErrorIsRefusedByName(String program, String message) {
		LispEvaluator evaluator = new LispEvaluator(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
		assertThatThrownBy(() -> {
			for (LispVal form : Scheme.read(program, null)) {
				evaluator.eval(form);
			}
		}).hasMessageContaining(message);
	}

	@Test
	void anArgumentATemplateNamesTwiceIsEvaluatedOnce() {
		List<LispVal> forms = Scheme.read("(square (f))", null);
		assertThat(forms.get(1).print()).isEqualTo("(LET ((%SCM-A1 (|f|))) (* %SCM-A1 %SCM-A1))");
	}

}
