package am.ik.rontolisp.compiler;

import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The call-position shapes of the wrapped built-ins: every count the wrapper's function
 * value takes, widened to the operator's standard lambda list, and the one report a count
 * outside them makes.
 */
class BuiltinCallArityTest {

	@Test
	void everyWrappedBuiltinHasAShapeThatTakesWhatItsFunctionValueTakes() {
		for (String name : BuiltinFunctionWrappers.wrapperNames()) {
			BuiltinCallArity.Shape shape = BuiltinCallArity.of(name);
			assertThat(shape).as(name).isNotNull();
			LispVal lambdaList = ((LispCons) ((LispCons) Objects
				.requireNonNull(BuiltinFunctionWrappers.lambdaFor(name))).cdr()).car();
			BuiltinCallArity.Shape wrapper = wrapperShape(lambdaList);
			for (int count = 0; count <= 8; count++) {
				if (wrapper.accepts(count)) {
					assertThat(shape.accepts(count)).as(name + " with " + count).isTrue();
				}
			}
		}
		assertThat(BuiltinCallArity.of("NO-SUCH-OPERATOR")).isNull();
	}

	@Test
	void theStandardLambdaListWidensAWrapperThatIsNarrowerThanItsOperator() {
		// #'< is binary (a sort predicate is a two-argument call); (< a b c) is legal.
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.LT, 3)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.LT, 0))
			.isEqualTo("< expects at least 1 argument, got 0");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.GETHASH, 3)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.GETHASH, 4))
			.isEqualTo("GETHASH expects at most 3 arguments, got 4");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.LOGAND, 0)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.GENSYM, 1)).isNull();
	}

	@Test
	void theReportIsTheCountTheShapeBroke() {
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.CAR, 2)).isEqualTo("CAR expects 1 argument, got 2");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.NTH, 1)).isEqualTo("NTH expects 2 arguments, got 1");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.FLOOR, 0))
			.isEqualTo("FLOOR expects at least 1 argument, got 0");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.FLOOR, 3))
			.isEqualTo("FLOOR expects at most 2 arguments, got 3");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.MAPCAR, 1))
			.isEqualTo("MAPCAR expects at least 2 arguments, got 1");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.CAR, 1)).isNull();
	}

	@Test
	void aWrongCountCallEvaluatesItsArgumentsAndThenSignals() {
		LispCons call = (LispCons) LispReader.readAllFromString("(cons (incf n))").getFirst();
		assertThat(Objects.requireNonNull(BuiltinCallArity.wrongCountSignal(call)).print())
			.isEqualTo("(PROGN (INCF N) (%PROGRAM-ERROR \"CONS expects 2 arguments, got 1\"))");
		assertThat(BuiltinCallArity.wrongCountSignal((LispCons) LispReader.readAllFromString("(cons 1 2)").getFirst()))
			.isNull();
		assertThat(BuiltinCallArity.wrongCountSignal((LispCons) LispReader.readAllFromString("(frob 1)").getFirst()))
			.isNull();
	}

	private static BuiltinCallArity.Shape wrapperShape(LispVal lambdaList) {
		int required = 0;
		int optional = 0;
		boolean inOptional = false;
		for (LispVal rest = lambdaList; rest instanceof LispCons cell; rest = cell.cdr()) {
			String name = cell.car().print();
			if (LispNames.LAMBDA_REST.equals(name)) {
				return new BuiltinCallArity.Shape(required, BuiltinCallArity.UNBOUNDED);
			}
			if (LispNames.LAMBDA_OPTIONAL.equals(name)) {
				inOptional = true;
			}
			else if (inOptional) {
				optional++;
			}
			else {
				required++;
			}
		}
		return new BuiltinCallArity.Shape(required, required + optional);
	}

}
