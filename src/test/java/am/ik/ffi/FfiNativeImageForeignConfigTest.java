package am.ik.ffi;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
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
 * integer travels as {@code jlong}, and so does every pointer and string -- a pointer and
 * a 64-bit integer are one parameter on both ABIs the linker serves), which collapses the
 * shape space to THREE carriers per parameter; the checked-in grid then covers the small
 * arities outright.
 *
 * <h2>What the grid covers</h2>
 *
 * Downcalls, every one registered with {@code captureCallState} (the {@code errno} option
 * every {@link FfiRuntime#call} handle carries):
 * <ul>
 * <li>{@code jlong} arguments -- every integer, pointer and string -- at arity 0-10,</li>
 * <li>every combination of {@code jlong}/{@code jdouble} at arity 1-4,</li>
 * <li>every combination of all three carriers (with {@code jfloat}) at arity 1-2,</li>
 * <li>each at every return carrier ({@code void}, {@code jlong}, {@code jdouble},
 * {@code jfloat}),</li>
 * </ul>
 * plus the two capture-free shapes the runtime's own constructor binds ({@code malloc}
 * and {@code free}), plus the {@linkplain #structReturnGrid() by-value struct-RETURN
 * family} (a struct's members are part of its shape, so that family is bounded by member
 * count rather than collapsed by carriers). Upcalls: {@code jlong} arguments at arity 0-6
 * and {@code jlong}/{@code jdouble} at arity 1-2, at the return carriers
 * {@code void}/{@code jlong}/{@code jdouble} (a callback can neither take nor answer a
 * struct, so the family has no upcall half). A shape outside the grid -- a seventh-plus
 * NARROW integer argument, which keeps its exact width, a variadic call, a struct
 * ARGUMENT, a struct return past the family's bounds -- SIGNALS in the binary with the
 * one metadata entry that would register it (see {@code FfiRuntime.downcall}); the JVM
 * registers nothing ahead of time and binds any shape.
 *
 * <p>
 * The grid entries in {@code reachability-metadata.json} are generated from these same
 * rules (arity ascending, the carrier and return orders below), so a regeneration's diff
 * stays empty; this test fails when the file and the rules drift.
 */
class FfiNativeImageForeignConfigTest {

	/** The one integer-class carrier: every integer, every pointer, every string. */
	private static final List<MemoryLayout> ONE = List.of(ValueLayout.JAVA_LONG);

	private static final List<MemoryLayout> TWO = List.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE);

	private static final List<MemoryLayout> THREE = List.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE,
			ValueLayout.JAVA_FLOAT);

	/** {@code null} = void. */
	private static final List<MemoryLayout> DOWNCALL_RETURNS = new ArrayList<>();

	private static final List<MemoryLayout> UPCALL_RETURNS = new ArrayList<>();

	static {
		DOWNCALL_RETURNS.add(null);
		DOWNCALL_RETURNS.add(ValueLayout.JAVA_LONG);
		DOWNCALL_RETURNS.add(ValueLayout.JAVA_DOUBLE);
		DOWNCALL_RETURNS.add(ValueLayout.JAVA_FLOAT);
		UPCALL_RETURNS.add(null);
		UPCALL_RETURNS.add(ValueLayout.JAVA_LONG);
		UPCALL_RETURNS.add(ValueLayout.JAVA_DOUBLE);
	}

	/** The downcall grid, in generation order. */
	static Set<FunctionDescriptor> downcallGrid() {
		Set<FunctionDescriptor> grid = new LinkedHashSet<>();
		addCombinations(grid, ONE, 0, 10, DOWNCALL_RETURNS);
		addCombinations(grid, TWO, 1, 4, DOWNCALL_RETURNS);
		addCombinations(grid, THREE, 1, 2, DOWNCALL_RETURNS);
		return grid;
	}

	/**
	 * The member layouts a by-value struct can be built from, in generation order. A
	 * member is NOT widened -- its width and offset are part of the layout -- so the
	 * alphabet is every DISTINCT layout {@link FfiType} can put in a struct:
	 * {@code :pointer} is absent because it is now {@code jlong}, the same member as
	 * {@code :int64}.
	 */
	private static final List<FfiType> MEMBERS = List.of(FfiType.Scalar.INT8, FfiType.Scalar.INT16,
			FfiType.Scalar.INT32, FfiType.Scalar.INT64, FfiType.Scalar.FLOAT, FfiType.Scalar.DOUBLE);

	/** The parameter carriers the struct-return family is crossed with, by arity. */
	private static final List<FfiType> STRUCT_PARAMS_THREE = List.of(FfiType.Scalar.INT64, FfiType.Scalar.DOUBLE,
			FfiType.Scalar.FLOAT);

	private static final List<FfiType> STRUCT_PARAMS_ONE = List.of(FfiType.Scalar.INT64);

	/**
	 * The by-value struct-RETURN sub-grid, in generation order: the member sequences of
	 * length 1-2 over every member layout, plus the homogeneous ones of length 3-4 (54
	 * return shapes), crossed with the parameter tuples of all three carriers at arity
	 * 0-2 and of {@code jlong} at arity 3-4 (15 tuples).
	 *
	 * <p>
	 * It is affordable because a registered shape is not a compiled stub of its own --
	 * the granularity is the ABI-LOWERED signature, and many descriptors lower the same
	 * way. Measured 2026-09-02 (GraalVM 25.0.4, Linux x86-64), when the family stood at
	 * 3,150 entries: they moved the image by 80 methods and ~30 KB of code area, and the
	 * binary size (80,808,200 bytes) and build time (1m 23s) not at all. What bounds the
	 * family is the checked-in file, not the image. It is enumerated by MEMBERS rather
	 * than by ABI class for the same reason a narrow member is not widened here: the
	 * lowering is the platform's, and SysV and AAPCS64 do not agree on it.
	 */
	static Set<FunctionDescriptor> structReturnGrid() {
		List<FfiType> returns = new ArrayList<>();
		for (FfiType member : MEMBERS) {
			returns.add(new FfiType.Struct(List.of(member)));
		}
		for (FfiType first : MEMBERS) {
			for (FfiType second : MEMBERS) {
				returns.add(new FfiType.Struct(List.of(first, second)));
			}
		}
		for (int length = 3; length <= 4; length++) {
			for (FfiType member : MEMBERS) {
				returns.add(new FfiType.Struct(Collections.nCopies(length, member)));
			}
		}
		List<List<FfiType>> parameters = new ArrayList<>();
		for (int arity = 0; arity <= 2; arity++) {
			parameters.addAll(typeCombinations(STRUCT_PARAMS_THREE, arity));
		}
		for (int arity = 3; arity <= 4; arity++) {
			parameters.addAll(typeCombinations(STRUCT_PARAMS_ONE, arity));
		}
		Set<FunctionDescriptor> grid = new LinkedHashSet<>();
		for (FfiType ret : returns) {
			for (List<FfiType> arguments : parameters) {
				grid.add(FfiRuntime.descriptorFor(ret, arguments, -1));
			}
		}
		return grid;
	}

	private static List<List<FfiType>> typeCombinations(List<FfiType> carriers, int arity) {
		List<List<FfiType>> all = new ArrayList<>();
		all.add(List.of());
		for (int i = 0; i < arity; i++) {
			List<List<FfiType>> next = new ArrayList<>();
			for (List<FfiType> prefix : all) {
				for (FfiType carrier : carriers) {
					List<FfiType> extended = new ArrayList<>(prefix);
					extended.add(carrier);
					next.add(extended);
				}
			}
			all = next;
		}
		return all;
	}

	/** The upcall grid, in generation order. */
	static Set<FunctionDescriptor> upcallGrid() {
		Set<FunctionDescriptor> grid = new LinkedHashSet<>();
		addCombinations(grid, ONE, 0, 6, UPCALL_RETURNS);
		addCombinations(grid, TWO, 1, 2, UPCALL_RETURNS);
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
	void everyStructReturnGridShapeIsRegisteredWithCaptureCallState() {
		assertThat(NativeImageDowncalls.missingCaptured(structReturnGrid()))
			.as("by-value struct RETURN shapes with no captureCallState entry in the native-image metadata -- "
					+ "a cffi:defcfun returning one of them works on java -jar and signals in the binary")
			.isEmpty();
	}

	@Test
	void theGuidesOwnByValueStructExampleIsInsideTheGrid() {
		// doc/{en,ja}/guides/cffi.md's "Structures, including by value":
		// (cffi:defcfun ("div" c-div) (:struct div-t) (numer :int) (denom :int)).
		FfiType.Struct divT = new FfiType.Struct(List.of(FfiType.Scalar.INT32, FfiType.Scalar.INT32));
		FunctionDescriptor div = FfiRuntime.descriptorFor(divT, List.of(FfiType.Scalar.INT32, FfiType.Scalar.INT32),
				-1);
		assertThat(NativeImageDowncalls.missingCaptured(Set.of(div))).isEmpty();
		// ... and so is a nested {CGPoint, CGSize}, which the flat layout makes the
		// four-double shape rather than a nesting of its own.
		FfiType.Struct pair = new FfiType.Struct(List.of(FfiType.Scalar.DOUBLE, FfiType.Scalar.DOUBLE));
		FunctionDescriptor rect = FfiRuntime.descriptorFor(new FfiType.Struct(List.of(pair, pair)),
				List.of(FfiType.Scalar.POINTER), -1);
		assertThat(NativeImageDowncalls.missingCaptured(Set.of(rect))).isEmpty();
	}

	@Test
	void theRuntimesOwnMallocAndFreeShapesAreRegisteredPlain() {
		// The constructor binds these two without captureCallState; a capture entry
		// does not serve them.
		assertThat(
				NativeImageDowncalls.missing(Set.of(FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
						FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)), Set.of()))
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
	void anAddressTakesTheSameCarrierAsA64BitInteger() {
		// The ABI passes an address in an integer-class register, so a :pointer, a
		// :string and an :int64 are ONE parameter and one registered shape rather than
		// three -- the whole reason the grid needs three carriers and not four.
		FunctionDescriptor addresses = FfiRuntime.descriptorFor(FfiType.Scalar.POINTER,
				List.of(FfiType.Scalar.POINTER, FfiType.Scalar.STRING), -1);
		FunctionDescriptor integers = FfiRuntime.descriptorFor(FfiType.Scalar.INT64,
				List.of(FfiType.Scalar.INT64, FfiType.Scalar.INT64), -1);
		assertThat(addresses).isEqualTo(integers);
		assertThat(FfiRuntime.metadataType(addresses.returnLayout().orElseThrow())).isEqualTo("jlong");
		// Inside a struct too, where a member is otherwise never widened: a pointer
		// member has the width and offset of a 64-bit integer, and both ABIs classify it
		// the same way, so it is the same member.
		assertThat(FfiRuntime.metadataType(new FfiType.Struct(List.of(FfiType.Scalar.POINTER)).layout()))
			.isEqualTo("struct(jlong)");
		// And in a variadic tail, which keeps its EXACT widths: an address is that
		// carrier there as well, so nothing is being canonicalised away here.
		assertThat(FfiRuntime
			.descriptorFor(FfiType.Scalar.INT32, List.of(FfiType.Scalar.POINTER, FfiType.Scalar.STRING), 1)
			.argumentLayouts()).allMatch(layout -> layout instanceof ValueLayout.OfLong);
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
		// div: (:int :int) (:struct :int32 :int32) -- the RETURN keeps its exact member
		// list, which is part of the shape, but the arguments still canonicalise (an
		// indirect struct return travels in a register of its own), so div, ldiv and
		// imaxdiv are one entry rather than three.
		FfiType.Struct divT = new FfiType.Struct(List.of(FfiType.Scalar.INT32, FfiType.Scalar.INT32));
		List<FfiType> twoInts = List.of(FfiType.Scalar.INT32, FfiType.Scalar.INT32);
		assertThat(FfiRuntime.canonicalisable(divT, twoInts, -1)).isFalse();
		assertThat(FfiRuntime.argumentsCanonicalisable(twoInts, -1)).isTrue();
		FunctionDescriptor struct = FfiRuntime.descriptorFor(divT, twoInts, -1);
		assertThat(struct.argumentLayouts()).allMatch(layout -> layout instanceof ValueLayout.OfLong);
		assertThat(FfiRuntime.metadataType(struct.returnLayout().orElseThrow())).isEqualTo("struct(jint,jint)");
		// A by-value struct ARGUMENT is the case that stays outside: it eats an
		// ABI-defined number of register-class slots, so the register window cannot be
		// counted past one.
		assertThat(FfiRuntime.argumentsCanonicalisable(List.of(FfiType.Scalar.INT32, divT), -1)).isFalse();
		assertThat(FfiRuntime.descriptorFor(FfiType.Scalar.INT32, List.of(FfiType.Scalar.INT32, divT), -1)
			.argumentLayouts()
			.get(0)).isInstanceOf(ValueLayout.OfInt.class);
	}

	@Test
	void aStructsMetadataSpellingCarriesItsPadding() {
		// The image builder rebuilds the layout with MemoryLayout.structLayout, which
		// REFUSES a member that does not sit at its own alignment: a padding-free
		// "struct(jbyte,jint)" aborts the build ("Invalid alignment constraint for
		// member layout: i4") instead of registering the shape. So the spelling the
		// miss message hands the user -- and the spelling this file is generated in --
		// has to carry the padding.
		FfiType.Struct padded = new FfiType.Struct(List.of(FfiType.Scalar.INT8, FfiType.Scalar.INT32));
		assertThat(FfiRuntime.metadataType(padded.layout())).isEqualTo("struct(jbyte,padding(3),jint)");
		FfiType.Struct tailPadded = new FfiType.Struct(List.of(FfiType.Scalar.INT32, FfiType.Scalar.INT8));
		assertThat(FfiRuntime.metadataType(tailPadded.layout())).isEqualTo("struct(jint,jbyte,padding(3))");
	}

}
