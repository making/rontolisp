package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.ArrayMethod;

/**
 * The JVM-compiled arm of {@code rontolisp:widen-float-bits} / {@code
 * rontolisp:narrow-float-bits} (.todo/671): two hand-assembled bytecode helpers,
 * {@code _widenFloatBits}/{@code _narrowFloatBits}, that loop over the same bare
 * {@code double[]}/{@code float[]}/{@code short[]} (with a
 * {@code [rank, dims..., data...]} header, {@link JvmFloatArrayRuntimeBuilder}; the
 * header's shape is per width and comes from {@link JvmPackedFloatWidth} alone, never
 * spelled here) and {@code long[]} (with a {@code [width, e0, ...]} header,
 * {@link JvmIntArrayRuntimeBuilder}) backing every other packed-array helper uses -- so a
 * widened/narrowed tensor is a normal packed array to every OTHER helper afterward, and
 * no boxed element ever exists.
 *
 * <p>
 * All three float widths are served in both directions. The {@code short[]} arms carry
 * PATTERNS rather than values: at format {@code :bfloat16} they are straight copies (the
 * bits vector already holds this width's representation), and at {@code :float16} they
 * are one conversion each -- through {@link #emitBf16Narrow} on the way in, through the
 * exact shift-widen plus {@code Float.floatToFloat16} on the way out, never through a
 * {@code double}.
 *
 * <p>
 * {@code float16-bits}/{@code bits-float16} (the scalar pair) need no helper here --
 * {@link JvmFloat16Compiler} compiles them straight to {@code invokestatic
 * java/lang/Float.floatToFloat16}/{@code float16ToFloat} at the call site, the JDK 20+
 * intrinsics. The bf16 round-to-nearest-even narrow ({@link #emitBf16Narrow}) is an
 * internal duplicate of the same trick {@code .todo/487}'s {@code bfloat16-bits} owns the
 * Lisp-level symbol for (now {@code am.ik.rontolisp.BFloat16#bits}), so this item needs
 * no dependency on that one's landing order. Every decoded value stays a raw
 * {@code float} end to end when the destination/source is single-float -- NEVER routed
 * through a {@code double} local, even transiently: measured (both directions,
 * exhaustively over all 2^32 float32 patterns), an f32-&gt;f64 widen (f2d) quiets a
 * signalling NaN exactly as often as a widen-then-narrow roundtrip does (126 of 65536),
 * so there is no safe direction through {@code double} to fall back on -- only avoiding
 * it entirely closes the gap.
 */
final class JvmFloat16RuntimeBuilder {

	private static final String OBJ = "Ljava/lang/Object;";

	static final String WIDEN = "_widenFloatBits";

	static final String WIDEN_DESC = "(" + OBJ + OBJ + OBJ + "I)" + OBJ;

	static final String NARROW = "_narrowFloatBits";

	static final String NARROW_DESC = "(" + OBJ + OBJ + OBJ + "I)" + OBJ;

	private JvmFloat16RuntimeBuilder() {
	}

