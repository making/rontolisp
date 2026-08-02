package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.eval.Environment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowedBuiltinsTest {

	@Test
	void everyLoweredNameIsAJavaBackedBuiltinOnTheInterpreter() {
		// Parity pin with the interpreter half of todo-237: the interpreter stashes a
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
	void theFastIoGrayMethodNamesAreAllShadowable() {
		// The measured trigger of todo-237: fast-io's gray.lisp defines methods on
		// these five CL built-ins. Each must be in the computed set, or loading
		// fast-io silently loses the user methods on the compile paths again.
		assertThat(ShadowedBuiltins.loweredBuiltinFunctions()).contains("CLOSE", "OPEN-STREAM-P", "INPUT-STREAM-P",
				"OUTPUT-STREAM-P", "STREAM-ELEMENT-TYPE");
	}

}
