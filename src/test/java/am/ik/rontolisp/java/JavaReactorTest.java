package am.ik.rontolisp.java;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.testsupport.HostBoundaryRuns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A Java program with no {@code main} and no {@code @WitExport} class: compiled to a
 * module with no WASI ({@code --no-wasi}), the public static methods of its entry class
 * are the module's exports, which a host calls through the same glue a Lisp reactor's
 * {@code rontolisp:wasm-export}s are called through. Under a JDK, on the interpreter and
 * on the JVM the class is an ordinary one a program with a {@code main} calls.
 */
class JavaReactorTest {

	@TempDir
	Path dir;

	/**
	 * The reactor: no main, an export of each kind of value, static state -- a logger
	 * among it, whose initialization makes the class's a lazy one.
	 */
	private static final String CALC = """
			import java.util.logging.Logger;

			public class Calc {

			    static int calls;

			    static final String GREETING = greeting("Hello");

			    static final Logger LOG = Logger.getLogger("calc");

			    private static String greeting(String word) {
			        return word + ", ";
			    }

			    public static int add(int a, int b) {
			        return a + b;
			    }

			    public static long square(long x) {
			        return x * x;
			    }

			    public static double half(double x) {
			        return x / 2;
			    }

			    public static boolean even(int n) {
			        return n % 2 == 0;
			    }

			    public static byte next(byte b) {
			        return (byte) (b + 1);
			    }

			    public static short wide(short s) {
			        return (short) (s * 2);
			    }

			    public static String greet(String name) {
			        calls++;
			        LOG.fine(name);
			        System.out.print("greeting ");
			        System.out.println(name);
			        return GREETING + name + "!";
			    }

			    public static void reset() {
			        calls = 0;
			    }

			    public static int count() {
			        return calls;
			    }

			    public static int parse(String text) {
			        return Integer.parseInt(text.trim());
			    }

			    static int hidden() {
			        return 0;
			    }

			    public int instance() {
			        return 1;
			    }

			    public static void audit(String what) {
			        LOG.info(what);
			        LOG.warning(what + " twice");
			        LOG.fine(what + " below the handler");
			        LOG.log(java.util.logging.Level.SEVERE, what, new IllegalStateException("broken"));
			    }
			}
			""";

	/** What the program calling the reactor's methods prints. */
	private static final String CALC_MAIN = """
			public class CalcMain {

			    public static void main(String[] args) {
			        System.out.println(Calc.add(2147483647, 1));
			        System.out.println(Calc.square(3037000500L));
			        System.out.println(Calc.half(5));
			        System.out.println(Calc.even(4) + " " + Calc.even(7));
			        System.out.println(Calc.next((byte) 127));
			        System.out.println(Calc.wide((short) 20000));
			        System.out.println(Calc.greet("Java"));
			        System.out.println(Calc.greet("Lisp ☃"));
			        System.out.println(Calc.count());
			        Calc.reset();
			        System.out.println(Calc.count());
			        System.out.println(Calc.parse(" 42 "));
			    }
			}
			""";

	/** The same calls through the glue {@code --emit-js-glue} writes. */
	private static final String HOST = """
			import { readFile } from "node:fs/promises";
			import { instantiate } from "./calc.js";

			const calc = instantiate(new WebAssembly.Module(await readFile("calc.wasm")));
			console.log(String(calc.add(2147483647, 1)));
			console.log(String(calc.square(3037000500n)));
			console.log(String(calc.half(5)));
			console.log(calc.even(4) + " " + calc.even(7));
			console.log(String(calc.next(127)));
			console.log(String(calc.wide(20000)));
			console.log(calc.greet("Java"));
			console.log(calc.greet("Lisp ☃"));
			console.log(String(calc.count()));
			calc.reset();
			console.log(String(calc.count()));
			console.log(String(calc.parse(" 42 ")));
			console.log(Object.keys(calc.exports).filter((name) => /^[a-z]/.test(name)).sort().join(" "));
			""";

