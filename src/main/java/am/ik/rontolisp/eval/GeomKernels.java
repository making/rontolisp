package am.ik.rontolisp.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's native fast paths for the four {@code geom} members a model FILE
 * spends its whole load time in: {@code geom:read-obj}, {@code geom:mesh},
 * {@code geom:wireframe} and the {@code geom::%vertex-extremes} behind
 * {@code geom:bounds}. Installed over the {@code geom.lisp} defuns the moment the library
 * loads, on the {@link LinalgSimd} interception seam exactly -- each native is a PARTIAL
 * function that answers Java {@code null} for any input it does not handle, and the
 * wrapper then applies the defun it replaced.
 *
 * <h2>Why these four</h2>
 *
 * Measured on the interpreter (Apple M4 Max) loading a 155 MB scanned hand -- 1,062,622
 * vertices, 2,123,160 facets -- with no native at all: {@code read-obj} 334 s,
 * {@code mesh} 121 s, {@code wireframe} 29 s, {@code bounds} 26 s, and the model-space
 * extent {@code scene} sizes an axis triad by 30 s. Nine minutes, and nothing else in the
 * load reached a second. These four are what scales with the FILE rather than with the
 * scene, and each is one loop over a million things the interpreter charges ~16 us a
 * float for ({@code .kb/geom.md}, "Measured"). With them the same load is 1.2 s.
 *
 * <p>
 * The JVM backend has the same four over its own CALL SITES since 2026-08-31
 * ({@code codegen/jvm/JvmGeomKernelCompiler} -> {@code JvmGeomTemplate}, a transcription
 * of the kernels below into the compiled value representation). The two are the same
 * transcription of the same defuns and {@code ci-spec.yaml} compares their output, so
 * they change together.
 *
 * <h2>Bit-identity, not approximation</h2>
 *
 * <b>This is not an accelerator with numerics of its own.</b> Unlike {@code --blas}
 * ({@code .kb/linalg-blas.md}) it is always on, which is only defensible because every
 * arithmetic step below is the defun's step transcribed: {@code %scan-number}'s
 * {@code (* m (expt 10.0 (- (* es e) frac)))} accumulated in {@code double} and finished
 * with {@code Math.pow}, Newell's normal accumulated in {@code double} over
 * {@code single-float} reads, {@code geom::%unit}'s normalization through the same
 * narrow-then-widen chain {@code linalg:emap} / {@code linalg:sum} / {@code linalg:mul}
 * puts it through, and the extremes walk narrowing where {@code linalg::%la-matmul} and
 * {@code linalg::%la-bcast} narrow. A step that rounds differently is a bug here, not a
 * tolerance: {@code GeomKernelsTest} runs every fixture down both paths and compares the
 * printed arrays, which render every element; and {@code ci-spec.yaml}'s
 * {@code geom-read-model-cross-backend} still pins this interpreter against the JVM and
 * both WASM backends -- the JVM through its own transcription of these kernels since
 * 2026-08-31, the two WASM backends through the defuns alone.
 *
 * <p>
 * Whatever the transcription does not cover DECLINES rather than approximating: a
 * non-string path, a keyword tail the reader does not take, a vertex line short of three
 * numbers, a boxed or double-width vertex array, a facet that is not a list of at least
 * three integers. The defun then runs and produces the value -- or the error -- it always
 * did.
 *
 * @see LinalgSimd
 */
public final class GeomKernels {

	private GeomKernels() {
	}

	private static final String READ_OBJ = LispNames.GEOM_PKG + ":READ-OBJ";

	private static final String MESH = LispNames.GEOM_PKG + ":MESH";

	private static final String WIREFRAME = LispNames.GEOM_PKG + ":WIREFRAME";

	private static final String SOLID_OF_VERTICES = LispNames.GEOM_PKG + "::%SOLID-OF-VERTICES";

	private static final String VERTEX_EXTREMES = LispNames.GEOM_PKG + "::%VERTEX-EXTREMES";

	/** {@code geom:read-obj}'s own keyword tail, which it passes straight through. */
	private static final String COLOR_KEYWORD = ":COLOR";

	private static final String LABEL_KEYWORD = ":LABEL";

	private static final String VERTICES_SLOT = "VERTICES";

	private static final String FACETS_SLOT = "FACETS";

	private static final String MESH_SLOT = "MESH-CACHE";

