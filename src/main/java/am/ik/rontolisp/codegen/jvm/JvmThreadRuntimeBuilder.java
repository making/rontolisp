package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.codegen.jvm.JvmAsyncRuntimeBuilder.Asm;

/**
 * Builds the JVM bytecode of the thread primitives behind {@code rontolisp:make-thread},
 * {@code join-thread}, {@code threadp}, {@code thread-alive-p} and
 * {@code destroy-thread}: real thread creation for a compiled program (the
 * {@code bordeaux-threads}/{@code bt2} shim -- clack's handler.lisp is the driving
 * consumer -- delegates here).
 *
 * <p>
 * Representations in the compiled value model:
 * <ul>
 * <li>a <em>thread handle</em> is {@code {TMARKER, java.lang.Thread,
 * java.util.concurrent.FutureTask}} -- marker-headed like the async runtime's stream
 * values, so {@code _threadp} is an identity test no reader-producible value can alias.
 * The handle is OPAQUE (the interpreter hands out a {@code LispThread}, the WASM backends
 * nothing at all), so nothing portable may print or compare one;</li>
 * <li>the spawned body runs on a virtual thread driving a {@code FutureTask} over the
 * generated class's {@code call()} ({@code implements Callable} -- the Runnable twin of
 * the async runtime's {@code run()}). {@code call()} first establishes the
 * {@code (symbol . value)} bindings alist as thread-scoped dynamic bindings -- resolving
 * each name to its {@code _d$} ThreadLocal at RUNTIME through the generated {@code _dtl}
 * dispatch (the compiler forces every special into the dynamically-bound set when the
 * program spawns threads, so any of them is bindable by name) -- then applies the
 * zero-argument function through the {@code _invoke_0} dispatcher. No restore is needed:
 * the bindings die with the thread;</li>
 * <li>an error thrown by the body cannot ride the {@code _condTl} condition channel
 * across threads, so {@code call()} completes NORMALLY with the async runtime's
 * {@code {EMARKER, throwable, condition}} payload and {@code _thread_join} re-sets the
 * condition on the joining thread before rethrowing -- {@code handler-case} around the
 * join then dispatches by type exactly like a same-thread signal (the {@code _await}
 * precedent).</li>
 * </ul>
 *
 * Emitted ONLY when the program references one of the five primitives, so a thread-free
 * program keeps byte-identical output.
 */
final class JvmThreadRuntimeBuilder {

	/** Marker heading a thread handle's {@code Object[3]}. */
	static final String TMARKER = "%thread\n";

	static final String SPAWN_METHOD = "_thread_spawn";

	static final String SPAWN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String JOIN_METHOD = "_thread_join";

	static final String ALIVE_METHOD = "_thread_alive";

	static final String DESTROY_METHOD = "_thread_destroy";

	static final String THREADP_METHOD = "_threadp";

	static final String UNARY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String DTL_METHOD = "_dtl";

	static final String DTL_DESC = "(Ljava/lang/String;)Ljava/lang/ThreadLocal;";

	/** The generated instance fields backing one spawned thread's body. */
	static final String FN_FIELD = "_threadFn";

	static final String BINDINGS_FIELD = "_threadBindings";

	private JvmThreadRuntimeBuilder() {
	}

