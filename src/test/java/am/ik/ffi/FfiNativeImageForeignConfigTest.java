package am.ik.ffi;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.NativeImageDowncalls;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the native-image foreign registration of {@code am.ik.ffi} against the runtime
 * itself, the way {@code ObjcNativeImageForeignConfigTest} pins {@code am.ik.objc} --
 * with one difference in kind: {@code cffi:defcfun} invents shapes at RUN time, in the
 * user's program, so no closed table of call sites can be complete. What makes a finite
 * registration possible is {@link FfiRuntime}'s carrier canonicalisation (every narrow
 * integer travels as {@code jlong}, every pointer as {@code void*}), which collapses the
 * shape space to four carriers per parameter; the checked-in grid then covers the small
 * arities outright.
 *
 * <h2>What the grid covers</h2>
 *
 * Downcalls, every one registered with {@code captureCallState} (the {@code errno} option
 * every {@link FfiRuntime#call} handle carries):
 * <ul>
 * <li>every combination of {@code void*}/{@code jlong} arguments at arity 0-6,</li>
 * <li>every combination of {@code void*}/{@code jlong}/{@code jdouble} at arity 1-4,</li>
 * <li>every combination of all four carriers (with {@code jfloat}) at arity 1-2,</li>
 * <li>each at every return carrier ({@code void}, {@code jlong}, {@code void*},
 * {@code jdouble}, {@code jfloat}),</li>
 * </ul>
 * plus the two capture-free shapes the runtime's own constructor binds ({@code malloc}
 * and {@code free}). Upcalls: {@code void*}/{@code jlong} arguments at arity 0-4 and
 * {@code void*}/{@code jlong}/{@code jdouble} at arity 1-2, at the return carriers
 * {@code void}/{@code jlong}/{@code void*}/{@code jdouble}. A shape outside the grid -- a
 * seventh-plus integer argument left narrow, a variadic call, a struct by value --
 * SIGNALS in the binary with the one metadata entry that would register it (see
 * {@code FfiRuntime.downcall}); the JVM registers nothing ahead of time and binds any
 * shape.
 *
 * <p>
 * The grid entries in {@code reachability-metadata.json} are generated from these same
 * rules (arity ascending, the carrier and return orders below), so a regeneration's diff
 * stays empty; this test fails when the file and the rules drift.
 */
class FfiNativeImageForeignConfigTest {

	private static final List<MemoryLayout> TWO = List.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

	private static final List<MemoryLayout> THREE = List.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_DOUBLE);

	private static final List<MemoryLayout> FOUR = List.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT);

	/** {@code null} = void. */
	private static final List<MemoryLayout> DOWNCALL_RETURNS = new ArrayList<>();

	private static final List<MemoryLayout> UPCALL_RETURNS = new ArrayList<>();

	static {
		DOWNCALL_RETURNS.add(null);
		DOWNCALL_RETURNS.add(ValueLayout.JAVA_LONG);
		DOWNCALL_RETURNS.add(ValueLayout.ADDRESS);
		DOWNCALL_RETURNS.add(ValueLayout.JAVA_DOUBLE);
		DOWNCALL_RETURNS.add(ValueLayout.JAVA_FLOAT);
		UPCALL_RETURNS.add(null);
		UPCALL_RETURNS.add(ValueLayout.JAVA_LONG);
		UPCALL_RETURNS.add(ValueLayout.ADDRESS);
		UPCALL_RETURNS.add(ValueLayout.JAVA_DOUBLE);
	}

	/** The downcall grid, in generation order. */
	static Set<FunctionDescriptor> downcallGrid() {
		Set<FunctionDescriptor> grid = new LinkedHashSet<>();
		addCombinations(grid, TWO, 0, 6, DOWNCALL_RETURNS);
		addCombinations(grid, THREE, 1, 4, DOWNCALL_RETURNS);
		addCombinations(grid, FOUR, 1, 2, DOWNCALL_RETURNS);
		return grid;
	}

	/** The upcall grid, in generation order. */
	static Set<FunctionDescriptor> upcallGrid() {
		Set<FunctionDescriptor> grid = new LinkedHashSet<>();
		addCombinations(grid, TWO, 0, 4, UPCALL_RETURNS);
		addCombinations(grid, THREE, 1, 2, UPCALL_RETURNS);
		return grid;
	}

	private static void addCombinations(Set<FunctionDescriptor> grid, List<MemoryLayout> carriers, int minArity,
			int maxArity, List<MemoryLayout> returns) {
		for (int arity = minArity; arity <= maxArity; arity++) {
			for (List<MemoryLayout> arguments : combinations(carriers, arity)) {
				for (MemoryLayout ret : returns) {
					MemoryLayout[] layouts = arguments.toArray(MemoryLayout[]::new);
					grid.add(ret == null ? FunctionDescriptor.ofVoid(layouts) : FunctionDescriptor.of(ret, layouts));
				}
			}
		}
	}

	private static List<List<MemoryLayout>> combinations(List<MemoryLayout> carriers, int arity) {
		List<List<MemoryLayout>> all = new ArrayList<>();
		all.add(List.of());
		for (int i = 0; i < arity; i++) {
			List<List<MemoryLayout>> next = new ArrayList<>();
			for (List<MemoryLayout> prefix : all) {
				for (MemoryLayout carrier : carriers) {
					List<MemoryLayout> extended = new ArrayList<>(prefix);
					extended.add(carrier);
					next.add(extended);
				}
			}
			all = next;
		}
		return all;
	}

	@Test
	void everyDowncallGridShapeIsRegisteredWithCaptureCallState() {
		assertThat(NativeImageDowncalls.missingCaptured(downcallGrid()))
			.as("canonical downcall shapes with no captureCallState entry in the native-image metadata -- the "
					+ "binary refuses to bind them, so a cffi: binding that works on java -jar signals there")
			.isEmpty();
	}

	@Test
	void theRuntimesOwnMallocAndFreeShapesAreRegisteredPlain() {
		// The constructor binds these two without captureCallState; a capture entry
		// does not serve them.
		assertThat(
				NativeImageDowncalls.missing(Set.of(FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
						FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)), Set.of()))
			.as("the malloc/free shapes FfiRuntime's constructor binds -- unregistered, the whole binding "
					+ "declines in the binary")
			.isEmpty();
	}

	@Test
	void everyUpcallGridShapeIsRegistered() {
		assertThat(NativeImageDowncalls.missingUpcalls(upcallGrid()))
			.as("canonical callback shapes with no foreign.upcalls entry -- the binary cannot build the stub "
					+ "for a cffi:defcallback of that shape")
			.isEmpty();
	}

	@Test
	void theShapesTheBundledConsumersReachLandInTheGrid() {
		// The downcalls use.lisp and the cffi test suite make, canonicalised by the
		// runtime's own rule: each must land inside the registered grid.
		List<FunctionDescriptor> canonical = List.of(
				// strlen: (:string) :long
				FfiRuntime.descriptorFor(FfiType.Scalar.INT64, List.of(FfiType.Scalar.STRING), -1),
				// cos: (:double) :double
				FfiRuntime.descriptorFor(FfiType.Scalar.DOUBLE, List.of(FfiType.Scalar.DOUBLE), -1),
				// getpid: () :int
				FfiRuntime.descriptorFor(FfiType.Scalar.INT32, List.of(), -1),
				// sqlite3_libversion: () :string
				FfiRuntime.descriptorFor(FfiType.Scalar.STRING, List.of(), -1),
				// gettimeofday: (:pointer :pointer) :int
				FfiRuntime.descriptorFor(FfiType.Scalar.INT32, List.of(FfiType.Scalar.POINTER, FfiType.Scalar.POINTER),
						-1),
				// sqlite3_exec: (:pointer :string :pointer :pointer :pointer) :int
				FfiRuntime.descriptorFor(FfiType.Scalar.INT32,
						List.of(FfiType.Scalar.POINTER, FfiType.Scalar.STRING, FfiType.Scalar.POINTER,
								FfiType.Scalar.POINTER, FfiType.Scalar.POINTER),
						-1),
				// fabsf: (:float) :float
				FfiRuntime.descriptorFor(FfiType.Scalar.FLOAT, List.of(FfiType.Scalar.FLOAT), -1),
				// qsort: (:pointer :ulong :ulong :pointer) :void
				FfiRuntime.descriptorFor(FfiType.Scalar.VOID, List.of(FfiType.Scalar.POINTER, FfiType.Scalar.UINT64,
						FfiType.Scalar.UINT64, FfiType.Scalar.POINTER), -1));
		assertThat(NativeImageDowncalls.missingCaptured(new LinkedHashSet<>(canonical))).isEmpty();
		// ... and the qsort comparator's callback shape: (:pointer :pointer) :int.
		FunctionDescriptor comparator = FfiRuntime.descriptorFor(FfiType.Scalar.INT32,
				List.of(FfiType.Scalar.POINTER, FfiType.Scalar.POINTER), -1);
		assertThat(NativeImageDowncalls.missingUpcalls(Set.of(comparator))).isEmpty();
	}

	@Test
	void canonicalisationWidensNarrowIntegersInsideTheRegisterWindowOnly() {
		// Seven :int arguments: the first six ride the canonical jlong, the seventh --
		// past the window both ABIs guarantee registers for -- keeps its exact width
		// (correct everywhere, outside the grid, actionable in the binary).
		List<FfiType> sevenInts = List.of(FfiType.Scalar.INT32, FfiType.Scalar.INT32, FfiType.Scalar.INT32,
				FfiType.Scalar.INT32, FfiType.Scalar.INT32, FfiType.Scalar.INT32, FfiType.Scalar.INT32);
		FunctionDescriptor descriptor = FfiRuntime.descriptorFor(FfiType.Scalar.INT32, sevenInts, -1);
		assertThat(descriptor.argumentLayouts().subList(0, 6)).allMatch(layout -> layout instanceof ValueLayout.OfLong);
		assertThat(descriptor.argumentLayouts().get(6)).isInstanceOf(ValueLayout.OfInt.class);
		assertThat(descriptor.returnLayout().orElseThrow()).isInstanceOf(ValueLayout.OfLong.class);
		// Floats do not count against the integer-class window.
		List<FfiType> mixed = List.of(FfiType.Scalar.DOUBLE, FfiType.Scalar.DOUBLE, FfiType.Scalar.INT8,
				FfiType.Scalar.FLOAT, FfiType.Scalar.UINT16, FfiType.Scalar.INT32);
		FunctionDescriptor mixedDescriptor = FfiRuntime.descriptorFor(FfiType.Scalar.UINT8, mixed, -1);
		assertThat(mixedDescriptor.argumentLayouts().get(0)).isInstanceOf(ValueLayout.OfDouble.class);
		assertThat(mixedDescriptor.argumentLayouts().get(2)).isInstanceOf(ValueLayout.OfLong.class);
		assertThat(mixedDescriptor.argumentLayouts().get(3)).isInstanceOf(ValueLayout.OfFloat.class);
		assertThat(mixedDescriptor.argumentLayouts().get(4)).isInstanceOf(ValueLayout.OfLong.class);
		assertThat(mixedDescriptor.argumentLayouts().get(5)).isInstanceOf(ValueLayout.OfLong.class);
	}

	@Test
	void aVariadicOrByValueStructCallKeepsItsExactLayouts() {
		// snprintf: (:pointer :ulong :string &optional :int) :int -- the variadic tail
		// keeps C's own promotions and the exact widths.
		List<FfiType> snprintf = List.of(FfiType.Scalar.POINTER, FfiType.Scalar.UINT64, FfiType.Scalar.STRING,
				FfiType.Scalar.INT32);
		assertThat(FfiRuntime.canonicalisable(FfiType.Scalar.INT32, snprintf, 3)).isFalse();
		FunctionDescriptor variadic = FfiRuntime.descriptorFor(FfiType.Scalar.INT32, snprintf, 3);
		assertThat(variadic.argumentLayouts().get(3)).isInstanceOf(ValueLayout.OfInt.class);
		assertThat(variadic.returnLayout().orElseThrow()).isInstanceOf(ValueLayout.OfInt.class);
		// div: (:int) (:struct :int32 :int32) -- the member list is part of the shape.
		FfiType.Struct divT = new FfiType.Struct(List.of(FfiType.Scalar.INT32, FfiType.Scalar.INT32));
		assertThat(FfiRuntime.canonicalisable(divT, List.of(FfiType.Scalar.INT32), -1)).isFalse();
		FunctionDescriptor struct = FfiRuntime.descriptorFor(divT, List.of(FfiType.Scalar.INT32), -1);
		assertThat(struct.argumentLayouts().get(0)).isInstanceOf(ValueLayout.OfInt.class);
	}

}
