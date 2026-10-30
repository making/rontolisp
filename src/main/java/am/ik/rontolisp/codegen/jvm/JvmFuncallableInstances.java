package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispLayout;

/**
 * Funcallable instances on the JVM (a class descending from
 * {@code closer-mop:funcallable-standard-object}, {@link LispLayout#funcallable()}): an
 * instance {@code Object[]{String[] layout, cells...}} whose layout tag is one of the
 * funcallable classes', carrying the function it calls in its
 * {@link LispLayout#FUNCALLABLE_CELL} ({@code arraylength - 2}). The dispatchers and
 * {@code _apply} replace such an instance by that function and classify the callee again;
 * {@code functionp} answers t for one. The tags are compared as TEXT, like
 * {@code %obj-is}: an instance may be built by any construction path. Emitted only for a
 * program defining a funcallable class ({@code ClosRegistry.funcallableTags}), so every
 * other class is byte-identical.
 */
final class JvmFuncallableInstances {

	private JvmFuncallableInstances() {
	}

	/**
	 * Over the {@code Object[]} in {@code arraySlot} whose element 0 is no funcId: when
	 * it is a funcallable instance with a function installed, stores the function into
	 * {@code valueSlot} and jumps to {@code reclassify}; anything else falls through with
	 * both slots unchanged. Clobbers {@code scratchSlot}.
	 * @param code the method body
	 * @param cp the constant pool
	 * @param objectArrayClass the {@code Object[]} class constant
	 * @param objectEquals {@code Object.equals}
	 * @param tags the funcallable layout tags
	 * @param arraySlot the local holding the callee as an {@code Object[]}
	 * @param valueSlot the local the classification reads the callee from
	 * @param scratchSlot a reference local free at this point
	 * @param reclassify where the classification starts
	 */
	static void emitUnwrap(MethodCode code, ConstantPool cp, ClassEntry objectArrayClass, MethodRefEntry objectEquals,
			List<String> tags, int arraySlot, int valueSlot, int scratchSlot, MethodCode.Label reclassify) {
		MethodCode.Label notFunctional = code.newLabel();
		emitTagTest(code, cp, objectArrayClass, objectEquals, tags, arraySlot, scratchSlot, notFunctional);
		code.aload(arraySlot);
		code.checkcast(objectArrayClass);
		code.dup();
		code.arraylength();
		code.loadConstant(-LispLayout.FUNCALLABLE_CELL);
		code.isub();
		code.aaload();
		code.astore(scratchSlot);
		code.aload(scratchSlot);
		code.ifnull(notFunctional);
		code.aload(scratchSlot);
		code.astore(valueSlot);
		code.goto_(reclassify);
		code.labelBinding(notFunctional);
	}

	/**
	 * Over the NON-EMPTY {@code Object[]} in {@code arraySlot}: falls through when it is
	 * an instance of a funcallable layout, and jumps to {@code notFuncallable} otherwise.
	 * Clobbers {@code scratchSlot}.
	 * @param code the method body
	 * @param cp the constant pool
	 * @param objectArrayClass the {@code Object[]} class constant
	 * @param objectEquals {@code Object.equals}
	 * @param tags the funcallable layout tags
	 * @param arraySlot the local holding the value as an {@code Object[]}
	 * @param scratchSlot a reference local free at this point
	 * @param notFuncallable where a value that is none goes
	 */
	static void emitTagTest(MethodCode code, ConstantPool cp, ClassEntry objectArrayClass, MethodRefEntry objectEquals,
			List<String> tags, int arraySlot, int scratchSlot, MethodCode.Label notFuncallable) {
		ClassEntry stringArrayClass = cp.classEntry("[Ljava/lang/String;");
		code.aload(arraySlot);
		code.checkcast(objectArrayClass);
		code.iconst_0();
		code.aaload();
		code.astore(scratchSlot);
		code.aload(scratchSlot);
		code.instanceOf(stringArrayClass);
		code.ifeq(notFuncallable);
		code.aload(scratchSlot);
		code.checkcast(stringArrayClass);
		code.iconst_0();
		code.aaload();
		code.astore(scratchSlot);
		MethodCode.Label funcallable = code.newLabel();
		for (String tag : tags) {
			code.aload(scratchSlot);
			code.ldc(cp.stringEntry(tag));
			code.invokevirtual(objectEquals);
			code.ifne(funcallable);
		}
		code.goto_(notFuncallable);
		code.labelBinding(funcallable);
	}

}
