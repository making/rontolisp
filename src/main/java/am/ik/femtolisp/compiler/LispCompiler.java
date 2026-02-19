package am.ik.femtolisp.compiler;

import java.util.List;

import am.ik.femtolisp.LispVal;

/**
 * Common interface for Lisp compilers.
 */
public interface LispCompiler {

	byte[] compile(List<LispVal> program);

}
