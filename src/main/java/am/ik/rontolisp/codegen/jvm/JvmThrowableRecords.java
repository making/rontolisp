package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

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
 *
 * <p>
 * A record also says whether the {@code handler-bind} handlers already ran for its
 * condition: {@link #COND_RAN} records {@code {_condTl, condition}} instead of the bare
 * condition (no Lisp value can hold the private {@code _condTl}, so the shape is
 * unambiguous) and {@link #COND_OF} answers the condition either shape names. What only
 * passes a record on -- an async body's future, a thread's join, a Java callback's
 * custody -- carries it as it is, so the fact rides the flight wherever the condition
 * does; a {@code %hb-guard} pad reads it instead of a global mark that the next signal a
 * cleanup handled could replace.
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

	/**
	 * {@code _condRan(t, c)}: records for {@code t} that the {@code handler-bind}
	 * handlers ran for {@code c} -- the record {@code {_condTl, c}} -- and answers
	 * {@code t}.
	 */
	static final String COND_RAN = "_condRan";

	static final String COND_RAN_DESC = COND_PUT_DESC;

	/**
	 * {@code _condOf(r)}: the condition the record {@code r} names -- {@code c} of a
	 * {@link #COND_RAN} record, {@code r} itself otherwise.
	 */
	static final String COND_OF = "_condOf";

	static final String COND_OF_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String WEAK_MAP = "java/util/WeakHashMap";

	private JvmThrowableRecords() {
	}

	/**
	 * {@return the {@link #TL_MAP} reference}
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 */
	static MethodRefEntry tlMap(ConstantPool cp, ClassEntry thisClass) {
		return self(cp, thisClass, TL_MAP, TL_MAP_DESC);
	}

	/**
	 * {@return {@code WeakHashMap.put}}
	 * @param cp the constant pool
	 */
	static MethodRefEntry mapPut(ConstantPool cp) {
		return method(cp, WEAK_MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
	}

	/**
	 * {@return {@code WeakHashMap.get}}
	 * @param cp the constant pool
	 */
	static MethodRefEntry mapGet(ConstantPool cp) {
		return method(cp, WEAK_MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
	}

	static MethodRefEntry self(ConstantPool cp, ClassEntry thisClass, String name, String desc) {
		return cp.methodRef(thisClass, name, desc);
	}

	private static MethodRefEntry method(ConstantPool cp, String owner, String name, String desc) {
		return cp.methodRef(cp.classEntry(owner), name, desc);
	}

	/**
	 * Builds {@link #TL_MAP} and, for a program with a condition channel,
	 * {@link #COND_TAKE} and {@link #COND_PUT} -- plus {@link #COND_RAN} and
	 * {@link #COND_OF} when a record can say the handlers ran.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param condTl the condition channel's {@code _condTl}, or null when the program has
	 * none
	 * @param handlersRan whether a signal hook or a {@code %hb-guard} pad records that
	 * the handlers ran
	 * @return the methods
	 */
	static List<JvmNumericRuntimeBuilder.NumericMethod> build(ConstantPool cp, ClassEntry thisClass,
			@Nullable FieldRefEntry condTl, boolean handlersRan) {
		ClassEntry weakMap = cp.classEntry(WEAK_MAP);
		MethodRefEntry tlGet = method(cp, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;");
		MethodRefEntry tlSet = method(cp, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V");
		List<JvmNumericRuntimeBuilder.NumericMethod> methods = new ArrayList<>();

		// _tlMap(ThreadLocal tl): (WeakHashMap) tl.get(), or a new one tl now holds.
		MethodCode m = new MethodCode();
		MethodCode.Label have = m.newLabel();
		m.aload(0);
		m.invokevirtual(tlGet);
		m.checkcast(weakMap);
		m.astore(1);
		m.aload(1);
		m.ifnonnull(have);
		m.new_(weakMap);
		m.dup();
		m.invokespecial(method(cp, WEAK_MAP, "<init>", "()V"));
		m.astore(1);
		m.aload(0);
		m.aload(1);
		m.invokevirtual(tlSet);
		m.labelBinding(have);
		m.aload(1);
		m.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(TL_MAP), cp.addUtf8(TL_MAP_DESC),
				JvmRuntimeBuilder.codeBytes(m), 2, 2, List.of()));
		if (condTl == null) {
			return methods;
		}

		// _condTake(Throwable t): the thread's map, when it has one, loses t's entry.
		MethodCode take = new MethodCode();
		MethodCode.Label none = take.newLabel();
		take.getstatic(condTl);
		take.invokevirtual(tlGet);
		take.checkcast(weakMap);
		take.astore(1);
		take.aload(1);
		take.ifnull(none);
		take.aload(1);
		take.aload(0);
		take.invokevirtual(method(cp, WEAK_MAP, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;"));
		take.areturn();
		take.labelBinding(none);
		take.aconst_null();
		take.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(COND_TAKE), cp.addUtf8(COND_TAKE_DESC),
				JvmRuntimeBuilder.codeBytes(take), 2, 2, List.of()));

		// _condPut(Throwable t, Object c): _tlMap(_condTl).put(t, c) unless c is null; t.
		MethodCode put = new MethodCode();
		MethodCode.Label done = put.newLabel();
		put.aload(1);
		put.ifnull(done);
		put.getstatic(condTl);
		put.invokestatic(tlMap(cp, thisClass));
		put.aload(0);
		put.aload(1);
		put.invokevirtual(mapPut(cp));
		put.pop();
		put.labelBinding(done);
		put.aload(0);
		put.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(COND_PUT), cp.addUtf8(COND_PUT_DESC),
				JvmRuntimeBuilder.codeBytes(put), 3, 2, List.of()));
		if (!handlersRan) {
			return methods;
		}
		ClassEntry objectArray = cp.classEntry("[Ljava/lang/Object;");

		// _condRan(Throwable t, Object c): _condPut(t, new Object[] {_condTl, c}).
		MethodCode ran = new MethodCode();
		ran.aload(0);
		ran.loadConstant(2);
		ran.anewarray(cp.classEntry("java/lang/Object"));
		ran.dup();
		ran.loadConstant(0);
		ran.getstatic(condTl);
		ran.aastore();
		ran.dup();
		ran.loadConstant(1);
		ran.aload(1);
		ran.aastore();
		ran.invokestatic(self(cp, thisClass, COND_PUT, COND_PUT_DESC));
		ran.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(COND_RAN), cp.addUtf8(COND_RAN_DESC),
				JvmRuntimeBuilder.codeBytes(ran), 5, 2, List.of()));

		// _condOf(Object r): r[1] when r is a {_condTl, c} record, r otherwise.
		MethodCode of = new MethodCode();
		MethodCode.Label plain = of.newLabel();
		of.aload(0);
		of.instanceOf(objectArray);
		of.ifeq(plain);
		of.aload(0);
		of.checkcast(objectArray);
		of.astore(1);
		of.aload(1);
		of.arraylength();
		of.loadConstant(2);
		of.if_icmpne(plain);
		of.aload(1);
		of.loadConstant(0);
		of.aaload();
		of.getstatic(condTl);
		of.if_acmpne(plain);
		of.aload(1);
		of.loadConstant(1);
		of.aaload();
		of.areturn();
		of.labelBinding(plain);
		of.aload(0);
		of.areturn();
		methods.add(new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8(COND_OF), cp.addUtf8(COND_OF_DESC),
				JvmRuntimeBuilder.codeBytes(of), 2, 2, List.of()));
		return methods;
	}

}
