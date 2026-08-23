package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.ArrayMethod;

/**
 * Builds the JVM bytecode for the packed float-array runtime helpers ({@code _fv*}). A
 * packed float array is represented at runtime as a bare {@code double[]} (double-float)
 * or {@code float[]} (single-float) carrying an embedded dimension header: {@code [rank,
 * dim_0, ..., dim_{rank-1}, e_0, ..., e_{total-1}]} (rank and dims stored in the array's
 * own element type). The data offset is {@code 1 + rank}. A {@code double[]} / {@code
 * float[]} is disjoint from the {@code Object[]} shape of a cons / function ref / ratio
 * and from the {@code ArrayList} shape of a general array, and disjoint from each other,
 * so no value-discriminator changes are needed anywhere else in the backend.
 *
 * <p>
 * These helpers are emitted only when the program can produce a packed float array (a
 * {@code #d(...)}/{@code #f(...)} literal or {@code make-array :element-type
 * 'double-float|'single-float}; see {@code JvmLispCompiler.Ctx#usesFloatArray}). Each
 * accessor dispatches on {@code instanceof double[]} then {@code instanceof float[]}: a
 * packed array is handled natively (header-aware; single-float reads widen f32-&gt;f64
 * and writes narrow f64-&gt;f32), any other array shape delegates to the matching general
 * {@code _array*} helper, so a value whose static type is "an array" works whichever
 * representation it holds at runtime. Allocation is width-specific ({@code _fvMake}
 * builds a {@code double[]}, {@code _sfvMake} a {@code float[]}) because the element type
 * is a compile-time literal at the {@code make-array} call site.
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

	static final String MAKE_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	static final String ELEMENT_TYPE = "_fvElementType";

	static final String ELEMENT_TYPE_DESC = "(" + OBJ + ")" + OBJ;

	private JvmFloatArrayRuntimeBuilder() {
	}

	// --- width-specific element/header emit helpers ---
	// The packed double[] and float[] representations share the same header/index layout;
	// only the array class and the load/store/convert opcodes differ. Each accessor emits
	// its body twice -- once for double[] (single=false), once for float[] (single=true)
	// --
	// via these helpers, so the double path stays byte-identical and the float path adds
	// the f32<->f64 widen/narrow.

	// stack: (..., arrayref, index) -> (..., double). Load a data element widened to
	// double.
	private static void loadElem(JvmAsm a, boolean single) {
		if (single) {
			a.faload();
			a.f2d();
		}
		else {
			a.daload();
		}
	}

	// stack: (..., arrayref, index) -> (..., int). Load a header slot (rank/dim) as int.
	private static void loadHeaderInt(JvmAsm a, boolean single) {
		if (single) {
			a.faload();
			a.f2i();
		}
		else {
			a.daload();
			a.d2i();
		}
	}

	// stack: (..., arrayref, index, double) -> (...). Narrow (single) then store a data
	// element.
	private static void storeElem(JvmAsm a, boolean single) {
		if (single) {
			a.d2f();
			a.fastore();
		}
		else {
			a.dastore();
		}
	}

	// stack: (..., arrayref, index, int) -> (...). Convert an int header value to the
	// array's element type then store it.
	private static void storeHeaderInt(JvmAsm a, boolean single) {
		if (single) {
			a.i2f();
			a.fastore();
		}
		else {
			a.i2d();
			a.dastore();
		}
	}

	// stack: (..., int length) -> (..., arrayref). Allocate a fresh backing array.
	private static void newBacking(JvmAsm a, boolean single) {
		if (single) {
			a.newarrayFloat();
		}
		else {
			a.newarrayDouble();
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
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
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

		MethodrefConstant floatValueOf = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/Float")),
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(F)Ljava/lang/Float;")));

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(buildToGeneral(cp, TO_GENERAL, doubleArrayClass, floatArrayClass, arrayListClass, objectClass,
				alInit, alAdd, longValueOf, doubleValueOf, null, materialize));
		// The print-only variant: a single-float element is boxed as a transient Float
		// so _lispToString renders it at its f32 width (#f(0.1) round-trips); every
		// semantic conversion keeps going through _fvToGeneral's widened Doubles.
		methods.add(buildToGeneral(cp, TO_GENERAL_PRINT, doubleArrayClass, floatArrayClass, arrayListClass, objectClass,
				alInit, alAdd, longValueOf, doubleValueOf, floatValueOf, materialize));
		methods.add(buildAref1(cp, doubleArrayClass, floatArrayClass, longClass, longIntValue, doubleValueOf, aref1,
				materialize));
		methods.add(buildAref2(cp, doubleArrayClass, floatArrayClass, longClass, longIntValue, doubleValueOf, aref2,
				materialize));
		methods.add(buildArefN(cp, doubleArrayClass, floatArrayClass, objectArrayClass, longClass, longIntValue,
				doubleValueOf, arefN, materialize));
		methods.add(buildAset1(cp, doubleArrayClass, floatArrayClass, longClass, numberClass, longIntValue,
				numberDoubleValue, doubleValueOf, dbl, aset1, written));
		methods.add(buildAset2(cp, doubleArrayClass, floatArrayClass, longClass, numberClass, longIntValue,
				numberDoubleValue, doubleValueOf, dbl, aset2, written));
		methods.add(buildAsetN(cp, doubleArrayClass, floatArrayClass, objectArrayClass, longClass, numberClass,
				longIntValue, numberDoubleValue, doubleValueOf, dbl, asetN, written));
		methods.add(buildDims(cp, doubleArrayClass, floatArrayClass, objectClass, longValueOf, arrayDims));
		methods.add(buildLength(cp, doubleArrayClass, floatArrayClass, longValueOf, toGeneral, lengthHelper));
		methods.add(buildMake(cp, false, MAKE, objectArrayClass, longClass, numberClass, longIntValue,
				numberDoubleValue, dbl));
		methods.add(buildMake(cp, true, SINGLE_MAKE, objectArrayClass, longClass, numberClass, longIntValue,
				numberDoubleValue, dbl));
		methods.add(buildElementType(cp, doubleArrayClass, floatArrayClass));
		return methods;
	}

	private static MethodrefConstant self(ConstantPool cp, ClassConstant selfClass, String name, String desc) {
		return cp.addMethodref(selfClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	// _fvToGeneral(o): convert a packed double[]/float[] into an equivalent general array
	// (an
	// ArrayList whose slot 0 is the {dims, null, null} header and slots 1.. are boxed
	// Doubles), so the existing _arrayToString / equality / coercion helpers render and
	// compare it exactly like a general double array. Only ever called with a packed
	// array
	// (the print/length dispatch tests instanceof first), so it dispatches double[] then
	// float[] with no general fallback. Locals: 0=o, 1=d (array), 2=rank, 3=off, 4=total,
	// 5=dimsArr, 6=list, 7=k, 8=f.
	private static ArrayMethod buildToGeneral(ConstantPool cp, String name, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant arrayListClass, ClassConstant objectClass,
			MethodrefConstant alInit, MethodrefConstant alAdd, MethodrefConstant longValueOf,
			MethodrefConstant doubleValueOf, @org.jspecify.annotations.Nullable MethodrefConstant floatValueOf,
			@Nullable MethodrefConstant materialize) {
		JvmAsm a = new JvmAsm();
		// --gpu: every element is about to be read; a result the device still holds comes
		// home first. Once, for the whole array, ahead of the loop.
		emitMaterialize(a, 0, materialize);
		int tryFloat = a.label();
		a.aload(0);
		a.instanceOf(doubleArrayClass);
		a.branch(Opcode.IFEQ, tryFloat);
		emitToGeneralBody(a, false, doubleArrayClass, arrayListClass, objectClass, alInit, alAdd, longValueOf,
				doubleValueOf, null);
		a.bind(tryFloat);
		emitToGeneralBody(a, true, floatArrayClass, arrayListClass, objectClass, alInit, alAdd, longValueOf,
				doubleValueOf, floatValueOf);
		return new ArrayMethod(cp.addUtf8(name), cp.addUtf8(TO_GENERAL_DESC), 6, 9, a.finish());
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

	// floatValueOf non-null selects the print-only boxing: a single-float element is
	// boxed as a Float (no widening) so the renderer can spell it at its f32 width.
	private static void emitToGeneralBody(JvmAsm a, boolean single, ClassConstant arrayClass,
			ClassConstant arrayListClass, ClassConstant objectClass, MethodrefConstant alInit, MethodrefConstant alAdd,
			MethodrefConstant longValueOf, MethodrefConstant doubleValueOf,
			@org.jspecify.annotations.Nullable MethodrefConstant floatValueOf) {
		int o = 0, d = 1, rank = 2, off = 3, total = 4, dimsArr = 5, list = 6, k = 7, f = 8;
		a.aload(o);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
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
		a.iconst(1);
		a.iload(k);
		a.op(Opcode.IADD);
		loadHeaderInt(a, single);
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
		if (single && floatValueOf != null) {
			a.faload();
			a.invokestatic(floatValueOf);
		}
		else {
			loadElem(a, single);
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

	// _fvAref1(arr, i): packed -> Double.valueOf(d[1 + rank + (int) i]); else _aref1.
	// Serves rank-1 aref and row-major-aref (rank read from the header). Locals:
	// 0=arr, 1=i, 2=d, 3=rank.
	private static ArrayMethod buildAref1(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant longClass, MethodrefConstant longIntValue,
			MethodrefConstant doubleValueOf, MethodrefConstant aref1, @Nullable MethodrefConstant materialize) {
		int arr = 0, i = 1, d = 2, rank = 3;
		JvmAsm a = new JvmAsm();
		// --gpu: the element read below must see the device's bytes if it holds them.
		emitMaterialize(a, arr, materialize);
		int notDouble = a.label();
		int notPacked = a.label();
		emitAref1Body(a, false, doubleArrayClass, notDouble, arr, i, d, rank, longClass, longIntValue, doubleValueOf);
		a.bind(notDouble);
		emitAref1Body(a, true, floatArrayClass, notPacked, arr, i, d, rank, longClass, longIntValue, doubleValueOf);
		a.bind(notPacked);
		a.aload(arr);
		a.aload(i);
		a.invokestatic(aref1);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREF1), cp.addUtf8(JvmArrayRuntimeBuilder.AREF1_DESC), 5, 4, a.finish());
	}

	private static void emitAref1Body(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr, int i,
			int d, int rank, ClassConstant longClass, MethodrefConstant longIntValue, MethodrefConstant doubleValueOf) {
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.aload(d);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.aload(i);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IADD);
		loadElem(a, single);
		a.invokestatic(doubleValueOf);
		a.areturn();
	}

	// _fvAref2(arr, i, j): packed -> Double.valueOf(d[1 + rank + i * cols + j]) with
	// cols = (int) d[2]; else _aref2. Locals: 0=arr, 1=i, 2=j, 3=d, 4=rank, 5=cols.
	private static ArrayMethod buildAref2(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant longClass, MethodrefConstant longIntValue,
			MethodrefConstant doubleValueOf, MethodrefConstant aref2, @Nullable MethodrefConstant materialize) {
		int arr = 0, i = 1, j = 2, d = 3, rank = 4, cols = 5;
		JvmAsm a = new JvmAsm();
		emitMaterialize(a, arr, materialize);
		int notDouble = a.label();
		int notPacked = a.label();
		emitAref2Body(a, false, doubleArrayClass, notDouble, arr, i, j, d, rank, cols, longClass, longIntValue,
				doubleValueOf);
		a.bind(notDouble);
		emitAref2Body(a, true, floatArrayClass, notPacked, arr, i, j, d, rank, cols, longClass, longIntValue,
				doubleValueOf);
		a.bind(notPacked);
		a.aload(arr);
		a.aload(i);
		a.aload(j);
		a.invokestatic(aref2);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREF2), cp.addUtf8(JvmArrayRuntimeBuilder.AREF2_DESC), 6, 6, a.finish());
	}

	private static void emitAref2Body(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr, int i,
			int j, int d, int rank, int cols, ClassConstant longClass, MethodrefConstant longIntValue,
			MethodrefConstant doubleValueOf) {
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.aload(d);
		a.iconst(2);
		loadHeaderInt(a, single);
		a.istore(cols);
		a.aload(d);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.aload(i);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.iload(cols);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.aload(j);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IADD);
		loadElem(a, single);
		a.invokestatic(doubleValueOf);
		a.areturn();
	}

	// _fvArefN(arr, subs): packed -> Horner flat index over the header dims; else _arefN.
	// Locals: 0=arr, 1=subs, 2=d, 3=subsArr, 4=rank, 5=flat, 6=k.
	private static ArrayMethod buildArefN(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant objectArrayClass, ClassConstant longClass,
			MethodrefConstant longIntValue, MethodrefConstant doubleValueOf, MethodrefConstant arefN,
			@Nullable MethodrefConstant materialize) {
		int arr = 0, subs = 1, d = 2, subsArr = 3, rank = 4, flat = 5, k = 6;
		JvmAsm a = new JvmAsm();
		emitMaterialize(a, arr, materialize);
		int notDouble = a.label();
		int notPacked = a.label();
		emitArefNBody(a, false, doubleArrayClass, notDouble, arr, subs, d, subsArr, rank, flat, k, objectArrayClass,
				longClass, longIntValue, doubleValueOf);
		a.bind(notDouble);
		emitArefNBody(a, true, floatArrayClass, notPacked, arr, subs, d, subsArr, rank, flat, k, objectArrayClass,
				longClass, longIntValue, doubleValueOf);
		a.bind(notPacked);
		a.aload(arr);
		a.aload(subs);
		a.invokestatic(arefN);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREFN), cp.addUtf8(JvmArrayRuntimeBuilder.AREFN_DESC), 5, 7, a.finish());
	}

	private static void emitArefNBody(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr,
			int subs, int d, int subsArr, int rank, int flat, int k, ClassConstant objectArrayClass,
			ClassConstant longClass, MethodrefConstant longIntValue, MethodrefConstant doubleValueOf) {
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(subs);
		a.checkcast(objectArrayClass);
		a.astore(subsArr);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.aload(subsArr);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(flat);
		a.iconst(1);
		a.istore(k);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(k);
		a.iload(rank);
		a.branch(Opcode.IF_ICMPGE, done);
		a.iload(flat);
		a.aload(d);
		a.iconst(1);
		a.iload(k);
		a.op(Opcode.IADD);
		loadHeaderInt(a, single);
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
		a.aload(d);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.iload(flat);
		a.op(Opcode.IADD);
		loadElem(a, single);
		a.invokestatic(doubleValueOf);
		a.areturn();
	}

	// _fvAset1(arr, i, val): packed -> d[1 + rank + (int) i] = coerce(val), return the
	// stored Double (matching the interpreter, which returns the coerced -- and, for
	// single-float, narrowed -- value); else _aset1. Locals: 0=arr, 1=i, 2=val, 3=d,
	// 4=rank, 5=idx, 6..7=dval.
	private static ArrayMethod buildAset1(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant longClass, ClassConstant numberClass,
			MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue, MethodrefConstant doubleValueOf,
			MethodrefConstant dbl, MethodrefConstant aset1, @Nullable MethodrefConstant written) {
		int arr = 0, i = 1, val = 2, d = 3, rank = 4, idx = 5, dval = 6;
		JvmAsm a = new JvmAsm();
		int notDouble = a.label();
		int notPacked = a.label();
		emitAset1Body(a, false, doubleArrayClass, notDouble, arr, i, val, d, rank, idx, dval, longClass, numberClass,
				longIntValue, numberDoubleValue, doubleValueOf, dbl, written);
		a.bind(notDouble);
		emitAset1Body(a, true, floatArrayClass, notPacked, arr, i, val, d, rank, idx, dval, longClass, numberClass,
				longIntValue, numberDoubleValue, doubleValueOf, dbl, written);
		a.bind(notPacked);
		a.aload(arr);
		a.aload(i);
		a.aload(val);
		a.invokestatic(aset1);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASET1), cp.addUtf8(JvmArrayRuntimeBuilder.ASET1_DESC), 4, 8, a.finish());
	}

	private static void emitAset1Body(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr, int i,
			int val, int d, int rank, int idx, int dval, ClassConstant longClass, ClassConstant numberClass,
			MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue, MethodrefConstant doubleValueOf,
			MethodrefConstant dbl, @Nullable MethodrefConstant written) {
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.aload(i);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IADD);
		a.istore(idx);
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
			a.checkcast(arrayClass);
			a.astore(d);
		}
		a.aload(d);
		a.iload(idx);
		a.dload(dval);
		storeElem(a, single);
		emitStoredValue(a, single, dval, doubleValueOf);
	}

	// _fvAset2(arr, i, j, val): packed store at i*cols+j; else _aset2.
	// Locals: 0=arr, 1=i, 2=j, 3=val, 4=d, 5=rank, 6=cols, 7=idx, 8..9=dval.
	private static ArrayMethod buildAset2(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant longClass, ClassConstant numberClass,
			MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue, MethodrefConstant doubleValueOf,
			MethodrefConstant dbl, MethodrefConstant aset2, @Nullable MethodrefConstant written) {
		int arr = 0, i = 1, j = 2, val = 3, d = 4, rank = 5, cols = 6, idx = 7, dval = 8;
		JvmAsm a = new JvmAsm();
		int notDouble = a.label();
		int notPacked = a.label();
		emitAset2Body(a, false, doubleArrayClass, notDouble, arr, i, j, val, d, rank, cols, idx, dval, longClass,
				numberClass, longIntValue, numberDoubleValue, doubleValueOf, dbl, written);
		a.bind(notDouble);
		emitAset2Body(a, true, floatArrayClass, notPacked, arr, i, j, val, d, rank, cols, idx, dval, longClass,
				numberClass, longIntValue, numberDoubleValue, doubleValueOf, dbl, written);
		a.bind(notPacked);
		a.aload(arr);
		a.aload(i);
		a.aload(j);
		a.aload(val);
		a.invokestatic(aset2);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASET2), cp.addUtf8(JvmArrayRuntimeBuilder.ASET2_DESC), 5, 10, a.finish());
	}

	private static void emitAset2Body(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr, int i,
			int j, int val, int d, int rank, int cols, int idx, int dval, ClassConstant longClass,
			ClassConstant numberClass, MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue,
			MethodrefConstant doubleValueOf, MethodrefConstant dbl, @Nullable MethodrefConstant written) {
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.aload(d);
		a.iconst(2);
		loadHeaderInt(a, single);
		a.istore(cols);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.aload(i);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.iload(cols);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.aload(j);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IADD);
		a.istore(idx);
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
			a.checkcast(arrayClass);
			a.astore(d);
		}
		a.aload(d);
		a.iload(idx);
		a.dload(dval);
		storeElem(a, single);
		emitStoredValue(a, single, dval, doubleValueOf);
	}

	// _fvAsetN(arr, subs, val): packed Horner store; else _asetN.
	// Locals: 0=arr, 1=subs, 2=val, 3=d, 4=subsArr, 5=rank, 6=flat, 7=k, 8=idx,
	// 9..10=dval.
	private static ArrayMethod buildAsetN(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant objectArrayClass, ClassConstant longClass,
			ClassConstant numberClass, MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue,
			MethodrefConstant doubleValueOf, MethodrefConstant dbl, MethodrefConstant asetN,
			@Nullable MethodrefConstant written) {
		int arr = 0, subs = 1, val = 2, d = 3, subsArr = 4, rank = 5, flat = 6, k = 7, idx = 8, dval = 9;
		JvmAsm a = new JvmAsm();
		int notDouble = a.label();
		int notPacked = a.label();
		emitAsetNBody(a, false, doubleArrayClass, notDouble, arr, subs, val, d, subsArr, rank, flat, k, idx, dval,
				objectArrayClass, longClass, numberClass, longIntValue, numberDoubleValue, doubleValueOf, dbl, written);
		a.bind(notDouble);
		emitAsetNBody(a, true, floatArrayClass, notPacked, arr, subs, val, d, subsArr, rank, flat, k, idx, dval,
				objectArrayClass, longClass, numberClass, longIntValue, numberDoubleValue, doubleValueOf, dbl, written);
		a.bind(notPacked);
		a.aload(arr);
		a.aload(subs);
		a.aload(val);
		a.invokestatic(asetN);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASETN), cp.addUtf8(JvmArrayRuntimeBuilder.ASETN_DESC), 5, 11, a.finish());
	}

	private static void emitAsetNBody(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr,
			int subs, int val, int d, int subsArr, int rank, int flat, int k, int idx, int dval,
			ClassConstant objectArrayClass, ClassConstant longClass, ClassConstant numberClass,
			MethodrefConstant longIntValue, MethodrefConstant numberDoubleValue, MethodrefConstant doubleValueOf,
			MethodrefConstant dbl, @Nullable MethodrefConstant written) {
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(subs);
		a.checkcast(objectArrayClass);
		a.astore(subsArr);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.aload(subsArr);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(flat);
		a.iconst(1);
		a.istore(k);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(k);
		a.iload(rank);
		a.branch(Opcode.IF_ICMPGE, done);
		a.iload(flat);
		a.aload(d);
		a.iconst(1);
		a.iload(k);
		a.op(Opcode.IADD);
		loadHeaderInt(a, single);
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
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.iload(flat);
		a.op(Opcode.IADD);
		a.istore(idx);
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
			a.checkcast(arrayClass);
			a.astore(d);
		}
		a.aload(d);
		a.iload(idx);
		a.dload(dval);
		storeElem(a, single);
		emitStoredValue(a, single, dval, doubleValueOf);
	}

	// Common tail of the aset helpers: return the coerced value as a Double, read back
	// through the array's element type so single-float narrowing (f64 -> f32 -> f64) is
	// reflected -- exactly what the interpreter returns. Stack in: (empty, dval in
	// local).
	private static void emitStoredValue(JvmAsm a, boolean single, int dval, MethodrefConstant doubleValueOf) {
		a.dload(dval);
		if (single) {
			a.d2f();
			a.f2d();
		}
		a.invokestatic(doubleValueOf);
		a.areturn();
	}

	// _fvDims(arr): packed -> a fresh cons list of the header dims as Longs; else
	// _arrayDims. Locals: 0=arr, 1=d, 2=rank, 3=result, 4=j.
	private static ArrayMethod buildDims(ConstantPool cp, ClassConstant doubleArrayClass, ClassConstant floatArrayClass,
			ClassConstant objectClass, MethodrefConstant longValueOf, MethodrefConstant arrayDims) {
		int arr = 0, d = 1, rank = 2, result = 3, j = 4;
		JvmAsm a = new JvmAsm();
		int notDouble = a.label();
		int notPacked = a.label();
		emitDimsBody(a, false, doubleArrayClass, notDouble, arr, d, rank, result, j, objectClass, longValueOf);
		a.bind(notDouble);
		emitDimsBody(a, true, floatArrayClass, notPacked, arr, d, rank, result, j, objectClass, longValueOf);
		a.bind(notPacked);
		a.aload(arr);
		a.invokestatic(arrayDims);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(DIMS), cp.addUtf8(JvmArrayRuntimeBuilder.DIMS_DESC), 6, 5, a.finish());
	}

	private static void emitDimsBody(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr, int d,
			int rank, int result, int j, ClassConstant objectClass, MethodrefConstant longValueOf) {
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.aconstNull();
		a.astore(result);
		a.iload(rank);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(j);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(j);
		a.branch(Opcode.IFLT, done);
		a.iconst(2);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.aload(d);
		a.iconst(1);
		a.iload(j);
		a.op(Opcode.IADD);
		loadHeaderInt(a, single);
		a.op(Opcode.I2L);
		a.invokestatic(longValueOf);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(result);
		a.aastore();
		a.astore(result);
		a.iinc(j, -1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.aload(result);
		a.areturn();
	}

	// _fvLength(arr): packed rank-1 -> Long.valueOf(count); packed rank-n -> delegate via
	// _length(_fvToGeneral(arr)) for exact parity with the general array; else _length.
	// Locals: 0=arr, 1=d, 2=rank.
	private static ArrayMethod buildLength(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, MethodrefConstant longValueOf, MethodrefConstant toGeneral,
			MethodrefConstant lengthHelper) {
		int arr = 0, d = 1, rank = 2;
		JvmAsm a = new JvmAsm();
		int notDouble = a.label();
		int notPacked = a.label();
		emitLengthBody(a, false, doubleArrayClass, notDouble, arr, d, rank, longValueOf, toGeneral, lengthHelper);
		a.bind(notDouble);
		emitLengthBody(a, true, floatArrayClass, notPacked, arr, d, rank, longValueOf, toGeneral, lengthHelper);
		a.bind(notPacked);
		a.aload(arr);
		a.invokestatic(lengthHelper);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(LENGTH), cp.addUtf8(LENGTH_DESC), 3, 3, a.finish());
	}

	private static void emitLengthBody(JvmAsm a, boolean single, ClassConstant arrayClass, int elseLabel, int arr,
			int d, int rank, MethodrefConstant longValueOf, MethodrefConstant toGeneral,
			MethodrefConstant lengthHelper) {
		int rankN = a.label();
		a.aload(arr);
		a.instanceOf(arrayClass);
		a.branch(Opcode.IFEQ, elseLabel);
		a.aload(arr);
		a.checkcast(arrayClass);
		a.astore(d);
		a.aload(d);
		a.iconst(0);
		loadHeaderInt(a, single);
		a.istore(rank);
		a.iload(rank);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPNE, rankN);
		// rank 1: count = d[1], the header's one dimension -- read from the header and
		// not from the Java length, because under --gpu a result stub is the header alone
		// (.kb/gpu.md, "A lazy result allocates no host array").
		a.aload(d);
		a.iconst(1);
		loadHeaderInt(a, single);
		a.op(Opcode.I2L);
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(rankN);
		a.aload(arr);
		a.invokestatic(toGeneral);
		a.invokestatic(lengthHelper);
		a.areturn();
	}

	// _fvMake / _sfvMake(dims, init): build a packed double[]/float[] with a dimension
	// header, filled with coerce(init) (default 0.0, narrowed to f32 for single). dims is
	// a
	// Long (rank-1 shorthand) or a cons list of Longs. Always produces a packed array of
	// the
	// chosen width (the compiler routes here only for :element-type 'double-float /
	// 'single-float without fill-pointer/adjustable/displacement). Locals: 0=dims,
	// 1=init,
	// 2..3=initVal, 4=rank, 5=total, 6=arr, 7=cur, 8=k, 9=off, 10=i.
	private static ArrayMethod buildMake(ConstantPool cp, boolean single, String name, ClassConstant objectArrayClass,
			ClassConstant longClass, ClassConstant numberClass, MethodrefConstant longIntValue,
			MethodrefConstant numberDoubleValue, MethodrefConstant dbl) {
		int dims = 0, init = 1, initVal = 2, rank = 4, total = 5, arr = 6, cur = 7, k = 8, off = 9, i = 10;
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
		// parse dims
		int listCase = a.label();
		int fill = a.label();
		a.aload(dims);
		a.instanceOf(longClass);
		a.branch(Opcode.IFEQ, listCase);
		// rank-1 shorthand: total = (int) dims; arr = new [width][2 + total];
		// arr[0]=1; arr[1]=total; off=2
		a.aload(dims);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(total);
		a.iconst(2);
		a.iload(total);
		a.op(Opcode.IADD);
		newBacking(a, single);
		a.astore(arr);
		a.aload(arr);
		a.iconst(0);
		a.iconst(1);
		storeHeaderInt(a, single);
		a.aload(arr);
		a.iconst(1);
		a.iload(total);
		storeHeaderInt(a, single);
		a.iconst(2);
		a.istore(off);
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
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.istore(off);
		a.iload(off);
		a.iload(total);
		a.op(Opcode.IADD);
		newBacking(a, single);
		a.astore(arr);
		a.aload(arr);
		a.iconst(0);
		a.iload(rank);
		storeHeaderInt(a, single);
		// write dims into arr[1..rank]
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
		a.aload(arr);
		a.iconst(1);
		a.iload(k);
		a.op(Opcode.IADD);
		a.aload(cur);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		storeHeaderInt(a, single);
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
		storeElem(a, single);
		a.iinc(i, 1);
		a.branch(Opcode.GOTO, fillLoop);
		a.bind(fillDone);
		a.aload(arr);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(name), cp.addUtf8(MAKE_DESC), 6, 12, a.finish());
	}

	// _fvElementType(arr): packed double[] -> the symbol double-float; packed float[] ->
	// the
	// symbol single-float; else the symbol t (general arrays are element-type t, matching
	// the lite expandArrayElementType). Locals: 0=arr.
	private static ArrayMethod buildElementType(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass) {
		JvmAsm a = new JvmAsm();
		int notDouble = a.label();
		int notPacked = a.label();
		a.aload(0);
		a.instanceOf(doubleArrayClass);
		a.branch(Opcode.IFEQ, notDouble);
		a.ldcString(cp.addString(am.ik.rontolisp.LispNames.DOUBLE_FLOAT));
		a.areturn();
		a.bind(notDouble);
		a.aload(0);
		a.instanceOf(floatArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.ldcString(cp.addString(am.ik.rontolisp.LispNames.SINGLE_FLOAT));
		a.areturn();
		a.bind(notPacked);
		a.ldcString(cp.addString("T"));
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ELEMENT_TYPE), cp.addUtf8(ELEMENT_TYPE_DESC), 1, 1, a.finish());
	}

}
