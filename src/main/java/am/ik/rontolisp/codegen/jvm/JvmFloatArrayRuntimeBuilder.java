package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.ArrayMethod;

import static am.ik.rontolisp.codegen.jvm.JvmPackedFloatWidth.BFLOAT16;
import static am.ik.rontolisp.codegen.jvm.JvmPackedFloatWidth.DOUBLE;
import static am.ik.rontolisp.codegen.jvm.JvmPackedFloatWidth.SINGLE;

/**
 * Builds the JVM bytecode for the packed float-array runtime helpers ({@code _fv*}). A
 * packed float array is represented at runtime as a bare {@code double[]} (double-float),
 * {@code float[]} (single-float) or {@code short[]} (bfloat16) carrying an embedded
 * dimension header, whose layout is width-dependent and owned by
 * {@link JvmPackedFloatWidth}: {@code [rank, dim_0, ..., dim_{rank-1}, e_0, ...]} with
 * data offset {@code 1 + rank} at the two CL widths, and {@code [rank, hi_0, lo_0, ...,
 * e_0, ...]} with data offset {@code 1 + 2 * rank} at bfloat16, whose {@code short}
 * cannot hold a dimension in one slot. The three backings are disjoint from the
 * {@code Object[]} shape of a cons / function ref / ratio and from the {@code ArrayList}
 * shape of a general array, and disjoint from each other, so no value-discriminator
 * changes are needed anywhere else in the backend.
 *
 * <p>
 * These helpers are emitted only when the program can produce a packed float array (a
 * {@code #d(...)}/{@code #f(...)}/{@code #bf16(...)} literal or {@code make-array
 * :element-type 'double-float|'single-float|'bfloat16}; see
 * {@code JvmLispCompiler.Ctx#usesFloatArray}). Each accessor dispatches on
 * {@code instanceof double[]}, then {@code float[]}, then {@code short[]}: a packed array
 * is handled natively (header-aware; a single-float read widens f32-&gt;f64 and a write
 * narrows f64-&gt;f32; a bfloat16 read and write go through {@code _bf16Value} /
 * {@code _bf16Bits}), any other array shape delegates to the matching general
 * {@code _array*} helper, so a value whose static type is "an array" works whichever
 * representation it holds at runtime. Allocation is width-specific ({@code _fvMake}
 * builds a {@code double[]}, {@code _sfvMake} a {@code float[]}, {@code _bfvMake} a
 * {@code short[]}) because the element type is a compile-time literal at the
 * {@code make-array} call site.
 *
 * <p>
 * {@code _bf16Value(I)D} and {@code _bf16Bits(D)I} are {@code am.ik.rontolisp.BFloat16}
 * emitted instruction for instruction ({@code .kb/bfloat16.md}): the authority lives in
 * the root package and cannot travel with a compiled program, so the copy is unavoidable
 * and is pinned against the authority over every f32 bit pattern by
 * {@code JvmBFloat16ArrayTest}. {@code JvmBFloat16Compiler} carries the same arithmetic
 * inline for the scalar pair, which must work in a program with no packed array at all.
 */
final class JvmFloatArrayRuntimeBuilder {

	static final String OBJ = "Ljava/lang/Object;";

	static final String TO_GENERAL = "_fvToGeneral";

	static final String TO_GENERAL_PRINT = "_fvToGeneralPrint";

	static final String TO_GENERAL_DESC = "(" + OBJ + ")" + OBJ;

	static final String AREF1 = "_fvAref1";

	static final String AREF2 = "_fvAref2";

	static final String AREFN = "_fvArefN";

	static final String ASET1 = "_fvAset1";

	static final String ASET2 = "_fvAset2";

	static final String ASETN = "_fvAsetN";

	static final String DIMS = "_fvDims";

	static final String LENGTH = "_fvLength";

	static final String LENGTH_DESC = "(" + OBJ + ")" + OBJ;

	static final String MAKE = "_fvMake";

	static final String SINGLE_MAKE = "_sfvMake";

	static final String BFLOAT16_MAKE = "_bfvMake";

	static final String MAKE_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	static final String ELEMENT_TYPE = "_fvElementType";

	static final String ELEMENT_TYPE_DESC = "(" + OBJ + ")" + OBJ;

	static final String REQUIRE_GENERAL = "_fvRequireGeneral";

	static final String REQUIRE_GENERAL_DESC = "(" + OBJ + ")" + OBJ;

	// _fvCheckRank(arr, given): packed -> compare the header rank against `given`; else
	// delegate to _arrayCheckRank. See JvmArrayRuntimeBuilder#CHECK_RANK.
	static final String CHECK_RANK = "_fvCheckRank";

	static final String CHECK_RANK_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	/** {@code _bf16Value(int bits) -> double}: {@code BFloat16.value(int)}. */
	static final String BF16_VALUE = "_bf16Value";

	static final String BF16_VALUE_DESC = "(I)D";

	/** {@code _bf16Bits(double value) -> int}: {@code BFloat16.bits(double)}. */
	static final String BF16_BITS = "_bf16Bits";

	static final String BF16_BITS_DESC = "(D)I";

	/**
	 * {@code _bf16Print(int bits) -> Float}: the float whose {@code Float.toString} is
	 * {@code FloatText.bfloat16Text} of the pattern's value -- the print-time box.
	 */
	static final String BF16_PRINT = "_bf16Print";

	static final String BF16_PRINT_DESC = "(I)Ljava/lang/Float;";

	/** The binary64 exponent field, all ones. */
	private static final long EXPONENT_MASK = 0x7ff0000000000000L;

	/** The binary64 mantissa field. */
	private static final long MANTISSA_MASK = 0x000fffffffffffffL;

	/** The widths in dispatch order; every accessor tests them in this order. */
	private static final JvmPackedFloatWidth[] WIDTHS = { DOUBLE, SINGLE, BFLOAT16 };

	private JvmFloatArrayRuntimeBuilder() {
	}

	/**
	 * The references the quantized matrix's arms need ({@code .kb/quantized-matrix.md}):
	 * a {@code byte[]} is the fourth packed shape these helpers meet, and every arm
	 * delegates to a {@code _qm*} helper ({@link JvmQuantizedMatrixRuntimeBuilder}),
	 * which exists exactly when this record is non-null.
	 */
	private record Quantized(ClassEntry byteArrayClass, MethodRefEntry aref1, MethodRefEntry aref2,
			MethodRefEntry arefN, MethodRefEntry dims, MethodRefEntry length, MethodRefEntry qmInt, boolean octets) {

	}

	/** The constant-pool references one emitted body needs, per width. */
	private record Refs(ClassEntry doubleArrayClass, ClassEntry floatArrayClass, ClassEntry shortArrayClass,
			MethodRefEntry bf16Value, MethodRefEntry bf16Bits, MethodRefEntry ckBound, @Nullable Quantized quantized) {

		ClassEntry arrayClass(JvmPackedFloatWidth w) {
			return switch (w) {
				case DOUBLE -> this.doubleArrayClass;
				case SINGLE -> this.floatArrayClass;
				case BFLOAT16 -> this.shortArrayClass;
			};
		}

	}

