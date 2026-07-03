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
 * runtime as a {@code java.util.ArrayList}: slot 0 holds the dimension sizes (an
 * {@code Object[]} of boxed {@code Long}s, length = rank) and slots {@code 1..} hold the
 * row-major data. Any rank {@code >= 1} is supported: the flat index is the Horner fold
 * over the subscripts, so a rank-2 element {@code (i, j)} lives at list index
 * {@code 1 + i * cols + j} and a rank-1 element {@code (i)} at {@code 1 + i}.
 *
 * <p>
 * The generated static helpers (gated on the program actually using arrays):
 * <ul>
 * <li>{@code _arrayMake(dims, init)} -&gt; a fresh ArrayList</li>
 * <li>{@code _aref1(arr, i)} / {@code _aref2(arr, i, j)} / {@code _arefN(arr, subs)}
 * -&gt; the stored element</li>
 * <li>{@code _aset1(arr, i, val)} / {@code _aset2(arr, i, j, val)} /
 * {@code _asetN(arr, subs, val)} -&gt; the value</li>
 * </ul>
 * The {@code N} variants take the subscripts packaged into an {@code Object[]} and are
 * used by rank-3+ call sites; ranks 1 and 2 keep their dedicated fast helpers.
 */
final class JvmArrayRuntimeBuilder {

	static final String MAKE = "_arrayMake";

	static final String MAKE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREF1 = "_aref1";

	static final String AREF1_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREF2 = "_aref2";

	static final String AREF2_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREFN = "_arefN";

	static final String AREFN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASET1 = "_aset1";

	static final String ASET1_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASET2 = "_aset2";

	static final String ASET2_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASETN = "_asetN";

	static final String ASETN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String DIMS = "_arrayDims";

	static final String DIMS_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TO_STRING = "_arrayToString";

	static final String TO_DISPLAY_STRING = "_arrayToDisplayString";

	static final String TO_STRING_DESC = "(Ljava/lang/Object;)Ljava/lang/String;";

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

		List<ArrayMethod> methods = new ArrayList<>();

