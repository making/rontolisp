package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.LoadInliner;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 3 integration target ({@code .todo/54}): the REAL split-sequence v2.0.1
 * sources (vendored unmodified under {@code src/test/resources/split-sequence}, MIT) load
 * via {@code asdf:load-system} and work on strings and lists -- including the second
 * return value, which crosses a user-function boundary through the {@code %mv-spill}
 * channel. The interpreter path drives {@code LispEvaluator} directly; the compile path
 * mirrors the CLI pipeline ({@code LoadInliner} splices the system,
 * {@code UserMacroExpander} expands its {@code defmacro check-tests}) into
 * {@code JvmLispCompiler}. WASM Preview 1 and {@code --component} are covered by manual
 * four-backend verification (the concatenated ci-spec driver cannot provide the
 * {@code .asd} on disk; the residue features have their own plain-Lisp ci-spec case).
 */
class SplitSequenceE2eTest {

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

	@Test
	void splitSequenceLoadsAndRunsOnTheInterpreter() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(List.of(SYSTEM_DIR));
		for (LispVal expr : LispReader.readAllFromString(EXERCISE)) {
			evaluator.eval(expr);
		}
		assertThat(out.toString(StandardCharsets.UTF_8).trim().lines().map(String::trim))
			.containsExactlyElementsOf(EXPECTED);
	}

	@Test
	void splitSequenceCompilesAndRunsOnJvm() throws Exception {
		// The CLI compile pipeline: inline the system's component files, then expand
		// the user macros they define (check-tests) before the compiler runs.
		List<LispVal> program = UserMacroExpander
			.expand(LoadInliner.inline(LispReader.readAllFromString(EXERCISE, Features.JVM), SourceLoader.fileSystem(),
					null, List.of(SYSTEM_DIR), Features.JVM));
		byte[] classBytes = new JvmLispCompiler("TestSplitSequence").compile(program);
		assertThat(runMain(classBytes, "TestSplitSequence").lines().map(String::trim))
			.containsExactlyElementsOf(EXPECTED);
	}

	// Defines the compiled class from its bytes and runs main, capturing stdout.
	private static String runMain(byte[] classBytes, String name) throws Exception {
		ClassLoader loader = new ClassLoader(SplitSequenceE2eTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				return super.findClass(n);
			}
		};
		java.lang.reflect.Method main = loader.loadClass(name).getMethod("main", String[].class);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintStream oldOut = System.out;
		System.setOut(new PrintStream(baos));
		try {
			main.invoke(null, (Object) new String[0]);
		}
		finally {
			System.setOut(oldOut);
		}
		return baos.toString().trim();
	}

}
