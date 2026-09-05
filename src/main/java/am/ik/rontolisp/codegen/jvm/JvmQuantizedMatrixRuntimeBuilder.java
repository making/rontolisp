package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.ArrayMethod;

/**
 * The JVM-compiled arm of the {@code rontolisp:quantized-matrix} type
 * ({@code .kb/quantized-matrix.md}): the {@code _qm*} helpers a program that can build
 * one carries. A compiled quantized matrix is a bare {@code byte[]} -- disjoint from
 * every shape the {@code instanceof} dispatch already tells apart, and one byte an
 * element, which is the whole reason the type exists ({@code .todo/672}: the packed
 * integer vector's {@code long[]} would store one byte in eight). Its layout is
 *
 * <pre>
 * [0..3]  format code, little-endian int (1 = Q8_0)
 * [4..7]  rank (1 or 2)
 * [8..]   one little-endian int per dimension
 * then    the ggml blocks verbatim: per 32 elements one binary16 scale and 32 int8 quants
 * </pre>
 *
 * so the blocks start at {@code 8 + 4 * rank}, and a {@code read-sequence} into the array
 * is one transfer of a GGUF tensor's bytes ({@link JvmIoRuntimeBuilder}). This class and
 * {@link JvmSimdVectorTemplate}'s {@code qmOff} / {@code qmDim} are the two places that
 * spell the header; the interpreter's {@code am.ik.rontolisp.LispQuantizedMatrix} keeps
 * the dimensions beside a header-free block array.
 *
 * <p>
 * {@code _qmQuantizeBlocks} is ggml's {@code quantize_row_q8_0_ref} instruction for
 * instruction ({@code eval.QuantizedMatrices#quantizeRowQ8_0} is the interpreter's copy):
 * f32 absmax, {@code d = amax / 127}, {@code id = 1 / d}, binary16 {@code d}, and each
 * quant {@code roundf(x * id)} -- half away from zero, {@code Math.round} on the
 * magnitude with the sign restored. Emitted only for a program that names
 * {@code rontolisp:quantize} or {@code rontolisp:make-quantized-matrix}
 * ({@code JvmLispCompiler.Ctx#usesQuantized}); every other program is byte-identical to
 * one that never knew the type.
 */
final class JvmQuantizedMatrixRuntimeBuilder {

	private static final String OBJ = "Ljava/lang/Object;";

	/** The header's format code for {@code Q8_0}. */
	static final int FORMAT_Q8_0 = 1;

	/** ggml's Q8_0 block: 32 elements in 34 bytes. */
	static final int BLOCK = 32;

	static final int BLOCK_BYTES = 34;

	static final String INT = "_qmInt";

	static final String INT_DESC = "([BI)I";

	static final String PUT_INT = "_qmPutInt";

	static final String PUT_INT_DESC = "([BII)V";

	static final String TOTAL = "_qmTotal";

	static final String TOTAL_DESC = "([B)I";

	/** {@code _qmValue(byte[] m, int flat) -> double}: the dequantized element. */
	static final String VALUE = "_qmValue";

	static final String VALUE_DESC = "([BI)D";

	static final String AREF1 = "_qmAref1";

	static final String AREF2 = "_qmAref2";

	static final String AREFN = "_qmArefN";

	static final String DIMS = "_qmDims";

	static final String LENGTH = "_qmLength";

	static final String TO_STRING = "_qmToString";

	static final String TO_STRING_DESC = "(" + OBJ + ")Ljava/lang/String;";

	static final String PREDICATE = "_qmP";

	static final String QUANT = "_qmQuant";

	static final String SCALE = "_qmScale";

	static final String MAKE = "_qmMake";

	static final String QUANTIZE = "_qmQuantize";

	static final String DEQUANTIZE = "_qmDequantize";

	static final String UNARY_DESC = "(" + OBJ + ")" + OBJ;

	static final String BINARY_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	static final String TERNARY_DESC = "(" + OBJ + OBJ + OBJ + ")" + OBJ;

	private static final String ALLOC = "_qmAlloc";

	private static final String ALLOC_DESC = "(Ljava/lang/String;III)[B";

	private static final String LOCAL_NAME = "_qmLocalName";

	private static final String LOCAL_NAME_DESC = "(" + OBJ + ")Ljava/lang/String;";

	private static final String CHECK_FORMAT = "_qmCheckFormat";

	private static final String CHECK_FORMAT_DESC = "(Ljava/lang/String;" + OBJ + ")V";

	private static final String QUANTIZE_BLOCKS = "_qmQuantizeBlocks";

	private static final String QUANTIZE_BLOCKS_DESC = "([F[BI)V";

	private JvmQuantizedMatrixRuntimeBuilder() {
	}

	/** The constant-pool references the bodies share. */
	private record Refs(ConstantPool cp, ClassConstant byteArrayClass, ClassConstant longClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, ClassConstant rtExClass,
			MethodrefConstant rtExInit, MethodrefConstant longIntValue, MethodrefConstant longValueOf,
			MethodrefConstant doubleValueOf, MethodrefConstant float16ToFloat, MethodrefConstant floatToFloat16,
			MethodrefConstant mathAbsF, MethodrefConstant mathRoundF, MethodrefConstant qmInt,
			MethodrefConstant qmPutInt, MethodrefConstant qmTotal, MethodrefConstant qmValue, MethodrefConstant qmAlloc,
			MethodrefConstant qmLocalName, MethodrefConstant qmCheckFormat, MethodrefConstant qmQuantizeBlocks,
			ClassConstant sbClass, MethodrefConstant sbInit, MethodrefConstant sbAppendStr,
			MethodrefConstant sbAppendInt, MethodrefConstant sbToString, MethodrefConstant stringLastIndexOf,
			MethodrefConstant stringSubstring, MethodrefConstant stringEquals, MethodrefConstant bf16Value,
			MethodrefConstant bf16Bits) {

	}