	private static final String WIRE_SLOT = "WIRE-CACHE";

	/**
	 * The largest magnitude a face index may have before {@code (floor x)} would answer a
	 * bignum rather than a fixnum. Well past any real file; it is here so a malformed one
	 * declines to the defun instead of being silently narrowed to a {@code long}.
	 */
	private static final double MAX_INDEX = 1L << 53;

	/**
	 * Overrides the accelerated {@code geom:} functions in the given (global)
	 * environment. Must be called AFTER the {@code geom.lisp} forms have been evaluated
	 * into the same environment: each override captures the defun it replaces and falls
	 * back to it.
	 * @param globalEnv the global environment holding the loaded geom library
	 * @param evaluator the evaluator used to apply a captured defun on fallback, and to
	 * hand a parsed OBJ to {@code geom::%solid-of-vertices}
	 */
	public static void install(Environment globalEnv, LispEvaluator evaluator) {
		LispVal solidOfVertices = required(globalEnv, SOLID_OF_VERTICES);
		define(globalEnv, evaluator, READ_OBJ, 1, 5, args -> readObj(args, evaluator, solidOfVertices));
		define(globalEnv, evaluator, MESH, 1, 1, GeomKernels::mesh);
		define(globalEnv, evaluator, WIREFRAME, 1, 1, GeomKernels::wireframe);
		define(globalEnv, evaluator, VERTEX_EXTREMES, 3, 3, GeomKernels::vertexExtremes);
	}

	/** A partial kernel: answers null for an input it declines. */
	@FunctionalInterface
	private interface Kernel {

		@Nullable LispVal apply(List<LispVal> args);

	}

	private static LispVal required(Environment globalEnv, String qualified) {
		LispVal defun = globalEnv.lookupFunctionOrNull(qualified);
		if (defun == null) {
			throw new IllegalStateException("geom.lisp must be loaded before " + qualified + " can be accelerated");
		}
		return defun;
	}

