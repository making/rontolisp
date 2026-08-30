package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped {@code scene} library as a library: it parses, its public names are exactly
 * the ones the package registry exports, the compile path splices it TOGETHER WITH
 * everything it stands on (geom, metal, linalg, appkit) and the WASM backends refuse a
 * program that reaches it. Nothing here opens a window (CI has no display); the visible
 * behavior is {@code examples/macos/scene-*}.
 */
class SceneLibraryTest {

	private static Set<String> externalNames(List<LispVal> forms) {
		Set<String> out = new TreeSet<>();
		for (LispVal form : forms) {
			collect(form, out);
		}
		return out;
	}

	private static void collect(LispVal form, Set<String> out) {
		switch (form) {
			case LispSymbol sym -> {
				String name = sym.name();
				if (name.startsWith("SCENE:") && !name.startsWith("SCENE::")) {
					out.add(name.substring("SCENE:".length()));
				}
			}
			case LispCons cons -> {
				collect(cons.car(), out);
				collect(cons.cdr(), out);
			}
			default -> {
			}
		}
	}

	@Test
	void theLibrarysPublicNamesAreExactlyWhatThePackageExports() {
		assertThat(externalNames(SceneLibrary.forms()))
			.containsExactlyInAnyOrderElementsOf(PackageRegistry.sceneFunctionNames());
	}

	@Test
	void qualifiedNamesAreRecognized() {
		assertThat(SceneLibrary.isSceneQualified("SCENE:VIEWER")).isTrue();
		assertThat(SceneLibrary.isSceneQualified("SCENE::%RENDER")).isTrue();
		assertThat(SceneLibrary.isSceneQualified("METAL:ATTACH")).isFalse();
		assertThat(SceneLibrary.isSceneQualified("VIEWER")).isFalse();
	}

	@Test
	void theCompilePathSplicesTheLibraryExactlyWhenSceneIsReferenced() {
		List<LispVal> unrelated = read("(print 1)");
		assertThat(SceneLibrary.process(unrelated)).isSameAs(unrelated);
		assertThat(SceneLibrary.process(read("(metal:attach w)"))).hasSize(1);
		assertThat(SceneLibrary.process(read("(scene:viewer)"))).hasSize(SceneLibrary.forms().size() + 1);
		assertThat(SceneLibrary.process(read("(in-package scene) (viewer)"))).hasSize(SceneLibrary.forms().size() + 2);
	}

