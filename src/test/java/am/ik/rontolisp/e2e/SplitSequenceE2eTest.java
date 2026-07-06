package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The Phase 3 integration target ({@code .todo/54}): the REAL split-sequence v2.0.1
 * sources (vendored unmodified under {@code src/test/resources/split-sequence}, MIT) load
 * via {@code asdf:load-system} and work on strings and lists -- including the second
 * return value, which crosses a user-function boundary through the {@code %mv-spill}
 * channel. Runs on all four backends via {@link AsdfLibraryE2eSupport}: the interpreter
 * drives {@code LispEvaluator} directly; the compile paths mirror the CLI pipeline
 * ({@code LoadInliner} splices the system, {@code UserMacroExpander} expands its
 * {@code defmacro check-tests}) into the JVM/WASM compilers. The {@code .asd} only has to
 * be on disk at compile time, so the emitted module is self-contained (WASM Preview 1 and
 * {@code --component} run under {@code wasmtime} in a container).
 */
class SplitSequenceE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "split-sequence")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :split-sequence)
			(print (split-sequence:split-sequence #\\, "a,b,,c"))
			(print (split-sequence:split-sequence #\\, "a,b,,c" :remove-empty-subseqs t))
			(print (split-sequence:split-sequence 3 '(1 2 3 4 5 3 6)))
			(print (split-sequence:split-sequence-if #'evenp '(1 2 3 4 5)))
			(print (split-sequence:split-sequence-if-not #'oddp '(1 2 3 4 5)))
			(multiple-value-bind (parts index)
			    (split-sequence:split-sequence #\\space "hello world lisp")
			  (print parts)
			  (print index))
			(multiple-value-bind (parts index)
			    (split-sequence:split-sequence #\\, '(#\\a #\\, #\\b))
			  (print parts)
			  (print index))
			(print (split-sequence:split-sequence #\\, "a,b,c,d" :count 2))
			(print (split-sequence:split-sequence #\\, "a,b,c,d" :count 2 :from-end t))
			(print (split-sequence:split-sequence #\\, "a,b,c,d" :start 2))
			(print (split-sequence:split-sequence #\\, "a,b,c,d" :end 3))
			(print (split-sequence:split-sequence 2 '(1 2 3 2 4) :test #'eql))
			(print (split-sequence:split-sequence #\\A "aAbAc" :key #'char-upcase :test #'char=))
			(print (split-sequence:split-sequence #\\b '(#\\a #\\b #\\c) :from-end t))
			(print (split-sequence:split-sequence-if #'evenp '(1 2 3 4 5) :count 1))
			""";

	private static final List<String> EXPECTED = List.of("(\"a\" \"b\" \"\" \"c\")", "(\"a\" \"b\" \"c\")",
			"((1 2) (4 5) (6))", "((1) (3) (5))", "((1) (3) (5))", "(\"hello\" \"world\" \"lisp\")", "16",
			"((#\\a) (#\\b))", "3", "(\"a\" \"b\")", "(\"c\" \"d\")", "(\"b\" \"c\" \"d\")", "(\"a\" \"b\")",
			"((1) (3) (4))", "(\"\" \"\" \"b\" \"c\")", "((#\\a) (#\\c))", "((1))");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "TestSplitSequence";
	}

}