	/**
	 * Builds {@code _widenFloatBits}/{@code _narrowFloatBits}.
	 * @param cp the constant pool
	 * @return the two helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp) {
		ClassEntry doubleArrayClass = cp.classEntry("[D");
		ClassEntry floatArrayClass = cp.classEntry("[F");
		ClassEntry shortArrayClass = cp.classEntry("[S");
		ClassEntry longArrayClass = cp.classEntry("[J");
		ClassEntry floatClass = cp.classEntry("java/lang/Float");
		ClassEntry rtExClass = cp.classEntry("java/lang/RuntimeException");

		MethodRefEntry rtExInit = cp.methodRef(rtExClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry stringEqualsObj = cp.methodRef(cp.classEntry("java/lang/String"), "equals",
				"(Ljava/lang/Object;)Z");
		MethodRefEntry float16ToFloat = cp.methodRef(floatClass, "float16ToFloat", "(S)F");
		MethodRefEntry floatToFloat16 = cp.methodRef(floatClass, "floatToFloat16", "(F)S");
		MethodRefEntry intBitsToFloat = cp.methodRef(floatClass, "intBitsToFloat", "(I)F");
		MethodRefEntry floatToRawIntBits = cp.methodRef(floatClass, "floatToRawIntBits", "(F)I");
		MethodRefEntry floatIsNaN = cp.methodRef(floatClass, "isNaN", "(F)Z");

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(buildWiden(cp, doubleArrayClass, floatArrayClass, shortArrayClass, longArrayClass, rtExClass,
				rtExInit, stringEqualsObj, float16ToFloat, intBitsToFloat, floatToRawIntBits, floatIsNaN));
		methods.add(buildNarrow(cp, doubleArrayClass, floatArrayClass, shortArrayClass, longArrayClass, rtExClass,
				rtExInit, stringEqualsObj, floatToFloat16, intBitsToFloat, floatToRawIntBits, floatIsNaN));
		return methods;
	}

	// _widenFloatBits(bits, format, dst, start): bits a long[] (width header at index 0,
	// data from index 1, .kb/packed-integer-vectors.md), format ":FLOAT16"/":BFLOAT16"
	// (a plain String -- a keyword literal compiles to one, JvmQuoteCompiler), dst a
	// packed double[]/float[]/short[] (rank header at index 0, data from the width's own
	// JvmPackedFloatWidth.dataOffset -- a bfloat16 header spends TWO slots per
	// dimension).
	// Fills dst[off+start .. +bits.length-1) row-major and returns dst. Locals: 0=bits,
	// 1=format, 2=dst, 3=start, 4=bitsArr, 5=n, 6=float16, 7=dArr, 8=rank, 9=off, 10=i,
	// 11=bTmp, 12=vf (the decoded float -- NEVER widened to double: see emitWidenArm),
	// 13=bitsInt, 14=resultInt (the bfloat16 destination's narrow only).
	private static ArrayMethod buildWiden(ConstantPool cp, ClassEntry doubleArrayClass, ClassEntry floatArrayClass,
			ClassEntry shortArrayClass, ClassEntry longArrayClass, ClassEntry rtExClass, MethodRefEntry rtExInit,
			MethodRefEntry stringEqualsObj, MethodRefEntry float16ToFloat, MethodRefEntry intBitsToFloat,
			MethodRefEntry floatToRawIntBits, MethodRefEntry floatIsNaN) {
		int bitsP = 0, formatP = 1, dstP = 2, startP = 3, bitsArr = 4, n = 5, float16 = 6, dArr = 7, rank = 8, off = 9,
				i = 10, bTmp = 11, vf = 12, bitsInt = 13, resultInt = 14;
		MethodCode a = new MethodCode();
		a.aload(bitsP);
		a.checkcast(longArrayClass);
		a.astore(bitsArr);
		a.aload(bitsArr);
		a.arraylength();
		a.loadConstant(1);
		a.isub();
		a.istore(n);
		emitFormatFlag(a, cp, formatP, float16, stringEqualsObj);
		emitFormatCheck(a, cp, float16, rtExClass, rtExInit, stringEqualsObj, formatP, "WIDEN-FLOAT-BITS");

		MethodCode.Label tryFloat = a.newLabel();
		MethodCode.Label tryShort = a.newLabel();
		MethodCode.Label notArray = a.newLabel();
		a.aload(dstP);
		a.instanceOf(doubleArrayClass);
		a.ifeq(tryFloat);
		emitWidenArm(a, JvmPackedFloatWidth.DOUBLE, doubleArrayClass, float16ToFloat, intBitsToFloat, floatToRawIntBits,
				floatIsNaN, dstP, bitsArr, n, float16, startP, dArr, rank, off, i, bTmp, vf, bitsInt, resultInt);
		a.labelBinding(tryFloat);
		a.aload(dstP);
		a.instanceOf(floatArrayClass);
		a.ifeq(tryShort);
		emitWidenArm(a, JvmPackedFloatWidth.SINGLE, floatArrayClass, float16ToFloat, intBitsToFloat, floatToRawIntBits,
				floatIsNaN, dstP, bitsArr, n, float16, startP, dArr, rank, off, i, bTmp, vf, bitsInt, resultInt);
		a.labelBinding(tryShort);
		a.aload(dstP);
		a.instanceOf(shortArrayClass);
		a.ifeq(notArray);
		emitWidenArm(a, JvmPackedFloatWidth.BFLOAT16, shortArrayClass, float16ToFloat, intBitsToFloat,
				floatToRawIntBits, floatIsNaN, dstP, bitsArr, n, float16, startP, dArr, rank, off, i, bTmp, vf, bitsInt,
				resultInt);
		a.labelBinding(notArray);
		emitThrow(a, cp, rtExClass, rtExInit, "WIDEN-FLOAT-BITS: dst must be a packed float array");
		return new ArrayMethod(cp.utf8Entry(WIDEN), cp.utf8Entry(WIDEN_DESC), a);
	}

	private static void emitWidenArm(MethodCode a, JvmPackedFloatWidth w, ClassEntry arrayClass,
			MethodRefEntry float16ToFloat, MethodRefEntry intBitsToFloat, MethodRefEntry floatToRawIntBits,
			MethodRefEntry floatIsNaN, int dstP, int bitsArr, int n, int float16, int startP, int dArr, int rank,
			int off, int i, int bTmp, int vf, int bitsInt, int resultInt) {
		a.aload(dstP);
		a.checkcast(arrayClass);
		a.astore(dArr);
		a.aload(dArr);
		w.loadRank(a);
		a.istore(rank);
		a.iload(rank);
		w.emitDataOffset(a);
		a.iload(startP);
		a.iadd();
		a.istore(off);
		a.loadConstant(0);
		a.istore(i);
		MethodCode.Label loopTop = a.newLabel();
		MethodCode.Label loopEnd = a.newLabel();
		a.labelBinding(loopTop);
		a.iload(i);
		a.iload(n);
		a.if_icmpge(loopEnd);
		// bTmp = (int) bitsArr[1 + i]
		a.aload(bitsArr);
		a.loadConstant(1);
		a.iload(i);
		a.iadd();
		a.laload();
		a.l2i();
		a.istore(bTmp);
		// Decode straight into a FLOAT local, never a double: float16ToFloat/
		// intBitsToFloat already answer a float exactly, and a float destination must
		// store that value AS-IS. Widening it to double here only to narrow back with
		// d2f (the old shape) is a real bug, not a redundant no-op -- d2f alone quiets a
		// signalling NaN 126/65536 times (measured against java.lang.Float.floatToFloat16
		// / bit-shift oracles, both directions), so the roundtrip silently drops every
		// NaN the source encoded as signalling into the corresponding quiet one.
		MethodCode.Label isBf16 = a.newLabel();
		MethodCode.Label decodeDone = a.newLabel();
		if (w == JvmPackedFloatWidth.BFLOAT16) {
			// The bfloat16 destination stores PATTERNS, not values, so this arm ends in
			// an int and never touches the two above's f32 store.
			//
			// :bfloat16 -> #bf16 is a straight COPY: the source patterns are already
			// what the destination holds, so there is nothing to convert and nothing a
			// NaN can lose. :float16 -> #bf16 is ONE conversion -- the f16 pattern's
			// float is exact, and the narrow is emitBf16Narrow, the same rounding this
			// file's narrow arm emits and the interpreter reaches through
			// am.ik.rontolisp.BFloat16#bits(float). Not a fourth copy of it.
			a.iload(float16);
			a.ifeq(isBf16);
			a.iload(bTmp);
			a.invokestatic(float16ToFloat);
			a.fstore(vf);
			emitBf16Narrow(a, floatToRawIntBits, floatIsNaN, vf, bitsInt, resultInt);
			a.goto_(decodeDone);
			a.labelBinding(isBf16);
			a.iload(bTmp);
			emitMaskU16(a);
			a.istore(resultInt);
			a.labelBinding(decodeDone);
			a.aload(dArr);
			a.iload(off);
			a.iload(i);
			a.iadd();
			a.iload(resultInt);
			a.sastore();
		}
		else {
			a.iload(float16);
			a.ifeq(isBf16);
			a.iload(bTmp);
			a.invokestatic(float16ToFloat);
			a.fstore(vf);
			a.goto_(decodeDone);
			a.labelBinding(isBf16);
			a.iload(bTmp);
			a.loadConstant(16);
			a.ishl();
			a.invokestatic(intBitsToFloat);
			a.fstore(vf);
			a.labelBinding(decodeDone);
			a.aload(dArr);
			a.iload(off);
			a.iload(i);
			a.iadd();
			a.fload(vf);
			if (w == JvmPackedFloatWidth.SINGLE) {
				a.fastore();
			}
			else {
				// float -> double is a WIDENING conversion: always exact, signal bit
				// included (unlike the double -> float narrow above, this is safe).
				a.f2d();
				a.dastore();
			}
		}
		a.iinc(i, 1);
		a.goto_(loopTop);
		a.labelBinding(loopEnd);
		a.aload(dstP);
		a.areturn();
	}

	// _narrowFloatBits(src, format, dst, start): the inverse. src a packed
	// double[]/float[]/short[] read row-major from element 0 for its TOTAL SIZE -- the
	// product of the header's dimensions, computed per arm (emitNarrowArm) because the
	// header shape is the width's. NOT the self-referencing _fvLength, which this used to
	// call: at rank 1 that answers the same number, but at rank n it goes through
	// _fvToGeneral and _length, and _length refuses a multidimensional array -- so a
	// rank-2 source threw here while the interpreter (LispFloatArray.totalSize) narrowed
	// it, at EVERY width. dst is a long[] bits vector written from 1 + start.
	// Locals: 0=src, 1=format, 2=dst, 3=start, 4=n, 5=float16, 6=sArr, 7=bitsArr, 8=i,
	// 9=fTmp, 10=bitsInt, 11=resultInt, 12=rank, 13=off, 14=k (the dimension product).
	private static ArrayMethod buildNarrow(ConstantPool cp, ClassEntry doubleArrayClass, ClassEntry floatArrayClass,
			ClassEntry shortArrayClass, ClassEntry longArrayClass, ClassEntry rtExClass, MethodRefEntry rtExInit,
			MethodRefEntry stringEqualsObj, MethodRefEntry floatToFloat16, MethodRefEntry intBitsToFloat,
			MethodRefEntry floatToRawIntBits, MethodRefEntry floatIsNaN) {
		int srcP = 0, formatP = 1, dstP = 2, startP = 3, n = 4, float16 = 5, sArr = 6, bitsArr = 7, i = 8, fTmp = 9,
				bitsInt = 10, resultInt = 11, rank = 12, off = 13, k = 14;
		MethodCode a = new MethodCode();
		emitFormatFlag(a, cp, formatP, float16, stringEqualsObj);
		emitFormatCheck(a, cp, float16, rtExClass, rtExInit, stringEqualsObj, formatP, "NARROW-FLOAT-BITS");
		a.aload(dstP);
		a.checkcast(longArrayClass);
		a.astore(bitsArr);

		MethodCode.Label tryFloat = a.newLabel();
		MethodCode.Label tryShort = a.newLabel();
		MethodCode.Label notArray = a.newLabel();
		a.aload(srcP);
		a.instanceOf(doubleArrayClass);
		a.ifeq(tryFloat);
		emitNarrowArm(a, JvmPackedFloatWidth.DOUBLE, doubleArrayClass, floatToFloat16, intBitsToFloat,
				floatToRawIntBits, floatIsNaN, srcP, bitsArr, n, float16, startP, sArr, i, fTmp, bitsInt, resultInt,
				rank, off, k);
		a.labelBinding(tryFloat);
		a.aload(srcP);
		a.instanceOf(floatArrayClass);
		a.ifeq(tryShort);
		emitNarrowArm(a, JvmPackedFloatWidth.SINGLE, floatArrayClass, floatToFloat16, intBitsToFloat, floatToRawIntBits,
				floatIsNaN, srcP, bitsArr, n, float16, startP, sArr, i, fTmp, bitsInt, resultInt, rank, off, k);
		a.labelBinding(tryShort);
		a.aload(srcP);
		a.instanceOf(shortArrayClass);
		a.ifeq(notArray);
		emitNarrowArm(a, JvmPackedFloatWidth.BFLOAT16, shortArrayClass, floatToFloat16, intBitsToFloat,
				floatToRawIntBits, floatIsNaN, srcP, bitsArr, n, float16, startP, sArr, i, fTmp, bitsInt, resultInt,
				rank, off, k);
		a.labelBinding(notArray);
		emitThrow(a, cp, rtExClass, rtExInit, "NARROW-FLOAT-BITS: src must be a packed float array");
		return new ArrayMethod(cp.utf8Entry(NARROW), cp.utf8Entry(NARROW_DESC), a);
	}

	private static void emitNarrowArm(MethodCode a, JvmPackedFloatWidth w, ClassEntry arrayClass,
			MethodRefEntry floatToFloat16, MethodRefEntry intBitsToFloat, MethodRefEntry floatToRawIntBits,
			MethodRefEntry floatIsNaN, int srcP, int bitsArr, int n, int float16, int startP, int sArr, int i, int fTmp,
			int bitsInt, int resultInt, int rank, int off, int k) {
		a.aload(srcP);
		a.checkcast(arrayClass);
		a.astore(sArr);
		// off = the width's own data offset -- the source is read row-major from its own
		// element 0, so (unlike widen's destination) no :start offset applies here.
		a.aload(sArr);
		w.loadRank(a);
		a.istore(rank);
		a.iload(rank);
		w.emitDataOffset(a);
		a.istore(off);
		// n = the product of the header's dimensions, which is what the interpreter's
		// LispFloatArray.totalSize answers at every rank. Read from the HEADER and not
		// from the Java length, for the same reason _fvLength's rank-1 arm does: under
		// --gpu a result stub is the header alone (.kb/gpu.md).
		a.loadConstant(1);
		a.istore(n);
		a.loadConstant(0);
		a.istore(k);
		MethodCode.Label dimTop = a.newLabel();
		MethodCode.Label dimEnd = a.newLabel();
		a.labelBinding(dimTop);
		a.iload(k);
		a.iload(rank);
		a.if_icmpge(dimEnd);
		a.iload(n);
		a.aload(sArr);
		a.iload(k);
		w.loadDim(a);
		a.imul();
		a.istore(n);
		a.iinc(k, 1);
		a.goto_(dimTop);
		a.labelBinding(dimEnd);
		a.loadConstant(0);
		a.istore(i);
		MethodCode.Label loopTop = a.newLabel();
		MethodCode.Label loopEnd = a.newLabel();
		a.labelBinding(loopTop);
		a.iload(i);
		a.iload(n);
		a.if_icmpge(loopEnd);
		// fTmp = sArr[off + i], as a float. NEVER via loadElemShared's widen-to-double
		// (that helper exists for callers that want a double either way): a float
		// source read faload straight into fTmp with no conversion at all, a double
		// source narrows ONCE with d2f -- either is safe, but a widen-then-narrow
		// roundtrip (f2d then d2f, what loadElemShared followed by a bare d2f would be
		// for the single-float arm) quiets a signalling NaN 126/65536 times (measured).
		MethodCode.Label isBf16 = a.newLabel();
		MethodCode.Label narrowDone = a.newLabel();
		if (w == JvmPackedFloatWidth.BFLOAT16) {
			// A bfloat16 SOURCE hands out patterns, not values: to :bfloat16 it is a
			// straight copy of the stored pattern (no conversion, so no NaN can be lost),
			// and to :float16 it is the exact shift-widen -- NEVER _bf16Value, which
			// answers a double and would quiet a signalling NaN on the way back down --
			// followed by the same Float.floatToFloat16 the two arms above run.
			a.aload(sArr);
			a.iload(off);
			a.iload(i);
			a.iadd();
			a.saload();
			emitMaskU16(a);
			a.istore(bitsInt);
			a.iload(float16);
			a.ifeq(isBf16);
			a.iload(bitsInt);
			a.loadConstant(16);
			a.ishl();
			a.invokestatic(intBitsToFloat);
			a.invokestatic(floatToFloat16);
			emitMaskU16(a);
			a.istore(resultInt);
			a.goto_(narrowDone);
			a.labelBinding(isBf16);
			a.iload(bitsInt);
			a.istore(resultInt);
			a.labelBinding(narrowDone);
		}
		else {
			a.aload(sArr);
			a.iload(off);
			a.iload(i);
			a.iadd();
			if (w == JvmPackedFloatWidth.SINGLE) {
				a.faload();
			}
			else {
				a.daload();
				a.d2f();
			}
			a.fstore(fTmp);
			a.iload(float16);
			a.ifeq(isBf16);
			// f16: Float.floatToFloat16(fTmp) & 0xFFFF
			a.fload(fTmp);
			a.invokestatic(floatToFloat16);
			emitMaskU16(a);
			a.istore(resultInt);
			a.goto_(narrowDone);
			a.labelBinding(isBf16);
			emitBf16Narrow(a, floatToRawIntBits, floatIsNaN, fTmp, bitsInt, resultInt);
			a.labelBinding(narrowDone);
		}
		// bitsArr[1 + start + i] = (long) resultInt
		a.aload(bitsArr);
		a.loadConstant(1);
		a.iload(startP);
		a.iadd();
		a.iload(i);
		a.iadd();
		a.iload(resultInt);
		a.i2l();
		a.lastore();
		a.iinc(i, 1);
		a.goto_(loopTop);
		a.labelBinding(loopEnd);
		a.aload(bitsArr);
		a.areturn();
	}

	// The bf16 round-to-nearest-even narrow, over the raw bits of a float already in
	// local slot fTmp: NaN is special-cased (a plain bits + 0x7fff + lsb bias-add can
	// carry a heavy-payload NaN's low bits into the sign -- .todo/482's Enc.java note)
	// rather than relying on the payload surviving the add. Emitted instruction for
	// instruction from am.ik.rontolisp.BFloat16#bits(float), which eval.FloatBitsWidening
	// calls -- the interpreter and this backend answer one rounding (.kb/bfloat16.md).
	private static void emitBf16Narrow(MethodCode a, MethodRefEntry floatToRawIntBits, MethodRefEntry floatIsNaN,
			int fTmp, int bitsInt, int resultInt) {
		a.fload(fTmp);
		a.invokestatic(floatToRawIntBits);
		a.istore(bitsInt);
		MethodCode.Label nanCase = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.fload(fTmp);
		a.invokestatic(floatIsNaN);
		a.ifne(nanCase);
		// rounded = bitsInt + 0x7fff + ((bitsInt >>> 16) & 1); result = (rounded >>> 16)
		// & 0xFFFF
		a.iload(bitsInt);
		a.loadConstant(0x7fff);
		a.iadd();
		a.iload(bitsInt);
		a.loadConstant(16);
		a.iushr();
		a.loadConstant(1);
		a.iand();
		a.iadd();
		a.loadConstant(16);
		a.iushr();
		emitMaskU16(a);
		a.istore(resultInt);
		a.goto_(done);
		a.labelBinding(nanCase);
		// result = u | (((u & 0x7f) - 1) >>> 31), where u = (bitsInt >>> 16) & 0xFFFF.
		// The top sixteen bits of an f32 NaN ALREADY are the sign, an all-ones exponent
		// and the payload's top seven bits, so the pattern is u unchanged -- carried
		// across rather than force-quieted, so a signalling NaN survives the round trip
		// here exactly as it does on the interpreter. The one correction is a payload
		// whose top seven bits are all zero, which u alone would spell as an infinity.
		a.iload(bitsInt);
		a.loadConstant(16);
		a.iushr();
		emitMaskU16(a);
		a.dup();
		a.loadConstant(0x7f);
		a.iand();
		a.loadConstant(1);
		a.isub();
		a.loadConstant(31);
		a.iushr();
		a.ior();
		a.istore(resultInt);
		a.labelBinding(done);
	}

	// AND with 0xFFFF: -1 (all bits set) shifted right unsigned by 16 is exactly
	// 0x0000FFFF -- iconst() cannot encode 0xFFFF directly (its SIPUSH fallback is a
	// SIGNED 16-bit immediate, so 65535 would silently become -1).
	private static void emitMaskU16(MethodCode a) {
		a.loadConstant(-1);
		a.loadConstant(16);
		a.iushr();
		a.iand();
	}

	// float16 = ":FLOAT16".equals(format)
	private static void emitFormatFlag(MethodCode a, ConstantPool cp, int formatP, int float16Slot,
			MethodRefEntry stringEqualsObj) {
		a.ldc(cp.stringEntry(":FLOAT16"));
		a.aload(formatP);
		a.invokevirtual(stringEqualsObj);
		a.istore(float16Slot);
	}

	// if !float16 && !":BFLOAT16".equals(format): throw
	private static void emitFormatCheck(MethodCode a, ConstantPool cp, int float16Slot, ClassEntry rtExClass,
			MethodRefEntry rtExInit, MethodRefEntry stringEqualsObj, int formatP, String opName) {
		MethodCode.Label ok = a.newLabel();
		a.iload(float16Slot);
		a.ifne(ok);
		a.ldc(cp.stringEntry(":BFLOAT16"));
		a.aload(formatP);
		a.invokevirtual(stringEqualsObj);
		a.ifne(ok);
		emitThrow(a, cp, rtExClass, rtExInit, opName + ": format must be :float16 or :bfloat16");
		a.labelBinding(ok);
	}

	private static void emitThrow(MethodCode a, ConstantPool cp, ClassEntry rtExClass, MethodRefEntry rtExInit,
			String message) {
		a.new_(rtExClass);
		a.dup();
		a.ldc(cp.stringEntry(message));
		a.invokespecial(rtExInit);
		a.athrow();
	}

}
