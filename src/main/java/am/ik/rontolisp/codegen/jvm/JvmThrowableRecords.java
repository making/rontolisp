package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

import org.jspecify.annotations.Nullable;

/**
 * What a compiled program's throwable carries besides its message, recorded per thread
 * and KEYED BY THE THROWABLE: the condition instance a signal travels with
 * ({@code _condTl}, {@link JvmLispCompiler.ConditionChannel}) and a wrong-type operand's
 * datum and expected type ({@code _teTl}, {@link JvmOperandTypeRuntime}). A
 * {@code RuntimeException} has nowhere to carry an object and a compiled program ships no
 * exception class of its own, so the record lives beside the throwable, in a
 * {@code java.util.WeakHashMap} each such {@code ThreadLocal} holds ({@link #TL_MAP}
 * makes it on the thread's first record).
 *
 * <p>
 * Keyed, a landing pad reads only the record of the throwable it caught. The single slot
 * it replaced was read by whichever pad came next: a plain {@code error} or a raw failure
 * handled inside an {@code unwind-protect} cleanup while a typed condition was on its way
 * out read the typed condition as its own, and the typed one arrived as a synthesized
 * {@code simple-error}.
 *
 * <p>
 * The keys are weak, so a record whose throwable no landing reads again -- a cleanup that
 * exits abandons it, a caller outside the program catches it -- goes with the throwable.
 * No record holds its throwable: a value that reaches its weak key keeps the entry alive.
 *
 * <p>
 * A condition is TAKEN by the landing that catches its throwable ({@link #COND_TAKE}) and
 * recorded again by one that passes the throwable on ({@link #COND_PUT}). A raw failure's
 * throwable is not always a fresh object -- HotSpot throws one preallocated instance per
 * class from a hot site ({@code OmitStackTraceInFastThrow}) -- and the instance a pad
 * synthesized for one failure must not describe the next; only a flight abandoned between
 * a pass-on and the next landing leaves such a record behind. A wrong-type record is made
 * for a fresh exception only, so it is never taken.
 */
final class JvmThrowableRecords {

	/**
	 * {@code _tlMap(tl)}: the calling thread's map of {@code tl}, made on first use.
	 */
	static final String TL_MAP = "_tlMap";

	static final String TL_MAP_DESC = "(Ljava/lang/ThreadLocal;)Ljava/util/WeakHashMap;";

	/**
	 * {@code _condTake(t)}: the condition recorded for {@code t} on this thread, removed,
	 * or null.
	 */
	static final String COND_TAKE = "_condTake";

	static final String COND_TAKE_DESC = "(Ljava/lang/Throwable;)Ljava/lang/Object;";

	/**
	 * {@code _condPut(t, c)}: records {@code c} for {@code t} on this thread (nothing for
	 * a null {@code c}) and answers {@code t}, so a site throws what it answers.
	 */
	static final String COND_PUT = "_condPut";

	static final String COND_PUT_DESC = "(Ljava/lang/Throwable;Ljava/lang/Object;)Ljava/lang/Throwable;";

	static final String WEAK_MAP = "java/util/WeakHashMap";

	private JvmThrowableRecords() {
	}

	/**
	 * {@return the {@link #TL_MAP} reference}
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 */
	static MethodrefConstant tlMap(ConstantPool cp, ClassConstant thisClass) {
		return self(cp, thisClass, TL_MAP, TL_MAP_DESC);
	}

	/**
	 * {@return {@code WeakHashMap.put}}
	 * @param cp the constant pool
	 */
	static MethodrefConstant mapPut(ConstantPool cp) {
		return method(cp, WEAK_MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
	}

	/**
	 * {@return {@code WeakHashMap.get}}
	 * @param cp the constant pool
	 */
	static MethodrefConstant mapGet(ConstantPool cp) {
		return method(cp, WEAK_MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
	}

	static MethodrefConstant self(ConstantPool cp, ClassConstant thisClass, String name, String desc) {
		return cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	private static MethodrefConstant method(ConstantPool cp, String owner, String name, String desc) {
		return cp.addMethodref(cp.addClass(cp.addUtf8(owner)), cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	/**
	 * Builds {@link #TL_MAP} and, for a program with a condition channel,
	 * {@link #COND_TAKE} and {@link #COND_PUT}.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param condTl the condition channel's {@code _condTl}, or null when the program has
	 * none
	 * @return the methods
	 */
	static List<JvmNumericRuntimeBuilder.NumericMethod> build(ConstantPool cp, ClassConstant thisClass,
			@Nullable FieldrefConstant condTl) {
		ClassConstant weakMap = cp.addClass(cp.addUtf8(WEAK_MAP));
		MethodrefConstant tlGet = method(cp, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;");
		MethodrefConstant tlSet = method(cp, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V");
		List<JvmNumericRuntimeBuilder.NumericMethod> methods = new ArrayList<>();

		// _tlMap(ThreadLocal tl): (WeakHashMap) tl.get(), or a new one tl now holds.
		JvmAsm m = new JvmAsm();
		int have = m.label();
		m.aload(0);
		m.invokevirtual(tlGet);
		m.checkcast(weakMap);
		m.astore(1);
		m.aload(1);
		m.branch(Opcode.IFNONNULL, have);
		m.anew(weakMap);
		m.dup();
		m.invokespecial(method(cp, WEAK_MAP, "<init>", "()V"));
		m.astore(1);
		m.aload(0);
		m.aload(1);
		m.invokevirtual(tlSet);
		m.bind(have);
		m.aload(1);
		m.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(TL_MAP), cp.addUtf8(TL_MAP_DESC), m.finish(),
				2, 2, List.of()));
		if (condTl == null) {
			return methods;
		}

		// _condTake(Throwable t): the thread's map, when it has one, loses t's entry.
		JvmAsm take = new JvmAsm();
		int none = take.label();
		take.getstatic(condTl);
		take.invokevirtual(tlGet);
		take.checkcast(weakMap);
		take.astore(1);
		take.aload(1);
		take.branch(Opcode.IFNULL, none);
		take.aload(1);
		take.aload(0);
		take.invokevirtual(method(cp, WEAK_MAP, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;"));
		take.areturn();
		take.bind(none);
		take.aconstNull();
		take.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(COND_TAKE), cp.addUtf8(COND_TAKE_DESC),
				take.finish(), 2, 2, List.of()));

		// _condPut(Throwable t, Object c): _tlMap(_condTl).put(t, c) unless c is null; t.
		JvmAsm put = new JvmAsm();
		int done = put.label();
		put.aload(1);
		put.branch(Opcode.IFNULL, done);
		put.getstatic(condTl);
		put.invokestatic(tlMap(cp, thisClass));
		put.aload(0);
		put.aload(1);
		put.invokevirtual(mapPut(cp));
		put.pop();
		put.bind(done);
		put.aload(0);
		put.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(COND_PUT), cp.addUtf8(COND_PUT_DESC),
				put.finish(), 3, 2, List.of()));
		return methods;
	}

}