	/**
	 * The records of the reactor's logger where the build hands them to the host's log
	 * ({@code --wasi-logging}): the glue's {@code defaultHost()} writes each on the
	 * console method of its level, the logger's name ahead.
	 */
	private static final String LOGGED_HOST = """
			import { readFile } from "node:fs/promises";
			import { defaultHost, instantiate } from "./logged.js";

			for (const level of ["debug", "info", "warn", "error"]) {
			  console[level] = (text) => console.log(level + " " + text);
			}
			const calc = instantiate(new WebAssembly.Module(await readFile("logged.wasm")), defaultHost());
			calc.audit("stock low");
			console.log(calc.greet("logged"));
			""";

	/**
	 * A reactor of scalar values -- primitives, strings, static state, {@code Math}, a
	 * {@code double[]}, a {@code throw} -- which {@code --no-gc} compiles: a static
	 * initializer that calls code, an {@code int} and a {@code long} that wrap, a
	 * {@code break} and a {@code continue}, a string of each kind of value, and the
	 * library members the run time writes over a value that is an index or nil:
	 * {@code substring}, {@code compareTo}, {@code indexOf} and {@code lastIndexOf},
	 * {@code Integer.parseInt}, a {@code double}'s text.
	 */
	private static final String TALLY = """
			public class Tally {

			    static int calls;

			    static long total = 5L;

			    static final String GREETING = greeting("Hello");

			    private static String greeting(String word) {
			        return word + ", ";
			    }

			    public static int add(int a, int b) {
			        return a + b;
			    }

			    public static long divide(long a, long b) {
			        return a / b;
			    }

			    public static long unsigned(long a, int n) {
			        return a >>> n;
			    }

			    public static byte next(byte b) {
			        return (byte) (b + 1);
			    }

			    public static double root(double x) {
			        return Math.sqrt(x);
			    }

			    public static int count() {
			        calls++;
			        return calls;
			    }

			    public static long tally(long x) {
			        total += x;
			        return total;
			    }

			    public static int odds(int limit) {
			        int s = 0;
			        for (int i = 0;; i++) {
			            if (i % 2 == 0) {
			                continue;
			            }
			            s += i;
			            if (s > limit) {
			                break;
			            }
			        }
			        return s;
			    }

			    public static int check(int n) {
			        if (n < 0) {
			            throw new IllegalArgumentException("negative: " + n);
			        }
			        return n;
			    }

			    public static String greet(String name, int n, boolean b, int code) {
			        return GREETING + name + " " + n + " " + b + " " + (char) code;
			    }

			    public static int at(String s, int i) {
			        return s.charAt(i);
			    }

			    public static boolean same(String a, String b) {
			        return a.equals(b);
			    }

			    public static double sum(int n, int at) {
			        double[] a = new double[n];
			        for (int i = 0; i < n; i++) {
			            a[i] = i * 0.5;
			        }
			        return a[at];
			    }

			    public static String cut(String s, int at) {
			        return s.substring(at, at + 1) + s.substring(at + 1);
			    }

			    public static int order(String a, String b) {
			        return a.compareTo(b);
			    }

			    public static String find(String s, String part) {
			        return s.indexOf(part) + " " + s.indexOf(part.charAt(0), 1) + " " + s.lastIndexOf(part);
			    }

			    public static int parse(String text) {
			        return Integer.parseInt(text);
			    }

			    public static String text(double d) {
			        return "" + d;
			    }
			}
			""";

