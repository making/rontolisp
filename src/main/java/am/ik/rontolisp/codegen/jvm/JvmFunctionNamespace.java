package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.ClosRegistry;

/**
 * The writes of the run-time function namespace {@code _fenv}, an alist of
 * {@code Object[]{name, value}} bindings a tombstone's nil value retires, and the cells
 * that make a literal reference to a name constant-time: a static field per name a
 * reference can read ({@code Ctx.namespaceCells}), null while {@code _fenv} has no
 * binding of the name and the binding once it has ({@code .kb/symbol-runtime-api.md}, "A
 * write through a computed name"). Every binding is made in {@code _fenvSet}, which
 * publishes it to the name's cell, so a later write changes what the cell's binding holds
 * and no write has to find the cell again.
 *
 * <p>
 * The alist holds only the names the program wrote, however many functions a write could
 * have reached, so a computed name's walk stays as short as it was before there were
 * cells.
 */
final class JvmFunctionNamespace {

	/**
	 * {@code _fenvSet(name, value)}: the name's binding holds the value, made if the name
	 * had none. A nil value is {@code fmakunbound}'s tombstone.
	 */
	static final String SET_NAME = "_fenvSet";

	/**
	 * {@code _fcPublish(name, binding)}: the cell of the name, if it has one, holds the
	 * binding. A chain of name compares over segments, run only when a binding is made or
	 * dropped.
	 */
	static final String PUBLISH_NAME = "_fcPublish";

	/**
	 * {@code _fcRestore(name)}: the name's binding is gone, and its compiled definition
	 * answers again.
	 */
	static final String RESTORE_NAME = "_fcRestore";

	/**
	 * {@code _fcValue(binding)}: the function a binding holds, or the undefined-function
	 * of its name for a tombstone.
	 */
	static final String VALUE_NAME = "_fcValue";

	/** The descriptor of {@code _fenvSet} and of {@code _fcPublish}'s segments. */
	static final String SET_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

	/** The descriptor of {@code _fcRestore}. */
	static final String RESTORE_DESC = "(Ljava/lang/Object;)V";

	/** The descriptor of {@code _fcValue}. */
	static final String VALUE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/** Segment budget in code bytes, as for {@code _lookup}'s chain of name compares. */
	private static final int PUBLISH_SEGMENT_BUDGET = 6_000;

	private JvmFunctionNamespace() {
	}

	/**
	 * The reference to {@code _fenvSet}.
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @return the method reference
	 */
	static MethodRefEntry setRef(ConstantPool cp, ClassEntry thisClass) {
		return cp.methodRef(thisClass, SET_NAME, SET_DESC);
	}

	/**
	 * The reference to {@code _fcRestore}.
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @return the method reference
	 */
	static MethodRefEntry restoreRef(ConstantPool cp, ClassEntry thisClass) {
		return cp.methodRef(thisClass, RESTORE_NAME, RESTORE_DESC);
	}

	/**
	 * The reference to {@code _fcValue}.
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @return the method reference
	 */
	static MethodRefEntry bindingValueRef(ConstantPool cp, ClassEntry thisClass) {
		return cp.methodRef(thisClass, VALUE_NAME, VALUE_DESC);
	}

	/**
	 * The name of a segment of {@code _fcPublish}.
	 * @param segment the segment's index, from 0
	 * @return the method's name
	 */
	static String publishSegmentName(int segment) {
		return segment == 0 ? PUBLISH_NAME : PUBLISH_NAME + "$" + segment;
	}

	/**
	 * The body of {@code _fenvSet}.
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param fenv the {@code _fenv} field
	 * @param envLookup {@code _envLookup}
	 * @return the body
	 */
	static MethodCode setBody(ConstantPool cp, ClassEntry thisClass, FieldRefEntry fenv, MethodRefEntry envLookup) {
		final int NAME = 0, VALUE = 1, BINDING = 2;
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		ClassEntry objectArray = cp.classEntry("[Ljava/lang/Object;");
		MethodCode a = new MethodCode();
		a.aload(NAME);
		a.getstatic(fenv);
		a.invokestatic(envLookup);
		a.astore(BINDING);
		MethodCode.Label create = a.newLabel();
		a.aload(BINDING);
		a.ifnull(create);
		a.aload(BINDING);
		a.checkcast(objectArray);
		a.iconst_1();
		a.aload(VALUE);
		a.aastore();
		a.return_();
		a.labelBinding(create);
		a.iconst_2();
		a.anewarray(objectClass);
		a.dup();
		a.iconst_0();
		a.aload(NAME);
		a.aastore();
		a.dup();
		a.iconst_1();
		a.aload(VALUE);
		a.aastore();
		a.astore(BINDING);
		a.iconst_2();
		a.anewarray(objectClass);
		a.dup();
		a.iconst_0();
		a.aload(BINDING);
		a.aastore();
		a.dup();
		a.iconst_1();
		a.getstatic(fenv);
		a.aastore();
		a.putstatic(fenv);
		a.aload(NAME);
		a.aload(BINDING);
		a.invokestatic(cp.methodRef(thisClass, PUBLISH_NAME, SET_DESC));
		a.return_();
		return a;
	}

