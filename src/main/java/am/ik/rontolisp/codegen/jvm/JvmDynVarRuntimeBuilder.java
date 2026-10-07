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
import am.ik.rontolisp.ClosRegistry;

/**
 * Builds the thread-scoped dynamic-binding runtime for special variables that are
 * dynamically bound somewhere in the program ({@code SpecialVarCollector.
 * collectDynamicallyBound}). Emitted only when that set is non-empty and Lisp code of the
 * program can run on another thread: a program that runs it on one thread only binds a
 * special by saving, setting and restoring its {@code _g$} field
 * ({@code JvmLetCompiler}), and a program that never {@code let}-binds a special compiles
 * byte-identically to a build without this runtime.
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
 * A global only a binding or an assignment gives a value carries its bound-ness in its
 * variable ({@link #unboundMarker}): its {@code _g$} global starts as the class's UNBOUND
 * marker and keeps it until a global store. {@code _dget} hands the marker back like any
 * value -- the read site tests it ({@code JvmExprCompiler.compileSpecialRead}) -- and for
 * a bound special whose bound-ness the program probes ({@code SpecialVarCollector.
 * collectProbedValueless}) {@code _dbound(tl, global)} answers t for this thread's
 * binding or a global that is not the marker. A binding never touches {@code _g$}, so the
 * marker outlives every extent. A program without such a special has no {@code _dbound}.
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
	 * @param clinitCode the {@code <clinit>} fragment creating every ThreadLocal
	 * @param dbound the {@code _dbound} helper ref, or null when the program probes the
	 * bound-ness of no bound special that starts as the UNBOUND marker
	 */
	record DynVarRuntime(Map<String, FieldRefEntry> fields, List<Utf8Entry> fieldNameUtfs, Utf8Entry fieldDescUtf,
			MethodRefEntry tlSet, MethodRefEntry dget, MethodRefEntry dbind, MethodRefEntry dset,
			List<HelperMethod> methods, MethodCode clinitCode, Utf8Entry clinitName, Utf8Entry clinitDesc,
			@Nullable MethodRefEntry dbound) {
	}

	/**
	 * The UNBOUND marker: {@code _unbound}, a {@code new Object()} no Lisp value can be,
	 * which the {@code _g$} global of each of {@code globals} holds from {@code <clinit>}
	 * until a store overwrites it. A read of such a global outside a binding of it
	 * signals the {@code unbound-variable} naming it ({@code _bound}), and {@code boundp}
	 * answers nil.
	 *
	 * @param globals the globals whose variable carries their bound-ness, in seeding
	 * order
	 * @param probed the ones among them a {@code boundp} answers from the variable
	 * ({@code LispMacroExpander.dynamicFirstBoundp}), in the order a computed probe's
	 * inline chain tests them; every other name's {@code boundp} probes the eval mirror,
	 * which agrees for a global no binding changes
	 * @param field the marker field
	 * @param fieldName its name constant
	 * @param fieldDesc its descriptor constant
	 * @param clinitCode the {@code <clinit>} fragment creating the marker and seeding the
	 * globals with it
	 * @param clinitName the {@code <clinit>} name constant
	 * @param clinitDesc the {@code <clinit>} descriptor constant
	 * @param bound the {@code _bound(value, name)} helper ref: the value, or the
	 * {@code unbound-variable} naming the variable when the value is the marker
	 * @param boundMethod its name, descriptor and body
	 */
	record UnboundMarker(SequencedSet<String> globals, SequencedSet<String> probed, FieldRefEntry field,
			Utf8Entry fieldName, Utf8Entry fieldDesc, MethodCode clinitCode, Utf8Entry clinitName, Utf8Entry clinitDesc,
			MethodRefEntry bound, HelperMethod boundMethod) {
	}

	/** One helper method: its name/descriptor constants and body. */
	record HelperMethod(Utf8Entry nameUtf8, Utf8Entry descUtf8, MethodCode code) {
	}

	/**
	 * The UNBOUND marker of a program some of whose globals carry their bound-ness in
	 * their variable (see the class note). {@code globals} is a {@link SequencedSet}, not
	 * a plain {@code Set}: its iteration order is the seeding order, so an unordered one
	 * emits a different-but-equivalent class per JVM run
	 * (.kb/emitted-output-determinism.md).
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param globals the globals whose {@code _g$} starts as the marker
	 * @param probed the ones among them whose {@code boundp} reads the variable
	 * @param globalFields every global's {@code _g$} field
	 * @return the marker, or null when {@code globals} is empty
	 */
	static @Nullable UnboundMarker unboundMarker(ConstantPool cp, ClassEntry thisClass, SequencedSet<String> globals,
			SequencedSet<String> probed, Map<String, FieldRefEntry> globalFields) {
		if (globals.isEmpty()) {
			return null;
		}
		ClassEntry objectClass = cp.classEntry("java/lang/Object");
		Utf8Entry fieldName = cp.utf8Entry(UNBOUND_FIELD);
		Utf8Entry fieldDesc = cp.utf8Entry("Ljava/lang/Object;");
		FieldRefEntry field = cp.fieldRef(thisClass, fieldName, fieldDesc);
		MethodCode clinitCode = new MethodCode();
		clinitCode.new_(objectClass);
		clinitCode.dup();
		clinitCode.invokespecial(cp.methodRef(objectClass, "<init>", "()V"));
		clinitCode.putstatic(field);
		for (String name : globals) {
			clinitCode.getstatic(field);
			clinitCode.putstatic(Objects.requireNonNull(globalFields.get(name)));
		}
		Utf8Entry boundName = cp.utf8Entry(BOUND_METHOD);
		Utf8Entry boundDesc = cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
		return new UnboundMarker(Collections.unmodifiableSequencedSet(globals),
				Collections.unmodifiableSequencedSet(probed), field, fieldName, fieldDesc, clinitCode,
				cp.utf8Entry("<clinit>"), cp.utf8Entry("()V"), cp.methodRef(thisClass, boundName, boundDesc),
				new HelperMethod(boundName, boundDesc, boundCode(cp, field)));
	}

	/** The name of the read check (see {@link UnboundMarker#bound}). */
	static final String BOUND_METHOD = "_bound";

	/**
	 * {@code _bound(value, name)}: the value, unless it is the UNBOUND marker, which
	 * throws {@code The variable NAME is unbound} -- the text the landing pad recovers
	 * the {@code unbound-variable} and its name from ({@code JvmHandlerCaseCompiler}),
	 * and the one the interpreter reports. Small enough for the JIT to inline at every
	 * site.
	 */
	private static MethodCode boundCode(ConstantPool cp, FieldRefEntry unboundField) {
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		ClassEntry runtimeEx = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry concat = cp.methodRef(stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodCode code = new MethodCode();
		MethodCode.Label unbound = code.newLabel();
		code.aload(0);
		code.getstatic(unboundField);
		code.if_acmpeq(unbound);
		code.aload(0);
		code.areturn();
		code.labelBinding(unbound);
		code.new_(runtimeEx);
		code.dup();
		code.ldc(cp.stringEntry(ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX));
		code.aload(1);
		code.invokevirtual(concat);
		code.ldc(cp.stringEntry(ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX));
		code.invokevirtual(concat);
		code.invokespecial(cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V"));
		code.athrow();
		return code;
	}

	/**
	 * The runtime. {@code boundSpecials} is a {@link SequencedSet}, not a plain
	 * {@code Set}: its iteration order is the mint order of the fields and the order of
	 * the constant-pool entries behind them, so an unordered one emits a
	 * different-but-equivalent class per JVM run (.kb/emitted-output-determinism.md).
	 * @param cp the class's constant pool
	 * @param thisClass the class being emitted
	 * @param objectArrayClass the {@code Object[]} class constant
	 * @param boundSpecials the dynamically bound specials, in mint order
	 * @param unboundField the UNBOUND marker when the program probes the bound-ness of
	 * some of them that start as it ({@link #unboundMarker}), else null
	 * @return the runtime
	 */
	static DynVarRuntime build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectArrayClass,
			SequencedSet<String> boundSpecials, @Nullable FieldRefEntry unboundField) {
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
				List.of(new HelperMethod(dgetName, refDescUtf, dgetCode(tlGet, objectArrayClass)),
						new HelperMethod(dbindName, refDescUtf, dbindCode(tlGet, tlSet, cp)),
						new HelperMethod(dsetName, boolDescUtf, dsetCode(tlGet, objectArrayClass))));
		MethodRefEntry dbound = null;
		if (unboundField != null) {
			Utf8Entry dboundName = cp.utf8Entry("_dbound");
			dbound = cp.methodRef(thisClass, dboundName, refDescUtf);
			methods.add(new HelperMethod(dboundName, refDescUtf, dboundCode(tlGet, unboundField, cp)));
		}
		return new DynVarRuntime(fields, fieldNameUtfs, fieldDescUtf, tlSet, dget, dbind, dset, List.copyOf(methods),
				clinitCode, cp.utf8Entry("<clinit>"), cp.utf8Entry("()V"), dbound);
	}

	/** The UNBOUND marker's field name (see the class note). */
	static final String UNBOUND_FIELD = "_unbound";

	/**
	 * {@code _dget(tl, global)}: the thread's cell value when bound, else the global --
	 * the UNBOUND marker included, which the read site tests.
	 */
	private static MethodCode dgetCode(MethodRefEntry tlGet, ClassEntry objectArrayClass) {
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
