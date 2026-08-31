package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeomKernels} against the {@code geom.lisp} defuns it replaces. The natives are
 * ALWAYS on ({@code .kb/geom.md}, "The interpreter's native readers"), and the whole
 * licence for that is that they answer what the defuns answer -- so every case here runs
 * one expression twice, once with the natives installed and once with
 * {@code setGeomKernels(false)}, and compares the PRINTED value. Printing a packed
 * {@code single-float} array renders every element, so an equal string is element-for-
 * element equality and a one-ULP drift fails.
 *
 * <p>
 * The cases are chosen for the seams a transcription gets wrong: exponent syntax, a
 * negative (relative) face index, {@code v/vt/vn} tokens, a facet with more than three
 * vertices (the fan), a mesh whose normals are Newell's over a non-planar loop, the
 * wireframe's de-duplication ORDER, and a posed solid's bounds (the matmul the extremes
 * walk replaced).
 */
class GeomKernelsTest {

	/**
	 * The value of {@code input} with the natives installed and with them suppressed. Two
	 * evaluators: the install happens once per evaluator, on the geom load.
	 */
	private void bothPathsAgree(String input) {
		assertThat(print(input, true)).as(input).isEqualTo(print(input, false));
	}

	private String print(String input, boolean kernels) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setGeomKernels(kernels);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

