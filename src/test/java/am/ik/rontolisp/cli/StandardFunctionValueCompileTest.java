package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@code cl} function is a function VALUE on every compiled target: one program
 * taking each name as {@code #'name} compiles, through the CLI's whole front end. 28 of
 * them -- the standard functions the backends lower only in call position, from
 * {@code arrayp} to {@code symbol-function} -- refused the program with
 * {@code Cannot compile: NAME as a function value}. The interpreter's half is
 * {@code BuiltinFunctionWrapperCatalogTest}; what the values answer is
 * {@code StandardFunctionValueFixture}'s.
 */
@Execution(ExecutionMode.CONCURRENT)
class StandardFunctionValueCompileTest {

	/**
	 * The names without a compiled value by design: the compile path has
	 * {@code require}/{@code provide} only as literal top-level forms
	 * ({@link #requireAndProvideAsValuesRefuseWithTheReasonTheirComputedCallsGet}).
	 */
	private static final Set<String> NO_VALUE = Set.of(LispNames.REQUIRE, LispNames.PROVIDE);

	@ParameterizedTest
	@ValueSource(strings = { "jvm", "wasm", "component" })
	void everyClFunctionCompilesAsAFunctionValue(String target, @TempDir Path dir) throws Exception {
		StringBuilder source = new StringBuilder("(defun every-value ()\n  (list");
		for (String name : PackageRegistry.clFunctionNames()) {
			if (!NO_VALUE.contains(name)) {
				source.append("\n   #'|").append(name).append('|');
			}
		}
		source.append("))\n(print (length (every-value)))\n");
		Path file = dir.resolve("every-value.lisp");
		Files.writeString(file, source);
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.err(err)) {
			cli().run(compileArgs(target, dir, file).toArray(String[]::new));
		}
		assertThat(err.toString().lines().filter(line -> line.contains("warning: ") || line.contains("error")))
			.isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "jvm", "wasm", "component" })
	void requireAndProvideAsValuesRefuseWithTheReasonTheirComputedCallsGet(String target, @TempDir Path dir)
			throws Exception {
		for (String name : List.of("require", "provide")) {
			Path file = dir.resolve(name + "-value.lisp");
			Files.writeString(file, "(defun module-op () #'" + name + ")\n(print 1)\n");
			assertThatThrownBy(() -> {
				try (var _ = ThreadStdio.err(new ByteArrayOutputStream())) {
					cli().run(compileArgs(target, dir, file).toArray(String[]::new));
				}
			}).hasMessageContaining(name.toUpperCase(java.util.Locale.ROOT)
					+ " is only supported as a literal top-level form on the compile path");
		}
	}

	private static RontoLispCli cli() {
		return new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()));
	}

	private static List<String> compileArgs(String target, Path dir, Path file) {
		List<String> args = new ArrayList<>(List.of(file.toString(), "-o"));
		switch (target) {
			case "jvm" ->
				args.addAll(List.of(dir.resolve("EveryValue.class").toString(), "--class-name", "EveryValue"));
			case "wasm" -> args.add(dir.resolve("every-value.wasm").toString());
			default -> args.addAll(List.of(dir.resolve("every-value-component.wasm").toString(), "--component"));
		}
		return args;
	}

}
