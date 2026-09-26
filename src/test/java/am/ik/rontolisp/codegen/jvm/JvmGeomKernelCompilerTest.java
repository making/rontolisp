package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.GeomLibrary;
import am.ik.rontolisp.eval.LibraryDefunPruner;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JVM backend's {@code geom:} kernels: the four call sites
 * {@link JvmGeomKernelCompiler} routes into the {@link JvmGeomTemplate} bridge shipped
 * beside the class, and the {@code geom.lisp} defuns they must agree with BIT FOR BIT.
 *
 * <p>
 * Two halves, and both are load-bearing. The first is the emit gate -- there is no flag
 * in front of this bridge, so the only thing keeping it out of a program that does not
 * need it is the call-site scan. The second is the oracle: every fixture is compiled
 * twice, once with the bridge and once with {@code geomKernels(false)}, run in the same
 * JVM and compared as PRINTED values, which render a packed array element for element.
 * That is {@code eval/GeomKernelsTest}'s shape for the interpreter ({@code .kb/geom.md},
 * "The interpreter's native kernels"); a kernel that rounds differently is a bug here,
 * not a tolerance.
 */
class JvmGeomKernelCompilerTest {

	@TempDir
	Path tempDir;

	private static List<LispVal> program(String lispCode) {
		// The compile path's own order: GeomLibrary inside LinalgLibrary, then the
		// pruner, so the scan sees exactly the defuns the program can reach.
		return LibraryDefunPruner
			.prune(LinalgLibrary.process(GeomLibrary.process(LispReader.readAllFromString(lispCode))));
	}

	/** A compiled program: the class and the files that travel beside it. */
	private record Compiled(byte[] bytes, Map<String, byte[]> beside) {
	}

	private static Compiled compile(String lispCode, boolean kernels) {
		JvmLispCompiler compiler = JvmLispCompiler.builder()
			.className("Test")
			.optimize(OptimizeLevel.NONE)
			.geomKernels(kernels)
			.build();
		byte[] bytes = compiler.compile(program(lispCode));
		return new Compiled(bytes, compiler.runtimeClassFiles());
	}

	private String run(Compiled compiled) throws Exception {
		return run(compiled, true, new java.util.HashSet<>());
	}

