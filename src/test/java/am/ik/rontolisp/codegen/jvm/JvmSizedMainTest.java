package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.AppKitLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compiled {@code main} runs the program on a thread of its own, sized by
 * {@code -Drontolisp.stack} (16 MiB by default), not on the launcher's thread
 * ({@code JvmSizedMainBuilder}, {@code .kb/interpreter-stack.md}).
 *
 * <p>
 * The depth cases run the class in a child JVM under {@code -Xint}: an interpreted frame
 * has one size, so a depth that must overflow 1 MiB and fit 16 MiB can be chosen with a
 * wide margin on both sides. With the JIT on, a warm frame is a fraction of a cold one
 * and the same depth can land on either side from one run to the next.
 */
class JvmSizedMainTest {

	/**
	 * Non-tail recursion to the depth the command line names. Under {@code -Xint} a 1 MiB
	 * thread holds about 9,000 of these calls and 16 MiB about 160,000 (2026-09-19,
	 * linux-x64), so 40,000 overflows the first four times over and fits the second more
	 * than three times over.
	 */
	private static final String DEPTH_PROGRAM = """
			(defun depth (n) (if (= n 0) 0 (+ 1 (depth (- n 1)))))
			(print (depth (parse-integer (second (%host-argv)))))
			""";

	private static final String DEPTH = "40000";

	@TempDir
	Path tempDir;

	private static byte[] compile(String lispCode, String className) {
		return new JvmLispCompiler(className).compile(LispReader.readAllFromString(lispCode));
	}

	private record Result(int exitCode, String out, String err) {
	}

	private Result runInChildJvm(String lispCode, String className, String... jvmOptionsThenArgs) throws Exception {
		Path dir = Files.createDirectories(this.tempDir.resolve(className));
		Files.write(dir.resolve(className + ".class"), compile(lispCode, className));
		List<String> command = new ArrayList<>();
		command.add(ProcessHandle.current().info().command().orElse("java"));
		List<String> args = new ArrayList<>();
		for (String option : jvmOptionsThenArgs) {
			(option.startsWith("-") ? command : args).add(option);
		}
		command.add("-cp");
		command.add(dir.toString());
		command.add(className);
		command.addAll(args);
		Path err = dir.resolve("stderr.txt");
		Process process = new ProcessBuilder(command).redirectError(err.toFile()).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exitCode = process.waitFor();
		// A JVMCI build (GraalVM) warns on -Xint that its compiler is off: the host's
		// line, not the program's.
		String programErr = Files.readAllLines(err)
			.stream()
			.filter(line -> !line.contains("VM warning:"))
			.map(line -> line + "\n")
			.collect(java.util.stream.Collectors.joining());
		return new Result(exitCode, out.trim(), programErr);
	}

	@Test
	void theProgramRunsOnItsOwnStackNotTheLaunchersOne() throws Exception {
		Result deep = runInChildJvm(DEPTH_PROGRAM, "SizedDeep", "-Xint", "-Xss1m", DEPTH);
		assertThat(deep.err()).isEmpty();
		assertThat(deep.out()).isEqualTo(DEPTH);
		assertThat(deep.exitCode()).isZero();
	}

	@Test
	void theStackPropertySizesTheThreadTheProgramRunsOn() throws Exception {
		// The control for the case above: the same depth on a 1 MiB worker overflows, so
		// the default one cannot have passed on a stack it never needed.
		Result shallow = runInChildJvm(DEPTH_PROGRAM, "SizedShallow", "-Xint", "-Drontolisp.stack=1", DEPTH);
		assertThat(shallow.err()).startsWith("Exception in thread \"main\" java.lang.StackOverflowError");
		assertThat(shallow.exitCode()).isEqualTo(1);
	}

	@Test
	void aStackPropertyOfZeroHandsTheSizeBackToXss() throws Exception {
		Result xss = runInChildJvm(DEPTH_PROGRAM, "SizedXss", "-Xint", "-Drontolisp.stack=0", "-Xss1m", DEPTH);
		assertThat(xss.err()).startsWith("Exception in thread \"main\" java.lang.StackOverflowError");
		Result roomy = runInChildJvm(DEPTH_PROGRAM, "SizedXssRoomy", "-Xint", "-Drontolisp.stack=0", "-Xss16m", DEPTH);
		assertThat(roomy.out()).isEqualTo(DEPTH);
	}

