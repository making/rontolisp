package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The uncaught-condition report's location lines are the SAME lines on the interpreter
 * and a compiled {@code .class} ({@code .kb/error-handling.md}, "An uncaught condition
 * reports ONE line"): each program runs both ways in process -- the interpreter through
 * the CLI's reporting wrapper, the class through its {@code main} -- and the whole of
 * standard error is compared, then pinned to what it should say. {@code java Prog} and
 * {@code java -jar} are {@link RontoLispCliStreamsTest}'s {@code anUncaught*} cases.
 *
 * <p>
 * The programs are the shapes where the two used to part: a tail call replacing the
 * interpreter's frame (an {@code flet} helper, an inline lambda, a callback), a lowering
 * that rebuilt forms and dropped their positions ({@code labels}, a user macro, a method
 * body), a library function a user callback runs under, and the async boundaries.
 */
@Execution(ExecutionMode.CONCURRENT)
class UncaughtReportParityTest {

	@TempDir
	Path tempDir;

	private record Report(int exitCode, List<String> err) {
	}

	@Test
	void anFletHelperCalledInTailPositionReportsTheFunctionThatCalledIt() throws Exception {
		Path program = write("flet.lisp", """
				(defun f (x)
				  (flet ((helper (y)
				           (error "helper ~a" y)))
				    (helper x)))

				(f 1)
				""");
		assertSameReport(program, "Unhandled condition: helper 1", "  at " + program + ":3 in F");
	}

	@Test
	void aLabelsFunctionKeepsItsFormsPositions() throws Exception {
		Path program = write("labels.lisp", """
				(defun count-down (n)
				  (labels ((walk (k)
				             (if (= k 0)
				                 (error "bottom reached")
				                 (walk (- k 1)))))
				    (walk n)))

				(count-down 3)
				""");
		assertSameReport(program, "Unhandled condition: bottom reached", "  at " + program + ":4 in COUNT-DOWN");
	}

	@Test
	void anInlineLambdaAndACallbackInTailPositionReportTheFunctionAroundThem() throws Exception {
		Path inline = write("inline.lisp", """
				(defun f ()
				  ((lambda (x)
				     (error "inline ~a" x))
				   7))

				(f)
				""");
		assertSameReport(inline, "Unhandled condition: inline 7", "  at " + inline + ":3 in F");
		Path callback = write("callback.lisp", """
				(defvar *cb* (lambda ()
				               (error "callback failed")))

				(defun run-callback (cb)
				  (funcall cb))

				(run-callback *cb*)
				""");
		assertSameReport(callback, "Unhandled condition: callback failed", "  at " + callback + ":2 in RUN-CALLBACK");
	}

	@Test
	void aMacroCallReportsItsOwnLineWhereverTheMacroWasDefined() throws Exception {
		Path local = write("macro.lisp", """
				(defmacro must (x)
				  `(if ,x ,x (error "must failed")))

				(defun check (v)
				  (must v))

				(check nil)
				""");
		assertSameReport(local, "Unhandled condition: must failed", "  at " + local + ":5 in CHECK");
		writeLibrary();
		Path loaded = write("use.lisp", """
				(load "lib/util.lisp")

				(defun use (v)
				  (with-checked (x v)
				    (print x)))

				(use nil)
				""");
		assertSameReport(loaded, "Unhandled condition: with-checked: X is nil", "  at " + loaded + ":4 in USE");
	}

	@Test
	void aLoadedFilesFunctionNamesThatFile() throws Exception {
		writeLibrary();
		Path program = write("total.lisp", """
				(load "lib/util.lisp")

				(defun total (items)
				  (reduce #'+ (mapcar #'lib-parse items)))

				(print (total '("1" "2" "x")))
				""");
		Report interpreted = interpret(program);
		assertThat(compileAndRun(program)).isEqualTo(interpreted);
		assertThat(interpreted.err()).hasSize(2);
		assertThat(interpreted.err().get(1)).startsWith("  at ").endsWith("lib/util.lisp:5 in LIB-PARSE");
	}

	@Test
	void aLibraryFunctionAUserFunctionRunsUnderIsNeverTheOneNamed() throws Exception {
		// The handler runs under the restart runtime's %run-handlers, a Lisp function of
		// the library on both backends: the report names K, the program's function.
		Path handler = write("handler.lisp", """
				(defun k ()
				  (handler-bind ((error (lambda (c)
				                          (declare (ignore c))
				                          (error "handler failed"))))
				    (error "first")))

				(k)
				""");
		assertSameReport(handler, "Unhandled condition: handler failed", "  at " + handler + ":4 in K");
		// sort's predicate runs under %sort-runtime on the JVM backend and a Java
		// built-in in the interpreter; the two report lines differ (a Java cast failure
		// against the interpreter's type check), the location does not.
		Path sort = write("sort.lisp", """
				(defun f (xs)
				  (sort xs (lambda (a b)
				             (< (car a) b))))

				(print (f (list 1 2 3)))
				""");
		assertSameLocation(sort, "  at " + sort + ":3 in F");
	}

	@Test
	void aMethodBodyReportsItsGenericFunction() throws Exception {
		Path program = write("method.lisp", """
				(defclass circle () ())

				(defgeneric area (s))

				(defmethod area ((s circle))
				  (error "no area for circle"))

				(print (area (make-instance 'circle)))
				""");
		assertSameReport(program, "Unhandled condition: no area for circle", "  at " + program + ":6 in AREA");
	}

	@Test
	void aNestedDefunIsANamedFunctionOnEveryBackend() throws Exception {
		Path program = write("nested.lisp", """
				(defun make-it ()
				  (defun made (x)
				    (error "made ~a" x)))

				(make-it)
				(made 3)
				""");
		assertSameReport(program, "Unhandled condition: made 3", "  at " + program + ":3 in MADE");
	}

	@Test
	void aStatementLetsInitAndAKeyDefaultReportTheirOwnForms() throws Exception {
		Path let = write("let.lisp", """
				(defun f ()
				  (let ((a 1))
				    (print a))
				  (let ((b (error "in init")))
				    b)
				  3)

				(f)
				""");
		assertSameReport(let, "Unhandled condition: in init", "  at " + let + ":4 in F");
		Path key = write("key.lisp", """
				(defun f (&key (x (error "x is required")))
				  x)

				(defun g ()
				  (f))

				(g)
				""");
		assertSameReport(key, "Unhandled condition: x is required", "  at " + key + ":1 in F");
	}

	@Test
	void anAsyncChainNamesEveryBoundaryAndItsAwait() throws Exception {
		Path chain = write("chain.lisp", """
				(rontolisp:async-defun a1 ()
				  (error "deepest"))

				(rontolisp:async-defun a2 ()
				  (rontolisp:await (a1)))

				(rontolisp:async-defun a3 ()
				  (list (rontolisp:await (a2))))

				(print (rontolisp:await (a3)))
				""");
		assertSameReport(chain, "Unhandled condition: deepest", "  at " + chain + ":2",
				"  in A1 (async), awaited at " + chain + ":5", "  in A2 (async), awaited at " + chain + ":8",
				"  in A3 (async), awaited at " + chain + ":10");
		Path lambda = write("async-lambda.lisp", """
				(defvar *job* (rontolisp:async-lambda ()
				               (error "lambda job failed")))

				(print (rontolisp:await (funcall *job*)))
				""");
		assertSameReport(lambda, "Unhandled condition: lambda job failed", "  at " + lambda + ":2",
				"  in an async lambda, awaited at " + lambda + ":4");
	}

	@Test
	void aSecondAwaitOfAFailedFutureKeepsTheFirstAwaitAsTheBoundarysSite() throws Exception {
		Path program = write("twice.lisp", """
				(rontolisp:async-defun job ()
				  (error "job failed"))

				(let ((f (job)))
				  (handler-case (rontolisp:await f)
				    (error () (print :caught)))
				  (rontolisp:await f))
				""");
		assertSameReport(program, "Unhandled condition: job failed", "  at " + program + ":2",
				"  in JOB (async), awaited at " + program + ":5");
	}

	@Test
	void aFusedArithmeticTreeReportsTheOperationThatFailedAndItsFunction() throws Exception {
		// Integer trees compile into one outlined _fx$N method (.kb/jvm-int-fusion.md):
		// a tree spanning lines, and a small defun inlined into its caller's tree -- here
		// into the top-level call, where F's own frame never exists -- still report the
		// failing operation's line and the function that wrote it.
		Path inlined = write("fused-inline.lisp", """
				(defun sq (x)
				  (* x x))

				(defun f (a)
				  (+ 1
				     (sq a)))

				(print (f "s"))
				""");
		assertSameReport(inlined, "Unhandled condition: *: The value \"s\" is not of type NUMBER",
				"  at " + inlined + ":2 in SQ");
		Path multiline = write("fused-lines.lisp", """
				(defun f (a b)
				  (+ (* a 2)
				     (* b 3)))

				(print (f 1 "x"))
				""");
		assertSameReport(multiline, "Unhandled condition: *: The value \"x\" is not of type NUMBER",
				"  at " + multiline + ":3 in F");
	}

	@Test
	void schemeSourceIsLocatedTheSameWay() throws Exception {
		// The Scheme reader locates its lists on both paths, and its procedures lower to
		// the same named functions -- a named let's loop and an internal define report
		// the procedure around them.
		Path program = write("scheme.scm", """
				(define (count-down n)
				  (define (check k)
				    (if (> k 5)
				        (error "too big" k)
				        k))
				  (let loop ((k (check n)))
				    (if (= k 0)
				        (error "bottom")
				        (loop (- k 1)))))

				(count-down 3)
				""");
		assertSameReport(program, "Unhandled condition: bottom", "  at " + program + ":8 in count-down");
	}

	@Test
	void aTypedLoopsOutOfRangeIndexReportsTheArefNotTheLoop() throws Exception {
		// A dotimes over a packed double array compiles to raw primitive code
		// (.kb/jvm-typed-loops.md): its array access still reports the aref's line.
		// The report lines differ -- the JVM backend's bounds failure is Java's own
		// message -- the location does not.
		Path program = write("typed-loop.lisp", """
				(defun sum-arr (v n)
				  (let ((s 0d0))
				    (dotimes (i n)
				      (setf s (+ s
				                 (aref v i))))
				    s))

				(print (sum-arr (make-array 3 :element-type 'double-float :initial-element 1d0) 5))
				""");
		assertSameLocation(program, "  at " + program + ":5 in SUM-ARR");
	}

	@Test
	void aBodySplitIntoContinuationsStillNamesItsFunction() throws Exception {
		// Enough statements that the tail-spine outliner moves the rest of BIG into a
		// _k$N continuation (.kb/hot-path-method-size.md) before the failing form.
		StringBuilder source = new StringBuilder("(defvar *n* 0)\n(defun big ()\n");
		int statements = 600;
		for (int i = 0; i < statements; i++) {
			source.append("  (setq *n* (+ *n* ").append(i).append("))\n");
		}
		source.append("  (error \"late: ~a\" *n*))\n\n(big)\n");
		Path program = write("big.lisp", source.toString());
		int failingLine = 3 + statements;
		Report compiled = assertSameReport(program, "Unhandled condition: late: " + (statements * (statements - 1) / 2),
				"  at " + program + ":" + failingLine + " in BIG");
		assertThat(compiled.exitCode()).isEqualTo(1);
		assertThat(methodNames(compiledClass(program))).anyMatch(name -> name.startsWith("_k$"));
	}

	@Test
	void aProgramWithNothingLocatedCompilesAsItAlwaysDid() throws Exception {
		// -e names no file: the report is the line alone on both backends, and the class
		// carries no line numbers, no site table and no report code.
		Path out = Files.createDirectories(this.tempDir.resolve("inline-out")).resolve("Inline.class");
		CliStack.call("compile", () -> {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()))
				.run(new String[] { "-e", "(defun f (x) (error \"no file: ~a\" x)) (f 1)", "-o", out.toString() });
			return null;
		});
		ClassModel model = ClassFile.of().parse(Files.readAllBytes(out));
		for (MethodModel method : model.methods()) {
			assertThat(method.code().flatMap(code -> code.findAttribute(Attributes.lineNumberTable())))
				.as(method.methodName().stringValue())
				.isEmpty();
		}
		assertThat(methodNames(Files.readAllBytes(out))).doesNotContain("_where", "_asyncCross", "_asyncAwaited");
		assertThat(run(out)).isEqualTo(new Report(1, List.of("Unhandled condition: no file: 1")));
	}

	private Report assertSameReport(Path program, String... expected) throws Exception {
		Report interpreted = interpret(program);
		assertThat(interpreted.exitCode()).isEqualTo(1);
		assertThat(interpreted.err()).containsExactly(expected);
		Report compiled = compileAndRun(program);
		assertThat(compiled).isEqualTo(interpreted);
		return compiled;
	}

	private void assertSameLocation(Path program, String... expectedLocationLines) throws Exception {
		Report interpreted = interpret(program);
		Report compiled = compileAndRun(program);
		assertThat(interpreted.err().subList(1, interpreted.err().size())).containsExactly(expectedLocationLines);
		assertThat(compiled.err().subList(1, compiled.err().size())).containsExactly(expectedLocationLines);
	}

	private Path write(String name, String source) throws Exception {
		Path file = this.tempDir.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, source);
		return file;
	}

	private void writeLibrary() throws Exception {
		write("lib/util.lisp", """
				(defun lib-parse (s)
				  (let ((n (parse-integer s :junk-allowed t)))
				    (if n
				        n
				        (error "lib-parse: not a number: ~a" s))))

				(defmacro with-checked ((var value) &body body)
				  `(let ((,var ,value))
				     (unless ,var
				       (error "with-checked: ~a is nil" ',var))
				     ,@body))
				""");
	}

	/** The interpreter, through the reporting wrapper the CLI's {@code main} uses. */
	private Report interpret(Path program) throws Exception {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int code;
		try (var _ = ThreadStdio.err(err)) {
			code = CliStack.call("interpret",
					() -> RontoLispCli.runReporting(
							new RontoLispCli(new ByteArrayInputStream(new byte[0]),
									new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)),
							new String[] { program.toString() }));
		}
		return new Report(code, err.toString(StandardCharsets.UTF_8).lines().toList());
	}

	private Path compiledClassFile(Path program) {
		String file = program.getFileName().toString();
		String stem = file.substring(0, file.lastIndexOf('.')).replace('-', '_');
		return this.tempDir.resolve("out-" + stem).resolve("P" + stem + ".class");
	}

	private byte[] compiledClass(Path program) throws Exception {
		return Files.readAllBytes(compiledClassFile(program));
	}

	/** Compiled through the CLI's {@code -o}, then run through its {@code main}. */
	private Report compileAndRun(Path program) throws Exception {
		Path out = compiledClassFile(program);
		Files.createDirectories(out.getParent());
		CliStack.call("compile", () -> {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()))
				.run(new String[] { program.toString(), "-o", out.toString() });
			return null;
		});
		return run(out);
	}

	private static Report run(Path classFile) throws Exception {
		String className = classFile.getFileName().toString().replace(".class", "");
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int code = 0;
		try (URLClassLoader loader = new URLClassLoader(
				new URL[] { java.util.Objects.requireNonNull(classFile.getParent()).toUri().toURL() },
				ClassLoader.getSystemClassLoader());
				var _ = ThreadStdio.err(err);
				var _ = ThreadStdio.out(new ByteArrayOutputStream())) {
			Method main = loader.loadClass(className).getMethod("main", String[].class);
			try {
				main.invoke(null, (Object) new String[0]);
			}
			catch (InvocationTargetException ex) {
				// The report's rethrow: what the launcher turns into exit code 1.
				code = 1;
			}
		}
		return new Report(code, err.toString(StandardCharsets.UTF_8).lines().toList());
	}

	private static List<String> methodNames(byte[] classFile) {
		return ClassFile.of().parse(classFile).methods().stream().map(m -> m.methodName().stringValue()).toList();
	}

}
