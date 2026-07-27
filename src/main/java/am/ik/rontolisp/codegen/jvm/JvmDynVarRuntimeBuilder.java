package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the thread-scoped dynamic-binding runtime for special variables that are
 * dynamically bound somewhere in the program ({@code SpecialVarCollector.
 * collectDynamicallyBound}). Emitted only when that set is non-empty, so a program that
 * never {@code let}-binds a special compiles byte-identically to a build without this
 * runtime.
 *
 * <p>
 * Each bound special gets, next to its {@code _g$<name>} global static field (the global
 * default), a {@code private static ThreadLocal _d$<name>} holding the thread's innermost
 * dynamic binding as a one-element {@code Object[]} cell -- a cell rather than the value
 * because {@code nil} compiles to Java {@code null}, so the value itself cannot double as
 * the "no binding on this thread" marker. Three tiny shared helpers keep the call sites
 * small:
 *
 * <ul>
 * <li>{@code _dget(tl, global)} -- the dynamic-first read: the cell's value when this
 * thread has a binding, else the global default passed in.
 * <li>{@code _dbind(tl, v)} -- push a binding: replaces the thread's cell with a fresh
 * one holding {@code v} and returns the previous cell (possibly {@code null}), which the
 * binding site saves in a local and restores with a plain {@code ThreadLocal.set} on
 * every exit path.
 * <li>{@code _dset(tl, v)} -- the conditional {@code setq} write: stores into the
 * thread's cell and answers 1 when a binding is active, else answers 0 and the call site
 * falls through to the global {@code putstatic}.
 * </ul>
 *
 * The ThreadLocals are created in {@code <clinit>} (never lazily: a racy first binding
 * from two request threads would mint two ThreadLocals and lose one of the bindings).
 */
final class JvmDynVarRuntimeBuilder {

	private JvmDynVarRuntimeBuilder() {
	}

	/**
	 * The constants and method bodies of the thread-scoped dynamic-binding runtime.
	 *
	 * @param fields bound special name to its {@code _d$<name>} ThreadLocal field
	 * @param fieldNameUtfs the field name constants, in {@code fields} order
	 * @param fieldDescUtf the shared {@code Ljava/lang/ThreadLocal;} descriptor
	 * @param tlSet {@code ThreadLocal.set(Object)}, used directly by restore sites
	 * @param dget the {@code _dget} helper ref
	 * @param dbind the {@code _dbind} helper ref
	 * @param dset the {@code _dset} helper ref
	 * @param methods the three helper method bodies to register
	 * @param clinitCode the {@code <clinit>} fragment creating every ThreadLocal
	 */
	record DynVarRuntime(Map<String, FieldrefConstant> fields, List<Utf8Constant> fieldNameUtfs,
			Utf8Constant fieldDescUtf, MethodrefConstant tlSet, MethodrefConstant dget, MethodrefConstant dbind,
			MethodrefConstant dset, List<HelperMethod> methods, List<Integer> clinitCode, Utf8Constant clinitName,
			Utf8Constant clinitDesc) {
	}

