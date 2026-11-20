package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ClassDefinition;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * One kind of {@code <clinit>} work that grows with the program -- a field set per
 * global, per instance layout, per bignum, per bound special -- written as a run of
 * steps, each leaving the operand stack as it found it. A run that fits one segment is
 * written into {@code <clinit>} itself, byte for byte as before; a longer one is cut
 * between steps into methods {@code <clinit>} calls in order, so no count of them takes
 * the initializer past the 65535 bytes a method holds (.kb/jvm-method-size-limits.md).
 * None of the fields these steps set is {@code final}, so a segment method may set it.
 */
final class JvmClinitSteps {

	/** The measured bytes a segment holds before the next step starts another. */
	static final int SEGMENT_BUDGET = 12000;

	private final String name;

	private final List<MethodCode> segments = new ArrayList<>(List.of(new MethodCode()));

	/**
	 * @param name the segment methods' name, numbered from {@code $0}
	 */
	JvmClinitSteps(String name) {
		this.name = name;
	}

	/**
	 * {@return the body the next step is written to} Called once before each step.
	 */
	MethodCode step() {
		MethodCode current = this.segments.getLast();
		if (current.size() < SEGMENT_BUDGET) {
			return current;
		}
		MethodCode next = new MethodCode();
		this.segments.add(next);
		return next;
	}

	/**
	 * Writes the steps into {@code clinit}: inline when they fit one segment, else a call
	 * of each segment, added to {@code definition} as a method of its own.
	 * @param clinit the {@code <clinit>} body being assembled
	 * @param definition the class
	 * @param cp the constant pool
	 * @param thisClass the class being emitted
	 * @return the bodies of the segment methods added, empty when written inline
	 */
	List<MethodCode> appendTo(MethodCode clinit, ClassDefinition.Builder definition, ConstantPool cp,
			ClassEntry thisClass) {
		if (this.segments.size() == 1) {
			clinit.append(this.segments.getFirst());
			return List.of();
		}
		Utf8Entry desc = cp.utf8Entry("()V");
		for (int i = 0; i < this.segments.size(); i++) {
			MethodCode segment = this.segments.get(i);
			segment.return_();
			Utf8Entry methodName = cp.utf8Entry(this.name + "$" + i);
			definition.addMethod(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, methodName, desc, segment);
			clinit.invokestatic(cp.methodRef(thisClass, methodName, desc));
		}
		return List.copyOf(this.segments);
	}

}
