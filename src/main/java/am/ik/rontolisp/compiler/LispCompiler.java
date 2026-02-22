package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * Common interface for Lisp compilers.
 */
public interface LispCompiler {

	/**
	 * Compile a Lisp program into bytecode.
	 * @param program the list of top-level expressions
	 * @return the compiled bytecode
	 */
	byte[] compile(List<LispVal> program);

}
