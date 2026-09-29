package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.compiler.OperandTypes;

/**
 * Builds the {@code _nthcdr} runtime helper behind the {@code nthcdr} built-in:
 *
 * <pre>{@code _nthcdr(int n, Object list) -> Object}</pre>
 *
 * <p>
 * Walks {@code cdr} {@code n} times, stopping early on {@code nil} because
 * {@code (nthcdr n lst)} past the end of the list is {@code nil} in Common Lisp. A
 * non-list met before the count runs out -- {@code 5} in {@code (nthcdr 1 5)} -- is
 * {@code NTHCDR}'s {@code LIST} type-error ({@link JvmOperandTypeRuntime}).
 *
 * <p>
 * The walk lives in this helper rather than inline at the call site for a reason that has
 * nothing to do with code size: HotSpot can only enter an on-stack-replacement
 * compilation at a backedge whose operand stack is EMPTY. Inline, the loop head sits
 * under whatever operands the enclosing expression has already pushed -- in
 * {@code (setq s (+ s (nth 999 lst)))} the pending {@code s} is one of them -- and every
 * tier then refuses the method with {@code COMPILE SKIPPED: stack not empty at OSR entry
 * point}. A top-level form or a {@code defun} called once is entered once, so OSR is the
 * only route into it and the walk runs in the bytecode interpreter forever. Here the loop
 * head is at stack depth 0 in a method called once per {@code nthcdr}, which the ordinary
 * invocation counters compile like anything else.
 */
final class JvmNthcdrRuntimeBuilder {

	/** An nthcdr runtime method body ready to be emitted into the generated class. */
	record NthcdrMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
	}

	static final String METHOD = "_nthcdr";

	static final String DESC = "(ILjava/lang/Object;)Ljava/lang/Object;";

	private JvmNthcdrRuntimeBuilder() {
	}

	static NthcdrMethod build(ConstantPool cp, JvmOperandTypeRuntime.ConsShape consShape, ClassEntry thisClass) {
		// Slots: 0 = n (int), 1 = the list cursor, 2 = it as a cons, 3 = its car.
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label notList = a.newLabel();

		a.labelBinding(loop);
		a.iload(0);
		a.ifle(done);
		a.aload(1);
		a.ifnull(done);
		consShape.emitTest(a, 1, 2, 3, notList);
		a.aload(2);
		a.loadConstant(1);
		a.aaload();
		a.astore(1);
		a.iinc(0, -1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(1);
		a.areturn();
		// throw _opTypeErr(_teRaw(list, "LIST"), "NTHCDR", FUNNEL_TYPE)
		a.labelBinding(notList);
		a.aload(1);
		a.ldc(cp.stringEntry(OperandTypes.Kind.LIST.name()));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, thisClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		a.ldc(cp.stringEntry(LispNames.NTHCDR));
		a.ldc(cp.stringEntry(OperandTypes.FUNNEL_TYPE));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, thisClass, JvmOperandTypeRuntime.OP_TYPE_ERR,
				JvmOperandTypeRuntime.OP_TYPE_ERR_DESC));
		a.athrow();

		return new NthcdrMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), a);
	}

}
