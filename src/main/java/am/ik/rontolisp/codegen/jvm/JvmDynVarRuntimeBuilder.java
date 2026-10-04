package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

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
 *
 * <p>
 * A special whose bound-ness the program probes and only a binding or an assignment gives
 * a value ({@code SpecialVarCollector.collectProbedValueless}) carries that bound-ness in
 * its variable: its {@code _g$} global starts as the class's UNBOUND marker
 * ({@code _unbound}, a {@code new Object()} no Lisp value can be, set in
 * {@code <clinit>}) and keeps it until a global store, so {@code _dbound(tl, global)}
 * answers t for this thread's binding or a global that is not the marker, and
 * {@code _dget} reads the marker back as nil. A binding never touches {@code _g$}, so the
 * marker outlives every extent. A program without such a special has neither, and its
 * {@code _dget} is the plain one.
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
	 * @param methods the helper method bodies to register
	 * @param clinitCode the {@code <clinit>} fragment creating every ThreadLocal (and the
	 * UNBOUND marker, seeding the globals that start with it)
	 * @param unboundSpecials the specials whose global starts as the UNBOUND marker
	 * @param unboundFieldName the marker field's name, or null without such a special
	 * @param dbound the {@code _dbound} helper ref, or null without such a special
	 */
	record DynVarRuntime(Map<String, FieldRefEntry> fields, List<Utf8Entry> fieldNameUtfs, Utf8Entry fieldDescUtf,
			MethodRefEntry tlSet, MethodRefEntry dget, MethodRefEntry dbind, MethodRefEntry dset,
			List<HelperMethod> methods, MethodCode clinitCode, Utf8Entry clinitName, Utf8Entry clinitDesc,
			SequencedSet<String> unboundSpecials, @Nullable Utf8Entry unboundFieldName,
			@Nullable MethodRefEntry dbound) {
	}

	/** One helper method: its name/descriptor constants and body. */
	record HelperMethod(Utf8Entry nameUtf8, Utf8Entry descUtf8, MethodCode code) {
	}

	/**
	 * The runtime, with {@code unboundSpecials} carrying their bound-ness in their
	 * variable (see the class note). Both sets are {@link SequencedSet}s, not plain
	 * {@code Set}s: their iteration order is the mint order of the fields, the seeding
	 * order and the order of the constant-pool entries behind them, so an unordered one
	 * emits a different-but-equivalent class per JVM run
	 * (.kb/emitted-output-determinism.md).
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param objectArrayClass the {@code Object[]} class constant
	 * @param boundSpecials the dynamically bound specials, in mint order
	 * @param unboundSpecials those of them whose global starts as the UNBOUND marker, in
	 * seeding order
	 * @param globalFields every global's {@code _g$} field
	 * @return the runtime
	 */
	static DynVarRuntime build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectArrayClass,
			SequencedSet<String> boundSpecials, SequencedSet<String> unboundSpecials,
			Map<String, FieldRefEntry> globalFields) {
		ClassEntry threadLocalClass = cp.classEntry("java/lang/ThreadLocal");
		MethodRefEntry tlCtor = cp.methodRef(threadLocalClass, "<init>", "()V");
		MethodRefEntry tlGet = cp.methodRef(threadLocalClass, "get", "()Ljava/lang/Object;");
		MethodRefEntry tlSet = cp.methodRef(threadLocalClass, "set", "(Ljava/lang/Object;)V");
		Utf8Entry fieldDescUtf = cp.utf8Entry("Ljava/lang/ThreadLocal;");
		Map<String, FieldRefEntry> fields = new LinkedHashMap<>();
		List<Utf8Entry> fieldNameUtfs = new ArrayList<>();
		MethodCode clinitCode = new MethodCode();
		for (String name : boundSpecials) {
			Utf8Entry nameUtf = cp.utf8Entry("_d$" + JvmLispCompiler.mangleMethodName(name));
			fieldNameUtfs.add(nameUtf);
			FieldRefEntry field = cp.fieldRef(thisClass, nameUtf, fieldDescUtf);
			fields.put(name, field);
			clinitCode.new_(threadLocalClass);
			clinitCode.dup();
			clinitCode.invokespecial(tlCtor);
			clinitCode.putstatic(field);
		}
		// The UNBOUND marker and the globals that start with it, after the ThreadLocals.
		FieldRefEntry unboundField = null;
		Utf8Entry unboundFieldName = null;
		if (!unboundSpecials.isEmpty()) {
			ClassEntry objectClass = cp.classEntry("java/lang/Object");
			unboundFieldName = cp.utf8Entry(UNBOUND_FIELD);
			unboundField = cp.fieldRef(thisClass, unboundFieldName, cp.utf8Entry("Ljava/lang/Object;"));
			clinitCode.new_(objectClass);
			clinitCode.dup();
			clinitCode.invokespecial(cp.methodRef(objectClass, "<init>", "()V"));
			clinitCode.putstatic(unboundField);
			for (String name : unboundSpecials) {
				clinitCode.getstatic(unboundField);
				clinitCode.putstatic(Objects.requireNonNull(globalFields.get(name)));
			}
		}
		String refDesc = "(Ljava/lang/ThreadLocal;Ljava/lang/Object;)Ljava/lang/Object;";
		Utf8Entry dgetName = cp.utf8Entry("_dget");
		Utf8Entry dbindName = cp.utf8Entry("_dbind");
		Utf8Entry dsetName = cp.utf8Entry("_dset");
		Utf8Entry refDescUtf = cp.utf8Entry(refDesc);
		Utf8Entry boolDescUtf = cp.utf8Entry("(Ljava/lang/ThreadLocal;Ljava/lang/Object;)Z");
		MethodRefEntry dget = cp.methodRef(thisClass, dgetName, refDescUtf);
		MethodRefEntry dbind = cp.methodRef(thisClass, dbindName, refDescUtf);
		MethodRefEntry dset = cp.methodRef(thisClass, dsetName, boolDescUtf);
		List<HelperMethod> methods = new ArrayList<>(
				List.of(new HelperMethod(dgetName, refDescUtf, dgetCode(tlGet, objectArrayClass, unboundField)),
						new HelperMethod(dbindName, refDescUtf, dbindCode(tlGet, tlSet, cp)),
						new HelperMethod(dsetName, boolDescUtf, dsetCode(tlGet, objectArrayClass))));
		MethodRefEntry dbound = null;
		if (unboundField != null) {
			Utf8Entry dboundName = cp.utf8Entry("_dbound");
			dbound = cp.methodRef(thisClass, dboundName, refDescUtf);
			methods.add(new HelperMethod(dboundName, refDescUtf, dboundCode(tlGet, unboundField, cp)));
		}
		return new DynVarRuntime(fields, fieldNameUtfs, fieldDescUtf, tlSet, dget, dbind, dset, List.copyOf(methods),
				clinitCode, cp.utf8Entry("<clinit>"), cp.utf8Entry("()V"),
				Collections.unmodifiableSequencedSet(unboundSpecials), unboundFieldName, dbound);
	}

	/** The UNBOUND marker's field name (see the class note). */
	static final String UNBOUND_FIELD = "_unbound";

	/**
	 * {@code _dget(tl, global)}: the thread's cell value when bound, else the global --
	 * nil for the UNBOUND marker, when the program has one ({@code unboundField}).
	 */
	private static MethodCode dgetCode(MethodRefEntry tlGet, ClassEntry objectArrayClass,
			@Nullable FieldRefEntry unboundField) {
		MethodCode code = new MethodCode();
		code.aload(0);
		code.invokevirtual(tlGet);
		code.dup();
		MethodCode.Label unbound = code.newLabel();
		code.ifnull(unbound);
		code.checkcast(objectArrayClass);
		code.iconst_0();
		code.aaload();
		code.areturn();
		code.labelBinding(unbound);
		code.pop();
		code.aload(1);
		if (unboundField != null) {
			code.getstatic(unboundField);
			MethodCode.Label value = code.newLabel();
			code.if_acmpne(value);
			code.aconst_null();
			code.areturn();
			code.labelBinding(value);
			code.aload(1);
		}
		code.areturn();
		return code;
	}

	/**
	 * {@code _dbound(tl, global)}: t when this thread has a binding or the global is not
	 * the UNBOUND marker, else nil -- {@code boundp} of a special whose variable carries
	 * its bound-ness.
	 */
	private static MethodCode dboundCode(MethodRefEntry tlGet, FieldRefEntry unboundField, ConstantPool cp) {
		MethodCode code = new MethodCode();
		MethodCode.Label bound = code.newLabel();
		code.aload(0);
		code.invokevirtual(tlGet);
		code.ifnonnull(bound);
		code.aload(1);
		code.getstatic(unboundField);
		code.if_acmpne(bound);
		code.aconst_null();
		code.areturn();
		code.labelBinding(bound);
		code.ldc(cp.stringEntry("T"));
		code.areturn();
		return code;
	}

	/** {@code _dbind(tl, v)}: install a fresh cell, answer the previous one. */
	private static MethodCode dbindCode(MethodRefEntry tlGet, MethodRefEntry tlSet, ConstantPool cp) {
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		MethodCode code = new MethodCode();
		code.aload(0);
		code.invokevirtual(tlGet); // old
		code.iconst_1();
		code.anewarray(objectClass);
		code.dup();
		code.iconst_0();
		code.aload(1);
		code.aastore(); // old cell
		code.aload(0);
		code.swap(); // old tl cell
		code.invokevirtual(tlSet);
		code.areturn();
		return code;
	}

	/** {@code _dset(tl, v)}: write the thread's cell when bound (1), else answer 0. */
	private static MethodCode dsetCode(MethodRefEntry tlGet, ClassEntry objectArrayClass) {
		MethodCode code = new MethodCode();
		code.aload(0);
		code.invokevirtual(tlGet);
		code.dup();
		MethodCode.Label unbound = code.newLabel();
		code.ifnull(unbound);
		code.checkcast(objectArrayClass);
		code.iconst_0();
		code.aload(1);
		code.aastore();
		code.iconst_1();
		code.ireturn();
		code.labelBinding(unbound);
		code.pop();
		code.iconst_0();
		code.ireturn();
		return code;
	}

}