	/**
	 * Builds the helpers.
	 * @param cp the constant pool
	 * @param selfClass the generated program class
	 * @return the helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp, ClassConstant selfClass) {
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		ClassConstant floatClass = cp.addClass(cp.addUtf8("java/lang/Float"));
		ClassConstant mathClass = cp.addClass(cp.addUtf8("java/lang/Math"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		ClassConstant sbClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		ClassConstant rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		Refs r = new Refs(cp, cp.addClass(cp.addUtf8("[B")), longClass, cp.addClass(cp.addUtf8("[Ljava/lang/Object;")),
				stringClass, rtExClass,
				cp.addMethodref(rtExClass,
						cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V"))),
				cp.addMethodref(longClass, cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I"))),
				cp.addMethodref(longClass, cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;"))),
				cp.addMethodref(doubleClass,
						cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;"))),
				cp.addMethodref(floatClass, cp.addNameAndType(cp.addUtf8("float16ToFloat"), cp.addUtf8("(S)F"))),
				cp.addMethodref(floatClass, cp.addNameAndType(cp.addUtf8("floatToFloat16"), cp.addUtf8("(F)S"))),
				cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(F)F"))),
				cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8("round"), cp.addUtf8("(F)I"))),
				self(cp, selfClass, INT, INT_DESC), self(cp, selfClass, PUT_INT, PUT_INT_DESC),
				self(cp, selfClass, TOTAL, TOTAL_DESC), self(cp, selfClass, VALUE, VALUE_DESC),
				self(cp, selfClass, ALLOC, ALLOC_DESC), self(cp, selfClass, LOCAL_NAME, LOCAL_NAME_DESC),
				self(cp, selfClass, CHECK_FORMAT, CHECK_FORMAT_DESC),
				self(cp, selfClass, QUANTIZE_BLOCKS, QUANTIZE_BLOCKS_DESC), sbClass,
				cp.addMethodref(sbClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V"))),
				cp.addMethodref(sbClass,
						cp.addNameAndType(cp.addUtf8("append"),
								cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;"))),
				cp.addMethodref(sbClass,
						cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;"))),
				cp.addMethodref(sbClass, cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;"))),
				cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("lastIndexOf"), cp.addUtf8("(I)I"))),
				cp.addMethodref(stringClass,
						cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(I)Ljava/lang/String;"))),
				cp.addMethodref(stringClass,
						cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z"))),
				self(cp, selfClass, JvmFloatArrayRuntimeBuilder.BF16_VALUE,
						JvmFloatArrayRuntimeBuilder.BF16_VALUE_DESC),
				self(cp, selfClass, JvmFloatArrayRuntimeBuilder.BF16_BITS, JvmFloatArrayRuntimeBuilder.BF16_BITS_DESC));
		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(buildInt(r));
		methods.add(buildPutInt(r));
		methods.add(buildTotal(r));
		methods.add(buildValue(r));
		methods.add(buildAref1(r));
		methods.add(buildAref2(r));
		methods.add(buildArefN(r));
		methods.add(buildDims(r));
		methods.add(buildLength(r));
		methods.add(buildToString(r));
		methods.add(buildPredicate(r));
		methods.add(buildQuant(r));
		methods.add(buildScale(r));
		methods.add(buildAlloc(r));
		methods.add(buildLocalName(r));
		methods.add(buildCheckFormat(r));
		methods.add(buildQuantizeBlocks(r));
		methods.add(buildMake(r));
		methods.add(buildQuantize(r));
		methods.add(buildDequantize(r));
		return methods;
	}

	private static MethodrefConstant self(ConstantPool cp, ClassConstant selfClass, String name, String desc) {
		return cp.addMethodref(selfClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	private static ArrayMethod method(Refs r, String name, String desc, int maxStack, int maxLocals, JvmAsm a) {
		return new ArrayMethod(r.cp().addUtf8(name), r.cp().addUtf8(desc), maxStack, maxLocals, a.finish());
	}

	private static void throwMessage(JvmAsm a, Refs r, String message) {
		a.anew(r.rtExClass());
		a.dup();
		a.ldcString(r.cp().addString(message));
		a.invokespecial(r.rtExInit());
		a.op(Opcode.ATHROW);
	}

	/** Stack: {@code (..., String prefix, int n) -> (..., String)}: prefix + n. */
	private static void appendIntToString(JvmAsm a, Refs r, int prefixSlot, int nSlot, String suffix) {
		a.anew(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(prefixSlot);
		a.invokevirtual(r.sbAppendStr());
		a.iload(nSlot);
		a.invokevirtual(r.sbAppendInt());
		if (!suffix.isEmpty()) {
			a.ldcString(r.cp().addString(suffix));
			a.invokevirtual(r.sbAppendStr());
		}
		a.invokevirtual(r.sbToString());
	}

	/** Throws a RuntimeException whose message is the String on the stack. */
	private static void throwStackMessage(JvmAsm a, Refs r, int msgSlot) {
		a.astore(msgSlot);
		a.anew(r.rtExClass());
		a.dup();
		a.aload(msgSlot);
		a.invokespecial(r.rtExInit());
		a.op(Opcode.ATHROW);
	}

	/**
	 * Stack: {@code (...) -> (..., int)}: the header int at {@code off} of local
	 * {@code arr}.
	 */
	private static void headerInt(JvmAsm a, Refs r, int arrSlot, int off) {
		a.aload(arrSlot);
		a.iconst(off);
		a.invokestatic(r.qmInt());
	}

	// _qmInt(a, off): the little-endian int at off.
	private static ArrayMethod buildInt(Refs r) {
		JvmAsm a = new JvmAsm();
		for (int k = 0; k < 4; k++) {
			a.aload(0);
			a.iload(1);
			if (k > 0) {
				a.iconst(k);
				a.op(Opcode.IADD);
			}
			a.baload();
			a.iconst(255);
			a.op(Opcode.IAND);
			if (k > 0) {
				a.iconst(8 * k);
				a.op(Opcode.ISHL);
				a.op(Opcode.IOR);
			}
		}
		a.ireturn();
		return method(r, INT, INT_DESC, 6, 2, a);
	}

	// _qmPutInt(a, off, v): writes v little-endian at off.
	private static ArrayMethod buildPutInt(Refs r) {
		JvmAsm a = new JvmAsm();
		for (int k = 0; k < 4; k++) {
			a.aload(0);
			a.iload(1);
			if (k > 0) {
				a.iconst(k);
				a.op(Opcode.IADD);
			}
			a.iload(2);
			if (k > 0) {
				a.iconst(8 * k);
				a.op(Opcode.IUSHR);
			}
			a.op(Opcode.I2B);
			a.bastore();
		}
		a.op(Opcode.RETURN);
		return method(r, PUT_INT, PUT_INT_DESC, 5, 3, a);
	}

	// _qmTotal(a): dim0 * (rank == 2 ? dim1 : 1).
	private static ArrayMethod buildTotal(Refs r) {
		JvmAsm a = new JvmAsm();
		int rank1 = a.label();
		headerInt(a, r, 0, 8);
		headerInt(a, r, 0, 4);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPNE, rank1);
		headerInt(a, r, 0, 12);
		a.op(Opcode.IMUL);
		a.bind(rank1);
		a.ireturn();
		return method(r, TOTAL, TOTAL_DESC, 5, 1, a);
	}