		// _arrayMake(dims, init):
		// list = new ArrayList(); build the Object[] dimension header and the total
		// element count from dims (a Long for the rank-1 shorthand, otherwise a cons
		// list of Longs); list.add(dimsArr); repeat total times: list.add(init).
		JvmAsm m = new JvmAsm();
		int dims = 0, init = 1, list = 2, total = 3, dimsArr = 4, idx = 5, cur = 6, n = 7;
		m.anew(arrayListClass);
		m.dup();
		m.invokespecial(alInit);
		m.astore(list);
		m.aload(dims);
		m.instanceOf(longClass);
		int notLong = m.label();
		m.branch(Opcode.IFEQ, notLong);
		// 1-D integer shorthand: dimsArr = {dims}; total = ((Long) dims).intValue()
		m.iconst(1);
		m.anewarray(objectClass);
		m.dup();
		m.iconst(0);
		m.aload(dims);
		m.aastore();
		m.astore(dimsArr);
		m.aload(dims);
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.istore(total);
		int afterDims = m.label();
		m.branch(Opcode.GOTO, afterDims);
		// cons list of dimensions: first count the length (n), then copy the sizes
		// into dimsArr while multiplying total.
		m.bind(notLong);
		m.iconst(0);
		m.istore(n);
		m.aload(dims);
		m.astore(cur);
		int countLoop = m.label();
		int countDone = m.label();
		m.bind(countLoop);
		m.aload(cur);
		m.instanceOf(objectArrayClass);
		m.branch(Opcode.IFEQ, countDone);
		m.iinc(n, 1);
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.iconst(1);
		m.aaload();
		m.astore(cur);
		m.branch(Opcode.GOTO, countLoop);
		m.bind(countDone);
		m.iload(n);
		m.anewarray(objectClass);
		m.astore(dimsArr);
		m.iconst(1);
		m.istore(total);
		m.iconst(0);
		m.istore(idx);
		m.aload(dims);
		m.astore(cur);
		int fillLoop = m.label();
		m.bind(fillLoop);
		m.iload(idx);
		m.iload(n);
		m.branch(Opcode.IF_ICMPGE, afterDims);
		// dimsArr[idx] = car(cur)
		m.aload(dimsArr);
		m.iload(idx);
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.iconst(0);
		m.aaload();
		m.aastore();
		// total *= ((Long) dimsArr[idx]).intValue()
		m.iload(total);
		m.aload(dimsArr);
		m.iload(idx);
		m.aaload();
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.op(Opcode.IMUL);
		m.istore(total);
		// cur = cdr(cur)
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.iconst(1);
		m.aaload();
		m.astore(cur);
		m.iinc(idx, 1);
		m.branch(Opcode.GOTO, fillLoop);
		// afterDims: list.add(dimsArr); fill init total times
		m.bind(afterDims);
		m.aload(list);
		m.aload(dimsArr);
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
		methods.add(new ArrayMethod(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC), 5, 8, m.finish()));

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

		// _aref2(arr, i, j): cols = dims[1]; return list.get(1 + i * cols + j)
		JvmAsm a2 = new JvmAsm();
		emitFlat2(a2, arrayListClass, longClass, objectArrayClass, alGet, longIntValue);
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

		// _arrayDims(arr): the dimension sizes as a fresh cons list, built backwards
		// over the Object[] header (the sizes are already boxed Longs). A cons is an
		// Object[]{car, cdr} and nil is null, matching the compiled cons representation.
		JvmAsm d = new JvmAsm();
		int dArr = 0, dDims = 1, dResult = 2, dJ = 3;
		d.aload(dArr);
		d.checkcast(arrayListClass);
		d.iconst(0);
		d.invokevirtual(alGet);
		d.checkcast(objectArrayClass);
		d.astore(dDims);
		d.aconstNull();
		d.astore(dResult);
		d.aload(dDims);
		d.arraylength();
		d.iconst(1);
		d.op(Opcode.ISUB);
		d.istore(dJ);
		int dLoop = d.label();
		int dDone = d.label();
		d.bind(dLoop);
		d.iload(dJ);
		d.branch(Opcode.IFLT, dDone);
		// result = new Object[]{dims[j], result}
		d.iconst(2);
		d.anewarray(objectClass);
		d.dup();
		d.iconst(0);
		d.aload(dDims);
		d.iload(dJ);
		d.aaload();
		d.aastore();
		d.dup();
		d.iconst(1);
		d.aload(dResult);
		d.aastore();
		d.astore(dResult);
		d.iinc(dJ, -1);
		d.branch(Opcode.GOTO, dLoop);
		d.bind(dDone);
		d.aload(dResult);
		d.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(DIMS), cp.addUtf8(DIMS_DESC), 6, 4, d.finish()));

		// _aset2(arr, i, j, val): list.set(1 + i * cols + j, val); return val
		JvmAsm s2 = new JvmAsm();
		emitFlat2(s2, arrayListClass, longClass, objectArrayClass, alGet, longIntValue);
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

		// _arefN(arr, subs): return list.get(1 + flatIndex(arr, subs))
		JvmAsm an = new JvmAsm();
		emitFlatN(an, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 1, 2, 3, 4, 5);
		an.aload(0);
		an.checkcast(arrayListClass);
		an.iconst(1);
		an.iload(2);
		an.op(Opcode.IADD);
		an.invokevirtual(alGet);
		an.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(AREFN), cp.addUtf8(AREFN_DESC), 4, 6, an.finish()));

		// _asetN(arr, subs, val): list.set(1 + flatIndex(arr, subs), val); return val
		JvmAsm sn = new JvmAsm();
		emitFlatN(sn, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 1, 3, 4, 5, 6);
		sn.aload(0);
		sn.checkcast(arrayListClass);
		sn.iconst(1);
		sn.iload(3);
		sn.op(Opcode.IADD);
		sn.aload(2);
		sn.invokevirtual(alSet);
		sn.pop();
		sn.aload(2);
		sn.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ASETN), cp.addUtf8(ASETN_DESC), 4, 7, sn.finish()));

		return methods;
	}

	/**
	 * Builds the two array-printing helpers ({@code _arrayToString} for prin1 and
	 * {@code _arrayToDisplayString} for princ). Each renders a rank-1 array as
	 * {@code #(...)} and a rank-n array as {@code #nA((...) ...)}, calling back into the
	 * element formatter ({@code _lispToString} / {@code _lispToDisplayString}) for each
	 * element. They are gated alongside the other array helpers.
	 * @param cp the constant pool
	 * @param lispToString the prin1 element formatter ({@code _lispToString})
	 * @param lispToDisplayString the princ element formatter
	 * ({@code _lispToDisplayString})
	 * @return the two helper methods
	 */
	static List<ArrayMethod> buildToStringMethods(ConstantPool cp, MethodrefConstant lispToString,
			MethodrefConstant lispToDisplayString) {
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		ClassConstant sbClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant alGet = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		MethodrefConstant alSize = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		MethodrefConstant longIntValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant sbInit = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant sbAppend = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant stringValueOfInt = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(I)Ljava/lang/String;")));

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(new ArrayMethod(cp.addUtf8(TO_STRING), cp.addUtf8(TO_STRING_DESC), 5, 10,
				buildToString(cp, arrayListClass, longClass, objectArrayClass, alGet, alSize, longIntValue, sbInit,
						sbAppend, sbToString, stringValueOfInt, lispToString)));
		methods.add(new ArrayMethod(cp.addUtf8(TO_DISPLAY_STRING), cp.addUtf8(TO_STRING_DESC), 5, 10,
				buildToString(cp, arrayListClass, longClass, objectArrayClass, alGet, alSize, longIntValue, sbInit,
						sbAppend, sbToString, stringValueOfInt, lispToDisplayString)));
		return methods;
	}

	// Emits one array-printing helper implementing the readable #(...) / #nA(...)
	// syntax: a nested group paren opens where the flat index k is a multiple of that
	// dimension's stride (the product of the trailing dimension sizes) and closes where
	// k + 1 is. Locals: 0=arr, 1=list, 2=sb, 3=n (element count), 4=dims (Object[]),
	// 5=k, 6=j (dimension), 7=stride, 8=m (stride scratch), 9=rank.
	private static List<Integer> buildToString(ConstantPool cp, ClassConstant arrayListClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant alGet, MethodrefConstant alSize,
			MethodrefConstant longIntValue, MethodrefConstant sbInit, MethodrefConstant sbAppend,
			MethodrefConstant sbToString, MethodrefConstant stringValueOfInt, MethodrefConstant elementFormat) {
		int arr = 0, list = 1, sb = 2, n = 3, dimsArr = 4, k = 5, j = 6, stride = 7, m = 8, rank = 9;
		JvmAsm a = new JvmAsm();
		// list = (ArrayList) arr; n = list.size() - 1
		a.aload(arr);
		a.checkcast(arrayListClass);
		a.astore(list);
		a.aload(list);
		a.invokevirtual(alSize);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(n);
		// dims = (Object[]) list.get(0); rank = dims.length
		a.aload(list);
		a.iconst(0);
		a.invokevirtual(alGet);
		a.checkcast(objectArrayClass);
		a.astore(dimsArr);
		a.aload(dimsArr);
		a.arraylength();
		a.istore(rank);
		// sb = new StringBuilder("#"); rank 1 appends "(", rank n appends n then "A("
		a.anew(sbClass(cp));
		a.dup();
		a.ldcString(cp.addString("#"));
		a.invokespecial(sbInit);
		a.astore(sb);
		int rankN = a.label();
		int afterPrefix = a.label();
		a.iload(rank);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPNE, rankN);
		appendStr(a, sb, sbAppend, cp.addString("("));
		a.branch(Opcode.GOTO, afterPrefix);
		a.bind(rankN);
		a.aload(sb);
		a.iload(rank);
		a.invokestatic(stringValueOfInt);
		a.invokevirtual(sbAppend);
		a.pop();
		appendStr(a, sb, sbAppend, cp.addString("A("));
		a.bind(afterPrefix);
		// k = 0
		a.iconst(0);
		a.istore(k);
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.iload(k);
		a.iload(n);
		a.branch(Opcode.IF_ICMPGE, end);
		// if (k != 0) sb.append(" ")
		int noSpace = a.label();
		a.iload(k);
		a.branch(Opcode.IFEQ, noSpace);
		appendStr(a, sb, sbAppend, cp.addString(" "));
		a.bind(noSpace);
		// opens: for j in 1..rank-1 (outermost first): if (k % stride(j) == 0) "("
		a.iconst(1);
		a.istore(j);
		int openLoop = a.label();
		int openDone = a.label();
		a.bind(openLoop);
		a.iload(j);
		a.iload(rank);
		a.branch(Opcode.IF_ICMPGE, openDone);
		emitStride(a, longClass, longIntValue, dimsArr, j, stride, m, rank);
		int noOpen = a.label();
		a.iload(k);
		a.iload(stride);
		a.op(Opcode.IREM);
		a.branch(Opcode.IFNE, noOpen);
		appendStr(a, sb, sbAppend, cp.addString("("));
		a.bind(noOpen);
		a.iinc(j, 1);
		a.branch(Opcode.GOTO, openLoop);
		a.bind(openDone);
		// sb.append(elementFormat(list.get(k + 1)))
		a.aload(sb);
		a.aload(list);
		a.iload(k);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(alGet);
		a.invokestatic(elementFormat);
		a.invokevirtual(sbAppend);
		a.pop();
		// closes: for j in rank-1..1 (innermost first): if ((k+1) % stride(j) == 0) ")"
		a.iload(rank);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(j);
		int closeLoop = a.label();
		int closeDone = a.label();
		a.bind(closeLoop);
		a.iload(j);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPLT, closeDone);
		emitStride(a, longClass, longIntValue, dimsArr, j, stride, m, rank);
		int noClose = a.label();
		a.iload(k);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.iload(stride);
		a.op(Opcode.IREM);
		a.branch(Opcode.IFNE, noClose);
		appendStr(a, sb, sbAppend, cp.addString(")"));
		a.bind(noClose);
		a.iinc(j, -1);
		a.branch(Opcode.GOTO, closeLoop);
		a.bind(closeDone);
		// k++; loop
		a.iinc(k, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(end);
		// sb.append(")"); return sb.toString()
		appendStr(a, sb, sbAppend, cp.addString(")"));
		a.aload(sb);
		a.invokevirtual(sbToString);
		a.areturn();
		return a.finish();
	}

	private static ClassConstant sbClass(ConstantPool cp) {
		return cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
	}

	// stride = product of ((Long) dims[m]).intValue() for m in j..rank-1.
	private static void emitStride(JvmAsm a, ClassConstant longClass, MethodrefConstant longIntValue, int dimsArrSlot,
			int jSlot, int strideSlot, int mSlot, int rankSlot) {
		a.iconst(1);
		a.istore(strideSlot);
		a.iload(jSlot);
		a.istore(mSlot);
		int strideLoop = a.label();
		int strideDone = a.label();
		a.bind(strideLoop);
		a.iload(mSlot);
		a.iload(rankSlot);
		a.branch(Opcode.IF_ICMPGE, strideDone);
		a.iload(strideSlot);
		a.aload(dimsArrSlot);
		a.iload(mSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IMUL);
		a.istore(strideSlot);
		a.iinc(mSlot, 1);
		a.branch(Opcode.GOTO, strideLoop);
		a.bind(strideDone);
	}

	// sb.append(str); discard the returned StringBuilder.
	private static void appendStr(JvmAsm a, int sbSlot, MethodrefConstant sbAppend,
			am.ik.jvm.ConstantPool.StringConstant str) {
		a.aload(sbSlot);
		a.ldcString(str);
		a.invokevirtual(sbAppend);
		a.pop();
	}

	// Pushes the rank-2 flat index 1 + i*cols + j, where arr is in slot 0, i in slot 1,
	// j in slot 2, and cols is the second element of the Object[] dimension header.
	private static void emitFlat2(JvmAsm a, ClassConstant arrayListClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant get, MethodrefConstant intValue) {
		a.iconst(1);
		a.aload(1);
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		// cols = ((Long) ((Object[]) list.get(0))[1]).intValue()
		a.aload(0);
		a.checkcast(arrayListClass);
		a.iconst(0);
		a.invokevirtual(get);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.aload(2);
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IADD);
	}

	// Computes the Horner flat index over an Object[] of Long subscripts (in the slot
	// subs) against the array in slot 0, leaving it in the int slot flat:
	// flat = subs[0]; for k in 1..: flat = flat * dims[k] + subs[k].
	private static void emitFlatN(JvmAsm a, ClassConstant arrayListClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant get, MethodrefConstant intValue, int subs, int flat,
			int kSlot, int nSlot, int dimsSlot) {
		// dims = (Object[]) ((ArrayList) arr).get(0)
		a.aload(0);
		a.checkcast(arrayListClass);
		a.iconst(0);
		a.invokevirtual(get);
		a.checkcast(objectArrayClass);
		a.astore(dimsSlot);
		// subs = (Object[]) subs (re-store the checked cast); n = subs.length
		a.aload(subs);
		a.checkcast(objectArrayClass);
		a.astore(subs);
		a.aload(subs);
		a.arraylength();
		a.istore(nSlot);
		// flat = ((Long) subs[0]).intValue()
		a.aload(subs);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.istore(flat);
		// for k in 1..n-1: flat = flat * dims[k] + subs[k]
		a.iconst(1);
		a.istore(kSlot);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(kSlot);
		a.iload(nSlot);
		a.branch(Opcode.IF_ICMPGE, done);
		a.iload(flat);
		a.aload(dimsSlot);
		a.iload(kSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IMUL);
		a.aload(subs);
		a.iload(kSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IADD);
		a.istore(flat);
		a.iinc(kSlot, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
	}

}
