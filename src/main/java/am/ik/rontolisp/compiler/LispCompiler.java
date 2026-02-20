package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;

/**
 * Common interface for Lisp compilers.
 */
public interface LispCompiler {

	byte[] compile(List<LispVal> program);

}
