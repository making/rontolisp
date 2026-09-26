package am.ik.rontolisp.compiler;

import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A direct call of a program's own function with a count its lambda list rules out: the
 * arguments, then the interpreter's program-error.
 */
class DefinedCallArityTest {

	@Test
	void aWrongCountCallEvaluatesItsArgumentsAndThenSignals() {
		assertThat(Objects.requireNonNull(DefinedCallArity.wrongCountSignal(call("(ud (incf n))"), "UD", 2, false))
			.print()).isEqualTo("(PROGN (INCF N) (%PROGRAM-ERROR \"Function expects 2 arguments, got 1\"))");
		assertThat(
				Objects.requireNonNull(DefinedCallArity.wrongCountSignal(call("(ud 1 2 3)"), "UD", 2, false)).print())
			.isEqualTo("(PROGN 1 2 3 (%PROGRAM-ERROR \"Function expects 2 arguments, got 3\"))");
		assertThat(Objects.requireNonNull(DefinedCallArity.wrongCountSignal(call("(ur)"), "UR", 1, true)).print())
			.isEqualTo("(PROGN (%PROGRAM-ERROR \"Function expects at least 1 argument, got 0\"))");
		assertThat(
				Objects.requireNonNull(DefinedCallArity.wrongCountSignal(call("((lambda (a) a) 1 2)"), null, 1, false))
					.print())
			.isEqualTo("(PROGN 1 2 (%PROGRAM-ERROR \"Function expects 1 argument, got 2\"))");
	}

	@Test
	void aCountTheLambdaListTakesIsLeftToTheCall() {
		assertThat(DefinedCallArity.wrongCountSignal(call("(ud 1 2)"), "UD", 2, false)).isNull();
		assertThat(DefinedCallArity.wrongCountSignal(call("(ur 1)"), "UR", 1, true)).isNull();
		assertThat(DefinedCallArity.wrongCountSignal(call("(ur 1 2 3)"), "UR", 1, true)).isNull();
	}

	@Test
	void theReportNamesTheOperatorTheInterpreterNames() {
		// A defun of a catalog name, and the compile-path dispatcher a defmethod on a
		// built-in is renamed to, report as the built-in -- the interpreter's dispatcher
		// keeps the built-in's name.
		String dispatcher = LispMacroExpander.shadowedDispatcherName(LispNames.LENGTH);
		assertThat(Objects.requireNonNull(DefinedCallArity.wrongCountSignal(call("(f 1 2)"), dispatcher, 1, false))
			.print()).isEqualTo("(PROGN 1 2 (%PROGRAM-ERROR \"LENGTH expects 1 argument, got 2\"))");
		assertThat(BuiltinFunctionWrappers.arityOperator(dispatcher)).isEqualTo(LispNames.LENGTH);
		assertThat(BuiltinFunctionWrappers.arityOperator(LispNames.CAR)).isEqualTo(LispNames.CAR);
		assertThat(BuiltinFunctionWrappers.arityOperator("%UD--dispatch")).isNull();
		assertThat(BuiltinFunctionWrappers.arityOperator(null)).isNull();
	}

	private static LispCons call(String source) {
		return (LispCons) LispReader.readAllFromString(source).getFirst();
	}

}
