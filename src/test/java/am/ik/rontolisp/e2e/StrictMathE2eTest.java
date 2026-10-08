package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.StrictMathFunction;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * {@code (%strict-math :name x [y])} answers {@code java.lang.StrictMath}'s bits for
 * every function it names, on the interpreter, the JVM and both wasm backends, over
 * ordinary, special and subnormal arguments; and {@code scale-float} answers
 * {@code Math.scalb}'s, the subnormal range included. Every result prints as its raw bits
 * (a NaN as {@code NaN}: its sign and payload are the engine's), and the expectations are
 * the JDK's own.
 */
class StrictMathE2eTest {

	/** The arguments of the one-argument functions, and the first of the others. */
	private static final double[] XS = { 0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.0, 3.0, -3.0, 10.0, 100.0, 0.1, 0.7, -0.7,
			1.5, 2.5, -2.5, 1e-300, -1e-300, 1e300, -1e300, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
			Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
			Math.PI, Math.PI / 2, Math.E, 1e22, 709.78, -745.13, 0x1p-1060, 3e-320, 0x1p-25 + 0x1p-77,
			-2.3779429016845906e285, 0.9999999999999999, 4503599627370497.0, 1e-8, 27.0, -8.0, 2.302585092994046 };

	/** The second arguments of the two-argument functions. */
	private static final double[] YS = { 0.0, -0.0, 1.0, -1.0, 0.5, 2.0, 3.0, -3.0, 1e-300, 1e300,
			Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Double.MIN_VALUE, Math.PI };

	/**
	 * The exponents of {@code :scalb} and {@code scale-float}, two bignums among them.
	 */
	private static final BigInteger[] NS = exponents(-2300, -2201, -2200, -2099, -1100, -1075, -1074, -1050, -2004,
			-1023, -1000, -999, -52, -1, 0, 1, 52, 999, 1000, 1023, 1024, 2099, 2200, 2300);

	@TempDir
	static Path workDir;

	private static String expected;

	@BeforeAll
	static void expectations() {
		StringBuilder out = new StringBuilder();
		for (double x : XS) {
			for (StrictMathFunction fn : StrictMathFunction.values()) {
				if (fn.shape() == StrictMathFunction.Shape.UNARY) {
					out.append(bits(fn.apply(x)));
				}
			}
			out.append(StrictMath.getExponent(x)).append('\n');
		}
		for (double x : XS) {
			for (double y : YS) {
				for (StrictMathFunction fn : StrictMathFunction.values()) {
					if (fn.shape() == StrictMathFunction.Shape.BINARY) {
						out.append(bits(fn.apply(x, y)));
					}
				}
			}
		}
		for (double x : XS) {
			for (BigInteger n : NS) {
				double scaled = StrictMath.scalb(x, clamp(n));
				out.append(bits(scaled)).append(bits(scaled));
			}
		}
		expected = out.toString();
	}

	@Test
	void interpreter() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("strict-math", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.COMMON_LISP.read(program(), Features.INTERPRETER, "strict-math.lisp")) {
				evaluator.eval(form);
			}
			return null;
		});
		assertSame(out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void jvm() throws Exception {
		String name = "StrictMathProgram";
		byte[] classBytes = new JvmSourceCompiler(name).compile(program(), null).classBytes();
		ClassLoader loader = new ClassLoader(StrictMathE2eTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				if (n.equals(name)) {
					return defineClass(n, classBytes, 0, classBytes.length);
				}
				return super.findClass(n);
			}
		};
		Method main = loader.loadClass(name).getMethod("main", String[].class);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.out(out)) {
			CliStack.call("strict-math", () -> main.invoke(null, (Object) new String[0]));
		}
		assertSame(out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void wasmPreview1() throws Exception {
		assertSame(runOnWasm(false));
	}

	@Test
	void wasmComponent() throws Exception {
		assertSame(runOnWasm(true));
	}

	private static void assertSame(String actual) {
		List<String> got = actual.lines().toList();
		List<String> want = expected.lines().toList();
		assertThat(got).hasSameSizeAs(want);
		List<String> differ = new ArrayList<>();
		for (int i = 0; i < want.size() && differ.size() < 5; i++) {
			if (!got.get(i).equals(want.get(i))) {
				differ.add("line " + (i + 1) + ": " + got.get(i) + ", StrictMath " + want.get(i));
			}
		}
		assertThat(differ).as("results differing from StrictMath").isEmpty();
	}

	private static String runOnWasm(boolean component) throws Exception {
		if (!HostWasmtime.isAvailable()) {
			abort("no usable wasmtime on PATH");
		}
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.commonLisp(program(), "strict-math.lisp",
				workDir.toString(), true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(workDir, "strict-math", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "strict-math", ".out");
		Path errFile = Files.createTempFile(workDir, "strict-math", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("wasmtime command timed out: " + path);
		}
		assertThat(process.exitValue()).as("wasmtime exit code: %s", Files.readString(errFile)).isZero();
		return Files.readString(outFile, StandardCharsets.UTF_8);
	}

	// One loop per shape: the one-argument functions and :get-exponent over XS, the
	// two-argument ones over XS x YS, then :scalb and scale-float over XS x NS. The
	// keyword of %strict-math is a literal at each call, as the compilers require.
	private static String program() {
		StringBuilder unary = new StringBuilder();
		StringBuilder binary = new StringBuilder();
		for (StrictMathFunction fn : StrictMathFunction.values()) {
			switch (fn.shape()) {
				case UNARY -> unary.append(" (show (%strict-math ").append(fn.keyword()).append(" x))");
				case BINARY -> binary.append(" (show (%strict-math ").append(fn.keyword()).append(" x y))");
				default -> {
				}
			}
		}
		return """
				(defun show (x)
				  (if (/= x x) (princ "NaN") (princ (%%ieee754-double-bits x)))
				  (terpri))
				(defun doubles (bits) (mapcar (lambda (b) (%%ieee754-double-from-bits b)) bits))
				(defparameter *xs* (doubles '(%s)))
				(defparameter *ys* (doubles '(%s)))
				(defparameter *ns* '(%s))
				(dolist (x *xs*)%s
				  (princ (%%strict-math :get-exponent x))
				  (terpri))
				(dolist (x *xs*)
				  (dolist (y *ys*)%s))
				(dolist (x *xs*)
				  (dolist (n *ns*)
				    (show (%%strict-math :scalb x n))
				    (show (scale-float x n))))
				""".formatted(bitList(XS), bitList(YS), join(NS), unary, binary);
	}

	private static String bitList(double[] values) {
		StringBuilder out = new StringBuilder();
		for (double v : values) {
			out.append(out.isEmpty() ? "" : " ").append(Long.toUnsignedString(Double.doubleToRawLongBits(v)));
		}
		return out.toString();
	}

	private static String join(BigInteger[] values) {
		StringBuilder out = new StringBuilder();
		for (BigInteger v : values) {
			out.append(out.isEmpty() ? "" : " ").append(v);
		}
		return out.toString();
	}

	private static BigInteger[] exponents(int... values) {
		List<BigInteger> out = new ArrayList<>();
		for (int v : values) {
			out.add(BigInteger.valueOf(v));
		}
		out.add(BigInteger.TWO.pow(70));
		out.add(BigInteger.TWO.pow(70).negate());
		return out.toArray(BigInteger[]::new);
	}

	private static int clamp(BigInteger n) {
		return n.max(BigInteger.valueOf(-StrictMathFunction.SCALB_CLAMP))
			.min(BigInteger.valueOf(StrictMathFunction.SCALB_CLAMP))
			.intValueExact();
	}

	private static String bits(double d) {
		return (Double.isNaN(d) ? "NaN" : Long.toUnsignedString(Double.doubleToRawLongBits(d))) + "\n";
	}

}