	/**
	 * Builds the packed float-array helper methods emitted into the program class.
	 * @param cp the constant pool
	 * @param objectClass the {@code java/lang/Object} class constant
	 * @param objectArrayClass the {@code [Ljava/lang/Object;} class constant
	 * @param selfClass the generated program class (for self-referencing invokestatic)
	 * @param quantized whether a quantized matrix can exist, so these helpers carry its
	 * {@code byte[]} arms
	 * @param octets whether an {@code (unsigned-byte 8)} vector -- also a {@code byte[]}
	 * -- can exist, so those arms test the tag that tells the two apart
	 * @return the helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp, ClassEntry objectClass, ClassEntry objectArrayClass,
			ClassEntry selfClass, @Nullable MethodRefEntry written, @Nullable MethodRefEntry materialize,
			boolean quantized, boolean octets) {
		ClassEntry doubleArrayClass = cp.classEntry("[D");
		ClassEntry floatArrayClass = cp.classEntry("[F");
		ClassEntry shortArrayClass = cp.classEntry("[S");
		ClassEntry arrayListClass = cp.classEntry("java/util/ArrayList");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		ClassEntry doubleClass = cp.classEntry("java/lang/Double");
		ClassEntry floatClass = cp.classEntry("java/lang/Float");
		ClassEntry numberClass = cp.classEntry("java/lang/Number");
		ClassEntry rtExClass = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry rtExInit = cp.methodRef(rtExClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry alInit = cp.methodRef(arrayListClass, "<init>", "()V");
		MethodRefEntry alAdd = cp.methodRef(arrayListClass, "add", "(Ljava/lang/Object;)Z");
		MethodRefEntry longIntValue = cp.methodRef(longClass, "intValue", "()I");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry doubleValueOf = cp.methodRef(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
		MethodRefEntry numberDoubleValue = cp.methodRef(numberClass, "doubleValue", "()D");
		MethodRefEntry floatValueOf = cp.methodRef(floatClass, "valueOf", "(F)Ljava/lang/Float;");
		// Self-referencing static helpers to delegate to / reuse.
		MethodRefEntry dbl = self(cp, selfClass, JvmNumericRuntimeBuilder.DBL, "(" + OBJ + ")" + OBJ);
		MethodRefEntry lengthHelper = self(cp, selfClass, JvmLengthRuntimeBuilder.METHOD, JvmLengthRuntimeBuilder.DESC);
		MethodRefEntry toGeneral = self(cp, selfClass, TO_GENERAL, TO_GENERAL_DESC);
		MethodRefEntry aref1 = self(cp, selfClass, JvmArrayRuntimeBuilder.AREF1, JvmArrayRuntimeBuilder.AREF1_DESC);
		MethodRefEntry aref2 = self(cp, selfClass, JvmArrayRuntimeBuilder.AREF2, JvmArrayRuntimeBuilder.AREF2_DESC);
		MethodRefEntry arefN = self(cp, selfClass, JvmArrayRuntimeBuilder.AREFN, JvmArrayRuntimeBuilder.AREFN_DESC);
		MethodRefEntry aset1 = self(cp, selfClass, JvmArrayRuntimeBuilder.ASET1, JvmArrayRuntimeBuilder.ASET1_DESC);
		MethodRefEntry aset2 = self(cp, selfClass, JvmArrayRuntimeBuilder.ASET2, JvmArrayRuntimeBuilder.ASET2_DESC);
		MethodRefEntry asetN = self(cp, selfClass, JvmArrayRuntimeBuilder.ASETN, JvmArrayRuntimeBuilder.ASETN_DESC);
		MethodRefEntry arrayDims = self(cp, selfClass, JvmArrayRuntimeBuilder.DIMS, JvmArrayRuntimeBuilder.DIMS_DESC);
		MethodRefEntry arrayCheckRank = self(cp, selfClass, JvmArrayRuntimeBuilder.CHECK_RANK,
				JvmArrayRuntimeBuilder.CHECK_RANK_DESC);
		MethodRefEntry ckBound = self(cp, selfClass, JvmOperandTypeRuntime.CK_BOUND,
				JvmOperandTypeRuntime.CK_BOUND_DESC);
		MethodRefEntry bf16Value = self(cp, selfClass, BF16_VALUE, BF16_VALUE_DESC);
		MethodRefEntry bf16Bits = self(cp, selfClass, BF16_BITS, BF16_BITS_DESC);
		MethodRefEntry bf16Print = self(cp, selfClass, BF16_PRINT, BF16_PRINT_DESC);
		Quantized qm = quantized ? new Quantized(cp.classEntry("[B"),
				self(cp, selfClass, JvmQuantizedMatrixRuntimeBuilder.AREF1,
						JvmQuantizedMatrixRuntimeBuilder.BINARY_DESC),
				self(cp, selfClass, JvmQuantizedMatrixRuntimeBuilder.AREF2,
						JvmQuantizedMatrixRuntimeBuilder.TERNARY_DESC),
				self(cp, selfClass, JvmQuantizedMatrixRuntimeBuilder.AREFN,
						JvmQuantizedMatrixRuntimeBuilder.BINARY_DESC),
				self(cp, selfClass, JvmQuantizedMatrixRuntimeBuilder.DIMS, JvmQuantizedMatrixRuntimeBuilder.UNARY_DESC),
				self(cp, selfClass, JvmQuantizedMatrixRuntimeBuilder.LENGTH,
						JvmQuantizedMatrixRuntimeBuilder.UNARY_DESC),
				self(cp, selfClass, JvmQuantizedMatrixRuntimeBuilder.INT, JvmQuantizedMatrixRuntimeBuilder.INT_DESC),
				octets) : null;
		Refs refs = new Refs(doubleArrayClass, floatArrayClass, shortArrayClass, bf16Value, bf16Bits, ckBound, qm);

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(buildToGeneral(cp, TO_GENERAL, refs, arrayListClass, objectClass, alInit, alAdd, longValueOf,
				doubleValueOf, null, null, materialize));
		// The print-only variant: a single-float element is boxed as a transient Float
		// so _lispToString renders it at its f32 width (#f(0.1) round-trips), and a
		// bfloat16 element as the Float _bf16Print chooses (the shortest decimal that
		// reads back to the same pattern); every semantic conversion keeps going through
		// _fvToGeneral's widened Doubles.
		methods.add(buildToGeneral(cp, TO_GENERAL_PRINT, refs, arrayListClass, objectClass, alInit, alAdd, longValueOf,
				doubleValueOf, floatValueOf, bf16Print, materialize));
		methods.add(buildAref1(cp, refs, longClass, longIntValue, doubleValueOf, aref1, materialize));
		methods.add(buildAref2(cp, refs, longClass, longIntValue, doubleValueOf, aref2, materialize));
		methods.add(buildArefN(cp, refs, objectArrayClass, longClass, longIntValue, doubleValueOf, arefN, materialize));
		methods.add(buildAset1(cp, refs, longClass, numberClass, longIntValue, numberDoubleValue, doubleValueOf, dbl,
				aset1, written));
		methods.add(buildAset2(cp, refs, longClass, numberClass, longIntValue, numberDoubleValue, doubleValueOf, dbl,
				aset2, written));
		methods.add(buildAsetN(cp, refs, objectArrayClass, longClass, numberClass, longIntValue, numberDoubleValue,
				doubleValueOf, dbl, asetN, written));
		methods.add(buildDims(cp, refs, objectClass, longValueOf, arrayDims));
		methods.add(buildCheckRank(cp, refs, longClass, longIntValue, rtExClass, rtExInit, arrayCheckRank));
		methods.add(buildLength(cp, refs, longValueOf, toGeneral, lengthHelper));
		methods.add(buildMake(cp, DOUBLE, MAKE, refs, objectArrayClass, longClass, numberClass, longIntValue,
				numberDoubleValue, dbl));
		methods.add(buildMake(cp, SINGLE, SINGLE_MAKE, refs, objectArrayClass, longClass, numberClass, longIntValue,
				numberDoubleValue, dbl));
		methods.add(buildMake(cp, BFLOAT16, BFLOAT16_MAKE, refs, objectArrayClass, longClass, numberClass, longIntValue,
				numberDoubleValue, dbl));
		methods.add(buildElementType(cp, refs));
		methods.add(buildRequireGeneral(cp, refs, rtExClass, rtExInit));
		methods.add(buildBf16Value(cp, doubleClass, floatClass));
		methods.add(buildBf16Bits(cp, doubleClass, floatClass));
		methods.add(buildBf16Print(cp, floatClass, floatValueOf, bf16Bits));
		return methods;
	}

	private static MethodRefEntry self(ConstantPool cp, ClassEntry selfClass, String name, String desc) {
		return cp.methodRef(selfClass, name, desc);
	}

	// _fvToGeneral(o): convert a packed array into an equivalent general array (an
	// ArrayList whose slot 0 is the {dims, null, null} header and slots 1.. are boxed
	// Doubles), so the existing _arrayToString / equality / coercion helpers render and
	// compare it exactly like a general double array. Only ever called with a packed
	// array (the print/length dispatch tests instanceof first), so it dispatches the
	// three widths with no general fallback. Locals: 0=o, 1=d (array), 2=rank, 3=off,
	// 4=total, 5=dimsArr, 6=list, 7=k, 8=f.
	private static ArrayMethod buildToGeneral(ConstantPool cp, String name, Refs refs, ClassEntry arrayListClass,
			ClassEntry objectClass, MethodRefEntry alInit, MethodRefEntry alAdd, MethodRefEntry longValueOf,
			MethodRefEntry doubleValueOf, @Nullable MethodRefEntry floatValueOf, @Nullable MethodRefEntry bf16Print,
			@Nullable MethodRefEntry materialize) {
		MethodCode a = new MethodCode();
		// --gpu: every element is about to be read; a result the device still holds comes
		// home first. Once, for the whole array, ahead of the loop.
		emitMaterialize(a, 0, materialize);
		for (int i = 0; i < WIDTHS.length; i++) {
			JvmPackedFloatWidth w = WIDTHS[i];
			MethodCode.Label next = a.newLabel();
			if (i < WIDTHS.length - 1) {
				a.aload(0);
				a.instanceOf(refs.arrayClass(w));
				a.ifeq(next);
			}
			emitToGeneralBody(a, w, refs, arrayListClass, objectClass, alInit, alAdd, longValueOf, doubleValueOf,
					floatValueOf, bf16Print);
			a.labelBinding(next);
		}
		return new ArrayMethod(cp.addUtf8(name), cp.addUtf8(TO_GENERAL_DESC), a);
	}

	/**
	 * {@code local = _gpuMaterialize(local)} when the GPU runtime is emitted; nothing
	 * otherwise. The guard answers the array to READ -- the array itself, or a result
	 * stub's backing -- so the local is rebound to it and every read below sees the
	 * bytes.
	 */
	private static void emitMaterialize(MethodCode a, int local, @Nullable MethodRefEntry materialize) {
		if (materialize != null) {
			a.aload(local);
			a.invokestatic(materialize);
			a.astore(local);
		}
	}