	@Test
	void anUncaughtConditionStillReportsOneLineAndExitsOneFromThreadZero() throws Exception {
		// The worker's throwable is rethrown on thread 0: the report, the launcher's echo
		// naming thread "main", and the exit code are what they were when the program
		// ran there.
		Result failed = runInChildJvm("""
				(print 1)
				(error "boom: ~a" 42)
				""", "SizedFail");
		assertThat(failed.out()).isEqualTo("1");
		assertThat(failed.err()).isEqualTo("""
				Unhandled condition: boom: 42
				Exception in thread "main" java.lang.RuntimeException: boom: 42
				""");
		assertThat(failed.exitCode()).isEqualTo(1);
	}

	@Test
	void theProgramThreadIsNamedMainAndIsNotTheCaller() throws Exception {
		String className = "SizedThread";
		JvmLispCompiler compiler = new JvmLispCompiler(className);
		Files.write(this.tempDir.resolve(className + ".class"), compiler.compile(LispReader.readAllFromString("""
				(let ((th (java:static "java.lang.Thread" "currentThread")))
				  (print (java:call th "getName"))
				  (print (java:call th "threadId")))
				""")));
		// The java: bridge travels beside the class as its own file.
		for (var file : compiler.runtimeClassFiles().entrySet()) {
			Files.write(this.tempDir.resolve(file.getKey()), file.getValue());
		}
		String out;
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Method main = loader.loadClass(className).getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			out = baos.toString().trim();
		}
		String[] lines = out.split("\\R");
		assertThat(lines[0]).isEqualTo("\"main\"");
		assertThat(Long.parseLong(lines[1].trim())).isNotEqualTo(Thread.currentThread().threadId());
	}

	@Test
	void aProgramWithADefaultMainGetsTheLauncher() {
		ClassModel model = ClassFile.of().parse(compile("(print 1)", "SizedShape"));
		assertThat(methodNames(model)).contains("main", JvmSizedMainBuilder.BODY_METHOD, JvmSizedMainBuilder.RUN_METHOD,
				"run");
		assertThat(model.interfaces()).extracting(i -> i.asInternalName()).containsExactly("java/lang/Runnable");
	}

	@Test
	void anObjcProgramKeepsItsMainOnThreadZero() {
		// AppKit belongs to thread 0 (.kb/objc.md), and a GUI change is verified only by
		// hand on macOS: a program that reaches objc: -- raw, or through the spliced
		// appkit layer -- keeps the main it had, with no launcher anywhere in the class.
		for (List<LispVal> program : List.of(LispReader.readAllFromString("(print (objc:class \"NSObject\"))"),
				AppKitLibrary.process(LispReader.readAllFromString("""
						(appkit:wait (appkit:window "t" :width 10 :height 10))
						""")))) {
			ClassModel model = ClassFile.of().parse(new JvmLispCompiler("SizedObjc").compile(program));
			assertThat(methodNames(model)).contains("main")
				.doesNotContain(JvmSizedMainBuilder.BODY_METHOD, JvmSizedMainBuilder.RUN_METHOD, "run");
			assertThat(model.interfaces()).isEmpty();
			assertThat(new String(new JvmLispCompiler("SizedObjc").compile(program), StandardCharsets.ISO_8859_1))
				.doesNotContain(JvmSizedMainBuilder.STACK_PROPERTY);
		}
	}

	@Test
	void aJvmExportLibraryKeepsItsMainBecauseItsTopLevelRunsInClinit() {
		ClassModel model = ClassFile.of().parse(compile("""
				(defun add2 (a b) (+ a b))
				(rontolisp:jvm-export 'add2 :params '(:long :long) :returns :long)
				""", "SizedExport"));
		assertThat(methodNames(model)).contains("main").doesNotContain(JvmSizedMainBuilder.BODY_METHOD);
	}

	private static List<String> methodNames(ClassModel model) {
		return model.methods().stream().map(MethodModel::methodName).map(n -> n.stringValue()).toList();
	}

}