	/** A ready-to-emit method body (optionally with an exception table). */
	record ThreadMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			List<int[]> exceptionTable) {
	}

	/** The emitted bodies: the static helpers plus the public instance {@code call()}. */
	record ThreadRuntime(List<ThreadMethod> staticMethods, ThreadMethod callMethod) {
	}

	/**
	 * Builds the thread runtime method bodies and their constant-pool entries.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param objectClass {@code java/lang/Object}
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringClass {@code java/lang/String}
	 * @param channel the condition channel (already ensured by the caller; the error
	 * payload rides it across the join)
	 * @param instanceInitRef the generated class's no-arg constructor ref
	 * ({@code _thread_spawn} instantiates the class as the {@code Callable})
	 * @param stringConcat {@code String.concat(String)}
	 * @param dynVarRuntime the dynamic-binding runtime (never null here: the compiler
	 * forces the stream specials into the bound set when the program spawns threads)
	 * @return the runtime bodies
	 */
	static ThreadRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, JvmLispCompiler.ConditionChannel channel,
			MethodrefConstant instanceInitRef, MethodrefConstant stringConcat,
			JvmDynVarRuntimeBuilder.DynVarRuntime dynVarRuntime) {
		ClassConstant threadClass = cp.addClass(cp.addUtf8("java/lang/Thread"));
		MethodrefConstant threadOfVirtual = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("ofVirtual"), cp.addUtf8("()Ljava/lang/Thread$Builder$OfVirtual;")));
		ClassConstant ofVirtualClass = cp.addClass(cp.addUtf8("java/lang/Thread$Builder$OfVirtual"));
		MethodrefConstant builderStart = cp.addInterfaceMethodref(ofVirtualClass,
				cp.addNameAndType(cp.addUtf8("start"), cp.addUtf8("(Ljava/lang/Runnable;)Ljava/lang/Thread;")));
		MethodrefConstant threadIsAlive = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("isAlive"), cp.addUtf8("()Z")));
		MethodrefConstant threadInterrupt = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("interrupt"), cp.addUtf8("()V")));
		MethodrefConstant threadJoin = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("join"), cp.addUtf8("()V")));
		ClassConstant futureTaskClass = cp.addClass(cp.addUtf8("java/util/concurrent/FutureTask"));
		MethodrefConstant futureTaskCtor = cp.addMethodref(futureTaskClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/util/concurrent/Callable;)V")));
		MethodrefConstant futureTaskGet = cp.addMethodref(futureTaskClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()Ljava/lang/Object;")));
		ClassConstant throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		ClassConstant exceptionClass = cp.addClass(cp.addUtf8("java/lang/Exception"));
		ClassConstant iseClass = cp.addClass(cp.addUtf8("java/lang/IllegalStateException"));
		MethodrefConstant iseCtor = cp.addMethodref(iseClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant stringEquals = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		FieldrefConstant fnField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(FN_FIELD), cp.addUtf8("Ljava/lang/Object;")));
		FieldrefConstant bindingsField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(BINDINGS_FIELD), cp.addUtf8("Ljava/lang/Object;")));
		MethodrefConstant invoke0 = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8("_invoke_0"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant dtl = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(DTL_METHOD), cp.addUtf8(DTL_DESC)));
		FieldrefConstant condTlField = java.util.Objects.requireNonNull(channel.condTlField);
		MethodrefConstant tlGet = java.util.Objects.requireNonNull(channel.tlGet);
		MethodrefConstant tlSet = java.util.Objects.requireNonNull(channel.tlSet);
		ConstantPool.StringConstant tMarker = cp.addString(TMARKER);
		ConstantPool.StringConstant eMarker = cp.addString(JvmAsyncRuntimeBuilder.EMARKER);
		ConstantPool.StringConstant tStr = cp.addString("T");

		List<ThreadMethod> methods = new ArrayList<>();

		// --- _thread_spawn(fn, bindings): FutureTask over a fresh runner instance on a
		// virtual thread; the handle packs the thread (alive/destroy) and the task (join)
		{
			Asm a = new Asm();
			// runner (slot 2) = new Prog() with the two fields set
			a.op(Opcode.NEW);
			a.u2(thisClass.index());
			a.op(Opcode.DUP);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(instanceInitRef.index());
			a.astore(2);
			a.aload(2);
			a.aload(0);
			a.op(Opcode.PUTFIELD);
			a.u2(fnField.index());
			a.aload(2);
			a.aload(1);
			a.op(Opcode.PUTFIELD);
			a.u2(bindingsField.index());
			// task (slot 3) = new FutureTask(runner)
			a.op(Opcode.NEW);
			a.u2(futureTaskClass.index());
			a.op(Opcode.DUP);
			a.aload(2);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(futureTaskCtor.index());
			a.astore(3);
			// thread (slot 4) = Thread.ofVirtual().start(task)
			a.op(Opcode.INVOKESTATIC);
			a.u2(threadOfVirtual.index()); // [builder]
			a.aload(3);
			a.op(Opcode.INVOKEINTERFACE);
			a.u2(builderStart.index());
			a.op(2); // this + 1 arg
			a.op(0); // [thread]
			a.astore(4);
			// {TMARKER, thread, task}
			a.iconst(3);
			a.anewarray(objectClass);
			a.op(Opcode.DUP);
			a.iconst(0);
			a.ldc(tMarker.index());
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(1);
			a.aload(4);
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(2);
			a.aload(3);
			a.aastore();
			a.areturn();
			methods
				.add(new ThreadMethod(cp.addUtf8(SPAWN_METHOD), cp.addUtf8(SPAWN_DESC), 4, 5, a.finish(), List.of()));
		}

		// --- _thread_join(h): the task's value, rethrowing an EMARKER error payload with
		// its condition re-set on the joining thread (the _await precedent)
		{
			Asm a = new Asm();
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(2);
			a.aaload();
			a.checkcast(futureTaskClass);
			// the try region covers ONLY get(): a bad handle's ClassCastException above
			// must surface as itself, not as "interrupted"
			int tryStart = a.pos();
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(futureTaskGet.index()); // [v]
			a.astore(1);
			// also wait for the thread itself to die, so thread-alive-p answers nil
			// deterministically after a join (the task settles inside the body, a beat
			// before the thread's teardown). The handle casts cannot throw here: the
			// same values already passed the casts above.
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(threadClass);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(threadJoin.index());
			int tryEnd = a.pos();
			int check = a.label();
			a.branch(Opcode.GOTO, check);
			// catch (Exception e): interrupted while joining (call() itself never throws)
			int handler = a.pos();
			a.astore(2);
			a.op(Opcode.NEW);
			a.u2(iseClass.index());
			a.op(Opcode.DUP);
			a.ldc(cp.addString("JOIN-THREAD: interrupted while joining the thread").index());
			a.op(Opcode.INVOKESPECIAL);
			a.u2(iseCtor.index());
			a.op(Opcode.ATHROW);
			a.bind(check);
			int ret = a.label();
			a.aload(1);
			a.op(Opcode.INSTANCEOF);
			a.u2(objectArrayClass.index());
			a.branch(Opcode.IFEQ, ret);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.op(Opcode.ARRAYLENGTH);
			a.iconst(3);
			a.branch(Opcode.IF_ICMPNE, ret);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.iconst(0);
			a.aaload();
			a.ldc(eMarker.index());
			a.branch(Opcode.IF_ACMPNE, ret);
			// error payload: re-set the condition on this thread, rethrow the throwable
			a.op(Opcode.GETSTATIC);
			a.u2(condTlField.index());
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.iconst(2);
			a.aaload();
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(tlSet.index());
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(throwableClass);
			a.op(Opcode.ATHROW);
			a.bind(ret);
			a.aload(1);
			a.areturn();
			methods.add(new ThreadMethod(cp.addUtf8(JOIN_METHOD), cp.addUtf8(UNARY_DESC), 3, 3, a.finish(),
					List.of(new int[] { tryStart, tryEnd, handler, exceptionClass.index() })));
		}

		// --- _thread_alive(h): Thread.isAlive as T/nil
		{
			Asm a = new Asm();
			int no = a.label();
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(threadClass);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(threadIsAlive.index());
			a.branch(Opcode.IFEQ, no);
			a.ldc(tStr.index());
			a.areturn();
			a.bind(no);
			a.aconstNull();
			a.areturn();
			methods
				.add(new ThreadMethod(cp.addUtf8(ALIVE_METHOD), cp.addUtf8(UNARY_DESC), 2, 1, a.finish(), List.of()));
		}

		// --- _thread_destroy(h): interrupt the thread, answer the handle
		{
			Asm a = new Asm();
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(threadClass);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(threadInterrupt.index());
			a.aload(0);
			a.areturn();
			methods
				.add(new ThreadMethod(cp.addUtf8(DESTROY_METHOD), cp.addUtf8(UNARY_DESC), 2, 1, a.finish(), List.of()));
		}

		// --- _threadp(x): the marker identity test
		{
			Asm a = new Asm();
			int no = a.label();
			a.aload(0);
			a.op(Opcode.INSTANCEOF);
			a.u2(objectArrayClass.index());
			a.branch(Opcode.IFEQ, no);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.op(Opcode.ARRAYLENGTH);
			a.iconst(3);
			a.branch(Opcode.IF_ICMPNE, no);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(0);
			a.aaload();
			a.ldc(tMarker.index());
			a.branch(Opcode.IF_ACMPNE, no);
			a.ldc(tStr.index());
			a.areturn();
			a.bind(no);
			a.aconstNull();
			a.areturn();
			methods
				.add(new ThreadMethod(cp.addUtf8(THREADP_METHOD), cp.addUtf8(UNARY_DESC), 2, 1, a.finish(), List.of()));
		}

		// --- _dtl(name): the special's _d$ ThreadLocal by runtime name, or a clear error
		{
			Asm a = new Asm();
			for (Map.Entry<String, FieldrefConstant> entry : dynVarRuntime.fields().entrySet()) {
				int next = a.label();
				a.ldc(cp.addString(entry.getKey()).index());
				a.aload(0);
				a.op(Opcode.INVOKEVIRTUAL);
				a.u2(stringEquals.index());
				a.branch(Opcode.IFEQ, next);
				a.op(Opcode.GETSTATIC);
				a.u2(entry.getValue().index());
				a.areturn();
				a.bind(next);
			}
			a.op(Opcode.NEW);
			a.u2(iseClass.index());
			a.op(Opcode.DUP);
			a.ldc(cp.addString("MAKE-THREAD: cannot dynamically bind ").index());
			a.aload(0);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringConcat.index());
			a.ldc(cp.addString(" (not a special variable of this program)").index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringConcat.index());
			a.op(Opcode.INVOKESPECIAL);
			a.u2(iseCtor.index());
			a.op(Opcode.ATHROW);
			methods.add(new ThreadMethod(cp.addUtf8(DTL_METHOD), cp.addUtf8(DTL_DESC), 4, 1, a.finish(), List.of()));
		}

		// --- call(): the spawned body (Callable protocol) -- bind, run, or answer the
		// EMARKER error payload
		ThreadMethod callMethod;
		{
			MethodrefConstant dbind = dynVarRuntime.dbind();
			Asm a = new Asm();
			int tryStart = a.pos();
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(bindingsField.index());
			a.astore(1);
			int loop = a.label();
			int loopEnd = a.label();
			a.bind(loop);
			a.aload(1);
			a.branch(Opcode.IFNULL, loopEnd);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.astore(2); // cons
			a.aload(2);
			a.iconst(0);
			a.aaload();
			a.checkcast(objectArrayClass);
			a.astore(3); // pair (name . value)
			a.aload(3);
			a.iconst(0);
			a.aaload();
			a.checkcast(stringClass);
			a.op(Opcode.INVOKESTATIC);
			a.u2(dtl.index()); // [tl]
			a.aload(3);
			a.iconst(1);
			a.aaload();
			a.op(Opcode.INVOKESTATIC);
			a.u2(dbind.index()); // [old cell]
			a.op(Opcode.POP); // no restore: the bindings die with the thread
			a.aload(2);
			a.iconst(1);
			a.aaload();
			a.astore(1);
			a.branch(Opcode.GOTO, loop);
			a.bind(loopEnd);
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(fnField.index());
			a.op(Opcode.INVOKESTATIC);
			a.u2(invoke0.index());
			a.areturn();
			int tryEnd = a.pos();
			// catch (Throwable t): answer {EMARKER, t, _condTl.get()} normally -- the
			// condition channel is a ThreadLocal, so the payload carries it to the joiner
			int handler = a.pos();
			a.astore(1);
			a.iconst(3);
			a.anewarray(objectClass);
			a.op(Opcode.DUP);
			a.iconst(0);
			a.ldc(eMarker.index());
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(1);
			a.aload(1);
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(2);
			a.op(Opcode.GETSTATIC);
			a.u2(condTlField.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(tlGet.index());
			a.aastore();
			a.areturn();
			callMethod = new ThreadMethod(cp.addUtf8("call"), cp.addUtf8("()Ljava/lang/Object;"), 4, 4, a.finish(),
					List.of(new int[] { tryStart, tryEnd, handler, throwableClass.index() }));
		}

		return new ThreadRuntime(List.copyOf(methods), callMethod);
	}

}