	// floatValueOf/bf16Print non-null select the print-only boxing: a single-float
	// element is boxed as a Float (no widening) and a bfloat16 element as its shortest
	// round-tripping Float, so the renderer can spell each at its own width.
	private static void emitToGeneralBody(MethodCode a, JvmPackedFloatWidth w, Refs refs, ClassEntry arrayListClass,
			ClassEntry objectClass, MethodRefEntry alInit, MethodRefEntry alAdd, MethodRefEntry longValueOf,
			MethodRefEntry doubleValueOf, @Nullable MethodRefEntry floatValueOf, @Nullable MethodRefEntry bf16Print) {
		int o = 0, d = 1, rank = 2, off = 3, total = 4, dimsArr = 5, list = 6, k = 7, f = 8;
		a.aload(o);
		a.checkcast(refs.arrayClass(w));
		a.astore(d);
		a.aload(d);
		w.loadRank(a);
		a.istore(rank);
		a.iload(rank);
		w.emitDataOffset(a);
		a.istore(off);
		a.aload(d);
		a.arraylength();
		a.iload(off);
		a.isub();
		a.istore(total);
		a.iload(rank);
		a.anewarray(objectClass);
		a.astore(dimsArr);
		a.loadConstant(0);
		a.istore(k);
		MethodCode.Label kLoop = a.newLabel();
		MethodCode.Label kDone = a.newLabel();
		a.labelBinding(kLoop);
		a.iload(k);
		a.iload(rank);
		a.if_icmpge(kDone);
		a.aload(dimsArr);
		a.iload(k);
		a.aload(d);
		a.iload(k);
		w.loadDim(a);
		a.i2l();
		a.invokestatic(longValueOf);
		a.aastore();
		a.iinc(k, 1);
		a.goto_(kLoop);
		a.labelBinding(kDone);
		a.new_(arrayListClass);
		a.dup();
		a.invokespecial(alInit);
		a.astore(list);
		a.aload(list);
		a.loadConstant(3);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.aload(dimsArr);
		a.aastore();
		a.invokevirtual(alAdd);
		a.pop();
		a.loadConstant(0);
		a.istore(f);
		MethodCode.Label fLoop = a.newLabel();
		MethodCode.Label fDone = a.newLabel();
		a.labelBinding(fLoop);
		a.iload(f);
		a.iload(total);
		a.if_icmpge(fDone);
		a.aload(list);
		a.aload(d);
		a.iload(off);
		a.iload(f);
		a.iadd();
		if (w == SINGLE && floatValueOf != null) {
			a.faload();
			a.invokestatic(floatValueOf);
		}
		else if (w == BFLOAT16 && bf16Print != null) {
			a.saload();
			a.invokestatic(bf16Print);
		}
		else {
			w.loadElem(a, refs.bf16Value());
			a.invokestatic(doubleValueOf);
		}
		a.invokevirtual(alAdd);
		a.pop();
		a.iinc(f, 1);
		a.goto_(fLoop);
		a.labelBinding(fDone);
		a.aload(list);
		a.areturn();
	}

	// Emits the width-dispatch skeleton every accessor shares: for each width in
	// dispatch order, "if (arr instanceof <width class>) { <body> }", then the general
	// fallback the caller emits after this returns. The body must leave the method
	// (areturn) on every path.
	private interface Body {

		void emit(MethodCode a, JvmPackedFloatWidth w);

	}

	private static void emitWidthDispatch(MethodCode a, Refs refs, int arr, Body body) {
		for (JvmPackedFloatWidth w : WIDTHS) {
			MethodCode.Label next = a.newLabel();
			a.aload(arr);
			a.instanceOf(refs.arrayClass(w));
			a.ifeq(next);
			body.emit(a, w);
			a.labelBinding(next);
		}
	}

	/**
	 * The quantized matrix's arm, ahead of the width dispatch: {@code if (arr instanceof
	 * byte[]) body}, where the body leaves the method -- and, where an
	 * {@code (unsigned-byte 8)} vector (the other {@code byte[]}) can exist, only when
	 * slot 0 is not that vector's tag. Nothing when no quantized matrix can exist in the
	 * program, so such a program's helpers keep their bytes.
	 */
	private static void emitQuantizedArm(MethodCode a, Refs refs, int arr,
			java.util.function.Consumer<Quantized> body) {
		Quantized qm = refs.quantized();
		if (qm == null) {
			return;
		}
		MethodCode.Label next = a.newLabel();
		a.aload(arr);
		a.instanceOf(qm.byteArrayClass());
		a.ifeq(next);
		if (qm.octets()) {
			a.aload(arr);
			a.checkcast(qm.byteArrayClass());
			a.loadConstant(0);
			a.baload();
			a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			a.if_icmpeq(next);
		}
		body.accept(qm);
		a.labelBinding(next);
	}

