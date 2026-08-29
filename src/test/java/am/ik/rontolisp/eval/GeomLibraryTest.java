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

}
