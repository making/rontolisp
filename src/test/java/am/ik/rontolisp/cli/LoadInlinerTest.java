package am.ik.rontolisp.cli;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadInlinerTest {

	// An in-memory SourceLoader so the tests need no files on disk.
	private static SourceLoader loaderOf(Map<String, String> files) {
		return path -> {
			String src = files.get(path);
			if (src == null) {
				throw new java.io.FileNotFoundException(path);
			}
			return src;
		};
	}

	private static List<LispVal> inline(String source, Map<String, String> files) {
		return LoadInliner.inline(LispReader.readAllFromString(source), loaderOf(files));
	}

	@Test
	void inlinesTopLevelLoadInPlace() {
		List<LispVal> result = inline("(load \"core.lisp\") (print (f 5))",
				Map.of("core.lisp", "(defun f (x) (* x x))"));
		// (defun f ...) from the loaded file, then the original (print (f 5)).
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun f (x) (* x x))", "(print (f 5))");
	}

	@Test
	void inlineIsRecursive() {
		List<LispVal> result = inline("(load \"a.lisp\")",
				Map.of("a.lisp", "(load \"b.lisp\") (defun a () 1)", "b.lisp", "(defun b () 2)"));
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun b nil 2)", "(defun a nil 1)");
	}

	@Test
	void resolvesRelativePathsAgainstTheLoadingFile() {
		// The entry source lives in proj/; a top-level (load "core.lisp") resolves to
		// proj/core.lisp, and the nested (load "common.lisp") inside it resolves relative
		// to core.lisp's directory (proj/), not against the process working directory.
		List<LispVal> result = LoadInliner.inline(LispReader.readAllFromString("(load \"core.lisp\") (print (cube 3))"),
				loaderOf(Map.of("proj/core.lisp", "(load \"common.lisp\") (defun cube (x) (* x (sq x)))", //
						"proj/common.lisp", "(defun sq (x) (* x x))")),
				"proj");
		assertThat(result.stream().map(LispVal::print)).containsExactly("(defun sq (x) (* x x))",
				"(defun cube (x) (* x (sq x)))", "(print (cube 3))");
	}

	@Test
	void leavesNonLiteralLoadUntouched() {
		// A load with a computed argument is not a compile-time include.
		List<LispVal> result = inline("(load (concatenate 'string \"a\" \".lisp\"))", Map.of());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).print()).startsWith("(load");
	}

	@Test
	void detectsCircularLoad() {
		assertThatThrownBy(
				() -> inline("(load \"a.lisp\")", Map.of("a.lisp", "(load \"b.lisp\")", "b.lisp", "(load \"a.lisp\")")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Circular load");
	}

	@Test
	void splitProgramCompilesAndRunsOnJvm() throws Exception {
		// This is the regression: before compile-time inlining, a console driver that
		// (load "core.lisp")s its functions failed to compile on the JVM backend because
		// the static defun-collection pass never saw the loaded definitions.
		List<LispVal> program = inline("(load \"core.lisp\") (print (square 7))",
				Map.of("core.lisp", "(defun square (x) (* x x))"));
		byte[] classBytes = new JvmLispCompiler("Test").compile(program);
		assertThat(runMain(classBytes, "Test")).isEqualTo("49");
	}

	// Defines the compiled class from its bytes and runs main, capturing stdout.
	private static String runMain(byte[] classBytes, String name) throws Exception {
		ClassLoader loader = new ClassLoader(LoadInlinerTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				return super.findClass(n);
			}
		};
		java.lang.reflect.Method main = loader.loadClass(name).getMethod("main", String[].class);
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		java.io.PrintStream oldOut = System.out;
		System.setOut(new java.io.PrintStream(baos));
		try {
			main.invoke(null, (Object) new String[0]);
		}
		finally {
			System.setOut(oldOut);
		}
		return baos.toString().trim();
	}

}