	/** What a program calling the scalar reactor's methods prints. */
	private static final String TALLY_MAIN = """
			public class TallyMain {

			    public static void main(String[] args) {
			        System.out.println(Tally.add(2147483647, 1) + " " + Tally.divide(Long.MIN_VALUE, -1) + " "
			                + Tally.unsigned(-1L, 60) + " " + Tally.next((byte) 127));
			        System.out.println(Tally.root(2) + " " + Tally.count() + " " + Tally.count() + " " + Tally.tally(7)
			                + " " + Tally.odds(10));
			        System.out.println(Tally.check(3) + " " + Tally.greet("Wasm ☃", -7, true, 0x263A) + " "
			                + Tally.at("a☃c", 1) + " " + Tally.same("ab", "ab") + " " + Tally.same("ab", "abc") + " "
			                + Tally.sum(4, 3));
			        System.out.println(Tally.cut("Wasm ☃ text", 5) + " " + Tally.order("a☃", "ab") + " "
			                + Tally.order("ab", "abc") + " " + Tally.find("banana", "ba") + " " + Tally.parse("-٤٢") + " "
			                + Tally.text(1.0E-5));
			    }
			}
			""";

	/**
	 * The same calls from a host with no glue: a {@code String} written into the module's
	 * memory through its {@code __ronto_alloc}, a result read back from its
	 * {@code (ptr, len)}; then the calls whose exception ends the call in a trap.
	 */
	private static final String TALLY_HOST = """
			import { readFile } from "node:fs/promises";

			const module = new WebAssembly.Module(await readFile("tally.wasm"));
			const t = new WebAssembly.Instance(module).exports;
			const text = (s) => {
			  const bytes = new TextEncoder().encode(s);
			  const at = t.__ronto_alloc(bytes.length);
			  new Uint8Array(t.memory.buffer, at, bytes.length).set(bytes);
			  return [at, bytes.length];
			};
			const read = ([at, length]) => new TextDecoder().decode(new Uint8Array(t.memory.buffer, at, length));
			const double = (d) => (Number.isInteger(d) ? d.toFixed(1) : String(d));
			console.log(t.add(2147483647, 1) + " " + t.divide(-(2n ** 63n), -1n) + " " + t.unsigned(-1n, 60) + " "
			    + t.next(127));
			console.log(double(t.root(2)) + " " + t.count() + " " + t.count() + " " + t.tally(7n) + " " + t.odds(10));
			console.log(t.check(3) + " " + read(t.greet(...text("Wasm ☃"), -7, 1, 0x263a)) + " "
			    + t.at(...text("a☃c"), 1) + " " + Boolean(t.same(...text("ab"), ...text("ab"))) + " "
			    + Boolean(t.same(...text("ab"), ...text("abc"))) + " " + double(t.sum(4, 3)));
			console.log(read(t.cut(...text("Wasm ☃ text"), 5)) + " " + t.order(...text("a☃"), ...text("ab")) + " "
			    + t.order(...text("ab"), ...text("abc")) + " " + read(t.find(...text("banana"), ...text("ba"))) + " "
			    + t.parse(...text("-٤٢")) + " " + read(t.text(1.0e-5)));
			for (const call of [() => t.check(-1), () => t.at(...text("abc"), 5), () => t.sum(2, 2),
			    () => t.divide(1n, 0n), () => t.cut(...text("abc"), 3), () => t.parse(...text("4x"))]) {
			  try {
			    call();
			    console.log("returned");
			  } catch (e) {
			    console.log(e.constructor.name);
			  }
			}
			console.log(WebAssembly.Module.imports(module).length + " "
			    + Object.keys(t).filter((name) => /^[a-z]/.test(name)).sort().join(" "));
			""";

