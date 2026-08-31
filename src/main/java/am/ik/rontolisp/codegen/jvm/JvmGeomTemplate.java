package am.ik.rontolisp.codegen.jvm;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The {@code geom} kernel bridge injected into a compiled {@code .class} whose program
 * calls one of the four members a model FILE spends its whole load time in:
 * {@code geom:read-obj}, {@code geom:mesh}, {@code geom:wireframe} and the
 * {@code geom::%vertex-extremes} behind {@code geom:bounds} and
 * {@code geom::%model-extent}.
 *
 * <p>
 * The compiled sibling of {@code eval/GeomKernels}, and a COPY of it rather than a call
 * into it: like {@link JvmBlasTemplate} and {@link JvmSimdVectorTemplate} this class's
 * bytecode is read from the classpath by {@link JvmGeomRuntimeBuilder}, renamed into the
 * generated program's own package, base64-embedded and defined at first use, so its bytes
 * must stand alone.
 *
 * <h2>Bit-identity, not approximation</h2>
 *
 * Every kernel here is PARTIAL: it answers {@code null} for an input it declines, and
 * {@link JvmGeomKernelCompiler}'s emitted call site then runs the spliced
 * {@code geom.lisp} defun over the same temps. That is the {@code linalg:} chain's
 * protocol, but the licence is a different one -- these kernels are not behind a flag, so
 * a step that rounds differently would silently change what a program prints. Each one is
 * therefore the DEFUN transcribed, in the JVM's own value representation:
 * {@code %scan-number}'s mantissa accumulated in {@code double} and scaled once by
 * {@code Math.pow}, Newell's normal accumulated in {@code double} over widened
 * {@code single-float} reads, {@code geom::%unit}'s narrow-then-widen chain, and the
 * extremes walk narrowing exactly where {@code linalg::%la-matmul} and
 * {@code linalg::%la-bcast} narrow. It is the same transcription {@code eval/GeomKernels}
 * makes for the interpreter, so the two stay in lockstep; change them together.
 *
 * <h2>The representation</h2>
 *
 * <ul>
 * <li>a packed {@code single-float} array is a bare {@code float[]} carrying the header
 * {@code [rank, dim_0, ..., dim_{rank-1}]}, so its elements start at {@code 1 + rank};
 * <li>an instance is {@code Object[]{ String[] layout, v1, ..., vn }}, the layout being
 * <code>{tag, printName, "S"|"C", slot0, ...}</code>;
 * <li>a cons is {@code Object[]{car, cdr}} and nil is {@code null};
 * <li>an integer is a {@code Long}, and a string carries its frame quotes.
 * </ul>
 *
 * <p>
 * Design constraints (as for {@link JavaBridgeTemplate}): no nested classes or records --
 * each would be a second class file the single-blob injection cannot carry -- and no
 * references to any other rontolisp class.
 */
final class JvmGeomTemplate {

	private JvmGeomTemplate() {
	}

	/** The slot names {@code geom:solid} declares, read by name rather than by index. */
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

	private static final long EDGE_HASH = 0x9E3779B97F4A7C15L;

	// --- geom:read-obj -----------------------------------------------------------------

