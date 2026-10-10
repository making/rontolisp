package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import org.jspecify.annotations.Nullable;

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
	record ThreadMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
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
	 * @param teTl the thread-local record of a wrong-type operand's datum and type
	 * ({@link JvmOperandTypeRuntime}), or null when no landing pad reads one: the error
	 * payload carries the record of its throwable, made on the spawned thread, and
	 * {@code _thread_join} records it again on the joining one, so a built-in's
	 * type-error stays one across the join ({@code JvmAsyncRuntimeBuilder}'s await does
	 * the same)
	 * @return the runtime bodies
	 */
	static ThreadRuntime build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass,
			ClassEntry objectArrayClass, ClassEntry stringClass, JvmLispCompiler.ConditionChannel channel,
			MethodRefEntry instanceInitRef, MethodRefEntry stringConcat,
			JvmDynVarRuntimeBuilder.DynVarRuntime dynVarRuntime, FieldRefEntry curThreadTlField,
			@Nullable FieldRefEntry teTl) {
		ClassEntry threadClass = cp.classEntry("java/lang/Thread");
		MethodRefEntry threadOfVirtual = cp.methodRef(threadClass, "ofVirtual",
				"()Ljava/lang/Thread$Builder$OfVirtual;");
		ClassEntry ofVirtualClass = cp.classEntry("java/lang/Thread$Builder$OfVirtual");
		InterfaceMethodRefEntry builderStart = cp.interfaceMethodRef(ofVirtualClass, "start",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;");
		MethodRefEntry threadIsAlive = cp.methodRef(threadClass, "isAlive", "()Z");
		MethodRefEntry threadInterrupt = cp.methodRef(threadClass, "interrupt", "()V");
		MethodRefEntry threadJoin = cp.methodRef(threadClass, "join", "()V");
		ClassEntry futureTaskClass = cp.classEntry("java/util/concurrent/FutureTask");
		MethodRefEntry futureTaskCtor = cp.methodRef(futureTaskClass, "<init>", "(Ljava/util/concurrent/Callable;)V");
		MethodRefEntry futureTaskGet = cp.methodRef(futureTaskClass, "get", "()Ljava/lang/Object;");
		ClassEntry throwableClass = cp.classEntry("java/lang/Throwable");
		ClassEntry exceptionClass = cp.classEntry("java/lang/Exception");
		ClassEntry iseClass = cp.classEntry("java/lang/IllegalStateException");
		MethodRefEntry iseCtor = cp.methodRef(iseClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry stringEquals = cp.methodRef(stringClass, "equals", "(Ljava/lang/Object;)Z");
		FieldRefEntry fnField = cp.fieldRef(thisClass, FN_FIELD, "Ljava/lang/Object;");
		FieldRefEntry bindingsField = cp.fieldRef(thisClass, BINDINGS_FIELD, "Ljava/lang/Object;");
		MethodRefEntry invoke0 = cp.methodRef(thisClass, JvmRuntimeBuilder.dispatcherName(0, false),
				JvmRuntimeBuilder.dispatcherDesc(0, false));
		MethodRefEntry dtl = cp.methodRef(thisClass, DTL_METHOD, DTL_DESC);
		java.lang.classfile.constantpool.MethodRefEntry condTake = java.util.Objects.requireNonNull(channel.condTake);
		java.lang.classfile.constantpool.MethodRefEntry condPut = java.util.Objects.requireNonNull(channel.condPut);
		StringEntry tMarker = cp.stringEntry(TMARKER);
		StringEntry eMarker = cp.stringEntry(JvmAsyncRuntimeBuilder.EMARKER);
		StringEntry tStr = cp.stringEntry("T");
		// The error payload {EMARKER, t, cond}, and t's wrong-type record after them when
		// a pad can read one: {EMARKER, t, cond, record}.
		int errorPayloadLength = teTl != null ? 4 : 3;
		@Nullable MethodRefEntry tlMap = teTl != null ? JvmThrowableRecords.tlMap(cp, thisClass) : null;

		List<ThreadMethod> methods = new ArrayList<>();

		// --- _thread_spawn(fn, bindings): FutureTask over a fresh runner instance on a
		// virtual thread; the handle packs the thread (alive/destroy) and the task (join)
		{
			MethodCode a = new MethodCode();
			// runner (slot 2) = new Prog() with the two fields set
			a.new_(thisClass);
			a.dup();
			a.invokespecial(instanceInitRef);
			a.astore(2);
			a.aload(2);
			a.aload(0);
			a.putfield(fnField);
			a.aload(2);
			a.aload(1);
			a.putfield(bindingsField);
			// task (slot 3) = new FutureTask(runner)
			a.new_(futureTaskClass);
			a.dup();
			a.aload(2);
			a.invokespecial(futureTaskCtor);
			a.astore(3);
			// thread (slot 4) = Thread.ofVirtual().start(task)
			a.invokestatic(threadOfVirtual); // [builder]
			a.aload(3);
			a.invokeinterface(builderStart);
			a.astore(4);
			// {TMARKER, thread, task}
			a.loadConstant(3);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(tMarker);
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
			methods.add(new ThreadMethod(cp.utf8Entry(SPAWN_METHOD), cp.utf8Entry(SPAWN_DESC), a));
		}

		// --- _thread_join(h): the task's value, rethrowing an EMARKER error payload with
		// its condition re-set on the joining thread (the _await precedent)
		{
			MethodCode a = new MethodCode();
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(2);
			a.aaload();
			a.checkcast(futureTaskClass);
			// the try region covers ONLY get(): a bad handle's ClassCastException above
			// must surface as itself, not as "interrupted"
			MethodCode.Label tryStart = a.newBoundLabel();
			a.invokevirtual(futureTaskGet); // [v]
			a.astore(1);
			// also wait for the thread itself to die, so thread-alive-p answers nil
			// deterministically after a join (the task settles inside the body, a beat
			// before the thread's teardown). The handle casts cannot throw here: the
			// same values already passed the casts above.
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(threadClass);
			a.invokevirtual(threadJoin);
			MethodCode.Label tryEnd = a.newBoundLabel();
			MethodCode.Label check = a.newLabel();
			a.goto_(check);
			// catch (Exception e): interrupted while joining (call() itself never throws)
			MethodCode.Label handler = a.newBoundLabel();
			a.astore(2);
			a.new_(iseClass);
			a.dup();
			a.ldc(cp.stringEntry("JOIN-THREAD: interrupted while joining the thread"));
			a.invokespecial(iseCtor);
			a.athrow();
			a.labelBinding(check);
			MethodCode.Label ret = a.newLabel();
			a.aload(1);
			a.instanceOf(objectArrayClass);
			a.ifeq(ret);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.loadConstant(errorPayloadLength);
			a.if_icmpne(ret);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.loadConstant(0);
			a.aaload();
			a.ldc(eMarker);
			a.if_acmpne(ret);
			// error payload: record the condition under the throwable on this thread,
			// rethrow the throwable
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(throwableClass);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.loadConstant(2);
			a.aaload();
			a.invokestatic(condPut);
			if (teTl != null) {
				// t's wrong-type record, made on the spawned thread, recorded HERE too
				// (slot 3 = the record)
				MethodCode.Label noRecord = a.newLabel();
				a.aload(1);
				a.checkcast(objectArrayClass);
				a.loadConstant(3);
				a.aaload();
				a.astore(3);
				a.aload(3);
				a.ifnull(noRecord);
				a.dup();
				a.getstatic(teTl);
				a.invokestatic(java.util.Objects.requireNonNull(tlMap));
				a.swap();
				a.aload(3);
				a.invokevirtual(JvmThrowableRecords.mapPut(cp));
				a.pop();
				a.labelBinding(noRecord);
			}
			a.athrow();
			a.labelBinding(ret);
			a.aload(1);
			a.areturn();
			a.exceptionCatch(tryStart, tryEnd, handler, exceptionClass);
			methods.add(new ThreadMethod(cp.utf8Entry(JOIN_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _thread_alive(h): Thread.isAlive as T/nil
		{
			MethodCode a = new MethodCode();
			MethodCode.Label no = a.newLabel();
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(threadClass);
			a.invokevirtual(threadIsAlive);
			a.ifeq(no);
			a.ldc(tStr);
			a.areturn();
			a.labelBinding(no);
			a.aconst_null();
			a.areturn();
			methods.add(new ThreadMethod(cp.utf8Entry(ALIVE_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _thread_destroy(h): interrupt the thread, answer the handle
		{
			MethodCode a = new MethodCode();
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(threadClass);
			a.invokevirtual(threadInterrupt);
			a.aload(0);
			a.areturn();
			methods.add(new ThreadMethod(cp.utf8Entry(DESTROY_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _thread_current(): the calling thread's own handle, cached in the
		// _curThreadTl ThreadLocal so repeated calls return the SAME array (an eq
		// hash-table key -- dbi's per-thread connection cache). Works for any thread,
		// not only _thread_spawn's; the task slot is null, so joining your own handle
		// is an error rather than a value (joining yourself deadlocks upstream too).
		{
			MethodRefEntry threadCurrent = cp.methodRef(threadClass, "currentThread", "()Ljava/lang/Thread;");
			MethodRefEntry tlGetRef = java.util.Objects.requireNonNull(channel.tlGet);
			MethodRefEntry tlSetRef = java.util.Objects.requireNonNull(channel.tlSet);
			MethodCode a = new MethodCode();
			a.getstatic(curThreadTlField);
			a.invokevirtual(tlGetRef);
			a.astore(0);
			MethodCode.Label ret = a.newLabel();
			a.aload(0);
			a.ifnonnull(ret);
			a.loadConstant(3);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(tMarker);
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.invokestatic(threadCurrent);
			a.aastore();
			a.astore(0);
			a.getstatic(curThreadTlField);
			a.aload(0);
			a.invokevirtual(tlSetRef);
			a.labelBinding(ret);
			a.aload(0);
			a.areturn();
			methods.add(new ThreadMethod(cp.utf8Entry(CURRENT_METHOD), cp.utf8Entry(CURRENT_DESC), a));
		}

		// --- _threadp(x): the marker identity test
		{
			MethodCode a = new MethodCode();
			MethodCode.Label no = a.newLabel();
			a.aload(0);
			a.instanceOf(objectArrayClass);
			a.ifeq(no);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.loadConstant(3);
			a.if_icmpne(no);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(0);
			a.aaload();
			a.ldc(tMarker);
			a.if_acmpne(no);
			a.ldc(tStr);
			a.areturn();
			a.labelBinding(no);
			a.aconst_null();
			a.areturn();
			methods.add(new ThreadMethod(cp.utf8Entry(THREADP_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _dtl(name): the special's _d$ ThreadLocal by runtime name, or a clear error
		{
			MethodCode a = new MethodCode();
			for (Map.Entry<String, FieldRefEntry> entry : dynVarRuntime.fields().entrySet()) {
				MethodCode.Label next = a.newLabel();
				a.ldc(cp.stringEntry(entry.getKey()));
				a.aload(0);
				a.invokevirtual(stringEquals);
				a.ifeq(next);
				a.getstatic(entry.getValue());
				a.areturn();
				a.labelBinding(next);
			}
			a.new_(iseClass);
			a.dup();
			a.ldc(cp.stringEntry("MAKE-THREAD: cannot dynamically bind "));
			a.aload(0);
			a.invokevirtual(stringConcat);
			a.ldc(cp.stringEntry(" (not a special variable of this program)"));
			a.invokevirtual(stringConcat);
			a.invokespecial(iseCtor);
			a.athrow();
			methods.add(new ThreadMethod(cp.utf8Entry(DTL_METHOD), cp.utf8Entry(DTL_DESC), a));
		}

		// --- call(): the spawned body (Callable protocol) -- bind, run, or answer the
		// EMARKER error payload
		ThreadMethod callMethod;
		{
			MethodRefEntry dbind = dynVarRuntime.dbind();
			MethodCode a = new MethodCode();
			MethodCode.Label tryStart = a.newBoundLabel();
			a.aload(0);
			a.getfield(bindingsField);
			a.astore(1);
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label loopEnd = a.newLabel();
			a.labelBinding(loop);
			a.aload(1);
			a.ifnull(loopEnd);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.astore(2); // cons
			a.aload(2);
			a.loadConstant(0);
			a.aaload();
			a.checkcast(objectArrayClass);
			a.astore(3); // pair (name . value)
			a.aload(3);
			a.loadConstant(0);
			a.aaload();
			a.checkcast(stringClass);
			a.invokestatic(dtl); // [tl]
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
			a.getfield(fnField);
			a.iconst_0();
			a.invokestatic(invoke0);
			JvmTailBounce.unwrapRaw(a, cp, thisClass);
			a.areturn();
			MethodCode.Label tryEnd = a.newBoundLabel();
			// catch (Throwable t): answer {EMARKER, t, _condTake(t)} normally -- the
			// condition channel is a ThreadLocal, so the payload carries it to the joiner
			MethodCode.Label handler = a.newBoundLabel();
			a.astore(1);
			a.loadConstant(errorPayloadLength);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(eMarker);
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
			if (teTl != null) {
				// t's wrong-type record on this thread, or null
				a.dup();
				a.loadConstant(3);
				a.getstatic(teTl);
				a.invokestatic(java.util.Objects.requireNonNull(tlMap));
				a.aload(1);
				a.invokevirtual(JvmThrowableRecords.mapGet(cp));
				a.aastore();
			}
			a.areturn();
			a.exceptionCatch(tryStart, tryEnd, handler, throwableClass);
			callMethod = new ThreadMethod(cp.utf8Entry("call"), cp.utf8Entry("()Ljava/lang/Object;"), a);
		}

		return new ThreadRuntime(List.copyOf(methods), callMethod);
	}

}
