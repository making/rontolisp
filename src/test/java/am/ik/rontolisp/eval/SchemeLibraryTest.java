package am.ik.rontolisp.eval;

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

}
