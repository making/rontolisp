package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.runtime.RontoFetch;

import org.jspecify.annotations.Nullable;

/**
 * Builds the JVM bytecode of the async/await runtime: the {@code %async-run} primitive
 * behind {@code rontolisp:async-defun}/{@code async-lambda}, the generic
 * {@code rontolisp:await} resolver, and the first-class asynchronous stream operations.
 *
 * <p>
 * Representations in the compiled value model:
 * <ul>
 * <li>a <em>future</em> is a bare {@link java.util.concurrent.CompletableFuture} OR a
 * stream-read token {@code {RMARKER, queue, state}} -- a deferred take whose blocking
 * happens at await;</li>
 * <li>a <em>stream</em> is {@code {SMARKER, LinkedBlockingQueue, AtomicInteger}}: chunks
 * ride the queue, the flag closes it, and end of stream is the {@code SMARKER} string
 * itself as a re-enqueued poison pill (an interned marker no reader-producible value can
 * alias), so no pending-read bookkeeping is needed;</li>
 * <li>a PULL stream ({@code rontolisp::%stream-new}) is the same {@code Object[3]} with a
 * {@code {readFn, closeFn}} pair where the queue would be, so {@code _streamp} and the
 * {@code #<STREAM>} print need no second shape. Nothing is buffered: {@code _stream_read}
 * runs the read thunk right there and answers a SETTLED future, and the first nil chunk
 * runs the close thunk once -- the same protocol the WASM tiers' stream runtimes
 * implement;</li>
 * <li>an asynchronous body runs on a virtual thread: {@code _async_run} instantiates the
 * generated class (which {@code implements Runnable}), hands it the body funref, a fresh
 * future and the eager-start handoff latch, starts the thread and waits on the latch --
 * released by the body's first blocking await ({@code _handoffTl}) or its completion, the
 * cross-backend eager-start contract;</li>
 * <li>an error thrown by the body cannot ride the {@code _condTl} condition channel
 * across threads (it is a ThreadLocal), so {@code run()} completes the future NORMALLY
 * with {@code {EMARKER, throwable, condition}} and {@code _await} records the condition
 * under the throwable on the awaiting thread before rethrowing it -- {@code handler-case}
 * around the await then dispatches by type exactly like a same-thread signal. A program
 * whose uncaught report records async boundaries adds the throwable's trace as the body
 * left it, which each await puts back ({@link JvmUncaughtHandler});</li>
 * <li>a body that answers other than exactly one value completes the future with
 * {@code {VMARKER, primary, extras}}, the channel read on the body's own thread the
 * moment it returns; {@code _await} answers the primary and publishes the extras on the
 * awaiting thread, the last future of a flattened chain deciding (a non-future, or a
 * one-value hop, publishes one value).</li>
 * </ul>
 * A fetch's future settles to its response plist inside the transport
 * ({@code runtime/RontoFetch}, which builds the body as a stream of this shape), so
 * {@code _await} knows nothing of HTTP. Futures are flattened in a loop, like JavaScript
 * await.
 */
final class JvmAsyncRuntimeBuilder {

	/**
	 * Marker heading a stream's {@code Object[3]}; doubles as the EOF poison pill.
	 * Declared by the fetch transport, which builds a reply's body stream in Java.
	 */
	static final String SMARKER = RontoFetch.STREAM_MARKER;

	/** Marker heading a stream-read token's {@code Object[3]}. */
	static final String RMARKER = "%stream-read\n";

	/**
	 * Marker heading an async body's error payload {@code {EMARKER, throwable, cond}}
	 * ({@code {EMARKER, throwable, cond, trace}} when the uncaught report records async
	 * boundaries).
	 */
	static final String EMARKER = "%async-error\n";

	/**
	 * Marker heading an async body's multiple-value payload {@code {VMARKER, primary,
	 * extras}}: the body answered other than exactly one value, {@code extras} being the
	 * {@code %mv-spill} channel as the body left it (the list of the values after the
	 * primary, or the zero-values marker). Built only in a program whose spill field
	 * exists -- no other program has a consumer to hand extras to.
	 */
	static final String VMARKER = "%async-values\n";

	static final String ASYNC_RUN_METHOD = "_async_run";

	static final String ASYNC_RUN_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AWAIT_METHOD = "_await";

	static final String AWAIT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String FUTUREP_METHOD = "_futurep";

	static final String STREAMP_METHOD = "_streamp";

	static final String MAKE_STREAM_METHOD = "_make_stream";

	static final String MAKE_STREAM_DESC = "()Ljava/lang/Object;";

	static final String STREAM_NEW_METHOD = "_stream_new";

	static final String STREAM_NEW_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String STREAM_READ_METHOD = "_stream_read";

	static final String STREAM_WRITE_METHOD = "_stream_write";

	static final String STREAM_WRITE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String STREAM_CLOSE_METHOD = "_stream_close";

	static final String DRAIN_BODY_METHOD = "_drain_body";

	/**
	 * {@code _iv_of_bytes(byte[]) -> byte[]}: raw bytes -> the packed
	 * {@code (unsigned-byte 8)} vector ({@code byte[]{8, e0, ...}}, the {@code _iv*}
	 * runtime's representation) a body stream answers them as. A drained octet body is
	 * built through it.
	 */
	static final String IV_OF_BYTES_METHOD = "_iv_of_bytes";

	static final String IV_OF_BYTES_DESC = "([B)[B";

	/**
	 * {@code _octetsToString(Object) -> Object}: the packed {@code (unsigned-byte 8)}
	 * vector ({@code long[]{8, e0, ...}}) decoded by
	 * {@code rontolisp::%octets-to-string}'s lenient rule into the quote-framed string,
	 * or {@code null} ({@code nil}) for any other value. The native half of that decoder:
	 * a well-formed body is a JDK decode, malformed bytes a bytecode transcode, and only
	 * a general array reaches the prelude's per-byte loop. Emitted only when the program
	 * references the primitive.
	 */
	static final String OCTETS_PACKED_METHOD = "_octetsToString";

	static final String WAIT_FOR_METHOD = "_wait_for";

	static final String RELEASE_HANDOFF_METHOD = "_release_handoff";

	static final String RELEASE_HANDOFF_DESC = "()V";

	static final String UNARY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/** The generated instance fields backing one async body run. */
	static final String FN_FIELD = "_asyncFn";

	static final String FUTURE_FIELD = "_asyncFuture";

	static final String LATCH_FIELD = "_asyncLatch";

	/** The eager-start handoff ThreadLocal static field. */
	static final String HANDOFF_FIELD = "_handoffTl";

	private JvmAsyncRuntimeBuilder() {
	}

	/** A ready-to-emit method body, its handlers included. */
	record AsyncMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	/**
	 * The emitted bodies: the static helpers plus, when the program spawns async bodies
	 * ({@code %async-run}), the public instance {@code run()} of the Runnable protocol.
	 */
	record AsyncRuntime(List<AsyncMethod> staticMethods, AsyncMethod runMethod, boolean spawns) {
	}

