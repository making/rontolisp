package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.compiler.OperandTypes;

/**
 * Builds the {@code _eltCell} runtime helper behind {@code %elt-cell}, the list arm of
 * {@code elt} and of its {@code setf} place:
 *
 * <pre>{@code _eltCell(Object list, Object index) -> Object}</pre>
 *
 * <p>
 * Walks the list counting the cells it passes and answers the cell at {@code index}. An
 * index outside the list -- past its end, negative, a {@code BigInteger} -- is never met,
 * so the walk reaches nil having counted the list's length:
 * {@code throw _opTypeErr(_oob(index, length), "ELT", FUNNEL_TYPE)}, the report an
 * out-of-range {@code aref} subscript gives ({@link JvmOperandTypeRuntime}). A non-list
 * met on the way is {@code ELT}'s {@code LIST} type-error. The index is already an
 * integer ({@code _ckIdx} at the call site).
 *
 * <p>
 * A method rather than an inline loop for the reason {@link JvmNthcdrRuntimeBuilder}
 * gives: its loop head sits at operand stack depth 0, the only shape HotSpot will
 * OSR-compile.
 */
final class JvmEltCellRuntimeBuilder {

	/** An elt-cell runtime method body ready to be emitted into the generated class. */
	record EltCellMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
	}

	static final String METHOD = "_eltCell";

	static final String DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private JvmEltCellRuntimeBuilder() {
	}

	static EltCellMethod build(ConstantPool cp, JvmOperandTypeRuntime.ConsShape consShape, ClassEntry thisClass) {
		// Slots: 0 = the list cursor, 1 = the index, 2 = the target (the index as an
		// int, -1 when no cell can have it), 3 = the cells still to pass, 4-5 = the index
		// as a long, 6 = the cursor as a cons, 7 = its car.
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		MethodRefEntry longValue = cp.methodRef(longClass, "longValue", "()J");
		MethodCode a = new MethodCode();
		MethodCode.Label walk = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label found = a.newLabel();
		MethodCode.Label outOfRange = a.newLabel();
		MethodCode.Label notList = a.newLabel();

		// target = the index when a Long in [0, Integer.MAX_VALUE], else -1: a negative,
		// an int-overflowing or a BigInteger index matches no cell.
		a.loadConstant(-1);
		a.istore(2);
		a.aload(1);
		a.instanceOf(longClass);
		a.ifeq(walk);
		a.aload(1);
		a.checkcast(longClass);
		a.invokevirtual(longValue);
		a.lstore(4);
		a.lload(4);
		a.l2i();
		a.i2l();
		a.lload(4);
		a.lcmp();
		a.ifne(walk);
		a.lload(4);
		a.l2i();
		a.istore(2);
		a.iload(2);
		a.ifge(walk);
		a.loadConstant(-1);
		a.istore(2);
		a.labelBinding(walk);
		// Counting DOWN, as _nthcdr does: the cells passed are target - remaining, a
		// -1 target only ever moving further from zero.
		a.iload(2);
		a.istore(3);
		a.labelBinding(loop);
		a.aload(0);
		a.ifnull(outOfRange);
		a.iload(3);
		a.ifeq(found);
		consShape.emitTest(a, 0, 6, 7, notList);
		a.aload(6);
		a.loadConstant(1);
		a.aaload();
		a.astore(0);
		a.iinc(3, -1);
		a.goto_(loop);
		// The cell itself must be a cons: (elt '(1 . 2) 1) meets 2 there.
		a.labelBinding(found);
		consShape.emitTest(a, 0, 6, 7, notList);
		a.aload(0);
		a.areturn();
		// throw _opTypeErr(_oob(index, target - remaining), "ELT", FUNNEL_TYPE)
		a.labelBinding(outOfRange);
		a.aload(1);
		a.iload(2);
		a.iload(3);
		a.isub();
		a.invokestatic(
				JvmOperandTypeRuntime.self(cp, thisClass, JvmOperandTypeRuntime.OOB, JvmOperandTypeRuntime.OOB_DESC));
		emitNamedThrow(a, cp, thisClass);
		// throw _opTypeErr(_teRaw(cursor, "LIST"), "ELT", FUNNEL_TYPE)
		a.labelBinding(notList);
		a.aload(0);
		a.ldc(cp.stringEntry(OperandTypes.Kind.LIST.name()));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, thisClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		emitNamedThrow(a, cp, thisClass);

		return new EltCellMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), a);
	}

	private static void emitNamedThrow(MethodCode a, ConstantPool cp, ClassEntry thisClass) {
		a.ldc(cp.stringEntry(java.util.Objects.requireNonNull(OperandTypes.reportedOperator(LispNames.ELT_CELL))));
		a.ldc(cp.stringEntry(OperandTypes.FUNNEL_TYPE));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, thisClass, JvmOperandTypeRuntime.OP_TYPE_ERR,
				JvmOperandTypeRuntime.OP_TYPE_ERR_DESC));
		a.athrow();
	}

}
