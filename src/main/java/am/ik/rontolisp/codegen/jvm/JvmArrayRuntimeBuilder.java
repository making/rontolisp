package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the JVM bytecode for the array runtime helpers. An array is represented at
 * runtime as a {@code java.util.ArrayList}: slot 0 holds the column count (a
 * {@code Long}, the second dimension for a rank-2 array, otherwise {@code Long(0)}) and
 * slots {@code 1..} hold the row-major data. Only ranks 1 and 2 are supported, so a
 * rank-2 element {@code (i, j)} lives at list index {@code 1 + i * cols + j} and a rank-1
 * element {@code (i)} at {@code 1 + i}.
 *
 * <p>
 * The generated static helpers (gated on the program actually using arrays):
 * <ul>
 * <li>{@code _arrayMake(dims, init)} -&gt; a fresh ArrayList</li>
 * <li>{@code _aref1(arr, i)} / {@code _aref2(arr, i, j)} -&gt; the stored element</li>
 * <li>{@code _aset1(arr, i, val)} / {@code _aset2(arr, i, j, val)} -&gt; the value</li>
 * </ul>
 */
final class JvmArrayRuntimeBuilder {

	static final String MAKE = "_arrayMake";

	static final String MAKE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREF1 = "_aref1";

	static final String AREF1_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREF2 = "_aref2";

	static final String AREF2_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASET1 = "_aset1";

	static final String ASET1_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASET2 = "_aset2";

	static final String ASET2_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/** An array helper method body ready to be emitted into the generated class. */
	record ArrayMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	private JvmArrayRuntimeBuilder() {
	}

	static List<ArrayMethod> build(ConstantPool cp, ClassConstant objectClass, ClassConstant objectArrayClass) {
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		MethodrefConstant alInit = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant alAdd = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant alGet = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		MethodrefConstant alSet = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(ILjava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant longIntValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));

		List<ArrayMethod> methods = new ArrayList<>();

		// _arrayMake(dims, init):
		// list = new ArrayList(); determine cols (Long) and total; list.add(cols);
		// repeat total times: list.add(init); return list.
		JvmAsm m = new JvmAsm();
		int dims = 0, init = 1, list = 2, total = 3, cols = 4, idx = 5;
		m.anew(arrayListClass);
		m.dup();
		m.invokespecial(alInit);
		m.astore(list);
		// if (dims instanceof Long) ... else ...
		m.aload(dims);
		m.instanceOf(longClass);
		int notLong = m.label();
		m.branch(Opcode.IFEQ, notLong);
		// 1-D integer: cols = Long(0); total = ((Long) dims).intValue()
		m.op(Opcode.LCONST_0);
		m.invokestatic(longValueOf);
		m.astore(cols);
		m.aload(dims);
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.istore(total);
		int afterDims = m.label();
		m.branch(Opcode.GOTO, afterDims);
		// cons: d0 = (Long)((Object[]) dims)[0]; rest = ((Object[]) dims)[1]
		m.bind(notLong);
		m.aload(dims);
		m.checkcast(objectArrayClass);
		m.iconst(0);
		m.aaload();
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.istore(total); // total = d0 (rank-1 result; overwritten for rank 2)
		m.aload(dims);
		m.checkcast(objectArrayClass);
		m.iconst(1);
		m.aaload(); // rest
		m.dup();
		m.instanceOf(objectArrayClass);
		int oneDim = m.label();
		m.branch(Opcode.IFEQ, oneDim);
		// rank 2: d1 = (Long)((Object[]) rest)[0]; cols = d1; total = d0 * d1
		m.checkcast(objectArrayClass);
		m.iconst(0);
		m.aaload(); // d1 (Long)
		m.dup();
		m.astore(cols);
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.iload(total);
		m.op(Opcode.IMUL);
		m.istore(total);
		m.branch(Opcode.GOTO, afterDims);
		// rank-1 list: discard rest, cols = Long(0)
		m.bind(oneDim);
		m.pop();
		m.op(Opcode.LCONST_0);
		m.invokestatic(longValueOf);
		m.astore(cols);
		// afterDims: list.add(cols); fill init total times
		m.bind(afterDims);
		m.aload(list);
		m.aload(cols);
		m.invokevirtual(alAdd);
		m.pop();
		m.iconst(0);
		m.istore(idx);
		int loop = m.label();
		int end = m.label();
		m.bind(loop);
		m.iload(idx);
		m.iload(total);
		m.branch(Opcode.IF_ICMPGE, end);
		m.aload(list);
		m.aload(init);
		m.invokevirtual(alAdd);
		m.pop();
		m.iinc(idx, 1);
		m.branch(Opcode.GOTO, loop);
		m.bind(end);
		m.aload(list);
		m.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC), 4, 6, m.finish()));

		// _aref1(arr, i): return ((ArrayList) arr).get(1 + ((Long) i).intValue())
		JvmAsm a1 = new JvmAsm();
		a1.aload(0);
		a1.checkcast(arrayListClass);
		a1.iconst(1);
		a1.aload(1);
		a1.checkcast(longClass);
		a1.invokevirtual(longIntValue);
		a1.op(Opcode.IADD);
		a1.invokevirtual(alGet);
		a1.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(AREF1), cp.addUtf8(AREF1_DESC), 3, 2, a1.finish()));

		// _aref2(arr, i, j): cols = ((Long) list.get(0)).intValue();
		// return list.get(1 + i * cols + j)
		JvmAsm a2 = new JvmAsm();
		emitFlat2(a2, arrayListClass, longClass, alGet, longIntValue, 3);
		a2.istore(3);
		a2.aload(0);
		a2.checkcast(arrayListClass);
		a2.iload(3);
		a2.invokevirtual(alGet);
		a2.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(AREF2), cp.addUtf8(AREF2_DESC), 4, 4, a2.finish()));

		// _aset1(arr, i, val): ((ArrayList) arr).set(1 + i, val); return val
		JvmAsm s1 = new JvmAsm();
		s1.aload(0);
		s1.checkcast(arrayListClass);
		s1.iconst(1);
		s1.aload(1);
		s1.checkcast(longClass);
		s1.invokevirtual(longIntValue);
		s1.op(Opcode.IADD);
		s1.aload(2);
		s1.invokevirtual(alSet);
		s1.pop();
		s1.aload(2);
		s1.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ASET1), cp.addUtf8(ASET1_DESC), 4, 3, s1.finish()));

		// _aset2(arr, i, j, val): list.set(1 + i * cols + j, val); return val
		JvmAsm s2 = new JvmAsm();
		emitFlat2(s2, arrayListClass, longClass, alGet, longIntValue, 4);
		s2.istore(4);
		s2.aload(0);
		s2.checkcast(arrayListClass);
		s2.iload(4);
		s2.aload(3);
		s2.invokevirtual(alSet);
		s2.pop();
		s2.aload(3);
		s2.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ASET2), cp.addUtf8(ASET2_DESC), 4, 5, s2.finish()));

		return methods;
	}

	// Pushes the rank-2 flat index 1 + i*cols + j, where arr is in slot 0, i in slot 1,
	// j in slot 2, and cols is list.get(0).
	private static void emitFlat2(JvmAsm a, ClassConstant arrayListClass, ClassConstant longClass,
			MethodrefConstant get, MethodrefConstant intValue, int unusedTempSlot) {
		a.iconst(1);
		a.aload(1);
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		// cols = ((Long) list.get(0)).intValue()
		a.aload(0);
		a.checkcast(arrayListClass);
		a.iconst(0);
		a.invokevirtual(get);
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.aload(2);
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IADD);
	}

}
