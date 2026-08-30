package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * The {@code geom} package ({@code geom.lisp}, spliced/loaded by {@link GeomLibrary}):
 * the numbers, the transform algebra, the scene graph and the cached mesh. This is the
 * interpreter half of the oracle -- the cross-backend half is the {@code geom} cases of
 * {@code ci-spec.yaml}, which run the same shapes on the JVM and both WASM backends
 * ({@code .kb/geom.md}).
 *
 * <p>
 * Every closed form below is approached FROM BELOW, as a polyhedral approximation must: a
 * flat-faceted cylinder is inscribed in the smooth one. A tolerance is therefore
 * one-sided in spirit, and the volumes double as a winding check -- the divergence
 * theorem subtracts a facet wound the wrong way, so a mis-wound primitive answers a
 * grossly wrong number rather than a slightly small one.
 */
class GeomLibraryTest {

	private static final double PI = 3.141592653589793;

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private double number(String input) {
		return Double.parseDouble(eval(input).print());
	}

	/**
	 * The three components of a {@code geom:vec3}-shaped answer, read off the packed
	 * single-float printed form {@code #f(x y z)}.
	 */
	private double[] vector(String input) {
		String printed = eval(input).print();
		assertThat(printed).as(input).startsWith("#f(");
		String[] parts = printed.substring(printed.indexOf('(') + 1, printed.lastIndexOf(')')).trim().split("\\s+");
		return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
				Double.parseDouble(parts[2]) };
	}

	// --- the library is reachable with nothing required --------------------------

	@Test
	void aGeomCallOnABareEvaluatorLoadsTheLibrary() {
		assertThat(eval("(geom:mesh-triangle-count (geom:box 1))").print()).isEqualTo("12");
	}

	@Test
	void theCompilePathSplicesTheLibraryOnlyWhenTheProgramUsesIt() {
		List<LispVal> unrelated = LispReader.readAllFromString("(print (+ 1 2))");
		assertThat(GeomLibrary.process(unrelated)).isSameAs(unrelated);
		List<LispVal> user = LispReader.readAllFromString("(print (geom:volume (geom:box 2)))");
		List<LispVal> spliced = GeomLibrary.process(user);
		assertThat(spliced).hasSizeGreaterThan(user.size());
		assertThat(definitionNames(spliced)).contains("GEOM:BOX", "GEOM:VOLUME");
	}

	@Test
	void theSplicedDefinitionsAreAllPrunable() {
		// Every geom definition is a defun/defconstant, so LibraryDefunPruner keys it
		// by name; the four defclass forms are the only roots. Without this a program
		// using geom:box alone would carry geom:revolution's tessellator.
		List<LispVal> pruned = LibraryDefunPruner
			.prune(GeomLibrary.process(LispReader.readAllFromString("(print (geom:volume (geom:box 2)))")));
		List<String> names = definitionNames(pruned);
		assertThat(names).contains("GEOM:BOX", "GEOM:VOLUME", "GEOM:MESH");
		assertThat(names).doesNotContain("GEOM:REVOLUTION", "GEOM:SPHERE", "GEOM:TORUS", "GEOM:CONE",
				"GEOM:SURFACE-AREA");
	}

	private static List<String> definitionNames(List<LispVal> forms) {
		return forms.stream()
			.filter(LispCons.class::isInstance)
			.map(LispCons.class::cast)
			.filter(cons -> cons.car() instanceof LispSymbol op && op.name().startsWith("DEF"))
			.filter(cons -> cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol)
			.map(cons -> ((LispSymbol) ((LispCons) cons.cdr()).car()).name())
			.toList();
	}

	// --- the closed forms --------------------------------------------------------

	@Test
	void aBoxIsExact() {
		assertThat(number("(geom:volume (geom:box '(100 200 300)))")).isEqualTo(6000000.0);
		assertThat(number("(geom:surface-area (geom:box '(100 200 300)))")).isEqualTo(220000.0);
		assertThat(vector("(geom:centroid (geom:box '(100 200 300)))")).containsExactly(0.0, 0.0, 0.0);
		assertThat(vector("(geom:bounds-extent (geom:bounds (geom:box '(100 200 300))))")).containsExactly(100.0, 200.0,
				300.0);
	}

	@Test
	void aCylinderApproachesPiRSquaredH() {
		double measured = number("(geom:volume (geom:cylinder :radius 50 :height 100 :sides 64))");
		double exact = PI * 50 * 50 * 100;
		assertThat(measured).isLessThan(exact).isGreaterThan(exact * 0.995);
	}

	@Test
	void aSphereApproachesFourThirdsPiRCubed() {
		double measured = number("(geom:volume (geom:sphere :radius 50 :sides 32 :stacks 24))");
		double exact = 4.0 / 3.0 * PI * 50 * 50 * 50;
		assertThat(measured).isLessThan(exact).isGreaterThan(exact * 0.98);
		double area = number("(geom:surface-area (geom:sphere :radius 50 :sides 32 :stacks 24))");
		assertThat(area).isLessThan(4 * PI * 50 * 50).isGreaterThan(4 * PI * 50 * 50 * 0.98);
	}

	@Test
	void aTorusApproachesTwoPiSquaredRTubeSquared() {
		double measured = number("(geom:volume (geom:torus :radius 60 :tube 20 :sides 48 :rings 24))");
		double exact = 2 * PI * PI * 60 * 20 * 20;
		assertThat(measured).isLessThan(exact).isGreaterThan(exact * 0.97);
	}

	@Test
	void aConeApproachesAThirdOfItsCylinder() {
		double measured = number("(geom:volume (geom:cone :radius 50 :height 120 :sides 64))");
		double exact = PI * 50 * 50 * 120 / 3;
		assertThat(measured).isLessThan(exact).isGreaterThan(exact * 0.995);
	}

	@Test
	void anExtrusionIsTheGeneralPrismAndIsExact() {
		// A unit-square prism 10 tall: the extrusion path has no curvature to lose.
		assertThat(number("""
				(geom:volume (geom:extrusion '((0 0 0) (4 0 0) (4 3 0) (0 3 0)) :along 10))
				""")).isEqualTo(120.0);
	}

	@Test
	void anArrowIsAPrismPlusAPyramidAndIsExact() {
		// The arrow is built as ONE shell -- a base cap, the shaft's sides, the head's
		// underside annulus and the head's cone -- so its volume has a closed form on
		// the TESSELLATED shape, not just a limit it approaches: a regular n-gon of
		// circumradius r has area (n/2) r^2 sin(2pi/n), and the solid is that prism
		// plus the pyramid over the wider n-gon. An inverted facet in any of the four
		// families would subtract and miss this by a mile.
		double ngon = 24 / 2.0 * Math.sin(2 * PI / 24);
		double exact = ngon * (6 * 6 * 156.0 + 18 * 18 * 44.0 / 3.0);
		assertThat(number("(geom:volume (geom:arrow :length 200 :sides 24))")).isCloseTo(exact, offset(1e-2));
		// The defaults are fractions of the length, so naming them changes nothing.
		assertThat(number("""
				(geom:volume (geom:arrow :length 200 :radius 6 :head-radius 18 :head-length 44 :sides 24))
				""")).isCloseTo(exact, offset(1e-2));
		// 142 triangles: (n - 2) for the cap, 2n for the shaft, 2n for the annulus,
		// n for the cone.
		assertThat(number("(geom:mesh-triangle-count (geom:arrow :length 200 :sides 24))")).isEqualTo(142.0);
	}

	@Test
	void anArrowPointsWhereItWasAimedAndIsTheSameSolidEveryWay() {
		// The tail is the model origin and the tip is LENGTH along the direction, so
		// the bounds say which way it points: 2 * head-radius across, length along.
		assertThat(vector("(geom:bounds-extent (geom:bounds (geom:arrow :length 200)))"))
			.usingComparatorWithPrecision(1e-3)
			.containsExactly(36.0, 36.0, 200.0);
		assertThat(vector("(geom:bounds-extent (geom:bounds (geom:arrow :length 200 :direction :x)))"))
			.usingComparatorWithPrecision(1e-3)
			.containsExactly(200.0, 36.0, 36.0);
		assertThat(vector("(geom:upper-of (geom:bounds (geom:arrow :length 200 :direction :y)))"))
			.usingComparatorWithPrecision(1e-3)
			.containsExactly(18.0, 200.0, 18.0);
		assertThat(vector("(geom:lower-of (geom:bounds (geom:arrow :length 200 :direction :-z)))"))
			.usingComparatorWithPrecision(1e-3)
			.containsExactly(-18.0, -18.0, -200.0);
		// A direction is a rigid rotation of the same shell, so it cannot change the
		// volume -- an arbitrary vector included.
		double along = number("(geom:volume (geom:arrow :length 200))");
		assertThat(number("(geom:volume (geom:arrow :length 200 :direction :x))")).isCloseTo(along, offset(1e-2));
		assertThat(number("(geom:volume (geom:arrow :length 200 :direction (geom:vec3 1 2 -3)))")).isCloseTo(along,
				offset(1e-2));
	}

	@Test
	void aThickerShaftIsAThickerArrow() {
		// The point of the whole exercise: a line has no width and a solid does.
		double thin = number("(geom:volume (geom:arrow :length 200 :radius 2 :head-radius 8 :head-length 40))");
		double thick = number("(geom:volume (geom:arrow :length 200 :radius 12 :head-radius 30 :head-length 40))");
		assertThat(thick).isGreaterThan(4 * thin);
		assertThat(vector("""
				(geom:bounds-extent (geom:bounds (geom:arrow :length 200 :radius 12 :head-radius 30)))
				""")).usingComparatorWithPrecision(1e-3).containsExactly(60.0, 60.0, 200.0);
	}

	@Test
	void aTriadIsThreeArrowsAndNotAViewerMode() {
		// The origin indicator, as solids the caller owns: three labelled arrows in
		// the tints the viewer's line triad draws, placeable by :at.
		assertThat(eval("(length (geom:triad))").print()).isEqualTo("3");
		assertThat(eval("(mapcar #'geom:label-of (geom:triad))").print()).isEqualTo("(\"x\" \"y\" \"z\")");
		assertThat(eval("""
				(mapcar (lambda (a) (typep a 'geom:solid)) (geom:triad))
				""").print()).isEqualTo("(T T T)");
		assertThat(vector("(geom:color-of (first (geom:triad)))")).usingComparatorWithPrecision(1e-6)
			.containsExactly(1.0, 0.28, 0.28);
		// Each arrow points down its own axis, from the point :at names.
		assertThat(vector("""
				(geom:world-translation (third (geom:triad :at (geom:vec3 10 20 30))))
				""")).usingComparatorWithPrecision(1e-4).containsExactly(10.0, 20.0, 30.0);
		assertThat(vector("""
				(geom:upper-of (geom:bounds (second (geom:triad :length 100 :at (geom:vec3 0 0 0)))))
				""")).usingComparatorWithPrecision(1e-3).containsExactly(9.0, 100.0, 9.0);
	}

	@Test
	void aPolyhedronIsTheEscapeHatch() {
		// A tetrahedron on the unit corner: volume 1/6, every loop wound outward.
		assertThat(number("""
				(geom:volume (geom:polyhedron '((0 0 0) (1 0 0) (0 1 0) (0 0 1))
				                              '((0 2 1) (0 1 3) (0 3 2) (1 2 3))))
				""")).isCloseTo(1.0 / 6.0, org.assertj.core.data.Offset.offset(1e-6));
	}

	@Test
	void aCentroidSitsWhereTheSolidWasBuilt() {
		// A cylinder's model origin is the centre of its base, so the centre of volume
		// is half its height up the z axis.
		assertThat(vector("(geom:centroid (geom:cylinder :radius 10 :height 80 :sides 48))")[2]).isCloseTo(40.0,
				org.assertj.core.data.Offset.offset(1e-3));
	}

	// --- transform algebra -------------------------------------------------------

	@Test
	void invertUndoesATransform() {
		assertThat(vector("""
				(let ((a (geom:make-transform :translation (geom:vec3 1 2 3) :axis :z :angle 0.5)))
				  (geom:transform-point (geom:invert a) (geom:transform-point a (geom:vec3 7 -3 2))))
				""")).usingComparatorWithPrecision(1e-4).containsExactly(7.0, -3.0, 2.0);
	}

	@Test
	void composeAgreesWithApplyingBothTransforms() {
		assertThat(vector("""
				(let* ((a (geom:make-transform :translation (geom:vec3 1 2 3) :axis :z :angle 0.5))
				       (b (geom:make-transform :translation (geom:vec3 -4 0 2) :axis :x :angle -1.1))
				       (p (geom:vec3 7 -3 2)))
				  (linalg:sub (geom:transform-point (geom:compose a b) p)
				              (geom:transform-point a (geom:transform-point b p))))
				""")).usingComparatorWithPrecision(1e-4).containsExactly(0.0, 0.0, 0.0);
	}

	@Test
	void inverseTransformPointIsTheInvertRoundTripWithoutBuildingATransform() {
		assertThat(vector("""
				(let ((a (geom:make-transform :translation (geom:vec3 1 2 3) :rpy '(0.3 -0.2 1.1))))
				  (geom:inverse-transform-point a (geom:transform-point a (geom:vec3 7 -3 2))))
				""")).usingComparatorWithPrecision(1e-4).containsExactly(7.0, -3.0, 2.0);
	}

	@Test
	void aTransformIsAValueAndTheMutatorsDoNotWriteThroughIt() {
		// move/turn/place REPLACE a node's local transform, so a transform handed to two
		// nodes stays what the caller built.
		assertThat(vector("""
				(let* ((tf (geom:make-transform :translation (geom:vec3 1 2 3)))
				       (n (geom:make-node :transform tf)))
				  (geom:move n (geom:vec3 10 0 0))
				  (geom:translation-of tf))
				""")).containsExactly(1.0, 2.0, 3.0);
	}

	@Test
	void anAxisAngleMatrixTurnsTheAxisItNames() {
		assertThat(vector("(linalg:row (geom:axis-angle-matrix (/ 3.141592653589793 2) :z) 0)"))
			.usingComparatorWithPrecision(1e-6)
			.containsExactly(0.0, -1.0, 0.0);
	}

	// --- the scene graph ---------------------------------------------------------

	@Test
	void aJointChainComposesItsWorldTransforms() {
		// base -> j1 -> j2 (100 up) -> link. j2 turned a quarter about y leaves the
		// link's origin where it was and points its model x down the world z; moving the
		// base then carries the whole subtree.
		String chain = """
				(defvar *base* (geom:make-node))
				(defvar *j1* (geom:make-node :parent *base*))
				(defvar *j2* (geom:make-node :translation (geom:vec3 0 0 100)))
				(defvar *link* (geom:cylinder :radius 8 :height 80))
				(geom:attach *j1* *j2*)
				(geom:attach *j2* *link*)
				(geom:turn *j2* (/ 3.141592653589793 2) :y)
				(geom:move *base* (geom:vec3 0 0 500))
				""";
		assertThat(vector(chain + "(geom:world-translation *link*)")).usingComparatorWithPrecision(1e-4)
			.containsExactly(0.0, 0.0, 600.0);
		assertThat(vector(chain + "(linalg:row (geom:world-rotation *link*) 0)")).usingComparatorWithPrecision(1e-6)
			.containsExactly(0.0, 0.0, 1.0);
		// The cylinder now lies along world x from the joint: 80 long, centred 40 out.
		assertThat(vector(chain + "(geom:bounds-center (geom:bounds *link*))")).usingComparatorWithPrecision(1e-3)
			.containsExactly(40.0, 0.0, 600.0);
	}

	@Test
	void detachTakesASubtreeOutOfItsParentsFrame() {
		assertThat(vector("""
				(let* ((base (geom:make-node :translation (geom:vec3 0 0 500)))
				       (n (geom:make-node :translation (geom:vec3 1 2 3) :parent base)))
				  (geom:detach n)
				  (geom:world-translation n))
				""")).containsExactly(1.0, 2.0, 3.0);
	}

	@Test
	void theParentAndChildLinksAreBothSides() {
		assertThat(eval("""
				(let* ((base (geom:make-node))
				       (n (geom:make-node :parent base)))
				  (list (eq (geom:parent-of n) base) (eq (first (geom:children-of base)) n)
				        (progn (geom:detach n) (list (geom:parent-of n) (geom:children-of base)))))
				""").print()).isEqualTo("(T T (NIL NIL))");
	}

	@Test
	void moveInTheParentFrameIgnoresTheNodesOwnRotation() {
		assertThat(vector("""
				(let ((n (geom:make-node :axis :z :angle (/ 3.141592653589793 2))))
				  (geom:move n (geom:vec3 10 0 0) :frame :parent)
				  (geom:world-translation n))
				""")).usingComparatorWithPrecision(1e-5).containsExactly(10.0, 0.0, 0.0);
	}

	@Test
	void moveInTheLocalFrameFollowsTheNodesOwnAxes() {
		// Turned a quarter about z, the node's own +x is the parent's +y.
		assertThat(vector("""
				(let ((n (geom:make-node :axis :z :angle (/ 3.141592653589793 2))))
				  (geom:move n (geom:vec3 10 0 0))
				  (geom:world-translation n))
				""")).usingComparatorWithPrecision(1e-5).containsExactly(0.0, 10.0, 0.0);
	}

	@Test
	void placeSetsThePoseOutrightRatherThanAccumulating() {
		assertThat(vector("""
				(let ((n (geom:make-node)))
				  (geom:turn n 0.4 :z)
				  (geom:turn n 0.4 :z)
				  (geom:place n :axis :z :angle 0.4)
				  (linalg:row (geom:world-rotation n) 0))
				""")).usingComparatorWithPrecision(1e-6).containsExactly(Math.cos(0.4), -Math.sin(0.4), 0.0);
	}

	@Test
	void aPoseChangeInvalidatesTheWholeSubtreesWorldTransform() {
		assertThat(vector("""
				(let* ((base (geom:make-node))
				       (mid (geom:make-node :parent base))
				       (leaf (geom:make-node :parent mid)))
				  (geom:world-translation leaf)
				  (geom:move base (geom:vec3 0 0 7))
				  (geom:world-translation leaf))
				""")).containsExactly(0.0, 0.0, 7.0);
	}

	// --- bounds ------------------------------------------------------------------

	@Test
	void boundsOfAListIsTheirUnionInWorldCoordinates() {
		assertThat(vector("""
				(let ((a (geom:box 10))
				      (b (geom:box 10)))
				  (geom:move b (geom:vec3 100 0 0))
				  (geom:bounds-extent (geom:bounds (list a b))))
				""")).containsExactly(110.0, 10.0, 10.0);
	}

	@Test
	void boundsUnionAndItsAccessors() {
		assertThat(eval("""
				(let* ((a (geom:bounds (geom:box 10)))
				       (b (progn (geom:bounds (geom:box 2)))))
				  (coerce (geom:lower-of (geom:bounds-union a b)) 'list))
				""").print()).isEqualTo("(-5.0 -5.0 -5.0)");
	}

	// --- the mesh, and its cache -------------------------------------------------

	@Test
	void theMeshIsEighteenFloatsATriangleAndIsCachedOnTheSolid() {
		assertThat(eval("""
				(let ((s (geom:box 1)))
				  (list (length (geom:mesh s)) (geom:mesh-triangle-count s)
				        (eq (geom:mesh s) (geom:mesh s))
				        (eq (geom:wireframe s) (geom:wireframe s))))
				""").print()).isEqualTo("(216 12 T T)");
	}

	@Test
	void theWireframeAnswersEachEdgeOnce() {
		// A cube has 12 edges; 6 floats a segment.
		assertThat(number("(length (geom:wireframe (geom:box 1)))")).isEqualTo(72.0);
	}

	@Test
	void scaleInvalidatesBothCachesAndTheUserDataSlot() {
		assertThat(eval("""
				(let* ((s (geom:box 10))
				       (m (geom:mesh s)))
				  (setf (geom:user-data s) :mine)
				  (geom:scale s 2)
				  (list (eq m (geom:mesh s)) (geom:user-data s) (geom:volume s)))
				""").print()).isEqualTo("(NIL NIL 8000.0)");
	}

	@Test
	void theMeshIsInModelSpaceAndDoesNotFollowThePose() {
		assertThat(eval("""
				(let* ((s (geom:box 10))
				       (m (geom:mesh s)))
				  (geom:move s (geom:vec3 1000 0 0))
				  (eq m (geom:mesh s)))
				""").print()).isEqualTo("T");
	}

	@Test
	void everyMeshAndVertexArrayIsPackedFloat32() {
		// A GPU vertex buffer's bytes: objc:data takes a packed single-float array of
		// any rank with no conversion.
		assertThat(eval("""
				(let ((s (geom:sphere :radius 3 :sides 8 :stacks 6)))
				  (list (array-element-type (geom:mesh s)) (array-element-type (geom:wireframe s))
				        (array-element-type (geom:vertices-of s)) (array-element-type (geom:vec3 1 2 3))
				        (array-element-type (geom:rotation-of (geom:make-transform)))))
				""").print()).isEqualTo("(SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT)");
	}

	@Test
	void aUserDataSlotRidesAlongForAConsumersOwnState() {
		assertThat(eval("""
				(let ((s (geom:box 1)))
				  (setf (geom:user-data s) (list :buffer 42))
				  (geom:user-data s))
				""").print()).isEqualTo("(:BUFFER 42)");
	}

	@Test
	void aSolidCarriesItsColourAndLabel() {
		assertThat(eval("""
				(let ((s (geom:box 1 :color (geom:vec3 1 0 0) :label "lid")))
				  (list (coerce (geom:color-of s) 'list) (geom:label-of s) (length (geom:facets-of s))))
				""").print()).isEqualTo("((1.0 0.0 0.0) \"lid\" 6)");
	}

	@Test
	void aSolidIsANodeAndATransformIsNotOne() {
		assertThat(eval("""
				(list (typep (geom:box 1) 'geom:node) (typep (geom:box 1) 'geom:solid)
				      (typep (geom:make-node) 'geom:solid) (typep (geom:make-transform) 'geom:node))
				""").print()).isEqualTo("(T T NIL NIL)");
	}

	// --- constructive solid geometry ---------------------------------------------
	//
	// BSP clipping over world-space boundary polygons (.kb/geom.md, "Boolean
	// operations"). Volume is the oracle: vol(A u B) + vol(A n B) = vol(A) + vol(B)
	// for ANY pair, within the tessellation error the primitives already carry --
	// and because volume is the divergence theorem, every equality below is also
	// the normals/winding check on the result.

	@Test
	void theBooleansOfTwoOverlappingBoxesAreExact() {
		// Axis-aligned splits land on exact dyadic coordinates, so every volume is
		// exact: overlap 50^3, union 2e6 - 125000, difference 1e6 - 125000.
		String setUp = """
				(defvar *a* (geom:box 100))
				(defvar *b* (geom:box 100))
				(geom:move *b* (geom:vec3 50 50 50))
				""";
		assertThat(number(setUp + "(geom:volume (geom:union *a* *b*))")).isEqualTo(1875000.0);
		assertThat(number(setUp + "(geom:volume (geom:difference *a* *b*))")).isEqualTo(875000.0);
		assertThat(number(setUp + "(geom:volume (geom:intersection *a* *b*))")).isEqualTo(125000.0);
	}

	@Test
	void theVolumeOracleHoldsForCurvedOverlappingSolids() {
		// vol(union) + vol(intersection) = vol(a) + vol(b), and vol(a \ b) =
		// vol(a) - vol(a n b), for a sphere overlapping an offset box.
		String setUp = """
				(defvar *a* (geom:sphere :radius 40 :sides 16 :stacks 8))
				(defvar *b* (geom:box 60))
				(geom:move *b* (geom:vec3 30 10 20))
				""";
		double va = number(setUp + "(geom:volume *a*)");
		double vb = number(setUp + "(geom:volume *b*)");
		double vu = number(setUp + "(geom:volume (geom:union *a* *b*))");
		double vi = number(setUp + "(geom:volume (geom:intersection *a* *b*))");
		double vd = number(setUp + "(geom:volume (geom:difference *a* *b*))");
		assertThat(vu + vi).isCloseTo(va + vb, org.assertj.core.data.Offset.offset((va + vb) * 1e-4));
		assertThat(vd).isCloseTo(va - vi, org.assertj.core.data.Offset.offset(va * 1e-4));
	}

	@Test
	void coplanarFacesAreTheFirstDegenerateCase() {
		// Two boxes sharing the x = 50 face EXACTLY: the union is their sum with the
		// shared face gone (surface area says so), the intersection is empty, and the
		// difference leaves a untouched.
		String setUp = """
				(defvar *a* (geom:box 100))
				(defvar *b* (geom:box 100))
				(geom:move *b* (geom:vec3 100 0 0))
				""";
		assertThat(number(setUp + "(geom:volume (geom:union *a* *b*))")).isEqualTo(2000000.0);
		assertThat(number(setUp + "(geom:surface-area (geom:union *a* *b*))")).isEqualTo(100000.0);
		assertThat(number(setUp + "(geom:volume (geom:intersection *a* *b*))")).isEqualTo(0.0);
		assertThat(number(setUp + "(geom:volume (geom:difference *a* *b*))")).isEqualTo(1000000.0);
	}

	@Test
	void anEdgeLyingExactlyOnAFaceIsTheSecondDegenerateCase() {
		// b turned 45 degrees about z and pushed until its corner edge lies ON a's
		// x = 50 face (to float32 rounding -- the near-degenerate contact the
		// tolerance model exists for). The two sides of that edge must classify
		// consistently: union = sum, intersection empty, no torn shell.
		String setUp = """
				(defvar *a* (geom:box 100))
				(defvar *b* (geom:box 100))
				(geom:turn *b* 0.7853981633974483 :z)
				(geom:move *b* (geom:vec3 120.71068 0 0) :frame :parent)
				""";
		assertThat(number(setUp + "(geom:volume (geom:union *a* *b*))")).isCloseTo(2000000.0,
				org.assertj.core.data.Offset.offset(20.0));
		assertThat(number(setUp + "(geom:volume (geom:intersection *a* *b*))")).isCloseTo(0.0,
				org.assertj.core.data.Offset.offset(20.0));
	}

	@Test
	void aHoleExactlyAsDeepAsThePlateIsThickGoesAllTheWayThrough() {
		// Both cylinder caps coplanar with the plate faces. The volume must be the
		// plate minus the full prism -- a hole that stops one epsilon short would
		// leave a membrane and answer the plate's volume.
		String setUp = """
				(defvar *plate* (geom:box '(100 100 20)))
				(defvar *hole* (geom:cylinder :radius 10 :height 20 :sides 24))
				(geom:move *hole* (geom:vec3 0 0 -10))
				""";
		double prism = 0.5 * 24 * 100 * Math.sin(2 * PI / 24) * 20;
		assertThat(number(setUp + "(geom:volume (geom:difference *plate* *hole*))")).isCloseTo(200000.0 - prism,
				org.assertj.core.data.Offset.offset(1.0));
		// The bore's lateral surface is part of the result: total area = plate faces
		// minus the two cap disks plus the 24-gon prism wall.
		double wall = 24 * 2 * 10 * Math.sin(PI / 24) * 20;
		assertThat(number(setUp + "(geom:surface-area (geom:difference *plate* *hole*))")).isCloseTo(
				2 * (10000 - 0.5 * 24 * 100 * Math.sin(2 * PI / 24)) + 4 * 100 * 20 + wall,
				org.assertj.core.data.Offset.offset(1.0));
	}

	@Test
	void disjointSolidsUniteToTheirSumAndIntersectToTheEmptySolid() {
		String setUp = """
				(defvar *a* (geom:box 10))
				(defvar *b* (geom:box 10))
				(geom:move *b* (geom:vec3 100 0 0))
				""";
		assertThat(number(setUp + "(geom:volume (geom:union *a* *b*))")).isEqualTo(2000.0);
		assertThat(eval(setUp + """
				(let ((i (geom:intersection *a* *b*)))
				  (list (geom:volume i) (geom:mesh-triangle-count i) (geom:facets-of i)))
				""").print()).isEqualTo("(0.0 0 NIL)");
	}

	@Test
	void theToleranceIsRelativeSoBothTinyAndHugeModelsSurvive() {
		// The same overlapping pair at 0.001 scale and 1000 scale: an absolute
		// epsilon would swallow the small model whole or misclassify nothing on the
		// large one. Relative volume error stays at float32 noise for both.
		double tiny = number("""
				(let ((a (geom:box 0.001)) (b (geom:box 0.001)))
				  (geom:move b (geom:vec3 0.0005 0.0005 0.0005))
				  (* 1e9 (geom:volume (geom:union a b))))
				""");
		assertThat(tiny).isCloseTo(1.875, org.assertj.core.data.Offset.offset(1e-4));
		double huge = number("""
				(let ((a (geom:box 1000)) (b (geom:box 1000)))
				  (geom:move b (geom:vec3 500 500 500))
				  (geom:volume (geom:union a b)))
				""");
		assertThat(huge).isEqualTo(1.875e9);
	}

	@Test
	void theOperandsAreUntouchedAndTheResultRecordsItsHistory() {
		assertThat(eval("""
				(let* ((a (geom:box 100))
				       (b (geom:box 100))
				       (va (geom:vertices-of a))
				       (d (progn (geom:move b (geom:vec3 50 50 50)) (geom:difference a b))))
				  (list (eq va (geom:vertices-of a)) (geom:volume a) (geom:volume b)
				        (first (geom:history d)) (eq (second (geom:history d)) a)
				        (eq (third (geom:history d)) b) (geom:history a)))
				""").print()).isEqualTo("(T 1000000.0 1000000.0 :DIFFERENCE T T NIL)");
	}

	@Test
	void theBooleansTakeTheirOperandsInWorldCoordinates() {
		// (geom:difference plate hole) means what it looks like after both have been
		// placed: the result is a new ROOT solid whose vertices are world
		// coordinates, not a node in either operand's frame.
		assertThat(eval("""
				(let* ((base (geom:make-node :translation (geom:vec3 0 0 500)))
				       (a (geom:box 100))
				       (b (geom:box 100)))
				  (geom:attach base a)
				  (geom:move b (geom:vec3 0 0 450))
				  (let ((u (geom:union a b)))
				    (list (geom:volume u) (geom:parent-of u)
				          (mapcar (lambda (x) (round x))
				                  (coerce (geom:bounds-center (geom:bounds u)) 'list)))))
				""").print()).isEqualTo("(1500000.0 NIL (0 0 475))");
	}

	@Test
	void aResultIsAnOrdinarySolidTheRestOfThePackageAccepts() {
		assertThat(eval("""
				(let ((u (geom:union (geom:box 10) (geom:box '(4 4 30)))))
				  (list (typep u 'geom:solid) (array-element-type (geom:mesh u))
				        (array-element-type (geom:vertices-of u))
				        (coerce (geom:bounds-extent (geom:bounds u)) 'list)))
				""").print()).isEqualTo("(T SINGLE-FLOAT SINGLE-FLOAT (10.0 10.0 30.0))");
	}

	// --- planar section ----------------------------------------------------------

	@Test
	void aSectionOfABoxIsOneRectangularLoop() {
		assertThat(eval("""
				(let ((loops (geom:section (geom:box '(100 200 300)))))
				  (list (length loops) (linalg:shape (first loops))))
				""").print()).isEqualTo("(1 (4 3))");
		// Wound counter-clockwise seen from the +normal side.
		assertThat(eval("(first (geom:section (geom:box '(100 200 300))))").print())
			.isEqualTo("#f((-50.0 -100.0 0.0) (50.0 -100.0 0.0) (50.0 100.0 0.0) (-50.0 100.0 0.0))");
	}

	@Test
	void aSectionOfATorusIsTwoLoops() {
		// z = 0 cuts the tube twice: an outer boundary and the hole.
		assertThat(number("(length (geom:section (geom:torus :radius 60 :tube 20 :sides 24 :rings 12)))"))
			.isEqualTo(2.0);
	}

	@Test
	void aSectionExactlyOnAFaceIsThatFacesBoundary() {
		// The plane coincides with the box top: the coplanar facet itself is skipped
		// and its boundary comes from the side facets' edges lying ON the plane.
		assertThat(eval("""
				(let ((loops (geom:section (geom:box 100) :offset 50)))
				  (list (length loops) (linalg:shape (first loops))))
				""").print()).isEqualTo("(1 (4 3))");
	}

	@Test
	void aSectionTakesAnAxisOrAVectorAndAnOffsetOrAnOrigin() {
		assertThat(eval("""
				(let ((s (geom:box '(100 200 300))))
				  (list (linalg:shape (first (geom:section s :normal :x)))
				        (length (geom:section s :normal (geom:vec3 0 0 1)
				                              :origin (geom:vec3 0 0 100)))
				        (geom:section s :normal :y :offset 500)))
				""").print()).isEqualTo("((4 3) 1 NIL)");
	}

	@Test
	void aSectionFollowsThePose() {
		// The solid is placed before it is cut: a box lifted 100 up is missed by
		// z = 0 and cut by z = 100.
		assertThat(eval("""
				(let ((s (geom:box 10)))
				  (geom:move s (geom:vec3 0 0 100))
				  (list (geom:section s) (length (geom:section s :offset 100))))
				""").print()).isEqualTo("(NIL 1)");
	}

	// --- the printed representation -----------------------------------

	@Test
	void aSolidPrintsItsLabelAndItsTwoCounts() {
		// print-object on geom:solid: the label when there is one, then the two counts
		// that say what it is -- not the whole vertex array (520 characters for a box,
		// 2,180 for a cylinder).
		assertThat(eval("(prin1-to-string (geom:box 2 :label \"b\"))").print())
			.isEqualTo("\"#<GEOM:SOLID \\\"b\\\" 8 vertices 6 facets>\"");
		// No label: the counts alone. princ drops the package qualifier (CLHS 22.1.3.3).
		assertThat(eval("(princ-to-string (geom:box 2))").print()).isEqualTo("\"#<SOLID 8 vertices 6 facets>\"");
	}

	@Test
	void aNodeInASceneGraphPrintsItsChildCountInsteadOfOverflowingTheStack() {
		// geom:node's parent and children slots point at each other, so the default
		// renderer StackOverflowError'd on ANY attached node. The method
		// prints the child count and never walks the graph.
		assertThat(eval("""
				(let ((a (geom:make-node)) (b (geom:make-node)))
				  (geom:attach a b)
				  (list (prin1-to-string b) (prin1-to-string a)))
				""").print()).isEqualTo("(\"#<GEOM:NODE 0 children>\" \"#<GEOM:NODE 1 child>\")");
	}

	@Test
	void anAttachedSolidPrintsToo() {
		// The more serious half: an attached SOLID was unprintable.
		assertThat(eval("""
				(let ((a (geom:box 2 :label "a")) (b (geom:box 3)))
				  (geom:attach a b)
				  (prin1-to-string a))
				""").print()).isEqualTo("\"#<GEOM:SOLID \\\"a\\\" 8 vertices 6 facets>\"");
	}

	@Test
	void aTransformAndABoundsStillPrintTheirSlots() {
		// Deliberately NO print-object method on geom:transform / geom:bounds: their
		// slots ARE the value (12 numbers of rigid motion, two corner points), they hold
		// no cache and cannot cycle, so the full default rendering is the honest print.
		assertThat(eval("(prin1-to-string (geom:make-transform))").print())
			.isEqualTo("\"#<GEOM:TRANSFORM :TRANSLATION #f(0.0 0.0 0.0)"
					+ " :ROTATION #f((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))>\"");
		assertThat(eval("(prin1-to-string (geom:bounds (geom:box 2)))").print())
			.isEqualTo("\"#<GEOM:BOUNDS :LOWER #f(-1.0 -1.0 -1.0) :UPPER #f(1.0 1.0 1.0)>\"");
	}

	@Test
	void theCsgDefinitionsArePrunedFromAProgramThatDoesNotUseThem() {
		List<LispVal> pruned = LibraryDefunPruner
			.prune(GeomLibrary.process(LispReader.readAllFromString("(print (geom:volume (geom:box 2)))")));
		assertThat(definitionNames(pruned)).doesNotContain("GEOM:UNION", "GEOM:DIFFERENCE", "GEOM:INTERSECTION",
				"GEOM:SECTION");
		List<LispVal> kept = LibraryDefunPruner.prune(GeomLibrary
			.process(LispReader.readAllFromString("(print (geom:volume (geom:union (geom:box 2) (geom:box 3))))")));
		assertThat(definitionNames(kept)).contains("GEOM:UNION", "GEOM:*TOLERANCE*")
			.doesNotContain("GEOM:SECTION", "GEOM:SPHERE");
	}

}
