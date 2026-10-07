package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
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

	static final String ROWS = "_qmRows";

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
	private record Refs(ConstantPool cp, ClassEntry byteArrayClass, ClassEntry longClass, ClassEntry objectArrayClass,
			ClassEntry stringClass, ClassEntry rtExClass, MethodRefEntry rtExInit, MethodRefEntry longIntValue,
			MethodRefEntry longValueOf, MethodRefEntry doubleValueOf, MethodRefEntry float16ToFloat,
			MethodRefEntry floatToFloat16, MethodRefEntry mathAbsF, MethodRefEntry mathRoundF, MethodRefEntry qmInt,
			MethodRefEntry qmPutInt, MethodRefEntry qmTotal, MethodRefEntry qmValue, MethodRefEntry qmAlloc,
			MethodRefEntry qmLocalName, MethodRefEntry qmCheckFormat, MethodRefEntry qmQuantizeBlocks,
			ClassEntry sbClass, MethodRefEntry sbInit, MethodRefEntry sbAppendStr, MethodRefEntry sbAppendInt,
			MethodRefEntry sbToString, MethodRefEntry stringLastIndexOf, MethodRefEntry stringSubstring,
			MethodRefEntry stringEquals, MethodRefEntry bf16Value, MethodRefEntry bf16Bits,
			MethodRefEntry systemArraycopy, MethodRefEntry ckBound, MethodRefEntry rankErr) {

	}

	/**
	 * Builds the helpers.
	 * @param cp the constant pool
	 * @param selfClass the generated program class
	 * @param octets whether an {@code (unsigned-byte 8)} vector -- the other
	 * {@code byte[]}, whose slot 0 is {@link JvmIntArrayRuntimeBuilder#OCTET_TAG} -- can
	 * exist, so the tests that take any value tell the two apart
	 * @return the helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp, ClassEntry selfClass, boolean octets) {
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		ClassEntry doubleClass = cp.classEntry("java/lang/Double");
		ClassEntry floatClass = cp.classEntry("java/lang/Float");
		ClassEntry mathClass = cp.classEntry("java/lang/Math");
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		ClassEntry sbClass = cp.classEntry("java/lang/StringBuilder");
		ClassEntry rtExClass = cp.classEntry("java/lang/RuntimeException");
		Refs r = new Refs(cp, cp.classEntry("[B"), longClass, cp.classEntry("[Ljava/lang/Object;"), stringClass,
				rtExClass, cp.methodRef(rtExClass, "<init>", "(Ljava/lang/String;)V"),
				cp.methodRef(longClass, "intValue", "()I"), cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;"),
				cp.methodRef(doubleClass, "valueOf", "(D)Ljava/lang/Double;"),
				cp.methodRef(floatClass, "float16ToFloat", "(S)F"), cp.methodRef(floatClass, "floatToFloat16", "(F)S"),
				cp.methodRef(mathClass, "abs", "(F)F"), cp.methodRef(mathClass, "round", "(F)I"),
				self(cp, selfClass, INT, INT_DESC), self(cp, selfClass, PUT_INT, PUT_INT_DESC),
				self(cp, selfClass, TOTAL, TOTAL_DESC), self(cp, selfClass, VALUE, VALUE_DESC),
				self(cp, selfClass, ALLOC, ALLOC_DESC), self(cp, selfClass, LOCAL_NAME, LOCAL_NAME_DESC),
				self(cp, selfClass, CHECK_FORMAT, CHECK_FORMAT_DESC),
				self(cp, selfClass, QUANTIZE_BLOCKS, QUANTIZE_BLOCKS_DESC), sbClass,
				cp.methodRef(sbClass, "<init>", "()V"),
				cp.methodRef(sbClass, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"),
				cp.methodRef(sbClass, "append", "(I)Ljava/lang/StringBuilder;"),
				cp.methodRef(sbClass, "toString", "()Ljava/lang/String;"),
				cp.methodRef(stringClass, "lastIndexOf", "(I)I"),
				cp.methodRef(stringClass, "substring", "(I)Ljava/lang/String;"),
				cp.methodRef(stringClass, "equals", "(Ljava/lang/Object;)Z"),
				self(cp, selfClass, JvmFloatArrayRuntimeBuilder.BF16_VALUE,
						JvmFloatArrayRuntimeBuilder.BF16_VALUE_DESC),
				self(cp, selfClass, JvmFloatArrayRuntimeBuilder.BF16_BITS, JvmFloatArrayRuntimeBuilder.BF16_BITS_DESC),
				cp.methodRef(cp.classEntry("java/lang/System"), "arraycopy",
						"(Ljava/lang/Object;ILjava/lang/Object;II)V"),
				self(cp, selfClass, JvmOperandTypeRuntime.CK_BOUND, JvmOperandTypeRuntime.CK_BOUND_DESC),
				self(cp, selfClass, JvmArrayRuntimeBuilder.RANK_ERR, JvmArrayRuntimeBuilder.RANK_ERR_DESC));
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
		methods.add(buildPredicate(r, octets));
		methods.add(buildQuant(r));
		methods.add(buildScale(r));
		methods.add(buildAlloc(r));
		methods.add(buildLocalName(r));
		methods.add(buildCheckFormat(r));
		methods.add(buildQuantizeBlocks(r));
		methods.add(buildMake(r));
		methods.add(buildQuantize(r));
		methods.add(buildDequantize(r, octets));
		methods.add(buildRows(r, octets));
		return methods;
	}

	private static MethodRefEntry self(ConstantPool cp, ClassEntry selfClass, String name, String desc) {
		return cp.methodRef(selfClass, name, desc);
	}

	private static ArrayMethod method(Refs r, String name, String desc, int maxStack, int maxLocals, MethodCode a) {
		return new ArrayMethod(r.cp().utf8Entry(name), r.cp().utf8Entry(desc), a);
	}

	private static void throwMessage(MethodCode a, Refs r, String message) {
		a.new_(r.rtExClass());
		a.dup();
		a.ldc(r.cp().stringEntry(message));
		a.invokespecial(r.rtExInit());
		a.athrow();
	}

	/** Throws a RuntimeException whose message is the String on the stack. */
	private static void throwStackMessage(MethodCode a, Refs r, int msgSlot) {
		a.astore(msgSlot);
		a.new_(r.rtExClass());
		a.dup();
		a.aload(msgSlot);
		a.invokespecial(r.rtExInit());
		a.athrow();
	}

	/**
	 * Stack: {@code (...) -> (..., int)}: the header int at {@code off} of local
	 * {@code arr}.
	 */
	private static void headerInt(MethodCode a, Refs r, int arrSlot, int off) {
		a.aload(arrSlot);
		a.loadConstant(off);
		a.invokestatic(r.qmInt());
	}

	// _qmInt(a, off): the little-endian int at off.
	private static ArrayMethod buildInt(Refs r) {
		MethodCode a = new MethodCode();
		for (int k = 0; k < 4; k++) {
			a.aload(0);
			a.iload(1);
			if (k > 0) {
				a.loadConstant(k);
				a.iadd();
			}
			a.baload();
			a.loadConstant(255);
			a.iand();
			if (k > 0) {
				a.loadConstant(8 * k);
				a.ishl();
				a.ior();
			}
		}
		a.ireturn();
		return method(r, INT, INT_DESC, 6, 2, a);
	}

	// _qmPutInt(a, off, v): writes v little-endian at off.
	private static ArrayMethod buildPutInt(Refs r) {
		MethodCode a = new MethodCode();
		for (int k = 0; k < 4; k++) {
			a.aload(0);
			a.iload(1);
			if (k > 0) {
				a.loadConstant(k);
				a.iadd();
			}
			a.iload(2);
			if (k > 0) {
				a.loadConstant(8 * k);
				a.iushr();
			}
			a.i2b();
			a.bastore();
		}
		a.return_();
		return method(r, PUT_INT, PUT_INT_DESC, 5, 3, a);
	}

	// _qmTotal(a): dim0 * (rank == 2 ? dim1 : 1).
	private static ArrayMethod buildTotal(Refs r) {
		MethodCode a = new MethodCode();
		MethodCode.Label rank1 = a.newLabel();
		headerInt(a, r, 0, 8);
		headerInt(a, r, 0, 4);
		a.loadConstant(2);
		a.if_icmpne(rank1);
		headerInt(a, r, 0, 12);
		a.imul();
		a.labelBinding(rank1);
		a.ireturn();
		return method(r, TOTAL, TOTAL_DESC, 5, 1, a);
	}

	// _qmValue(a, flat): q * scale as a double. Locals: 0=a, 1=flat, 2=bo.
	private static ArrayMethod buildValue(Refs r) {
		MethodCode a = new MethodCode();
		// bo = 8 + 4 * rank + (flat / 32) * 34
		headerInt(a, r, 0, 4);
		a.loadConstant(4);
		a.imul();
		a.loadConstant(8);
		a.iadd();
		a.iload(1);
		a.loadConstant(BLOCK);
		a.idiv();
		a.loadConstant(BLOCK_BYTES);
		a.imul();
		a.iadd();
		a.istore(2);
		// (double) a[bo + 2 + flat % 32]
		a.aload(0);
		a.iload(2);
		a.loadConstant(2);
		a.iadd();
		a.iload(1);
		a.loadConstant(BLOCK);
		a.irem();
		a.iadd();
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
	private static void emitScale(MethodCode a, Refs r, int arrSlot, int boSlot) {
		a.aload(arrSlot);
		a.iload(boSlot);
		a.baload();
		a.loadConstant(255);
		a.iand();
		a.aload(arrSlot);
		a.iload(boSlot);
		a.loadConstant(1);
		a.iadd();
		a.baload();
		a.loadConstant(8);
		a.ishl();
		a.ior();
		a.i2s();
		a.invokestatic(r.float16ToFloat());
	}

	// _qmAref1(arr, i): the element at flat index i (rank-1 aref and row-major-aref), i
	// checked against the total size (_ckBound, JvmOperandTypeRuntime). Locals: 0=arr,
	// 1=i, 2=a.
	private static ArrayMethod buildAref1(Refs r) {
		MethodCode a = new MethodCode();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(2);
		a.aload(2);
		a.aload(1);
		a.aload(2);
		a.invokestatic(r.qmTotal());
		a.invokestatic(r.ckBound());
		a.invokestatic(r.qmValue());
		a.invokestatic(r.doubleValueOf());
		a.areturn();
		return method(r, AREF1, BINARY_DESC, 6, 3, a);
	}

	// _qmAref2(arr, i, j), each subscript checked against its own dimension. Locals:
	// 0=arr, 1=i, 2=j, 3=a, 4=row, 5=col, 6=cols.
	private static ArrayMethod buildAref2(Refs r) {
		MethodCode a = new MethodCode();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(3);
		MethodCode.Label rank2 = a.newLabel();
		headerInt(a, r, 3, 4);
		a.loadConstant(2);
		a.if_icmpeq(rank2);
		a.aload(0);
		a.loadConstant(2);
		a.invokestatic(r.rankErr());
		a.athrow();
		a.labelBinding(rank2);
		headerInt(a, r, 3, 12);
		a.istore(6);
		a.aload(1);
		headerInt(a, r, 3, 8);
		a.invokestatic(r.ckBound());
		a.istore(4);
		a.aload(2);
		a.iload(6);
		a.invokestatic(r.ckBound());
		a.istore(5);
		a.aload(3);
		a.iload(4);
		a.iload(6);
		a.imul();
		a.iload(5);
		a.iadd();
		a.invokestatic(r.qmValue());
		a.invokestatic(r.doubleValueOf());
		a.areturn();
		return method(r, AREF2, TERNARY_DESC, 6, 8, a);
	}

	// _qmArefN(arr, subs): the Horner fold over the header dims, each subscript checked
	// against its own dimension. Locals: 0=arr, 1=subs, 2=a, 3=subsArr, 4=rank, 5=flat,
	// 6=k, 7=d, 8=s.
	private static ArrayMethod buildArefN(Refs r) {
		MethodCode a = new MethodCode();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(2);
		a.aload(1);
		a.checkcast(r.objectArrayClass());
		a.astore(3);
		headerInt(a, r, 2, 4);
		a.istore(4);
		MethodCode.Label rankOk = a.newLabel();
		a.aload(3);
		a.arraylength();
		a.iload(4);
		a.if_icmpeq(rankOk);
		a.aload(0);
		a.aload(3);
		a.arraylength();
		a.invokestatic(r.rankErr());
		a.athrow();
		a.labelBinding(rankOk);
		a.loadConstant(0);
		a.istore(5);
		a.loadConstant(0);
		a.istore(6);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(loop);
		a.iload(6);
		a.iload(4);
		a.if_icmpge(done);
		// d = dim k; s = subs[k], checked against d
		a.aload(2);
		a.iload(6);
		a.loadConstant(4);
		a.imul();
		a.loadConstant(8);
		a.iadd();
		a.invokestatic(r.qmInt());
		a.istore(7);
		a.aload(3);
		a.iload(6);
		a.aaload();
		a.iload(7);
		a.invokestatic(r.ckBound());
		a.istore(8);
		a.iload(5);
		a.iload(7);
		a.imul();
		a.iload(8);
		a.iadd();
		a.istore(5);
		a.iinc(6, 1);
		a.goto_(loop);
		a.labelBinding(done);
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
		MethodCode a = new MethodCode();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(1);
		a.aconst_null();
		a.astore(2);
		headerInt(a, r, 1, 4);
		a.loadConstant(1);
		a.isub();
		a.istore(3);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(loop);
		a.iload(3);
		a.iflt(done);
		a.loadConstant(2);
		a.anewarray(r.cp().classEntry("java/lang/Object"));
		a.dup();
		a.loadConstant(0);
		a.aload(1);
		a.iload(3);
		a.loadConstant(4);
		a.imul();
		a.loadConstant(8);
		a.iadd();
		a.invokestatic(r.qmInt());
		a.i2l();
		a.invokestatic(r.longValueOf());
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(2);
		a.aastore();
		a.astore(2);
		a.iinc(3, -1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(2);
		a.areturn();
		return method(r, DIMS, UNARY_DESC, 9, 4, a);
	}

	// _qmLength(arr): dim 0 at rank 1; a rank-2 matrix is no sequence.
	private static ArrayMethod buildLength(Refs r) {
		MethodCode a = new MethodCode();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(1);
		MethodCode.Label rank1 = a.newLabel();
		headerInt(a, r, 1, 4);
		a.loadConstant(1);
		a.if_icmpeq(rank1);
		throwMessage(a, r, "length: argument is not a sequence (rank-2 array)");
		a.labelBinding(rank1);
		headerInt(a, r, 1, 8);
		a.i2l();
		a.invokestatic(r.longValueOf());
		a.areturn();
		return method(r, LENGTH, UNARY_DESC, 4, 2, a);
	}

	// _qmToString(arr): "#<quantized-matrix q8-0 (rows cols)>". Locals: 0=arr, 1=a, 2=sb.
	private static ArrayMethod buildToString(Refs r) {
		MethodCode a = new MethodCode();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(1);
		a.new_(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.ldc(r.cp().stringEntry("#<quantized-matrix q8-0 ("));
		a.invokevirtual(r.sbAppendStr());
		headerInt(a, r, 1, 8);
		a.invokevirtual(r.sbAppendInt());
		a.astore(2);
		MethodCode.Label rank1 = a.newLabel();
		headerInt(a, r, 1, 4);
		a.loadConstant(2);
		a.if_icmpne(rank1);
		a.aload(2);
		a.ldc(r.cp().stringEntry(" "));
		a.invokevirtual(r.sbAppendStr());
		headerInt(a, r, 1, 12);
		a.invokevirtual(r.sbAppendInt());
		a.pop();
		a.labelBinding(rank1);
		a.aload(2);
		a.ldc(r.cp().stringEntry(")>"));
		a.invokevirtual(r.sbAppendStr());
		a.invokevirtual(r.sbToString());
		a.areturn();
		return method(r, TO_STRING, TO_STRING_DESC, 5, 3, a);
	}

	// Branches to notMatrix unless local slot holds a quantized matrix: a byte[] -- whose
	// slot 0 is not the octet vector's tag, where one of those can exist.
	private static void emitMatrixTest(MethodCode a, Refs r, int slot, MethodCode.Label notMatrix, boolean octets) {
		a.aload(slot);
		a.instanceOf(r.byteArrayClass());
		a.ifeq(notMatrix);
		if (octets) {
			a.aload(slot);
			a.checkcast(r.byteArrayClass());
			a.loadConstant(0);
			a.baload();
			a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			a.if_icmpeq(notMatrix);
		}
	}

	// _qmP(o): T for a quantized matrix, nil otherwise.
	private static ArrayMethod buildPredicate(Refs r, boolean octets) {
		MethodCode a = new MethodCode();
		MethodCode.Label no = a.newLabel();
		emitMatrixTest(a, r, 0, no, octets);
		a.ldc(r.cp().stringEntry("T"));
		a.areturn();
		a.labelBinding(no);
		a.aconst_null();
		a.areturn();
		return method(r, PREDICATE, UNARY_DESC, 2, 1, a);
	}

	/**
	 * Locals {@code rowsSlot} / {@code colsSlot} := the row count (1 at rank 1) and the
	 * column count (the last dimension) of the matrix in local {@code arrSlot}.
	 */
	private static void emitRowsCols(MethodCode a, Refs r, int arrSlot, int rowsSlot, int colsSlot) {
		MethodCode.Label rank2 = a.newLabel();
		MethodCode.Label done = a.newLabel();
		headerInt(a, r, arrSlot, 4);
		a.loadConstant(2);
		a.if_icmpeq(rank2);
		a.loadConstant(1);
		a.istore(rowsSlot);
		headerInt(a, r, arrSlot, 8);
		a.istore(colsSlot);
		a.goto_(done);
		a.labelBinding(rank2);
		headerInt(a, r, arrSlot, 8);
		a.istore(rowsSlot);
		headerInt(a, r, arrSlot, 12);
		a.istore(colsSlot);
		a.labelBinding(done);
	}

	/**
	 * Unboxes local {@code fromSlot} (a Long) into int local {@code toSlot}, bounded by
	 * local {@code boundSlot}.
	 */
	private static void emitIndex(MethodCode a, Refs r, int fromSlot, int toSlot, int boundSlot, String message) {
		a.aload(fromSlot);
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(toSlot);
		MethodCode.Label bad = a.newLabel();
		MethodCode.Label ok = a.newLabel();
		a.iload(toSlot);
		a.iflt(bad);
		a.iload(toSlot);
		a.iload(boundSlot);
		a.if_icmplt(ok);
		a.labelBinding(bad);
		throwMessage(a, r, message);
		a.labelBinding(ok);
	}

	// _qmQuant(m, row, col): the signed quant, a Long. Locals: 0=m, 1=row, 2=col, 3=a,
	// 4=rows, 5=cols, 6=i, 7=j, 8=flat.
	private static ArrayMethod buildQuant(Refs r) {
		MethodCode a = new MethodCode();
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
		a.imul();
		a.iload(7);
		a.iadd();
		a.istore(8);
		a.aload(3);
		headerInt(a, r, 3, 4);
		a.loadConstant(4);
		a.imul();
		a.loadConstant(8);
		a.iadd();
		a.iload(8);
		a.loadConstant(BLOCK);
		a.idiv();
		a.loadConstant(BLOCK_BYTES);
		a.imul();
		a.iadd();
		a.loadConstant(2);
		a.iadd();
		a.iload(8);
		a.loadConstant(BLOCK);
		a.irem();
		a.iadd();
		a.baload();
		a.i2l();
		a.invokestatic(r.longValueOf());
		a.areturn();
		return method(r, QUANT, TERNARY_DESC, 8, 9, a);
	}

	// _qmScale(m, row, block): the block's scale, a Double. Locals: 0=m, 1=row, 2=blk,
	// 3=a, 4=rows, 5=cols, 6=i, 7=b, 8=bpr, 9=bo.
	private static ArrayMethod buildScale(Refs r) {
		MethodCode a = new MethodCode();
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(3);
		emitRowsCols(a, r, 3, 4, 5);
		a.iload(5);
		a.loadConstant(BLOCK);
		a.idiv();
		a.istore(8);
		String message = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_SCALE_INTERNAL)
				+ ": index out of bounds";
		emitIndex(a, r, 1, 6, 4, message);
		emitIndex(a, r, 2, 7, 8, message);
		// bo = 8 + 4 * rank + (i * bpr + b) * 34
		headerInt(a, r, 3, 4);
		a.loadConstant(4);
		a.imul();
		a.loadConstant(8);
		a.iadd();
		a.iload(6);
		a.iload(8);
		a.imul();
		a.iload(7);
		a.iadd();
		a.loadConstant(BLOCK_BYTES);
		a.imul();
		a.iadd();
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
		MethodCode a = new MethodCode();
		MethodCode.Label rankOk = a.newLabel();
		MethodCode.Label rank1 = a.newLabel();
		MethodCode.Label rank2 = a.newLabel();
		a.iload(1);
		a.loadConstant(1);
		a.if_icmpeq(rank1);
		a.iload(1);
		a.loadConstant(2);
		a.if_icmpeq(rank2);
		a.aload(0);
		a.astore(7);
		a.new_(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(7);
		a.invokevirtual(r.sbAppendStr());
		a.ldc(r.cp().stringEntry(": a quantized matrix has rank 1 or 2, got rank "));
		a.invokevirtual(r.sbAppendStr());
		a.iload(1);
		a.invokevirtual(r.sbAppendInt());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 7);
		a.labelBinding(rank1);
		a.iload(2);
		a.istore(4);
		a.iload(2);
		a.istore(5);
		a.goto_(rankOk);
		a.labelBinding(rank2);
		a.iload(3);
		a.istore(4);
		a.iload(2);
		a.iload(3);
		a.imul();
		a.istore(5);
		a.labelBinding(rankOk);
		// negative dimension, or a last dimension that is not a multiple of the block
		MethodCode.Label negative = a.newLabel();
		MethodCode.Label colsOk = a.newLabel();
		a.iload(2);
		a.iflt(negative);
		a.iload(3);
		a.iflt(negative);
		a.iload(4);
		a.loadConstant(BLOCK);
		a.irem();
		a.ifeq(colsOk);
		a.new_(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(0);
		a.invokevirtual(r.sbAppendStr());
		a.ldc(r.cp().stringEntry(": the last dimension must be a multiple of 32 (the q8-0 block), got "));
		a.invokevirtual(r.sbAppendStr());
		a.iload(4);
		a.invokevirtual(r.sbAppendInt());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 7);
		a.labelBinding(negative);
		a.new_(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(0);
		a.invokevirtual(r.sbAppendStr());
		a.ldc(r.cp().stringEntry(": a dimension must be non-negative"));
		a.invokevirtual(r.sbAppendStr());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 7);
		a.labelBinding(colsOk);
		// arr = new byte[8 + 4 * rank + total / 32 * 34]; header
		a.loadConstant(8);
		a.iload(1);
		a.loadConstant(4);
		a.imul();
		a.iadd();
		a.iload(5);
		a.loadConstant(BLOCK);
		a.idiv();
		a.loadConstant(BLOCK_BYTES);
		a.imul();
		a.iadd();
		a.newarray(TypeKind.BYTE);
		a.astore(6);
		a.aload(6);
		a.loadConstant(0);
		a.loadConstant(FORMAT_Q8_0);
		a.invokestatic(r.qmPutInt());
		a.aload(6);
		a.loadConstant(4);
		a.iload(1);
		a.invokestatic(r.qmPutInt());
		a.aload(6);
		a.loadConstant(8);
		a.iload(2);
		a.invokestatic(r.qmPutInt());
		MethodCode.Label done = a.newLabel();
		a.iload(1);
		a.loadConstant(2);
		a.if_icmpne(done);
		a.aload(6);
		a.loadConstant(12);
		a.iload(3);
		a.invokestatic(r.qmPutInt());
		a.labelBinding(done);
		a.aload(6);
		a.areturn();
		return method(r, ALLOC, ALLOC_DESC, 6, 8, a);
	}

	// _qmLocalName(o): a symbol's name after its last colon ("" for a non-symbol) -- how
	// q8-0, rontolisp:q8-0 and :q8-0 all name one format.
	private static ArrayMethod buildLocalName(Refs r) {
		MethodCode a = new MethodCode();
		MethodCode.Label notString = a.newLabel();
		a.aload(0);
		a.instanceOf(r.stringClass());
		a.ifeq(notString);
		a.aload(0);
		a.checkcast(r.stringClass());
		a.astore(1);
		a.aload(1);
		a.aload(1);
		a.loadConstant(':');
		a.invokevirtual(r.stringLastIndexOf());
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(r.stringSubstring());
		a.areturn();
		a.labelBinding(notString);
		a.ldc(r.cp().stringEntry(""));
		a.areturn();
		return method(r, LOCAL_NAME, LOCAL_NAME_DESC, 3, 2, a);
	}

	// _qmCheckFormat(op, format): signals unless the designator names q8-0.
	private static ArrayMethod buildCheckFormat(Refs r) {
		MethodCode a = new MethodCode();
		MethodCode.Label ok = a.newLabel();
		a.aload(1);
		a.invokestatic(r.qmLocalName());
		a.ldc(r.cp().stringEntry(LispNames.Q8_0));
		a.invokevirtual(r.stringEquals());
		a.ifne(ok);
		a.new_(r.sbClass());
		a.dup();
		a.invokespecial(r.sbInit());
		a.aload(0);
		a.invokevirtual(r.sbAppendStr());
		a.ldc(r.cp().stringEntry(": the format must be q8-0"));
		a.invokevirtual(r.sbAppendStr());
		a.invokevirtual(r.sbToString());
		throwStackMessage(a, r, 2);
		a.labelBinding(ok);
		a.return_();
		return method(r, CHECK_FORMAT, CHECK_FORMAT_DESC, 4, 3, a);
	}

	// _qmQuantizeBlocks(src, dst, ro): ggml's quantize_row_q8_0_ref over every 32-float
	// block of src, into the blocks of dst from ro. Locals: 0=src, 1=dst, 2=ro, 3=nb,
	// 4=b, 5=base, 6=bo, 7=amax, 8=v, 9=d, 10=id, 11=dh, 12=k, 13=x0, 14=q.
	private static ArrayMethod buildQuantizeBlocks(Refs r) {
		MethodCode a = new MethodCode();
		a.aload(0);
		a.arraylength();
		a.loadConstant(BLOCK);
		a.idiv();
		a.istore(3);
		a.loadConstant(0);
		a.istore(4);
		MethodCode.Label blockLoop = a.newLabel();
		MethodCode.Label blockDone = a.newLabel();
		a.labelBinding(blockLoop);
		a.iload(4);
		a.iload(3);
		a.if_icmpge(blockDone);
		a.iload(4);
		a.loadConstant(BLOCK);
		a.imul();
		a.istore(5);
		a.iload(2);
		a.iload(4);
		a.loadConstant(BLOCK_BYTES);
		a.imul();
		a.iadd();
		a.istore(6);
		// amax = 0f; for k: v = abs(src[base + k]); if (v > amax) amax = v
		a.fconst_0();
		a.fstore(7);
		a.loadConstant(0);
		a.istore(12);
		MethodCode.Label amaxLoop = a.newLabel();
		MethodCode.Label amaxDone = a.newLabel();
		MethodCode.Label notBigger = a.newLabel();
		a.labelBinding(amaxLoop);
		a.iload(12);
		a.loadConstant(BLOCK);
		a.if_icmpge(amaxDone);
		a.aload(0);
		a.iload(5);
		a.iload(12);
		a.iadd();
		a.faload();
		a.invokestatic(r.mathAbsF());
		a.fstore(8);
		a.fload(8);
		a.fload(7);
		a.fcmpl();
		a.ifle(notBigger);
		a.fload(8);
		a.fstore(7);
		a.labelBinding(notBigger);
		a.iinc(12, 1);
		a.goto_(amaxLoop);
		a.labelBinding(amaxDone);
		// d = amax / 127f; id = d != 0f ? 1f / d : 0f
		a.fload(7);
		a.loadConstant(127);
		a.i2f();
		a.fdiv();
		a.fstore(9);
		MethodCode.Label zeroScale = a.newLabel();
		MethodCode.Label idDone = a.newLabel();
		a.fload(9);
		a.fconst_0();
		a.fcmpl();
		a.ifeq(zeroScale);
		a.fconst_1();
		a.fload(9);
		a.fdiv();
		a.fstore(10);
		a.goto_(idDone);
		a.labelBinding(zeroScale);
		a.fconst_0();
		a.fstore(10);
		a.labelBinding(idDone);
		// dh = floatToFloat16(d); dst[bo] = (byte) dh; dst[bo + 1] = (byte) (dh >>> 8)
		a.fload(9);
		a.invokestatic(r.floatToFloat16());
		a.istore(11);
		a.aload(1);
		a.iload(6);
		a.iload(11);
		a.i2b();
		a.bastore();
		a.aload(1);
		a.iload(6);
		a.loadConstant(1);
		a.iadd();
		a.iload(11);
		a.loadConstant(8);
		a.iushr();
		a.i2b();
		a.bastore();
		// for k: x0 = src[base + k] * id; q = x0 < 0 ? -round(-x0) : round(x0);
		// dst[bo + 2 + k] = (byte) q
		a.loadConstant(0);
		a.istore(12);
		MethodCode.Label qLoop = a.newLabel();
		MethodCode.Label qDone = a.newLabel();
		MethodCode.Label negativeX = a.newLabel();
		MethodCode.Label qStore = a.newLabel();
		a.labelBinding(qLoop);
		a.iload(12);
		a.loadConstant(BLOCK);
		a.if_icmpge(qDone);
		a.aload(0);
		a.iload(5);
		a.iload(12);
		a.iadd();
		a.faload();
		a.fload(10);
		a.fmul();
		a.fstore(13);
		a.fload(13);
		a.fconst_0();
		a.fcmpg();
		a.iflt(negativeX);
		a.fload(13);
		a.invokestatic(r.mathRoundF());
		a.istore(14);
		a.goto_(qStore);
		a.labelBinding(negativeX);
		a.fload(13);
		a.fneg();
		a.invokestatic(r.mathRoundF());
		a.ineg();
		a.istore(14);
		a.labelBinding(qStore);
		a.aload(1);
		a.iload(6);
		a.loadConstant(2);
		a.iadd();
		a.iload(12);
		a.iadd();
		a.iload(14);
		a.i2b();
		a.bastore();
		a.iinc(12, 1);
		a.goto_(qLoop);
		a.labelBinding(qDone);
		a.iinc(4, 1);
		a.goto_(blockLoop);
		a.labelBinding(blockDone);
		a.return_();
		return method(r, QUANTIZE_BLOCKS, QUANTIZE_BLOCKS_DESC, 6, 15, a);
	}

	// _qmMake(format, dims): the all-zero matrix. dims is a Long (rank 1) or a cons list
	// of Longs. Locals: 0=format, 1=dims, 2=rank, 3=d0, 4=d1, 5=cur.
	private static ArrayMethod buildMake(Refs r) {
		String op = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_QUANTIZED_MATRIX);
		MethodCode a = new MethodCode();
		a.ldc(r.cp().stringEntry(op));
		a.aload(0);
		a.invokestatic(r.qmCheckFormat());
		a.loadConstant(0);
		a.istore(2);
		a.loadConstant(0);
		a.istore(3);
		a.loadConstant(0);
		a.istore(4);
		MethodCode.Label listCase = a.newLabel();
		MethodCode.Label alloc = a.newLabel();
		a.aload(1);
		a.instanceOf(r.longClass());
		a.ifeq(listCase);
		a.loadConstant(1);
		a.istore(2);
		a.aload(1);
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(3);
		a.goto_(alloc);
		a.labelBinding(listCase);
		a.aload(1);
		a.astore(5);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label notFirst = a.newLabel();
		MethodCode.Label notSecond = a.newLabel();
		a.labelBinding(loop);
		a.aload(5);
		a.instanceOf(r.objectArrayClass());
		a.ifeq(done);
		a.iload(2);
		a.ifne(notFirst);
		a.aload(5);
		a.checkcast(r.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(3);
		a.goto_(notSecond);
		a.labelBinding(notFirst);
		a.iload(2);
		a.loadConstant(1);
		a.if_icmpne(notSecond);
		a.aload(5);
		a.checkcast(r.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(4);
		a.labelBinding(notSecond);
		a.iinc(2, 1);
		a.aload(5);
		a.checkcast(r.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.astore(5);
		a.goto_(loop);
		a.labelBinding(done);
		a.labelBinding(alloc);
		a.ldc(r.cp().stringEntry(op));
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
		MethodCode a = new MethodCode();
		a.ldc(r.cp().stringEntry(op));
		a.aload(1);
		a.invokestatic(r.qmCheckFormat());
		MethodCode.Label gathered = a.newLabel();
		for (JvmPackedFloatWidth w : new JvmPackedFloatWidth[] { JvmPackedFloatWidth.DOUBLE, JvmPackedFloatWidth.SINGLE,
				JvmPackedFloatWidth.BFLOAT16 }) {
			MethodCode.Label next = a.newLabel();
			ClassEntry arrayClass = r.cp().classEntry(w.descriptor());
			a.aload(0);
			a.instanceOf(arrayClass);
			a.ifeq(next);
			a.aload(0);
			a.checkcast(arrayClass);
			a.astore(10);
			a.aload(10);
			w.loadRank(a);
			a.istore(2);
			// rank outside 1..2: _qmAlloc reports it (dims are not read first)
			MethodCode.Label rankBad = a.newLabel();
			MethodCode.Label rankGood = a.newLabel();
			a.iload(2);
			a.loadConstant(1);
			a.if_icmplt(rankBad);
			a.iload(2);
			a.loadConstant(2);
			a.if_icmple(rankGood);
			a.labelBinding(rankBad);
			a.ldc(r.cp().stringEntry(op));
			a.iload(2);
			a.loadConstant(0);
			a.loadConstant(0);
			a.invokestatic(r.qmAlloc());
			a.pop();
			a.labelBinding(rankGood);
			a.aload(10);
			a.loadConstant(0);
			w.loadDim(a);
			a.istore(3);
			a.loadConstant(0);
			a.istore(4);
			a.iload(3);
			a.istore(5);
			MethodCode.Label rank1 = a.newLabel();
			a.iload(2);
			a.loadConstant(2);
			a.if_icmpne(rank1);
			a.aload(10);
			a.loadConstant(1);
			w.loadDim(a);
			a.istore(4);
			a.iload(3);
			a.iload(4);
			a.imul();
			a.istore(5);
			a.labelBinding(rank1);
			// tmp = new float[total]; tmp[i] = (float) elem(off + i)
			a.iload(2);
			w.emitDataOffset(a);
			a.istore(8);
			a.iload(5);
			a.newarray(TypeKind.FLOAT);
			a.astore(6);
			a.loadConstant(0);
			a.istore(7);
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			a.labelBinding(loop);
			a.iload(7);
			a.iload(5);
			a.if_icmpge(done);
			a.aload(6);
			a.iload(7);
			a.aload(10);
			a.iload(8);
			a.iload(7);
			a.iadd();
			w.loadElem(a, r.bf16Value());
			a.d2f();
			a.fastore();
			a.iinc(7, 1);
			a.goto_(loop);
			a.labelBinding(done);
			a.goto_(gathered);
			a.labelBinding(next);
		}
		throwMessage(a, r, op + ": expects a packed float array");
		a.labelBinding(gathered);
		a.ldc(r.cp().stringEntry(op));
		a.iload(2);
		a.iload(3);
		a.iload(4);
		a.invokestatic(r.qmAlloc());
		a.astore(9);
		a.aload(6);
		a.aload(9);
		a.loadConstant(8);
		a.iload(2);
		a.loadConstant(4);
		a.imul();
		a.iadd();
		a.invokestatic(r.qmQuantizeBlocks());
		a.aload(9);
		a.areturn();
		return method(r, QUANTIZE, BINARY_DESC, 8, 11, a);
	}

	// _qmDequantize(m, element-type): a fresh packed array of the width named, every
	// element q * scale. Locals: 0=m, 1=etype, 2=a, 3=rank, 4=total, 5=arr, 6=off, 7=i,
	// 8=k, 9=dim, 10=name.
	private static ArrayMethod buildDequantize(Refs r, boolean octets) {
		String op = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DEQUANTIZE);
		MethodCode a = new MethodCode();
		MethodCode.Label isMatrix = a.newLabel();
		MethodCode.Label notMatrix = a.newLabel();
		emitMatrixTest(a, r, 0, notMatrix, octets);
		a.goto_(isMatrix);
		a.labelBinding(notMatrix);
		throwMessage(a, r, op + ": expects a quantized matrix");
		a.labelBinding(isMatrix);
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
			MethodCode.Label next = a.newLabel();
			a.aload(10);
			a.ldc(r.cp().stringEntry(names[n]));
			a.invokevirtual(r.stringEquals());
			a.ifeq(next);
			// arr = new backing[off + total]; rank; dims
			a.iload(3);
			w.emitDataOffset(a);
			a.istore(6);
			a.iload(6);
			a.iload(4);
			a.iadd();
			w.newBacking(a);
			a.astore(5);
			a.aload(5);
			a.iload(3);
			w.storeRank(a);
			a.loadConstant(0);
			a.istore(8);
			MethodCode.Label dimLoop = a.newLabel();
			MethodCode.Label dimDone = a.newLabel();
			a.labelBinding(dimLoop);
			a.iload(8);
			a.iload(3);
			a.if_icmpge(dimDone);
			a.aload(2);
			a.iload(8);
			a.loadConstant(4);
			a.imul();
			a.loadConstant(8);
			a.iadd();
			a.invokestatic(r.qmInt());
			a.istore(9);
			w.storeDim(a, 5, 8, 9);
			a.iinc(8, 1);
			a.goto_(dimLoop);
			a.labelBinding(dimDone);
			// arr[off + i] = narrow(_qmValue(a, i))
			a.loadConstant(0);
			a.istore(7);
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			a.labelBinding(loop);
			a.iload(7);
			a.iload(4);
			a.if_icmpge(done);
			a.aload(5);
			a.iload(6);
			a.iload(7);
			a.iadd();
			a.aload(2);
			a.iload(7);
			a.invokestatic(r.qmValue());
			w.storeElem(a, r.bf16Bits());
			a.iinc(7, 1);
			a.goto_(loop);
			a.labelBinding(done);
			a.aload(5);
			a.areturn();
			a.labelBinding(next);
		}
		throwMessage(a, r, op + ": element-type must be single-float, double-float or bfloat16");
		return method(r, DEQUANTIZE, BINARY_DESC, 8, 11, a);
	}

	// _qmRows(m, rows): a fresh rank-2 matrix gathering the rows named by the cons list
	// of Longs, one array copy a row -- the source's own blocks. Locals: 0=m, 1=rows,
	// 2=a, 3=srcRows, 4=cols, 5=rowBytes, 6=srcOff, 7=count, 8=cur, 9=out, 10=i, 11=idx.
	private static ArrayMethod buildRows(Refs r, boolean octets) {
		String op = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_ROWS);
		MethodCode a = new MethodCode();
		MethodCode.Label isMatrix = a.newLabel();
		MethodCode.Label notMatrix = a.newLabel();
		emitMatrixTest(a, r, 0, notMatrix, octets);
		a.goto_(isMatrix);
		a.labelBinding(notMatrix);
		throwMessage(a, r, op + ": expects a quantized matrix");
		a.labelBinding(isMatrix);
		a.aload(0);
		a.checkcast(r.byteArrayClass());
		a.astore(2);
		emitRowsCols(a, r, 2, 3, 4);
		// rowBytes = cols / 32 * 34; srcOff = 8 + 4 * rank (the destination's own header
		// is 16 bytes, a gathered matrix always being rank 2)
		a.iload(4);
		a.loadConstant(BLOCK);
		a.idiv();
		a.loadConstant(BLOCK_BYTES);
		a.imul();
		a.istore(5);
		headerInt(a, r, 2, 4);
		a.loadConstant(4);
		a.imul();
		a.loadConstant(8);
		a.iadd();
		a.istore(6);
		// a cons list is Object[]{car, cdr} terminated by null; nothing else is one
		MethodCode.Label listOk = a.newLabel();
		a.aload(1);
		a.ifnull(listOk);
		a.aload(1);
		a.instanceOf(r.objectArrayClass());
		a.ifne(listOk);
		throwMessage(a, r, op + ": expects a list of row indexes");
		a.labelBinding(listOk);
		// count the indexes, then allocate the destination
		a.loadConstant(0);
		a.istore(7);
		a.aload(1);
		a.astore(8);
		MethodCode.Label countLoop = a.newLabel();
		MethodCode.Label countDone = a.newLabel();
		a.labelBinding(countLoop);
		a.aload(8);
		a.instanceOf(r.objectArrayClass());
		a.ifeq(countDone);
		a.iinc(7, 1);
		a.aload(8);
		a.checkcast(r.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.astore(8);
		a.goto_(countLoop);
		a.labelBinding(countDone);
		a.ldc(r.cp().stringEntry(op));
		a.loadConstant(2);
		a.iload(7);
		a.iload(4);
		a.invokestatic(r.qmAlloc());
		a.astore(9);
		// row idx of the source over row i of the destination
		a.loadConstant(0);
		a.istore(10);
		a.aload(1);
		a.astore(8);
		MethodCode.Label gatherLoop = a.newLabel();
		MethodCode.Label gatherDone = a.newLabel();
		MethodCode.Label bad = a.newLabel();
		MethodCode.Label ok = a.newLabel();
		a.labelBinding(gatherLoop);
		a.aload(8);
		a.instanceOf(r.objectArrayClass());
		a.ifeq(gatherDone);
		a.aload(8);
		a.checkcast(r.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.checkcast(r.longClass());
		a.invokevirtual(r.longIntValue());
		a.istore(11);
		a.iload(11);
		a.iflt(bad);
		a.iload(11);
		a.iload(3);
		a.if_icmplt(ok);
		a.labelBinding(bad);
		throwMessage(a, r, op + ": row index out of bounds");
		a.labelBinding(ok);
		a.aload(2);
		a.iload(6);
		a.iload(11);
		a.iload(5);
		a.imul();
		a.iadd();
		a.aload(9);
		a.loadConstant(16);
		a.iload(10);
		a.iload(5);
		a.imul();
		a.iadd();
		a.iload(5);
		a.invokestatic(r.systemArraycopy());
		a.iinc(10, 1);
		a.aload(8);
		a.checkcast(r.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.astore(8);
		a.goto_(gatherLoop);
		a.labelBinding(gatherDone);
		a.aload(9);
		a.areturn();
		return method(r, ROWS, BINARY_DESC, 8, 12, a);
	}

}
