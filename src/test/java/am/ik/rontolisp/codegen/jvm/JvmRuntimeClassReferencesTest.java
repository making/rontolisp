package am.ik.rontolisp.codegen.jvm;

import java.util.HashMap;
import java.util.Map;

import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.reader.Features;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * An emitted class runs with only the runtime classes that travel beside it: the output
 * has no rontolisp jar on its class path, so a runtime class it loads but does not ship
 * is a {@code NoClassDefFoundError} ({@code .kb/jvm-export.md}, "What travels"). Each
 * program runs in a loader that sees the platform classes and the shipped files alone.
 */
class JvmRuntimeClassReferencesTest {

	@ParameterizedTest
	@ValueSource(strings = { "(print (java:call (java:new \"java.lang.StringBuilder\" \"ab\") \"reverse\"))",
			"(defun rev (s) (java:call s \"reverse\")) (print (rev (java:new \"java.lang.StringBuilder\" \"ab\")))",
			"(let ((h (make-hash-table))) (setf (gethash 1 h) 2) (print (gethash 1 h)))",
			"(print (java:call (java:reify \"java.lang.Runnable\" \"run\" (lambda () nil)) \"toString\"))" })
	void runsWithTheShippedRuntimeClassesAlone(String source) {
		JvmLispCompiler compiler = JvmLispCompiler.builder().className("Prog").build();
		byte[] bytes = compiler.compile(CompileFrontendAccess.corpus(source, Features.JVM, false, false));
		Map<String, byte[]> classes = new HashMap<>();
		classes.put("Prog", bytes);
		compiler.runtimeClassFiles()
			.forEach((path, file) -> classes.put(path.substring(0, path.length() - ".class".length()).replace('/', '.'),
					file));
		ShippedOnlyLoader loader = new ShippedOnlyLoader(classes);
		assertThatCode(
				() -> loader.loadClass("Prog").getMethod("main", String[].class).invoke(null, (Object) new String[0]))
			.describedAs(source)
			.doesNotThrowAnyException();
	}

	private static final class ShippedOnlyLoader extends ClassLoader {

		private final Map<String, byte[]> classes;

		ShippedOnlyLoader(Map<String, byte[]> classes) {
			super(ClassLoader.getPlatformClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = this.classes.get(name);
			if (bytes == null) {
				throw new ClassNotFoundException(name);
			}
			return defineClass(name, bytes, 0, bytes.length);
		}

	}

}
