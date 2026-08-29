package am.ik.rontolisp.eval;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import am.ik.objc.ObjcRuntime;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code scene} RENDERER, checked by looking at the pixels it produces.
 *
 * <p>
 * No test may open a window ({@code .kb/objc.md}), which left the camera, the projection,
 * the per-solid model matrix, the winding convention and the depth test -- arithmetic
 * that breaks silently and is obvious in a picture -- with no automated coverage at all.
 * {@code scene:offscreen} closes that: it is the SAME {@code scene::%render} over a
 * {@code metal:offscreen} context, so what is asserted here is what a window draws, not a
 * second path that resembles it.
 *
 * <p>
 * macOS-gated, like {@code ObjcNativeImageForeignConfigTest} and the {@code os: [mac]}
 * rows of {@code examples.yaml}; skipped where there is no Objective-C runtime or no
 * Metal device. Every frame is also written to {@code target/scene-frames/} as a PNG, and
 * every assertion names its file: a render that is wrong is a picture to look at rather
 * than a byte count to guess from.
 */
@EnabledOnOs(OS.MAC)
class SceneOffscreenRenderTest {

	private static final int WIDTH = 160;

	private static final int HEIGHT = 120;

	/**
	 * The viewer's default background, as {@code metal} writes it into a BGRA8 texture.
	 */
	private static final int[] BACKGROUND = { 14, 17, 23 };

	private static final Path FRAMES = Path.of("target", "scene-frames");

	@BeforeAll
	static void metalIsReachable() {
		assumeTrue(ObjcRuntime.available(), ObjcRuntime.description());
		try {
			new LispEvaluator(new PrintStream(new ByteArrayOutputStream()))
				.eval(LispReader.readAllFromString("(metal:offscreen :width 8 :height 8)").get(0));
		}
		catch (RuntimeException ex) {
			assumeTrue(false, "no usable Metal device here: " + ex.getMessage());
		}
	}

	// --- what a picture makes obvious and a number does not ----------------------

	@Test
	void aRedBoxAtTheOriginIsRedInTheMiddleAndBackgroundInTheCorners() {
		Frame frame = render("box", """
				(defvar *v* (scene:offscreen :width %d :height %d))
				(scene:grid *v* :extent nil)
				(scene:axes *v* nil)
				(scene:shading *v* :solid)
				(scene:add *v* (geom:box '(200 200 200) :color (geom:vec3 1.0 0.2 0.2)))
				(scene:camera *v* :azimuth 0.9 :elevation 0.45 :distance 700.0)
				(scene:snapshot *v*)
				""");
		assertThat(frame.isRed(WIDTH / 2, HEIGHT / 2)).as("%s: the middle of the frame", frame).isTrue();
		for (int[] corner : new int[][] { { 0, 0 }, { WIDTH - 1, 0 }, { 0, HEIGHT - 1 }, { WIDTH - 1, HEIGHT - 1 } }) {
			assertThat(frame.isBackground(corner[0], corner[1])).as("%s: the corner %d,%d", frame, corner[0], corner[1])
				.isTrue();
		}
	}

	@Test
	void aSolidBehindAnotherIsOccluded() {
		// The camera is on +x looking back at the origin, so the blue box is
		// squarely behind the red one; the red one is added FIRST, so without a
		// working depth attachment the blue draw would overwrite it.
		String scene = """
				(defvar *v* (scene:offscreen :width %d :height %d))
				(scene:grid *v* :extent nil)
				(scene:axes *v* nil)
				(scene:shading *v* :solid)
				(scene:camera *v* :azimuth 0.0 :elevation 0.0 :distance 700.0)
				""";
		Frame far = render("occlusion-far-only", scene + """
				(scene:add *v* (geom:box '(140 140 140) :color (geom:vec3 0.15 0.3 1.0)))
				(geom:move (car (scene:contents *v*)) (geom:vec3 -320 0 0))
				(scene:snapshot *v*)
				""");
		assertThat(far.isBlue(WIDTH / 2, HEIGHT / 2)).as("%s: the far box alone is what is seen", far).isTrue();

		Frame both = render("occlusion", scene + """
				(defvar *near* (geom:box '(140 140 140) :color (geom:vec3 1.0 0.15 0.15)))
				(defvar *far* (geom:box '(140 140 140) :color (geom:vec3 0.15 0.3 1.0)))
				(geom:move *far* (geom:vec3 -320 0 0))
				(scene:add *v* *near* *far*)
				(scene:snapshot *v*)
				""");
		assertThat(both.isRed(WIDTH / 2, HEIGHT / 2)).as("%s: the near box hides the far one", both).isTrue();
	}

	@Test
	void aFacetWoundInsideOutIsCulled() {
		// One quad in the y-z plane with the camera on +x: wound counter-clockwise
		// seen from OUTSIDE -- geom's convention, the one geom:volume's divergence
		// integral rests on -- it faces the camera and draws; reversed, back-face
		// culling removes it and the background is all that is left.
		String scene = """
				(defvar *v* (scene:offscreen :width %d :height %d))
				(scene:grid *v* :extent nil)
				(scene:axes *v* nil)
				(scene:shading *v* :solid)
				(scene:camera *v* :azimuth 0.0 :elevation 0.0 :distance 400.0)
				(scene:add *v* (geom:polyhedron '%s '((0 1 2 3)) :color (geom:vec3 1.0 0.2 0.2)))
				(scene:snapshot *v*)
				""";
		String outward = "((0 60 -60) (0 60 60) (0 -60 60) (0 -60 -60))";
		String inward = "((0 -60 -60) (0 -60 60) (0 60 60) (0 60 -60))";

		Frame front = render("winding-outward", scene.formatted(WIDTH, HEIGHT, outward));
		assertThat(front.isRed(WIDTH / 2, HEIGHT / 2)).as("%s: an outward facet is drawn", front).isTrue();

		Frame back = render("winding-inside-out", scene.formatted(WIDTH, HEIGHT, inward));
		assertThat(back.isBackground(WIDTH / 2, HEIGHT / 2)).as("%s: an inside-out facet is culled", back).isTrue();
	}