	// Runs the class with (or without) the files that travel beside it, recording every
	// class the loader found on disk, so an accelerated run can prove its bridge loaded:
	// a bridge that cannot load degrades to the defuns in silence, which every oracle
	// comparison here would still pass.
	private String run(Compiled compiled, boolean withBeside, java.util.Set<String> loaded) throws Exception {
		Path dir = Files.createTempDirectory(this.tempDir, "cls");
		Files.write(dir.resolve("Test.class"), compiled.bytes());
		if (withBeside) {
			for (Map.Entry<String, byte[]> file : compiled.beside().entrySet()) {
				Path target = dir.resolve(file.getKey());
				Files.createDirectories(target.getParent());
				Files.write(target, file.getValue());
			}
		}
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				Class<?> found = super.findClass(name);
				loaded.add(name);
				return found;
			}
		}) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString(StandardCharsets.UTF_8).trim();
		}
	}

	private static final String BRIDGE = JvmGeomRuntimeBuilder.bridgeName("Test");

	// The bridge travels as its own class file beside the program -- never as bytes the
	// program defines at run time, which a GraalVM native image refuses with an Error
	// the LinkageError degrade does not catch -- and the class names it.
	private static boolean embedsGeomBridge(Compiled compiled) {
		boolean named = new String(compiled.bytes(), StandardCharsets.ISO_8859_1).contains(BRIDGE);
		boolean shipped = compiled.beside().containsKey(BRIDGE + ".class");
		assertThat(shipped).as("the class names the bridge exactly when it ships beside it").isEqualTo(named);
		return shipped;
	}

	/**
	 * The whole point: the accelerated program and the defun-only program print the same
	 * bytes. Asserted together with the gate, so a dead bridge cannot pass -- every
	 * numeric assertion here would still hold on the defuns alone.
	 */
	private void assertMatchesTheDefunsAlone(String lispCode) throws Exception {
		Compiled accelerated = compile(lispCode, true);
		Compiled defunsAlone = compile(lispCode, false);
		assertThat(embedsGeomBridge(accelerated)).as("the bridge travels: %s", lispCode).isTrue();
		assertThat(embedsGeomBridge(defunsAlone)).as("the oracle carries no bridge: %s", lispCode).isFalse();
		java.util.Set<String> loaded = new java.util.HashSet<>();
		assertThat(run(accelerated, true, loaded)).as(lispCode).isEqualTo(run(defunsAlone));
		assertThat(loaded).as("the accelerated run loaded its bridge: %s", lispCode).contains(BRIDGE);
	}

	// --- the emit gate ---------------------------------------------------------------

	@Test
	void theBridgeTravelsOnlyForAProgramThatCallsOneOfTheFourKernels() {
		// A program with no geom: at all, and one that splices geom but reaches none of
		// the four -- vec3 is arithmetic over a packed array and nothing else.
		assertThat(embedsGeomBridge(compile("(print (+ 1 2))", true))).isFalse();
		assertThat(embedsGeomBridge(compile("(print (geom:vec3 1 2 3))", true))).isFalse();
		assertThat(embedsGeomBridge(
				compile("(print (geom:transform-point (geom:make-transform) (geom:vec3 1 2 3)))", true)))
			.isFalse();
		// And the four that do arm it: geom:bounds through geom::%vertex-extremes (which
		// only a program that measures a pose reaches now that LibraryDefunPruner walks
		// a class header by position -- (geom:vec3 ...) above is the counter-case), mesh
		// directly, mesh through volume, wireframe and the reader.
		assertThat(embedsGeomBridge(compile("(print (geom:bounds (geom:box 10)))", true))).isTrue();
		assertThat(embedsGeomBridge(compile("(print (length (geom:mesh (geom:box 10))))", true))).isTrue();
		assertThat(embedsGeomBridge(compile("(print (geom:volume (geom:box 10)))", true))).isTrue();
		assertThat(embedsGeomBridge(compile("(print (length (geom:wireframe (geom:box 10))))", true))).isTrue();
		assertThat(embedsGeomBridge(compile("(print (geom:read-obj \"m.obj\"))", true))).isTrue();
	}

	@Test
	void aProgramWithNoGeomKernelIsEmittedByteForByteAsBefore() {
		assertThat(compile("(print (+ 1 2))", true).bytes()).isEqualTo(compile("(print (+ 1 2))", false).bytes());
		assertThat(compile("(defstruct pt x y) (print (pt-x (make-pt :x 1 :y 2)))", true).bytes())
			.isEqualTo(compile("(defstruct pt x y) (print (pt-x (make-pt :x 1 :y 2)))", false).bytes());
	}

	@Test
	void theClassDefinesNothingAtRunTime() {
		Compiled accelerated = compile("(print (geom:volume (geom:box 10)))", true);
		assertThat(new String(accelerated.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("defineClass")
			.doesNotContain("java/util/Base64");
	}

	@Test
	void aClassCopiedWithoutItsBridgeRunsOnTheDefuns() throws Exception {
		// No flag stands in front of this bridge, so it must never break a program: a
		// bridge that cannot load (left behind, or too new a class version for the JRE)
		// is a LinkageError _geomInit swallows, and the defuns answer.
		String source = "(print (geom:volume (geom:box 10)))";
		java.util.Set<String> loaded = new java.util.HashSet<>();
		assertThat(run(compile(source, true), false, loaded)).isEqualTo(run(compile(source, false)));
		assertThat(loaded).doesNotContain(BRIDGE);
	}

	// --- the oracle ------------------------------------------------------------------

	@Test
	void theMeshKernelAnswersTheDefunsMeshForEveryPrimitive() throws Exception {
		assertMatchesTheDefunsAlone("""
				(dolist (s (list (geom:box 10) (geom:box '(3 5 7))
				                 (geom:cylinder :radius 5 :height 12 :sides 17)
				                 (geom:cone :radius 4 :height 9 :sides 13)
				                 (geom:sphere :radius 6 :sides 11 :stacks 7)
				                 (geom:torus :radius 8 :tube 3 :sides 9 :rings 5)))
				  (princ (geom:mesh s))
				  (terpri)
				  (princ (list (geom:volume s) (geom:surface-area s) (geom:centroid s)))
				  (terpri))
				""");
	}

	@Test
	void theWireframeKernelAnswersTheDefunsSegmentsInTheSameOrder() throws Exception {
		assertMatchesTheDefunsAlone("""
				(dolist (s (list (geom:box 10) (geom:cylinder :radius 5 :height 12 :sides 9)
				                 (geom:sphere :radius 6 :sides 8 :stacks 5)))
				  (princ (geom:wireframe s))
				  (terpri))
				""");
	}

	@Test
	void theExtremesKernelAnswersTheDefunsBoundsUnderEveryPose() throws Exception {
		assertMatchesTheDefunsAlone("""
				(let ((s (geom:box '(3 5 7))))
				  ;; the mesh call is what arms the bridge; the bounds below it are what
				  ;; the extremes kernel answers.
				  (princ (length (geom:mesh s)))
				  (terpri)
				  (princ (list (geom:lower-of (geom:bounds s)) (geom:upper-of (geom:bounds s))))
				  (terpri)
				  (princ (geom::%model-extent s))
				  (terpri)
				  (geom:translate s (geom:vec3 1.5 -2.25 0.125))
				  (geom:rotate s 0.7 :z)
				  (geom:rotate s -0.31 :x)
				  (princ (list (geom:lower-of (geom:bounds s)) (geom:upper-of (geom:bounds s))))
				  (terpri)
				  (princ (geom::%model-extent s))
				  (terpri))
				""");
	}

	@Test
	void theReadObjKernelAnswersTheDefunsSolidIncludingItsColourAndLabel() throws Exception {
		Path model = this.tempDir.resolve("cube.obj");
		Files.writeString(model, """
				# a cube, with a comment, a normal record and 1-based faces carrying texture
				# indices -- plus one negative (relative) index and a four-sided facet.
				v -1.0 -1.0 -1.0
				v 1.0 -1.0 -1.0
				v 1.0 1.0 -1.0
				v -1.0 1.0 -1.0
				v -1.0 -1.0 1.0e0
				v 1.0 -1.0 1.
				v 1.25e0 1.5 1.0
				v -1.0 1.0 +1.0
				vn 0 0 1
				vt 0 0
				g cube
				f 1/1 4/1 3/1 2/1
				f 5/1/1 6/1/1 7/1/1 8/1/1
				f 1//1 2//1 6//1 5//1
				f -6 -5 -1 -2
				f 3 4 8 7
				f 4 1 5 8
				""");
		String path = model.toString().replace("\\", "\\\\");
		assertMatchesTheDefunsAlone("""
				(let ((s (geom:read-obj "%s" :label "cube" :color (geom:vec3 0.1 0.2 0.3))))
				  (princ (list (geom:label-of s) (geom:color-of s)))
				  (terpri)
				  (princ (geom:vertices-of s))
				  (terpri)
				  (princ (geom:facets-of s))
				  (terpri)
				  (princ (geom:mesh s))
				  (terpri)
				  (princ (list (geom:volume s) (geom:surface-area s)))
				  (terpri)
				  (princ s)
				  (terpri))
				""".formatted(path));
	}

	@Test
	void aReadObjWithNoKeywordTailAnswersTheSameSolid() throws Exception {
		Path model = this.tempDir.resolve("tri.obj");
		Files.writeString(model, """
				v 0 0 0
				v 1 0 0
				v 0 1 0
				f 1 2 3
				""");
		String path = model.toString().replace("\\", "\\\\");
		assertMatchesTheDefunsAlone("""
				(let ((s (geom:read-obj "%s")))
				  (princ (list (geom:color-of s) (geom:label-of s)))
				  (terpri)
				  (princ (geom:vertices-of s))
				  (terpri)
				  (princ (geom:facets-of s))
				  (terpri))
				""".formatted(path));
	}

	// --- what the bridge declines ----------------------------------------------------

	@Test
	void aMissingFileFallsThroughToTheDefunsOwnError() throws Exception {
		// The kernel cannot open it, so it answers null and the defun runs -- and the
		// error the caller sees is the one the defun has always signalled.
		String source = """
				(princ (handler-case (geom:read-obj "no-such-model-file.obj")
				         (error (e) (princ-to-string e))))
				""";
		String accelerated = run(compile(source, true));
		assertThat(accelerated).isEqualTo(run(compile(source, false)));
		assertThat(accelerated).contains("no-such-model-file.obj");
	}

	@Test
	void anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines() throws Exception {
		// The temps are what make the fallback safe: recompiling the argument form would
		// run its side effects twice. The path here is a nonexistent file, so the kernel
		// declines and the defun runs over the SAME temp.
		String source = """
				(defvar *calls* 0)
				(defun the-path () (setq *calls* (+ *calls* 1)) "no-such-model-file.obj")
				(princ (handler-case (geom:read-obj (the-path)) (error (e) "signalled")))
				(terpri)
				(princ *calls*)
				""";
		assertThat(run(compile(source, true))).isEqualTo("signalled\n1");
	}

	@Test
	void aMeshOfSomethingThatIsNotASolidSignalsWhatTheDefunSignals() throws Exception {
		String source = """
				(princ (handler-case (geom:mesh 42) (error (e) "signalled")))
				""";
		assertThat(run(compile(source, true))).isEqualTo(run(compile(source, false)));
	}

	@Test
	void theCachedMeshAndWireAreAnsweredOnASecondCallJustAsTheDefunAnswersThem() throws Exception {
		assertMatchesTheDefunsAlone("""
				(let ((s (geom:cylinder :radius 3 :height 4 :sides 7)))
				  (princ (eq (geom:mesh s) (geom:mesh s)))
				  (princ (eq (geom:wireframe s) (geom:wireframe s)))
				  (terpri)
				  (princ (geom:mesh s))
				  (terpri))
				""");
	}

}
