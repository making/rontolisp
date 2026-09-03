package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
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

	/** The constant-pool references one emitted body needs, per width. */
	private record Refs(ClassConstant doubleArrayClass, ClassConstant floatArrayClass, ClassConstant shortArrayClass,
			MethodrefConstant bf16Value, MethodrefConstant bf16Bits) {

		ClassConstant arrayClass(JvmPackedFloatWidth w) {
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
	 * @return the helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp, ClassConstant objectClass, ClassConstant objectArrayClass,
			ClassConstant selfClass, @Nullable MethodrefConstant written, @Nullable MethodrefConstant materialize) {
		ClassConstant doubleArrayClass = cp.addClass(cp.addUtf8("[D"));
		ClassConstant floatArrayClass = cp.addClass(cp.addUtf8("[F"));
		ClassConstant shortArrayClass = cp.addClass(cp.addUtf8("[S"));
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		ClassConstant floatClass = cp.addClass(cp.addUtf8("java/lang/Float"));
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		ClassConstant rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant rtExInit = cp.addMethodref(rtExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant alInit = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant alAdd = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant longIntValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant doubleValueOf = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
		MethodrefConstant numberDoubleValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));
		MethodrefConstant floatValueOf = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(F)Ljava/lang/Float;")));
		// Self-referencing static helpers to delegate to / reuse.
		MethodrefConstant dbl = self(cp, selfClass, JvmNumericRuntimeBuilder.DBL, "(" + OBJ + ")" + OBJ);
		MethodrefConstant lengthHelper = self(cp, selfClass, JvmLengthRuntimeBuilder.METHOD,
				JvmLengthRuntimeBuilder.DESC);
		MethodrefConstant toGeneral = self(cp, selfClass, TO_GENERAL, TO_GENERAL_DESC);
		MethodrefConstant aref1 = self(cp, selfClass, JvmArrayRuntimeBuilder.AREF1, JvmArrayRuntimeBuilder.AREF1_DESC);
		MethodrefConstant aref2 = self(cp, selfClass, JvmArrayRuntimeBuilder.AREF2, JvmArrayRuntimeBuilder.AREF2_DESC);
		MethodrefConstant arefN = self(cp, selfClass, JvmArrayRuntimeBuilder.AREFN, JvmArrayRuntimeBuilder.AREFN_DESC);
		MethodrefConstant aset1 = self(cp, selfClass, JvmArrayRuntimeBuilder.ASET1, JvmArrayRuntimeBuilder.ASET1_DESC);
		MethodrefConstant aset2 = self(cp, selfClass, JvmArrayRuntimeBuilder.ASET2, JvmArrayRuntimeBuilder.ASET2_DESC);
		MethodrefConstant asetN = self(cp, selfClass, JvmArrayRuntimeBuilder.ASETN, JvmArrayRuntimeBuilder.ASETN_DESC);
		MethodrefConstant arrayDims = self(cp, selfClass, JvmArrayRuntimeBuilder.DIMS,
				JvmArrayRuntimeBuilder.DIMS_DESC);
		MethodrefConstant arrayCheckRank = self(cp, selfClass, JvmArrayRuntimeBuilder.CHECK_RANK,
				JvmArrayRuntimeBuilder.CHECK_RANK_DESC);
		MethodrefConstant bf16Value = self(cp, selfClass, BF16_VALUE, BF16_VALUE_DESC);
		MethodrefConstant bf16Bits = self(cp, selfClass, BF16_BITS, BF16_BITS_DESC);
		MethodrefConstant bf16Print = self(cp, selfClass, BF16_PRINT, BF16_PRINT_DESC);
		Refs refs = new Refs(doubleArrayClass, floatArrayClass, shortArrayClass, bf16Value, bf16Bits);

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

	private static MethodrefConstant self(ConstantPool cp, ClassConstant selfClass, String name, String desc) {
		return cp.addMethodref(selfClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	// _fvToGeneral(o): convert a packed array into an equivalent general array (an
	// ArrayList whose slot 0 is the {dims, null, null} header and slots 1.. are boxed
	// Doubles), so the existing _arrayToString / equality / coercion helpers render and
	// compare it exactly like a general double array. Only ever called with a packed
	// array (the print/length dispatch tests instanceof first), so it dispatches the
	// three widths with no general fallback. Locals: 0=o, 1=d (array), 2=rank, 3=off,
	// 4=total, 5=dimsArr, 6=list, 7=k, 8=f.
	private static ArrayMethod buildToGeneral(ConstantPool cp, String name, Refs refs, ClassConstant arrayListClass,
			ClassConstant objectClass, MethodrefConstant alInit, MethodrefConstant alAdd, MethodrefConstant longValueOf,
			MethodrefConstant doubleValueOf, @Nullable MethodrefConstant floatValueOf,
			@Nullable MethodrefConstant bf16Print, @Nullable MethodrefConstant materialize) {
		JvmAsm a = new JvmAsm();
		// --gpu: every element is about to be read; a result the device still holds comes
		// home first. Once, for the whole array, ahead of the loop.
		emitMaterialize(a, 0, materialize);
		for (int i = 0; i < WIDTHS.length; i++) {
			JvmPackedFloatWidth w = WIDTHS[i];
			int next = a.label();
			if (i < WIDTHS.length - 1) {
				a.aload(0);
				a.instanceOf(refs.arrayClass(w));
				a.branch(Opcode.IFEQ, next);
			}
			emitToGeneralBody(a, w, refs, arrayListClass, objectClass, alInit, alAdd, longValueOf, doubleValueOf,
					floatValueOf, bf16Print);
			a.bind(next);
		}
		return new ArrayMethod(cp.addUtf8(name), cp.addUtf8(TO_GENERAL_DESC), 8, 9, a.finish());
	}

	/**
	 * {@code local = _gpuMaterialize(local)} when the GPU runtime is emitted; nothing
	 * otherwise. The guard answers the array to READ -- the array itself, or a result
	 * stub's backing -- so the local is rebound to it and every read below sees the
	 * bytes.
	 */
	private static void emitMaterialize(JvmAsm a, int local, @Nullable MethodrefConstant materialize) {
		if (materialize != null) {
			a.aload(local);
			a.invokestatic(materialize);
			a.astore(local);
		}
	}

	// floatValueOf/bf16Print non-null select the print-only boxing: a single-float
	// element is boxed as a Float (no widening) and a bfloat16 element as its shortest
	// round-tripping Float, so the renderer can spell each at its own width.
	private static void emitToGeneralBody(JvmAsm a, JvmPackedFloatWidth w, Refs refs, ClassConstant arrayListClass,
			ClassConstant objectClass, MethodrefConstant alInit, MethodrefConstant alAdd, MethodrefConstant longValueOf,
			MethodrefConstant doubleValueOf, @Nullable MethodrefConstant floatValueOf,
			@Nullable MethodrefConstant bf16Print) {
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
		a.op(Opcode.ISUB);
		a.istore(total);
		a.iload(rank);
		a.anewarray(objectClass);
		a.astore(dimsArr);
		a.iconst(0);
		a.istore(k);
		int kLoop = a.label();
		int kDone = a.label();
		a.bind(kLoop);
		a.iload(k);
		a.iload(rank);
		a.branch(Opcode.IF_ICMPGE, kDone);
		a.aload(dimsArr);
		a.iload(k);
		a.aload(d);
		a.iload(k);
		w.loadDim(a);
		a.op(Opcode.I2L);
		a.invokestatic(longValueOf);
		a.aastore();
		a.iinc(k, 1);
		a.branch(Opcode.GOTO, kLoop);
		a.bind(kDone);
		a.anew(arrayListClass);
		a.dup();
		a.invokespecial(alInit);
		a.astore(list);
		a.aload(list);
		a.iconst(3);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.aload(dimsArr);
		a.aastore();
		a.invokevirtual(alAdd);
		a.pop();
		a.iconst(0);
		a.istore(f);
		int fLoop = a.label();
		int fDone = a.label();
		a.bind(fLoop);
		a.iload(f);
		a.iload(total);
		a.branch(Opcode.IF_ICMPGE, fDone);
		a.aload(list);
		a.aload(d);
		a.iload(off);
		a.iload(f);
		a.op(Opcode.IADD);
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
		a.branch(Opcode.GOTO, fLoop);
		a.bind(fDone);
		a.aload(list);
		a.areturn();
	}

	// Emits the width-dispatch skeleton every accessor shares: for each width in
	// dispatch order, "if (arr instanceof <width class>) { <body> }", then the general
	// fallback the caller emits after this returns. The body must leave the method
	// (areturn) on every path.
	private interface Body {

		void emit(JvmAsm a, JvmPackedFloatWidth w);

	}

	private static void emitWidthDispatch(JvmAsm a, Refs refs, int arr, Body body) {
		for (JvmPackedFloatWidth w : WIDTHS) {
			int next = a.label();
			a.aload(arr);
			a.instanceOf(refs.arrayClass(w));
			a.branch(Opcode.IFEQ, next);
			body.emit(a, w);
			a.bind(next);
		}
	}

	// _fvAref1(arr, i): packed -> Double.valueOf(d[off + (int) i]); else _aref1.
	// Serves rank-1 aref and row-major-aref (rank read from the header). Locals:
	// 0=arr, 1=i, 2=d, 3=rank.
	private static ArrayMethod buildAref1(ConstantPool cp, Refs refs, ClassConstant longClass,
			MethodrefConstant longIntValue, MethodrefConstant doubleValueOf, MethodrefConstant aref1,
			@Nullable MethodrefConstant materialize) {
		int arr = 0, i = 1, d = 2, rank = 3;
		JvmAsm a = new JvmAsm();
		// --gpu: the element read below must see the device's bytes if it holds them.
		emitMaterialize(a, arr, materialize);
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.aload(d);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.aload(i);
			asm.checkcast(longClass);
			asm.invokevirtual(longIntValue);
			asm.op(Opcode.IADD);
			w.loadElem(asm, refs.bf16Value());
			asm.invokestatic(doubleValueOf);
			asm.areturn();
		});
		a.aload(arr);
		a.aload(i);
		a.invokestatic(aref1);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREF1), cp.addUtf8(JvmArrayRuntimeBuilder.AREF1_DESC), 5, 4, a.finish());
	}

	// _fvAref2(arr, i, j): packed -> Double.valueOf(d[off + i * cols + j]) with
	// cols = dim 1; else _aref2. Locals: 0=arr, 1=i, 2=j, 3=d, 4=rank, 5=cols.
	private static ArrayMethod buildAref2(ConstantPool cp, Refs refs, ClassConstant longClass,
			MethodrefConstant longIntValue, MethodrefConstant doubleValueOf, MethodrefConstant aref2,
			@Nullable MethodrefConstant materialize) {
		int arr = 0, i = 1, j = 2, d = 3, rank = 4, cols = 5;
		JvmAsm a = new JvmAsm();
		emitMaterialize(a, arr, materialize);
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.aload(d);
			asm.iconst(1);
			w.loadDim(asm);
			asm.istore(cols);
			asm.aload(d);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.aload(i);
			asm.checkcast(longClass);
			asm.invokevirtual(longIntValue);
			asm.iload(cols);
			asm.op(Opcode.IMUL);
			asm.op(Opcode.IADD);
			asm.aload(j);
			asm.checkcast(longClass);
			asm.invokevirtual(longIntValue);
			asm.op(Opcode.IADD);
			w.loadElem(asm, refs.bf16Value());
			asm.invokestatic(doubleValueOf);
			asm.areturn();
		});
		a.aload(arr);
		a.aload(i);
		a.aload(j);
		a.invokestatic(aref2);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREF2), cp.addUtf8(JvmArrayRuntimeBuilder.AREF2_DESC), 8, 6, a.finish());
	}

	// _fvArefN(arr, subs): packed -> Horner flat index over the header dims; else _arefN.
	// Locals: 0=arr, 1=subs, 2=d, 3=subsArr, 4=rank, 5=flat, 6=k.
	private static ArrayMethod buildArefN(ConstantPool cp, Refs refs, ClassConstant objectArrayClass,
			ClassConstant longClass, MethodrefConstant longIntValue, MethodrefConstant doubleValueOf,
			MethodrefConstant arefN, @Nullable MethodrefConstant materialize) {
		int arr = 0, subs = 1, d = 2, subsArr = 3, rank = 4, flat = 5, k = 6;
		JvmAsm a = new JvmAsm();
		emitMaterialize(a, arr, materialize);
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
			emitHornerFlatIndex(asm, w, d, subsArr, rank, flat, k, longClass, longIntValue);
			asm.aload(d);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.iload(flat);
			asm.op(Opcode.IADD);
			w.loadElem(asm, refs.bf16Value());
			asm.invokestatic(doubleValueOf);
			asm.areturn();
		});
		a.aload(arr);
		a.aload(subs);
		a.invokestatic(arefN);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREFN), cp.addUtf8(JvmArrayRuntimeBuilder.AREFN_DESC), 8, 7, a.finish());
	}

	// flat = 0; for k in 0..rank-1: flat = flat * dims[k] + subs[k]. Starting the fold
	// at 0 rather than at subs[0] is what makes a RANK-0 packed array (no subscripts)
	// answer the flat index 0 of its single element.
	private static void emitHornerFlatIndex(JvmAsm a, JvmPackedFloatWidth w, int d, int subsArr, int rank, int flat,
			int k, ClassConstant longClass, MethodrefConstant longIntValue) {
		a.iconst(0);
		a.istore(flat);
		a.iconst(0);
		a.istore(k);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(k);
		a.iload(rank);
		a.branch(Opcode.IF_ICMPGE, done);
		a.iload(flat);
		a.aload(d);
		a.iload(k);
		w.loadDim(a);
		a.op(Opcode.IMUL);
		a.aload(subsArr);
		a.iload(k);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IADD);
		a.istore(flat);
		a.iinc(k, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
	}

	// Common tail of the aset bodies: coerce val to a double in dval, report the write
	// to the device runtime (--gpu), store at idx, and return the value AS STORED.
	private static void emitCoerceStoreReturn(JvmAsm a, JvmPackedFloatWidth w, Refs refs, int val, int d, int idx,
			int dval, ClassConstant numberClass, MethodrefConstant numberDoubleValue, MethodrefConstant doubleValueOf,
			MethodrefConstant dbl, @Nullable MethodrefConstant written) {
		a.aload(val);
		a.invokestatic(dbl);
		a.checkcast(numberClass);
		a.invokevirtual(numberDoubleValue);
		a.dstore(dval);
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
	private static ArrayMethod buildAset1(ConstantPool cp, Refs refs, ClassConstant longClass,
			ClassConstant numberClass, MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue,
			MethodrefConstant doubleValueOf, MethodrefConstant dbl, MethodrefConstant aset1,
			@Nullable MethodrefConstant written) {
		int arr = 0, i = 1, val = 2, d = 3, rank = 4, idx = 5, dval = 6;
		JvmAsm a = new JvmAsm();
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.aload(i);
			asm.checkcast(longClass);
			asm.invokevirtual(longIntValue);
			asm.op(Opcode.IADD);
			asm.istore(idx);
			emitCoerceStoreReturn(asm, w, refs, val, d, idx, dval, numberClass, numberDoubleValue, doubleValueOf, dbl,
					written);
		});
		a.aload(arr);
		a.aload(i);
		a.aload(val);
		a.invokestatic(aset1);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASET1), cp.addUtf8(JvmArrayRuntimeBuilder.ASET1_DESC), 5, 8, a.finish());
	}

	// _fvAset2(arr, i, j, val): packed store at i*cols+j; else _aset2.
	// Locals: 0=arr, 1=i, 2=j, 3=val, 4=d, 5=rank, 6=cols, 7=idx, 8..9=dval.
	private static ArrayMethod buildAset2(ConstantPool cp, Refs refs, ClassConstant longClass,
			ClassConstant numberClass, MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue,
			MethodrefConstant doubleValueOf, MethodrefConstant dbl, MethodrefConstant aset2,
			@Nullable MethodrefConstant written) {
		int arr = 0, i = 1, j = 2, val = 3, d = 4, rank = 5, cols = 6, idx = 7, dval = 8;
		JvmAsm a = new JvmAsm();
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.aload(d);
			asm.iconst(1);
			w.loadDim(asm);
			asm.istore(cols);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.aload(i);
			asm.checkcast(longClass);
			asm.invokevirtual(longIntValue);
			asm.iload(cols);
			asm.op(Opcode.IMUL);
			asm.op(Opcode.IADD);
			asm.aload(j);
			asm.checkcast(longClass);
			asm.invokevirtual(longIntValue);
			asm.op(Opcode.IADD);
			asm.istore(idx);
			emitCoerceStoreReturn(asm, w, refs, val, d, idx, dval, numberClass, numberDoubleValue, doubleValueOf, dbl,
					written);
		});
		a.aload(arr);
		a.aload(i);
		a.aload(j);
		a.aload(val);
		a.invokestatic(aset2);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASET2), cp.addUtf8(JvmArrayRuntimeBuilder.ASET2_DESC), 8, 10, a.finish());
	}

	// _fvAsetN(arr, subs, val): packed Horner store; else _asetN.
	// Locals: 0=arr, 1=subs, 2=val, 3=d, 4=subsArr, 5=rank, 6=flat, 7=k, 8=idx,
	// 9..10=dval.
	private static ArrayMethod buildAsetN(ConstantPool cp, Refs refs, ClassConstant objectArrayClass,
			ClassConstant longClass, ClassConstant numberClass, MethodrefConstant longIntValue,
			MethodrefConstant numberDoubleValue, MethodrefConstant doubleValueOf, MethodrefConstant dbl,
			MethodrefConstant asetN, @Nullable MethodrefConstant written) {
		int arr = 0, subs = 1, val = 2, d = 3, subsArr = 4, rank = 5, flat = 6, k = 7, idx = 8, dval = 9;
		JvmAsm a = new JvmAsm();
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
			emitHornerFlatIndex(asm, w, d, subsArr, rank, flat, k, longClass, longIntValue);
			asm.iload(rank);
			w.emitDataOffset(asm);
			asm.iload(flat);
			asm.op(Opcode.IADD);
			asm.istore(idx);
			emitCoerceStoreReturn(asm, w, refs, val, d, idx, dval, numberClass, numberDoubleValue, doubleValueOf, dbl,
					written);
		});
		a.aload(arr);
		a.aload(subs);
		a.aload(val);
		a.invokestatic(asetN);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASETN), cp.addUtf8(JvmArrayRuntimeBuilder.ASETN_DESC), 8, 11, a.finish());
	}

	// _fvDims(arr): packed -> a fresh cons list of the header dims as Longs; else
	// _arrayDims. Locals: 0=arr, 1=d, 2=rank, 3=result, 4=j.
	private static ArrayMethod buildDims(ConstantPool cp, Refs refs, ClassConstant objectClass,
			MethodrefConstant longValueOf, MethodrefConstant arrayDims) {
		int arr = 0, d = 1, rank = 2, result = 3, j = 4;
		JvmAsm a = new JvmAsm();
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.aconstNull();
			asm.astore(result);
			asm.iload(rank);
			asm.iconst(1);
			asm.op(Opcode.ISUB);
			asm.istore(j);
			int loop = asm.label();
			int done = asm.label();
			asm.bind(loop);
			asm.iload(j);
			asm.branch(Opcode.IFLT, done);
			asm.iconst(2);
			asm.anewarray(objectClass);
			asm.dup();
			asm.iconst(0);
			asm.aload(d);
			asm.iload(j);
			w.loadDim(asm);
			asm.op(Opcode.I2L);
			asm.invokestatic(longValueOf);
			asm.aastore();
			asm.dup();
			asm.iconst(1);
			asm.aload(result);
			asm.aastore();
			asm.astore(result);
			asm.iinc(j, -1);
			asm.branch(Opcode.GOTO, loop);
			asm.bind(done);
			asm.aload(result);
			asm.areturn();
		});
		a.aload(arr);
		a.invokestatic(arrayDims);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(DIMS), cp.addUtf8(JvmArrayRuntimeBuilder.DIMS_DESC), 9, 5, a.finish());
	}

	// _fvCheckRank(arr, given): packed -> the header rank (loaded the same way DIMS
	// reads it) compared against `given`; else delegate to _arrayCheckRank. Locals:
	// 0=arr, 1=given, 2=rank, 3=giv.
	private static ArrayMethod buildCheckRank(ConstantPool cp, Refs refs, ClassConstant longClass,
			MethodrefConstant longIntValue, ClassConstant rtExClass, MethodrefConstant rtExInit,
			MethodrefConstant checkRankDelegate) {
		ClassConstant sbClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = cp.addMethodref(sbClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant sbAppendStr = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbAppendInt = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		int arr = 0, given = 1, rank = 2, giv = 3;
		JvmAsm a = new JvmAsm();
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
		return new ArrayMethod(cp.addUtf8(CHECK_RANK), cp.addUtf8(CHECK_RANK_DESC), 6, 4, a.finish());
	}

	// Shared tail of _fvCheckRank: unbox `given` (givenSlot) to int (givSlot), compare it
	// against the already-computed actual rank (rankSlot); a match returns arr (arrSlot)
	// unchanged, a mismatch throws the "aref: expected N subscripts, got M" text
	// LispFloatArray#flatIndex uses in the interpreter.
	private static void emitRankCheckAndReturn(ConstantPool cp, JvmAsm a, ClassConstant longClass,
			MethodrefConstant longIntValue, ClassConstant sbClass, MethodrefConstant sbInit,
			MethodrefConstant sbAppendStr, MethodrefConstant sbAppendInt, MethodrefConstant sbToString,
			ClassConstant rtExClass, MethodrefConstant rtExInit, int arrSlot, int givenSlot, int rankSlot,
			int givSlot) {
		a.aload(givenSlot);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(givSlot);
		int ok = a.label();
		a.iload(rankSlot);
		a.iload(givSlot);
		a.branch(Opcode.IF_ICMPEQ, ok);
		a.anew(rtExClass);
		a.dup();
		a.anew(sbClass);
		a.dup();
		a.invokespecial(sbInit);
		a.ldcString(cp.addString("aref: expected "));
		a.invokevirtual(sbAppendStr);
		a.iload(rankSlot);
		a.invokevirtual(sbAppendInt);
		a.ldcString(cp.addString(" subscripts, got "));
		a.invokevirtual(sbAppendStr);
		a.iload(givSlot);
		a.invokevirtual(sbAppendInt);
		a.invokevirtual(sbToString);
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
		a.bind(ok);
		a.aload(arrSlot);
		a.areturn();
	}

	// _fvLength(arr): packed rank-1 -> Long.valueOf(count); packed rank-n -> delegate via
	// _length(_fvToGeneral(arr)) for exact parity with the general array; else _length.
	// Locals: 0=arr, 1=d, 2=rank.
	private static ArrayMethod buildLength(ConstantPool cp, Refs refs, MethodrefConstant longValueOf,
			MethodrefConstant toGeneral, MethodrefConstant lengthHelper) {
		int arr = 0, d = 1, rank = 2;
		JvmAsm a = new JvmAsm();
		emitWidthDispatch(a, refs, arr, (asm, w) -> {
			int rankN = asm.label();
			asm.aload(arr);
			asm.checkcast(refs.arrayClass(w));
			asm.astore(d);
			asm.aload(d);
			w.loadRank(asm);
			asm.istore(rank);
			asm.iload(rank);
			asm.iconst(1);
			asm.branch(Opcode.IF_ICMPNE, rankN);
			// rank 1: count = dim 0, the header's one dimension -- read from the header
			// and not from the Java length, because under --gpu a result stub is the
			// header alone (.kb/gpu.md, "Lazy results, and the result that has no host
			// array").
			asm.aload(d);
			asm.iconst(0);
			w.loadDim(asm);
			asm.op(Opcode.I2L);
			asm.invokestatic(longValueOf);
			asm.areturn();
			asm.bind(rankN);
			asm.aload(arr);
			asm.invokestatic(toGeneral);
			asm.invokestatic(lengthHelper);
			asm.areturn();
		});
		a.aload(arr);
		a.invokestatic(lengthHelper);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(LENGTH), cp.addUtf8(LENGTH_DESC), 6, 3, a.finish());
	}

	// _fvMake / _sfvMake / _bfvMake(dims, init): build a packed array of the width with a
	// dimension header, filled with coerce(init) (default 0.0, narrowed to the width).
	// dims is a Long (rank-1 shorthand) or a cons list of Longs. Always produces a packed
	// array of the chosen width (the compiler routes here only for a packed
	// :element-type without fill-pointer/adjustable/displacement). Locals: 0=dims,
	// 1=init, 2..3=initVal, 4=rank, 5=total, 6=arr, 7=cur, 8=k, 9=off, 10=i, 11=dim,
	// 12=initBits (bfloat16: the pattern, narrowed once rather than per element).
	private static ArrayMethod buildMake(ConstantPool cp, JvmPackedFloatWidth w, String name, Refs refs,
			ClassConstant objectArrayClass, ClassConstant longClass, ClassConstant numberClass,
			MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue, MethodrefConstant dbl) {
		int dims = 0, init = 1, initVal = 2, rank = 4, total = 5, arr = 6, cur = 7, k = 8, off = 9, i = 10, dim = 11,
				initBits = 12;
		MethodrefConstant arraysFillShort = cp.addMethodref(cp.addClass(cp.addUtf8("java/util/Arrays")),
				cp.addNameAndType(cp.addUtf8("fill"), cp.addUtf8("([SIIS)V")));
		JvmAsm a = new JvmAsm();
		// initVal = init == null ? 0.0 : ((Number) _dbl(init)).doubleValue()
		int haveInit = a.label();
		int initDone = a.label();
		a.aload(init);
		a.branch(Opcode.IFNONNULL, haveInit);
		a.op(Opcode.DCONST_0);
		a.dstore(initVal);
		a.branch(Opcode.GOTO, initDone);
		a.bind(haveInit);
		a.aload(init);
		a.invokestatic(dbl);
		a.checkcast(numberClass);
		a.invokevirtual(numberDoubleValue);
		a.dstore(initVal);
		a.bind(initDone);
		if (w == BFLOAT16) {
			a.dload(initVal);
			a.invokestatic(refs.bf16Bits());
			a.istore(initBits);
		}
		// parse dims
		int listCase = a.label();
		int fill = a.label();
		a.aload(dims);
		a.instanceOf(longClass);
		a.branch(Opcode.IFEQ, listCase);
		// rank-1 shorthand: total = (int) dims; arr = new [width][off + total];
		// arr[0]=1; dim 0 = total; off = dataOffset(1)
		a.aload(dims);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(total);
		a.iconst(1);
		a.istore(rank);
		a.iconst(w.dataOffset(1));
		a.istore(off);
		a.iload(off);
		a.iload(total);
		a.op(Opcode.IADD);
		w.newBacking(a);
		a.astore(arr);
		a.aload(arr);
		a.iconst(1);
		w.storeRank(a);
		a.iconst(0);
		a.istore(k);
		a.iload(total);
		a.istore(dim);
		w.storeDim(a, arr, k, dim);
		a.branch(Opcode.GOTO, fill);
		// cons list of dims: count rank + product, then allocate and write header
		a.bind(listCase);
		a.iconst(0);
		a.istore(rank);
		a.iconst(1);
		a.istore(total);
		a.aload(dims);
		a.astore(cur);
		int countLoop = a.label();
		int countDone = a.label();
		a.bind(countLoop);
		a.aload(cur);
		a.instanceOf(objectArrayClass);
		a.branch(Opcode.IFEQ, countDone);
		a.iinc(rank, 1);
		a.iload(total);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IMUL);
		a.istore(total);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(cur);
		a.branch(Opcode.GOTO, countLoop);
		a.bind(countDone);
		a.iload(rank);
		w.emitDataOffset(a);
		a.istore(off);
		a.iload(off);
		a.iload(total);
		a.op(Opcode.IADD);
		w.newBacking(a);
		a.astore(arr);
		a.aload(arr);
		a.iload(rank);
		w.storeRank(a);
		// write the dims into the header
		a.aload(dims);
		a.astore(cur);
		a.iconst(0);
		a.istore(k);
		int dimLoop = a.label();
		int dimDone = a.label();
		a.bind(dimLoop);
		a.aload(cur);
		a.instanceOf(objectArrayClass);
		a.branch(Opcode.IFEQ, dimDone);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(dim);
		w.storeDim(a, arr, k, dim);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(cur);
		a.iinc(k, 1);
		a.branch(Opcode.GOTO, dimLoop);
		a.bind(dimDone);
		// fill data slots with initVal
		a.bind(fill);
		if (w == BFLOAT16) {
			// Arrays.fill(arr, off, off + total, (short) initBits): the pattern was
			// narrowed once above, so a checkpoint-sized allocation does not pay for
			// the conversion per element.
			a.aload(arr);
			a.iload(off);
			a.iload(off);
			a.iload(total);
			a.op(Opcode.IADD);
			a.iload(initBits);
			a.op(Opcode.I2S);
			a.invokestatic(arraysFillShort);
		}
		else {
			a.iconst(0);
			a.istore(i);
			int fillLoop = a.label();
			int fillDone = a.label();
			a.bind(fillLoop);
			a.iload(i);
			a.iload(total);
			a.branch(Opcode.IF_ICMPGE, fillDone);
			a.aload(arr);
			a.iload(off);
			a.iload(i);
			a.op(Opcode.IADD);
			a.dload(initVal);
			w.storeElem(a, refs.bf16Bits());
			a.iinc(i, 1);
			a.branch(Opcode.GOTO, fillLoop);
			a.bind(fillDone);
		}
		a.aload(arr);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(name), cp.addUtf8(MAKE_DESC), 6, 13, a.finish());
	}

	// _fvElementType(arr): packed double[] -> the symbol double-float; packed float[] ->
	// the symbol single-float; packed short[] -> the symbol bfloat16; else the symbol t
	// (general arrays are element-type t, matching the lite expandArrayElementType).
	// Locals: 0=arr.
	private static ArrayMethod buildElementType(ConstantPool cp, Refs refs) {
		JvmAsm a = new JvmAsm();
		emitWidthDispatch(a, refs, 0, (asm, w) -> {
			asm.ldcString(cp.addString(switch (w) {
				case DOUBLE -> am.ik.rontolisp.LispNames.DOUBLE_FLOAT;
				case SINGLE -> am.ik.rontolisp.LispNames.SINGLE_FLOAT;
				case BFLOAT16 -> am.ik.rontolisp.LispNames.BFLOAT16;
			}));
			asm.areturn();
		});
		a.ldcString(cp.addString("T"));
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ELEMENT_TYPE), cp.addUtf8(ELEMENT_TYPE_DESC), 1, 1, a.finish());
	}

	// _fvRequireGeneral(o): the fill-pointer-surface guard for a packed float array -- a
	// packed array has no fill pointer, adjustability or displacement, so those
	// operations reject it with a clear error (mirroring the interpreter's
	// requireGeneralArray and _ivRequireGeneral's packed-integer-vector twin); any other
	// value passes through unchanged. Locals: 0=o.
	private static ArrayMethod buildRequireGeneral(ConstantPool cp, Refs refs, ClassConstant rtExClass,
			MethodrefConstant rtExInit) {
		JvmAsm a = new JvmAsm();
		ConstantPool.StringConstant message = cp.addString("not applicable to a packed float array");
		emitWidthDispatch(a, refs, 0, (asm, w) -> emitThrow(asm, rtExClass, rtExInit, message));
		a.aload(0);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(REQUIRE_GENERAL), cp.addUtf8(REQUIRE_GENERAL_DESC), 3, 1, a.finish());
	}

	// _bf16Value(bits): BFloat16.value(int), instruction for instruction. Locals:
	// 0=bits, 1=b.
	//
	// b = bits & 0xffff;
	// if ((b & 0x7f80) == 0x7f80 && (b & 0x7f) != 0)
	// return Double.longBitsToDouble(((long) (b & 0x8000) << 48) | EXPONENT_MASK |
	// ((long) (b & 0x7f) << 45));
	// return Float.intBitsToFloat(b << 16); -- f2d is exact here: not a NaN.
	private static ArrayMethod buildBf16Value(ConstantPool cp, ClassConstant doubleClass, ClassConstant floatClass) {
		MethodrefConstant longBitsToDouble = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("longBitsToDouble"), cp.addUtf8("(J)D")));
		MethodrefConstant intBitsToFloat = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("intBitsToFloat"), cp.addUtf8("(I)F")));
		int bits = 0, b = 1;
		JvmAsm a = new JvmAsm();
		a.iload(bits);
		JvmPackedFloatWidth.emitMaskU16(a);
		a.istore(b);
		int ordinary = a.label();
		a.iload(b);
		a.iconst(0x7f80);
		a.op(Opcode.IAND);
		a.iconst(0x7f80);
		a.branch(Opcode.IF_ICMPNE, ordinary);
		a.iload(b);
		a.iconst(0x7f);
		a.op(Opcode.IAND);
		a.branch(Opcode.IFEQ, ordinary);
		// NaN: the sign and the payload's top seven bits carried across by hand
		a.iload(b);
		a.iconst(0x7fff);
		a.iconst(1);
		a.op(Opcode.IADD); // 0x8000, which iconst cannot encode as a positive sipush
		a.op(Opcode.IAND);
		a.i2l();
		a.iconst(48);
		a.op(Opcode.LSHL);
		a.ldc2Long(cp.addLong(EXPONENT_MASK));
		a.op(Opcode.LOR);
		a.iload(b);
		a.iconst(0x7f);
		a.op(Opcode.IAND);
		a.i2l();
		a.iconst(45);
		a.op(Opcode.LSHL);
		a.op(Opcode.LOR);
		a.invokestatic(longBitsToDouble);
		a.op(Opcode.DRETURN);
		a.bind(ordinary);
		a.iload(b);
		a.iconst(16);
		a.op(Opcode.ISHL);
		a.invokestatic(intBitsToFloat);
		a.f2d();
		a.op(Opcode.DRETURN);
		return new ArrayMethod(cp.addUtf8(BF16_VALUE), cp.addUtf8(BF16_VALUE_DESC), 6, 2, a.finish());
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
	private static ArrayMethod buildBf16Bits(ConstantPool cp, ClassConstant doubleClass, ClassConstant floatClass) {
		MethodrefConstant doubleToRawLongBits = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("doubleToRawLongBits"), cp.addUtf8("(D)J")));
		MethodrefConstant floatToRawIntBits = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("floatToRawIntBits"), cp.addUtf8("(F)I")));
		int value = 0, l = 2, f = 4, payload = 5;
		JvmAsm a = new JvmAsm();
		a.dload(value);
		a.invokestatic(doubleToRawLongBits);
		a.lstore(l);
		int viaFloat = a.label();
		a.lload(l);
		a.ldc2Long(cp.addLong(EXPONENT_MASK));
		a.op(Opcode.LAND);
		a.ldc2Long(cp.addLong(EXPONENT_MASK));
		a.op(Opcode.LCMP);
		a.branch(Opcode.IFNE, viaFloat);
		a.lload(l);
		a.ldc2Long(cp.addLong(MANTISSA_MASK));
		a.op(Opcode.LAND);
		a.op(Opcode.LCONST_0);
		a.op(Opcode.LCMP);
		a.branch(Opcode.IFEQ, viaFloat);
		// a double NaN
		a.lload(l);
		a.iconst(45);
		a.op(Opcode.LUSHR);
		a.l2i();
		a.iconst(0x7f);
		a.op(Opcode.IAND);
		a.istore(payload);
		a.lload(l);
		a.iconst(63);
		a.op(Opcode.LUSHR);
		a.l2i();
		a.iconst(15);
		a.op(Opcode.ISHL);
		a.iconst(0x7f80);
		a.op(Opcode.IOR);
		emitPayloadOrOne(a, payload);
		a.op(Opcode.IOR);
		a.op(Opcode.IRETURN);
		a.bind(viaFloat);
		a.dload(value);
		a.d2f();
		a.invokestatic(floatToRawIntBits);
		a.istore(f);
		int roundToNearestEven = a.label();
		a.iload(f);
		a.ldcInt(cp.addInteger(0x7f800000));
		a.op(Opcode.IAND);
		a.ldcInt(cp.addInteger(0x7f800000));
		a.branch(Opcode.IF_ICMPNE, roundToNearestEven);
		a.iload(f);
		a.ldcInt(cp.addInteger(0x007fffff));
		a.op(Opcode.IAND);
		a.branch(Opcode.IFEQ, roundToNearestEven);
		// an f32 NaN (unreachable from a double that was not one, kept for the mirror)
		a.iload(f);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		a.iconst(0x7f);
		a.op(Opcode.IAND);
		a.istore(payload);
		a.iload(f);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		a.iconst(0x7fff);
		a.iconst(1);
		a.op(Opcode.IADD); // 0x8000
		a.op(Opcode.IAND);
		a.iconst(0x7f80);
		a.op(Opcode.IOR);
		emitPayloadOrOne(a, payload);
		a.op(Opcode.IOR);
		a.op(Opcode.IRETURN);
		a.bind(roundToNearestEven);
		a.iload(f);
		a.iconst(0x7fff);
		a.op(Opcode.IADD);
		a.iload(f);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		a.iconst(1);
		a.op(Opcode.IAND);
		a.op(Opcode.IADD);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		JvmPackedFloatWidth.emitMaskU16(a);
		a.op(Opcode.IRETURN);
		return new ArrayMethod(cp.addUtf8(BF16_BITS), cp.addUtf8(BF16_BITS_DESC), 6, 6, a.finish());
	}

	// stack: (...) -> (..., int): payload | ((payload - 1) >>> 31) -- a zero payload
	// becomes one, so a NaN never comes back as an infinity. Branch-free, as the
	// authority spells it.
	private static void emitPayloadOrOne(JvmAsm a, int payload) {
		a.iload(payload);
		a.iload(payload);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.iconst(31);
		a.op(Opcode.IUSHR);
		a.op(Opcode.IOR);
	}

	// _bf16Print(bits): the Float whose Float.toString (with the FloatText E -> e
	// rewrite _lispToString applies) is FloatText.bfloat16Text of the pattern's value:
	// the widened value for NaN, an infinity and a zero, else the first of the
	// 1..9-significant-digit roundings of the value that narrows back to the same
	// pattern. Locals: 0=bits, 1=f, 2=digits, 3=candidate.
	private static ArrayMethod buildBf16Print(ConstantPool cp, ClassConstant floatClass, MethodrefConstant floatValueOf,
			MethodrefConstant bf16Bits) {
		ClassConstant bigDecimalClass = cp.addClass(cp.addUtf8("java/math/BigDecimal"));
		ClassConstant mathContextClass = cp.addClass(cp.addUtf8("java/math/MathContext"));
		MethodrefConstant intBitsToFloat = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("intBitsToFloat"), cp.addUtf8("(I)F")));
		MethodrefConstant floatIsNaN = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("isNaN"), cp.addUtf8("(F)Z")));
		MethodrefConstant floatIsInfinite = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("isInfinite"), cp.addUtf8("(F)Z")));
		MethodrefConstant bigDecimalInit = cp.addMethodref(bigDecimalClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(D)V")));
		MethodrefConstant mathContextInit = cp.addMethodref(mathContextClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(I)V")));
		MethodrefConstant bigDecimalRound = cp.addMethodref(bigDecimalClass,
				cp.addNameAndType(cp.addUtf8("round"), cp.addUtf8("(Ljava/math/MathContext;)Ljava/math/BigDecimal;")));
		MethodrefConstant bigDecimalFloatValue = cp.addMethodref(bigDecimalClass,
				cp.addNameAndType(cp.addUtf8("floatValue"), cp.addUtf8("()F")));
		int bits = 0, f = 1, digits = 2, candidate = 3;
		JvmAsm a = new JvmAsm();
		a.iload(bits);
		JvmPackedFloatWidth.emitMaskU16(a);
		a.istore(bits);
		a.iload(bits);
		a.iconst(16);
		a.op(Opcode.ISHL);
		a.invokestatic(intBitsToFloat);
		a.fstore(f);
		int asIs = a.label();
		a.fload(f);
		a.invokestatic(floatIsNaN);
		a.branch(Opcode.IFNE, asIs);
		a.fload(f);
		a.invokestatic(floatIsInfinite);
		a.branch(Opcode.IFNE, asIs);
		a.fload(f);
		a.op(Opcode.FCONST_0);
		a.op(Opcode.FCMPL);
		a.branch(Opcode.IFEQ, asIs);
		a.iconst(1);
		a.istore(digits);
		int loop = a.label();
		int next = a.label();
		a.bind(loop);
		a.iload(digits);
		a.iconst(9);
		a.branch(Opcode.IF_ICMPGT, asIs);
		// candidate = new BigDecimal((double) f).round(new
		// MathContext(digits)).floatValue()
		a.anew(bigDecimalClass);
		a.dup();
		a.fload(f);
		a.f2d();
		a.invokespecial(bigDecimalInit);
		a.anew(mathContextClass);
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
		a.branch(Opcode.IF_ICMPNE, next);
		a.fload(candidate);
		a.invokestatic(floatValueOf);
		a.areturn();
		a.bind(next);
		a.iinc(digits, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(asIs);
		a.fload(f);
		a.invokestatic(floatValueOf);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(BF16_PRINT), cp.addUtf8(BF16_PRINT_DESC), 6, 4, a.finish());
	}

	// new RuntimeException(message); athrow.
	private static void emitThrow(JvmAsm a, ClassConstant rtExClass, MethodrefConstant rtExInit,
			ConstantPool.StringConstant message) {
		a.anew(rtExClass);
		a.dup();
		a.ldcString(message);
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
	}

}
