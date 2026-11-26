package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * Builds the helpers {@code _equal} and {@code _hash} compare and fold a bit vector by
 * (CLHS 5.3: {@code equal} compares two bit vectors element by element with {@code eql},
 * up to a fill pointer). A bit vector is the rank-1 general array stamped {@code bit}
 * ({@code .kb/array-literals.md}, "The degraded array REMEMBERS its element type"), so
 * the test is {@code _arrayElementType}'s answer plus the rank. Emitted only in a program
 * that can build a bit vector -- the {@code bit} width of the element-type mask -- so
 * every other program keeps the {@code _equal} and {@code _hash} it had.
 */
final class JvmBitVectorRuntimeBuilder {

	/**
	 * {@code _bvLen(Object) -> int}: the active length of a bit vector (its fill pointer,
	 * else its dimension), or -1 for any other value.
	 */
	static final String LENGTH = "_bvLen";

	static final String LENGTH_DESC = "(Ljava/lang/Object;)I";

	/**
	 * {@code _bvEqual(Object a, Object b) -> int}: 1 when both are bit vectors of equal
	 * active elements, else 0 -- {@code _equal}'s answer for two arrays, which compare by
	 * identity otherwise.
	 */
	static final String EQUAL = "_bvEqual";

	static final String EQUAL_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)I";

	/**
	 * {@code _bvHash(Object v, int d, int[] gas) -> int}: {@code _hash}'s answer for an
	 * array -- a bit vector folds its active elements under the caller's depth and work
	 * budget, any other array hashes by identity.
	 */
	static final String HASH = "_bvHash";

	private JvmBitVectorRuntimeBuilder() {
	}

	/**
	 * Builds the helpers.
	 * @param cp the constant pool
	 * @param selfClass the generated program class
	 * @param hashes whether the program uses hash tables, so {@code _hash} exists and
	 * {@code _bvHash} is emitted beside it
	 * @return the helper methods
	 */
	static List<JvmArrayRuntimeBuilder.ArrayMethod> build(ConstantPool cp, ClassEntry selfClass, boolean hashes) {
		ClassEntry arrayListClass = cp.classEntry("java/util/ArrayList");
		ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		MethodRefEntry alSize = cp.methodRef(arrayListClass, "size", "()I");
		MethodRefEntry alGet = cp.methodRef(arrayListClass, "get", "(I)Ljava/lang/Object;");
		MethodRefEntry longIntValue = cp.methodRef(longClass, "intValue", "()I");
		MethodRefEntry stringEquals = cp.methodRef(stringClass, "equals", "(Ljava/lang/Object;)Z");
		MethodRefEntry elementType = cp.methodRef(selfClass, JvmArrayRuntimeBuilder.ELEMENT_TYPE,
				JvmArrayRuntimeBuilder.ELEMENT_TYPE_DESC);
		MethodRefEntry rmGet = cp.methodRef(selfClass, JvmArrayRuntimeBuilder.RM_GET,
				JvmArrayRuntimeBuilder.RM_GET_DESC);
		MethodRefEntry length = cp.methodRef(selfClass, LENGTH, LENGTH_DESC);
		List<JvmArrayRuntimeBuilder.ArrayMethod> methods = new ArrayList<>();

		// _bvLen(o): an ArrayList with an Object[] header, stamped bit (through a
		// displaced chain, as _arrayElementType reads it), of rank 1. Locals: 0 = o,
		// 1 = header.
		MethodCode len = new MethodCode();
		MethodCode.Label no = len.newLabel();
		MethodCode.Label noFillPointer = len.newLabel();
		len.aload(0);
		len.instanceOf(arrayListClass);
		len.ifeq(no);
		len.aload(0);
		len.checkcast(arrayListClass);
		len.invokevirtual(alSize);
		len.ifeq(no);
		len.aload(0);
		len.checkcast(arrayListClass);
		len.loadConstant(0);
		len.invokevirtual(alGet);
		len.instanceOf(objectArrayClass);
		len.ifeq(no);
		len.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.BIT));
		len.aload(0);
		len.invokestatic(elementType);
		len.invokevirtual(stringEquals);
		len.ifeq(no);
		len.aload(0);
		len.checkcast(arrayListClass);
		len.loadConstant(0);
		len.invokevirtual(alGet);
		len.checkcast(objectArrayClass);
		len.astore(1);
		len.aload(1);
		len.loadConstant(0);
		len.aaload();
		len.checkcast(objectArrayClass);
		len.arraylength();
		len.loadConstant(1);
		len.if_icmpne(no);
		len.aload(1);
		len.loadConstant(1);
		len.aaload();
		len.ifnull(noFillPointer);
		len.aload(1);
		len.loadConstant(1);
		len.aaload();
		len.checkcast(longClass);
		len.invokevirtual(longIntValue);
		len.ireturn();
		len.labelBinding(noFillPointer);
		len.aload(1);
		len.loadConstant(0);
		len.aaload();
		len.checkcast(objectArrayClass);
		len.loadConstant(0);
		len.aaload();
		len.checkcast(longClass);
		len.invokevirtual(longIntValue);
		len.ireturn();
		len.labelBinding(no);
		len.iconst_m1();
		len.ireturn();
		methods.add(new JvmArrayRuntimeBuilder.ArrayMethod(cp.utf8Entry(LENGTH), cp.utf8Entry(LENGTH_DESC), len));

		// _bvEqual(a, b): the lengths, then the elements pairwise through _eqv. Locals:
		// 0 = a, 1 = b, 2 = n, 3 = i.
		MethodRefEntry eqv = cp.methodRef(selfClass, JvmNumericRuntimeBuilder.EQV, EQUAL_DESC);
		MethodCode eq = new MethodCode();
		MethodCode.Label differ = eq.newLabel();
		MethodCode.Label same = eq.newLabel();
		eq.aload(0);
		eq.invokestatic(length);
		eq.dup();
		eq.istore(2);
		eq.iflt(differ);
		eq.aload(1);
		eq.invokestatic(length);
		eq.iload(2);
		eq.if_icmpne(differ);
		eq.loadConstant(0);
		eq.istore(3);
		MethodCode.Label top = eq.newBoundLabel();
		eq.iload(3);
		eq.iload(2);
		eq.if_icmpge(same);
		emitElement(eq, rmGet, 0, 3);
		emitElement(eq, rmGet, 1, 3);
		eq.invokestatic(eqv);
		eq.ifeq(differ);
		eq.iinc(3, 1);
		eq.goto_(top);
		eq.labelBinding(same);
		eq.loadConstant(1);
		eq.ireturn();
		eq.labelBinding(differ);
		eq.loadConstant(0);
		eq.ireturn();
		methods.add(new JvmArrayRuntimeBuilder.ArrayMethod(cp.utf8Entry(EQUAL), cp.utf8Entry(EQUAL_DESC), eq));

		if (hashes) {
			// _bvHash(v, d, gas): h = n, then h = 31 * h + _hash(element, d - 1, gas)
			// while gas lasts -- LispEquality.hash's bit-vector arm. Locals: 0 = v,
			// 1 = d, 2 = gas, 3 = n, 4 = h, 5 = i.
			MethodRefEntry hash = cp.methodRef(selfClass, JvmHashRuntimeBuilder.HASH, JvmHashRuntimeBuilder.HASH_DESC);
			MethodCode h = new MethodCode();
			MethodCode.Label bitVector = h.newLabel();
			MethodCode.Label done = h.newLabel();
			h.aload(0);
			h.invokestatic(length);
			h.dup();
			h.istore(3);
			h.ifge(bitVector);
			h.aload(0);
			h.invokestatic(
					cp.methodRef(cp.classEntry("java/lang/System"), "identityHashCode", "(Ljava/lang/Object;)I"));
			h.ireturn();
			h.labelBinding(bitVector);
			h.iload(3);
			h.istore(4);
			h.loadConstant(0);
			h.istore(5);
			MethodCode.Label hashTop = h.newBoundLabel();
			h.iload(5);
			h.iload(3);
			h.if_icmpge(done);
			h.aload(2);
			h.loadConstant(0);
			h.iaload();
			h.ifle(done);
			h.iload(4);
			h.loadConstant(31);
			h.imul();
			emitElement(h, rmGet, 0, 5);
			h.iload(1);
			h.loadConstant(1);
			h.isub();
			h.aload(2);
			h.invokestatic(hash);
			h.iadd();
			h.istore(4);
			h.iinc(5, 1);
			h.goto_(hashTop);
			h.labelBinding(done);
			h.iload(4);
			h.ireturn();
			methods.add(new JvmArrayRuntimeBuilder.ArrayMethod(cp.utf8Entry(HASH),
					cp.utf8Entry(JvmHashRuntimeBuilder.HASH_DESC), h));
		}
		return methods;
	}

	// Pushes element i (the int in indexSlot) of the vector in arraySlot: _rmGet takes
	// the 1-based list index and follows a displaced chain.
	private static void emitElement(MethodCode c, MethodRefEntry rmGet, int arraySlot, int indexSlot) {
		c.aload(arraySlot);
		c.iload(indexSlot);
		c.loadConstant(1);
		c.iadd();
		c.invokestatic(rmGet);
	}

}
