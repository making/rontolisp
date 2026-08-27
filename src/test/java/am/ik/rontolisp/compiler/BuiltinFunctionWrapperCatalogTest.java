package am.ik.rontolisp.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog invariant: <strong>a CL FUNCTION the evaluator lowers in operator position
 * must have a function VALUE too.</strong> A name with an {@code evalCons} case and no
 * {@code BuiltinFunctionWrappers} entry answered "The function NAME is undefined" for
 * {@code #'name} on every backend -- and the consumer is not only {@code mapcar}: rove's
 * {@code form-inspect} rewrites every non-macro form inside an {@code ok} into
 * {@code (apply #'op args)}, so an assertion merely MENTIONING such a name died as a
 * recorded error, or -- when the name only built an argument, {@code #'vector} being the
 * sighting -- as a silent false assertion with the function under test never called.
 *
 * <p>
 * The sweep is table-driven over {@link PackageRegistry#clFunctionNames()} rather than
 * over a hand-kept list, so a new built-in classified as a CL function fails here until
 * it has a value.
 */
class BuiltinFunctionWrapperCatalogTest {

	/**
	 * The CL function names with no function value, deliberately. Each is a standard
	 * GENERIC function with no built-in definition of its own ({@code make-load-form} is
	 * the compile path's literal-object protocol, {@code .kb/make-load-form.md}): the
	 * value appears when the program's own {@code defmethod} generates the dispatcher
	 * defun, exactly as in CL, and there is nothing for a wrapper to call before that. A
	 * wrapper here would be a lambda whose body resolves back to itself.
	 */
	private static final Set<String> USER_DEFINED_GENERICS = Set.of(LispNames.PRINT_OBJECT,
			LispNames.INITIALIZE_INSTANCE, LispNames.REINITIALIZE_INSTANCE, LispNames.SHARED_INITIALIZE,
			LispNames.MAKE_LOAD_FORM);

	@Test
	void everyClFunctionNameHasAFunctionValue() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		List<String> undefined = new ArrayList<>();
		for (String name : PackageRegistry.clFunctionNames()) {
			if (USER_DEFINED_GENERICS.contains(name)) {
				continue;
			}
			try {
				evaluator.eval(LispReader.readFromString("(symbol-function '" + name + ")"));
			}
			catch (RuntimeException notAFunction) {
				undefined.add(name + " -- " + notAFunction.getMessage());
			}
		}
		assertThat(undefined).isEmpty();
	}

	@Test
	void aUserDefinedGenericGetsItsValueFromItsOwnDefmethod() {
		// The other half of the exclusion above: the four names are not permanently
		// value-less, they are value-less until the program defines a method. If this
		// ever fails the exclusion has become a real gap.
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		for (var form : LispReader.readAllFromString("""
				(defclass point () ((x :initarg :x :initform 0)))
				(defmethod print-object ((p point) stream) (princ "#<point>" stream))
				(defmethod initialize-instance ((p point) &rest args) p)
				(defmethod reinitialize-instance ((p point) &rest args) p)
				(defmethod shared-initialize ((p point) slots &rest args) p)
				(defmethod make-load-form ((p point) &optional environment) nil)
				""")) {
			evaluator.eval(form);
		}
		for (String name : USER_DEFINED_GENERICS) {
			assertThat(evaluator.eval(LispReader.readFromString("(functionp (symbol-function '" + name + "))")).print())
				.as(name)
				.isEqualTo("T");
		}
	}

	@Test
	void aWrapperNameResolvesToItsCatalogLambda() {
		// The interpreter reads the function value of a lowered-only built-in out of the
		// SAME catalog the compile paths inject, so the four backends cannot answer
		// different arities for one #'name.
		assertThat(BuiltinFunctionWrappers.lambdaFor(LispNames.ELT)).isNotNull();
		assertThat(BuiltinFunctionWrappers.lambdaFor(LispNames.COERCE)).isNotNull();
		assertThat(BuiltinFunctionWrappers.lambdaFor("NO-SUCH-BUILT-IN")).isNull();
	}

}