	@Test
	void aSceneProgramPullsInEverythingItStandsOn(@TempDir Path dir) throws IOException {
		// The order of the splice chain is the point: scene's bodies reference geom:,
		// metal:, linalg: and appkit:, and each of those passes runs AFTER scene's, so
		// the reference it introduces is seen.
		Path source = dir.resolve("view.lisp");
		Files.writeString(source, "(scene:add (scene:viewer) (geom:box 10))\n");
		Path prog = dir.resolve("Prog.class");
		compile(source, "-o", prog.toString());
		String bytes = Files.readString(prog, StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains("SCENE$colonVIEWER")
			.contains("METAL$colonATTACH")
			.contains("GEOM$colonBOX")
			.contains("APPKIT$colonWINDOW")
			.contains("RontoLispObjcBridge");
	}

	@Test
	void theCompilePathRefusesSceneOnTheWasmBackendsNamingThePackage(@TempDir Path dir) throws IOException {
		Path source = dir.resolve("view.lisp");
		Files.writeString(source, "(scene:viewer :title \"hi\")\n");
		for (String[] output : new String[][] { { "-o", dir.resolve("prog.wasm").toString() },
				{ "-o", dir.resolve("comp.wasm").toString(), "--component" } }) {
			assertThatThrownBy(() -> compile(source, output)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot compile: SCENE:VIEWER")
				.hasMessageContaining("not in a .wasm");
		}
	}

	// --- what a viewer's contents may hold ---------------------------------------
	//
	// A viewer needs a window and a Metal device, but its CONTENTS need neither: the
	// state class is a plain CLOS instance whose contents slot has an initform, so
	// scene:add and scene:drop can be driven anywhere. The library loads on the first
	// scene:-qualified FUNCTION resolution, which is why each program asks for one
	// before it names the class.

	private static final String VIEWER = "(defvar *v* (progn #'scene:add (make-instance 'scene:viewer-state)))\n";

	@Test
	void addSplicesAListSoATriadGoesInAsOneArgument() {
		// geom:triad answers three solids where geom:box answers one. Handed the list
		// itself, scene:add used to cons it in whole and the report arrived a frame
		// later, from inside the draw callback, as a geom:user-data dispatch failure on
		// a CONS -- naming nothing the caller wrote (.kb/geom.md).
		assertThat(eval(VIEWER + """
				(scene:add *v* (geom:triad))
				(length (scene:contents *v*))
				""")).isEqualTo("3");
		// A solid, a list and a solid compose the same way, in order.
		assertThat(eval(VIEWER + """
				(scene:add *v* (geom:box 10) (geom:triad) (geom:sphere :radius 5))
				(mapcar #'geom:label-of (scene:contents *v*))
				""")).isEqualTo("(NIL \"x\" \"y\" \"z\" NIL)");
		// nil is the empty list and adds nothing.
		assertThat(eval(VIEWER + """
				(scene:add *v* nil)
				(scene:contents *v*)
				""")).isEqualTo("NIL");
		// The answer is still the last solid that went in.
		assertThat(eval(VIEWER + "(geom:label-of (scene:add *v* (geom:triad)))")).isEqualTo("\"z\"");
	}

	@Test
	void addRefusesANonSolidNamingItAndLeavesTheViewerAsItWas() {
		assertThatThrownBy(() -> eval(VIEWER + "(scene:add *v* (geom:box 10) 5)")).hasMessageContaining("scene:add")
			.hasMessageContaining("5")
			.hasMessageContaining("not a geom:solid");
		// Nothing was added: the check runs over every argument before the first one is
		// consed in, so a refused call leaves no half-filled viewer behind.
		assertThat(eval(VIEWER + """
				(ignore-errors (scene:add *v* (geom:box 10) 5))
				(scene:contents *v*)
				""")).isEqualTo("NIL");
	}

	@Test
	void dropTakesTheShapeAddTakes() {
		// What went in as one argument comes back out as one argument; scene:clear
		// names no solid at all and therefore needs no equivalent.
		assertThat(eval(VIEWER + """
				(defvar *t* (geom:triad))
				(scene:add *v* (geom:box 10) *t*)
				(scene:drop *v* *t*)
				(length (scene:contents *v*))
				""")).isEqualTo("1");
		assertThatThrownBy(() -> eval(VIEWER + "(scene:drop *v* \"x\")")).hasMessageContaining("scene:drop")
			.hasMessageContaining("not a geom:solid");
	}

	// --- picking -----------------------------------------------------------------
	//
	// scene:ray is camera arithmetic and nothing else, so it needs no window and no
	// Metal device either: a viewer-state with a width, a height and a target is
	// enough. The gesture that reaches it -- a press released without travelling --
	// is an NSEvent and stays uncovered here, exactly as %view-point does.

	private static final String CAMERA = """
			(defvar *v* (progn #'scene:ray
			                   (make-instance 'scene:viewer-state
			                                  :width 800.0 :height 600.0
			                                  :target (geom:vec3 0 0 0))))
			""";

	@Test
	void aRayThroughTheCentreOfTheViewLandsOnTheOrbitTarget() {
		// The centre pixel looks straight down the view direction, so the plane
		// through the target answers the target itself -- which is what makes
		// "click where you see" true from any camera angle.
		assertThat(eval(CAMERA + """
				(let ((p (scene::%click-point *v* 400.0 300.0)))
				  (list (round (aref p 0)) (round (aref p 1)) (round (aref p 2))))
				""")).isEqualTo("(0 0 0)");
		// The ray starts at the eye and points into the scene.
		assertThat(eval(CAMERA + """
				(let ((r (scene:ray *v* 400.0 300.0)))
				  (list (eq (first r) (scene::%eye *v*))
				        (round (* 100 (linalg:dot (second r)
				                                  (linalg:row (scene::%basis *v*) 2))))))
				""")).isEqualTo("(T 100)");
	}

	@Test
	void aClickRightOfCentreLandsRightOfTheTargetOnTheSamePlane() {
		// Right of centre is +right in the camera's own basis, and the point stays
		// on the plane through the target -- its component along the view direction
		// is zero however far off-centre the pixel is.
		assertThat(eval(CAMERA + """
				(let* ((p (scene::%click-point *v* 700.0 480.0))
				       (b (scene::%basis *v*)))
				  (list (> (linalg:dot p (linalg:row b 0)) 0.0)
				        (> (linalg:dot p (linalg:row b 1)) 0.0)
				        (< (abs (linalg:dot p (linalg:row b 2))) 1.0)))
				""")).isEqualTo("(T T T)");
	}

	@Test
	void onClickInstallsTheHookAndNilRemovesIt() {
		assertThat(eval(CAMERA + """
				(scene:on-click *v* (lambda (p) p))
				(list (functionp (scene::%click-hook *v*))
				      (progn (scene:on-click *v* nil) (scene::%click-hook *v*)))
				""")).isEqualTo("(T NIL)");
	}

	private static String eval(String program) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal answer = LispNil.INSTANCE;
		for (LispVal form : read(program)) {
			answer = evaluator.eval(form);
		}
		return answer.print();
	}

	private static void compile(Path source, String... options) {
		List<String> args = new ArrayList<>();
		args.add(source.toString());
		args.addAll(List.of(options));
		new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()))
			.run(args.toArray(String[]::new));
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

}