	private Path write(String name, String text) throws Exception {
		Path file = this.dir.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	/**
	 * The reactor's exports answer what a JDK, the interpreter and the JVM answer for the
	 * same calls, its static initialization run by {@code _initialize}, which the glue
	 * calls. The methods that are not public and static are no exports. A program with no
	 * {@code main} is refused where nothing calls it: the interpreter, the JVM, a WASI
	 * command module and a component, whose exports are a WIT world's.
	 */
	@Test
	void theEntryClassesPublicStaticMethodsAreTheExportsOfAModuleWithNoWasi() throws Exception {
		Path calc = write("Calc.java", CALC);
		Path main = write("CalcMain.java", CALC_MAIN);
		String expected = "-2147483648\n-9223372036709301616\n2.5\ntrue false\n-128\n-25536\ngreeting Java\n"
				+ "Hello, Java!\ngreeting Lisp ☃\nHello, Lisp ☃!\n2\n0\n42\n";
		assertThat(jdk(main)).isEqualTo(expected);
		assertThat(HostBoundaryRuns.cli(main.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(main, Files.createDirectories(this.dir.resolve("classes")), "CalcMain"))
			.isEqualTo(expected);
		String refused = "Calc.java:3:14: no main method found in Calc: a program starts at the main of the first "
				+ "class of the file or of the class named as the file; with none it is a reactor, its entry points "
				+ "the public static methods of that class, which a module compiled -o out.wasm --no-wasi exports, "
				+ "or the exports of the @WitExport classes of its source tree, which a component exports";
		assertThatThrownBy(() -> HostBoundaryRuns.cli(calc.toString())).hasMessageEndingWith(refused);
		for (String target : new String[] { "p1.wasm", "Calc.class" }) {
			assertThatThrownBy(() -> HostBoundaryRuns.cli(calc.toString(), "-o", this.dir.resolve(target).toString()))
				.hasMessageEndingWith(refused);
		}
		assertThatThrownBy(
				() -> HostBoundaryRuns.cli(calc.toString(), "-o", this.dir.resolve("c.wasm").toString(), "--component"))
			.hasMessageEndingWith(refused);
		reactorRefused("public static double half(double x)", "public static float half(float x)",
				"Calc.java:23:25: half is an export of the reactor Calc, a public static method: its parameter x is "
						+ "float, which no export carries (byte, short, int, long, double, boolean or String)");
		reactorRefused("public static long square(long x) {\n        return x * x;",
				"public static long[] square(long x) {\n        return new long[] { x * x };",
				"Calc.java:19:26: square is an export of the reactor Calc, a public static method: its result is "
						+ "long[], which no export carries (void, byte, short, int, long, double, boolean or String)");
		reactorRefused("public static void reset() {\n        calls = 0;",
				"public static int add(int a, int b, int c) {\n        return a + b + c;",
				"Calc.java:47:23: add is an export of the reactor Calc, a public static method, and so is "
						+ "add(int,int): a module exports a name once");
		write("Calc.java", CALC);
		assumeTrue(HostBoundaryRuns.nodeAvailable(), "no node on PATH");
		// what an export prints is the host's console's
		HostBoundaryRuns.cli(calc.toString(), "-o", this.dir.resolve("calc.wasm").toString(), "--no-wasi",
				"--host-output", "--emit-js-glue");
		write("package.json", "{ \"type\": \"module\" }\n");
		write("host.js", HOST);
		assertThat(HostBoundaryRuns.node(this.dir, "host.js"))
			.isEqualTo(expected + "add audit count even greet half memory next parse reset square wide\n");
		// its logger's records are the host's where the build says so, the console's
		// method by the level
		HostBoundaryRuns.cli(calc.toString(), "-o", this.dir.resolve("logged.wasm").toString(), "--no-wasi",
				"--wasi-logging", "--emit-js-glue");
		write("logged-host.js", LOGGED_HOST);
		assertThat(HostBoundaryRuns.node(this.dir, "logged-host.js"))
			.isEqualTo("info calc: stock low\nwarn calc: stock low twice\nerror calc: stock low\n"
					+ "java.lang.IllegalStateException: broken\nHello, logged!\n");
	}

	/**
	 * {@code --no-gc} compiles a reactor of scalar values to a plain module with no
	 * import, whose exports answer what a JDK answers: an {@code int} and a {@code long}
	 * wrap, a static initializer that calls code runs once at instantiation, an exception
	 * ends the call in a trap, and the run time's index or nil is nil apart from the
	 * index 0. Where the program leaves the subset it is refused by name, at the Java
	 * position: a read of an object, a {@code float}'s text (a ratio), a command, a
	 * component of no {@code @WitExport} world.
	 */
	@Test
	void aReactorOfScalarValuesCompilesWithNoGcAndWhatLeavesTheSubsetIsRefusedByName() throws Exception {
		Path tally = write("Tally.java", TALLY);
		Path main = write("TallyMain.java", TALLY_MAIN);
		String expected = "-2147483648 -9223372036854775808 15 -128\n1.4142135623730951 1 2 12 16\n"
				+ "3 Hello, Wasm ☃ -7 true ☺ 9731 true false 1.5\n☃ text 9633 -1 0 -1 0 -42 1.0E-5\n";
		assertThat(jdk(main)).isEqualTo(expected);
		scalarRefused("Point.java", """
				public class Point {
				    final int x;
				    Point(int x) {
				        this.x = x;
				    }
				    public static int make(int v) {
				        return new Point(v).x;
				    }
				}
				""", "Point.java:7:9: --no-gc: 'Point.x' in function 'Point.make(int)' reads a structure (Point)");
		scalarRefused("Single.java", """
				public class Single {
				    public static String show(double d) {
				        return "" + (float) d;
				    }
				}
				""", "Single.java:3:9: --no-gc: rational numbers are not supported in function "
				+ "'RONTOLISP::%JAVA-FLOAT-STRING'");
		scalarRefused("Run.java", """
				public class Run {
				    public static void main(String[] args) {
				        System.out.println(1);
				    }
				}
				""", "--no-gc makes a reactor, whose entry points are its exports, and the program exports nothing");
		assertThatThrownBy(() -> HostBoundaryRuns.cli(tally.toString(), "-o", this.dir.resolve("c.wasm").toString(),
				"--no-gc", "--component"))
			.hasMessageContaining("no main method found in Tally")
			.hasMessageContaining(
					"or the exports of the @WitExport classes of its source tree, which a component exports");
		assumeTrue(HostBoundaryRuns.nodeAvailable(), "no node on PATH");
		HostBoundaryRuns.cli(tally.toString(), "-o", this.dir.resolve("tally.wasm").toString(), "--no-gc");
		write("package.json", "{ \"type\": \"module\" }\n");
		write("tally-host.js", TALLY_HOST);
		assertThat(HostBoundaryRuns.node(this.dir, "tally-host.js"))
			.isEqualTo(expected + "RuntimeError\n".repeat(6) + "0 add at check count cut divide find greet memory next"
					+ " odds order parse root same sum tally text unsigned\n");
	}

	/** A program --no-gc refuses, with where and why. */
	private void scalarRefused(String name, String source, String message) throws Exception {
		Path program = write(name, source);
		assertThatThrownBy(() -> HostBoundaryRuns.cli(program.toString(), "-o",
				this.dir.resolve("refused.wasm").toString(), "--no-gc"))
			.hasMessageContaining(message);
	}

	/** The reactor refused for the {@code --no-wasi} module, a declaration replaced. */
	private void reactorRefused(String declared, String replaced, String message) throws Exception {
		Path calc = write("Calc.java", CALC.replace(declared, replaced));
		assertThatThrownBy(
				() -> HostBoundaryRuns.cli(calc.toString(), "-o", this.dir.resolve("r.wasm").toString(), "--no-wasi"))
			.hasMessageEndingWith(message);
	}

	/** A program run under the source launcher of the JDK running the test. */
	private String jdk(Path program) throws Exception {
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				program.getFileName().toString())
			.directory(java.util.Objects.requireNonNull(program.getParent()).toFile())
			.redirectErrorStream(true)
			.start();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		process.getInputStream().transferTo(out);
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
		assertThat(process.exitValue()).as(out.toString(StandardCharsets.UTF_8)).isZero();
		return out.toString(StandardCharsets.UTF_8);
	}

}
