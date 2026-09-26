package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import am.ik.rontolisp.compiler.BuiltinCallArity;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A direct call of a native built-in outside the wrapper catalog with a count its shape
 * rules out compiles, through the CLI's whole front end, to the interpreter's run-time
 * {@code program-error} on every compiled target: the per-operator lowerings used to fail
 * the compile ({@code (boundp)}, {@code (rontolisp:tcp-connect "h")}), answer
 * ({@code (export 'x p 3)} was {@code T}) or report {@code Function} from a spliced
 * library defun. The compile-time warning carries the signal's literal message, so
 * matching it here pins the run-time report without running anything; the interpreter's
 * side is
 * {@code LispEvaluatorTest.everyNativeBuiltinReportsAWrongDirectCountWithItsCallShape}.
 */
class NativeCallArityCompileTest {

	@TempDir
	Path dir;

	@ParameterizedTest
	@ValueSource(strings = { "jvm", "wasm", "component" })
	void everyWrongDirectCountOfANativeBuiltinCompilesToItsReport(String target) throws Exception {
		StringBuilder source = new StringBuilder();
		List<String> expected = new ArrayList<>();
		for (String name : new TreeSet<>(BuiltinCallArity.nativeNames())) {
			BuiltinCallArity.Shape shape = Objects.requireNonNull(BuiltinCallArity.of(name));
			String spelled = name.indexOf(':') > 0 ? name : "|" + name + "|";
			int last = shape.max() == BuiltinCallArity.UNBOUNDED ? shape.min() : shape.max() + 1;
			for (int count = 0; count <= last; count++) {
				String message = BuiltinCallArity.wrongCountMessage(name, count);
				if (message != null) {
					source.append("(ignore-errors (").append(spelled).append(" nil".repeat(count)).append("))\n");
					expected.add("warning: " + message + "; compiled as a call-time program-error");
				}
			}
		}
		Path file = this.dir.resolve("native-arity.lisp");
		Files.writeString(file, source);
		List<String> args = new ArrayList<>(List.of(file.toString(), "-o"));
		switch (target) {
			case "jvm" ->
				args.addAll(List.of(this.dir.resolve("NativeArity.class").toString(), "--class-name", "NativeArity"));
			case "wasm" -> args.add(this.dir.resolve("native-arity.wasm").toString());
			default -> args.addAll(List.of(this.dir.resolve("native-arity-component.wasm").toString(), "--component"));
		}
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.err(err)) {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()))
				.run(args.toArray(String[]::new));
		}
		assertThat(err.toString().lines().map(line -> line.substring(line.indexOf("warning: "))).toList())
			.containsAll(expected);
	}

}