	// _qmValue(a, flat): q * scale as a double. Locals: 0=a, 1=flat, 2=bo.
	private static ArrayMethod buildValue(Refs r) {
		JvmAsm a = new JvmAsm();
		// bo = 8 + 4 * rank + (flat / 32) * 34
		headerInt(a, r, 0, 4);
		a.iconst(4);
		a.op(Opcode.IMUL);
		a.iconst(8);
		a.op(Opcode.IADD);
		a.iload(1);
		a.iconst(BLOCK);
		a.op(Opcode.IDIV);
		a.iconst(BLOCK_BYTES);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.istore(2);
		// (double) a[bo + 2 + flat % 32]
		a.aload(0);
		a.iload(2);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.iload(1);
		a.iconst(BLOCK);
		a.op(Opcode.IREM);
		a.op(Opcode.IADD);
		a.baload();
		a.i2d();
		// (double) float16ToFloat((short) ((a[bo] & 0xff) | (a[bo + 1] << 8)))
		emitScale(a, r, 0, 2);
		a.f2d();
		a.dmul();
		a.dreturn();
		return method(r, VALUE, VALUE_DESC, 8, 3, a);
	}

	/**
	 * Stack: {@code (...) -> (..., float)}: the scale of the block at local
	 * {@code boSlot}.
	 */
	private static void emitScale(JvmAsm a, Refs r, int arrSlot, int boSlot) {
		a.aload(arrSlot);
		a.iload(boSlot);
		a.baload();
		a.iconst(255);
		a.op(Opcode.IAND);
		a.aload(arrSlot);
		a.iload(boSlot);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.baload();
		a.iconst(8);
		a.op(Opcode.ISHL);
		a.op(Opcode.IOR);
		a.op(Opcode.I2S);
		a.invokestatic(r.float16ToFloat());
	}

	// _qmAref1(arr, i): the element at flat index i (rank-1 aref and row-major-aref).
	// Locals: 0=arr, 1=i, 2=a, 3=flat.
	private static ArrayMethod buildAref1(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(2);
		a.aload(1);
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(3);
		int bad = a.label();
		int ok = a.label();
		a.iload(3);
		a.branch(Opcode.IFLT, bad);
		a.iload(3);
		a.aload(2);
		a.invokestatic(r.qmTotal());
		a.branch(Opcode.IF_ICMPLT, ok);
		a.bind(bad);
		throwMessage(a, r, "aref: index out of bounds");
		a.bind(ok);
		a.aload(2);
		a.iload(3);
		a.invokestatic(r.qmValue());
		a.invokestatic(r.doubleValueOf());
		a.areturn();
		return method(r, AREF1, BINARY_DESC, 6, 4, a);
	}

