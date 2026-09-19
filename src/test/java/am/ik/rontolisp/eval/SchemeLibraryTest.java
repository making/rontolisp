package am.ik.rontolisp.eval;

import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.scheme.Scheme;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeLibraryTest {

	@ParameterizedTest
	@ValueSource(strings = { "(write '|a b|)", "(write '(x #(|1|)))", "(write (string->symbol \"a\"))",
			"(write (read))", "(define (f) '|#t|)", "(write '||)" })
	void aProgramThatCanHoldASymbolWrittenWithVerticalLinesGetsThePrinterArm(String source) {
		assertThat(SchemeLibrary.makesBarSymbols(Scheme.read(source, null), Features.INTERPRETER)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "(display \"hello\")", "(write '(a b ABC a:b ... +))", "(define |a b| 1) (write |a b|)",
			"(write (list #f (if #f #f) (interaction-environment)))", "(write (symbol->string 'abc))",
			"(write (list +inf.0 (floor 2.5)))" })
	void anyOtherProgramKeepsThePrinterItHad(String source) {
		assertThat(SchemeLibrary.makesBarSymbols(Scheme.read(source, null), Features.INTERPRETER)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "(eval '(cond-expand (r7rs 1)) (interaction-environment))",
			"(eval (read (open-input-string \"(cond-expand (else 2))\")) (interaction-environment))" })
	void aProgramThatSpellsCondExpandGetsTheEvalArm(String source) {
		assertThat(processed(source)).contains("(RONTOLISP::%SCHEME-EVAL-COND-EXPAND X)");
	}

	@ParameterizedTest
	@ValueSource(strings = { "(eval '(+ 1 2) (interaction-environment))",
			"(write (cond-expand (r7rs (eval 1 (interaction-environment)))))" })
	void anyOtherEvalHasNoCondExpandArm(String source) {
		assertThat(processed(source)).contains("%SCHEME-EVAL-KEYWORD-P")
			.doesNotContain("(RONTOLISP::%SCHEME-EVAL-COND-EXPAND X)");
	}

	private static String processed(String source) {
		return SchemeLibrary.process(Scheme.read(source, null), Features.INTERPRETER, SourceStandards.DEFAULT)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

}