	// _fvAref1(arr, i): packed -> Double.valueOf(d[off + i]), i checked against the
	// total size (d.length - off); else _aref1. Serves rank-1 aref and row-major-aref
	// (rank read from the header). Locals: 0=arr, 1=i, 2=d, 3=rank, 4=off.
	private static ArrayMethod buildAref1(ConstantPool cp, Refs refs, ClassEntry longClass, MethodRefEntry longIntValue,
			MethodRefEntry doubleValueOf, MethodRefEntry aref1, @Nullable MethodRefEntry materialize) {
		int arr = 0, i = 1, d = 2, rank = 3, off = 4;
		MethodCode a = new MethodCode();
		// --gpu: the element read below must see the device's bytes if it holds them.
		emitMaterialize(a, arr, materialize);
		emitQuantizedArm(a, refs, arr, qm -> {
			a.aload(arr);
			a.aload(i);
			a.invokestatic(qm.aref1());
			a.areturn();
		});
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.istore(off);
			asm.aload(d);
			asm.iload(off);
			emitFlatBounded(asm, refs, i, d, off);
			asm.iadd();
			w.loadElem(asm, refs.bf16Value());
			asm.invokestatic(doubleValueOf);
			asm.areturn();
		});
		a.aload(arr);
		a.aload(i);
		a.invokestatic(aref1);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREF1), cp.addUtf8(JvmArrayRuntimeBuilder.AREF1_DESC), a);
	}

	// _fvAref2(arr, i, j): packed -> Double.valueOf(d[off + i * cols + j]) with
	// cols = dim 1, i and j each checked against its own dimension; else _aref2.
	// Locals: 0=arr, 1=i, 2=j, 3=d, 4=rank, 5=cols.
	private static ArrayMethod buildAref2(ConstantPool cp, Refs refs, ClassEntry longClass, MethodRefEntry longIntValue,
			MethodRefEntry doubleValueOf, MethodRefEntry aref2, @Nullable MethodRefEntry materialize) {
		int arr = 0, i = 1, j = 2, d = 3, rank = 4, cols = 5;
		MethodCode a = new MethodCode();
		emitMaterialize(a, arr, materialize);
		emitQuantizedArm(a, refs, arr, qm -> {
			a.aload(arr);
			a.aload(i);
			a.aload(j);
			a.invokestatic(qm.aref2());
			a.areturn();
		});
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.aload(d);
			asm.loadConstant(1);
			w.loadDim(asm);
			asm.istore(cols);
			asm.aload(d);
			asm.iload(rank);
			w.emitDataOffset(asm);
			emitFlat2Bounded(asm, w, refs, i, j, d, cols);
			asm.iadd();
			w.loadElem(asm, refs.bf16Value());
			asm.invokestatic(doubleValueOf);
			asm.areturn();
		});
		a.aload(arr);
		a.aload(i);
		a.aload(j);
		a.invokestatic(aref2);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREF2), cp.addUtf8(JvmArrayRuntimeBuilder.AREF2_DESC), a);
	}

	// _fvArefN(arr, subs): packed -> Horner flat index over the header dims; else _arefN.
	// Locals: 0=arr, 1=subs, 2=d, 3=subsArr, 4=rank, 5=flat, 6=k.
	private static ArrayMethod buildArefN(ConstantPool cp, Refs refs, ClassEntry objectArrayClass, ClassEntry longClass,
			MethodRefEntry longIntValue, MethodRefEntry doubleValueOf, MethodRefEntry arefN,
			@Nullable MethodRefEntry materialize) {
		int arr = 0, subs = 1, d = 2, subsArr = 3, rank = 4, flat = 5, k = 6;
		MethodCode a = new MethodCode();
		emitMaterialize(a, arr, materialize);
		emitQuantizedArm(a, refs, arr, qm -> {
			a.aload(arr);
			a.aload(subs);
			a.invokestatic(qm.arefN());
			a.areturn();
		});
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(subs);
			asm.checkcast(objectArrayClass);
			asm.astore(subsArr);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			emitHornerFlatIndex(asm, w, refs, d, subsArr, rank, flat, k);
			asm.aload(d);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.iload(flat);
			asm.iadd();
			w.loadElem(asm, refs.bf16Value());
			asm.invokestatic(doubleValueOf);
			asm.areturn();
		});
		a.aload(arr);
		a.aload(subs);
		a.invokestatic(arefN);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREFN), cp.addUtf8(JvmArrayRuntimeBuilder.AREFN_DESC), a);
	}

	// Pushes the subscript in local i checked against the flat bound d.length - off,
	// the total size: the index as an int (_ckBound, JvmOperandTypeRuntime).
	private static void emitFlatBounded(MethodCode a, Refs refs, int i, int d, int off) {
		a.aload(i);
		a.aload(d);
		a.arraylength();
		a.iload(off);
		a.isub();
		a.invokestatic(refs.ckBound());
	}

	// Pushes i * cols + j, each subscript checked against its own dimension (dim 0 read
	// from the header, cols in its local) so a column past its dimension is out of
	// range rather than folding into the next row.
	private static void emitFlat2Bounded(MethodCode a, JvmPackedFloatWidth w, Refs refs, int i, int j, int d,
			int cols) {
		a.aload(i);
		a.aload(d);
		a.loadConstant(0);
		w.loadDim(a);
		a.invokestatic(refs.ckBound());
		a.iload(cols);
		a.imul();
		a.aload(j);
		a.iload(cols);
		a.invokestatic(refs.ckBound());
		a.iadd();
	}

	// flat = 0; for k in 0..rank-1: flat = flat * dims[k] + subs[k], each subscript
	// checked against its own dimension. Starting the fold at 0 rather than at subs[0]
	// is what makes a RANK-0 packed array (no subscripts) answer the flat index 0 of its
	// single element.
	private static void emitHornerFlatIndex(MethodCode a, JvmPackedFloatWidth w, Refs refs, int d, int subsArr,
			int rank, int flat, int k) {
		a.loadConstant(0);
		a.istore(flat);
		a.loadConstant(0);
		a.istore(k);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(loop);
		a.iload(k);
		a.iload(rank);
		a.if_icmpge(done);
		a.iload(flat);
		a.aload(d);
		a.iload(k);
		w.loadDim(a);
		a.imul();
		a.aload(subsArr);
		a.iload(k);
		a.aaload();
		a.aload(d);
		a.iload(k);
		w.loadDim(a);
		a.invokestatic(refs.ckBound());
		a.iadd();
		a.istore(flat);
		a.iinc(k, 1);
		a.goto_(loop);
		a.labelBinding(done);
	}

	// The first half of the aset bodies: coerce val to a double in dval -- a non-real
	// is the store's type-error, reported before an out-of-range subscript is.
	private static void emitCoerce(MethodCode a, int val, int dval, ClassEntry numberClass,
			MethodRefEntry numberDoubleValue, MethodRefEntry dbl) {
		a.aload(val);
		a.invokestatic(dbl);
		a.checkcast(numberClass);
		a.invokevirtual(numberDoubleValue);
		a.dstore(dval);
	}

	// The tail of the aset bodies: report the write to the device runtime (--gpu), store
	// dval at idx, and return the value AS STORED.
	private static void emitStoreReturn(MethodCode a, JvmPackedFloatWidth w, Refs refs, int d, int idx, int dval,
			MethodRefEntry doubleValueOf, @Nullable MethodRefEntry written) {
		if (written != null) {
			// --gpu, BEFORE the store: a device copy that was the authoritative one comes
			// home first and is dropped, so the store lands on the array's real bytes --
			// which are the array the guard ANSWERS (the array, or a result stub's
			// backing), so the store goes into that.
			a.aload(d);
			a.invokestatic(written);
			a.checkcast(refs.arrayClass(w));
			a.astore(d);
		}
		a.aload(d);
		a.iload(idx);
		a.dload(dval);
		w.storeElem(a, refs.bf16Bits());
		// Return the coerced value as a Double, read back through the array's element
		// type so the narrowing (f64 -> f32 -> f64, or through the bfloat16 pair) is
		// reflected -- exactly what the interpreter returns.
		a.dload(dval);
		w.emitStoredValue(a, refs.bf16Value(), refs.bf16Bits());
		a.invokestatic(doubleValueOf);
		a.areturn();
	}

	// _fvAset1(arr, i, val): packed -> d[off + (int) i] = coerce(val), return the stored
	// value (matching the interpreter, which returns the coerced -- and narrowed --
	// value); else _aset1. Locals: 0=arr, 1=i, 2=val, 3=d, 4=rank, 5=idx, 6..7=dval.
	private static ArrayMethod buildAset1(ConstantPool cp, Refs refs, ClassEntry longClass, ClassEntry numberClass,
			MethodRefEntry longIntValue, MethodRefEntry numberDoubleValue, MethodRefEntry doubleValueOf,
			MethodRefEntry dbl, MethodRefEntry aset1, @Nullable MethodRefEntry written) {
		int arr = 0, i = 1, val = 2, d = 3, rank = 4, idx = 5, dval = 6;
		MethodCode a = new MethodCode();
		// A quantized matrix has no slot to store into (.kb/quantized-matrix.md): the
		// interpreter's sentence, word for word.
		StringEntry immutable = cp.stringEntry(am.ik.rontolisp.LispNames.ASET
				+ ": a quantized matrix is immutable (dequantize it into a packed float array to change it)");
		ClassEntry rtExForQuantized = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry rtExInitForQuantized = cp.methodRef(rtExForQuantized, "<init>", "(Ljava/lang/String;)V");
		emitQuantizedArm(a, refs, arr, qm -> emitThrow(a, rtExForQuantized, rtExInitForQuantized, immutable));
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			emitCoerce(asm, val, dval, numberClass, numberDoubleValue, dbl);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.istore(idx);
			asm.iload(idx);
			emitFlatBounded(asm, refs, i, d, idx);
			asm.iadd();
			asm.istore(idx);
			emitStoreReturn(asm, w, refs, d, idx, dval, doubleValueOf, written);
		});
		a.aload(arr);
		a.aload(i);
		a.aload(val);
		a.invokestatic(aset1);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASET1), cp.addUtf8(JvmArrayRuntimeBuilder.ASET1_DESC), a);
	}

	// _fvAset2(arr, i, j, val): packed store at i*cols+j; else _aset2.
	// Locals: 0=arr, 1=i, 2=j, 3=val, 4=d, 5=rank, 6=cols, 7=idx, 8..9=dval.
	private static ArrayMethod buildAset2(ConstantPool cp, Refs refs, ClassEntry longClass, ClassEntry numberClass,
			MethodRefEntry longIntValue, MethodRefEntry numberDoubleValue, MethodRefEntry doubleValueOf,
			MethodRefEntry dbl, MethodRefEntry aset2, @Nullable MethodRefEntry written) {
		int arr = 0, i = 1, j = 2, val = 3, d = 4, rank = 5, cols = 6, idx = 7, dval = 8;
		MethodCode a = new MethodCode();
		// A quantized matrix has no slot to store into (.kb/quantized-matrix.md): the
		// interpreter's sentence, word for word.
		StringEntry immutable = cp.stringEntry(am.ik.rontolisp.LispNames.ASET
				+ ": a quantized matrix is immutable (dequantize it into a packed float array to change it)");
		ClassEntry rtExForQuantized = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry rtExInitForQuantized = cp.methodRef(rtExForQuantized, "<init>", "(Ljava/lang/String;)V");
		emitQuantizedArm(a, refs, arr, qm -> emitThrow(a, rtExForQuantized, rtExInitForQuantized, immutable));
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			emitCoerce(asm, val, dval, numberClass, numberDoubleValue, dbl);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.aload(d);
			asm.loadConstant(1);
			w.loadDim(asm);
			asm.istore(cols);
			asm.iload(rank);
			w.emitDataOffset(asm);
			emitFlat2Bounded(asm, w, refs, i, j, d, cols);
			asm.iadd();
			asm.istore(idx);
			emitStoreReturn(asm, w, refs, d, idx, dval, doubleValueOf, written);
		});
		a.aload(arr);
		a.aload(i);
		a.aload(j);
		a.aload(val);
		a.invokestatic(aset2);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASET2), cp.addUtf8(JvmArrayRuntimeBuilder.ASET2_DESC), a);
	}

	// _fvAsetN(arr, subs, val): packed Horner store; else _asetN.
	// Locals: 0=arr, 1=subs, 2=val, 3=d, 4=subsArr, 5=rank, 6=flat, 7=k, 8=idx,
	// 9..10=dval.
	private static ArrayMethod buildAsetN(ConstantPool cp, Refs refs, ClassEntry objectArrayClass, ClassEntry longClass,
			ClassEntry numberClass, MethodRefEntry longIntValue, MethodRefEntry numberDoubleValue,
			MethodRefEntry doubleValueOf, MethodRefEntry dbl, MethodRefEntry asetN, @Nullable MethodRefEntry written) {
		int arr = 0, subs = 1, val = 2, d = 3, subsArr = 4, rank = 5, flat = 6, k = 7, idx = 8, dval = 9;
		MethodCode a = new MethodCode();
		// A quantized matrix has no slot to store into (.kb/quantized-matrix.md): the
		// interpreter's sentence, word for word.
		StringEntry immutable = cp.stringEntry(am.ik.rontolisp.LispNames.ASET
				+ ": a quantized matrix is immutable (dequantize it into a packed float array to change it)");
		ClassEntry rtExForQuantized = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry rtExInitForQuantized = cp.methodRef(rtExForQuantized, "<init>", "(Ljava/lang/String;)V");
		emitQuantizedArm(a, refs, arr, qm -> emitThrow(a, rtExForQuantized, rtExInitForQuantized, immutable));
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(subs);
			asm.checkcast(objectArrayClass);
			asm.astore(subsArr);
			emitCoerce(asm, val, dval, numberClass, numberDoubleValue, dbl);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			emitHornerFlatIndex(asm, w, refs, d, subsArr, rank, flat, k);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.iload(flat);
			asm.iadd();
			asm.istore(idx);
			emitStoreReturn(asm, w, refs, d, idx, dval, doubleValueOf, written);
		});
		a.aload(arr);
		a.aload(subs);
		a.aload(val);
		a.invokestatic(asetN);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASETN), cp.addUtf8(JvmArrayRuntimeBuilder.ASETN_DESC), a);
	}

	// _fvDims(arr): packed -> a fresh cons list of the header dims as Longs; else
	// _arrayDims. Locals: 0=arr, 1=d, 2=rank, 3=result, 4=j.
	private static ArrayMethod buildDims(ConstantPool cp, Refs refs, ClassEntry objectClass, MethodRefEntry longValueOf,
			MethodRefEntry arrayDims) {
		int arr = 0, d = 1, rank = 2, result = 3, j = 4;
		MethodCode a = new MethodCode();
		emitQuantizedArm(a, refs, arr, qm -> {
			a.aload(arr);
			a.invokestatic(qm.dims());
			a.areturn();
		});
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.aconst_null();
			asm.astore(result);
			asm.iload(rank);
			asm.loadConstant(1);
			asm.isub();
			asm.istore(j);
			MethodCode.Label loop = asm.newLabel();
			MethodCode.Label done = asm.newLabel();
			asm.labelBinding(loop);
			asm.iload(j);
			asm.iflt(done);
			asm.loadConstant(2);
			asm.anewarray(objectClass);
			asm.dup();
			asm.loadConstant(0);
			asm.aload(d);
			asm.iload(j);
			w.loadDim(asm);
			asm.i2l();
			asm.invokestatic(longValueOf);
			asm.aastore();
			asm.dup();
			asm.loadConstant(1);
			asm.aload(result);
			asm.aastore();
			asm.astore(result);
			asm.iinc(j, -1);
			asm.goto_(loop);
			asm.labelBinding(done);
			asm.aload(result);
			asm.areturn();
		});
		a.aload(arr);
		a.invokestatic(arrayDims);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(DIMS), cp.addUtf8(JvmArrayRuntimeBuilder.DIMS_DESC), a);
	}

	// _fvCheckRank(arr, given): packed -> the header rank (loaded the same way DIMS
	// reads it) compared against `given`; else delegate to _arrayCheckRank. Locals:
	// 0=arr, 1=given, 2=rank, 3=giv.
	private static ArrayMethod buildCheckRank(ConstantPool cp, Refs refs, ClassEntry longClass,
			MethodRefEntry longIntValue, ClassEntry rtExClass, MethodRefEntry rtExInit,
			MethodRefEntry checkRankDelegate) {
		ClassEntry sbClass = cp.classEntry("java/lang/StringBuilder");
		MethodRefEntry sbInit = cp.methodRef(sbClass, "<init>", "()V");
		MethodRefEntry sbAppendStr = cp.methodRef(sbClass, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
		MethodRefEntry sbAppendInt = cp.methodRef(sbClass, "append", "(I)Ljava/lang/StringBuilder;");
		MethodRefEntry sbToString = cp.methodRef(sbClass, "toString", "()Ljava/lang/String;");
		int arr = 0, given = 1, rank = 2, giv = 3;
		MethodCode a = new MethodCode();
		emitQuantizedArm(a, refs, arr, qm -> {
			a.aload(arr);
			a.checkcast(qm.byteArrayClass());
			a.loadConstant(4);
			a.invokestatic(qm.qmInt());
			a.istore(rank);
			emitRankCheckAndReturn(cp, a, longClass, longIntValue, sbClass, sbInit, sbAppendStr, sbAppendInt,
					sbToString, rtExClass, rtExInit, arr, given, rank, giv);
		});
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			w.loadRank(asm);
			asm.istore(rank);
			emitRankCheckAndReturn(cp, asm, longClass, longIntValue, sbClass, sbInit, sbAppendStr, sbAppendInt,
					sbToString, rtExClass, rtExInit, arr, given, rank, giv);
		});
		a.aload(arr);
		a.aload(given);
		a.invokestatic(checkRankDelegate);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(CHECK_RANK), cp.addUtf8(CHECK_RANK_DESC), a);
	}

	// Shared tail of _fvCheckRank: unbox `given` (givenSlot) to int (givSlot), compare it
	// against the already-computed actual rank (rankSlot); a match returns arr (arrSlot)
	// unchanged, a mismatch throws the "aref: expected N subscripts, got M" text
	// LispFloatArray#flatIndex uses in the interpreter.
	private static void emitRankCheckAndReturn(ConstantPool cp, MethodCode a, ClassEntry longClass,
			MethodRefEntry longIntValue, ClassEntry sbClass, MethodRefEntry sbInit, MethodRefEntry sbAppendStr,
			MethodRefEntry sbAppendInt, MethodRefEntry sbToString, ClassEntry rtExClass, MethodRefEntry rtExInit,
			int arrSlot, int givenSlot, int rankSlot, int givSlot) {
		a.aload(givenSlot);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(givSlot);
		MethodCode.Label ok = a.newLabel();
		a.iload(rankSlot);
		a.iload(givSlot);
		a.if_icmpeq(ok);
		a.new_(rtExClass);
		a.dup();
		a.new_(sbClass);
		a.dup();
		a.invokespecial(sbInit);
		a.ldc(cp.stringEntry("aref: expected "));
		a.invokevirtual(sbAppendStr);
		a.iload(rankSlot);
		a.invokevirtual(sbAppendInt);
		a.ldc(cp.addString(" subscripts, got ").entry());
		a.invokevirtual(sbAppendStr);
		a.iload(givSlot);
		a.invokevirtual(sbAppendInt);
		a.invokevirtual(sbToString);
		a.invokespecial(rtExInit);
		a.athrow();
		a.labelBinding(ok);
		a.aload(arrSlot);
		a.areturn();
	}

	// _fvLength(arr): packed rank-1 -> Long.valueOf(count); packed rank-n -> delegate via
	// _length(_fvToGeneral(arr)) for exact parity with the general array; else _length.
	// Locals: 0=arr, 1=d, 2=rank.
	private static ArrayMethod buildLength(ConstantPool cp, Refs refs, MethodRefEntry longValueOf,
			MethodRefEntry toGeneral, MethodRefEntry lengthHelper) {
		int arr = 0, d = 1, rank = 2;
		MethodCode a = new MethodCode();
		emitQuantizedArm(a, refs, arr, qm -> {
			a.aload(arr);
			a.invokestatic(qm.length());
			a.areturn();
		});
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			MethodCode.Label rankN = asm.newLabel();
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.iload(rank);
			asm.loadConstant(1);
			asm.if_icmpne(rankN);
			// rank 1: count = dim 0, the header's one dimension -- read from the header
			// and not from the Java length, because under --gpu a result stub is the
			// header alone (.kb/gpu.md, "Lazy results, and the result that has no host
			// array").
			asm.aload(d);
			asm.loadConstant(0);
			w.loadDim(asm);
			asm.i2l();
			asm.invokestatic(longValueOf);
			asm.areturn();
			asm.labelBinding(rankN);
			asm.aload(arr);
			asm.invokestatic(toGeneral);
			asm.invokestatic(lengthHelper);
			asm.areturn();
		});
		a.aload(arr);
		a.invokestatic(lengthHelper);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(LENGTH), cp.addUtf8(LENGTH_DESC), a);
	}

	// _fvMake / _sfvMake / _bfvMake(dims, init): build a packed array of the width with a
	// dimension header, filled with coerce(init) (default 0.0, narrowed to the width).
	// dims is a Long (rank-1 shorthand) or a cons list of Longs. Always produces a packed
	// array of the chosen width (the compiler routes here only for a packed
	// :element-type without fill-pointer/adjustable/displacement). Locals: 0=dims,
	// 1=init, 2..3=initVal, 4=rank, 5=total, 6=arr, 7=cur, 8=k, 9=off, 10=i, 11=dim,
	// 12=initBits (bfloat16: the pattern, narrowed once rather than per element).
	private static ArrayMethod buildMake(ConstantPool cp, JvmPackedFloatWidth w, String name, Refs refs,
			ClassEntry objectArrayClass, ClassEntry longClass, ClassEntry numberClass, MethodRefEntry longIntValue,
			MethodRefEntry numberDoubleValue, MethodRefEntry dbl) {
		int dims = 0, init = 1, initVal = 2, rank = 4, total = 5, arr = 6, cur = 7, k = 8, off = 9, i = 10, dim = 11,
				initBits = 12;
		MethodRefEntry arraysFillShort = cp.methodRef(cp.classEntry("java/util/Arrays"), "fill", "([SIIS)V");
		MethodCode a = new MethodCode();
		// initVal = init == null ? 0.0 : ((Number) _dbl(init)).doubleValue()
		MethodCode.Label haveInit = a.newLabel();
		MethodCode.Label initDone = a.newLabel();
		a.aload(init);
		a.ifnonnull(haveInit);
		a.dconst_0();
		a.dstore(initVal);
		a.goto_(initDone);
		a.labelBinding(haveInit);
		a.aload(init);
		a.invokestatic(dbl);
		a.checkcast(numberClass);
		a.invokevirtual(numberDoubleValue);
		a.dstore(initVal);
		a.labelBinding(initDone);
		if (w == BFLOAT16) {
			a.dload(initVal);
			a.invokestatic(refs.bf16Bits());
			a.istore(initBits);
		}
		// parse dims
		MethodCode.Label listCase = a.newLabel();
		MethodCode.Label fill = a.newLabel();
		a.aload(dims);
		a.instanceOf(longClass);
		a.ifeq(listCase);
		// rank-1 shorthand: total = (int) dims; arr = new [width][off + total];
		// arr[0]=1; dim 0 = total; off = dataOffset(1)
		a.aload(dims);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(total);
		a.loadConstant(1);
		a.istore(rank);
		a.loadConstant(w.dataOffset(1));
		a.istore(off);
		a.iload(off);
		a.iload(total);
		a.iadd();
		w.newBacking(a);
		a.astore(arr);
		a.aload(arr);
		a.loadConstant(1);
		w.storeRank(a);
		a.loadConstant(0);
		a.istore(k);
		a.iload(total);
		a.istore(dim);
		w.storeDim(a, arr, k, dim);
		a.goto_(fill);
		// cons list of dims: count rank + product, then allocate and write header
		a.labelBinding(listCase);
		a.loadConstant(0);
		a.istore(rank);
		a.loadConstant(1);
		a.istore(total);
		a.aload(dims);
		a.astore(cur);
		MethodCode.Label countLoop = a.newLabel();
		MethodCode.Label countDone = a.newLabel();
		a.labelBinding(countLoop);
		a.aload(cur);
		a.instanceOf(objectArrayClass);
		a.ifeq(countDone);
		a.iinc(rank, 1);
		a.iload(total);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.imul();
		a.istore(total);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.astore(cur);
		a.goto_(countLoop);
		a.labelBinding(countDone);
		a.iload(rank);
		w.emitDataOffset(a);
		a.istore(off);
		a.iload(off);
		a.iload(total);
		a.iadd();
		w.newBacking(a);
		a.astore(arr);
		a.aload(arr);
		a.iload(rank);
		w.storeRank(a);
		// write the dims into the header
		a.aload(dims);
		a.astore(cur);
		a.loadConstant(0);
		a.istore(k);
		MethodCode.Label dimLoop = a.newLabel();
		MethodCode.Label dimDone = a.newLabel();
		a.labelBinding(dimLoop);
		a.aload(cur);
		a.instanceOf(objectArrayClass);
		a.ifeq(dimDone);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(dim);
		w.storeDim(a, arr, k, dim);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.astore(cur);
		a.iinc(k, 1);
		a.goto_(dimLoop);
		a.labelBinding(dimDone);
		// fill data slots with initVal
		a.labelBinding(fill);
		if (w == BFLOAT16) {
			// Arrays.fill(arr, off, off + total, (short) initBits): the pattern was
			// narrowed once above, so a checkpoint-sized allocation does not pay for
			// the conversion per element.
			a.aload(arr);
			a.iload(off);
			a.iload(off);
			a.iload(total);
			a.iadd();
			a.iload(initBits);
			a.i2s();
			a.invokestatic(arraysFillShort);
		}
		else {
			a.loadConstant(0);
			a.istore(i);
			MethodCode.Label fillLoop = a.newLabel();
			MethodCode.Label fillDone = a.newLabel();
			a.labelBinding(fillLoop);
			a.iload(i);
			a.iload(total);
			a.if_icmpge(fillDone);
			a.aload(arr);
			a.iload(off);
			a.iload(i);
			a.iadd();
			a.dload(initVal);
			w.storeElem(a, refs.bf16Bits());
			a.iinc(i, 1);
			a.goto_(fillLoop);
			a.labelBinding(fillDone);
		}
		a.aload(arr);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(name), cp.addUtf8(MAKE_DESC), a);
	}

	// _fvElementType(arr): packed double[] -> the symbol double-float; packed float[] ->
	// the symbol single-float; packed short[] -> the symbol bfloat16; else the symbol t
	// (general arrays are element-type t, matching the lite expandArrayElementType).
	// Locals: 0=arr.
	private static ArrayMethod buildElementType(ConstantPool cp, Refs refs) {
		MethodCode a = new MethodCode();
		emitQuantizedArm(a, refs, 0, qm -> {
			a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.Q8_0));
			a.areturn();
		});
		emitWidthDispatch(a, refs, 0, (asm, w) -> {
			asm.ldc(cp.stringEntry(switch (w) {
				case DOUBLE -> am.ik.rontolisp.LispNames.DOUBLE_FLOAT;
				case SINGLE -> am.ik.rontolisp.LispNames.SINGLE_FLOAT;
				case BFLOAT16 -> am.ik.rontolisp.LispNames.BFLOAT16;
			}));
			asm.areturn();
		});
		a.ldc(cp.stringEntry("T"));
		a.areturn();
		// 2: the quantized arm's tag test (a byte[] slot and the tag) where an octet
		// vector can exist.
		return new ArrayMethod(cp.addUtf8(ELEMENT_TYPE), cp.addUtf8(ELEMENT_TYPE_DESC), a);
	}

	// _fvRequireGeneral(o): the fill-pointer-surface guard for a packed float array -- a
	// packed array has no fill pointer, adjustability or displacement, so those
	// operations reject it with a clear error (mirroring the interpreter's
	// requireGeneralArray and _ivRequireGeneral's packed-integer-vector twin); any other
	// value passes through unchanged. Locals: 0=o.
	private static ArrayMethod buildRequireGeneral(ConstantPool cp, Refs refs, ClassEntry rtExClass,
			MethodRefEntry rtExInit) {
		MethodCode a = new MethodCode();
		StringEntry message = cp.stringEntry("not applicable to a packed float array");
		StringEntry quantizedMessage = cp.stringEntry("not applicable to a quantized matrix");
		emitQuantizedArm(a, refs, 0, qm -> emitThrow(a, rtExClass, rtExInit, quantizedMessage));
		emitWidthDispatch(a, refs, 0, (asm, w) -> emitThrow(asm, rtExClass, rtExInit, message));
		a.aload(0);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(REQUIRE_GENERAL), cp.addUtf8(REQUIRE_GENERAL_DESC), a);
	}

	// _bf16Value(bits): BFloat16.value(int), instruction for instruction. Locals:
	// 0=bits, 1=b.
	//
	// b = bits & 0xffff;
	// if ((b & 0x7f80) == 0x7f80 && (b & 0x7f) != 0)
	// return Double.longBitsToDouble(((long) (b & 0x8000) << 48) | EXPONENT_MASK |
	// ((long) (b & 0x7f) << 45));
	// return Float.intBitsToFloat(b << 16); -- f2d is exact here: not a NaN.
	private static ArrayMethod buildBf16Value(ConstantPool cp, ClassEntry doubleClass, ClassEntry floatClass) {
		MethodRefEntry longBitsToDouble = cp.methodRef(doubleClass, "longBitsToDouble", "(J)D");
		MethodRefEntry intBitsToFloat = cp.methodRef(floatClass, "intBitsToFloat", "(I)F");
		int bits = 0, b = 1;
		MethodCode a = new MethodCode();
		a.iload(bits);
		JvmPackedFloatWidth.emitMaskU16(a);
		a.istore(b);
		MethodCode.Label ordinary = a.newLabel();
		a.iload(b);
		a.loadConstant(0x7f80);
		a.iand();
		a.loadConstant(0x7f80);
		a.if_icmpne(ordinary);
		a.iload(b);
		a.loadConstant(0x7f);
		a.iand();
		a.ifeq(ordinary);
		// NaN: the sign and the payload's top seven bits carried across by hand
		a.iload(b);
		a.loadConstant(0x7fff);
		a.loadConstant(1);
		a.iadd(); // 0x8000, which iconst cannot encode as a positive sipush
		a.iand();
		a.i2l();
		a.loadConstant(48);
		a.lshl();
		a.ldc(cp.entries().longEntry(EXPONENT_MASK));
		a.lor();
		a.iload(b);
		a.loadConstant(0x7f);
		a.iand();
		a.i2l();
		a.loadConstant(45);
		a.lshl();
		a.lor();
		a.invokestatic(longBitsToDouble);
		a.dreturn();
		a.labelBinding(ordinary);
		a.iload(b);
		a.loadConstant(16);
		a.ishl();
		a.invokestatic(intBitsToFloat);
		a.f2d();
		a.dreturn();
		return new ArrayMethod(cp.addUtf8(BF16_VALUE), cp.addUtf8(BF16_VALUE_DESC), a);
	}

	// _bf16Bits(value): BFloat16.bits(double) then bits(float), instruction for
	// instruction. Locals: 0..1=value, 2..3=l (the raw double bits), 4=f (the raw f32
	// bits), 5=payload.
	//
	// l = doubleToRawLongBits(value);
	// if ((l & EXPONENT_MASK) == EXPONENT_MASK && (l & MANTISSA_MASK) != 0) {
	// payload = (int) ((l >>> 45) & 0x7f);
	// return ((int) (l >>> 63) << 15) | 0x7f80 | (payload | ((payload - 1) >>> 31)); }
	// f = floatToRawIntBits((float) value);
	// if ((f & 0x7f800000) == 0x7f800000 && (f & 0x007fffff) != 0) {
	// payload = (f >>> 16) & 0x7f;
	// return (f >>> 16) & 0x8000 | 0x7f80 | (payload | ((payload - 1) >>> 31)); }
	// return ((f + 0x7fff + ((f >>> 16) & 1)) >>> 16) & 0xffff;
	private static ArrayMethod buildBf16Bits(ConstantPool cp, ClassEntry doubleClass, ClassEntry floatClass) {
		MethodRefEntry doubleToRawLongBits = cp.methodRef(doubleClass, "doubleToRawLongBits", "(D)J");
		MethodRefEntry floatToRawIntBits = cp.methodRef(floatClass, "floatToRawIntBits", "(F)I");
		int value = 0, l = 2, f = 4, payload = 5;
		MethodCode a = new MethodCode();
		a.dload(value);
		a.invokestatic(doubleToRawLongBits);
		a.lstore(l);
		MethodCode.Label viaFloat = a.newLabel();
		a.lload(l);
		a.ldc(cp.entries().longEntry(EXPONENT_MASK));
		a.land();
		a.ldc(cp.entries().longEntry(EXPONENT_MASK));
		a.lcmp();
		a.ifne(viaFloat);
		a.lload(l);
		a.ldc(cp.entries().longEntry(MANTISSA_MASK));
		a.land();
		a.lconst_0();
		a.lcmp();
		a.ifeq(viaFloat);
		// a double NaN
		a.lload(l);
		a.loadConstant(45);
		a.lushr();
		a.l2i();
		a.loadConstant(0x7f);
		a.iand();
		a.istore(payload);
		a.lload(l);
		a.loadConstant(63);
		a.lushr();
		a.l2i();
		a.loadConstant(15);
		a.ishl();
		a.loadConstant(0x7f80);
		a.ior();
		emitPayloadOrOne(a, payload);
		a.ior();
		a.ireturn();
		a.labelBinding(viaFloat);
		a.dload(value);
		a.d2f();
		a.invokestatic(floatToRawIntBits);
		a.istore(f);
		MethodCode.Label roundToNearestEven = a.newLabel();
		a.iload(f);
		a.ldc(cp.entries().intEntry(0x7f800000));
		a.iand();
		a.ldc(cp.entries().intEntry(0x7f800000));
		a.if_icmpne(roundToNearestEven);
		a.iload(f);
		a.ldc(cp.entries().intEntry(0x007fffff));
		a.iand();
		a.ifeq(roundToNearestEven);
		// an f32 NaN (unreachable from a double that was not one, kept for the mirror)
		a.iload(f);
		a.loadConstant(16);
		a.iushr();
		a.loadConstant(0x7f);
		a.iand();
		a.istore(payload);
		a.iload(f);
		a.loadConstant(16);
		a.iushr();
		a.loadConstant(0x7fff);
		a.loadConstant(1);
		a.iadd(); // 0x8000
		a.iand();
		a.loadConstant(0x7f80);
		a.ior();
		emitPayloadOrOne(a, payload);
		a.ior();
		a.ireturn();
		a.labelBinding(roundToNearestEven);
		a.iload(f);
		a.loadConstant(0x7fff);
		a.iadd();
		a.iload(f);
		a.loadConstant(16);
		a.iushr();
		a.loadConstant(1);
		a.iand();
		a.iadd();
		a.loadConstant(16);
		a.iushr();
		JvmPackedFloatWidth.emitMaskU16(a);
		a.ireturn();
		return new ArrayMethod(cp.addUtf8(BF16_BITS), cp.addUtf8(BF16_BITS_DESC), a);
	}

	// stack: (...) -> (..., int): payload | ((payload - 1) >>> 31) -- a zero payload
	// becomes one, so a NaN never comes back as an infinity. Branch-free, as the
	// authority spells it.
	private static void emitPayloadOrOne(MethodCode a, int payload) {
		a.iload(payload);
		a.iload(payload);
		a.loadConstant(1);
		a.isub();
		a.loadConstant(31);
		a.iushr();
		a.ior();
	}

	// _bf16Print(bits): the Float whose Float.toString (with the FloatText E -> e
	// rewrite _lispToString applies) is FloatText.bfloat16Text of the pattern's value:
	// the widened value for NaN, an infinity and a zero, else the first of the
	// 1..9-significant-digit roundings of the value that narrows back to the same
	// pattern. Locals: 0=bits, 1=f, 2=digits, 3=candidate.
	private static ArrayMethod buildBf16Print(ConstantPool cp, ClassEntry floatClass, MethodRefEntry floatValueOf,
			MethodRefEntry bf16Bits) {
		ClassEntry bigDecimalClass = cp.classEntry("java/math/BigDecimal");
		ClassEntry mathContextClass = cp.classEntry("java/math/MathContext");
		MethodRefEntry intBitsToFloat = cp.methodRef(floatClass, "intBitsToFloat", "(I)F");
		MethodRefEntry floatIsNaN = cp.methodRef(floatClass, "isNaN", "(F)Z");
		MethodRefEntry floatIsInfinite = cp.methodRef(floatClass, "isInfinite", "(F)Z");
		MethodRefEntry bigDecimalInit = cp.methodRef(bigDecimalClass, "<init>", "(D)V");
		MethodRefEntry mathContextInit = cp.methodRef(mathContextClass, "<init>", "(I)V");
		MethodRefEntry bigDecimalRound = cp.methodRef(bigDecimalClass, "round",
				"(Ljava/math/MathContext;)Ljava/math/BigDecimal;");
		MethodRefEntry bigDecimalFloatValue = cp.methodRef(bigDecimalClass, "floatValue", "()F");
		int bits = 0, f = 1, digits = 2, candidate = 3;
		MethodCode a = new MethodCode();
		a.iload(bits);
		JvmPackedFloatWidth.emitMaskU16(a);
		a.istore(bits);
		a.iload(bits);
		a.loadConstant(16);
		a.ishl();
		a.invokestatic(intBitsToFloat);
		a.fstore(f);
		MethodCode.Label asIs = a.newLabel();
		a.fload(f);
		a.invokestatic(floatIsNaN);
		a.ifne(asIs);
		a.fload(f);
		a.invokestatic(floatIsInfinite);
		a.ifne(asIs);
		a.fload(f);
		a.fconst_0();
		a.fcmpl();
		a.ifeq(asIs);
		a.loadConstant(1);
		a.istore(digits);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label next = a.newLabel();
		a.labelBinding(loop);
		a.iload(digits);
		a.loadConstant(9);
		a.if_icmpgt(asIs);
		// candidate = new BigDecimal((double) f).round(new
		// MathContext(digits)).floatValue()
		a.new_(bigDecimalClass);
		a.dup();
		a.fload(f);
		a.f2d();
		a.invokespecial(bigDecimalInit);
		a.new_(mathContextClass);
		a.dup();
		a.iload(digits);
		a.invokespecial(mathContextInit);
		a.invokevirtual(bigDecimalRound);
		a.invokevirtual(bigDecimalFloatValue);
		a.fstore(candidate);
		// if (_bf16Bits((double) candidate) == bits) return Float.valueOf(candidate)
		a.fload(candidate);
		a.f2d();
		a.invokestatic(bf16Bits);
		a.iload(bits);
		a.if_icmpne(next);
		a.fload(candidate);
		a.invokestatic(floatValueOf);
		a.areturn();
		a.labelBinding(next);
		a.iinc(digits, 1);
		a.goto_(loop);
		a.labelBinding(asIs);
		a.fload(f);
		a.invokestatic(floatValueOf);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(BF16_PRINT), cp.addUtf8(BF16_PRINT_DESC), a);
	}

	// new RuntimeException(message); athrow.
	private static void emitThrow(MethodCode a, ClassEntry rtExClass, MethodRefEntry rtExInit, StringEntry message) {
		a.new_(rtExClass);
		a.dup();
		a.ldc(message);
		a.invokespecial(rtExInit);
		a.athrow();
	}

}
