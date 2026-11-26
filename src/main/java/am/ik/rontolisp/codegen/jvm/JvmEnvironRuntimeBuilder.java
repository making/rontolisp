package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * Builds the {@code _environ} runtime helper behind the {@code %host-environ} primitive:
 * every variable of {@code System.getenv()} as a Lisp list of {@code "NAME=value"}
 * strings, in the map's own order. Emitted ONLY for a program that references the
 * primitive (Scheme's {@code get-environment-variables}), so every other program keeps
 * byte-identical output.
 */
final class JvmEnvironRuntimeBuilder {

	static final String METHOD = "_environ";

	static final String DESC = "()Ljava/lang/Object;";

	/** The emitted helper: its name/descriptor plus the body. */
	record EnvironRuntime(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	private JvmEnvironRuntimeBuilder() {
	}

	static EnvironRuntime build(ConstantPool cp, ClassEntry objectClass, MethodRefEntry stringConcat) {
		ClassEntry entryClass = cp.classEntry("java/util/Map$Entry");
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		StringEntry quoteStr = cp.stringEntry("\"");
		StringEntry equalsStr = cp.stringEntry("=");
		MethodRefEntry getenv = cp.methodRef("java/lang/System", "getenv", "()Ljava/util/Map;");
		var entrySet = cp.interfaceMethodRef("java/util/Map", "entrySet", "()Ljava/util/Set;");
		var iterator = cp.interfaceMethodRef("java/util/Set", "iterator", "()Ljava/util/Iterator;");
		var hasNext = cp.interfaceMethodRef("java/util/Iterator", "hasNext", "()Z");
		var next = cp.interfaceMethodRef("java/util/Iterator", "next", "()Ljava/lang/Object;");
		var getKey = cp.interfaceMethodRef("java/util/Map$Entry", "getKey", "()Ljava/lang/Object;");
		var getValue = cp.interfaceMethodRef("java/util/Map$Entry", "getValue", "()Ljava/lang/Object;");

		// Slots: 0=acc (Object), 1=iterator, 2=entry. Runtime strings carry their quotes.
		MethodCode a = new MethodCode();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aconst_null();
		a.astore(0);
		a.invokestatic(getenv);
		a.invokeinterface(entrySet);
		a.invokeinterface(iterator);
		a.astore(1);
		a.labelBinding(loop);
		a.aload(1);
		a.invokeinterface(hasNext);
		a.ifeq(done);
		a.aload(1);
		a.invokeinterface(next);
		a.checkcast(entryClass);
		a.astore(2);
		// acc = new Object[] { "\"" + key + "=" + value + "\"", acc };
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.ldc(quoteStr);
		a.aload(2);
		a.invokeinterface(getKey);
		a.checkcast(stringClass);
		a.invokevirtual(stringConcat);
		a.ldc(equalsStr);
		a.invokevirtual(stringConcat);
		a.aload(2);
		a.invokeinterface(getValue);
		a.checkcast(stringClass);
		a.invokevirtual(stringConcat);
		a.ldc(quoteStr);
		a.invokevirtual(stringConcat);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(0);
		a.aastore();
		a.astore(0);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(0);
		a.areturn();
		return new EnvironRuntime(cp.utf8Entry(METHOD), cp.utf8Entry(DESC), a);
	}

}
