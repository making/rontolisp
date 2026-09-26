package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.CliStackExtension;
import am.ik.rontolisp.testsupport.SplitPrograms;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A program whose constant pool outgrows one class file comes out as its class plus
 * {@code $PartN} classes ({@code .kb/jvm-method-size-limits.md}). One program here is
 * large enough for real ({@link SplitPrograms}); the others are forced to split by a
 * small class-pool limit and must behave exactly as the same program in one class. The
 * output shapes that carry the parts are {@code RontoLispCliTest}'s.
 */
@ExtendWith(CliStackExtension.class)
class JvmLispCompilerSplitTest {

	@TempDir
	Path tempDir;

	@Test
	void aProgramPastOneClassesPoolRunsAsItsClassAndItsParts() throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("Big");
		byte[] main = compiler.compile(LispReader.readAllFromString(SplitPrograms.pastOneClass()));
		Map<String, byte[]> parts = compiler.runtimeClassFiles();
		assertThat(parts).containsKey("Big$Part1.class");
		assertThat(poolCount(main)).isLessThanOrEqualTo(65534);
		parts.values().forEach(part -> assertThat(poolCount(part)).isLessThanOrEqualTo(65534));
		assertThat(run("Big", main, parts)).isEqualTo(SplitPrograms.pastOneClassOutput());

