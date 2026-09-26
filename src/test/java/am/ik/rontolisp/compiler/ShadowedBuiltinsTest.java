package am.ik.rontolisp.compiler;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.Environment;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowedBuiltinsTest {

	@Test
	void everyLoweredNameIsAJavaBackedBuiltinOnTheInterpreter() {
		// Parity pin with the interpreter half: the interpreter stashes a
		// shadowed built-in only when the global binding is a Java-backed LispFunction
		// (LispEvaluator.builtinDefaultMethodFor), so every name this pass treats as
		// shadowable on the compile paths must be one -- otherwise a defmethod on it
		// would keep the built-in here and lose it there. A name in this set that is
		// NOT a LispFunction (a macro, a special form, a prelude defun) is a
		// misclassification: its call sites are not function calls, and rewriting them
		// onto a dispatcher would corrupt the form.
		Environment env = Environment.createGlobal(System.out);
		List<String> notBuiltins = new ArrayList<>();
		for (String name : ShadowedBuiltins.loweredBuiltinFunctions()) {
			if (!(env.lookupFunctionOrNull(name) instanceof LispFunction)) {
				notBuiltins.add(name);
			}
		}
		assertThat(notBuiltins).isEmpty();
	}

	@Test
	void everyLoweredNameDispatchesAUserMethodOnTheInterpreter() {
		// The other half of the parity: being a LispFunction is what makes the
		// interpreter STASH the built-in, but its operator tables expand many of these
		// names before the global binding is read ((byte-size x) into (car x)), and its
		// multiple-value lowerings spell a producer's values by name ((floor x) into
		// quotient and remainder). Each name is methoded in a fresh evaluator and called
		// on an instance directly, from a function's tail, under a consumer and from a
		// lambda's tail -- every one must reach the method, as it does on the compile
		// paths, which rename the call onto the dispatcher.
		Map<String, String> ignored = new TreeMap<>();
		for (String name : ShadowedBuiltins.loweredBuiltinFunctions()) {
			String program = """
					(defclass shadow-probe () ())
					(defmethod %1$s ((p shadow-probe) &rest r) (declare (ignore r)) :dispatched)
					(defun shadow-probe-call (x) (%1$s x))
					(let ((p (make-instance 'shadow-probe)))
					  (list (%1$s p) (shadow-probe-call p) (multiple-value-list (%1$s p))
					        (funcall (lambda (x) (%1$s x)) p)))
					""".formatted(name);
			String answer;
			try {
				LispEvaluator evaluator = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
				LispVal result = LispNil.INSTANCE;
				for (LispVal form : LispReader.readAllFromString(program)) {
					result = evaluator.eval(form);
				}
				answer = result.print();
			}
			catch (RuntimeException ex) {
				answer = ex.getMessage();
			}
			if (!"(:DISPATCHED :DISPATCHED (:DISPATCHED) :DISPATCHED)".equals(answer)) {
				ignored.put(name, answer);
			}
		}
		assertThat(ignored).isEmpty();
	}

	@Test
	void theFastIoGrayMethodNamesAreAllShadowable() {
		// The measured trigger: fast-io's gray.lisp defines methods on
		// these five CL built-ins. Each must be in the computed set, or loading
		// fast-io silently loses the user methods on the compile paths again.
		assertThat(ShadowedBuiltins.loweredBuiltinFunctions()).contains("CLOSE", "OPEN-STREAM-P", "INPUT-STREAM-P",
				"OUTPUT-STREAM-P", "STREAM-ELEMENT-TYPE");
	}

}