	private String textFile(String name, String content) {
		try {
			Path path = Files.createDirectories(Path.of("target", "geom-kernels")).resolve(name);
			Files.writeString(path, content);
			return "\"" + path.toString().replace("\\", "\\\\") + "\"";
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// --- the natives are actually installed ------------------------------------------

	@Test
	void theNativesAreInstalledOverTheDefunsAndNotQuietlyDeclining() {
		// A decline is invisible in a value comparison -- every case below would pass
		// with the natives never firing. The two paths are told apart by the FUNCTION
		// value: the native is a built-in, the defun a lambda.
		String probe = "(let ((s (geom:box 2))) (geom:mesh s) (princ-to-string #'geom:read-obj))";
		assertThat(print(probe, true)).contains("#<function GEOM:READ-OBJ");
		assertThat(print(probe, false)).doesNotContain("#<function GEOM:READ-OBJ");
	}

	// --- geom:read-obj ---------------------------------------------------------------

	@Test
	void aReadObjSolidIsTheSameSolidTheDefunReads() {
		String file = textFile("cube.obj", """
				# a comment, then a blank line

				mtllib nothing.mtl
				o cube
				v -5.0 -5.0 -5.0
				v 5.0 -5.0 -5.0
				v 5.0 5.0 -5.0
				v -5.0 5.0 -5.0
				v -5.0 -5.0 5.0
				v 5.0 -5.0 5.0
				v 5.0 5.0 5.0
				v -5.0 5.0 5.0
				vn 0.0 0.0 1.0
				vt 0.0 0.0
				usemtl none
				f 1 4 3 2
				f 5 6 7 8
				f 1 2 6 5
				f 2 3 7 6
				f 3 4 8 7
				f 4 1 5 8
				""");
		bothPathsAgree("""
				(let ((s (geom:read-obj %s :label "cube")))
				  (list (geom:vertices-of s) (geom:facets-of s) (geom:label-of s)
				        (geom:color-of s) (geom:volume s) (geom:surface-area s)))
				""".formatted(file));
	}

	@Test
	void theExponentSyntaxScansToTheSameFloats() {
		// The whole reason the reader scans with char-code rather than borrowing the
		// Lisp reader, and the step where a transcription is most likely to round
		// differently: the mantissa is accumulated across the decimal point and scaled
		// ONCE by (expt 10.0 (- (* es e) frac)).
		String file = textFile("exponents.obj", """
				v -3.4101800e-003 1.3031957E+002 2.1754370e2
				v +0.5 .25 1.
				v 1e10 -1E-10 12345678.90123
				f 1 2 3
				""");
		bothPathsAgree("(geom:vertices-of (geom:read-obj %s))".formatted(file));
	}

	@Test
	void faceTokenShapesAndRelativeIndicesAgree() {
		// v, v/vt, v/vt/vn and v//vn are one token shape, and a negative index counts
		// back from the vertices seen SO FAR -- so the index arithmetic depends on the
		// running count, which the native keeps itself.
		String file = textFile("tokens.obj", """
				v 0 0 0
				v 10 0 0
				v 0 10 0
				v 10 10 0
				f 1/1 2/1/2 3//2
				f -4 -3 -1
				f 1 2 4 3
				f 1 2
				""");
		bothPathsAgree("(geom:facets-of (geom:read-obj %s))".formatted(file));
	}

	@Test
	void aFileThatIsNotThereSignalsTheDefunSError() {
		// The native declines rather than inventing a message, so the condition is the
		// one with-open-file has always signalled.
		bothPathsAgree("""
				(handler-case (geom:read-obj "target/geom-kernels/absent.obj")
				  (error (e) (princ-to-string e)))
				""");
	}

	@Test
	void aVertexLineShortOfThreeNumbersSignalsTheDefunSError() {
		String file = textFile("short.obj", """
				v 1 2
				f 1 1 1
				""");
		bothPathsAgree("""
				(handler-case (geom:read-obj %s) (error (e) (princ-to-string e)))
				""".formatted(file));
	}

	// --- geom:mesh and geom:wireframe ------------------------------------------------

	@Test
	void theMeshOfEveryPrimitiveIsTheSameFloatArray() {
		// Newell's normal over a quad, a triangle fan and a non-planar loop, all at once:
		// the primitives between them cover every facet arity the library builds.
		for (String solid : List.of("(geom:box '(100 200 300))", "(geom:cylinder :radius 50 :height 100 :sides 64)",
				"(geom:sphere :radius 50 :sides 32 :stacks 24)", "(geom:torus :radius 60 :tube 20 :sides 48 :rings 24)",
				"(geom:cone :radius 50 :height 120 :sides 64)")) {
			bothPathsAgree("(geom:mesh %s)".formatted(solid));
			bothPathsAgree("(geom:wireframe %s)".formatted(solid));
		}
	}

	@Test
	void theMeshIsCachedOnTheSolidAndAnsweredAgainUnchanged() {
		bothPathsAgree("""
				(let ((s (geom:sphere :radius 3 :sides 8 :stacks 6)))
				  (list (eq (geom:mesh s) (geom:mesh s)) (eq (geom:wireframe s) (geom:wireframe s))
				        (geom:mesh-triangle-count s) (length (geom:wireframe s))))
				""");
	}

	@Test
	void aDegenerateFacetNormalTakesTheSameEpsilonFloor() {
		// %unit divides by 1e-9 rather than by the norm when the loop has no area, so a
		// zero-area facet is where the two paths would part company first.
		bothPathsAgree("""
				(geom:mesh (geom:polyhedron '((0 0 0) (1 1 1) (2 2 2) (3 3 3))
				                            '((0 1 2) (1 2 3) (0 1 2 3))))
				""");
	}

	// --- geom::%vertex-extremes ------------------------------------------------------

	@Test
	void theBoundsOfAPosedSolidAreTheSameCorners() {
		// The extremes walk replaced a linalg:matmul over every vertex; a solid under a
		// rotation is what tells a transcribed accumulation from a reassociated one.
		bothPathsAgree("""
				(let ((s (geom:cylinder :radius 12.5 :height 40 :sides 33)))
				  (geom:rotate s 0.7 (geom:vec3 1 2 3))
				  (geom:translate s (geom:vec3 1.5 -2.25 30))
				  (let ((b (geom:bounds s)))
				    (list (geom:lower-of b) (geom:upper-of b) (geom:bounds-center b)
				          (geom:bounds-extent b))))
				""");
	}

	@Test
	void theModelExtentIgnoresThePoseTheWayTheColumnFoldsDid() {
		// scene: sizes a body's axis triad by this, and it must not move when the body
		// does -- the property the amin/amax pair it replaced had for free.
		bothPathsAgree("""
				(let ((s (geom:torus :radius 60 :tube 20 :sides 12 :rings 8)))
				  (let ((before (geom::%model-extent s)))
				    (geom:rotate s 1.1 (geom:vec3 0 1 0))
				    (geom:translate s (geom:vec3 100 100 100))
				    (list before (geom::%model-extent s)
				          (linalg:norm (linalg:sub (linalg:amax (geom:vertices-of s) :axis 0)
				                                   (linalg:amin (geom:vertices-of s) :axis 0))))))
				""");
	}

}