	private static void define(Environment globalEnv, LispEvaluator evaluator, String qualified, int minArity,
			int maxArity, Kernel kernel) {
		LispVal defun = required(globalEnv, qualified);
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() >= minArity && args.size() <= maxArity) {
				LispVal fast = kernel.apply(args);
				if (fast != null) {
					return fast;
				}
			}
			return evaluator.applyGlobal(defun, args);
		}));
	}

	// --- geom:read-obj ---------------------------------------------------------------

	/**
	 * {@code geom:read-obj}'s scan, in Java. The line walk, the two-character record
	 * test, the index arithmetic and the number syntax are the defun's; the lines are
	 * decoded by the same {@code Files.newBufferedReader} that {@code with-open-file}
	 * opens a character stream with, so a file that reads one way there reads that way
	 * here.
	 *
	 * <p>
	 * The vertices are packed straight into the {@code (n 3)} {@code single-float} array
	 * a solid holds -- {@code linalg:from-list}'s narrowing is {@code (float)} on the
	 * value the scan just produced, and a million points do not need to become a million
	 * three-element lists on the way -- and that array and the index loops are handed to
	 * the LISP {@code geom::%solid-of-vertices}, where the colour default and the
	 * transform still live.
	 */
	private static @Nullable LispVal readObj(List<LispVal> args, LispEvaluator evaluator, LispVal solidOfVertices) {
		if (!(args.get(0) instanceof LispString pathVal) || !readObjKeywordTail(args)) {
			return null;
		}
		float[] points = new float[3 * 1024];
		int coordinates = 0;
		List<LispVal> facets = new ArrayList<>();
		Scanner scan = new Scanner();
		long nv = 0;
		try (BufferedReader in = Files.newBufferedReader(Path.of(pathVal.value()))) {
			String line;
			while ((line = in.readLine()) != null) {
				int n = line.length();
				if (n <= 1) {
					continue;
				}
				char c0 = line.charAt(0);
				char c1 = line.charAt(1);
				if (c0 == 'v' && c1 == ' ') {
					if (!scan.number(line, 2, n)) {
						return null;
					}
					double x = scan.value;
					if (!scan.number(line, scan.next, n)) {
						return null;
					}
					double y = scan.value;
					if (!scan.number(line, scan.next, n)) {
						// A vertex line short of three numbers: the defun signals its
						// own error, naming the path.
						return null;
					}
					if (coordinates + 3 > points.length) {
						points = Arrays.copyOf(points, points.length * 2);
					}
					points[coordinates] = (float) x;
					points[coordinates + 1] = (float) y;
					points[coordinates + 2] = (float) scan.value;
					coordinates += 3;
					nv++;
				}
				else if (c0 == 'f' && c1 == ' ') {
					List<LispVal> loop = new ArrayList<>(4);
					int p = 2;
					while (scan.number(line, p, n)) {
						p = scan.next;
						double floored = Math.floor(scan.value);
						if (!(floored >= -MAX_INDEX && floored <= MAX_INDEX)) {
							// (floor x) answers a BIGNUM out here, which is not what a
							// LispInteger is. Decline rather than narrow.
							return null;
						}
						long i = (long) floored;
						loop.add(new LispInteger(i < 0 ? nv + i : i - 1));
						// Read past the /vt/vn of this token.
						while (p < n && line.charAt(p) == '/') {
							p = p + 1;
							if (scan.number(line, p, n)) {
								p = scan.next;
							}
						}
					}
					if (loop.size() >= 3) {
						facets.add(list(loop));
					}
				}
			}
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
		List<LispVal> call = new ArrayList<>(args.size() + 1);
		call.add(new LispSingleFloatArray(Arrays.copyOf(points, coordinates), new int[] { coordinates / 3, 3 }));
		call.add(list(facets));
		call.addAll(args.subList(1, args.size()));
		return evaluator.applyGlobal(solidOfVertices, call);
	}

	/**
	 * Whether the tail past the path is exactly the {@code :color} / {@code :label}
	 * keyword pairs the reader declares. Anything else declines, so the defun's own
	 * lambda-list signals about it.
	 */
	private static boolean readObjKeywordTail(List<LispVal> args) {
		if ((args.size() - 1) % 2 != 0) {
			return false;
		}
		for (int i = 1; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol key)
					|| !(COLOR_KEYWORD.equals(key.name()) || LABEL_KEYWORD.equals(key.name()))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@code geom::%scan-number} transcribed: leading blanks skipped, the syntax
	 * {@code [+-]d*[.d*][eE[+-]d*]}, the mantissa accumulated in {@code double} across
	 * the decimal point and the whole thing scaled once by {@code (expt 10.0 (- (* es e)
	 * frac))} -- which the interpreter's {@code expt} answers with {@code Math.pow} for a
	 * float base, so the same two multiplications happen in the same order here.
	 */
	private static final class Scanner {

		private double value;

		private int next;

		private boolean number(String s, int i, int n) {
			int j = i;
			while (j < n) {
				char c = s.charAt(j);
				if (c == ' ' || c == '\t' || c == '\r') {
					j++;
				}
				else {
					break;
				}
			}
			if (j >= n) {
				return false;
			}
			boolean neg = false;
			double m = 0.0;
			int frac = 0;
			int e = 0;
			int es = 1;
			int digits = 0;
			char c = s.charAt(j);
			if (c == '-') {
				neg = true;
				j++;
			}
			else if (c == '+') {
				j++;
			}
			while (j < n) {
				c = s.charAt(j);
				if (c >= '0' && c <= '9') {
					m = m * 10.0 + (c - '0');
					digits++;
					j++;
				}
				else {
					break;
				}
			}
			if (j < n && s.charAt(j) == '.') {
				j++;
				while (j < n) {
					c = s.charAt(j);
					if (c >= '0' && c <= '9') {
						m = m * 10.0 + (c - '0');
						frac++;
						digits++;
						j++;
					}
					else {
						break;
					}
				}
			}
			if (digits == 0) {
				return false;
			}
			if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
				j++;
				if (j < n) {
					c = s.charAt(j);
					if (c == '-') {
						es = -1;
						j++;
					}
					else if (c == '+') {
						j++;
					}
				}
				while (j < n) {
					c = s.charAt(j);
					if (c >= '0' && c <= '9') {
						e = e * 10 + (c - '0');
						j++;
					}
					else {
						break;
					}
				}
			}
			double v = m * Math.pow(10.0, (double) (es * e - frac));
			this.value = neg ? -v : v;
			this.next = j;
			return true;
		}

	}

	// --- geom:mesh -------------------------------------------------------------------

	/**
	 * {@code geom:mesh}'s fan triangulation with a Newell normal per facet, filling the
	 * same {@code (* tris 18)} float array in the same order. The cache slot is read and
	 * written exactly where the defun reads and writes it.
	 */
	private static @Nullable LispVal mesh(List<LispVal> args) {
		Solid s = Solid.of(args.get(0));
		if (s == null) {
			return null;
		}
		LispVal cached = s.instance.slot(s.meshSlot);
		if (!(cached instanceof LispNil)) {
			return cached;
		}
		int[][] facets = s.facets();
		if (facets == null) {
			return null;
		}
		float[] v = s.vertices.data();
		long tris = 0;
		for (int[] facet : facets) {
			tris += facet.length - 2;
		}
		if (tris * 18 > Integer.MAX_VALUE) {
			return null;
		}
		float[] out = new float[(int) (tris * 18)];
		float[] normal = new float[3];
		int k = 0;
		for (int[] idx : facets) {
			if (!facetNormal(v, idx, normal)) {
				return null;
			}
			int m = idx.length;
			int a = idx[0];
			for (int i = 1; i < m - 1; i++) {
				k = emitTriangleVertex(out, k, v, a, normal);
				k = emitTriangleVertex(out, k, v, idx[i], normal);
				k = emitTriangleVertex(out, k, v, idx[i + 1], normal);
			}
		}
		LispVal result = new LispSingleFloatArray(out, new int[] { out.length });
		s.instance.setSlot(s.meshSlot, result);
		return result;
	}

	private static int emitTriangleVertex(float[] out, int k, float[] v, int p, float[] normal) {
		out[k] = v[p * 3];
		out[k + 1] = v[p * 3 + 1];
		out[k + 2] = v[p * 3 + 2];
		out[k + 3] = normal[0];
		out[k + 4] = normal[1];
		out[k + 5] = normal[2];
		return k + 6;
	}

	/**
	 * {@code geom::%facet-normal}: Newell's method accumulated in {@code double} over
	 * widened {@code single-float} reads, narrowed to a {@code geom:vec3} and then run
	 * through {@code geom::%unit} -- whose {@code linalg:norm} squares the NARROWED
	 * components ({@code emap} writes into a same-width array before {@code sum} reads
	 * them back) and whose {@code linalg:mul} narrows once more on the way out.
	 * @return false when an index is outside the vertex array, which declines the whole
	 * mesh to the defun rather than throwing out of a kernel
	 */
	private static boolean facetNormal(float[] v, int[] idx, float[] out) {
		int m = idx.length;
		int vertexCount = v.length / 3;
		double nx = 0.0;
		double ny = 0.0;
		double nz = 0.0;
		for (int i = 0; i < m; i++) {
			int a = idx[i];
			int b = idx[(i + 1) % m];
			if (a < 0 || b < 0 || a >= vertexCount || b >= vertexCount) {
				return false;
			}
			double ax = v[a * 3];
			double ay = v[a * 3 + 1];
			double az = v[a * 3 + 2];
			double bx = v[b * 3];
			double by = v[b * 3 + 1];
			double bz = v[b * 3 + 2];
			nx = nx + (ay - by) * (az + bz);
			ny = ny + (az - bz) * (ax + bx);
			nz = nz + (ax - bx) * (ay + by);
		}
		float px = (float) nx;
		float py = (float) ny;
		float pz = (float) nz;
		double sum = (float) ((double) px * px);
		sum = sum + (float) ((double) py * py);
		sum = sum + (float) ((double) pz * pz);
		double norm = Math.sqrt(sum);
		double inverse = 1.0 / (norm < 1e-9 ? 1e-9 : norm);
		out[0] = (float) (px * inverse);
		out[1] = (float) (py * inverse);
		out[2] = (float) (pz * inverse);
		return true;
	}

	// --- geom:wireframe --------------------------------------------------------------

	/**
	 * {@code geom:wireframe}'s edge walk: every facet edge once, keyed on the ordered
	 * endpoint pair. The defun collects the keys with {@code push} and then walks the
	 * list, so the segments come out in REVERSE discovery order -- reproduced here rather
	 * than tidied, because the order is what the vertex buffer holds and a renderer's
	 * uploaded bytes are part of the answer.
	 */
	private static @Nullable LispVal wireframe(List<LispVal> args) {
		Solid s = Solid.of(args.get(0));
		if (s == null) {
			return null;
		}
		LispVal cached = s.instance.slot(s.wireSlot);
		if (!(cached instanceof LispNil)) {
			return cached;
		}
		int[][] facets = s.facets();
		if (facets == null) {
			return null;
		}
		float[] v = s.vertices.data();
		int vertexCount = v.length / 3;
		EdgeSet seen = new EdgeSet();
		long[] pairs = new long[64];
		int count = 0;
		for (int[] idx : facets) {
			int m = idx.length;
			for (int i = 0; i < m; i++) {
				int a = idx[i];
				int b = idx[(i + 1) % m];
				if (a < 0 || b < 0 || a >= vertexCount || b >= vertexCount) {
					return null;
				}
				long key = a < b ? ((long) a << 32) | (b & 0xffffffffL) : ((long) b << 32) | (a & 0xffffffffL);
				if (seen.add(key)) {
					if (count == pairs.length) {
						pairs = Arrays.copyOf(pairs, pairs.length * 2);
					}
					pairs[count++] = key;
				}
			}
		}
		float[] out = new float[count * 6];
		int k = 0;
		// The defun's pairs list is the discovery order REVERSED (push), and its dolist
		// walks that.
		for (int i = count - 1; i >= 0; i--) {
			int a = (int) (pairs[i] >>> 32);
			int b = (int) pairs[i];
			out[k] = v[a * 3];
			out[k + 1] = v[a * 3 + 1];
			out[k + 2] = v[a * 3 + 2];
			out[k + 3] = v[b * 3];
			out[k + 4] = v[b * 3 + 1];
			out[k + 5] = v[b * 3 + 2];
			k += 6;
		}
		LispVal result = new LispSingleFloatArray(out, new int[] { out.length });
		s.instance.setSlot(s.wireSlot, result);
		return result;
	}

	/**
	 * An open-addressing set of packed endpoint pairs. A {@code HashSet<Long>} boxes
	 * three million keys for a million-vertex model and is the reason the defun's own
	 * {@code equal} hash table is the slow half of {@code wireframe}.
	 */
	private static final class EdgeSet {

		private long[] keys = new long[1 << 12];

		private boolean[] used = new boolean[1 << 12];

		private int size;

		private boolean add(long key) {
			int mask = this.keys.length - 1;
			int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
			while (this.used[i]) {
				if (this.keys[i] == key) {
					return false;
				}
				i = (i + 1) & mask;
			}
			this.used[i] = true;
			this.keys[i] = key;
			this.size++;
			if (this.size * 2 > this.keys.length) {
				grow();
			}
			return true;
		}

		private void grow() {
			long[] oldKeys = this.keys;
			boolean[] oldUsed = this.used;
			this.keys = new long[oldKeys.length * 2];
			this.used = new boolean[oldUsed.length * 2];
			int mask = this.keys.length - 1;
			for (int j = 0; j < oldKeys.length; j++) {
				if (!oldUsed[j]) {
					continue;
				}
				long key = oldKeys[j];
				int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
				while (this.used[i]) {
					i = (i + 1) & mask;
				}
				this.used[i] = true;
				this.keys[i] = key;
			}
		}

	}

	// --- geom::%vertex-extremes ------------------------------------------------------

	/**
	 * {@code geom::%vertex-extremes} without the intermediate array. The defun poses
	 * every vertex through {@code linalg:matmul} and {@code linalg:add} and then walks
	 * the result for the two corners; a million vertices is a 12 MB array built to be
	 * read once. Each posed coordinate here is the same value the defun would have stored
	 * -- {@code linalg::%la-matmul} accumulates the three products in a {@code double}
	 * from an integer zero and narrows ONCE on the store, and {@code linalg::%la-bcast}
	 * then adds the translation and narrows again -- so the two narrowings happen here in
	 * the same places and the extremes are the same floats.
	 */
	private static @Nullable LispVal vertexExtremes(List<LispVal> args) {
		if (!(args.get(0) instanceof LispSingleFloatArray vertices) || vertices.dims().length != 2
				|| vertices.dims()[1] != 3) {
			return null;
		}
		if (!(args.get(1) instanceof LispSingleFloatArray rotation) || rotation.dims().length != 2
				|| rotation.dims()[0] != 3 || rotation.dims()[1] != 3) {
			return null;
		}
		if (!(args.get(2) instanceof LispSingleFloatArray translation) || translation.dims().length != 1
				|| translation.dims()[0] != 3) {
			return null;
		}
		float[] v = vertices.data();
		float[] r = rotation.data();
		float[] t = translation.data();
		int n = vertices.dims()[0];
		float[] lo = { 1.0e30f, 1.0e30f, 1.0e30f };
		float[] hi = { -1.0e30f, -1.0e30f, -1.0e30f };
		for (int i = 0; i < n; i++) {
			double x = v[i * 3];
			double y = v[i * 3 + 1];
			double z = v[i * 3 + 2];
			for (int j = 0; j < 3; j++) {
				// (transpose rot)[k][j] is rot[j][k], and the defun's accumulator starts
				// at zero and runs k ascending.
				double acc = 0.0;
				acc = acc + x * r[j * 3];
				acc = acc + y * r[j * 3 + 1];
				acc = acc + z * r[j * 3 + 2];
				float posed = (float) acc;
				float world = (float) ((double) posed + t[j]);
				if (world < lo[j]) {
					lo[j] = world;
				}
				if (world > hi[j]) {
					hi[j] = world;
				}
			}
		}
		return new LispCons(new LispSingleFloatArray(lo, new int[] { 3 }),
				new LispSingleFloatArray(hi, new int[] { 3 }));
	}

	// --- the solid's slots -----------------------------------------------------------

	/**
	 * A {@code geom:solid} the kernels can read: the instance, the packed
	 * {@code single-float} {@code (n 3)} vertex array and the slot indices. Anything else
	 * -- a boxed or double-width vertex array, a rank that is not {@code (n 3)}, a class
	 * without these slots -- answers null and declines the call.
	 */
	private static final class Solid {

		private final LispInstance instance;

		private final LispSingleFloatArray vertices;

		private final LispVal facetList;

		private final int meshSlot;

		private final int wireSlot;

		private Solid(LispInstance instance, LispSingleFloatArray vertices, LispVal facetList, int meshSlot,
				int wireSlot) {
			this.instance = instance;
			this.vertices = vertices;
			this.facetList = facetList;
			this.meshSlot = meshSlot;
			this.wireSlot = wireSlot;
		}

		private static @Nullable Solid of(LispVal value) {
			if (!(value instanceof LispInstance instance)) {
				return null;
			}
			int verticesSlot = instance.layout().slotIndex(VERTICES_SLOT);
			int facetsSlot = instance.layout().slotIndex(FACETS_SLOT);
			int meshSlot = instance.layout().slotIndex(MESH_SLOT);
			int wireSlot = instance.layout().slotIndex(WIRE_SLOT);
			if (verticesSlot < 0 || facetsSlot < 0 || meshSlot < 0 || wireSlot < 0) {
				return null;
			}
			if (!(instance.slot(verticesSlot) instanceof LispSingleFloatArray vertices) || vertices.dims().length != 2
					|| vertices.dims()[1] != 3) {
				return null;
			}
			return new Solid(instance, vertices, instance.slot(facetsSlot), meshSlot, wireSlot);
		}

		/** The facet list as index arrays, or null when any facet is not one. */
		private int @Nullable [][] facets() {
			List<int[]> out = new ArrayList<>();
			LispVal rest = this.facetList;
			while (rest instanceof LispCons cons) {
				List<Integer> loop = new ArrayList<>(4);
				LispVal inner = cons.car();
				while (inner instanceof LispCons element) {
					if (!(element.car() instanceof LispInteger index) || index.value() > Integer.MAX_VALUE
							|| index.value() < Integer.MIN_VALUE) {
						return null;
					}
					loop.add((int) index.value());
					inner = element.cdr();
				}
				if (!(inner instanceof LispNil) || loop.size() < 3) {
					return null;
				}
				int[] indices = new int[loop.size()];
				for (int i = 0; i < indices.length; i++) {
					indices[i] = loop.get(i);
				}
				out.add(indices);
				rest = cons.cdr();
			}
			return rest instanceof LispNil ? out.toArray(new int[0][]) : null;
		}

	}

	private static final LispVal NIL = LispNil.INSTANCE;

	private static LispVal list(List<LispVal> items) {
		LispVal out = NIL;
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new LispCons(items.get(i), out);
		}
		return out;
	}

}
