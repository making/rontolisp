package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;

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
 * {@code {EMARKER, throwable, condition}} payload and {@code _thread_join} records the
 * condition under the throwable on the joining thread before rethrowing it --
 * {@code handler-case} around the join then dispatches by type exactly like a same-thread
 * signal (the {@code _await} precedent).</li>
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

	static final String CURRENT_METHOD = "_thread_current";

	static final String CURRENT_DESC = "()Ljava/lang/Object;";

	/**
	 * The static ThreadLocal caching each thread's own handle, so
	 * {@code rontolisp:current-thread} is EQ-stable per thread (it must key an {@code eq}
	 * hash table -- dbi's per-thread connection cache). Declared and
	 * {@code <clinit>}-initialized by the class writer next to the condition channel's.
	 */
	static final String CURRENT_TL_FIELD = "_curThreadTl";

	static final String UNARY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String DTL_METHOD = "_dtl";

	static final String DTL_DESC = "(Ljava/lang/String;)Ljava/lang/ThreadLocal;";

	/** The generated instance fields backing one spawned thread's body. */
	static final String FN_FIELD = "_threadFn";

	static final String BINDINGS_FIELD = "_threadBindings";

	private JvmThreadRuntimeBuilder() {
	}

	/** A ready-to-emit method body, its handlers included. */
	record ThreadMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
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
			JvmDynVarRuntimeBuilder.DynVarRuntime dynVarRuntime, FieldrefConstant curThreadTlField) {
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
		java.lang.classfile.constantpool.MethodRefEntry condTake = java.util.Objects.requireNonNull(channel.condTake);
		java.lang.classfile.constantpool.MethodRefEntry condPut = java.util.Objects.requireNonNull(channel.condPut);
		ConstantPool.StringConstant tMarker = cp.addString(TMARKER);
		ConstantPool.StringConstant eMarker = cp.addString(JvmAsyncRuntimeBuilder.EMARKER);
		ConstantPool.StringConstant tStr = cp.addString("T");

		List<ThreadMethod> methods = new ArrayList<>();

		// --- _thread_spawn(fn, bindings): FutureTask over a fresh runner instance on a
		// virtual thread; the handle packs the thread (alive/destroy) and the task (join)
		{
			MethodCode a = new MethodCode();
			// runner (slot 2) = new Prog() with the two fields set
			a.new_(thisClass.entry());
			a.dup();
			a.invokespecial(instanceInitRef.entry());
			a.astore(2);
			a.aload(2);
			a.aload(0);
			a.putfield(fnField.entry());
			a.aload(2);
			a.aload(1);
			a.putfield(bindingsField.entry());
			// task (slot 3) = new FutureTask(runner)
			a.new_(futureTaskClass.entry());
			a.dup();
			a.aload(2);
			a.invokespecial(futureTaskCtor.entry());
			a.astore(3);
			// thread (slot 4) = Thread.ofVirtual().start(task)
			a.invokestatic(threadOfVirtual.entry()); // [builder]
			a.aload(3);
			a.invokeinterface(builderStart.interfaceMethodRefEntry());
			a.astore(4);
			// {TMARKER, thread, task}
			a.loadConstant(3);
			a.anewarray(objectClass.entry());
			a.dup();
			a.loadConstant(0);
			a.ldc(tMarker.entry());
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(4);
			a.aastore();
			a.dup();
			a.loadConstant(2);
			a.aload(3);
			a.aastore();
			a.areturn();
			methods.add(new ThreadMethod(cp.addUtf8(SPAWN_METHOD), cp.addUtf8(SPAWN_DESC), a));
		}

		// --- _thread_join(h): the task's value, rethrowing an EMARKER error payload with
		// its condition re-set on the joining thread (the _await precedent)
		{
			MethodCode a = new MethodCode();
			a.aload(0);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(2);
			a.aaload();
			a.checkcast(futureTaskClass.entry());
			// the try region covers ONLY get(): a bad handle's ClassCastException above
			// must surface as itself, not as "interrupted"
			MethodCode.Label tryStart = a.newBoundLabel();
			a.invokevirtual(futureTaskGet.methodRefEntry()); // [v]
			a.astore(1);
			// also wait for the thread itself to die, so thread-alive-p answers nil
			// deterministically after a join (the task settles inside the body, a beat
			// before the thread's teardown). The handle casts cannot throw here: the
			// same values already passed the casts above.
			a.aload(0);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(1);
			a.aaload();
			a.checkcast(threadClass.entry());
			a.invokevirtual(threadJoin.methodRefEntry());
			MethodCode.Label tryEnd = a.newBoundLabel();
			MethodCode.Label check = a.newLabel();
			a.goto_(check);
			// catch (Exception e): interrupted while joining (call() itself never throws)
			MethodCode.Label handler = a.newBoundLabel();
			a.astore(2);
			a.new_(iseClass.entry());
			a.dup();
			a.ldc(cp.addString("JOIN-THREAD: interrupted while joining the thread").entry());
			a.invokespecial(iseCtor.entry());
			a.athrow();
			a.labelBinding(check);
			MethodCode.Label ret = a.newLabel();
			a.aload(1);
			a.instanceOf(objectArrayClass.entry());
			a.ifeq(ret);
			a.aload(1);
			a.checkcast(objectArrayClass.entry());
			a.arraylength();
			a.loadConstant(3);
			a.if_icmpne(ret);
			a.aload(1);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(0);
			a.aaload();
			a.ldc(eMarker.entry());
			a.if_acmpne(ret);
			// error payload: record the condition under the throwable on this thread,
			// rethrow the throwable
			a.aload(1);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(1);
			a.aaload();
			a.checkcast(throwableClass.entry());
			a.aload(1);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(2);
			a.aaload();
			a.invokestatic(condPut);
			a.athrow();
			a.labelBinding(ret);
			a.aload(1);
			a.areturn();
			a.exceptionCatch(tryStart, tryEnd, handler, exceptionClass.entry());
			methods.add(new ThreadMethod(cp.addUtf8(JOIN_METHOD), cp.addUtf8(UNARY_DESC), a));
		}

		// --- _thread_alive(h): Thread.isAlive as T/nil
		{
			MethodCode a = new MethodCode();
			MethodCode.Label no = a.newLabel();
			a.aload(0);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(1);
			a.aaload();
			a.checkcast(threadClass.entry());
			a.invokevirtual(threadIsAlive.methodRefEntry());
			a.ifeq(no);
			a.ldc(tStr.entry());
			a.areturn();
			a.labelBinding(no);
			a.aconst_null();
			a.areturn();
			methods.add(new ThreadMethod(cp.addUtf8(ALIVE_METHOD), cp.addUtf8(UNARY_DESC), a));
		}

		// --- _thread_destroy(h): interrupt the thread, answer the handle
		{
			MethodCode a = new MethodCode();
			a.aload(0);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(1);
			a.aaload();
			a.checkcast(threadClass.entry());
			a.invokevirtual(threadInterrupt.methodRefEntry());
			a.aload(0);
			a.areturn();
			methods.add(new ThreadMethod(cp.addUtf8(DESTROY_METHOD), cp.addUtf8(UNARY_DESC), a));
		}

		// --- _thread_current(): the calling thread's own handle, cached in the
		// _curThreadTl ThreadLocal so repeated calls return the SAME array (an eq
		// hash-table key -- dbi's per-thread connection cache). Works for any thread,
		// not only _thread_spawn's; the task slot is null, so joining your own handle
		// is an error rather than a value (joining yourself deadlocks upstream too).
		{
			MethodrefConstant threadCurrent = cp.addMethodref(threadClass,
					cp.addNameAndType(cp.addUtf8("currentThread"), cp.addUtf8("()Ljava/lang/Thread;")));
			MethodrefConstant tlGetRef = java.util.Objects.requireNonNull(channel.tlGet);
			MethodrefConstant tlSetRef = java.util.Objects.requireNonNull(channel.tlSet);
			MethodCode a = new MethodCode();
			a.getstatic(curThreadTlField.entry());
			a.invokevirtual(tlGetRef.methodRefEntry());
			a.astore(0);
			MethodCode.Label ret = a.newLabel();
			a.aload(0);
			a.ifnonnull(ret);
			a.loadConstant(3);
			a.anewarray(objectClass.entry());
			a.dup();
			a.loadConstant(0);
			a.ldc(tMarker.entry());
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.invokestatic(threadCurrent.entry());
			a.aastore();
			a.astore(0);
			a.getstatic(curThreadTlField.entry());
			a.aload(0);
			a.invokevirtual(tlSetRef.methodRefEntry());
			a.labelBinding(ret);
			a.aload(0);
			a.areturn();
			methods.add(new ThreadMethod(cp.addUtf8(CURRENT_METHOD), cp.addUtf8(CURRENT_DESC), a));
		}

		// --- _threadp(x): the marker identity test
		{
			MethodCode a = new MethodCode();
			MethodCode.Label no = a.newLabel();
			a.aload(0);
			a.instanceOf(objectArrayClass.entry());
			a.ifeq(no);
			a.aload(0);
			a.checkcast(objectArrayClass.entry());
			a.arraylength();
			a.loadConstant(3);
			a.if_icmpne(no);
			a.aload(0);
			a.checkcast(objectArrayClass.entry());
			a.loadConstant(0);
			a.aaload();
			a.ldc(tMarker.entry());
			a.if_acmpne(no);
			a.ldc(tStr.entry());
			a.areturn();
			a.labelBinding(no);
			a.aconst_null();
			a.areturn();
			methods.add(new ThreadMethod(cp.addUtf8(THREADP_METHOD), cp.addUtf8(UNARY_DESC), a));
		}

		// --- _dtl(name): the special's _d$ ThreadLocal by runtime name, or a clear error
		{
			MethodCode a = new MethodCode();
			for (Map.Entry<String, FieldRefEntry> entry : dynVarRuntime.fields().entrySet()) {
				MethodCode.Label next = a.newLabel();
				a.ldc(cp.addString(entry.getKey()).entry());
				a.aload(0);
				a.invokevirtual(stringEquals.methodRefEntry());
				a.ifeq(next);
				a.getstatic(entry.getValue());
				a.areturn();
				a.labelBinding(next);
			}
			a.new_(iseClass.entry());
			a.dup();
			a.ldc(cp.addString("MAKE-THREAD: cannot dynamically bind ").entry());
			a.aload(0);
			a.invokevirtual(stringConcat.methodRefEntry());
			a.ldc(cp.addString(" (not a special variable of this program)").entry());
			a.invokevirtual(stringConcat.methodRefEntry());
			a.invokespecial(iseCtor.entry());
			a.athrow();
			methods.add(new ThreadMethod(cp.addUtf8(DTL_METHOD), cp.addUtf8(DTL_DESC), a));
		}

		// --- call(): the spawned body (Callable protocol) -- bind, run, or answer the
		// EMARKER error payload
		ThreadMethod callMethod;
		{
			MethodRefEntry dbind = dynVarRuntime.dbind();
			MethodCode a = new MethodCode();
			MethodCode.Label tryStart = a.newBoundLabel();
			a.aload(0);
			a.getfield(bindingsField.entry());
			a.astore(1);
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label loopEnd = a.newLabel();
			a.labelBinding(loop);
			a.aload(1);
			a.ifnull(loopEnd);
			a.aload(1);
			a.checkcast(objectArrayClass.entry());
			a.astore(2); // cons
			a.aload(2);
			a.loadConstant(0);
			a.aaload();
			a.checkcast(objectArrayClass.entry());
			a.astore(3); // pair (name . value)
			a.aload(3);
			a.loadConstant(0);
			a.aaload();
			a.checkcast(stringClass.entry());
			a.invokestatic(dtl.entry()); // [tl]
			a.aload(3);
			a.loadConstant(1);
			a.aaload();
			a.invokestatic(dbind); // [old cell]
			a.pop(); // no restore: the bindings die with the thread
			a.aload(2);
			a.loadConstant(1);
			a.aaload();
			a.astore(1);
			a.goto_(loop);
			a.labelBinding(loopEnd);
			a.aload(0);
			a.getfield(fnField.entry());
			a.invokestatic(invoke0.entry());
			a.areturn();
			MethodCode.Label tryEnd = a.newBoundLabel();
			// catch (Throwable t): answer {EMARKER, t, _condTake(t)} normally -- the
			// condition channel is a ThreadLocal, so the payload carries it to the joiner
			MethodCode.Label handler = a.newBoundLabel();
			a.astore(1);
			a.loadConstant(3);
			a.anewarray(objectClass.entry());
			a.dup();
			a.loadConstant(0);
			a.ldc(eMarker.entry());
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(1);
			a.aastore();
			a.dup();
			a.loadConstant(2);
			a.aload(1);
			a.invokestatic(condTake);
			a.aastore();
			a.areturn();
			a.exceptionCatch(tryStart, tryEnd, handler, throwableClass.entry());
			callMethod = new ThreadMethod(cp.addUtf8("call"), cp.addUtf8("()Ljava/lang/Object;"), a);
		}

		return new ThreadRuntime(List.copyOf(methods), callMethod);
	}

}
