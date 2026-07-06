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
 * The {@code .todo/76}/{@code .todo/81} integration target: Edi Weitz's REAL cl-who
 * sources (vendored unmodified under {@code src/test/resources/cl-who}, BSD) load via
 * {@code asdf:load-system} and render (X)HTML. cl-who's
 * {@code with-html-output-to-string} runs a chain of ordinary defuns (and a generic
 * function) AT MACRO-EXPANSION TIME, so the interpreter path drives {@code LispEvaluator}
 * directly while the compile path mirrors the CLI pipeline ({@code LoadInliner} splices
 * the system, {@code UserMacroExpander} expands the
 * {@code with-html-output}/{@code htm}/{@code str}/{@code esc}/{@code fmt} macros) into
 * {@code JvmLispCompiler}. WASM Preview 1 and {@code --component} are covered by manual
 * four-backend verification (the concatenated ci-spec driver cannot provide the {@code
 * .asd} on disk; a self-contained render is exercised end-to-end by the {@code cl-who}
 * ci-spec case with {@code --system-path}).
 *
 * <p>
 * Lite limitations exercised here: the default no-indent {@code :xml} rendering is exact,
 * and {@code (setf (html-mode) :html5)} switches the output mode (a compile-time constant
 * on the compile path); {@code :indent} and dynamic {@code let}-rebinding of the specials
 * are the documented unsupported cases (see {@code .kb/clos.md}/{@code .todo/76}).
 */
class ClWhoE2eTest {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-who").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-who)
			(princ (cl-who:with-html-output-to-string (s)
			  (:html (:head (:title "Hi")) (:body (:p "Hello" (:a :href "/x" "link"))))))
			(terpri)
			(princ (cl-who:with-html-output-to-string (s)
			  (:div (:span (cl-who:str (+ 1 2)))
			        (:span (cl-who:esc "<a&b>"))
			        (:span (cl-who:fmt "~a-~a" 3 4)))))
			(terpri)
			(princ (cl-who:with-html-output-to-string (s) (:br)))
			(terpri)
			(setf (cl-who:html-mode) :html5)
			(princ (cl-who:with-html-output-to-string (s) (:br)))
			(terpri)
			(setf (cl-who:html-mode) :xml)
			(princ (cl-who:with-html-output-to-string (s)
			  (:p (cl-who:esc (string (code-char 233))))))
			(terpri)
			""";

	private static final List<String> EXPECTED = List.of(
			"<html><head><title>Hi</title></head><body><p>Hello<a href='/x'>link</a></p></body></html>",
			"<div><span>3</span><span>&lt;a&amp;b&gt;</span><span>3-4</span></div>", "<br />", "<br>", "<p>&#xe9;</p>");

	@Test
	void clWhoLoadsAndRendersOnTheInterpreter() {
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
	void clWhoCompilesAndRendersOnJvm() throws Exception {
		// The CLI compile pipeline: inline the system's component files, then expand the
		// with-html-output macros (which run cl-who's defuns/generic at expansion time)
		// before the compiler runs.
		List<LispVal> program = UserMacroExpander
			.expand(LoadInliner.inline(LispReader.readAllFromString(EXERCISE, Features.JVM), SourceLoader.fileSystem(),
					null, List.of(SYSTEM_DIR), Features.JVM));
		byte[] classBytes = new JvmLispCompiler("TestClWho").compile(program);
		assertThat(runMain(classBytes, "TestClWho").lines().map(String::trim)).containsExactlyElementsOf(EXPECTED);
	}

	// Defines the compiled class from its bytes and runs main, capturing stdout.
	private static String runMain(byte[] classBytes, String name) throws Exception {
		ClassLoader loader = new ClassLoader(ClWhoE2eTest.class.getClassLoader()) {
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
		System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
		try {
			main.invoke(null, (Object) new String[0]);
		}
		finally {
			System.setOut(oldOut);
		}
		return baos.toString(StandardCharsets.UTF_8).trim();
	}

}
