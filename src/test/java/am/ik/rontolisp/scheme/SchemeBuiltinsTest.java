package am.ik.rontolisp.scheme;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
				"values", "call-with-values", "error", "display", "write", "newline", "write-char", "write-string");
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
				&& !symbol.name().equals(SchemeBuiltins.FALSE_VARIABLE)) {
			assertThat(am.ik.rontolisp.eval.SchemeLibrary.isSchemeFunction(symbol.name()))
				.as("%s names %s", entry, symbol.name())
				.isTrue();
		}
	}

	@Test
	void anArgumentATemplateNamesTwiceIsEvaluatedOnce() {
		List<LispVal> forms = Scheme.read("(square (f))", null);
		assertThat(forms.get(1).print()).isEqualTo("(LET ((%SCM-A1 (|f|))) (* %SCM-A1 %SCM-A1))");
	}

}
