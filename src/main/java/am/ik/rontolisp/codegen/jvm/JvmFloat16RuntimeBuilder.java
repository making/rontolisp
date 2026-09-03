package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.ArrayMethod;

/**
 * The JVM-compiled arm of {@code rontolisp:widen-float-bits} / {@code
 * rontolisp:narrow-float-bits} (.todo/671): two hand-assembled bytecode helpers,
 * {@code _widenFloatBits}/{@code _narrowFloatBits}, that loop over the same bare
 * {@code double[]}/{@code float[]} (with a {@code [rank, dims..., data...]} header,
 * {@link JvmFloatArrayRuntimeBuilder}) and {@code long[]} (with a {@code [width, e0,
 * ...]} header, {@link JvmIntArrayRuntimeBuilder}) backing every other packed-array
 * helper uses -- so a widened/narrowed tensor is a normal packed array to every OTHER
 * helper afterward, and no boxed element ever exists.
 *
 * <p>
 * {@code float16-bits}/{@code bits-float16} (the scalar pair) need no helper here --
 * {@link JvmFloat16Compiler} compiles them straight to {@code invokestatic
 * java/lang/Float.floatToFloat16}/{@code float16ToFloat} at the call site, the JDK 20+
 * intrinsics. The bf16 round-to-nearest-even narrow ({@link #emitBf16Narrow}) is the same
 * six-line trick {@code eval.FloatBitsWidening#bfloat16BitsOf} implements for the
 * interpreter (a separate copy on purpose -- {@code .todo/487}'s {@code bfloat16-bits}
 * owns the Lisp-level symbol, this is an internal duplicate so this item needs no
 * dependency on that one's landing order).
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
	 * @param objectClass the {@code java/lang/Object} class constant
	 * @param selfClass the generated program class (for the self-referencing
	 * {@code _fvLength} call {@code _narrowFloatBits} makes)
	 * @return the two helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp, ClassConstant objectClass, ClassConstant selfClass) {
		ClassConstant doubleArrayClass = cp.addClass(cp.addUtf8("[D"));
		ClassConstant floatArrayClass = cp.addClass(cp.addUtf8("[F"));
		ClassConstant longArrayClass = cp.addClass(cp.addUtf8("[J"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant floatClass = cp.addClass(cp.addUtf8("java/lang/Float"));
		ClassConstant rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));

		MethodrefConstant rtExInit = cp.addMethodref(rtExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant longIntValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant stringEqualsObj = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/String")),
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant float16ToFloat = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("float16ToFloat"), cp.addUtf8("(S)F")));
		MethodrefConstant floatToFloat16 = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("floatToFloat16"), cp.addUtf8("(F)S")));
		MethodrefConstant intBitsToFloat = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("intBitsToFloat"), cp.addUtf8("(I)F")));
		MethodrefConstant floatToRawIntBits = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("floatToRawIntBits"), cp.addUtf8("(F)I")));
		MethodrefConstant floatIsNaN = cp.addMethodref(floatClass,
				cp.addNameAndType(cp.addUtf8("isNaN"), cp.addUtf8("(F)Z")));
		MethodrefConstant fvLength = cp.addMethodref(selfClass, cp.addNameAndType(
				cp.addUtf8(JvmFloatArrayRuntimeBuilder.LENGTH), cp.addUtf8(JvmFloatArrayRuntimeBuilder.LENGTH_DESC)));

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(buildWiden(cp, doubleArrayClass, floatArrayClass, longArrayClass, rtExClass, rtExInit,
				stringEqualsObj, float16ToFloat, intBitsToFloat));
		methods.add(buildNarrow(cp, doubleArrayClass, floatArrayClass, longArrayClass, longClass, rtExClass, rtExInit,
				longIntValue, stringEqualsObj, floatToFloat16, floatToRawIntBits, floatIsNaN, fvLength));
		return methods;
	}

	// _widenFloatBits(bits, format, dst, start): bits a long[] (width header at index 0,
	// data from index 1, .kb/packed-integer-vectors.md), format ":FLOAT16"/":BFLOAT16"
	// (a plain String -- a keyword literal compiles to one, JvmQuoteCompiler), dst a
	// packed double[]/float[] (rank header at index 0, data from index 1+rank). Fills
	// dst[1+rank+start .. +bits.length-1) row-major and returns dst. Locals: 0=bits,
	// 1=format, 2=dst, 3=start, 4=bitsArr, 5=n, 6=float16, 7=dArr, 8=rank, 9=off, 10=i,
	// 11=bTmp, 12-13=v.
	private static ArrayMethod buildWiden(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant longArrayClass, ClassConstant rtExClass,
			MethodrefConstant rtExInit, MethodrefConstant stringEqualsObj, MethodrefConstant float16ToFloat,
			MethodrefConstant intBitsToFloat) {
		int bitsP = 0, formatP = 1, dstP = 2, startP = 3, bitsArr = 4, n = 5, float16 = 6, dArr = 7, rank = 8, off = 9,
				i = 10, bTmp = 11, v = 12;
		JvmAsm a = new JvmAsm();
		a.aload(bitsP);
		a.checkcast(longArrayClass);
		a.astore(bitsArr);
		a.aload(bitsArr);
		a.arraylength();
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(n);
		emitFormatFlag(a, cp, formatP, float16, stringEqualsObj);
		emitFormatCheck(a, cp, float16, rtExClass, rtExInit, stringEqualsObj, formatP, "WIDEN-FLOAT-BITS");

		int tryFloat = a.label();
		int notArray = a.label();
		a.aload(dstP);
		a.instanceOf(doubleArrayClass);
		a.branch(Opcode.IFEQ, tryFloat);
		emitWidenArm(a, false, doubleArrayClass, float16ToFloat, intBitsToFloat, dstP, bitsArr, n, float16, startP,
				dArr, rank, off, i, bTmp, v);
		a.bind(tryFloat);
		a.aload(dstP);
		a.instanceOf(floatArrayClass);
		a.branch(Opcode.IFEQ, notArray);
		emitWidenArm(a, true, floatArrayClass, float16ToFloat, intBitsToFloat, dstP, bitsArr, n, float16, startP, dArr,
				rank, off, i, bTmp, v);
		a.bind(notArray);
		emitThrow(a, cp, rtExClass, rtExInit, "WIDEN-FLOAT-BITS: dst must be a packed float array");
		return new ArrayMethod(cp.addUtf8(WIDEN), cp.addUtf8(WIDEN_DESC), 6, 14, a.finish());
	}

	private static void emitWidenArm(JvmAsm a, boolean single, ClassConstant arrayClass,
			MethodrefConstant float16ToFloat, MethodrefConstant intBitsToFloat, int dstP, int bitsArr, int n,
			int float16, int startP, int dArr, int rank, int off, int i, int bTmp, int v) {
		a.aload(dstP);
		a.checkcast(arrayClass);
		a.astore(dArr);
		a.aload(dArr);
		a.iconst(0);
		loadHeaderIntShared(a, single);
		a.istore(rank);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.iload(startP);
		a.op(Opcode.IADD);
		a.istore(off);
		a.iconst(0);
		a.istore(i);
		int loopTop = a.label();
		int loopEnd = a.label();
		a.bind(loopTop);
		a.iload(i);
		a.iload(n);
		a.branch(Opcode.IF_ICMPGE, loopEnd);
		// bTmp = (int) bitsArr[1 + i]
		a.aload(bitsArr);
		a.iconst(1);
		a.iload(i);
		a.op(Opcode.IADD);
		a.laload();
		a.l2i();
		a.istore(bTmp);
		int isBf16 = a.label();
		int decodeDone = a.label();
		a.iload(float16);
		a.branch(Opcode.IFEQ, isBf16);
		a.iload(bTmp);
		a.invokestatic(float16ToFloat);
		a.f2d();
		a.dstore(v);
		a.branch(Opcode.GOTO, decodeDone);
		a.bind(isBf16);
		a.iload(bTmp);
		a.iconst(16);
		a.op(Opcode.ISHL);
		a.invokestatic(intBitsToFloat);
		a.f2d();
		a.dstore(v);
		a.bind(decodeDone);
		a.aload(dArr);
		a.iload(off);
		a.iload(i);
		a.op(Opcode.IADD);
		a.dload(v);
		storeElemShared(a, single);
		a.iinc(i, 1);
		a.branch(Opcode.GOTO, loopTop);
		a.bind(loopEnd);
		a.aload(dstP);
		a.areturn();
	}

	// _narrowFloatBits(src, format, dst, start): the inverse. src a packed
	// double[]/float[] read row-major from element 0 (its own total size, through the
	// self-referencing _fvLength); dst a long[] bits vector written from 1 + start.
	// Locals: 0=src, 1=format, 2=dst, 3=start, 4=n, 5=float16, 6=sArr, 7=bitsArr, 8=i,
	// 9=fTmp, 10=bitsInt, 11=resultInt, 12=rank, 13=off.
	private static ArrayMethod buildNarrow(ConstantPool cp, ClassConstant doubleArrayClass,
			ClassConstant floatArrayClass, ClassConstant longArrayClass, ClassConstant longClass,
			ClassConstant rtExClass, MethodrefConstant rtExInit, MethodrefConstant longIntValue,
			MethodrefConstant stringEqualsObj, MethodrefConstant floatToFloat16, MethodrefConstant floatToRawIntBits,
			MethodrefConstant floatIsNaN, MethodrefConstant fvLength) {
		int srcP = 0, formatP = 1, dstP = 2, startP = 3, n = 4, float16 = 5, sArr = 6, bitsArr = 7, i = 8, fTmp = 9,
				bitsInt = 10, resultInt = 11, rank = 12, off = 13;
		JvmAsm a = new JvmAsm();
		a.aload(srcP);
		a.invokestatic(fvLength);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(n);
		emitFormatFlag(a, cp, formatP, float16, stringEqualsObj);
		emitFormatCheck(a, cp, float16, rtExClass, rtExInit, stringEqualsObj, formatP, "NARROW-FLOAT-BITS");
		a.aload(dstP);
		a.checkcast(longArrayClass);
		a.astore(bitsArr);

		int tryFloat = a.label();
		int notArray = a.label();
		a.aload(srcP);
		a.instanceOf(doubleArrayClass);
		a.branch(Opcode.IFEQ, tryFloat);
		emitNarrowArm(a, false, doubleArrayClass, floatToFloat16, floatToRawIntBits, floatIsNaN, srcP, bitsArr, n,
				float16, startP, sArr, i, fTmp, bitsInt, resultInt, rank, off);
		a.bind(tryFloat);
		a.aload(srcP);
		a.instanceOf(floatArrayClass);
		a.branch(Opcode.IFEQ, notArray);
		emitNarrowArm(a, true, floatArrayClass, floatToFloat16, floatToRawIntBits, floatIsNaN, srcP, bitsArr, n,
				float16, startP, sArr, i, fTmp, bitsInt, resultInt, rank, off);
		a.bind(notArray);
		emitThrow(a, cp, rtExClass, rtExInit, "NARROW-FLOAT-BITS: src must be a packed float array");
		return new ArrayMethod(cp.addUtf8(NARROW), cp.addUtf8(NARROW_DESC), 6, 14, a.finish());
	}

	private static void emitNarrowArm(JvmAsm a, boolean single, ClassConstant arrayClass,
			MethodrefConstant floatToFloat16, MethodrefConstant floatToRawIntBits, MethodrefConstant floatIsNaN,
			int srcP, int bitsArr, int n, int float16, int startP, int sArr, int i, int fTmp, int bitsInt,
			int resultInt, int rank, int off) {
		a.aload(srcP);
		a.checkcast(arrayClass);
		a.astore(sArr);
		// off = 1 + rank -- the source is read row-major from its own element 0, so
		// (unlike widen's destination) no :start offset applies here.
		a.aload(sArr);
		a.iconst(0);
		loadHeaderIntShared(a, single);
		a.istore(rank);
		a.iconst(1);
		a.iload(rank);
		a.op(Opcode.IADD);
		a.istore(off);
		a.iconst(0);
		a.istore(i);
		int loopTop = a.label();
		int loopEnd = a.label();
		a.bind(loopTop);
		a.iload(i);
		a.iload(n);
		a.branch(Opcode.IF_ICMPGE, loopEnd);
		// fTmp = (float) sArr[off + i] (widened to double by loadElem, narrowed back for
		// the single-float arm too -- a no-op there -- so both widths share one body).
		a.aload(sArr);
		a.iload(off);
		a.iload(i);
		a.op(Opcode.IADD);
		loadElemShared(a, single);
		a.d2f();
		a.fstore(fTmp);
		int isBf16 = a.label();
		int narrowDone = a.label();
		a.iload(float16);
		a.branch(Opcode.IFEQ, isBf16);
		// f16: Float.floatToFloat16(fTmp) & 0xFFFF
		a.fload(fTmp);
		a.invokestatic(floatToFloat16);
		emitMaskU16(a);
		a.istore(resultInt);
		a.branch(Opcode.GOTO, narrowDone);
		a.bind(isBf16);
		emitBf16Narrow(a, floatToRawIntBits, floatIsNaN, fTmp, bitsInt, resultInt);
		a.bind(narrowDone);
		// bitsArr[1 + start + i] = (long) resultInt
		a.aload(bitsArr);
		a.iconst(1);
		a.iload(startP);
		a.op(Opcode.IADD);
		a.iload(i);
		a.op(Opcode.IADD);
		a.iload(resultInt);
		a.op(Opcode.I2L);
		a.lastore();
		a.iinc(i, 1);
		a.branch(Opcode.GOTO, loopTop);
		a.bind(loopEnd);
		a.aload(bitsArr);
		a.areturn();
	}

	// The bf16 round-to-nearest-even narrow, over the raw bits of a float already in
	// local slot fTmp: NaN is special-cased (a plain bits + 0x7fff + lsb bias-add can
	// carry a heavy-payload NaN's low bits into the sign -- .todo/482's Enc.java note)
	// rather than relying on the payload surviving the add. The exact match of
	// eval.FloatBitsWidening#bfloat16BitsOf -- keep the two in sync.
	private static void emitBf16Narrow(JvmAsm a, MethodrefConstant floatToRawIntBits, MethodrefConstant floatIsNaN,
			int fTmp, int bitsInt, int resultInt) {
		a.fload(fTmp);
		a.invokestatic(floatToRawIntBits);
		a.istore(bitsInt);
		int nanCase = a.label();
		int done = a.label();
		a.fload(fTmp);
		a.invokestatic(floatIsNaN);
		a.branch(Opcode.IFNE, nanCase);
		// rounded = bitsInt + 0x7fff + ((bitsInt >>> 16) & 1); result = (rounded >>> 16)
		// & 0xFFFF
		a.iload(bitsInt);
		a.iconst(0x7fff);
		a.op(Opcode.IADD);
		a.iload(bitsInt);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		a.iconst(1);
		a.op(Opcode.IAND);
		a.op(Opcode.IADD);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		emitMaskU16(a);
		a.istore(resultInt);
		a.branch(Opcode.GOTO, done);
		a.bind(nanCase);
		// result = ((bitsInt >>> 16) | 0x0040) & 0xFFFF
		a.iload(bitsInt);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		a.iconst(0x40);
		a.op(Opcode.IOR);
		emitMaskU16(a);
		a.istore(resultInt);
		a.bind(done);
	}

	// AND with 0xFFFF: -1 (all bits set) shifted right unsigned by 16 is exactly
	// 0x0000FFFF -- iconst() cannot encode 0xFFFF directly (its SIPUSH fallback is a
	// SIGNED 16-bit immediate, so 65535 would silently become -1).
	private static void emitMaskU16(JvmAsm a) {
		a.iconst(-1);
		a.iconst(16);
		a.op(Opcode.IUSHR);
		a.op(Opcode.IAND);
	}

	// float16 = ":FLOAT16".equals(format)
	private static void emitFormatFlag(JvmAsm a, ConstantPool cp, int formatP, int float16Slot,
			MethodrefConstant stringEqualsObj) {
		a.ldcString(cp.addString(":FLOAT16"));
		a.aload(formatP);
		a.invokevirtual(stringEqualsObj);
		a.istore(float16Slot);
	}

	// if !float16 && !":BFLOAT16".equals(format): throw
	private static void emitFormatCheck(JvmAsm a, ConstantPool cp, int float16Slot, ClassConstant rtExClass,
			MethodrefConstant rtExInit, MethodrefConstant stringEqualsObj, int formatP, String opName) {
		int ok = a.label();
		a.iload(float16Slot);
		a.branch(Opcode.IFNE, ok);
		a.ldcString(cp.addString(":BFLOAT16"));
		a.aload(formatP);
		a.invokevirtual(stringEqualsObj);
		a.branch(Opcode.IFNE, ok);
		emitThrow(a, cp, rtExClass, rtExInit, opName + ": format must be :float16 or :bfloat16");
		a.bind(ok);
	}

	private static void emitThrow(JvmAsm a, ConstantPool cp, ClassConstant rtExClass, MethodrefConstant rtExInit,
			String message) {
		a.anew(rtExClass);
		a.dup();
		a.ldcString(cp.addString(message));
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
	}

	// stack: (..., arrayref, index) -> (..., double). Shared with
	// JvmFloatArrayRuntimeBuilder's identical private helper -- duplicated rather than
	// exposed there, since these two loops are this class's only callers.
	private static void loadElemShared(JvmAsm a, boolean single) {
		if (single) {
			a.faload();
			a.f2d();
		}
		else {
			a.daload();
		}
	}

	// stack: (..., arrayref, index, double) -> (...).
	private static void storeElemShared(JvmAsm a, boolean single) {
		if (single) {
			a.d2f();
			a.fastore();
		}
		else {
			a.dastore();
		}
	}

	// stack: (..., arrayref, index) -> (..., int).
	private static void loadHeaderIntShared(JvmAsm a, boolean single) {
		if (single) {
			a.faload();
			a.f2i();
		}
		else {
			a.daload();
			a.d2i();
		}
	}

}
