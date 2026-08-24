package am.ik.rontolisp.codegen.jvm;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.runtime.RontoFloatArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code rontolisp:jvm-export} directive: typed, Java-callable wrapper methods on the
 * compiled class ({@code .kb/jvm-export.md}). Every class here is compiled at the default
 * optimize level, so the wrappers double as the tree-shaker roots they are documented to
 * be, and {@code main} is never invoked — the top level must have run in {@code <clinit>}
 * by the time the first typed call arrives.
 */
class JvmExportTest {

	@TempDir
	Path tempDir;

	private Class<?> compileToClass(String lispCode) throws Exception {
		return compileToClass(lispCode, false);
	}

	private Class<?> compileToClass(String lispCode, boolean noMain) throws Exception {
		List<LispVal> program = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString(lispCode));
		byte[] classBytes = new JvmLispCompiler("ExportTest", false, OptimizeLevel.DEFAULT).noMain(noMain)
			.compile(program);
		Path classFile = this.tempDir.resolve("ExportTest.class");
		Files.write(classFile, classBytes);
		// The loader leaks deliberately: the loaded class is used after this method
		// returns. One class per @Test tempDir, so nothing collides.
		URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		return loader.loadClass("ExportTest");
	}

	@Test
	void signedIntegerDesignatorsRoundTripAsByteShortIntLong() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun echo (x) x)
				(rontolisp:jvm-export 'echo :params '(:s8) :returns :s8 :as "echoS8")
				(rontolisp:jvm-export 'echo :params '(:s16) :returns :s16 :as "echoS16")
				(rontolisp:jvm-export 'echo :params '(:s32) :returns :s32 :as "echoS32")
				(rontolisp:jvm-export 'echo :params '(:s64) :returns :s64 :as "echoS64")
				""");
		assertThat(clazz.getMethod("echoS8", byte.class).invoke(null, (byte) -128)).isEqualTo((byte) -128);
		assertThat(clazz.getMethod("echoS16", short.class).invoke(null, (short) -32768)).isEqualTo((short) -32768);
		assertThat(clazz.getMethod("echoS32", int.class).invoke(null, Integer.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
		assertThat(clazz.getMethod("echoS64", long.class).invoke(null, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void unsignedIntegerDesignatorsRoundTripTheirFullDeclaredRange() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun echo (x) x)
				(rontolisp:jvm-export 'echo :params '(:u8) :returns :u8 :as "echoU8")
				(rontolisp:jvm-export 'echo :params '(:u16) :returns :u16 :as "echoU16")
				(rontolisp:jvm-export 'echo :params '(:u32) :returns :u32 :as "echoU32")
				(rontolisp:jvm-export 'echo :params '(:u64) :returns :u64 :as "echoU64")
				""");
		assertThat(clazz.getMethod("echoU8", int.class).invoke(null, 255)).isEqualTo(255);
		assertThat(clazz.getMethod("echoU16", int.class).invoke(null, 65535)).isEqualTo(65535);
		assertThat(clazz.getMethod("echoU32", long.class).invoke(null, 4294967295L)).isEqualTo(4294967295L);
		assertThat(clazz.getMethod("echoU64", long.class).invoke(null, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void anUnsignedArgumentOutsideItsDeclaredRangeThrowsInsteadOfMasking() throws Exception {
		// The boundary carries the value exactly, or it throws — wasm-export's
		// trapping-not-masking rule, verbatim.
		Class<?> clazz = compileToClass("""
				(defun echo (x) x)
				(rontolisp:jvm-export 'echo :params '(:u8) :returns :u8 :as "echoU8")
				(rontolisp:jvm-export 'echo :params '(:u64) :returns :u64 :as "echoU64")
				""");
		assertThatThrownBy(() -> clazz.getMethod("echoU8", int.class).invoke(null, 300))
			.hasCauseInstanceOf(IllegalArgumentException.class)
			.cause()
			.hasMessage("rontolisp:jvm-export echoU8 argument 1 (:u8) cannot carry the value exactly: 300");
		assertThatThrownBy(() -> clazz.getMethod("echoU64", long.class).invoke(null, -1L))
			.hasCauseInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aResultTheDeclaredTypeCannotStateThrowsInsteadOfWrapping() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun big (x) (+ x 1000))
				(rontolisp:jvm-export 'big :params '(:s32) :returns :u8 :as "bigU8")
				(rontolisp:jvm-export 'big :params '(:s32) :returns :s32 :as "bigS32")
				""");
		assertThatThrownBy(() -> clazz.getMethod("bigU8", int.class).invoke(null, 0))
			.hasCauseInstanceOf(ArithmeticException.class)
			.cause()
			.hasMessage("rontolisp:jvm-export bigU8 result (:u8) cannot carry the value exactly: 1000");
		assertThatThrownBy(() -> clazz.getMethod("bigS32", int.class).invoke(null, Integer.MAX_VALUE))
			.hasCauseInstanceOf(ArithmeticException.class);
		assertThat(clazz.getMethod("bigS32", int.class).invoke(null, 42)).isEqualTo(1042);
	}

	@Test
	void aFloatExportReadsADefvarInitializedWithoutMainHavingRun() throws Exception {
		// The typed call is the FIRST entry into the class: the _top$N chunks (the
		// defvar initialization) must have run in <clinit>, or *scale* reads null.
		Class<?> clazz = compileToClass("""
				(defvar *scale* 2.0)
				(defun scaled-sum (a b) (* *scale* (+ a b)))
				(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)
				""");
		assertThat(clazz.getMethod("scaledSum", double.class, double.class).invoke(null, 2.5, 3.5)).isEqualTo(12.0);
	}

	@Test
	void aBoolExportCrossesAsJavaBoolean() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun negate (b) (not b))
				(rontolisp:jvm-export 'negate :params '(:bool) :returns :bool)
				""");
		assertThat(clazz.getMethod("negate", boolean.class).invoke(null, false)).isEqualTo(true);
		assertThat(clazz.getMethod("negate", boolean.class).invoke(null, true)).isEqualTo(false);
	}

	@Test
	void aStringExportFramesOnTheWayInAndUnframesOnTheWayOut() throws Exception {
		// The silent-failure case the typed boundary exists for: a string stores its
		// frame quotes, so the untyped GREET("ron") answered "hello, o" — the r and n
		// were read as the frame. The wrapper must answer "hello, ron".
		Class<?> clazz = compileToClass("""
				(defun greet (name) (concatenate 'string "hello, " name))
				(rontolisp:jvm-export 'greet :params '(:string) :returns :string)
				""");
		assertThat(clazz.getMethod("greet", String.class).invoke(null, "ron")).isEqualTo("hello, ron");
	}

	@Test
	void aStringResultThatIsNotAStringThrowsInsteadOfLeakingTheRepresentation() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun answer (s) 42)
				(rontolisp:jvm-export 'answer :params '(:string) :returns :string)
				""");
		assertThatThrownBy(() -> clazz.getMethod("answer", String.class).invoke(null, "x"))
			.hasCauseInstanceOf(ClassCastException.class)
			.cause()
			.hasMessageContaining("did not return a string");
	}

	@Test
	void anSExprExportCrossesAsPrintedText() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun swap (p) (list (cadr p) (car p)))
				(rontolisp:jvm-export 'swap :params '(:s-expr) :returns :s-expr)
				""");
		assertThat(clazz.getMethod("swap", String.class).invoke(null, "(1 2)")).isEqualTo("(2 1)");
	}

	@Test
	void aBytesExportRoundTripsRawOctets() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun second-half (v)
				  (let* ((n (length v))
				         (h (floor n 2))
				         (out (make-array (- n h) :element-type '(unsigned-byte 8))))
				    (dotimes (i (- n h))
				      (setf (aref out i) (aref v (+ h i))))
				    out))
				(rontolisp:jvm-export 'second-half :params '(:bytes) :returns :bytes)
				""");
		byte[] out = (byte[]) clazz.getMethod("secondHalf", byte[].class)
			.invoke(null, (Object) new byte[] { 1, 2, (byte) 0xFE, (byte) 0xFF });
		assertThat(out).containsExactly((byte) 0xFE, (byte) 0xFF);
	}

	@Test
	void aVoidExportDiscardsTheResultAndItsEffectIsObservable() throws Exception {
		Class<?> clazz = compileToClass("""
				(defvar *last* 0)
				(defun remember (x) (setq *last* x))
				(defun recall () *last*)
				(rontolisp:jvm-export 'remember :params '(:s32))
				(rontolisp:jvm-export 'recall :returns :s32)
				""");
		Method remember = clazz.getMethod("remember", int.class);
		assertThat(remember.getReturnType()).isEqualTo(void.class);
		remember.invoke(null, 7);
		assertThat(clazz.getMethod("recall").invoke(null)).isEqualTo(7);
	}

	@Test
	void anExportedDefunSurvivesTheDefaultOptimizeWhileAnUnexportedOneIsShaken() throws Exception {
		// The whole reason the directive earns its keep: main is the only shaker root a
		// program has, so a library's defuns are dead code without export roots.
		Class<?> clazz = compileToClass("""
				(defun keep-me (x) (* x 2))
				(defun drop-me (x) (* x 3))
				(rontolisp:jvm-export 'keep-me :params '(:s64) :returns :s64)
				""");
		assertThat(clazz.getMethod("keepMe", long.class).invoke(null, 21L)).isEqualTo(42L);
		List<String> methodNames = Arrays.stream(clazz.getDeclaredMethods()).map(Method::getName).toList();
		assertThat(methodNames).contains("KEEP-ME").doesNotContain("DROP-ME");
	}

	@Test
	void anAsThatCollidesWithAMangledDefunNameIsRejected() {
		// NORM2 the wrapper + NORM2 the defun method would be two same-named methods in
		// one class — the redefined-defun failure shape, refused at compile time.
		assertThatThrownBy(() -> compileToClass("""
				(defun norm2 (x) x)
				(rontolisp:jvm-export 'norm2 :params '(:float) :returns :float :as "NORM2")
				""")).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("collides with the method name of a defun");
	}

	@Test
	void aDuplicateExportNameIsRejected() {
		assertThatThrownBy(() -> compileToClass("""
				(defun f (x) x)
				(defun g (x) x)
				(rontolisp:jvm-export 'f :params '(:s32) :returns :s32 :as "same")
				(rontolisp:jvm-export 'g :params '(:s32) :returns :s32 :as "same")
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("declared twice");
	}

	@Test
	void anUnknownFunctionAndAnArityMismatchAreRejected() {
		assertThatThrownBy(() -> compileToClass("""
				(rontolisp:jvm-export 'nope :params '(:s32) :returns :s32)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("unknown function");
		assertThatThrownBy(() -> compileToClass("""
				(defun two (a b) a)
				(rontolisp:jvm-export 'two :params '(:s32) :returns :s32)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("arity mismatch");
	}

	@Test
	void aLambdaListWithOptionalRestOrKeyIsRefusedNotMisWrapped() {
		assertThatThrownBy(() -> compileToClass("""
				(defun opt (a &optional b) a)
				(rontolisp:jvm-export 'opt :params '(:s32) :returns :s32)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("&optional/&rest/&key");
	}

	@Test
	void aNameTheDefaultDerivationCannotMakeJavaLegalRequiresAs() {
		assertThatThrownBy(() -> compileToClass("""
				(defun *twice* (x) x)
				(rontolisp:jvm-export '*twice* :params '(:s32) :returns :s32)
				""")).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining(":as");
	}

	@Test
	void noMainOmitsTheMainMethodAndTheExportsStillWork() throws Exception {
		Class<?> clazz = compileToClass("""
				(defvar *base* 40)
				(defun add-base (x) (+ *base* x))
				(rontolisp:jvm-export 'add-base :params '(:s32) :returns :s32)
				""", true);
		assertThat(clazz.getMethod("addBase", int.class).invoke(null, 2)).isEqualTo(42);
		assertThatThrownBy(() -> clazz.getMethod("main", String[].class)).isInstanceOf(NoSuchMethodException.class);
	}

	@Test
	void noMainWithoutAnyExportIsRefusedByName() {
		assertThatThrownBy(() -> compileToClass("(defun f (x) x)", true))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--no-main")
			.hasMessageContaining("rontolisp:jvm-export");
	}

	@Test
	void mainStillRunsTheTopLevelExactlyOnceOnAnExportCarryingClass() throws Exception {
		// A program may want both a main and exports (a CLI tool that is also a
		// library). main triggers <clinit>, which runs the top level; main itself has
		// nothing left to do — the effect must appear exactly once.
		Class<?> clazz = compileToClass("""
				(defvar *count* 0)
				(setq *count* (+ *count* 1))
				(defun count-now () *count*)
				(rontolisp:jvm-export 'count-now :returns :s32 :as "countNow")
				""");
		clazz.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
		assertThat(clazz.getMethod("countNow").invoke(null)).isEqualTo(1);
	}

	@Test
	void aFloatVectorCrossesAsTheHandleWithNoCopyInEitherDirection() throws Exception {
		// The measurement that shaped this designator: a per-call copy is ~10x the
		// kernel, so the boundary must hand over the packed array the handle already
		// holds. Identity is how "no copy" is pinned.
		Class<?> clazz = compileToClass("""
				(defun echo (v) v)
				(rontolisp:jvm-export 'echo :params '(:float-vector) :returns :float-vector)
				""");
		RontoFloatArray in = RontoFloatArray.of(new double[] { 3.0, 4.0 });
		Method echo = clazz.getMethod("echo", RontoFloatArray.class);
		Object out = echo.invoke(null, in);
		assertThat(out).isInstanceOf(RontoFloatArray.class);
		assertThat(((RontoFloatArray) out).packed()).isSameAs(in.packed());
	}

	@Test
	void aHandleHeldAcrossCallsCopiesOnce() throws Exception {
		// n calls cost one copy, not n: of() copies into the packed representation, and
		// every crossing after that hands over that very array.
		Class<?> clazz = compileToClass("""
				(defun bump (v)
				  (dotimes (i (length v) v) (setf (aref v i) (+ 1.0d0 (aref v i)))))
				(rontolisp:jvm-export 'bump :params '(:float-vector) :returns :float-vector)
				""");
		Method bump = clazz.getMethod("bump", RontoFloatArray.class);
		RontoFloatArray handle = RontoFloatArray.of(new double[] { 0.0, 10.0 });
		Object packed = handle.packed();
		for (int i = 0; i < 5; i++) {
			assertThat(((RontoFloatArray) bump.invoke(null, handle)).packed()).isSameAs(packed);
		}
		// The Lisp side wrote into the caller's own storage, five times over.
		assertThat(handle.toArray()).containsExactly(5.0, 15.0);
		assertThat(handle.packed()).isSameAs(packed);
	}

	@Test
	void bothPackedWidthsCrossTheSameDesignator() throws Exception {
		// double[] and float[] are disjoint representations; the designator names the
		// packed float array, not one of its widths.
		Class<?> clazz = compileToClass("""
				(defun total (v)
				  (let ((acc 0.0d0))
				    (dotimes (i (length v) acc) (setq acc (+ acc (aref v i))))))
				(rontolisp:jvm-export 'total :params '(:float-vector) :returns :float)
				""");
		Method total = clazz.getMethod("total", RontoFloatArray.class);
		assertThat(total.invoke(null, RontoFloatArray.of(new double[] { 1.5, 2.5 }))).isEqualTo(4.0);
		assertThat(total.invoke(null, RontoFloatArray.of(new float[] { 1.5f, 2.5f }))).isEqualTo(4.0);
	}

	@Test
	void aFloatMatrixIsTheSameHandleAtRankTwo() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun cell (m i j) (aref m i j))
				(defun transpose (m)
				  (let* ((rows (first (array-dimensions m)))
				         (cols (second (array-dimensions m)))
				         (out (make-array (list cols rows) :element-type 'double-float)))
				    (dotimes (i rows out)
				      (dotimes (j cols)
				        (setf (aref out j i) (aref m i j))))))
				(rontolisp:jvm-export 'cell :params '(:float-matrix :s32 :s32) :returns :float)
				(rontolisp:jvm-export 'transpose :params '(:float-matrix) :returns :float-matrix)
				""");
		RontoFloatArray matrix = RontoFloatArray.of(new double[] { 1, 2, 3, 4, 5, 6 }, 2, 3);
		assertThat(clazz.getMethod("cell", RontoFloatArray.class, int.class, int.class).invoke(null, matrix, 1, 2))
			.isEqualTo(6.0);
		Object transposed = clazz.getMethod("transpose", RontoFloatArray.class).invoke(null, matrix);
		assertThat(((RontoFloatArray) transposed).dims()).containsExactly(3, 2);
		assertThat(((RontoFloatArray) transposed).toArray()).containsExactly(1, 4, 2, 5, 3, 6);
	}

	@Test
	void aRankTheDesignatorDoesNotDeclareThrowsAtTheBoundary() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun echo (v) v)
				(rontolisp:jvm-export 'echo :params '(:float-vector) :returns :float-vector)
				(rontolisp:jvm-export 'echo :params '(:float-vector) :returns :float-matrix :as "echoAsMatrix")
				""");
		RontoFloatArray matrix = RontoFloatArray.of(new double[] { 1, 2, 3, 4 }, 2, 2);
		assertThatThrownBy(() -> clazz.getMethod("echo", RontoFloatArray.class).invoke(null, matrix))
			.hasCauseInstanceOf(IllegalArgumentException.class)
			.cause()
			.hasMessageContaining("argument 1 (:float-vector) expects rank 1, got rank 2");
		RontoFloatArray vector = RontoFloatArray.of(new double[] { 1, 2 });
		assertThatThrownBy(() -> clazz.getMethod("echoAsMatrix", RontoFloatArray.class).invoke(null, vector))
			.hasCauseInstanceOf(ClassCastException.class)
			.cause()
			.hasMessageContaining("result (:float-matrix) expects rank 2, got rank 1");
	}

	@Test
	void aResultThatIsNotAPackedFloatArrayThrowsClassCastException() throws Exception {
		Class<?> clazz = compileToClass("""
				(defun not-a-vector () 42)
				(rontolisp:jvm-export 'not-a-vector :returns :float-vector :as "notAVector")
				""");
		assertThatThrownBy(() -> clazz.getMethod("notAVector").invoke(null))
			.hasCauseInstanceOf(ClassCastException.class)
			.cause()
			.hasMessageContaining("not a packed float array");
	}

	@Test
	void theHandleClassFilesTravelOnlyWithALibraryThatDeclaresOne() throws Exception {
		// The artifact stays dependency-free: the handle's class files are written beside
		// the program's own class, at their canonical names so two libraries agree on the
		// type (.kb/jvm-export.md, "Where the handle type comes from").
		List<LispVal> withHandle = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(defun echo (v) v)
				(rontolisp:jvm-export 'echo :params '(:float-vector) :returns :float-vector)
				"""));
		JvmLispCompiler compiler = new JvmLispCompiler("ExportTest", false, OptimizeLevel.DEFAULT);
		compiler.compile(withHandle);
		assertThat(compiler.runtimeClassFiles().keySet()).containsExactlyInAnyOrder(
				"am/ik/rontolisp/runtime/RontoBoundary.class", "am/ik/rontolisp/runtime/RontoFloatArray.class",
				"am/ik/rontolisp/runtime/RontoFloatArray$Width.class");
		for (byte[] classFile : compiler.runtimeClassFiles().values()) {
			assertThat(classFile).isNotEmpty();
		}
		List<LispVal> withoutHandle = am.ik.rontolisp.eval.LispPreludeLibrary.process(LispReader.readAllFromString("""
				(defun echo (x) x)
				(rontolisp:jvm-export 'echo :params '(:s32) :returns :s32)
				"""));
		JvmLispCompiler plain = new JvmLispCompiler("ExportTest", false, OptimizeLevel.DEFAULT);
		plain.compile(withoutHandle);
		assertThat(plain.runtimeClassFiles()).isEmpty();
	}

	@Test
	void theTravellingClassListIsEveryClassFileOfTheRuntimePackage() throws Exception {
		// Nothing can enumerate a package from a classpath, still less from inside a
		// native image, so the list is hand-kept — and pinned here against the package's
		// actual class files. package-info carries only the build's nullness annotation
		// and deliberately stays behind.
		Path packageDir = Path.of("target/classes/am/ik/rontolisp/runtime");
		try (java.util.stream.Stream<Path> files = Files.list(packageDir)) {
			List<String> onDisk = files.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".class"))
				.filter(name -> !name.equals("package-info.class"))
				.sorted()
				.toList();
			assertThat(JvmExportRuntimeBuilder.RUNTIME_CLASS_FILES.stream()
				.map(path -> path.substring(path.lastIndexOf('/') + 1))
				.sorted()
				.toList()).isEqualTo(onDisk);
		}
	}

	@Test
	void aTopLevelThatSignalsSurfacesAsExceptionInInitializerErrorOnTheFirstTypedCall() throws Exception {
		// The reactor's failure shape: the top level runs at instantiation, so a
		// top-level condition poisons the class instead of arriving per call.
		Class<?> clazz = compileToClass("""
				(defun f (x) x)
				(rontolisp:jvm-export 'f :params '(:s32) :returns :s32)
				(error "boom at load")
				""");
		assertThatThrownBy(() -> clazz.getMethod("f", int.class).invoke(null, 1))
			.isInstanceOf(ExceptionInInitializerError.class);
	}

}
