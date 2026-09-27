package am.ik.rontolisp.compiler;

import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
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
	void theComparisonsAndTheBitwiseFoldsTakeTheirStandardLambdaListAsFunctionValues() {
		// #'< takes (a &optional b &rest r) and #'logand (&optional a b &rest r): the
		// operator's own counts, with the two-argument call -- a sort predicate's, a
		// fold's -- passing b as a parameter rather than in a rest list.
		assertThat(wrapperShape(lambdaList(LispNames.LT)))
			.isEqualTo(new BuiltinCallArity.Shape(1, BuiltinCallArity.UNBOUNDED));
		assertThat(wrapperShape(lambdaList(LispNames.CHAR_EQUAL)))
			.isEqualTo(new BuiltinCallArity.Shape(1, BuiltinCallArity.UNBOUNDED));
		assertThat(wrapperShape(lambdaList(LispNames.LOGAND)))
			.isEqualTo(new BuiltinCallArity.Shape(0, BuiltinCallArity.UNBOUNDED));
		assertThat(wrapperShape(lambdaList(LispNames.ADD)))
			.isEqualTo(new BuiltinCallArity.Shape(0, BuiltinCallArity.UNBOUNDED));
		assertThat(wrapperShape(lambdaList(LispNames.MIN)))
			.isEqualTo(new BuiltinCallArity.Shape(1, BuiltinCallArity.UNBOUNDED));
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.LT, 1)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.LT, 0))
			.isEqualTo("< expects at least 1 argument, got 0");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.LOGAND, 0)).isNull();
	}

	@Test
	void theStandardLambdaListWidensAWrapperThatIsNarrowerThanItsOperator() {
		// #'write-to-string does not forward its keywords yet; (write-to-string x :base
		// 2)
		// is legal in call position.
		assertThat(wrapperShape(lambdaList(LispNames.WRITE_TO_STRING))).isEqualTo(new BuiltinCallArity.Shape(1, 1));
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.WRITE_TO_STRING, 3)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.GETHASH, 3)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.GETHASH, 4))
			.isEqualTo("GETHASH expects at most 3 arguments, got 4");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.GENSYM, 1)).isNull();
	}

	@Test
	void theFunctionValueTakesTheOperatorsOptionalAndKeywordArguments() {
		assertThat(wrapperShape(lambdaList(LispNames.GETHASH))).isEqualTo(new BuiltinCallArity.Shape(2, 3));
		assertThat(wrapperShape(lambdaList(LispNames.GENSYM))).isEqualTo(new BuiltinCallArity.Shape(0, 1));
		assertThat(wrapperShape(lambdaList(LispNames.TYPEP))).isEqualTo(new BuiltinCallArity.Shape(2, 3));
		assertThat(wrapperShape(lambdaList(LispNames.STRING_UPCASE)))
			.isEqualTo(new BuiltinCallArity.Shape(1, BuiltinCallArity.UNBOUNDED));
		assertThat(wrapperShape(lambdaList(LispNames.ADJUST_ARRAY)))
			.isEqualTo(new BuiltinCallArity.Shape(2, BuiltinCallArity.UNBOUNDED));
		assertThat(wrapperShape(lambdaList(LispNames.READ_CHAR_NO_HANG))).isEqualTo(new BuiltinCallArity.Shape(0, 4));
	}

	private static LispVal lambdaList(String name) {
		return ((LispCons) ((LispCons) Objects.requireNonNull(BuiltinFunctionWrappers.lambdaFor(name))).cdr()).car();
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

	@Test
	void aNativeBuiltinIsJudgedByItsOwnShapeAndReportedUnderTheInterpretersName() {
		assertThat(BuiltinFunctionWrappers.wrapperNames()).doesNotContainAnyElementsOf(BuiltinCallArity.nativeNames());
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.BOUNDP, 0))
			.isEqualTo("BOUNDP expects 1 argument, got 0");
		assertThat(BuiltinCallArity.wrongCountMessage(LispNames.EXPORT, 3))
			.isEqualTo("EXPORT expects at most 2 arguments, got 3");
		String tcpConnect = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT);
		assertThat(BuiltinCallArity.wrongCountMessage(tcpConnect, 1))
			.isEqualTo("TCP-CONNECT expects 2 arguments, got 1");
		assertThat(Objects
			.requireNonNull(BuiltinCallArity
				.wrongCountSignal((LispCons) LispReader.readAllFromString("(rontolisp:tcp-connect h)").getFirst()))
			.print()).isEqualTo("(PROGN H (%PROGRAM-ERROR \"TCP-CONNECT expects 2 arguments, got 1\"))");
		// tls-connect takes a host and a port, and at most one option pair.
		String tlsConnect = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_CONNECT);
		assertThat(BuiltinCallArity.wrongCountMessage(tlsConnect, 2)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(tlsConnect, 4)).isNull();
		assertThat(BuiltinCallArity.wrongCountMessage(tlsConnect, 3))
			.isEqualTo("TLS-CONNECT expects 2 or 4 arguments, got 3");
		assertThat(BuiltinCallArity.wrongCountMessage(tlsConnect, 1))
			.isEqualTo("TLS-CONNECT expects 2 or 4 arguments, got 1");
		// A library defun that implements a native built-in names it in its own reports.
		assertThat(BuiltinFunctionWrappers.arityOperator(tcpConnect)).isEqualTo("TCP-CONNECT");
		assertThat(BuiltinFunctionWrappers.arityOperator(LispNames.CHAR_NAME)).isEqualTo("CHAR-NAME");
	}

	// A library's implementation of a native built-in takes the built-in's counts; a
	// defun of another shape, and any defun of a catalog name, keeps its own call path.
	@Test
	void aDefunWithANativeBuiltinsOwnCountsIsJudgedAsTheBuiltin() {
		assertThat(BuiltinCallArity.builtinShapedDefuns(LispReader.readAllFromString("""
				(defun rontolisp:tcp-listen (port &optional host) (list port host))
				(progn (defun find-class (name &optional errorp env) (list name errorp env)))
				(defun make-package (name &key use nicknames) (list name use nicknames))
				(defun rontolisp:tls-connect (host port &optional opt value) (list host port opt value))
				(defun char-name (a b) (list a b))
				(defun rontolisp:fetch (url &rest options) (list url options))
				(defun car (x) x)
				(defun my-own (x) x)
				"""))).containsExactlyInAnyOrder(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LISTEN),
				LispNames.FIND_CLASS, LispNames.MAKE_PACKAGE,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_CONNECT));
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