	/**
	 * The segments of {@code _fcPublish}, chained {@code _fcPublish},
	 * {@code _fcPublish$1}, ... so the compares, linear in the cells, never grow one
	 * method past what the JIT compiles.
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param rows each spelling a run-time name can carry, with the cell it writes
	 * @return the bodies, the first being {@code _fcPublish}'s
	 */
	static List<MethodCode> publishSegments(ConstantPool cp, ClassEntry thisClass, Map<String, FieldRefEntry> rows) {
		MethodRefEntry equals = cp.methodRef(cp.classEntry("java/lang/Object"), "equals", "(Ljava/lang/Object;)Z");
		List<Map.Entry<String, FieldRefEntry>> entries = new ArrayList<>(rows.entrySet());
		List<MethodCode> segments = new ArrayList<>();
		int index = 0;
		do {
			MethodCode a = new MethodCode();
			while (index < entries.size() && a.size() < PUBLISH_SEGMENT_BUDGET) {
				Map.Entry<String, FieldRefEntry> row = entries.get(index++);
				MethodCode.Label next = a.newLabel();
				a.ldc(cp.stringEntry(row.getKey()));
				a.aload(0);
				a.invokevirtual(equals);
				a.ifeq(next);
				a.aload(1);
				a.putstatic(row.getValue());
				a.return_();
				a.labelBinding(next);
			}
			if (index < entries.size()) {
				a.aload(0);
				a.aload(1);
				a.invokestatic(cp.methodRef(thisClass, publishSegmentName(segments.size() + 1), SET_DESC));
			}
			a.return_();
			segments.add(a);
		}
		while (index < entries.size());
		return segments;
	}

	/**
	 * The body of {@code _fcRestore}: the binding out of the alist, and out of the name's
	 * cell.
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param fenv the {@code _fenv} field
	 * @return the body
	 */
	static MethodCode restoreBody(ConstantPool cp, ClassEntry thisClass, FieldRefEntry fenv) {
		final int NAME = 0, PREVIOUS = 1, NODE = 2;
		ClassEntry objectArray = cp.classEntry("[Ljava/lang/Object;");
		MethodRefEntry equals = cp.methodRef(cp.classEntry("java/lang/Object"), "equals", "(Ljava/lang/Object;)Z");
		MethodCode a = new MethodCode();
		a.aconst_null();
		a.astore(PREVIOUS);
		a.getstatic(fenv);
		a.astore(NODE);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label end = a.newLabel();
		MethodCode.Label advance = a.newLabel();
		MethodCode.Label headOfList = a.newLabel();
		a.labelBinding(loop);
		a.aload(NODE);
		a.ifnull(end);
		// node[0] is the binding, whose [0] is the name
		a.aload(NAME);
		a.aload(NODE);
		a.checkcast(objectArray);
		a.iconst_0();
		a.aaload();
		a.checkcast(objectArray);
		a.iconst_0();
		a.aaload();
		a.invokevirtual(equals);
		a.ifeq(advance);
		a.aload(PREVIOUS);
		a.ifnull(headOfList);
		a.aload(PREVIOUS);
		a.checkcast(objectArray);
		a.iconst_1();
		a.aload(NODE);
		a.checkcast(objectArray);
		a.iconst_1();
		a.aaload();
		a.aastore();
		a.goto_(end);
		a.labelBinding(headOfList);
		a.aload(NODE);
		a.checkcast(objectArray);
		a.iconst_1();
		a.aaload();
		a.putstatic(fenv);
		a.goto_(end);
		a.labelBinding(advance);
		a.aload(NODE);
		a.astore(PREVIOUS);
		a.aload(NODE);
		a.checkcast(objectArray);
		a.iconst_1();
		a.aaload();
		a.astore(NODE);
		a.goto_(loop);
		a.labelBinding(end);
		a.aload(NAME);
		a.aconst_null();
		a.invokestatic(cp.methodRef(thisClass, PUBLISH_NAME, SET_DESC));
		a.return_();
		return a;
	}

	/**
	 * The body of {@code _fcValue}: the binding's value, or -- for the tombstone -- the
	 * undefined-function of its name: {@code _apply}'s probe of the symbol reports it,
	 * and a {@code (setf place)} function's writer is reported as the list the program
	 * wrote.
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @return the body
	 */
	static MethodCode valueBody(ConstantPool cp, ClassEntry thisClass) {
		final int BINDING = 0, NAME = 1;
		ClassEntry objectArray = cp.classEntry("[Ljava/lang/Object;");
		ClassEntry string = cp.classEntry("java/lang/String");
		ClassEntry runtimeEx = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry startsWith = cp.methodRef(string, "startsWith", "(Ljava/lang/String;)Z");
		MethodRefEntry substring = cp.methodRef(string, "substring", "(I)Ljava/lang/String;");
		MethodRefEntry concat = cp.methodRef(string, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodCode a = new MethodCode();
		MethodCode.Label retired = a.newLabel();
		MethodCode.Label symbol = a.newLabel();
		a.aload(BINDING);
		a.checkcast(objectArray);
		a.iconst_1();
		a.aaload();
		a.dup();
		a.ifnull(retired);
		a.areturn();
		a.labelBinding(retired);
		a.pop();
		a.aload(BINDING);
		a.checkcast(objectArray);
		a.iconst_0();
		a.aaload();
		a.astore(NAME);
		a.aload(NAME);
		a.checkcast(string);
		a.ldc(cp.stringEntry(ClosRegistry.SETF_FUNCTION_PREFIX));
		a.invokevirtual(startsWith);
		a.ifeq(symbol);
		a.new_(runtimeEx);
		a.dup();
		a.ldc(cp.stringEntry(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + ClosRegistry.SETF_FUNCTION_NAME_OPEN));
		a.aload(NAME);
		a.checkcast(string);
		a.loadConstant(cp, ClosRegistry.SETF_FUNCTION_PREFIX.length());
		a.invokevirtual(substring);
		a.invokevirtual(concat);
		a.ldc(cp.stringEntry(")" + ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX));
		a.invokevirtual(concat);
		a.invokespecial(cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V"));
		a.athrow();
		a.labelBinding(symbol);
		a.aload(NAME);
		a.aconst_null();
		a.invokestatic(cp.methodRef(thisClass, "_apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
		a.areturn();
		return a;
	}

}
