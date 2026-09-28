package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.BuiltinCallArity;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every count a built-in's call shape ADMITS compiles, through the CLI's whole front end,
 * on every compiled target: {@link BuiltinCallArity} lets such a call through to the
 * lowerings, and a lowering narrower than the shape failed the compile
 * ({@code (read-char s nil :eof nil)}: {@code read-char expects 0 to 3 arguments}) or
 * compiled it to a count report ({@code (peek-char nil s nil :eof nil)}). The call's
 * arguments are nil, so a body may fail at run time on the values; nothing runs here. The
 * interpreter's half is
 * {@code LispEvaluatorTest.everyCountABuiltinCallShapeAdmitsReachesItsBody}.
 */
@Execution(ExecutionMode.CONCURRENT)
class BuiltinCallArityCompileTest {

	@ParameterizedTest
	@ValueSource(strings = { "jvm", "wasm", "component" })
	void everyCountABuiltinCallShapeAdmitsCompiles(String target, @TempDir Path dir) throws Exception {
		TreeSet<String> names = new TreeSet<>(BuiltinFunctionWrappers.wrapperNames());
		names.addAll(BuiltinCallArity.nativeNames());
		List<String> calls = new ArrayList<>();
		// tls-listen-pem embeds the certificate files it names, which the compile reads:
		// no call of it compiles without two real files.
		names.remove(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN_PEM));
		for (String name : names) {
			BuiltinCallArity.Shape shape = Objects.requireNonNull(BuiltinCallArity.of(name));
			String spelled = name.indexOf(':') > 0 ? name : "|" + name + "|";
			int last = shape.max() == BuiltinCallArity.UNBOUNDED ? shape.min() + 2 : shape.max();
			for (int count = shape.min(); count <= last; count++) {
				if (shape.accepts(count)) {
					// close's pair is :abort and its value, and nothing else.
					String args = LispNames.CLOSE.equals(name) && count == 3 ? " nil :abort nil" : " nil".repeat(count);
					calls.add("(ignore-errors (" + spelled + args + "))");
				}
			}
		}
		Pattern refusal = Pattern
			.compile("(?i)(expects|requires) (at (most|least) )?(\\d+|one)( (to|or) \\d+)? argument");
		Pattern located = Pattern.compile("admitted-arity\\.lisp:(\\d+):");
		Set<String> refused = new LinkedHashSet<>();
		// A lowering may refuse a nil argument that must be a literal (open's direction,
		// concatenate's result type, a keyword): the compile stops at the first
		// refusal, which is dropped and the rest compiled again. Only a refusal of the
		// COUNT is a failure.
		for (int attempt = 0; attempt < 100; attempt++) {
			Path file = dir.resolve("admitted-arity.lisp");
			Files.writeString(file, String.join("\n", calls) + "\n");
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
					new PrintStream(new ByteArrayOutputStream()));
			RuntimeException failure = null;
			try (var _ = ThreadStdio.err(err)) {
				cli.run(compileArgs(target, dir, file).toArray(String[]::new));
			}
			catch (RuntimeException ex) {
				failure = ex;
			}
			err.toString().lines().filter(line -> refusal.matcher(line).find()).forEach(refused::add);
			if (failure == null) {
				assertThat(refused).isEmpty();
				return;
			}
			String message = String.valueOf(failure.getMessage());
			Matcher line = located.matcher(message);
			assertThat(line.find()).as(message).isTrue();
			int index = Integer.parseInt(line.group(1)) - 1;
			if (refusal.matcher(message).find()) {
				refused.add(calls.get(index) + " => " + message);
			}
			calls.remove(index);
		}
		throw new AssertionError("more than 100 refused calls");
	}

	private static List<String> compileArgs(String target, Path dir, Path file) {
		List<String> args = new ArrayList<>(List.of(file.toString(), "-o"));
		switch (target) {
			case "jvm" ->
				args.addAll(List.of(dir.resolve("AdmittedArity.class").toString(), "--class-name", "AdmittedArity"));
			case "wasm" -> args.add(dir.resolve("admitted-arity.wasm").toString());
			default -> args.addAll(List.of(dir.resolve("admitted-arity-component.wasm").toString(), "--component"));
		}
		return args;
	}

}