	/** One helper method body: name/descriptor constants, code, and its stack shape. */
	record HelperMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, List<Integer> code, int maxStack, int maxLocals) {
	}

	static DynVarRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectArrayClass,
			Set<String> boundSpecials) {
		ClassConstant threadLocalClass = cp.addClass(cp.addUtf8("java/lang/ThreadLocal"));
		MethodrefConstant tlCtor = cp.addMethodref(threadLocalClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant tlGet = cp.addMethodref(threadLocalClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant tlSet = cp.addMethodref(threadLocalClass,
				cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(Ljava/lang/Object;)V")));
		Utf8Constant fieldDescUtf = cp.addUtf8("Ljava/lang/ThreadLocal;");
		Map<String, FieldrefConstant> fields = new LinkedHashMap<>();
		List<Utf8Constant> fieldNameUtfs = new ArrayList<>();
		List<Integer> clinitCode = new ArrayList<>();
		for (String name : boundSpecials) {
			Utf8Constant nameUtf = cp.addUtf8("_d$" + JvmLispCompiler.mangleMethodName(name));
			fieldNameUtfs.add(nameUtf);
			FieldrefConstant field = cp.addFieldref(thisClass, cp.addNameAndType(nameUtf, fieldDescUtf));
			fields.put(name, field);
			clinitCode.add(Opcode.NEW);
			JvmRuntimeBuilder.emitU2(clinitCode, threadLocalClass.index());
			clinitCode.add(Opcode.DUP);
			clinitCode.add(Opcode.INVOKESPECIAL);
			JvmRuntimeBuilder.emitU2(clinitCode, tlCtor.index());
			clinitCode.add(Opcode.PUTSTATIC);
			JvmRuntimeBuilder.emitU2(clinitCode, field.index());
		}
		String refDesc = "(Ljava/lang/ThreadLocal;Ljava/lang/Object;)Ljava/lang/Object;";
		Utf8Constant dgetName = cp.addUtf8("_dget");
		Utf8Constant dbindName = cp.addUtf8("_dbind");
		Utf8Constant dsetName = cp.addUtf8("_dset");
		Utf8Constant refDescUtf = cp.addUtf8(refDesc);
		Utf8Constant boolDescUtf = cp.addUtf8("(Ljava/lang/ThreadLocal;Ljava/lang/Object;)Z");
		MethodrefConstant dget = cp.addMethodref(thisClass, cp.addNameAndType(dgetName, refDescUtf));
		MethodrefConstant dbind = cp.addMethodref(thisClass, cp.addNameAndType(dbindName, refDescUtf));
		MethodrefConstant dset = cp.addMethodref(thisClass, cp.addNameAndType(dsetName, boolDescUtf));
		List<HelperMethod> methods = List.of(
				new HelperMethod(dgetName, refDescUtf, dgetCode(tlGet, objectArrayClass), 2, 2),
				new HelperMethod(dbindName, refDescUtf, dbindCode(tlGet, tlSet, cp), 5, 2),
				new HelperMethod(dsetName, boolDescUtf, dsetCode(tlGet, objectArrayClass), 3, 2));
		return new DynVarRuntime(fields, fieldNameUtfs, fieldDescUtf, tlSet, dget, dbind, dset, methods, clinitCode,
				cp.addUtf8("<clinit>"), cp.addUtf8("()V"));
	}

	/** {@code _dget(tl, global)}: the thread's cell value when bound, else the global. */
	private static List<Integer> dgetCode(MethodrefConstant tlGet, ClassConstant objectArrayClass) {
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.ALOAD); // 0
		code.add(0);
		code.add(Opcode.INVOKEVIRTUAL); // 2
		JvmRuntimeBuilder.emitU2(code, tlGet.index());
		code.add(Opcode.DUP); // 5
		code.add(Opcode.IFNULL); // 6 -> 15
		JvmRuntimeBuilder.emitU2(code, 15 - 6);
		code.add(Opcode.CHECKCAST); // 9
		JvmRuntimeBuilder.emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_0); // 12
		code.add(Opcode.AALOAD); // 13
		code.add(Opcode.ARETURN); // 14
		code.add(Opcode.POP); // 15
		code.add(Opcode.ALOAD); // 16
		code.add(1);
		code.add(Opcode.ARETURN); // 18
		return code;
	}

	/** {@code _dbind(tl, v)}: install a fresh cell, answer the previous one. */
	private static List<Integer> dbindCode(MethodrefConstant tlGet, MethodrefConstant tlSet, ConstantPool cp) {
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.ALOAD); // 0
		code.add(0);
		code.add(Opcode.INVOKEVIRTUAL); // 2: old
		JvmRuntimeBuilder.emitU2(code, tlGet.index());
		code.add(Opcode.ICONST_1); // 5
		code.add(Opcode.ANEWARRAY); // 6
		JvmRuntimeBuilder.emitU2(code, objectClass.index());
		code.add(Opcode.DUP); // 9
		code.add(Opcode.ICONST_0); // 10
		code.add(Opcode.ALOAD); // 11
		code.add(1);
		code.add(Opcode.AASTORE); // 13: old cell
		code.add(Opcode.ALOAD); // 14
		code.add(0);
		code.add(Opcode.SWAP); // 16: old tl cell
		code.add(Opcode.INVOKEVIRTUAL); // 17
		JvmRuntimeBuilder.emitU2(code, tlSet.index());
		code.add(Opcode.ARETURN); // 20
		return code;
	}

	/** {@code _dset(tl, v)}: write the thread's cell when bound (1), else answer 0. */
	private static List<Integer> dsetCode(MethodrefConstant tlGet, ClassConstant objectArrayClass) {
		List<Integer> code = new ArrayList<>();
		code.add(Opcode.ALOAD); // 0
		code.add(0);
		code.add(Opcode.INVOKEVIRTUAL); // 2
		JvmRuntimeBuilder.emitU2(code, tlGet.index());
		code.add(Opcode.DUP); // 5
		code.add(Opcode.IFNULL); // 6 -> 18
		JvmRuntimeBuilder.emitU2(code, 18 - 6);
		code.add(Opcode.CHECKCAST); // 9
		JvmRuntimeBuilder.emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_0); // 12
		code.add(Opcode.ALOAD); // 13
		code.add(1);
		code.add(Opcode.AASTORE); // 15
		code.add(Opcode.ICONST_1); // 16
		code.add(Opcode.IRETURN); // 17
		code.add(Opcode.POP); // 18
		code.add(Opcode.ICONST_0); // 19
		code.add(Opcode.IRETURN); // 20
		return code;
	}

}