	/**
	 * Builds the async runtime method bodies and their constant-pool entries.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param objectClass {@code java/lang/Object}
	 * @param objectArrayClass {@code [Ljava/lang/Object;}
	 * @param stringClass {@code java/lang/String}
	 * @param channel the condition channel (already ensured by the caller; the error
	 * payload rides it across the await)
	 * @param instanceInitRef the generated class's no-arg constructor ref (present
	 * whenever this runtime is emitted; {@code _async_run} instantiates the class)
	 * @param longValueOf {@code Long.valueOf(J)}
	 * @param stringLength {@code String.length()}
	 * @param stringSubstring {@code String.substring(II)}
	 * @param stringConcat {@code String.concat(String)}
	 * @param launcherRun the sized-stack launcher's {@code _main$run(Prog)}, or null when
	 * main runs on the caller's thread ({@code JvmSizedMainBuilder}): the class has ONE
	 * {@code run()}, so the launcher instance -- the one whose latch is null -- is
	 * dispatched from its head
	 * @param mvChannel the {@code %mv-spill} channel, or null when the program has no
	 * multiple-value consumer: a body's extra values then need not travel, and the bodies
	 * are what they were before they could
	 * @param asyncAwaited {@code _asyncAwaited}, which {@code _await} hands a body's
	 * exception to before rethrowing it -- the uncaught report's record of the await
	 * ({@link JvmUncaughtHandler}) -- or null when no async body records its boundary
	 * @return the runtime bodies
	 */
	static AsyncRuntime build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass,
			ClassEntry objectArrayClass, ClassEntry stringClass, JvmLispCompiler.ConditionChannel channel,
			MethodRefEntry instanceInitRef, MethodRefEntry longValueOf, MethodRefEntry stringLength,
			MethodRefEntry stringSubstring, MethodRefEntry stringConcat, @Nullable MethodRefEntry launcherRun,
			@Nullable JvmMvChannel mvChannel, @Nullable MethodRefEntry asyncAwaited) {
		// --- shared class/method references ---
		ClassEntry futureClass = cp.classEntry("java/util/concurrent/CompletableFuture");
		MethodRefEntry futureCtor = cp.methodRef(futureClass, "<init>", "()V");
		MethodRefEntry futureJoin = cp.methodRef(futureClass, "join", "()Ljava/lang/Object;");
		MethodRefEntry futureIsDone = cp.methodRef(futureClass, "isDone", "()Z");
		MethodRefEntry futureComplete = cp.methodRef(futureClass, "complete", "(Ljava/lang/Object;)Z");
		MethodRefEntry futureCompleted = cp.methodRef(futureClass, "completedFuture",
				"(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;");

		ClassEntry queueClass = cp.classEntry("java/util/concurrent/LinkedBlockingQueue");
		MethodRefEntry queueCtor = cp.methodRef(queueClass, "<init>", "()V");
		MethodRefEntry queueOffer = cp.methodRef(queueClass, "offer", "(Ljava/lang/Object;)Z");
		MethodRefEntry queueTake = cp.methodRef(queueClass, "take", "()Ljava/lang/Object;");

		ClassEntry atomicIntClass = cp.classEntry("java/util/concurrent/atomic/AtomicInteger");
		MethodRefEntry atomicIntCtor = cp.methodRef(atomicIntClass, "<init>", "(I)V");
		MethodRefEntry atomicIntGet = cp.methodRef(atomicIntClass, "get", "()I");
		MethodRefEntry atomicIntGetAndSet = cp.methodRef(atomicIntClass, "getAndSet", "(I)I");

		ClassEntry latchClass = cp.classEntry("java/util/concurrent/CountDownLatch");
		MethodRefEntry latchCtor = cp.methodRef(latchClass, "<init>", "(I)V");
		MethodRefEntry latchAwait = cp.methodRef(latchClass, "await", "()V");
		MethodRefEntry latchCountDown = cp.methodRef(latchClass, "countDown", "()V");

		ClassEntry threadClass = cp.classEntry("java/lang/Thread");
		MethodRefEntry threadOfVirtual = cp.methodRef(threadClass, "ofVirtual",
				"()Ljava/lang/Thread$Builder$OfVirtual;");
		ClassEntry ofVirtualClass = cp.classEntry("java/lang/Thread$Builder$OfVirtual");
		InterfaceMethodRefEntry builderStart = cp.interfaceMethodRef(ofVirtualClass, "start",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;");

		ClassEntry threadLocalClass = cp.classEntry("java/lang/ThreadLocal");
		MethodRefEntry tlSet = cp.methodRef(threadLocalClass, "set", "(Ljava/lang/Object;)V");
		MethodRefEntry tlGet = cp.methodRef(threadLocalClass, "get", "()Ljava/lang/Object;");

		ClassEntry throwableClass = cp.classEntry("java/lang/Throwable");
		// An async body's error payload carries its trace when the uncaught report
		// records
		// the boundary in it (asyncAwaited): {EMARKER, t, cond, trace}.
		int errorPayloadLength = asyncAwaited != null ? 4 : 3;
		@Nullable MethodRefEntry throwableGetStackTrace = asyncAwaited != null
				? cp.methodRef(throwableClass, "getStackTrace", "()[Ljava/lang/StackTraceElement;") : null;
		@Nullable ClassEntry stackTraceArrayClass = asyncAwaited != null ? cp.classEntry("[Ljava/lang/StackTraceElement;") : null;
		ClassEntry runtimeExceptionClass = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry runtimeExceptionInit = cp.methodRef(runtimeExceptionClass, "<init>", "(Ljava/lang/String;)V");

		FieldRefEntry handoffField = cp.fieldRef(thisClass, HANDOFF_FIELD, "Ljava/lang/ThreadLocal;");
		java.lang.classfile.constantpool.MethodRefEntry condTake = java.util.Objects.requireNonNull(channel.condTake);
		java.lang.classfile.constantpool.MethodRefEntry condPut = java.util.Objects.requireNonNull(channel.condPut);

		FieldRefEntry fnField = cp.fieldRef(thisClass, FN_FIELD, "Ljava/lang/Object;");
		FieldRefEntry futureField = cp.fieldRef(thisClass, FUTURE_FIELD, "Ljava/lang/Object;");
		FieldRefEntry latchField = cp.fieldRef(thisClass, LATCH_FIELD, "Ljava/lang/Object;");

		MethodRefEntry invoke0 = cp.methodRef(thisClass, JvmRuntimeBuilder.dispatcherName(0, false),
				JvmRuntimeBuilder.dispatcherDesc(0, false));
		MethodRefEntry releaseHandoff = cp.methodRef(thisClass, RELEASE_HANDOFF_METHOD, RELEASE_HANDOFF_DESC);
		// Self-references: a pull stream's read resolves the thunk's answer through the
		// generic _await (a thunk may answer a future), and _drain_body reads through
		// _stream_read so one drain serves both stream modes.
		MethodRefEntry awaitSelf = cp.methodRef(thisClass, AWAIT_METHOD, AWAIT_DESC);
		MethodRefEntry streamReadSelf = cp.methodRef(thisClass, STREAM_READ_METHOD, UNARY_DESC);
		MethodRefEntry ivOfBytesSelf = cp.methodRef(thisClass, IV_OF_BYTES_METHOD, IV_OF_BYTES_DESC);
		ClassEntry byteArrayClass = cp.classEntry("[B");
		ClassEntry longArrayClass = cp.classEntry("[J");

		StringEntry sMarker = cp.stringEntry(SMARKER);
		StringEntry rMarker = cp.stringEntry(RMARKER);
		StringEntry eMarker = cp.stringEntry(EMARKER);
		@Nullable StringEntry vMarker = mvChannel != null ? cp.stringEntry(VMARKER) : null;
		StringEntry tStr = cp.stringEntry("T");
		StringEntry quote = cp.stringEntry("\"");

		List<AsyncMethod> methods = new ArrayList<>();

		// --- _release_handoff(): countDown the current thread's handoff latch, if any
		{
			MethodCode a = new MethodCode();
			MethodCode.Label done = a.newLabel();
			a.getstatic(handoffField);
			a.invokevirtual(tlGet); // [latch-or-null]
			a.dup();
			MethodCode.Label nonNull = a.newLabel();
			a.ifnonnull(nonNull);
			a.pop();
			a.goto_(done);
			a.labelBinding(nonNull);
			a.checkcast(latchClass);
			a.invokevirtual(latchCountDown);
			a.labelBinding(done);
			a.return_();
			methods.add(new AsyncMethod(cp.utf8Entry(RELEASE_HANDOFF_METHOD), cp.utf8Entry(RELEASE_HANDOFF_DESC), a));
		}

		// --- _async_run(fn): spawn the body on a virtual thread, eager-start handoff
		{
			MethodCode a = new MethodCode();
			// future (slot 1)
			a.new_(futureClass);
			a.dup();
			a.invokespecial(futureCtor);
			a.astore(1);
			// latch (slot 2)
			a.new_(latchClass);
			a.dup();
			a.loadConstant(1);
			a.invokespecial(latchCtor);
			a.astore(2);
			// runner (slot 3) = new Prog() with the three fields set
			a.new_(thisClass);
			a.dup();
			a.invokespecial(instanceInitRef);
			a.astore(3);
			a.aload(3);
			a.aload(0);
			a.putfield(fnField);
			a.aload(3);
			a.aload(1);
			a.putfield(futureField);
			a.aload(3);
			a.aload(2);
			a.putfield(latchField);
			// Thread.ofVirtual().start(runner)
			a.invokestatic(threadOfVirtual); // [builder]
			a.aload(3);
			a.invokeinterface(builderStart);
			a.pop();
			// latch.await() -- resumes at the body's first suspension or completion
			a.aload(2);
			a.invokevirtual(latchAwait);
			a.aload(1);
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(ASYNC_RUN_METHOD), cp.utf8Entry(ASYNC_RUN_DESC), a));
		}

		// --- run(): the async body on its virtual thread (Runnable protocol)
		AsyncMethod runMethod;
		{
			MethodCode a = new MethodCode();
			if (launcherRun != null) {
				// if (this._asyncLatch == null) { _main$run(this); return; }
				MethodCode.Label asyncBody = a.newLabel();
				a.aload(0);
				a.getfield(latchField);
				a.ifnonnull(asyncBody);
				a.aload(0);
				a.invokestatic(launcherRun);
				a.return_();
				a.labelBinding(asyncBody);
			}
			// _handoffTl.set(this._asyncLatch)
			a.getstatic(handoffField);
			a.aload(0);
			a.getfield(latchField);
			a.invokevirtual(tlSet);
			// try { future.complete(_invoke_0(fn)) }
			MethodCode.Label tryStart = a.newBoundLabel();
			a.aload(0);
			a.getfield(futureField);
			a.checkcast(futureClass);
			a.aload(0);
			a.getfield(fnField);
			a.iconst_0();
			a.invokestatic(invoke0); // [future, v]
			JvmTailBounce.unwrapRaw(a, cp, thisClass);
			if (mvChannel != null && vMarker != null) {
				// The channel holds the body's extra values the moment its thunk
				// returns (its tail settled them), on THIS thread: a body that answered
				// other than one value completes with {VMARKER, v, extras}.
				MethodCode.Label single = a.newLabel();
				a.astore(1);
				mvChannel.emitLoad(a);
				a.ifnull(single);
				a.loadConstant(3);
				a.anewarray(objectClass);
				a.dup();
				a.loadConstant(0);
				a.ldc(vMarker);
				a.aastore();
				a.dup();
				a.loadConstant(1);
				a.aload(1);
				a.aastore();
				a.dup();
				a.loadConstant(2);
				mvChannel.emitLoad(a);
				a.aastore();
				a.astore(1);
				a.labelBinding(single);
				a.aload(1); // [future, v-or-payload]
			}
			a.invokevirtual(futureComplete);
			a.pop();
			MethodCode.Label tryEnd = a.newBoundLabel();
			MethodCode.Label done = a.newLabel();
			a.goto_(done);
			// catch (Throwable t): future.complete({EMARKER, t, _condTake(t)}), and
			// t.getStackTrace() after them when the body recorded its boundary there
			MethodCode.Label handler = a.newBoundLabel();
			a.astore(1);
			a.aload(0);
			a.getfield(futureField);
			a.checkcast(futureClass);
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
			if (throwableGetStackTrace != null) {
				// The trace as the boundary left it, which every await puts back
				// (JvmUncaughtHandler): the condition is one object all of them rethrow.
				a.dup();
				a.loadConstant(3);
				a.aload(1);
				a.invokevirtual(throwableGetStackTrace);
				a.aastore();
			}
			// [future, payload]
			a.invokevirtual(futureComplete);
			a.pop();
			a.labelBinding(done);
			// latch.countDown() on both paths
			a.aload(0);
			a.getfield(latchField);
			a.checkcast(latchClass);
			a.invokevirtual(latchCountDown);
			a.return_();
			a.exceptionCatch(tryStart, tryEnd, handler, throwableClass);
			runMethod = new AsyncMethod(cp.utf8Entry("run"), cp.utf8Entry("()V"), a);
		}

		// --- _await(v): the generic resolver (flattening loop)
		{
			MethodCode a = new MethodCode();
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label notToken = a.newLabel();
			MethodCode.Label notFuture = a.newLabel();
			if (mvChannel != null) {
				// await is a multiple-value producer: one value unless the last
				// future of the chain settled with a {VMARKER, ...} payload.
				a.aconst_null();
				mvChannel.emitStore(a);
			}
			a.labelBinding(loop);
			// stream-read token {RMARKER, queue, state}?
			a.aload(0);
			a.instanceOf(objectArrayClass);
			a.ifeq(notToken);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.loadConstant(3);
			a.if_icmpne(notToken);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(0);
			a.aaload();
			a.ldc(rMarker);
			a.if_acmpne(notToken);
			// blocking take (the suspension point): release the handoff first
			a.invokestatic(releaseHandoff);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(queueClass);
			a.astore(1); // q
			a.aload(1);
			a.invokevirtual(queueTake);
			a.astore(2); // chunk
			MethodCode.Label notPill = a.newLabel();
			a.aload(2);
			a.ldc(sMarker);
			a.if_acmpne(notPill);
			// end of stream: re-enqueue the pill for other readers, yield nil
			a.aload(1);
			a.ldc(sMarker);
			a.invokevirtual(queueOffer);
			a.pop();
			a.aconst_null();
			a.areturn();
			a.labelBinding(notPill);
			if (mvChannel != null) {
				a.aconst_null();
				mvChannel.emitStore(a);
			}
			a.aload(2);
			a.astore(0);
			a.goto_(loop); // flatten the chunk
			a.labelBinding(notToken);
			// CompletableFuture?
			a.aload(0);
			a.instanceOf(futureClass);
			a.ifeq(notFuture);
			a.aload(0);
			a.checkcast(futureClass);
			a.astore(3); // f
			a.aload(3);
			a.invokevirtual(futureIsDone);
			MethodCode.Label joinIt = a.newLabel();
			a.ifne(joinIt);
			a.invokestatic(releaseHandoff);
			a.labelBinding(joinIt);
			a.aload(3);
			a.invokevirtual(futureJoin);
			a.astore(4); // r
			// the {EMARKER, t, cond[, trace]} error envelope
			MethodCode.Label plain = a.newLabel();
			a.aload(4);
			a.instanceOf(objectArrayClass);
			a.ifeq(plain);
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.loadConstant(errorPayloadLength);
			a.if_icmpne(plain);
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.loadConstant(0);
			a.aaload();
			a.ldc(eMarker);
			a.if_acmpne(plain);
			// {EMARKER, t, cond}: record the condition under t HERE (the awaiting
			// thread) and rethrow, so handler-case dispatches by type
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(throwableClass);
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.loadConstant(2);
			a.aaload();
			a.invokestatic(condPut);
			if (asyncAwaited != null) {
				// The uncaught report's hop: this await completes the boundary the body's
				// thunk recorded in the trace, put back as stored (JvmUncaughtHandler).
				a.dup();
				a.aload(4);
				a.checkcast(objectArrayClass);
				a.loadConstant(3);
				a.aaload();
				a.checkcast(java.util.Objects.requireNonNull(stackTraceArrayClass));
				a.invokestatic(asyncAwaited);
			}
			a.athrow();
			a.labelBinding(plain);
			if (mvChannel != null && vMarker != null) {
				// {VMARKER, primary, extras}: publish the extras, flatten the primary
				MethodCode.Label oneValue = a.newLabel();
				emitMarkerTest(a, objectArrayClass, vMarker, 4, oneValue);
				a.aload(4);
				a.checkcast(objectArrayClass);
				a.loadConstant(2);
				a.aaload();
				mvChannel.emitStore(a);
				a.aload(4);
				a.checkcast(objectArrayClass);
				a.loadConstant(1);
				a.aaload();
				a.astore(0);
				a.goto_(loop);
				a.labelBinding(oneValue);
				a.aconst_null();
				mvChannel.emitStore(a);
			}
			// flatten: v = r; loop (a plain value exits at the type checks above)
			a.aload(4);
			a.astore(0);
			a.goto_(loop);
			a.labelBinding(notFuture);
			a.aload(0);
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(AWAIT_METHOD), cp.utf8Entry(AWAIT_DESC), a));
		}

		// --- _futurep(v): CompletableFuture or a stream-read token
		{
			MethodCode a = new MethodCode();
			MethodCode.Label yes = a.newLabel();
			MethodCode.Label no = a.newLabel();
			a.aload(0);
			a.instanceOf(futureClass);
			a.ifne(yes);
			emitMarkerTest(a, objectArrayClass, rMarker, 0, no);
			a.labelBinding(yes);
			a.ldc(tStr);
			a.areturn();
			a.labelBinding(no);
			a.aconst_null();
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(FUTUREP_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _streamp(v)
		{
			MethodCode a = new MethodCode();
			MethodCode.Label no = a.newLabel();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, no);
			a.ldc(tStr);
			a.areturn();
			a.labelBinding(no);
			a.aconst_null();
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(STREAMP_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _make_stream(): {SMARKER, new LinkedBlockingQueue, new AtomicInteger(0)}
		{
			MethodCode a = new MethodCode();
			a.loadConstant(3);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(sMarker);
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.new_(queueClass);
			a.dup();
			a.invokespecial(queueCtor);
			a.aastore();
			a.dup();
			a.loadConstant(2);
			a.new_(atomicIntClass);
			a.dup();
			a.loadConstant(0);
			a.invokespecial(atomicIntCtor);
			a.aastore();
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(MAKE_STREAM_METHOD), cp.utf8Entry(MAKE_STREAM_DESC), a));
		}

		// --- _stream_new(readFn, closeFn): the PULL stream rontolisp::%stream-new
		// builds, {SMARKER, {readFn, closeFn}, AtomicInteger(0)}. Same Object[3] as a
		// buffered stream, with the thunk pair where the chunk queue would be, so every
		// consumer that only asks "is this a stream" (_streamp, the printer) is
		// untouched.
		{
			MethodCode a = new MethodCode();
			a.loadConstant(3);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(sMarker);
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.loadConstant(2);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.aload(0);
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(1);
			a.aastore();
			a.aastore();
			a.dup();
			a.loadConstant(2);
			a.new_(atomicIntClass);
			a.dup();
			a.loadConstant(0);
			a.invokespecial(atomicIntCtor);
			a.aastore();
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(STREAM_NEW_METHOD), cp.utf8Entry(STREAM_NEW_DESC), a));
		}

		// --- _stream_read(s): a buffered stream answers the {RMARKER, q, state} token
		// (the take happens at await); a PULL stream has nothing to defer to, so it runs
		// the read thunk here and answers a settled future -- resolving the thunk's
		// answer BEFORE the end-of-stream test, because a thunk that awaits answers a
		// future and a future wrapping nil is not nil. The first nil chunk runs the close
		// thunk once (the drain closes exactly once; a read past the end is nil).
		{
			MethodCode a = new MethodCode();
			MethodCode.Label bad = a.newLabel();
			MethodCode.Label pull = a.newLabel();
			MethodCode.Label drained = a.newLabel();
			MethodCode.Label settle = a.newLabel();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, bad);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.instanceOf(queueClass);
			a.ifeq(pull);
			a.loadConstant(3);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			a.ldc(rMarker);
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.aastore();
			a.dup();
			a.loadConstant(2);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(2);
			a.aaload();
			a.aastore();
			a.areturn();
			a.labelBinding(pull);
			// fns (slot 1), state (slot 2)
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(objectArrayClass);
			a.astore(1);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(2);
			a.aaload();
			a.checkcast(atomicIntClass);
			a.astore(2);
			a.aload(2);
			a.invokevirtual(atomicIntGet);
			a.ifne(drained);
			// chunk (slot 3) = _await(_invoke_0(readFn, 0))
			a.aload(1);
			a.loadConstant(0);
			a.aaload();
			a.iconst_0();
			a.invokestatic(invoke0);
			JvmTailBounce.unwrapRaw(a, cp, thisClass);
			a.invokestatic(awaitSelf);
			a.astore(3);
			a.aload(3);
			a.ifnonnull(settle);
			a.aload(2);
			a.loadConstant(1);
			a.invokevirtual(atomicIntGetAndSet);
			a.ifne(settle);
			a.aload(1);
			a.loadConstant(1);
			a.aaload();
			a.iconst_0();
			a.invokestatic(invoke0);
			// The close thunk's answer is dropped, but a bounce in it is a call still to
			// make (JvmTailBounce).
			JvmTailBounce.unwrapRaw(a, cp, thisClass);
			a.pop();
			a.labelBinding(settle);
			a.aload(3);
			a.invokestatic(futureCompleted);
			a.areturn();
			a.labelBinding(drained);
			a.aconst_null();
			a.invokestatic(futureCompleted);
			a.areturn();
			a.labelBinding(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-read expects a stream");
			methods.add(new AsyncMethod(cp.utf8Entry(STREAM_READ_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _stream_write(s, chunk)
		{
			MethodCode a = new MethodCode();
			MethodCode.Label bad = a.newLabel();
			MethodCode.Label nilChunk = a.newLabel();
			MethodCode.Label closed = a.newLabel();
			MethodCode.Label noWriteEnd = a.newLabel();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, bad);
			// A pull stream has no buffer to append to -- its chunks come from its read
			// thunk -- so the refusal is its own, not "the stream is closed".
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.instanceOf(queueClass);
			a.ifeq(noWriteEnd);
			a.aload(1);
			a.ifnull(nilChunk);
			// closed?
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(2);
			a.aaload();
			a.checkcast(atomicIntClass);
			a.invokevirtual(atomicIntGet);
			a.ifne(closed);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.checkcast(queueClass);
			a.aload(1);
			a.invokevirtual(queueOffer);
			a.pop();
			// accepted immediately: a settled future of nil
			a.aconst_null();
			a.invokestatic(futureCompleted);
			a.areturn();
			a.labelBinding(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write expects a stream");
			a.labelBinding(nilChunk);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write: a chunk must not be nil");
			a.labelBinding(closed);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write: the stream is closed");
			a.labelBinding(noWriteEnd);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write: the stream has no write end");
			methods.add(new AsyncMethod(cp.utf8Entry(STREAM_WRITE_METHOD), cp.utf8Entry(STREAM_WRITE_DESC), a));
		}

		// --- _stream_close(s): end the stream once -- the poison pill for a buffered
		// stream, the close thunk for a pull one
		{
			MethodCode a = new MethodCode();
			MethodCode.Label bad = a.newLabel();
			MethodCode.Label already = a.newLabel();
			MethodCode.Label pull = a.newLabel();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, bad);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(2);
			a.aaload();
			a.checkcast(atomicIntClass);
			a.loadConstant(1);
			a.invokevirtual(atomicIntGetAndSet);
			a.ifne(already);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.astore(1);
			a.aload(1);
			a.instanceOf(queueClass);
			a.ifeq(pull);
			a.aload(1);
			a.checkcast(queueClass);
			a.ldc(sMarker);
			a.invokevirtual(queueOffer);
			a.pop();
			a.goto_(already);
			a.labelBinding(pull);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.loadConstant(1);
			a.aaload();
			a.iconst_0();
			a.invokestatic(invoke0);
			JvmTailBounce.unwrapRaw(a, cp, thisClass);
			a.pop();
			a.labelBinding(already);
			a.aconst_null();
			a.areturn();
			a.labelBinding(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-close expects a stream");
			methods.add(new AsyncMethod(cp.utf8Entry(STREAM_CLOSE_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _iv_of_bytes(byte[]): raw bytes -> byte[]{8, e0, ...}, the packed
		// (unsigned-byte 8) vector every HTTP body stream answers its chunks as (the
		// _iv* runtime's representation, so aref/length dispatch on it as on any
		// make-array'd octet vector).
		{
			MethodRefEntry arraycopy = cp.methodRef(cp.classEntry("java/lang/System"), "arraycopy",
					"(Ljava/lang/Object;ILjava/lang/Object;II)V");
			MethodCode a = new MethodCode();
			// slots: 0 bytes, 1 out
			a.aload(0);
			a.arraylength();
			a.loadConstant(1);
			a.iadd();
			a.newarray(TypeKind.BYTE);
			a.astore(1);
			a.aload(1);
			a.loadConstant(0);
			a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			a.bastore(); // out[0] = 8 (the width header)
			// System.arraycopy(bytes, 0, out, 1, bytes.length)
			a.aload(0);
			a.loadConstant(0);
			a.aload(1);
			a.loadConstant(1);
			a.aload(0);
			a.arraylength();
			a.invokestatic(arraycopy);
			a.aload(1);
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(IV_OF_BYTES_METHOD), cp.utf8Entry(IV_OF_BYTES_DESC), a));
		}

		// --- _drain_body(v): for http-handler response marshaling -- a stream drains to
		// ONE body value the transport writes as it is: OCTET chunks (every HTTP body
		// stream's, so a proxied fetch reply goes out byte-exact) to one long[] octet
		// vector, string chunks (a guest make-stream) to their quoted concatenation; a
		// stream mixing the two kinds is refused, like http-server.lisp's %http-drain.
		// Any other value passes through. The chunks come through _stream_read + _await
		// rather than off the queue directly, which is what makes ONE drain serve both
		// stream modes (and leaves the buffered one where it was: that pair takes the
		// chunk, re-enqueues the pill at the end and answers nil).
		{
			ClassEntry baosClass = cp.classEntry("java/io/ByteArrayOutputStream");
			MethodRefEntry baosInit = cp.methodRef(baosClass, "<init>", "()V");
			MethodRefEntry baosWrite = cp.methodRef(baosClass, "write", "(I)V");
			MethodRefEntry baosWriteBytes = cp.methodRef(baosClass, "writeBytes", "([B)V");
			MethodRefEntry baosWriteRange = cp.methodRef(baosClass, "write", "([BII)V");
			MethodRefEntry baosToByteArray = cp.methodRef(baosClass, "toByteArray", "()[B");
			MethodRefEntry baosToString = cp.methodRef(baosClass, "toString",
					"(Ljava/nio/charset/Charset;)Ljava/lang/String;");
			ClassEntry charsetsClass = cp.classEntry("java/nio/charset/StandardCharsets");
			FieldRefEntry utf8Field = cp.fieldRef(charsetsClass, "UTF_8", "Ljava/nio/charset/Charset;");
			MethodRefEntry stringGetBytes = cp.methodRef(stringClass, "getBytes", "(Ljava/nio/charset/Charset;)[B");
			MethodCode a = new MethodCode();
			// slots: 0 v, 1 sink, 2 chunk, 3 octetsSeen, 4 textSeen, 5 i, 6 iv
			MethodCode.Label passThrough = a.newLabel();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, passThrough);
			a.new_(baosClass);
			a.dup();
			a.invokespecial(baosInit);
			a.astore(1);
			a.loadConstant(0);
			a.istore(3);
			a.loadConstant(0);
			a.istore(4);
			MethodCode.Label loop = a.newLabel();
			MethodCode.Label done = a.newLabel();
			MethodCode.Label notIv = a.newLabel();
			MethodCode.Label mixed = a.newLabel();
			a.labelBinding(loop);
			a.aload(0);
			a.invokestatic(streamReadSelf);
			a.invokestatic(awaitSelf);
			a.astore(2); // chunk
			a.aload(2);
			a.ifnull(done);
			// an octet chunk, byte[]{8, e0, ...}: the elements after the width header, in
			// one write
			MethodCode.Label notOctets = a.newLabel();
			a.aload(2);
			a.instanceOf(byteArrayClass);
			a.ifeq(notOctets);
			a.loadConstant(1);
			a.istore(3);
			a.aload(1);
			a.aload(2);
			a.checkcast(byteArrayClass);
			a.dup(); // [sink, chunk, chunk]
			a.arraylength();
			a.loadConstant(1);
			a.isub();
			a.loadConstant(1);
			a.swap(); // [sink, chunk, 1, len-1]
			a.invokevirtual(baosWriteRange);
			a.goto_(loop);
			a.labelBinding(notOctets);
			a.aload(2);
			a.instanceOf(longArrayClass);
			a.ifeq(notIv);
			// a wider packed chunk: every element after the width header, one write each
			a.loadConstant(1);
			a.istore(3);
			a.aload(2);
			a.checkcast(longArrayClass);
			a.astore(6);
			a.loadConstant(1);
			a.istore(5);
			MethodCode.Label ivLoop = a.newLabel();
			MethodCode.Label ivDone = a.newLabel();
			a.labelBinding(ivLoop);
			a.iload(5);
			a.aload(6);
			a.arraylength();
			a.if_icmpge(ivDone);
			a.aload(1);
			a.aload(6);
			a.iload(5);
			a.laload();
			a.l2i();
			a.invokevirtual(baosWrite);
			a.iinc(5, 1);
			a.goto_(ivLoop);
			a.labelBinding(ivDone);
			a.goto_(loop);
			// a string chunk: its raw text, UTF-8 encoded
			a.labelBinding(notIv);
			a.loadConstant(1);
			a.istore(4);
			a.aload(1);
			a.aload(2);
			a.checkcast(stringClass);
			a.dup(); // [sink, chunk, chunk]
			a.invokevirtual(stringLength); // [sink, chunk, len]
			a.loadConstant(1);
			a.isub();
			a.loadConstant(1);
			a.swap(); // [sink, chunk, 1, len-1]
			a.invokevirtual(stringSubstring); // [sink, raw]
			a.getstatic(utf8Field);
			a.invokevirtual(stringGetBytes); // [sink, bytes]
			a.invokevirtual(baosWriteBytes);
			a.goto_(loop);
			a.labelBinding(done);
			a.iload(3);
			a.iload(4);
			a.iand();
			a.ifne(mixed);
			MethodCode.Label textResult = a.newLabel();
			a.iload(3);
			a.ifeq(textResult);
			// octets: one byte[] vector, written by the transport as it is
			a.aload(1);
			a.invokevirtual(baosToByteArray);
			a.invokestatic(ivOfBytesSelf);
			a.areturn();
			// text (or an empty stream): the quoted concatenation
			a.labelBinding(textResult);
			a.ldc(quote);
			a.aload(1);
			a.getstatic(utf8Field);
			a.invokevirtual(baosToString);
			a.invokevirtual(stringConcat);
			a.ldc(quote);
			a.invokevirtual(stringConcat);
			a.areturn();
			a.labelBinding(mixed);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit,
					"http-handler: a stream response body mixes string and octet chunks");
			a.labelBinding(passThrough);
			a.aload(0);
			a.areturn();
			methods.add(new AsyncMethod(cp.utf8Entry(DRAIN_BODY_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		// --- _wait_for(ms): a future settling to nil after ms milliseconds, via
		// CompletableFuture.completeOnTimeout (the JDK's shared delayer thread)
		{
			MethodRefEntry longValue = cp.methodRef(cp.classEntry("java/lang/Long"), "longValue", "()J");
			ClassEntry longBoxClass = cp.classEntry("java/lang/Long");
			ClassEntry timeUnitClass = cp.classEntry("java/util/concurrent/TimeUnit");
			FieldRefEntry millisUnit = cp.fieldRef(timeUnitClass, "MILLISECONDS", "Ljava/util/concurrent/TimeUnit;");
			MethodRefEntry completeOnTimeout = cp.methodRef(futureClass, "completeOnTimeout",
					"(Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/CompletableFuture;");
			MethodCode a = new MethodCode();
			MethodCode.Label bad = a.newLabel();
			a.aload(0);
			a.instanceOf(longBoxClass);
			a.ifeq(bad);
			a.aload(0);
			a.checkcast(longBoxClass);
			a.invokevirtual(longValue); // [J]
			a.lstore(1); // ms in slots 1-2; the bad path is reached stack-empty
			a.lload(1);
			a.lconst_0();
			a.lcmp();
			a.iflt(bad); // []
			a.new_(futureClass);
			a.dup();
			a.invokespecial(futureCtor); // [cf]
			a.aconst_null(); // [cf, nil]
			a.lload(1); // [cf, nil, J]
			a.getstatic(millisUnit); // [cf, nil, J, unit]
			a.invokevirtual(completeOnTimeout); // [cf]
			a.areturn();
			a.labelBinding(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit,
					"wait-for expects a non-negative integer of milliseconds");
			methods.add(new AsyncMethod(cp.utf8Entry(WAIT_FOR_METHOD), cp.utf8Entry(UNARY_DESC), a));
		}

		return new AsyncRuntime(methods, runMethod, true);
	}

	/**
	 * Builds {@code _octetsToString} ({@link #OCTETS_PACKED_METHOD}), the native half of
	 * {@code rontolisp::%octets-to-string}: the packed octet vector's bytes transcoded
	 * arm for arm by the prelude's lenient rule -- a byte that leads no sequence, one a
	 * truncated tail cuts short, and a four-byte form past U+10FFFF are their own
	 * characters, and a lead byte takes its continuation bytes whatever their high bits
	 * (the prelude's arms, which the interpreter's {@code Environment} mirrors) -- and
	 * framed in the storage quotes a compiled string carries. On valid UTF-8 every arm
	 * answers what a strict decoder answers, so there is no strict pass in front.
	 *
	 * <p>
	 * The units are COUNTED first, so the result is built once at its exact size: when
	 * every unit is one octet the octets are the string's Latin-1 content and are copied
	 * as they are; when every character fits Latin-1 they are narrowed into a
	 * {@code byte[]}; otherwise they go into a {@code char[]}. Until 2026-09-26 the
	 * vector was a {@code long[]}, copied into a {@code byte[]}, decoded into a
	 * {@code CharBuffer} (and, when that refused, into a {@code StringBuilder}) and
	 * framed by two concatenations -- a 256 MiB body held 1.2 GB of intermediates at the
	 * decode. A value that is not an octet vector ({@code byte[]} whose slot 0 is
	 * {@link JvmIntArrayRuntimeBuilder#OCTET_TAG}) answers {@code null}, and the caller's
	 * per-byte loop (which walks the value through the generic {@code aref}) decides.
	 * @param cp the constant pool
	 * @return the helper body
	 */
	static AsyncMethod buildOctetsToString(ConstantPool cp) {
		ClassEntry byteArrayClass = cp.classEntry("[B");
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		MethodRefEntry stringFromBytes = cp.methodRef(stringClass, "<init>", "([BLjava/nio/charset/Charset;)V");
		MethodRefEntry stringFromChars = cp.methodRef(stringClass, "<init>", "([C)V");
		FieldRefEntry latin1 = cp.fieldRef(cp.classEntry("java/nio/charset/StandardCharsets"), "ISO_8859_1",
				"Ljava/nio/charset/Charset;");
		MethodRefEntry arraycopy = cp.methodRef(cp.classEntry("java/lang/System"), "arraycopy",
				"(Ljava/lang/Object;ILjava/lang/Object;II)V");
		ClassEntry characterClass = cp.classEntry("java/lang/Character");
		MethodRefEntry highSurrogate = cp.methodRef(characterClass, "highSurrogate", "(I)C");
		MethodRefEntry lowSurrogate = cp.methodRef(characterClass, "lowSurrogate", "(I)C");

		MethodCode a = new MethodCode();
		// slots: 0 v, 1 bytes (the vector: tag, then the octets), 2 n (its length), 3 i,
		// 4 units, 5 every code point OR'd, 6 b, 7 cp, 8 adv, 9 four-byte code point,
		// 10 k, 11 out
		int bytesSlot = 1, nSlot = 2, iSlot = 3, unitsSlot = 4, orSlot = 5, bSlot = 6, cpSlot = 7, advSlot = 8,
				cp4Slot = 9, kSlot = 10, outSlot = 11;
		Unit unit = new Unit(bytesSlot, iSlot, nSlot, bSlot, cpSlot, advSlot, cp4Slot);
		MethodCode.Label none = a.newLabel();
		a.aload(0);
		a.instanceOf(byteArrayClass);
		a.ifeq(none);
		a.aload(0);
		a.checkcast(byteArrayClass);
		a.astore(bytesSlot);
		a.aload(bytesSlot);
		a.arraylength();
		a.istore(nSlot);
		// Refuse an empty array and a quantized matrix (another byte[], whose slot 0 is
		// its format code) rather than reading a header that is not the tag.
		a.iload(nSlot);
		a.loadConstant(1);
		a.if_icmplt(none);
		a.aload(bytesSlot);
		a.loadConstant(0);
		a.baload();
		a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
		a.if_icmpne(none);
		// Count: units (UTF-16 code units, a supplementary character two) and the OR of
		// every code point.
		a.loadConstant(0);
		a.istore(unitsSlot);
		a.loadConstant(0);
		a.istore(orSlot);
		MethodCode.Label counted = a.newLabel();
		unit.emitLoop(a, counted, () -> {
			a.iload(orSlot);
			a.iload(cpSlot);
			a.ior();
			a.istore(orSlot);
			a.iinc(unitsSlot, 1);
			MethodCode.Label bmp = a.newLabel();
			a.iload(cpSlot);
			a.loadConstant(16);
			a.ishr();
			a.ifeq(bmp);
			a.iinc(unitsSlot, 1);
			a.labelBinding(bmp);
		});
		a.labelBinding(counted);
		// Every unit one octet: the octets ARE the Latin-1 content -- one copy between
		// the frame quotes.
		MethodCode.Label notVerbatim = a.newLabel();
		a.iload(unitsSlot);
		a.iload(nSlot);
		a.loadConstant(1);
		a.isub();
		a.if_icmpne(notVerbatim);
		a.iload(nSlot);
		a.loadConstant(1);
		a.iadd();
		a.newarray(TypeKind.BYTE);
		a.astore(outSlot);
		a.aload(bytesSlot);
		a.loadConstant(1);
		a.aload(outSlot);
		a.loadConstant(1);
		a.iload(unitsSlot);
		a.invokestatic(arraycopy);
		MethodCode.Label latin1Framed = a.newLabel();
		a.goto_(latin1Framed);
		a.labelBinding(notVerbatim);
		MethodCode.Label wide = a.newLabel();
		a.iload(orSlot);
		a.loadConstant(0xFF);
		a.if_icmpgt(wide);
		// Every character Latin-1: narrowed into a byte[].
		a.iload(unitsSlot);
		a.loadConstant(2);
		a.iadd();
		a.newarray(TypeKind.BYTE);
		a.astore(outSlot);
		a.loadConstant(1);
		a.istore(kSlot);
		unit.emitLoop(a, latin1Framed, () -> {
			a.aload(outSlot);
			a.checkcast(byteArrayClass);
			a.iload(kSlot);
			a.iload(cpSlot);
			a.bastore();
			a.iinc(kSlot, 1);
		});
		// out[0] = out[last] = '"'; return new String(out, ISO_8859_1)
		a.labelBinding(latin1Framed);
		a.aload(outSlot);
		a.checkcast(byteArrayClass);
		a.loadConstant(0);
		a.loadConstant('"');
		a.bastore();
		a.aload(outSlot);
		a.checkcast(byteArrayClass);
		a.dup();
		a.arraylength();
		a.loadConstant(1);
		a.isub();
		a.loadConstant('"');
		a.bastore();
		a.new_(stringClass);
		a.dup();
		a.aload(outSlot);
		a.getstatic(latin1);
		a.invokespecial(stringFromBytes);
		a.areturn();
		// Otherwise a char[], a supplementary character as its surrogate pair.
		a.labelBinding(wide);
		a.iload(unitsSlot);
		a.loadConstant(2);
		a.iadd();
		a.newarray(TypeKind.CHAR);
		a.astore(outSlot);
		a.loadConstant(1);
		a.istore(kSlot);
		MethodCode.Label wideDone = a.newLabel();
		unit.emitLoop(a, wideDone, () -> {
			MethodCode.Label bmp = a.newLabel();
			MethodCode.Label stored = a.newLabel();
			a.iload(cpSlot);
			a.loadConstant(16);
			a.ishr();
			a.ifeq(bmp);
			a.aload(outSlot);
			a.checkcast(cp.classEntry("[C"));
			a.iload(kSlot);
			a.iload(cpSlot);
			a.invokestatic(highSurrogate);
			a.castore();
			a.iinc(kSlot, 1);
			a.aload(outSlot);
			a.checkcast(cp.classEntry("[C"));
			a.iload(kSlot);
			a.iload(cpSlot);
			a.invokestatic(lowSurrogate);
			a.castore();
			a.goto_(stored);
			a.labelBinding(bmp);
			a.aload(outSlot);
			a.checkcast(cp.classEntry("[C"));
			a.iload(kSlot);
			a.iload(cpSlot);
			a.castore();
			a.labelBinding(stored);
			a.iinc(kSlot, 1);
		});
		a.labelBinding(wideDone);
		a.aload(outSlot);
		a.checkcast(cp.classEntry("[C"));
		a.loadConstant(0);
		a.loadConstant('"');
		a.castore();
		a.aload(outSlot);
		a.checkcast(cp.classEntry("[C"));
		a.dup();
		a.arraylength();
		a.loadConstant(1);
		a.isub();
		a.loadConstant('"');
		a.castore();
		a.new_(stringClass);
		a.dup();
		a.aload(outSlot);
		a.checkcast(cp.classEntry("[C"));
		a.invokespecial(stringFromChars);
		a.areturn();
		a.labelBinding(none);
		a.aconst_null();
		a.areturn();
		return new AsyncMethod(cp.utf8Entry(OCTETS_PACKED_METHOD), cp.utf8Entry(UNARY_DESC), a);
	}

	/**
	 * The lenient rule's step over the octets in {@code bytes[1..n)}: the code point of
	 * the unit at {@code i} into {@code cp} and its length in octets into {@code adv}.
	 * Emitted once per pass of {@code _octetsToString}'s decode, which differ only in
	 * what they do with each unit.
	 */
	private record Unit(int bytesSlot, int iSlot, int nSlot, int bSlot, int cpSlot, int advSlot, int cp4Slot) {

		/**
		 * Emits {@code for (i = 1; i < n; i += adv) { <step>; body }}, leaving the loop
		 * at {@code done}.
		 * @param a the method being built
		 * @param done the label past the loop
		 * @param body what each unit does, with its code point in {@code cpSlot}
		 */
		void emitLoop(MethodCode a, MethodCode.Label done, Runnable body) {
			a.loadConstant(1);
			a.istore(this.iSlot);
			MethodCode.Label loop = a.newLabel();
			a.labelBinding(loop);
			a.iload(this.iSlot);
			a.iload(this.nSlot);
			a.if_icmpge(done);
			emitStep(a);
			body.run();
			a.iload(this.iSlot);
			a.iload(this.advSlot);
			a.iadd();
			a.istore(this.iSlot);
			a.goto_(loop);
		}

		private void emitStep(MethodCode a) {
			MethodCode.Label emit = a.newLabel();
			// b = bytes[i] & 0xFF; by default it is its own character, one octet long.
			a.aload(this.bytesSlot);
			a.iload(this.iSlot);
			a.baload();
			a.loadConstant(0xFF);
			a.iand();
			a.dup();
			a.istore(this.bSlot);
			a.istore(this.cpSlot);
			a.loadConstant(1);
			a.istore(this.advSlot);
			// b < 0xC0: ASCII, or a continuation byte that leads nothing.
			a.iload(this.bSlot);
			a.loadConstant(0xC0);
			a.if_icmplt(emit);
			MethodCode.Label notTwo = a.newLabel();
			a.iload(this.bSlot);
			a.loadConstant(0xE0);
			a.if_icmpge(notTwo);
			emitLeadTakes(a, 2, 0x1F, this.bytesSlot, this.iSlot, this.nSlot, this.bSlot, emit);
			a.istore(this.cpSlot);
			a.loadConstant(2);
			a.istore(this.advSlot);
			a.goto_(emit);
			a.labelBinding(notTwo);
			MethodCode.Label notThree = a.newLabel();
			a.iload(this.bSlot);
			a.loadConstant(0xF0);
			a.if_icmpge(notThree);
			emitLeadTakes(a, 3, 0x0F, this.bytesSlot, this.iSlot, this.nSlot, this.bSlot, emit);
			a.istore(this.cpSlot);
			a.loadConstant(3);
			a.istore(this.advSlot);
			a.goto_(emit);
			a.labelBinding(notThree);
			a.iload(this.bSlot);
			a.loadConstant(0xF8);
			a.if_icmpge(emit);
			emitLeadTakes(a, 4, 0x07, this.bytesSlot, this.iSlot, this.nSlot, this.bSlot, emit);
			a.istore(this.cp4Slot);
			// Past U+10FFFF (cp >> 16 > 0x10) the lead byte stays its own character.
			a.iload(this.cp4Slot);
			a.loadConstant(16);
			a.ishr();
			a.loadConstant(0x10);
			a.if_icmpgt(emit);
			a.iload(this.cp4Slot);
			a.istore(this.cpSlot);
			a.loadConstant(4);
			a.istore(this.advSlot);
			a.labelBinding(emit);
		}

	}

	/**
	 * Emits the code point a {@code length}-byte lead at {@code bytes[i]} assembles --
	 * {@code (b & leadMask) << 6 * (length - 1)} OR'd with the low six bits of each byte
	 * after it -- leaving it on the stack, or branches to {@code short} (with nothing on
	 * the stack) when the vector ends before the sequence does.
	 */
	private static void emitLeadTakes(MethodCode a, int length, int leadMask, int bytesSlot, int iSlot, int nSlot,
			int bSlot, MethodCode.Label shortLabel) {
		a.iload(iSlot);
		a.loadConstant(length - 1);
		a.iadd();
		a.iload(nSlot);
		a.if_icmpge(shortLabel);
		a.iload(bSlot);
		a.loadConstant(leadMask);
		a.iand();
		for (int k = 1; k < length; k++) {
			a.loadConstant(6);
			a.ishl();
			a.aload(bytesSlot);
			a.iload(iSlot);
			a.loadConstant(k);
			a.iadd();
			a.baload();
			a.loadConstant(0x3F);
			a.iand();
			a.ior();
		}
	}

	/**
	 * Emits "is local {@code slot} an {@code Object[3]} whose head is {@code marker}",
	 * branching to {@code noLabel} when it is not (falls through when it is).
	 */
	private static void emitMarkerTest(MethodCode a, ClassEntry objectArrayClass, StringEntry marker, int slot,
			MethodCode.Label noLabel) {
		a.aload(slot);
		a.instanceOf(objectArrayClass);
		a.ifeq(noLabel);
		a.aload(slot);
		a.checkcast(objectArrayClass);
		a.arraylength();
		a.loadConstant(3);
		a.if_icmpne(noLabel);
		a.aload(slot);
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.ldc(marker);
		a.if_acmpne(noLabel);
	}

	/** Emits {@code throw new RuntimeException(message)}. */
	private static void emitThrow(MethodCode a, ConstantPool cp, ClassEntry runtimeExceptionClass,
			MethodRefEntry runtimeExceptionInit, String message) {
		StringEntry msg = cp.stringEntry(message);
		a.new_(runtimeExceptionClass);
		a.dup();
		a.ldc(msg);
		a.invokespecial(runtimeExceptionInit);
		a.athrow();
	}

}