	// _qmAref2(arr, i, j). Locals: 0=arr, 1=i, 2=j, 3=a, 4=row, 5=col, 6=cols, 7=msg.
	private static ArrayMethod buildAref2(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(3);
		int rank2 = a.label();
		headerInt(a, r, 3, 4);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPEQ, rank2);
		throwMessage(a, r, "aref: expected 1 subscripts, got 2");
		a.bind(rank2);
		a.aload(1);
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(4);
		a.aload(2);
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(5);
		headerInt(a, r, 3, 12);
		a.istore(6);
		int bad = a.label();
		int ok = a.label();
		a.iload(4);
		a.branch(Opcode.IFLT, bad);
		a.iload(5);
		a.branch(Opcode.IFLT, bad);
		a.iload(4);
		headerInt(a, r, 3, 8);
		a.branch(Opcode.IF_ICMPGE, bad);
		a.iload(5);
		a.iload(6);
		a.branch(Opcode.IF_ICMPLT, ok);
		a.bind(bad);
		throwMessage(a, r, "aref: index out of bounds");
		a.bind(ok);
		a.aload(3);
		a.iload(4);
		a.iload(6);
		a.op(Opcode.IMUL);
		a.iload(5);
		a.op(Opcode.IADD);
		a.invokestatic(r.qmValue());
		a.invokestatic(r.doubleValueOf());
		a.areturn();
		return method(r, AREF2, TERNARY_DESC, 6, 8, a);
	}

	// _qmArefN(arr, subs): the Horner fold over the header dims. Locals: 0=arr, 1=subs,
	// 2=a, 3=subsArr, 4=rank, 5=flat, 6=k, 7=d, 8=s, 9=msg.
	private static ArrayMethod buildArefN(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(2);
		a.aload(1);
		a.checkcast(r.objectArrayClass());
		a.astore(3);
		headerInt(a, r, 2, 4);
		a.istore(4);
		int rankOk = a.label();
		a.aload(3);
		a.arraylength();
		a.iload(4);
		a.branch(Opcode.IF_ICMPEQ, rankOk);
		a.ldcString(r.cp().addString("aref: expected "));
		a.astore(9);
		a.aload(3);
		a.arraylength();
		a.istore(8);
		appendIntToString(a, r, 9, 4, " subscripts, got ");
		a.astore(9);
		appendIntToString(a, r, 9, 8, "");
		throwStackMessage(a, r, 9);
		a.bind(rankOk);
		a.iconst(0);
		a.istore(5);
		a.iconst(0);
		a.istore(6);
		int loop = a.label();
		int done = a.label();
		int bad = a.label();
		a.bind(loop);
		a.iload(6);
		a.iload(4);
		a.branch(Opcode.IF_ICMPGE, done);
		// d = dim k; s = subs[k]
		a.aload(2);
		a.iload(6);
		a.iconst(4);
		a.op(Opcode.IMUL);
		a.iconst(8);
		a.op(Opcode.IADD);
		a.invokestatic(r.qmInt());
		a.istore(7);
		a.aload(3);
		a.iload(6);
		a.aaload();
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(8);
		a.iload(8);
		a.branch(Opcode.IFLT, bad);
		a.iload(8);
		a.iload(7);
		a.branch(Opcode.IF_ICMPGE, bad);
		a.iload(5);
		a.iload(7);
		a.op(Opcode.IMUL);
		a.iload(8);
		a.op(Opcode.IADD);
		a.istore(5);
		a.iinc(6, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(bad);
		throwMessage(a, r, "aref: index out of bounds");
		a.bind(done);
		a.aload(2);
		a.iload(5);
		a.invokestatic(r.qmValue());
		a.invokestatic(r.doubleValueOf());
		a.areturn();
		return method(r, AREFN, BINARY_DESC, 6, 10, a);
	}

	// _qmDims(arr): the dimensions as a cons list of Longs. Locals: 0=arr, 1=a, 2=result,
	// 3=j.
	private static ArrayMethod buildDims(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(1);
		a.aconstNull();
		a.astore(2);
		headerInt(a, r, 1, 4);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(3);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(3);
		a.branch(Opcode.IFLT, done);
		a.iconst(2);
		a.anewarray(r.cp().addClass(r.cp().addUtf8("java/lang/Object")));
		a.dup();
		a.iconst(0);
		a.aload(1);
		a.iload(3);
		a.iconst(4);
		a.op(Opcode.IMUL);
		a.iconst(8);
		a.op(Opcode.IADD);
		a.invokestatic(r.qmInt());
		a.op(Opcode.I2L);
		a.invokestatic(r.longValueOf());
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(2);
		a.aastore();
		a.astore(2);
		a.iinc(3, -1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.aload(2);
		a.areturn();
		return method(r, DIMS, UNARY_DESC, 9, 4, a);
	}

	// _qmLength(arr): dim 0 at rank 1; a rank-2 matrix is no sequence.
	private static ArrayMethod buildLength(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(1);
		int rank1 = a.label();
		headerInt(a, r, 1, 4);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPEQ, rank1);
		throwMessage(a, r, "length: argument is not a sequence (rank-2 array)");
		a.bind(rank1);
		headerInt(a, r, 1, 8);
		a.op(Opcode.I2L);
		a.invokestatic(r.longValueOf());
		a.areturn();
		return method(r, LENGTH, UNARY_DESC, 4, 2, a);
	}

	// _qmToString(arr): "#<quantized-matrix q8-0 (rows cols)>". Locals: 0=arr, 1=a, 2=sb.
	private static ArrayMethod buildToString(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(1);
		a.anew(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.ldcString(r.cp().addString("#<quantized-matrix q8-0 ("));
		a.invokevirtual(r.sbAppendStr());
		headerInt(a, r, 1, 8);
		a.invokevirtual(r.sbAppendInt());
		a.astore(2);
		int rank1 = a.label();
		headerInt(a, r, 1, 4);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPNE, rank1);
		a.aload(2);
		a.ldcString(r.cp().addString(" "));
		a.invokevirtual(r.sbAppendStr());
		headerInt(a, r, 1, 12);
		a.invokevirtual(r.sbAppendInt());
		a.pop();
		a.bind(rank1);
		a.aload(2);
		a.ldcString(r.cp().addString(")>"));
		a.invokevirtual(r.sbAppendStr());
		a.invokevirtual(r.sbToString());
		a.areturn();
		return method(r, TO_STRING, TO_STRING_DESC, 5, 3, a);
	}

	// _qmP(o): T for a byte[], nil otherwise.
	private static ArrayMethod buildPredicate(Refs r) {
		JvmAsm a = new JvmAsm();
		int no = a.label();
		a.aload(0);
		a.instanceOf(r.byteArrayClass());
		a.branch(Opcode.IFEQ, no);
		a.ldcString(r.cp().addString("T"));
		a.areturn();
		a.bind(no);
		a.aconstNull();
		a.areturn();
		return method(r, PREDICATE, UNARY_DESC, 2, 1, a);
	}

	/**
	 * Locals {@code rowsSlot} / {@code colsSlot} := the row count (1 at rank 1) and the
	 * column count (the last dimension) of the matrix in local {@code arrSlot}.
	 */
	private static void emitRowsCols(JvmAsm a, Refs r, int arrSlot, int rowsSlot, int colsSlot) {
		int rank2 = a.label();
		int done = a.label();
		headerInt(a, r, arrSlot, 4);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPEQ, rank2);
		a.iconst(1);
		a.istore(rowsSlot);
		headerInt(a, r, arrSlot, 8);
		a.istore(colsSlot);
		a.branch(Opcode.GOTO, done);
		a.bind(rank2);
		headerInt(a, r, arrSlot, 8);
		a.istore(rowsSlot);
		headerInt(a, r, arrSlot, 12);
		a.istore(colsSlot);
		a.bind(done);
	}

	/**
	 * Unboxes local {@code fromSlot} (a Long) into int local {@code toSlot}, bounded by
	 * local {@code boundSlot}.
	 */
	private static void emitIndex(JvmAsm a, Refs r, int fromSlot, int toSlot, int boundSlot, String message) {
		a.aload(fromSlot);
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(toSlot);
		int bad = a.label();
		int ok = a.label();
		a.iload(toSlot);
		a.branch(Opcode.IFLT, bad);
		a.iload(toSlot);
		a.iload(boundSlot);
		a.branch(Opcode.IF_ICMPLT, ok);
		a.bind(bad);
		throwMessage(a, r, message);
		a.bind(ok);
	}

	// _qmQuant(m, row, col): the signed quant, a Long. Locals: 0=m, 1=row, 2=col, 3=a,
	// 4=rows, 5=cols, 6=i, 7=j, 8=flat.
	private static ArrayMethod buildQuant(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(3);
		emitRowsCols(a, r, 3, 4, 5);
		String message = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_QUANT_INTERNAL)
				+ ": index out of bounds";
		emitIndex(a, r, 1, 6, 4, message);
		emitIndex(a, r, 2, 7, 5, message);
		// flat = i * cols + j; bo = 8 + 4 * rank + (flat / 32) * 34; q = a[bo + 2 + flat
		// % 32]
		a.iload(6);
		a.iload(5);
		a.op(Opcode.IMUL);
		a.iload(7);
		a.op(Opcode.IADD);
		a.istore(8);
		a.aload(3);
		headerInt(a, r, 3, 4);
		a.iconst(4);
		a.op(Opcode.IMUL);
		a.iconst(8);
		a.op(Opcode.IADD);
		a.iload(8);
		a.iconst(BLOCK);
		a.op(Opcode.IDIV);
		a.iconst(BLOCK_BYTES);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.iload(8);
		a.iconst(BLOCK);
		a.op(Opcode.IREM);
		a.op(Opcode.IADD);
		a.baload();
		a.op(Opcode.I2L);
		a.invokestatic(r.longValueOf());
		a.areturn();
		return method(r, QUANT, TERNARY_DESC, 8, 9, a);
	}

	// _qmScale(m, row, block): the block's scale, a Double. Locals: 0=m, 1=row, 2=blk,
	// 3=a, 4=rows, 5=cols, 6=i, 7=b, 8=bpr, 9=bo.
	private static ArrayMethod buildScale(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(3);
		emitRowsCols(a, r, 3, 4, 5);
		a.iload(5);
		a.iconst(BLOCK);
		a.op(Opcode.IDIV);
		a.istore(8);
		String message = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_SCALE_INTERNAL)
				+ ": index out of bounds";
		emitIndex(a, r, 1, 6, 4, message);
		emitIndex(a, r, 2, 7, 8, message);
		// bo = 8 + 4 * rank + (i * bpr + b) * 34
		headerInt(a, r, 3, 4);
		a.iconst(4);
		a.op(Opcode.IMUL);
		a.iconst(8);
		a.op(Opcode.IADD);
		a.iload(6);
		a.iload(8);
		a.op(Opcode.IMUL);
		a.iload(7);
		a.op(Opcode.IADD);
		a.iconst(BLOCK_BYTES);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.istore(9);
		emitScale(a, r, 3, 9);
		a.f2d();
		a.invokestatic(r.doubleValueOf());
		a.areturn();
		return method(r, SCALE, TERNARY_DESC, 8, 10, a);
	}

	// _qmAlloc(op, rank, d0, d1): the checked, header-written, all-zero array. Locals:
	// 0=op, 1=rank, 2=d0, 3=d1, 4=cols, 5=total, 6=arr, 7=msg.
	private static ArrayMethod buildAlloc(Refs r) {
		JvmAsm a = new JvmAsm();
		int rankOk = a.label();
		int rank1 = a.label();
		int rank2 = a.label();
		a.iload(1);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPEQ, rank1);
		a.iload(1);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPEQ, rank2);
		a.aload(0);
		a.astore(7);
		a.anew(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(7);
		a.invokevirtual(r.sbAppendStr());
		a.ldcString(r.cp().addString(": a quantized matrix has rank 1 or 2, got rank "));
		a.invokevirtual(r.sbAppendStr());
		a.iload(1);
		a.invokevirtual(r.sbAppendInt());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 7);
		a.bind(rank1);
		a.iload(2);
		a.istore(4);
		a.iload(2);
		a.istore(5);
		a.branch(Opcode.GOTO, rankOk);
		a.bind(rank2);
		a.iload(3);
		a.istore(4);
		a.iload(2);
		a.iload(3);
		a.op(Opcode.IMUL);
		a.istore(5);
		a.bind(rankOk);
		// negative dimension, or a last dimension that is not a multiple of the block
		int negative = a.label();
		int colsOk = a.label();
		a.iload(2);
		a.branch(Opcode.IFLT, negative);
		a.iload(3);
		a.branch(Opcode.IFLT, negative);
		a.iload(4);
		a.iconst(BLOCK);
		a.op(Opcode.IREM);
		a.branch(Opcode.IFEQ, colsOk);
		a.anew(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(0);
		a.invokevirtual(r.sbAppendStr());
		a.ldcString(r.cp().addString(": the last dimension must be a multiple of 32 (the q8-0 block), got "));
		a.invokevirtual(r.sbAppendStr());
		a.iload(4);
		a.invokevirtual(r.sbAppendInt());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 7);
		a.bind(negative);
		a.anew(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(0);
		a.invokevirtual(r.sbAppendStr());
		a.ldcString(r.cp().addString(": a dimension must be non-negative"));
		a.invokevirtual(r.sbAppendStr());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 7);
		a.bind(colsOk);
		// arr = new byte[8 + 4 * rank + total / 32 * 34]; header
		a.iconst(8);
		a.iload(1);
		a.iconst(4);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.iload(5);
		a.iconst(BLOCK);
		a.op(Opcode.IDIV);
		a.iconst(BLOCK_BYTES);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.newarrayByte();
		a.astore(6);
		a.aload(6);
		a.iconst(0);
		a.iconst(FORMAT_Q8_0);
		a.invokestatic(r.qmPutInt());
		a.aload(6);
		a.iconst(4);
		a.iload(1);
		a.invokestatic(r.qmPutInt());
		a.aload(6);
		a.iconst(8);
		a.iload(2);
		a.invokestatic(r.qmPutInt());
		int done = a.label();
		a.iload(1);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPNE, done);
		a.aload(6);
		a.iconst(12);
		a.iload(3);
		a.invokestatic(r.qmPutInt());
		a.bind(done);
		a.aload(6);
		a.areturn();
		return method(r, ALLOC, ALLOC_DESC, 6, 8, a);
	}

	// _qmLocalName(o): a symbol's name after its last colon ("" for a non-symbol) -- how
	// q8-0, rontolisp:q8-0 and :q8-0 all name one format.
	private static ArrayMethod buildLocalName(Refs r) {
		JvmAsm a = new JvmAsm();
		int notString = a.label();
		a.aload(0);
		a.instanceOf(r.stringClass());
		a.branch(Opcode.IFEQ, notString);
		a.aload(0);
		a.checkcast(r.stringClass());
		a.astore(1);
		a.aload(1);
		a.aload(1);
		a.iconst(':');
		a.invokevirtual(r.stringLastIndexOf());
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(r.stringSubstring());
		a.areturn();
		a.bind(notString);
		a.ldcString(r.cp().addString(""));
		a.areturn();
		return method(r, LOCAL_NAME, LOCAL_NAME_DESC, 3, 2, a);
	}

	// _qmCheckFormat(op, format): signals unless the designator names q8-0.
	private static ArrayMethod buildCheckFormat(Refs r) {
		JvmAsm a = new JvmAsm();
		int ok = a.label();
		a.aload(1);
		a.invokestatic(r.qmLocalName());
		a.ldcString(r.cp().addString(LispNames.Q8_0));
		a.invokevirtual(r.stringEquals());
		a.branch(Opcode.IFNE, ok);
		a.anew(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(0);
		a.invokevirtual(r.sbAppendStr());
		a.ldcString(r.cp().addString(": the format must be q8-0"));
		a.invokevirtual(r.sbAppendStr());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 2);
		a.bind(ok);
		a.op(Opcode.RETURN);
		return method(r, CHECK_FORMAT, CHECK_FORMAT_DESC, 4, 3, a);
	}

	// _qmQuantizeBlocks(src, dst, ro): ggml's quantize_row_q8_0_ref over every 32-float
	// block of src, into the blocks of dst from ro. Locals: 0=src, 1=dst, 2=ro, 3=nb,
	// 4=b, 5=base, 6=bo, 7=amax, 8=v, 9=d, 10=id, 11=dh, 12=k, 13=x0, 14=q.
	private static ArrayMethod buildQuantizeBlocks(Refs r) {
		JvmAsm a = new JvmAsm();
		a.aload(0);
		a.arraylength();
		a.iconst(BLOCK);
		a.op(Opcode.IDIV);
		a.istore(3);
		a.iconst(0);
		a.istore(4);
		int blockLoop = a.label();
		int blockDone = a.label();
		a.bind(blockLoop);
		a.iload(4);
		a.iload(3);
		a.branch(Opcode.IF_ICMPGE, blockDone);
		a.iload(4);
		a.iconst(BLOCK);
		a.op(Opcode.IMUL);
		a.istore(5);
		a.iload(2);
		a.iload(4);
		a.iconst(BLOCK_BYTES);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.istore(6);
		// amax = 0f; for k: v = abs(src[base + k]); if (v > amax) amax = v
		a.op(Opcode.FCONST_0);
		a.fstore(7);
		a.iconst(0);
		a.istore(12);
		int amaxLoop = a.label();
		int amaxDone = a.label();
		int notBigger = a.label();
		a.bind(amaxLoop);
		a.iload(12);
		a.iconst(BLOCK);
		a.branch(Opcode.IF_ICMPGE, amaxDone);
		a.aload(0);
		a.iload(5);
		a.iload(12);
		a.op(Opcode.IADD);
		a.faload();
		a.invokestatic(r.mathAbsF());
		a.fstore(8);
		a.fload(8);
		a.fload(7);
		a.op(Opcode.FCMPL);
		a.branch(Opcode.IFLE, notBigger);
		a.fload(8);
		a.fstore(7);
		a.bind(notBigger);
		a.iinc(12, 1);
		a.branch(Opcode.GOTO, amaxLoop);
		a.bind(amaxDone);
		// d = amax / 127f; id = d != 0f ? 1f / d : 0f
		a.fload(7);
		a.iconst(127);
		a.i2f();
		a.op(Opcode.FDIV);
		a.fstore(9);
		int zeroScale = a.label();
		int idDone = a.label();
		a.fload(9);
		a.op(Opcode.FCONST_0);
		a.op(Opcode.FCMPL);
		a.branch(Opcode.IFEQ, zeroScale);
		a.op(Opcode.FCONST_1);
		a.fload(9);
		a.op(Opcode.FDIV);
		a.fstore(10);
		a.branch(Opcode.GOTO, idDone);
		a.bind(zeroScale);
		a.op(Opcode.FCONST_0);
		a.fstore(10);
		a.bind(idDone);
		// dh = floatToFloat16(d); dst[bo] = (byte) dh; dst[bo + 1] = (byte) (dh >>> 8)
		a.fload(9);
		a.invokestatic(r.floatToFloat16());
		a.istore(11);
		a.aload(1);
		a.iload(6);
		a.iload(11);
		a.op(Opcode.I2B);
		a.bastore();
		a.aload(1);
		a.iload(6);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.iload(11);
		a.iconst(8);
		a.op(Opcode.IUSHR);
		a.op(Opcode.I2B);
		a.bastore();
		// for k: x0 = src[base + k] * id; q = x0 < 0 ? -round(-x0) : round(x0);
		// dst[bo + 2 + k] = (byte) q
		a.iconst(0);
		a.istore(12);
		int qLoop = a.label();
		int qDone = a.label();
		int negativeX = a.label();
		int qStore = a.label();
		a.bind(qLoop);
		a.iload(12);
		a.iconst(BLOCK);
		a.branch(Opcode.IF_ICMPGE, qDone);
		a.aload(0);
		a.iload(5);
		a.iload(12);
		a.op(Opcode.IADD);
		a.faload();
		a.fload(10);
		a.op(Opcode.FMUL);
		a.fstore(13);
		a.fload(13);
		a.op(Opcode.FCONST_0);
		a.op(Opcode.FCMPG);
		a.branch(Opcode.IFLT, negativeX);
		a.fload(13);
		a.invokestatic(r.mathRoundF());
		a.istore(14);
		a.branch(Opcode.GOTO, qStore);
		a.bind(negativeX);
		a.fload(13);
		a.op(Opcode.FNEG);
		a.invokestatic(r.mathRoundF());
		a.op(Opcode.INEG);
		a.istore(14);
		a.bind(qStore);
		a.aload(1);
		a.iload(6);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.iload(12);
		a.op(Opcode.IADD);
		a.iload(14);
		a.op(Opcode.I2B);
		a.bastore();
		a.iinc(12, 1);
		a.branch(Opcode.GOTO, qLoop);
		a.bind(qDone);
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, blockLoop);
		a.bind(blockDone);
		a.op(Opcode.RETURN);
		return method(r, QUANTIZE_BLOCKS, QUANTIZE_BLOCKS_DESC, 6, 15, a);
	}

	// _qmMake(format, dims): the all-zero matrix. dims is a Long (rank 1) or a cons list
	// of Longs. Locals: 0=format, 1=dims, 2=rank, 3=d0, 4=d1, 5=cur.
	private static ArrayMethod buildMake(Refs r) {
		String op = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_QUANTIZED_MATRIX);
		JvmAsm a = new JvmAsm();
		a.ldcString(r.cp().addString(op));
		a.aload(0);
		a.invokestatic(r.qmCheckFormat());
		a.iconst(0);
		a.istore(2);
		a.iconst(0);
		a.istore(3);
		a.iconst(0);
		a.istore(4);
		int listCase = a.label();
		int alloc = a.label();
		a.aload(1);
		a.instanceOf(r.longClass());
		a.branch(Opcode.IFEQ, listCase);
		a.iconst(1);
		a.istore(2);
		a.aload(1);
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(3);
		a.branch(Opcode.GOTO, alloc);
		a.bind(listCase);
		a.aload(1);
		a.astore(5);
		int loop = a.label();
		int done = a.label();
		int notFirst = a.label();
		int notSecond = a.label();
		a.bind(loop);
		a.aload(5);
		a.instanceOf(r.objectArrayClass());
		a.branch(Opcode.IFEQ, done);
		a.iload(2);
		a.branch(Opcode.IFNE, notFirst);
		a.aload(5);
		a.checkcast(r.objectArrayClass());
		a.iconst(0);
		a.aaload();
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(3);
		a.branch(Opcode.GOTO, notSecond);
		a.bind(notFirst);
		a.iload(2);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPNE, notSecond);
		a.aload(5);
		a.checkcast(r.objectArrayClass());
		a.iconst(0);
		a.aaload();
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(4);
		a.bind(notSecond);
		a.iinc(2, 1);
		a.aload(5);
		a.checkcast(r.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.astore(5);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.bind(alloc);
		a.ldcString(r.cp().addString(op));
		a.iload(2);
		a.iload(3);
		a.iload(4);
		a.invokestatic(r.qmAlloc());
		a.areturn();
		return method(r, MAKE, BINARY_DESC, 5, 6, a);
	}

	// _qmQuantize(src, format): a packed float array of any width, its values narrowed
	// to f32, quantized block by block. Locals: 0=src, 1=format, 2=rank, 3=d0, 4=d1,
	// 5=total, 6=tmp, 7=i, 8=off, 9=res, 10=d.
	private static ArrayMethod buildQuantize(Refs r) {
		String op = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZE);
		JvmAsm a = new JvmAsm();
		a.ldcString(r.cp().addString(op));
		a.aload(1);
		a.invokestatic(r.qmCheckFormat());
		int gathered = a.label();
		for (JvmPackedFloatWidth w : new JvmPackedFloatWidth[] { JvmPackedFloatWidth.DOUBLE, JvmPackedFloatWidth.SINGLE,
				JvmPackedFloatWidth.BFLOAT16 }) {
			int next = a.label();
			ClassConstant arrayClass = r.cp().addClass(r.cp().addUtf8(w.descriptor()));
			a.aload(0);
			a.instanceOf(arrayClass);
			a.branch(Opcode.IFEQ, next);
			a.aload(0);
			a.checkcast(arrayClass);
			a.astore(10);
			a.aload(10);
			w.loadRank(a);
			a.istore(2);
			// rank outside 1..2: _qmAlloc reports it (dims are not read first)
			int rankBad = a.label();
			int rankGood = a.label();
			a.iload(2);
			a.iconst(1);
			a.branch(Opcode.IF_ICMPLT, rankBad);
			a.iload(2);
			a.iconst(2);
			a.branch(Opcode.IF_ICMPLE, rankGood);
			a.bind(rankBad);
			a.ldcString(r.cp().addString(op));
			a.iload(2);
			a.iconst(0);
			a.iconst(0);
			a.invokestatic(r.qmAlloc());
			a.pop();
			a.bind(rankGood);
			a.aload(10);
			a.iconst(0);
			w.loadDim(a);
			a.istore(3);
			a.iconst(0);
			a.istore(4);
			a.iload(3);
			a.istore(5);
			int rank1 = a.label();
			a.iload(2);
			a.iconst(2);
			a.branch(Opcode.IF_ICMPNE, rank1);
			a.aload(10);
			a.iconst(1);
			w.loadDim(a);
			a.istore(4);
			a.iload(3);
			a.iload(4);
			a.op(Opcode.IMUL);
			a.istore(5);
			a.bind(rank1);
			// tmp = new float[total]; tmp[i] = (float) elem(off + i)
			a.iload(2);
			w.emitDataOffset(a);
			a.istore(8);
			a.iload(5);
			a.newarrayFloat();
			a.astore(6);
			a.iconst(0);
			a.istore(7);
			int loop = a.label();
			int done = a.label();
			a.bind(loop);
			a.iload(7);
			a.iload(5);
			a.branch(Opcode.IF_ICMPGE, done);
			a.aload(6);
			a.iload(7);
			a.aload(10);
			a.iload(8);
			a.iload(7);
			a.op(Opcode.IADD);
			w.loadElem(a, r.bf16Value());
			a.d2f();
			a.fastore();
			a.iinc(7, 1);
			a.branch(Opcode.GOTO, loop);
			a.bind(done);
			a.branch(Opcode.GOTO, gathered);
			a.bind(next);
		}
		throwMessage(a, r, op + ": expects a packed float array");
		a.bind(gathered);
		a.ldcString(r.cp().addString(op));
		a.iload(2);
		a.iload(3);
		a.iload(4);
		a.invokestatic(r.qmAlloc());
		a.astore(9);
		a.aload(6);
		a.aload(9);
		a.iconst(8);
		a.iload(2);
		a.iconst(4);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.invokestatic(r.qmQuantizeBlocks());
		a.aload(9);
		a.areturn();
		return method(r, QUANTIZE, BINARY_DESC, 8, 11, a);
	}

	// _qmDequantize(m, element-type): a fresh packed array of the width named, every
	// element q * scale. Locals: 0=m, 1=etype, 2=a, 3=rank, 4=total, 5=arr, 6=off, 7=i,
	// 8=k, 9=dim, 10=name.
	private static ArrayMethod buildDequantize(Refs r) {
		String op = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DEQUANTIZE);
		JvmAsm a = new JvmAsm();
		int isMatrix = a.label();
		a.aload(0);
		a.instanceOf(r.byteArrayClass());
		a.branch(Opcode.IFNE, isMatrix);
		throwMessage(a, r, op + ": expects a quantized matrix");
		a.bind(isMatrix);
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(2);
		headerInt(a, r, 2, 4);
		a.istore(3);
		a.aload(2);
		a.invokestatic(r.qmTotal());
		a.istore(4);
		a.aload(1);
		a.invokestatic(r.qmLocalName());
		a.astore(10);
		String[] names = { LispNames.SINGLE_FLOAT, LispNames.DOUBLE_FLOAT, LispNames.BFLOAT16 };
		JvmPackedFloatWidth[] widths = { JvmPackedFloatWidth.SINGLE, JvmPackedFloatWidth.DOUBLE,
				JvmPackedFloatWidth.BFLOAT16 };
		for (int n = 0; n < names.length; n++) {
			JvmPackedFloatWidth w = widths[n];
			int next = a.label();
			a.aload(10);
			a.ldcString(r.cp().addString(names[n]));
			a.invokevirtual(r.stringEquals());
			a.branch(Opcode.IFEQ, next);
			// arr = new backing[off + total]; rank; dims
			a.iload(3);
			w.emitDataOffset(a);
			a.istore(6);
			a.iload(6);
			a.iload(4);
			a.op(Opcode.IADD);
			w.newBacking(a);
			a.astore(5);
			a.aload(5);
			a.iload(3);
			w.storeRank(a);
			a.iconst(0);
			a.istore(8);
			int dimLoop = a.label();
			int dimDone = a.label();
			a.bind(dimLoop);
			a.iload(8);
			a.iload(3);
			a.branch(Opcode.IF_ICMPGE, dimDone);
			a.aload(2);
			a.iload(8);
			a.iconst(4);
			a.op(Opcode.IMUL);
			a.iconst(8);
			a.op(Opcode.IADD);
			a.invokestatic(r.qmInt());
			a.istore(9);
			w.storeDim(a, 5, 8, 9);
			a.iinc(8, 1);
			a.branch(Opcode.GOTO, dimLoop);
			a.bind(dimDone);
			// arr[off + i] = narrow(_qmValue(a, i))
			a.iconst(0);
			a.istore(7);
			int loop = a.label();
			int done = a.label();
			a.bind(loop);
			a.iload(7);
			a.iload(4);
			a.branch(Opcode.IF_ICMPGE, done);
			a.aload(5);
			a.iload(6);
			a.iload(7);
			a.op(Opcode.IADD);
			a.aload(2);
			a.iload(7);
			a.invokestatic(r.qmValue());
			w.storeElem(a, r.bf16Bits());
			a.iinc(7, 1);
			a.branch(Opcode.GOTO, loop);
			a.bind(done);
			a.aload(5);
			a.areturn();
			a.bind(next);
		}
		throwMessage(a, r, op + ": element-type must be single-float, double-float or bfloat16");
		return method(r, DEQUANTIZE, BINARY_DESC, 8, 11, a);
	}

}