	@Test
	void fitPutsTheWholeSolidInsideTheFrameFromEveryAngle() {
		String scene = """
				(defvar *v* (scene:offscreen :width %d :height %d))
				(scene:grid *v* :extent nil)
				(scene:axes *v* nil)
				(scene:shading *v* :solid)
				(defvar *b* (geom:box '(200 260 180) :color (geom:vec3 1.0 0.2 0.2)))
				(geom:move *b* (geom:vec3 150 -80 60))
				(scene:add *v* *b*)
				(scene:camera *v* :azimuth %s :elevation %s)
				(scene:fit *v*)
				(scene:snapshot *v*)
				""";
		for (String[] angles : new String[][] { { "0.0", "0.0" }, { "0.9", "0.45" }, { "2.4", "-0.8" },
				{ "-1.7", "1.2" } }) {
			Frame frame = render("fit-" + angles[0] + "-" + angles[1],
					scene.formatted(WIDTH, HEIGHT, angles[0], angles[1]));
			assertThat(frame.redPixels()).as("%s: the solid is somewhere in the frame", frame).isPositive();
			assertThat(frame.redOnTheBorder()).as("%s: no part of the solid is cut off by the frame", frame).isZero();
		}
	}

	@Test
	void theSameSceneRendersTheSameBytesTwice() {
		// A frame is arithmetic over float32 and one draw call per solid, so it has
		// no business varying -- the way an emitted module has none
		// (.kb/emitted-output-determinism.md). A second viewer built from scratch
		// must agree with a second frame of the first one.
		String scene = """
				(defvar *v* (scene:offscreen :width %d :height %d))
				(scene:add *v* (geom:cylinder :radius 70 :height 180 :sides 24
				                              :color (geom:vec3 0.4 0.8 0.5)))
				(scene:fit *v*)
				""";
		Frame first = render("determinism-first", scene + "(scene:snapshot *v*)\n");
		Frame again = render("determinism-again", scene + "(scene:snapshot *v*)\n(scene:snapshot *v*)\n");
		assertThat(again.pixels).as("a second frame of the same scene").isEqualTo(first.pixels);
	}

	// --- driving the offscreen renderer ------------------------------------------

	private Frame render(String name, String program) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal answer = LispNil.INSTANCE;
		for (LispVal form : LispReader
			.readAllFromString(program.contains("%d") ? program.formatted(WIDTH, HEIGHT) : program)) {
			answer = evaluator.eval(form);
		}
		assertThat(answer).as("scene:snapshot answered a packed byte vector").isInstanceOf(LispIntVector.class);
		LispIntVector vector = (LispIntVector) answer;
		assertThat(vector.length()).as("width * height * 4 bytes of BGRA").isEqualTo(WIDTH * HEIGHT * 4);
		Frame frame = new Frame(name, vector.data());
		frame.write();
		return frame;
	}

	/**
	 * One rendered frame: the texture's own bytes (BGRA, row 0 at the top), the colour
	 * tests the assertions are written in, and the PNG that says what went wrong.
	 */
	private record Frame(String name, long[] pixels) {

		int red(int x, int y) {
			return (int) this.pixels[4 * (y * WIDTH + x) + 2];
		}

		int green(int x, int y) {
			return (int) this.pixels[4 * (y * WIDTH + x) + 1];
		}

		int blue(int x, int y) {
			return (int) this.pixels[4 * (y * WIDTH + x)];
		}

		boolean isRed(int x, int y) {
			return red(x, y) > 90 && red(x, y) > 2 * green(x, y) && red(x, y) > 2 * blue(x, y);
		}

		boolean isBlue(int x, int y) {
			return blue(x, y) > 90 && blue(x, y) > 2 * red(x, y);
		}

		boolean isBackground(int x, int y) {
			return Math.abs(red(x, y) - BACKGROUND[0]) <= 2 && Math.abs(green(x, y) - BACKGROUND[1]) <= 2
					&& Math.abs(blue(x, y) - BACKGROUND[2]) <= 2;
		}

		int redPixels() {
			int count = 0;
			for (int y = 0; y < HEIGHT; y++) {
				for (int x = 0; x < WIDTH; x++) {
					if (isRed(x, y)) {
						count++;
					}
				}
			}
			return count;
		}

		int redOnTheBorder() {
			int count = 0;
			for (int x = 0; x < WIDTH; x++) {
				count += (isRed(x, 0) ? 1 : 0) + (isRed(x, HEIGHT - 1) ? 1 : 0);
			}
			for (int y = 0; y < HEIGHT; y++) {
				count += (isRed(0, y) ? 1 : 0) + (isRed(WIDTH - 1, y) ? 1 : 0);
			}
			return count;
		}

		void write() {
			BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < HEIGHT; y++) {
				for (int x = 0; x < WIDTH; x++) {
					image.setRGB(x, y, (red(x, y) << 16) | (green(x, y) << 8) | blue(x, y));
				}
			}
			try {
				Files.createDirectories(FRAMES);
				ImageIO.write(image, "png", FRAMES.resolve(this.name + ".png").toFile());
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		@Override
		public String toString() {
			return FRAMES.resolve(this.name + ".png").toString();
		}
	}

}
