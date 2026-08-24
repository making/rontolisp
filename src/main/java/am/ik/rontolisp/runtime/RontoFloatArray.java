package am.ik.rontolisp.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Java-side handle on a rontolisp <strong>packed float array</strong> — the boundary
 * type the {@code :float-vector} and {@code :float-matrix} designators of
 * {@code rontolisp:jvm-export} carry ({@code .kb/jvm-export.md}).
 *
 * <p>
 * <strong>Why a handle and not a {@code double[]}.</strong> A rontolisp packed float
 * array is a bare {@code double[]} (or {@code float[]}) carrying an embedded
 * {@code [rank, dim_0..dim_{rank-1}, e_0..e_n]} header, so a plain Java array is
 * <em>not</em> one: handing {@code new double[]{3, 4}} to a kernel would compile and
 * answer a wrong number. Marshalling a plain array at every call would be safe but costs
 * about ten times the kernel it feeds — measured at 2.67 ms/call against a 0.27 ms kernel
 * and a 0.90 ms hand-written Java loop, i.e. a 3x LOSS where the kernel is a 3.3x win.
 * This handle holds the packed representation across calls: {@link #of(double[], int...)}
 * copies ONCE, {@link #toArray()} copies out once, and every call in between hands the
 * kernel the array it already has — measured at the kernel's own floor.
 *
 * <p>
 * <strong>Aliasing is the contract.</strong> A handle a kernel <em>returns</em> aliases
 * the very array the Lisp side holds; {@link #set(int, double)} through the handle is
 * visible to a Lisp closure over the same array, and a Lisp write is visible through
 * {@link #get(int)}. Nothing is defensively copied — the copy is the 10x this type exists
 * to avoid. {@link #of(double[], int...)} and {@link #toArray()} are the two points where
 * a copy happens, and they are the two points where the caller asked for one.
 *
 * <p>
 * <strong>Every width, every rank.</strong> The element width is carried by the packed
 * representation itself and reported by {@link #width()}; rank is carried by the header,
 * so a matrix is this same type with a rank-2 {@link #dims()} rather than a second class.
 * Accessors read and write in {@code double} whatever the storage width, exactly as
 * {@code aref} does on every backend.
 *
 * <p>
 * This class imports nothing outside {@code java.base} and is the only type a consumer of
 * a compiled rontolisp library needs beyond the library's own class.
 */
public final class RontoFloatArray {

	/**
	 * The element width of a packed float array. The set grows with the representation
	 * (rontolisp's packed arrays are double-float and single-float today), so a caller
	 * must never assume it has exactly two members.
	 */
	public enum Width {

		/** {@code double-float}: 64-bit elements, stored in a {@code double[]}. */
		DOUBLE_FLOAT("double-float"),

		/** {@code single-float}: 32-bit elements, stored in a {@code float[]}. */
		SINGLE_FLOAT("single-float");

		private final String lispName;

		Width(String lispName) {
			this.lispName = lispName;
		}

		/**
		 * The Common Lisp {@code :element-type} spelling of this width.
		 * @return the element-type name, e.g. {@code "double-float"}
		 */
		public String lispName() {
			return this.lispName;
		}

	}

	/**
	 * The residency hooks of a class that produced handles, resolved once per class. A
	 * {@code --gpu} compiled class carries a {@code _gpuMaterialize} /
	 * {@code _gpuWritten} guard pair ({@code .kb/gpu.md}); every other class resolves to
	 * {@link #NO_RESIDENCY} and pays one reference comparison per access.
	 */
	private static final ConcurrentHashMap<Class<?>, MethodHandle[]> RESIDENCY = new ConcurrentHashMap<>();

	/** The marker for "this class has no device residency", i.e. no {@code --gpu}. */
	private static final MethodHandle[] NO_RESIDENCY = new MethodHandle[0];

	/** The header slot holding the rank; the dimensions follow, then the elements. */
	private static final int RANK_SLOT = 0;

	private final Object packed;

	/**
	 * The {@code {materialize, written}} guards of the class that handed this handle out,
	 * or {@link #NO_RESIDENCY}. Mutable because a handle a Java caller built can be
	 * <em>passed into</em> a {@code --gpu} kernel, which may leave it resident.
	 */
	private volatile MethodHandle[] residency = NO_RESIDENCY;

	private RontoFloatArray(Object packed) {
		this.packed = packed;
	}

	/**
	 * A fresh rank-1 packed double-float array holding a copy of the given elements.
	 * @param data the elements (copied)
	 * @param dims the dimensions; omit them for a rank-1 array of {@code data.length}
	 * @return the handle on the fresh packed array
	 * @throws IllegalArgumentException if the dimensions do not multiply to
	 * {@code data.length}
	 */
	public static RontoFloatArray of(double[] data, int... dims) {
		int[] shape = shapeOf(data.length, dims);
		double[] packed = new double[1 + shape.length + data.length];
		packed[RANK_SLOT] = shape.length;
		for (int k = 0; k < shape.length; k++) {
			packed[1 + k] = shape[k];
		}
		System.arraycopy(data, 0, packed, 1 + shape.length, data.length);
		return new RontoFloatArray(packed);
	}

	/**
	 * A fresh rank-1 packed single-float array holding a copy of the given elements.
	 * @param data the elements (copied)
	 * @param dims the dimensions; omit them for a rank-1 array of {@code data.length}
	 * @return the handle on the fresh packed array
	 * @throws IllegalArgumentException if the dimensions do not multiply to
	 * {@code data.length}
	 */
	public static RontoFloatArray of(float[] data, int... dims) {
		int[] shape = shapeOf(data.length, dims);
		float[] packed = new float[1 + shape.length + data.length];
		packed[RANK_SLOT] = shape.length;
		for (int k = 0; k < shape.length; k++) {
			packed[1 + k] = shape[k];
		}
		System.arraycopy(data, 0, packed, 1 + shape.length, data.length);
		return new RontoFloatArray(packed);
	}

	/**
	 * A fresh zero-filled packed array of the given width and shape — the destination a
	 * caller supplies to an export that writes into one instead of allocating
	 * ({@code vec:}'s {@code -into} shape, {@code .kb/vec.md}).
	 * @param width the element width
	 * @param dims the dimensions, at least one
	 * @return the handle on the fresh packed array
	 * @throws IllegalArgumentException if no dimension is given or one is negative
	 */
	public static RontoFloatArray zeros(Width width, int... dims) {
		if (dims.length == 0) {
			throw new IllegalArgumentException("RontoFloatArray.zeros needs at least one dimension");
		}
		int total = 1;
		for (int dim : dims) {
			if (dim < 0) {
				throw new IllegalArgumentException("RontoFloatArray.zeros: negative dimension " + dim);
			}
			total *= dim;
		}
		return switch (width) {
			case DOUBLE_FLOAT -> of(new double[total], dims);
			case SINGLE_FLOAT -> of(new float[total], dims);
		};
	}

	/**
	 * A handle <strong>aliasing</strong> an existing packed array — the inverse of
	 * {@link #packed()}, and the raw form a caller only needs when it already holds the
	 * packed representation itself.
	 * @param packed a {@code double[]} or {@code float[]} carrying the dimension header
	 * @return a handle over that very array, with no copy
	 * @throws IllegalArgumentException if the argument is not a packed float array
	 */
	public static RontoFloatArray wrap(Object packed) {
		checkPacked(packed);
		return new RontoFloatArray(packed);
	}

	/**
	 * The packed representation this handle holds, aliased rather than copied. It is the
	 * array with its dimension header, not the elements — {@link #toArray()} is the
	 * elements.
	 * @return the {@code double[]} or {@code float[]} backing this handle
	 */
	public Object packed() {
		return this.packed;
	}

	/**
	 * The element width of the packed representation.
	 * @return the width
	 */
	public Width width() {
		return widthOf(this.packed);
	}

	/**
	 * The number of dimensions, read from the header.
	 * @return the rank (1 for a vector, 2 for a matrix)
	 */
	public int rank() {
		return headerAt(this.packed, RANK_SLOT);
	}

	/**
	 * The dimensions, read from the header.
	 * @return a fresh array of the dimensions, one per rank
	 */
	public int[] dims() {
		int rank = rank();
		int[] dims = new int[rank];
		for (int k = 0; k < rank; k++) {
			dims[k] = headerAt(this.packed, 1 + k);
		}
		return dims;
	}

	/**
	 * One dimension, read from the header.
	 * @param axis the axis, {@code 0 <= axis < rank()}
	 * @return the length of that axis
	 */
	public int dim(int axis) {
		int rank = rank();
		if (axis < 0 || axis >= rank) {
			throw new IndexOutOfBoundsException("axis " + axis + " of a rank-" + rank + " packed float array");
		}
		return headerAt(this.packed, 1 + axis);
	}

	/**
	 * The total number of elements, i.e. the product of {@link #dims()}.
	 * @return the element count
	 */
	public int size() {
		int rank = rank();
		int total = 1;
		for (int k = 0; k < rank; k++) {
			total *= headerAt(this.packed, 1 + k);
		}
		return total;
	}

	/**
	 * One element by its row-major flat index, widened to {@code double} whatever the
	 * storage width. For a rank-1 array this is the subscript.
	 * @param index the flat index, {@code 0 <= index < size()}
	 * @return the element
	 */
	public double get(int index) {
		checkIndex(index);
		Object storage = read();
		int offset = 1 + headerAt(storage, RANK_SLOT) + index;
		return storage instanceof double[] doubles ? doubles[offset] : ((float[]) storage)[offset];
	}

	/**
	 * One element of a rank-2 array by its subscripts.
	 * @param row the first subscript
	 * @param column the second subscript
	 * @return the element
	 */
	public double get(int row, int column) {
		return get(flatIndex(row, column));
	}

	/**
	 * Stores one element by its row-major flat index, narrowed to the storage width.
	 * <strong>The store is visible to the Lisp side</strong> — a handle a kernel returned
	 * aliases the array the program holds.
	 * @param index the flat index, {@code 0 <= index < size()}
	 * @param value the value to store
	 */
	public void set(int index, double value) {
		checkIndex(index);
		Object storage = write();
		int offset = 1 + headerAt(storage, RANK_SLOT) + index;
		if (storage instanceof double[] doubles) {
			doubles[offset] = value;
		}
		else {
			((float[]) storage)[offset] = (float) value;
		}
	}

	/**
	 * Stores one element of a rank-2 array by its subscripts.
	 * @param row the first subscript
	 * @param column the second subscript
	 * @param value the value to store
	 */
	public void set(int row, int column, double value) {
		set(flatIndex(row, column), value);
	}

	/**
	 * The elements, copied out into a fresh {@code double[]} in row-major order (widened
	 * from {@code float} storage). This is a copy — the one the caller asked for.
	 * @return the elements
	 */
	public double[] toArray() {
		Object storage = read();
		int offset = 1 + headerAt(storage, RANK_SLOT);
		int total = size();
		double[] out = new double[total];
		if (storage instanceof double[] doubles) {
			System.arraycopy(doubles, offset, out, 0, total);
		}
		else {
			float[] floats = (float[]) storage;
			for (int k = 0; k < total; k++) {
				out[k] = floats[offset + k];
			}
		}
		return out;
	}

	/**
	 * The elements, copied out into a fresh {@code float[]} in row-major order (narrowed
	 * from {@code double} storage).
	 * @return the elements
	 */
	public float[] toFloatArray() {
		Object storage = read();
		int offset = 1 + headerAt(storage, RANK_SLOT);
		int total = size();
		float[] out = new float[total];
		if (storage instanceof float[] floats) {
			System.arraycopy(floats, offset, out, 0, total);
		}
		else {
			double[] doubles = (double[]) storage;
			for (int k = 0; k < total; k++) {
				out[k] = (float) doubles[offset + k];
			}
		}
		return out;
	}

	@Override
	public String toString() {
		return "RontoFloatArray[" + width().lispName() + " " + Arrays.toString(dims()) + "]";
	}

	// --- the compiler seam (RontoBoundary), not part of the caller-facing API ---

	/**
	 * Records the class that handed this handle out, so a host read can consult that
	 * class's {@code --gpu} residency guards. Called by {@code RontoBoundary} in both
	 * directions: a handle a Java caller built adopts the owner the first time it is
	 * passed INTO an export, because a kernel may leave it device-resident.
	 */
	void adopt(Class<?> owner) {
		if (this.residency == NO_RESIDENCY) {
			this.residency = RESIDENCY.computeIfAbsent(owner, RontoFloatArray::lookupResidency);
		}
	}

	/** Throws unless the given object is a packed float array of a known width. */
	static void checkPacked(Object packed) {
		if (!(packed instanceof double[]) && !(packed instanceof float[])) {
			throw new IllegalArgumentException(
					"not a packed float array: " + (packed == null ? "null" : packed.getClass().getName()));
		}
		int length = packed instanceof double[] doubles ? doubles.length : ((float[]) packed).length;
		if (length < 1) {
			throw new IllegalArgumentException("not a packed float array: it carries no dimension header");
		}
		int rank = headerAt(packed, RANK_SLOT);
		// A --gpu lazy result is the header ALONE (.kb/gpu.md, "A lazy result allocates
		// no host array"), so the elements are NOT required to be present here.
		if (rank < 1 || length < 1 + rank) {
			throw new IllegalArgumentException("not a packed float array: its dimension header states rank " + rank);
		}
	}

	// --- internals ---

	private static Width widthOf(Object packed) {
		if (packed instanceof double[]) {
			return Width.DOUBLE_FLOAT;
		}
		if (packed instanceof float[]) {
			return Width.SINGLE_FLOAT;
		}
		throw new IllegalStateException("unknown packed float-array width: " + packed.getClass().getName());
	}

	private static int headerAt(Object packed, int slot) {
		return packed instanceof double[] doubles ? (int) doubles[slot] : (int) ((float[]) packed)[slot];
	}

	private static int[] shapeOf(int length, int[] dims) {
		if (dims.length == 0) {
			return new int[] { length };
		}
		int total = 1;
		for (int dim : dims) {
			if (dim < 0) {
				throw new IllegalArgumentException("RontoFloatArray: negative dimension " + dim);
			}
			total *= dim;
		}
		if (total != length) {
			throw new IllegalArgumentException("RontoFloatArray: " + Arrays.toString(dims) + " needs " + total
					+ " elements, but " + length + " were given");
		}
		return dims.clone();
	}

	private void checkIndex(int index) {
		int total = size();
		if (index < 0 || index >= total) {
			throw new IndexOutOfBoundsException(
					"index " + index + " of a packed float array of " + total + " elements");
		}
	}

	private int flatIndex(int row, int column) {
		if (rank() != 2) {
			throw new IllegalStateException("two subscripts on a rank-" + rank() + " packed float array");
		}
		int columns = dim(1);
		if (row < 0 || row >= dim(0) || column < 0 || column >= columns) {
			throw new IndexOutOfBoundsException(
					"[" + row + ", " + column + "] of a " + Arrays.toString(dims()) + " packed float array");
		}
		return row * columns + column;
	}

	/**
	 * The storage a host READ must read: under {@code --gpu} a result the device still
	 * holds the only copy of comes home first, and the reader reads what the guard
	 * ANSWERS — the array itself, or a lazy result stub's backing ({@code .kb/gpu.md}).
	 * Without {@code --gpu} this is the array.
	 */
	private Object read() {
		MethodHandle[] hooks = this.residency;
		return hooks == NO_RESIDENCY ? this.packed : invokeGuard(hooks[0]);
	}

	/**
	 * The storage a host WRITE must write into — the other half of {@link #read()}: a
	 * device copy that was the authoritative one comes home and is dropped, so the store
	 * lands on the array's real bytes.
	 */
	private Object write() {
		MethodHandle[] hooks = this.residency;
		return hooks == NO_RESIDENCY ? this.packed : invokeGuard(hooks[1]);
	}

	private Object invokeGuard(MethodHandle guard) {
		try {
			return (Object) guard.invokeExact(this.packed);
		}
		catch (RuntimeException | Error ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new IllegalStateException("the --gpu residency guard failed", ex);
		}
	}

	/**
	 * Resolves a class's {@code --gpu} residency guards, or {@link #NO_RESIDENCY} when it
	 * has none. Both guards are emitted private (they are the generated class's own
	 * machinery), so they are unreflected rather than looked up publicly.
	 */
	private static MethodHandle[] lookupResidency(Class<?> owner) {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			Method materialize = owner.getDeclaredMethod("_gpuMaterialize", Object.class);
			Method written = owner.getDeclaredMethod("_gpuWritten", Object.class);
			materialize.setAccessible(true);
			written.setAccessible(true);
			return new MethodHandle[] { lookup.unreflect(materialize), lookup.unreflect(written) };
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			return NO_RESIDENCY;
		}
	}

}