		// The same program compiles to the same classes, parts included
		// (.kb/emitted-output-determinism.md).
		JvmLispCompiler again = new JvmLispCompiler("Big");
		assertThat(again.compile(LispReader.readAllFromString(SplitPrograms.pastOneClass()))).isEqualTo(main);
		assertThat(again.runtimeClassFiles()).containsOnlyKeys(parts.keySet());
		parts.forEach((name, bytes) -> assertThat(again.runtimeClassFiles().get(name)).as(name).isEqualTo(bytes));
	}

	// The language's moving parts, each of which reaches across methods in its own way --
	// closures and their dispatch, a non-local exit, a handler, a dynamic binding, a
	// generic function, a struct, eval over a read form, apply through a designator --
	// forced into classes of a few thousand entries: the split must be invisible.
	private static final String FEATURES = """
			(defstruct point x y)
			(defclass shape () ((name :initarg :name :reader shape-name)))
			(defgeneric area (s))
			(defclass square (shape) ((side :initarg :side)))
			(defmethod area ((s square)) (* (slot-value s 'side) (slot-value s 'side)))
			(defvar *depth* 0)
			(defun depth-now () *depth*)
			(defun make-counter ()
			  (let ((n 0)) (lambda () (incf n))))
			(defun find-first (pred items)
			  (block found
			    (dolist (x items) (when (funcall pred x) (return-from found x)))
			    nil))
			(defun risky (x)
			  (handler-case (if (> x 2) (error "too big: ~a" x) (* x 10))
			    (error (e) (format nil "caught ~a" e))))
			(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
			(let ((c (make-counter)))
			  (funcall c) (funcall c)
			  (print (funcall c)))
			(print (find-first #'evenp '(1 3 4 5 6)))
			(print (risky 1))
			(print (risky 5))
			(print (let ((*depth* 3)) (depth-now)))
			(print (depth-now))
			(print (area (make-instance 'square :name "sq" :side 7)))
			(print (point-x (make-point :x 11 :y 12)))
			(print (eval (read-from-string "(+ 1 2 3)")))
			(print (apply #'+ 1 2 '(3 4)))
			(print (mapcar #'fib '(10 20)))
			(print (catch 'done (throw 'done :thrown)))
			(print (multiple-value-list (floor 17 5)))
			(let ((h (make-hash-table :test 'equal)))
			  (setf (gethash "k" h) 42)
			  (print (gethash "k" h)))
			(print (expt 2 100))
			(print (unwind-protect (+ 1 1) (print :cleanup)))
			""";

	@Test
	void aForcedSplitRunsExactlyAsTheWholeClass() throws Exception {
		for (OptimizeLevel optimize : new OptimizeLevel[] { OptimizeLevel.DEFAULT, OptimizeLevel.NONE }) {
			JvmLispCompiler whole = JvmLispCompiler.builder().className("Features").optimize(optimize).build();
			byte[] wholeMain = whole.compile(program(FEATURES));
			assertThat(whole.runtimeClassFiles()).noneSatisfy((name, bytes) -> assertThat(name).contains("$Part"));
			String expected = run("Features", wholeMain, whole.runtimeClassFiles());

			JvmLispCompiler split = JvmLispCompiler.builder()
				.className("Features")
				.optimize(optimize)
				.classPoolLimit(3000)
				.build();
			byte[] splitMain = split.compile(program(FEATURES));
			assertThat(split.runtimeClassFiles()).as("%s", optimize).containsKey("Features$Part1.class");
			assertThat(run("Features", splitMain, split.runtimeClassFiles())).as("%s", optimize).isEqualTo(expected);
		}
	}

	// Direct java: calls are private methods of the class, the helpers they share too,
	// the bridge finds _apply by name, and a generated interface implementation calls
	// its program-side callback from its own class: a forced split must leave every one
	// reachable.
	@Test
	void aForcedSplitKeepsJavaCallsWorking() throws Exception {
		String source = FEATURES + """
				(let ((lst (java:new "java.util.ArrayList")))
				  (java:call lst "add" 2)
				  (java:call lst "add" 1)
				  (java:static "java.util.Collections" "sort" lst
				    (java:reify "java.util.Comparator" "compare" (lambda (a b) (- a b))))
				  (print (java:call lst "toString")))
				(defun describe-point (p)
				  (declare (type (java:object "java.awt.Point") p))
				  (list (java:field p "x") (java:call p "getY")))
				(print (describe-point (java:new "java.awt.Point" 3 4)))
				(print (java:static "java.lang.Math" "max" 3 7))
				(print (java:call (java:call (java:new "java.lang.StringBuilder" "ab") "reverse") "toString"))
				(print (java:call (java:call (java:new "java.lang.StringBuilder" "ab") "chars") "toArray"))
				(java:call (java:static "java.util.List" "of" 1 2) "forEach" (lambda (m x) (print x)))
				(defun len (x) (java:call x "length"))
				(print (len (java:new "java.lang.StringBuilder" "abc")))
				""";
		JvmLispCompiler whole = JvmLispCompiler.builder().className("Features").build();
		String expected = run("Features", whole.compile(program(source)), whole.runtimeClassFiles());
		assertThat(expected).contains("\"[1, 2]\"").endsWith("(3 4.0)\n7\n\"ba\"\n(97 98)\n1\n2\n3");
		JvmLispCompiler split = JvmLispCompiler.builder().className("Features").classPoolLimit(3000).build();
		byte[] splitMain = split.compile(program(source));
		assertThat(split.runtimeClassFiles()).containsKey("Features$Part1.class");
		assertThat(run("Features", splitMain, split.runtimeClassFiles())).isEqualTo(expected);
	}

	// A library's Java API is its class: the typed wrappers and the defuns behind them
	// stay where a Java caller looks them up, however the rest is spread.
	@Test
	void anExportedLibraryKeepsItsJavaApiInItsClass() throws Exception {
		JvmLispCompiler compiler = JvmLispCompiler.builder()
			.className("Kernels")
			.noMain(true)
			.classPoolLimit(3000)
			.build();
		byte[] main = compiler.compile(program(FEATURES.replaceAll("\\(print ", "(identity ") + """
				(defun scale (x) (* x (fib 10)))
				(rontolisp:jvm-export 'scale :params '(:s64) :returns :s64 :as "scale")
				"""));
		assertThat(compiler.runtimeClassFiles()).containsKey("Kernels$Part1.class");
		Class<?> kernels = load("Kernels", main, compiler.runtimeClassFiles());
		Method scale = kernels.getMethod("scale", long.class);
		assertThat(Modifier.isPublic(scale.getModifiers())).isTrue();
		assertThat(scale.invoke(null, 2L)).isEqualTo(110L);
		assertThat(Arrays.stream(kernels.getDeclaredMethods()).map(Method::getName))
			.as("the untyped defun an export wraps stays beside it")
			.contains("SCALE");
	}

	private static List<LispVal> program(String source) {
		return am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(source));
	}

	private Class<?> load(String className, byte[] main, Map<String, byte[]> travelling) throws Exception {
		Path dir = Files.createTempDirectory(this.tempDir, className);
		Files.write(dir.resolve(className + ".class"), main);
		for (Map.Entry<String, byte[]> entry : travelling.entrySet()) {
			Path target = dir.resolve(entry.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, entry.getValue());
		}
		// Leaks deliberately: the class is used after this returns.
		URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		return loader.loadClass(className);
	}

	private String run(String className, byte[] main, Map<String, byte[]> travelling) throws Exception {
		Class<?> clazz = load(className, main, travelling);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.out(out)) {
			clazz.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
		}
		return out.toString().trim();
	}

	private static int poolCount(byte[] classFile) {
		return ((classFile[8] & 0xFF) << 8 | (classFile[9] & 0xFF)) - 1;
	}

}