	/**
	 * {@code geom:read-obj}'s scan, in Java. Answers {@code Object[]{vertices, facets}}
	 * -- the packed {@code (n 3)} {@code single-float} array and the list of index loops
	 * -- which the call site hands to the LISP {@code geom::%solid-of-vertices} along
	 * with the reader's own {@code :color} / {@code :label} tail, so the colour default
	 * and the transform still live there. Answers {@code null} for anything the
	 * transcription does not cover, and the whole {@code geom:read-obj} defun then runs.
	 * @param pathValue the compiled {@code path} argument (a framed string, or something
	 * this declines)
	 * @return the two arguments of {@code geom::%solid-of-vertices}, or null
	 */
	static @Nullable Object geomReadObj(@Nullable Object pathValue) {
		String path = unframe(pathValue);
		if (path == null) {
			return null;
		}
		float[] points = new float[3 * 1024];
		int coordinates = 0;
		List<Object> facets = new ArrayList<>();
		double[] scan = new double[2];
		long nv = 0;
		try (BufferedReader in = Files.newBufferedReader(Path.of(path))) {
			String line;
			while ((line = in.readLine()) != null) {
				int n = line.length();
				if (n <= 1) {
					continue;
				}
				char c0 = line.charAt(0);
				char c1 = line.charAt(1);
				if (c0 == 'v' && c1 == ' ') {
					if (!scanNumber(line, 2, n, scan)) {
						return null;
					}
					double x = scan[0];
					if (!scanNumber(line, (int) scan[1], n, scan)) {
						return null;
					}
					double y = scan[0];
					if (!scanNumber(line, (int) scan[1], n, scan)) {
						// A vertex line short of three numbers: the defun signals its
						// own error, naming the path.
						return null;
					}
					if (coordinates + 3 > points.length) {
						points = Arrays.copyOf(points, points.length * 2);
					}
					points[coordinates] = (float) x;
					points[coordinates + 1] = (float) y;
					points[coordinates + 2] = (float) scan[0];
					coordinates += 3;
					nv++;
				}
				else if (c0 == 'f' && c1 == ' ') {
					List<Object> loop = new ArrayList<>(4);
					int p = 2;
					while (scanNumber(line, p, n, scan)) {
						p = (int) scan[1];
						double floored = Math.floor(scan[0]);
						if (!(floored >= -MAX_INDEX && floored <= MAX_INDEX)) {
							// (floor x) answers a BIGNUM out here, which is not what a
							// compiled Long is. Decline rather than narrow.
							return null;
						}
						long i = (long) floored;
						loop.add(Long.valueOf(i < 0 ? nv + i : i - 1));
						// Read past the /vt/vn of this token.
						while (p < n && line.charAt(p) == '/') {
							p = p + 1;
							if (scanNumber(line, p, n, scan)) {
								p = (int) scan[1];
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
		float[] vertices = new float[3 + coordinates];
		vertices[0] = 2.0f;
		vertices[1] = coordinates / 3;
		vertices[2] = 3.0f;
		System.arraycopy(points, 0, vertices, 3, coordinates);
		return new Object[] { vertices, list(facets) };
	}

	/**
	 * The content of a compiled Lisp string. A symbol shares the representation and is
	 * told apart by the leading frame quote ({@code .kb/core-representation.md}), so
	 * anything else -- a symbol, a pathname instance, nil -- declines here.
	 */
	private static @Nullable String unframe(@Nullable Object value) {
		if (!(value instanceof String s) || s.length() < 2 || s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"') {
			return null;
		}
		return s.substring(1, s.length() - 1);
	}

	/**
	 * {@code geom::%scan-number} transcribed: leading blanks skipped, the syntax
	 * {@code [+-]d*[.d*][eE[+-]d*]}, the mantissa accumulated in {@code double} across
	 * the decimal point and the whole thing scaled once by
	 * {@code (expt 10.0 (- (* es e) frac))} -- which is {@code Math.pow} for a float base
	 * on every backend, so the same two multiplications happen in the same order here.
	 * @param s the line
	 * @param i where to start
	 * @param n the line length
	 * @param out {@code out[0]} receives the value and {@code out[1]} the index just past
	 * it (the defun's {@code (value . next)} cons, without the allocation)
	 * @return whether a number was read
	 */
	private static boolean scanNumber(String s, int i, int n, double[] out) {
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
		out[0] = neg ? -v : v;
		out[1] = j;
		return true;
	}

	// --- geom:mesh ---------------------------------------------------------------------

	/**
	 * {@code geom:mesh}'s fan triangulation with a Newell normal per facet, filling the
	 * same {@code (* tris 18)} float array in the same order. The cache slot is read and
	 * written exactly where the defun reads and writes it.
	 * @param solidValue the compiled {@code geom:solid}
	 * @return the packed rank-1 mesh array, or null when this declines
	 */
	static @Nullable Object geomMesh(@Nullable Object solidValue) {
		if (!(solidValue instanceof Object[] instance) || instance.length == 0
				|| !(instance[0] instanceof String[] layout)) {
			return null;
		}
		int verticesSlot = slotIndex(layout, VERTICES_SLOT);
		int facetsSlot = slotIndex(layout, FACETS_SLOT);
		int meshSlot = slotIndex(layout, MESH_SLOT);
		if (verticesSlot < 0 || facetsSlot < 0 || meshSlot < 0 || 1 + meshSlot >= instance.length) {
			return null;
		}
		Object cached = instance[1 + meshSlot];
		if (cached != null) {
			return cached;
		}
		float[] v = vertexArray(instance[1 + verticesSlot]);
		if (v == null) {
			return null;
		}
		int[][] facets = facetLoops(instance[1 + facetsSlot]);
		if (facets == null) {
			return null;
		}
		long tris = 0;
		for (int[] facet : facets) {
			tris += facet.length - 2;
		}
		if (2 + tris * 18 > Integer.MAX_VALUE) {
			return null;
		}
		float[] out = new float[(int) (2 + tris * 18)];
		out[0] = 1.0f;
		out[1] = tris * 18;
		float[] normal = new float[3];
		int k = 2;
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
		instance[1 + meshSlot] = out;
		return out;
	}

	private static int emitTriangleVertex(float[] out, int k, float[] v, int p, float[] normal) {
		out[k] = v[3 + p * 3];
		out[k + 1] = v[3 + p * 3 + 1];
		out[k + 2] = v[3 + p * 3 + 2];
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
	 * @param v the packed {@code (n 3)} vertex array
	 * @param idx the facet's index loop
	 * @param out the three unit-normal components
	 * @return false when an index is outside the vertex array, which declines the whole
	 * mesh to the defun rather than throwing out of a kernel
	 */
	private static boolean facetNormal(float[] v, int[] idx, float[] out) {
		int m = idx.length;
		int vertexCount = (int) v[1];
		double nx = 0.0;
		double ny = 0.0;
		double nz = 0.0;
		for (int i = 0; i < m; i++) {
			int a = idx[i];
			int b = idx[(i + 1) % m];
			if (a < 0 || b < 0 || a >= vertexCount || b >= vertexCount) {
				return false;
			}
			double ax = v[3 + a * 3];
			double ay = v[3 + a * 3 + 1];
			double az = v[3 + a * 3 + 2];
			double bx = v[3 + b * 3];
			double by = v[3 + b * 3 + 1];
			double bz = v[3 + b * 3 + 2];
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

	// --- geom:wireframe ----------------------------------------------------------------

	/**
	 * {@code geom:wireframe}'s edge walk: every facet edge once, keyed on the ordered
	 * endpoint pair, over an open-addressing {@code long} set instead of the defun's
	 * {@code equal} hash table of conses. The defun collects the keys with {@code push}
	 * and then walks the list, so the segments come out in REVERSE discovery order --
	 * reproduced here rather than tidied, because the order is what the vertex buffer
	 * holds and a renderer's uploaded bytes are part of the answer.
	 * @param solidValue the compiled {@code geom:solid}
	 * @return the packed rank-1 segment array, or null when this declines
	 */
	static @Nullable Object geomWireframe(@Nullable Object solidValue) {
		if (!(solidValue instanceof Object[] instance) || instance.length == 0
				|| !(instance[0] instanceof String[] layout)) {
			return null;
		}
		int verticesSlot = slotIndex(layout, VERTICES_SLOT);
		int facetsSlot = slotIndex(layout, FACETS_SLOT);
		int wireSlot = slotIndex(layout, WIRE_SLOT);
		if (verticesSlot < 0 || facetsSlot < 0 || wireSlot < 0 || 1 + wireSlot >= instance.length) {
			return null;
		}
		Object cached = instance[1 + wireSlot];
		if (cached != null) {
			return cached;
		}
		float[] v = vertexArray(instance[1 + verticesSlot]);
		if (v == null) {
			return null;
		}
		int[][] facets = facetLoops(instance[1 + facetsSlot]);
		if (facets == null) {
			return null;
		}
		int vertexCount = (int) v[1];
		long[] keys = new long[1 << 12];
		boolean[] used = new boolean[1 << 12];
		int size = 0;
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
				int mask = keys.length - 1;
				int slot = (int) ((key * EDGE_HASH) >>> 32) & mask;
				boolean seen = false;
				while (used[slot]) {
					if (keys[slot] == key) {
						seen = true;
						break;
					}
					slot = (slot + 1) & mask;
				}
				if (seen) {
					continue;
				}
				used[slot] = true;
				keys[slot] = key;
				size++;
				if (count == pairs.length) {
					pairs = Arrays.copyOf(pairs, pairs.length * 2);
				}
				pairs[count++] = key;
				if (size * 2 > keys.length) {
					long[] oldKeys = keys;
					boolean[] oldUsed = used;
					keys = new long[oldKeys.length * 2];
					used = new boolean[oldUsed.length * 2];
					int grown = keys.length - 1;
					for (int j = 0; j < oldKeys.length; j++) {
						if (!oldUsed[j]) {
							continue;
						}
						long moved = oldKeys[j];
						int p = (int) ((moved * EDGE_HASH) >>> 32) & grown;
						while (used[p]) {
							p = (p + 1) & grown;
						}
						used[p] = true;
						keys[p] = moved;
					}
				}
			}
		}
		float[] out = new float[2 + count * 6];
		out[0] = 1.0f;
		out[1] = count * 6;
		int k = 2;
		// The defun's pairs list is the discovery order REVERSED (push), and its dolist
		// walks that.
		for (int i = count - 1; i >= 0; i--) {
			int a = (int) (pairs[i] >>> 32);
			int b = (int) pairs[i];
			out[k] = v[3 + a * 3];
			out[k + 1] = v[3 + a * 3 + 1];
			out[k + 2] = v[3 + a * 3 + 2];
			out[k + 3] = v[3 + b * 3];
			out[k + 4] = v[3 + b * 3 + 1];
			out[k + 5] = v[3 + b * 3 + 2];
			k += 6;
		}
		instance[1 + wireSlot] = out;
		return out;
	}

	// --- geom::%vertex-extremes --------------------------------------------------------

	/**
	 * {@code geom::%vertex-extremes} without the intermediate array. The defun poses
	 * every vertex through {@code linalg:matmul} and {@code linalg:add} and then walks
	 * the result for the two corners; a million vertices is a 12 MB array built to be
	 * read once. Each posed coordinate here is the same value the defun would have stored
	 * -- {@code linalg::%la-matmul} accumulates the three products in a {@code double}
	 * from an integer zero and narrows ONCE on the store, and {@code linalg::%la-bcast}
	 * then adds the translation and narrows again -- so the two narrowings happen here in
	 * the same places and the extremes are the same floats.
	 * @param verticesValue the packed {@code (n 3)} vertex array
	 * @param rotationValue the packed {@code (3 3)} rotation
	 * @param translationValue the packed 3-vector translation
	 * @return the {@code (lo . hi)} cons, or null when this declines
	 */
	static @Nullable Object geomVertexExtremes(@Nullable Object verticesValue, @Nullable Object rotationValue,
			@Nullable Object translationValue) {
		float[] v = vertexArray(verticesValue);
		if (v == null) {
			return null;
		}
		if (!(rotationValue instanceof float[] r) || (int) r[0] != 2 || (int) r[1] != 3 || (int) r[2] != 3) {
			return null;
		}
		if (!(translationValue instanceof float[] t) || (int) t[0] != 1 || (int) t[1] != 3) {
			return null;
		}
		int n = (int) v[1];
		float[] lo = { 1.0f, 3.0f, 1.0e30f, 1.0e30f, 1.0e30f };
		float[] hi = { 1.0f, 3.0f, -1.0e30f, -1.0e30f, -1.0e30f };
		for (int i = 0; i < n; i++) {
			double x = v[3 + i * 3];
			double y = v[3 + i * 3 + 1];
			double z = v[3 + i * 3 + 2];
			for (int j = 0; j < 3; j++) {
				// (transpose rot)[k][j] is rot[j][k], and the defun's accumulator starts
				// at zero and runs k ascending.
				double acc = 0.0;
				acc = acc + x * r[3 + j * 3];
				acc = acc + y * r[3 + j * 3 + 1];
				acc = acc + z * r[3 + j * 3 + 2];
				float posed = (float) acc;
				float world = (float) ((double) posed + t[2 + j]);
				if (world < lo[2 + j]) {
					lo[2 + j] = world;
				}
				if (world > hi[2 + j]) {
					hi[2 + j] = world;
				}
			}
		}
		return new Object[] { lo, hi };
	}

	// --- the solid's slots -------------------------------------------------------------

	/**
	 * The index of a named slot in an instance layout
	 * (<code>{tag, printName, kind, slot0, ...}</code>), or -1 when the class has no such
	 * slot -- which declines the call.
	 */
	private static int slotIndex(String[] layout, String name) {
		for (int i = 3; i < layout.length; i++) {
			if (name.equals(layout[i])) {
				return i - 3;
			}
		}
		return -1;
	}

	/**
	 * A packed {@code (n 3)} {@code single-float} vertex array, or null for anything else
	 * -- a boxed or double-width array, a rank that is not {@code (n 3)}.
	 */
	private static float @Nullable [] vertexArray(@Nullable Object value) {
		if (!(value instanceof float[] v) || v.length < 3 || (int) v[0] != 2 || (int) v[2] != 3) {
			return null;
		}
		return v;
	}

	/** The facet list as index arrays, or null when any facet is not one. */
	private static int @Nullable [][] facetLoops(@Nullable Object facetList) {
		List<int[]> out = new ArrayList<>();
		Object rest = facetList;
		while (rest instanceof Object[] cons && cons.length == 2) {
			List<Long> loop = new ArrayList<>(4);
			Object inner = cons[0];
			while (inner instanceof Object[] element && element.length == 2) {
				if (!(element[0] instanceof Long index) || index.longValue() > Integer.MAX_VALUE
						|| index.longValue() < Integer.MIN_VALUE) {
					return null;
				}
				loop.add(index);
				inner = element[1];
			}
			if (inner != null || loop.size() < 3) {
				return null;
			}
			int[] indices = new int[loop.size()];
			for (int i = 0; i < indices.length; i++) {
				indices[i] = loop.get(i).intValue();
			}
			out.add(indices);
			rest = cons[1];
		}
		return rest == null ? out.toArray(new int[0][]) : null;
	}

	private static @Nullable Object list(List<Object> items) {
		Object out = null;
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new Object[] { items.get(i), out };
		}
		return out;
	}

}
