package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
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
 * with {@code {EMARKER, throwable, condition}} and {@code _await} re-sets the condition
 * on the awaiting thread before rethrowing -- {@code handler-case} around the await then
 * dispatches by type exactly like a same-thread signal. A program whose uncaught report
 * records async boundaries adds the throwable's trace as the body left it, which each
 * await puts back ({@link JvmUncaughtHandler});</li>
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

	/** A ready-to-emit method body (optionally with an exception table). */
	record AsyncMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			List<int[]> exceptionTable) {
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
	static AsyncRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, JvmLispCompiler.ConditionChannel channel,
			MethodrefConstant instanceInitRef, MethodrefConstant longValueOf, MethodrefConstant stringLength,
			MethodrefConstant stringSubstring, MethodrefConstant stringConcat, @Nullable MethodrefConstant launcherRun,
			@Nullable JvmMvChannel mvChannel, @Nullable MethodrefConstant asyncAwaited) {
		// --- shared class/method references ---
		ClassConstant futureClass = cp.addClass(cp.addUtf8("java/util/concurrent/CompletableFuture"));
		MethodrefConstant futureCtor = cp.addMethodref(futureClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant futureJoin = cp.addMethodref(futureClass,
				cp.addNameAndType(cp.addUtf8("join"), cp.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant futureIsDone = cp.addMethodref(futureClass,
				cp.addNameAndType(cp.addUtf8("isDone"), cp.addUtf8("()Z")));
		MethodrefConstant futureComplete = cp.addMethodref(futureClass,
				cp.addNameAndType(cp.addUtf8("complete"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant futureCompleted = cp.addMethodref(futureClass,
				cp.addNameAndType(cp.addUtf8("completedFuture"),
						cp.addUtf8("(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;")));

		ClassConstant queueClass = cp.addClass(cp.addUtf8("java/util/concurrent/LinkedBlockingQueue"));
		MethodrefConstant queueCtor = cp.addMethodref(queueClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant queueOffer = cp.addMethodref(queueClass,
				cp.addNameAndType(cp.addUtf8("offer"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant queueTake = cp.addMethodref(queueClass,
				cp.addNameAndType(cp.addUtf8("take"), cp.addUtf8("()Ljava/lang/Object;")));

		ClassConstant atomicIntClass = cp.addClass(cp.addUtf8("java/util/concurrent/atomic/AtomicInteger"));
		MethodrefConstant atomicIntCtor = cp.addMethodref(atomicIntClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(I)V")));
		MethodrefConstant atomicIntGet = cp.addMethodref(atomicIntClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()I")));
		MethodrefConstant atomicIntGetAndSet = cp.addMethodref(atomicIntClass,
				cp.addNameAndType(cp.addUtf8("getAndSet"), cp.addUtf8("(I)I")));

		ClassConstant latchClass = cp.addClass(cp.addUtf8("java/util/concurrent/CountDownLatch"));
		MethodrefConstant latchCtor = cp.addMethodref(latchClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(I)V")));
		MethodrefConstant latchAwait = cp.addMethodref(latchClass,
				cp.addNameAndType(cp.addUtf8("await"), cp.addUtf8("()V")));
		MethodrefConstant latchCountDown = cp.addMethodref(latchClass,
				cp.addNameAndType(cp.addUtf8("countDown"), cp.addUtf8("()V")));

		ClassConstant threadClass = cp.addClass(cp.addUtf8("java/lang/Thread"));
		MethodrefConstant threadOfVirtual = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("ofVirtual"), cp.addUtf8("()Ljava/lang/Thread$Builder$OfVirtual;")));
		ClassConstant ofVirtualClass = cp.addClass(cp.addUtf8("java/lang/Thread$Builder$OfVirtual"));
		MethodrefConstant builderStart = cp.addInterfaceMethodref(ofVirtualClass,
				cp.addNameAndType(cp.addUtf8("start"), cp.addUtf8("(Ljava/lang/Runnable;)Ljava/lang/Thread;")));

		ClassConstant threadLocalClass = cp.addClass(cp.addUtf8("java/lang/ThreadLocal"));
		MethodrefConstant tlSet = cp.addMethodref(threadLocalClass,
				cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(Ljava/lang/Object;)V")));
		MethodrefConstant tlGet = cp.addMethodref(threadLocalClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()Ljava/lang/Object;")));

		ClassConstant throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		// An async body's error payload carries its trace when the uncaught report
		// records
		// the boundary in it (asyncAwaited): {EMARKER, t, cond, trace}.
		int errorPayloadLength = asyncAwaited != null ? 4 : 3;
		@Nullable MethodrefConstant throwableGetStackTrace = asyncAwaited != null
				? cp.addMethodref(throwableClass,
						cp.addNameAndType(cp.addUtf8("getStackTrace"), cp.addUtf8("()[Ljava/lang/StackTraceElement;")))
				: null;
		@Nullable ClassConstant stackTraceArrayClass = asyncAwaited != null
				? cp.addClass(cp.addUtf8("[Ljava/lang/StackTraceElement;")) : null;
		ClassConstant runtimeExceptionClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant runtimeExceptionInit = cp.addMethodref(runtimeExceptionClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));

		ConstantPool.FieldrefConstant handoffField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(HANDOFF_FIELD), cp.addUtf8("Ljava/lang/ThreadLocal;")));
		ConstantPool.FieldrefConstant condTlField = java.util.Objects.requireNonNull(channel.condTlField);

		ConstantPool.FieldrefConstant fnField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(FN_FIELD), cp.addUtf8("Ljava/lang/Object;")));
		ConstantPool.FieldrefConstant futureField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(FUTURE_FIELD), cp.addUtf8("Ljava/lang/Object;")));
		ConstantPool.FieldrefConstant latchField = cp.addFieldref(thisClass,
				cp.addNameAndType(cp.addUtf8(LATCH_FIELD), cp.addUtf8("Ljava/lang/Object;")));

		MethodrefConstant invoke0 = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8("_invoke_0"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant releaseHandoff = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(RELEASE_HANDOFF_METHOD), cp.addUtf8(RELEASE_HANDOFF_DESC)));
		// Self-references: a pull stream's read resolves the thunk's answer through the
		// generic _await (a thunk may answer a future), and _drain_body reads through
		// _stream_read so one drain serves both stream modes.
		MethodrefConstant awaitSelf = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(AWAIT_METHOD), cp.addUtf8(AWAIT_DESC)));
		MethodrefConstant streamReadSelf = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(STREAM_READ_METHOD), cp.addUtf8(UNARY_DESC)));
		MethodrefConstant ivOfBytesSelf = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(IV_OF_BYTES_METHOD), cp.addUtf8(IV_OF_BYTES_DESC)));
		ClassConstant byteArrayClass = cp.addClass(cp.addUtf8("[B"));
		ClassConstant longArrayClass = cp.addClass(cp.addUtf8("[J"));

		ConstantPool.StringConstant sMarker = cp.addString(SMARKER);
		ConstantPool.StringConstant rMarker = cp.addString(RMARKER);
		ConstantPool.StringConstant eMarker = cp.addString(EMARKER);
		ConstantPool.@Nullable StringConstant vMarker = mvChannel != null ? cp.addString(VMARKER) : null;
		ConstantPool.StringConstant tStr = cp.addString("T");
		ConstantPool.StringConstant quote = cp.addString("\"");

		List<AsyncMethod> methods = new ArrayList<>();

		// --- _release_handoff(): countDown the current thread's handoff latch, if any
		{
			Asm a = new Asm();
			int done = a.label();
			a.op(Opcode.GETSTATIC);
			a.u2(handoffField.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(tlGet.index()); // [latch-or-null]
			a.op(Opcode.DUP);
			int nonNull = a.label();
			a.branch(Opcode.IFNONNULL, nonNull);
			a.op(Opcode.POP);
			a.branch(Opcode.GOTO, done);
			a.bind(nonNull);
			a.checkcast(latchClass);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(latchCountDown.index());
			a.bind(done);
			a.op(Opcode.RETURN);
			methods.add(new AsyncMethod(cp.addUtf8(RELEASE_HANDOFF_METHOD), cp.addUtf8(RELEASE_HANDOFF_DESC), 2, 1,
					a.finish(), List.of()));
		}

		// --- _async_run(fn): spawn the body on a virtual thread, eager-start handoff
		{
			Asm a = new Asm();
			// future (slot 1)
			a.op(Opcode.NEW);
			a.u2(futureClass.index());
			a.op(Opcode.DUP);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(futureCtor.index());
			a.astore(1);
			// latch (slot 2)
			a.op(Opcode.NEW);
			a.u2(latchClass.index());
			a.op(Opcode.DUP);
			a.iconst(1);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(latchCtor.index());
			a.astore(2);
			// runner (slot 3) = new Prog() with the three fields set
			a.op(Opcode.NEW);
			a.u2(thisClass.index());
			a.op(Opcode.DUP);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(instanceInitRef.index());
			a.astore(3);
			a.aload(3);
			a.aload(0);
			a.op(Opcode.PUTFIELD);
			a.u2(fnField.index());
			a.aload(3);
			a.aload(1);
			a.op(Opcode.PUTFIELD);
			a.u2(futureField.index());
			a.aload(3);
			a.aload(2);
			a.op(Opcode.PUTFIELD);
			a.u2(latchField.index());
			// Thread.ofVirtual().start(runner)
			a.op(Opcode.INVOKESTATIC);
			a.u2(threadOfVirtual.index()); // [builder]
			a.aload(3);
			a.op(Opcode.INVOKEINTERFACE);
			a.u2(builderStart.index());
			a.op(2); // this + 1 arg
			a.op(0); // [thread]
			a.op(Opcode.POP);
			// latch.await() -- resumes at the body's first suspension or completion
			a.aload(2);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(latchAwait.index());
			a.aload(1);
			a.areturn();
			methods.add(new AsyncMethod(cp.addUtf8(ASYNC_RUN_METHOD), cp.addUtf8(ASYNC_RUN_DESC), 3, 4, a.finish(),
					List.of()));
		}

		// --- run(): the async body on its virtual thread (Runnable protocol)
		AsyncMethod runMethod;
		{
			Asm a = new Asm();
			if (launcherRun != null) {
				// if (this._asyncLatch == null) { _main$run(this); return; }
				int asyncBody = a.label();
				a.aload(0);
				a.op(Opcode.GETFIELD);
				a.u2(latchField.index());
				a.branch(Opcode.IFNONNULL, asyncBody);
				a.aload(0);
				a.op(Opcode.INVOKESTATIC);
				a.u2(launcherRun.index());
				a.op(Opcode.RETURN);
				a.bind(asyncBody);
			}
			// _handoffTl.set(this._asyncLatch)
			a.op(Opcode.GETSTATIC);
			a.u2(handoffField.index());
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(latchField.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(tlSet.index());
			// try { future.complete(_invoke_0(fn)) }
			int tryStart = a.pos();
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(futureField.index());
			a.checkcast(futureClass);
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(fnField.index());
			a.op(Opcode.INVOKESTATIC);
			a.u2(invoke0.index()); // [future, v]
			if (mvChannel != null && vMarker != null) {
				// The channel holds the body's extra values the moment its thunk
				// returns (its tail settled them), on THIS thread: a body that answered
				// other than one value completes with {VMARKER, v, extras}.
				int single = a.label();
				a.astore(1);
				mvChannel.emitLoad(a::op, a::u2);
				a.branch(Opcode.IFNULL, single);
				a.iconst(3);
				a.anewarray(objectClass);
				a.op(Opcode.DUP);
				a.iconst(0);
				a.ldc(vMarker.index());
				a.aastore();
				a.op(Opcode.DUP);
				a.iconst(1);
				a.aload(1);
				a.aastore();
				a.op(Opcode.DUP);
				a.iconst(2);
				mvChannel.emitLoad(a::op, a::u2);
				a.aastore();
				a.astore(1);
				a.bind(single);
				a.aload(1); // [future, v-or-payload]
			}
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(futureComplete.index());
			a.op(Opcode.POP);
			int tryEnd = a.pos();
			int done = a.label();
			a.branch(Opcode.GOTO, done);
			// catch (Throwable t): future.complete({EMARKER, t, _condTl.get()}), and
			// t.getStackTrace() after them when the body recorded its boundary there
			int handler = a.pos();
			a.astore(1);
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(futureField.index());
			a.checkcast(futureClass);
			a.iconst(errorPayloadLength);
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
			if (throwableGetStackTrace != null) {
				// The trace as the boundary left it, which every await puts back
				// (JvmUncaughtHandler): the condition is one object all of them rethrow.
				a.op(Opcode.DUP);
				a.iconst(3);
				a.aload(1);
				a.op(Opcode.INVOKEVIRTUAL);
				a.u2(throwableGetStackTrace.index());
				a.aastore();
			}
			// [future, payload]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(futureComplete.index());
			a.op(Opcode.POP);
			a.bind(done);
			// latch.countDown() on both paths
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(latchField.index());
			a.checkcast(latchClass);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(latchCountDown.index());
			a.op(Opcode.RETURN);
			runMethod = new AsyncMethod(cp.addUtf8("run"), cp.addUtf8("()V"), 6, 2, a.finish(),
					List.of(new int[] { tryStart, tryEnd, handler, throwableClass.index() }));
		}

		// --- _await(v): the generic resolver (flattening loop)
		{
			Asm a = new Asm();
			int loop = a.label();
			int notToken = a.label();
			int notFuture = a.label();
			if (mvChannel != null) {
				// await is a multiple-value producer: one value unless the last
				// future of the chain settled with a {VMARKER, ...} payload.
				a.aconstNull();
				mvChannel.emitStore(a::op, a::u2);
			}
			a.bind(loop);
			// stream-read token {RMARKER, queue, state}?
			a.aload(0);
			a.op(Opcode.INSTANCEOF);
			a.u2(objectArrayClass.index());
			a.branch(Opcode.IFEQ, notToken);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.op(Opcode.ARRAYLENGTH);
			a.iconst(3);
			a.branch(Opcode.IF_ICMPNE, notToken);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(0);
			a.aaload();
			a.ldc(rMarker.index());
			a.branch(Opcode.IF_ACMPNE, notToken);
			// blocking take (the suspension point): release the handoff first
			a.op(Opcode.INVOKESTATIC);
			a.u2(releaseHandoff.index());
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(queueClass);
			a.astore(1); // q
			a.aload(1);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(queueTake.index());
			a.astore(2); // chunk
			int notPill = a.label();
			a.aload(2);
			a.ldc(sMarker.index());
			a.branch(Opcode.IF_ACMPNE, notPill);
			// end of stream: re-enqueue the pill for other readers, yield nil
			a.aload(1);
			a.ldc(sMarker.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(queueOffer.index());
			a.op(Opcode.POP);
			a.aconstNull();
			a.areturn();
			a.bind(notPill);
			if (mvChannel != null) {
				a.aconstNull();
				mvChannel.emitStore(a::op, a::u2);
			}
			a.aload(2);
			a.astore(0);
			a.branch(Opcode.GOTO, loop); // flatten the chunk
			a.bind(notToken);
			// CompletableFuture?
			a.aload(0);
			a.op(Opcode.INSTANCEOF);
			a.u2(futureClass.index());
			a.branch(Opcode.IFEQ, notFuture);
			a.aload(0);
			a.checkcast(futureClass);
			a.astore(3); // f
			a.aload(3);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(futureIsDone.index());
			int joinIt = a.label();
			a.branch(Opcode.IFNE, joinIt);
			a.op(Opcode.INVOKESTATIC);
			a.u2(releaseHandoff.index());
			a.bind(joinIt);
			a.aload(3);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(futureJoin.index());
			a.astore(4); // r
			// the {EMARKER, t, cond[, trace]} error envelope
			int plain = a.label();
			a.aload(4);
			a.op(Opcode.INSTANCEOF);
			a.u2(objectArrayClass.index());
			a.branch(Opcode.IFEQ, plain);
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.op(Opcode.ARRAYLENGTH);
			a.iconst(errorPayloadLength);
			a.branch(Opcode.IF_ICMPNE, plain);
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.iconst(0);
			a.aaload();
			a.ldc(eMarker.index());
			a.branch(Opcode.IF_ACMPNE, plain);
			// {EMARKER, t, cond}: re-set the condition channel HERE (the awaiting
			// thread) and rethrow, so handler-case dispatches by type
			a.op(Opcode.GETSTATIC);
			a.u2(condTlField.index());
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.iconst(2);
			a.aaload();
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(tlSet.index());
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(throwableClass);
			if (asyncAwaited != null) {
				// The uncaught report's hop: this await completes the boundary the body's
				// thunk recorded in the trace, put back as stored (JvmUncaughtHandler).
				a.op(Opcode.DUP);
				a.aload(4);
				a.checkcast(objectArrayClass);
				a.iconst(3);
				a.aaload();
				a.checkcast(java.util.Objects.requireNonNull(stackTraceArrayClass));
				a.op(Opcode.INVOKESTATIC);
				a.u2(asyncAwaited.index());
			}
			a.op(Opcode.ATHROW);
			a.bind(plain);
			if (mvChannel != null && vMarker != null) {
				// {VMARKER, primary, extras}: publish the extras, flatten the primary
				int oneValue = a.label();
				emitMarkerTest(a, objectArrayClass, vMarker, 4, oneValue);
				a.aload(4);
				a.checkcast(objectArrayClass);
				a.iconst(2);
				a.aaload();
				mvChannel.emitStore(a::op, a::u2);
				a.aload(4);
				a.checkcast(objectArrayClass);
				a.iconst(1);
				a.aaload();
				a.astore(0);
				a.branch(Opcode.GOTO, loop);
				a.bind(oneValue);
				a.aconstNull();
				mvChannel.emitStore(a::op, a::u2);
			}
			// flatten: v = r; loop (a plain value exits at the type checks above)
			a.aload(4);
			a.astore(0);
			a.branch(Opcode.GOTO, loop);
			a.bind(notFuture);
			a.aload(0);
			a.areturn();
			methods
				.add(new AsyncMethod(cp.addUtf8(AWAIT_METHOD), cp.addUtf8(AWAIT_DESC), 12, 20, a.finish(), List.of()));
		}

		// --- _futurep(v): CompletableFuture or a stream-read token
		{
			Asm a = new Asm();
			int yes = a.label();
			int no = a.label();
			a.aload(0);
			a.op(Opcode.INSTANCEOF);
			a.u2(futureClass.index());
			a.branch(Opcode.IFNE, yes);
			emitMarkerTest(a, objectArrayClass, rMarker, 0, no);
			a.bind(yes);
			a.ldc(tStr.index());
			a.areturn();
			a.bind(no);
			a.aconstNull();
			a.areturn();
			methods
				.add(new AsyncMethod(cp.addUtf8(FUTUREP_METHOD), cp.addUtf8(UNARY_DESC), 2, 1, a.finish(), List.of()));
		}

		// --- _streamp(v)
		{
			Asm a = new Asm();
			int no = a.label();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, no);
			a.ldc(tStr.index());
			a.areturn();
			a.bind(no);
			a.aconstNull();
			a.areturn();
			methods
				.add(new AsyncMethod(cp.addUtf8(STREAMP_METHOD), cp.addUtf8(UNARY_DESC), 2, 1, a.finish(), List.of()));
		}

		// --- _make_stream(): {SMARKER, new LinkedBlockingQueue, new AtomicInteger(0)}
		{
			Asm a = new Asm();
			a.iconst(3);
			a.anewarray(objectClass);
			a.op(Opcode.DUP);
			a.iconst(0);
			a.ldc(sMarker.index());
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(1);
			a.op(Opcode.NEW);
			a.u2(queueClass.index());
			a.op(Opcode.DUP);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(queueCtor.index());
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(2);
			a.op(Opcode.NEW);
			a.u2(atomicIntClass.index());
			a.op(Opcode.DUP);
			a.iconst(0);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(atomicIntCtor.index());
			a.aastore();
			a.areturn();
			methods.add(new AsyncMethod(cp.addUtf8(MAKE_STREAM_METHOD), cp.addUtf8(MAKE_STREAM_DESC), 6, 1, a.finish(),
					List.of()));
		}

		// --- _stream_new(readFn, closeFn): the PULL stream rontolisp::%stream-new
		// builds, {SMARKER, {readFn, closeFn}, AtomicInteger(0)}. Same Object[3] as a
		// buffered stream, with the thunk pair where the chunk queue would be, so every
		// consumer that only asks "is this a stream" (_streamp, the printer) is
		// untouched.
		{
			Asm a = new Asm();
			a.iconst(3);
			a.anewarray(objectClass);
			a.op(Opcode.DUP);
			a.iconst(0);
			a.ldc(sMarker.index());
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(1);
			a.iconst(2);
			a.anewarray(objectClass);
			a.op(Opcode.DUP);
			a.iconst(0);
			a.aload(0);
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(1);
			a.aload(1);
			a.aastore();
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(2);
			a.op(Opcode.NEW);
			a.u2(atomicIntClass.index());
			a.op(Opcode.DUP);
			a.iconst(0);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(atomicIntCtor.index());
			a.aastore();
			a.areturn();
			methods.add(new AsyncMethod(cp.addUtf8(STREAM_NEW_METHOD), cp.addUtf8(STREAM_NEW_DESC), 8, 2, a.finish(),
					List.of()));
		}

		// --- _stream_read(s): a buffered stream answers the {RMARKER, q, state} token
		// (the take happens at await); a PULL stream has nothing to defer to, so it runs
		// the read thunk here and answers a settled future -- resolving the thunk's
		// answer BEFORE the end-of-stream test, because a thunk that awaits answers a
		// future and a future wrapping nil is not nil. The first nil chunk runs the close
		// thunk once (the drain closes exactly once; a read past the end is nil).
		{
			Asm a = new Asm();
			int bad = a.label();
			int pull = a.label();
			int drained = a.label();
			int settle = a.label();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, bad);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.op(Opcode.INSTANCEOF);
			a.u2(queueClass.index());
			a.branch(Opcode.IFEQ, pull);
			a.iconst(3);
			a.anewarray(objectClass);
			a.op(Opcode.DUP);
			a.iconst(0);
			a.ldc(rMarker.index());
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(1);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.aastore();
			a.op(Opcode.DUP);
			a.iconst(2);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(2);
			a.aaload();
			a.aastore();
			a.areturn();
			a.bind(pull);
			// fns (slot 1), state (slot 2)
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(objectArrayClass);
			a.astore(1);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(2);
			a.aaload();
			a.checkcast(atomicIntClass);
			a.astore(2);
			a.aload(2);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(atomicIntGet.index());
			a.branch(Opcode.IFNE, drained);
			// chunk (slot 3) = _await(_invoke_0(readFn))
			a.aload(1);
			a.iconst(0);
			a.aaload();
			a.op(Opcode.INVOKESTATIC);
			a.u2(invoke0.index());
			a.op(Opcode.INVOKESTATIC);
			a.u2(awaitSelf.index());
			a.astore(3);
			a.aload(3);
			a.branch(Opcode.IFNONNULL, settle);
			a.aload(2);
			a.iconst(1);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(atomicIntGetAndSet.index());
			a.branch(Opcode.IFNE, settle);
			a.aload(1);
			a.iconst(1);
			a.aaload();
			a.op(Opcode.INVOKESTATIC);
			a.u2(invoke0.index());
			a.op(Opcode.POP);
			a.bind(settle);
			a.aload(3);
			a.op(Opcode.INVOKESTATIC);
			a.u2(futureCompleted.index());
			a.areturn();
			a.bind(drained);
			a.aconstNull();
			a.op(Opcode.INVOKESTATIC);
			a.u2(futureCompleted.index());
			a.areturn();
			a.bind(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-read expects a stream");
			methods.add(new AsyncMethod(cp.addUtf8(STREAM_READ_METHOD), cp.addUtf8(UNARY_DESC), 5, 4, a.finish(),
					List.of()));
		}

		// --- _stream_write(s, chunk)
		{
			Asm a = new Asm();
			int bad = a.label();
			int nilChunk = a.label();
			int closed = a.label();
			int noWriteEnd = a.label();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, bad);
			// A pull stream has no buffer to append to -- its chunks come from its read
			// thunk -- so the refusal is its own, not "the stream is closed".
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.op(Opcode.INSTANCEOF);
			a.u2(queueClass.index());
			a.branch(Opcode.IFEQ, noWriteEnd);
			a.aload(1);
			a.branch(Opcode.IFNULL, nilChunk);
			// closed?
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(2);
			a.aaload();
			a.checkcast(atomicIntClass);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(atomicIntGet.index());
			a.branch(Opcode.IFNE, closed);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.checkcast(queueClass);
			a.aload(1);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(queueOffer.index());
			a.op(Opcode.POP);
			// accepted immediately: a settled future of nil
			a.aconstNull();
			a.op(Opcode.INVOKESTATIC);
			a.u2(futureCompleted.index());
			a.areturn();
			a.bind(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write expects a stream");
			a.bind(nilChunk);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write: a chunk must not be nil");
			a.bind(closed);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write: the stream is closed");
			a.bind(noWriteEnd);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-write: the stream has no write end");
			methods.add(new AsyncMethod(cp.addUtf8(STREAM_WRITE_METHOD), cp.addUtf8(STREAM_WRITE_DESC), 3, 2,
					a.finish(), List.of()));
		}

		// --- _stream_close(s): end the stream once -- the poison pill for a buffered
		// stream, the close thunk for a pull one
		{
			Asm a = new Asm();
			int bad = a.label();
			int already = a.label();
			int pull = a.label();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, bad);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(2);
			a.aaload();
			a.checkcast(atomicIntClass);
			a.iconst(1);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(atomicIntGetAndSet.index());
			a.branch(Opcode.IFNE, already);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.astore(1);
			a.aload(1);
			a.op(Opcode.INSTANCEOF);
			a.u2(queueClass.index());
			a.branch(Opcode.IFEQ, pull);
			a.aload(1);
			a.checkcast(queueClass);
			a.ldc(sMarker.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(queueOffer.index());
			a.op(Opcode.POP);
			a.branch(Opcode.GOTO, already);
			a.bind(pull);
			a.aload(1);
			a.checkcast(objectArrayClass);
			a.iconst(1);
			a.aaload();
			a.op(Opcode.INVOKESTATIC);
			a.u2(invoke0.index());
			a.op(Opcode.POP);
			a.bind(already);
			a.aconstNull();
			a.areturn();
			a.bind(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit, "stream-close expects a stream");
			methods.add(new AsyncMethod(cp.addUtf8(STREAM_CLOSE_METHOD), cp.addUtf8(UNARY_DESC), 3, 2, a.finish(),
					List.of()));
		}

		// --- _iv_of_bytes(byte[]): raw bytes -> byte[]{8, e0, ...}, the packed
		// (unsigned-byte 8) vector every HTTP body stream answers its chunks as (the
		// _iv* runtime's representation, so aref/length dispatch on it as on any
		// make-array'd octet vector).
		{
			MethodrefConstant arraycopy = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/System")), cp
				.addNameAndType(cp.addUtf8("arraycopy"), cp.addUtf8("(Ljava/lang/Object;ILjava/lang/Object;II)V")));
			Asm a = new Asm();
			// slots: 0 bytes, 1 out
			a.aload(0);
			a.op(Opcode.ARRAYLENGTH);
			a.iconst(1);
			a.op(Opcode.IADD);
			a.op(Opcode.NEWARRAY);
			a.op(8); // T_BYTE
			a.astore(1);
			a.aload(1);
			a.iconst(0);
			a.iconst(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			a.op(Opcode.BASTORE); // out[0] = 8 (the width header)
			// System.arraycopy(bytes, 0, out, 1, bytes.length)
			a.aload(0);
			a.iconst(0);
			a.aload(1);
			a.iconst(1);
			a.aload(0);
			a.op(Opcode.ARRAYLENGTH);
			a.op(Opcode.INVOKESTATIC);
			a.u2(arraycopy.index());
			a.aload(1);
			a.areturn();
			methods.add(new AsyncMethod(cp.addUtf8(IV_OF_BYTES_METHOD), cp.addUtf8(IV_OF_BYTES_DESC), 5, 2, a.finish(),
					List.of()));
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
			ClassConstant baosClass = cp.addClass(cp.addUtf8("java/io/ByteArrayOutputStream"));
			MethodrefConstant baosInit = cp.addMethodref(baosClass,
					cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
			MethodrefConstant baosWrite = cp.addMethodref(baosClass,
					cp.addNameAndType(cp.addUtf8("write"), cp.addUtf8("(I)V")));
			MethodrefConstant baosWriteBytes = cp.addMethodref(baosClass,
					cp.addNameAndType(cp.addUtf8("writeBytes"), cp.addUtf8("([B)V")));
			MethodrefConstant baosWriteRange = cp.addMethodref(baosClass,
					cp.addNameAndType(cp.addUtf8("write"), cp.addUtf8("([BII)V")));
			MethodrefConstant baosToByteArray = cp.addMethodref(baosClass,
					cp.addNameAndType(cp.addUtf8("toByteArray"), cp.addUtf8("()[B")));
			MethodrefConstant baosToString = cp.addMethodref(baosClass, cp.addNameAndType(cp.addUtf8("toString"),
					cp.addUtf8("(Ljava/nio/charset/Charset;)Ljava/lang/String;")));
			ClassConstant charsetsClass = cp.addClass(cp.addUtf8("java/nio/charset/StandardCharsets"));
			ConstantPool.FieldrefConstant utf8Field = cp.addFieldref(charsetsClass,
					cp.addNameAndType(cp.addUtf8("UTF_8"), cp.addUtf8("Ljava/nio/charset/Charset;")));
			MethodrefConstant stringGetBytes = cp.addMethodref(stringClass,
					cp.addNameAndType(cp.addUtf8("getBytes"), cp.addUtf8("(Ljava/nio/charset/Charset;)[B")));
			Asm a = new Asm();
			// slots: 0 v, 1 sink, 2 chunk, 3 octetsSeen, 4 textSeen, 5 i, 6 iv
			int passThrough = a.label();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, passThrough);
			a.op(Opcode.NEW);
			a.u2(baosClass.index());
			a.op(Opcode.DUP);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(baosInit.index());
			a.astore(1);
			a.iconst(0);
			a.istore(3);
			a.iconst(0);
			a.istore(4);
			int loop = a.label();
			int done = a.label();
			int notIv = a.label();
			int mixed = a.label();
			a.bind(loop);
			a.aload(0);
			a.op(Opcode.INVOKESTATIC);
			a.u2(streamReadSelf.index());
			a.op(Opcode.INVOKESTATIC);
			a.u2(awaitSelf.index());
			a.astore(2); // chunk
			a.aload(2);
			a.branch(Opcode.IFNULL, done);
			// an octet chunk, byte[]{8, e0, ...}: the elements after the width header, in
			// one write
			int notOctets = a.label();
			a.aload(2);
			a.op(Opcode.INSTANCEOF);
			a.u2(byteArrayClass.index());
			a.branch(Opcode.IFEQ, notOctets);
			a.iconst(1);
			a.istore(3);
			a.aload(1);
			a.aload(2);
			a.checkcast(byteArrayClass);
			a.op(Opcode.DUP); // [sink, chunk, chunk]
			a.op(Opcode.ARRAYLENGTH);
			a.iconst(1);
			a.op(Opcode.ISUB);
			a.iconst(1);
			a.op(Opcode.SWAP); // [sink, chunk, 1, len-1]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(baosWriteRange.index());
			a.branch(Opcode.GOTO, loop);
			a.bind(notOctets);
			a.aload(2);
			a.op(Opcode.INSTANCEOF);
			a.u2(longArrayClass.index());
			a.branch(Opcode.IFEQ, notIv);
			// a wider packed chunk: every element after the width header, one write each
			a.iconst(1);
			a.istore(3);
			a.aload(2);
			a.checkcast(longArrayClass);
			a.astore(6);
			a.iconst(1);
			a.istore(5);
			int ivLoop = a.label();
			int ivDone = a.label();
			a.bind(ivLoop);
			a.iload(5);
			a.aload(6);
			a.op(Opcode.ARRAYLENGTH);
			a.branch(Opcode.IF_ICMPGE, ivDone);
			a.aload(1);
			a.aload(6);
			a.iload(5);
			a.op(Opcode.LALOAD);
			a.op(Opcode.L2I);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(baosWrite.index());
			a.iinc(5, 1);
			a.branch(Opcode.GOTO, ivLoop);
			a.bind(ivDone);
			a.branch(Opcode.GOTO, loop);
			// a string chunk: its raw text, UTF-8 encoded
			a.bind(notIv);
			a.iconst(1);
			a.istore(4);
			a.aload(1);
			a.aload(2);
			a.checkcast(stringClass);
			a.op(Opcode.DUP); // [sink, chunk, chunk]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringLength.index()); // [sink, chunk, len]
			a.iconst(1);
			a.op(Opcode.ISUB);
			a.iconst(1);
			a.op(Opcode.SWAP); // [sink, chunk, 1, len-1]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringSubstring.index()); // [sink, raw]
			a.op(Opcode.GETSTATIC);
			a.u2(utf8Field.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringGetBytes.index()); // [sink, bytes]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(baosWriteBytes.index());
			a.branch(Opcode.GOTO, loop);
			a.bind(done);
			a.iload(3);
			a.iload(4);
			a.op(Opcode.IAND);
			a.branch(Opcode.IFNE, mixed);
			int textResult = a.label();
			a.iload(3);
			a.branch(Opcode.IFEQ, textResult);
			// octets: one byte[] vector, written by the transport as it is
			a.aload(1);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(baosToByteArray.index());
			a.op(Opcode.INVOKESTATIC);
			a.u2(ivOfBytesSelf.index());
			a.areturn();
			// text (or an empty stream): the quoted concatenation
			a.bind(textResult);
			a.ldc(quote.index());
			a.aload(1);
			a.op(Opcode.GETSTATIC);
			a.u2(utf8Field.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(baosToString.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringConcat.index());
			a.ldc(quote.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringConcat.index());
			a.areturn();
			a.bind(mixed);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit,
					"http-handler: a stream response body mixes string and octet chunks");
			a.bind(passThrough);
			a.aload(0);
			a.areturn();
			methods.add(new AsyncMethod(cp.addUtf8(DRAIN_BODY_METHOD), cp.addUtf8(UNARY_DESC), 5, 7, a.finish(),
					List.of()));
		}

		// --- _wait_for(ms): a future settling to nil after ms milliseconds, via
		// CompletableFuture.completeOnTimeout (the JDK's shared delayer thread)
		{
			MethodrefConstant longValue = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/Long")),
					cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
			ClassConstant longBoxClass = cp.addClass(cp.addUtf8("java/lang/Long"));
			ClassConstant timeUnitClass = cp.addClass(cp.addUtf8("java/util/concurrent/TimeUnit"));
			ConstantPool.FieldrefConstant millisUnit = cp.addFieldref(timeUnitClass,
					cp.addNameAndType(cp.addUtf8("MILLISECONDS"), cp.addUtf8("Ljava/util/concurrent/TimeUnit;")));
			MethodrefConstant completeOnTimeout = cp
				.addMethodref(futureClass, cp.addNameAndType(cp.addUtf8("completeOnTimeout"), cp.addUtf8(
						"(Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/CompletableFuture;")));
			Asm a = new Asm();
			int bad = a.label();
			a.aload(0);
			a.op(Opcode.INSTANCEOF);
			a.u2(longBoxClass.index());
			a.branch(Opcode.IFEQ, bad);
			a.aload(0);
			a.checkcast(longBoxClass);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(longValue.index()); // [J]
			a.op(Opcode.LSTORE_1); // ms in slots 1-2; the bad path is reached stack-empty
			a.op(Opcode.LLOAD_1);
			a.op(Opcode.LCONST_0);
			a.op(Opcode.LCMP);
			a.branch(Opcode.IFLT, bad); // []
			a.op(Opcode.NEW);
			a.u2(futureClass.index());
			a.op(Opcode.DUP);
			a.op(Opcode.INVOKESPECIAL);
			a.u2(futureCtor.index()); // [cf]
			a.aconstNull(); // [cf, nil]
			a.op(Opcode.LLOAD_1); // [cf, nil, J]
			a.op(Opcode.GETSTATIC);
			a.u2(millisUnit.index()); // [cf, nil, J, unit]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(completeOnTimeout.index()); // [cf]
			a.areturn();
			a.bind(bad);
			emitThrow(a, cp, runtimeExceptionClass, runtimeExceptionInit,
					"wait-for expects a non-negative integer of milliseconds");
			methods
				.add(new AsyncMethod(cp.addUtf8(WAIT_FOR_METHOD), cp.addUtf8(UNARY_DESC), 6, 3, a.finish(), List.of()));
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
		ClassConstant byteArrayClass = cp.addClass(cp.addUtf8("[B"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant stringFromBytes = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("([BLjava/nio/charset/Charset;)V")));
		MethodrefConstant stringFromChars = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("([C)V")));
		ConstantPool.FieldrefConstant latin1 = cp.addFieldref(
				cp.addClass(cp.addUtf8("java/nio/charset/StandardCharsets")),
				cp.addNameAndType(cp.addUtf8("ISO_8859_1"), cp.addUtf8("Ljava/nio/charset/Charset;")));
		MethodrefConstant arraycopy = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/System")),
				cp.addNameAndType(cp.addUtf8("arraycopy"), cp.addUtf8("(Ljava/lang/Object;ILjava/lang/Object;II)V")));
		ClassConstant characterClass = cp.addClass(cp.addUtf8("java/lang/Character"));
		MethodrefConstant highSurrogate = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("highSurrogate"), cp.addUtf8("(I)C")));
		MethodrefConstant lowSurrogate = cp.addMethodref(characterClass,
				cp.addNameAndType(cp.addUtf8("lowSurrogate"), cp.addUtf8("(I)C")));

		Asm a = new Asm();
		// slots: 0 v, 1 bytes (the vector: tag, then the octets), 2 n (its length), 3 i,
		// 4 units, 5 every code point OR'd, 6 b, 7 cp, 8 adv, 9 four-byte code point,
		// 10 k, 11 out
		int bytesSlot = 1, nSlot = 2, iSlot = 3, unitsSlot = 4, orSlot = 5, bSlot = 6, cpSlot = 7, advSlot = 8,
				cp4Slot = 9, kSlot = 10, outSlot = 11;
		Unit unit = new Unit(bytesSlot, iSlot, nSlot, bSlot, cpSlot, advSlot, cp4Slot);
		int none = a.label();
		a.aload(0);
		a.op(Opcode.INSTANCEOF);
		a.u2(byteArrayClass.index());
		a.branch(Opcode.IFEQ, none);
		a.aload(0);
		a.checkcast(byteArrayClass);
		a.astore(bytesSlot);
		a.aload(bytesSlot);
		a.op(Opcode.ARRAYLENGTH);
		a.istore(nSlot);
		// Refuse an empty array and a quantized matrix (another byte[], whose slot 0 is
		// its format code) rather than reading a header that is not the tag.
		a.iload(nSlot);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPLT, none);
		a.aload(bytesSlot);
		a.iconst(0);
		a.op(Opcode.BALOAD);
		a.iconst(JvmIntArrayRuntimeBuilder.OCTET_TAG);
		a.branch(Opcode.IF_ICMPNE, none);
		// Count: units (UTF-16 code units, a supplementary character two) and the OR of
		// every code point.
		a.iconst(0);
		a.istore(unitsSlot);
		a.iconst(0);
		a.istore(orSlot);
		int counted = a.label();
		unit.emitLoop(a, counted, () -> {
			a.iload(orSlot);
			a.iload(cpSlot);
			a.op(Opcode.IOR);
			a.istore(orSlot);
			a.iinc(unitsSlot, 1);
			int bmp = a.label();
			a.iload(cpSlot);
			a.iconst(16);
			a.op(Opcode.ISHR);
			a.branch(Opcode.IFEQ, bmp);
			a.iinc(unitsSlot, 1);
			a.bind(bmp);
		});
		a.bind(counted);
		// Every unit one octet: the octets ARE the Latin-1 content -- one copy between
		// the frame quotes.
		int notVerbatim = a.label();
		a.iload(unitsSlot);
		a.iload(nSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPNE, notVerbatim);
		a.iload(nSlot);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.op(Opcode.NEWARRAY);
		a.op(8); // T_BYTE
		a.astore(outSlot);
		a.aload(bytesSlot);
		a.iconst(1);
		a.aload(outSlot);
		a.iconst(1);
		a.iload(unitsSlot);
		a.op(Opcode.INVOKESTATIC);
		a.u2(arraycopy.index());
		int latin1Framed = a.label();
		a.branch(Opcode.GOTO, latin1Framed);
		a.bind(notVerbatim);
		int wide = a.label();
		a.iload(orSlot);
		a.iconst(0xFF);
		a.branch(Opcode.IF_ICMPGT, wide);
		// Every character Latin-1: narrowed into a byte[].
		a.iload(unitsSlot);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.op(Opcode.NEWARRAY);
		a.op(8); // T_BYTE
		a.astore(outSlot);
		a.iconst(1);
		a.istore(kSlot);
		unit.emitLoop(a, latin1Framed, () -> {
			a.aload(outSlot);
			a.checkcast(byteArrayClass);
			a.iload(kSlot);
			a.iload(cpSlot);
			a.op(Opcode.BASTORE);
			a.iinc(kSlot, 1);
		});
		// out[0] = out[last] = '"'; return new String(out, ISO_8859_1)
		a.bind(latin1Framed);
		a.aload(outSlot);
		a.checkcast(byteArrayClass);
		a.iconst(0);
		a.iconst('"');
		a.op(Opcode.BASTORE);
		a.aload(outSlot);
		a.checkcast(byteArrayClass);
		a.op(Opcode.DUP);
		a.op(Opcode.ARRAYLENGTH);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.iconst('"');
		a.op(Opcode.BASTORE);
		a.op(Opcode.NEW);
		a.u2(stringClass.index());
		a.op(Opcode.DUP);
		a.aload(outSlot);
		a.op(Opcode.GETSTATIC);
		a.u2(latin1.index());
		a.op(Opcode.INVOKESPECIAL);
		a.u2(stringFromBytes.index());
		a.areturn();
		// Otherwise a char[], a supplementary character as its surrogate pair.
		a.bind(wide);
		a.iload(unitsSlot);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.op(Opcode.NEWARRAY);
		a.op(5); // T_CHAR
		a.astore(outSlot);
		a.iconst(1);
		a.istore(kSlot);
		int wideDone = a.label();
		unit.emitLoop(a, wideDone, () -> {
			int bmp = a.label();
			int stored = a.label();
			a.iload(cpSlot);
			a.iconst(16);
			a.op(Opcode.ISHR);
			a.branch(Opcode.IFEQ, bmp);
			a.aload(outSlot);
			a.checkcast(cp.addClass(cp.addUtf8("[C")));
			a.iload(kSlot);
			a.iload(cpSlot);
			a.op(Opcode.INVOKESTATIC);
			a.u2(highSurrogate.index());
			a.op(Opcode.CASTORE);
			a.iinc(kSlot, 1);
			a.aload(outSlot);
			a.checkcast(cp.addClass(cp.addUtf8("[C")));
			a.iload(kSlot);
			a.iload(cpSlot);
			a.op(Opcode.INVOKESTATIC);
			a.u2(lowSurrogate.index());
			a.op(Opcode.CASTORE);
			a.branch(Opcode.GOTO, stored);
			a.bind(bmp);
			a.aload(outSlot);
			a.checkcast(cp.addClass(cp.addUtf8("[C")));
			a.iload(kSlot);
			a.iload(cpSlot);
			a.op(Opcode.CASTORE);
			a.bind(stored);
			a.iinc(kSlot, 1);
		});
		a.bind(wideDone);
		a.aload(outSlot);
		a.checkcast(cp.addClass(cp.addUtf8("[C")));
		a.iconst(0);
		a.iconst('"');
		a.op(Opcode.CASTORE);
		a.aload(outSlot);
		a.checkcast(cp.addClass(cp.addUtf8("[C")));
		a.op(Opcode.DUP);
		a.op(Opcode.ARRAYLENGTH);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.iconst('"');
		a.op(Opcode.CASTORE);
		a.op(Opcode.NEW);
		a.u2(stringClass.index());
		a.op(Opcode.DUP);
		a.aload(outSlot);
		a.checkcast(cp.addClass(cp.addUtf8("[C")));
		a.op(Opcode.INVOKESPECIAL);
		a.u2(stringFromChars.index());
		a.areturn();
		a.bind(none);
		a.aconstNull();
		a.areturn();
		return new AsyncMethod(cp.addUtf8(OCTETS_PACKED_METHOD), cp.addUtf8(UNARY_DESC), 6, 12, a.finish(), List.of());
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
		void emitLoop(Asm a, int done, Runnable body) {
			a.iconst(1);
			a.istore(this.iSlot);
			int loop = a.label();
			a.bind(loop);
			a.iload(this.iSlot);
			a.iload(this.nSlot);
			a.branch(Opcode.IF_ICMPGE, done);
			emitStep(a);
			body.run();
			a.iload(this.iSlot);
			a.iload(this.advSlot);
			a.op(Opcode.IADD);
			a.istore(this.iSlot);
			a.branch(Opcode.GOTO, loop);
		}

		private void emitStep(Asm a) {
			int emit = a.label();
			// b = bytes[i] & 0xFF; by default it is its own character, one octet long.
			a.aload(this.bytesSlot);
			a.iload(this.iSlot);
			a.op(Opcode.BALOAD);
			a.iconst(0xFF);
			a.op(Opcode.IAND);
			a.op(Opcode.DUP);
			a.istore(this.bSlot);
			a.istore(this.cpSlot);
			a.iconst(1);
			a.istore(this.advSlot);
			// b < 0xC0: ASCII, or a continuation byte that leads nothing.
			a.iload(this.bSlot);
			a.iconst(0xC0);
			a.branch(Opcode.IF_ICMPLT, emit);
			int notTwo = a.label();
			a.iload(this.bSlot);
			a.iconst(0xE0);
			a.branch(Opcode.IF_ICMPGE, notTwo);
			emitLeadTakes(a, 2, 0x1F, this.bytesSlot, this.iSlot, this.nSlot, this.bSlot, emit);
			a.istore(this.cpSlot);
			a.iconst(2);
			a.istore(this.advSlot);
			a.branch(Opcode.GOTO, emit);
			a.bind(notTwo);
			int notThree = a.label();
			a.iload(this.bSlot);
			a.iconst(0xF0);
			a.branch(Opcode.IF_ICMPGE, notThree);
			emitLeadTakes(a, 3, 0x0F, this.bytesSlot, this.iSlot, this.nSlot, this.bSlot, emit);
			a.istore(this.cpSlot);
			a.iconst(3);
			a.istore(this.advSlot);
			a.branch(Opcode.GOTO, emit);
			a.bind(notThree);
			a.iload(this.bSlot);
			a.iconst(0xF8);
			a.branch(Opcode.IF_ICMPGE, emit);
			emitLeadTakes(a, 4, 0x07, this.bytesSlot, this.iSlot, this.nSlot, this.bSlot, emit);
			a.istore(this.cp4Slot);
			// Past U+10FFFF (cp >> 16 > 0x10) the lead byte stays its own character.
			a.iload(this.cp4Slot);
			a.iconst(16);
			a.op(Opcode.ISHR);
			a.iconst(0x10);
			a.branch(Opcode.IF_ICMPGT, emit);
			a.iload(this.cp4Slot);
			a.istore(this.cpSlot);
			a.iconst(4);
			a.istore(this.advSlot);
			a.bind(emit);
		}

	}

	/**
	 * Emits the code point a {@code length}-byte lead at {@code bytes[i]} assembles --
	 * {@code (b & leadMask) << 6 * (length - 1)} OR'd with the low six bits of each byte
	 * after it -- leaving it on the stack, or branches to {@code short} (with nothing on
	 * the stack) when the vector ends before the sequence does.
	 */
	private static void emitLeadTakes(Asm a, int length, int leadMask, int bytesSlot, int iSlot, int nSlot, int bSlot,
			int shortLabel) {
		a.iload(iSlot);
		a.iconst(length - 1);
		a.op(Opcode.IADD);
		a.iload(nSlot);
		a.branch(Opcode.IF_ICMPGE, shortLabel);
		a.iload(bSlot);
		a.iconst(leadMask);
		a.op(Opcode.IAND);
		for (int k = 1; k < length; k++) {
			a.iconst(6);
			a.op(Opcode.ISHL);
			a.aload(bytesSlot);
			a.iload(iSlot);
			a.iconst(k);
			a.op(Opcode.IADD);
			a.op(Opcode.BALOAD);
			a.iconst(0x3F);
			a.op(Opcode.IAND);
			a.op(Opcode.IOR);
		}
	}

	/**
	 * Emits "is local {@code slot} an {@code Object[3]} whose head is {@code marker}",
	 * branching to {@code noLabel} when it is not (falls through when it is).
	 */
	private static void emitMarkerTest(Asm a, ClassConstant objectArrayClass, ConstantPool.StringConstant marker,
			int slot, int noLabel) {
		a.aload(slot);
		a.op(Opcode.INSTANCEOF);
		a.u2(objectArrayClass.index());
		a.branch(Opcode.IFEQ, noLabel);
		a.aload(slot);
		a.checkcast(objectArrayClass);
		a.op(Opcode.ARRAYLENGTH);
		a.iconst(3);
		a.branch(Opcode.IF_ICMPNE, noLabel);
		a.aload(slot);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.ldc(marker.index());
		a.branch(Opcode.IF_ACMPNE, noLabel);
	}

	/** Emits {@code throw new RuntimeException(message)}. */
	private static void emitThrow(Asm a, ConstantPool cp, ClassConstant runtimeExceptionClass,
			MethodrefConstant runtimeExceptionInit, String message) {
		ConstantPool.StringConstant msg = cp.addString(message);
		a.op(Opcode.NEW);
		a.u2(runtimeExceptionClass.index());
		a.op(Opcode.DUP);
		a.ldc(msg.index());
		a.op(Opcode.INVOKESPECIAL);
		a.u2(runtimeExceptionInit.index());
		a.op(Opcode.ATHROW);
	}

	/** Minimal label-based assembler, mirroring the one in JvmFetchRuntimeBuilder. */
	static final class Asm {

		private final List<Integer> code = new ArrayList<>();

		private final Map<Integer, Integer> labelPos = new HashMap<>();

		private final Map<Integer, List<Integer>> pending = new HashMap<>();

		private int nextLabel = 0;

		int pos() {
			return this.code.size();
		}

		int label() {
			return this.nextLabel++;
		}

		void bind(int label) {
			int pos = this.code.size();
			this.labelPos.put(label, pos);
			List<Integer> ps = this.pending.remove(label);
			if (ps != null) {
				for (int bp : ps) {
					JvmRuntimeBuilder.patchBranch(this.code, bp, pos);
				}
			}
		}

		void branch(int opcode, int label) {
			int bp = this.code.size();
			this.code.add(opcode);
			JvmRuntimeBuilder.emitU2(this.code, 0);
			Integer tgt = this.labelPos.get(label);
			if (tgt != null) {
				JvmRuntimeBuilder.patchBranch(this.code, bp, tgt);
			}
			else {
				this.pending.computeIfAbsent(label, k -> new ArrayList<>()).add(bp);
			}
		}

		void op(int opcode) {
			this.code.add(opcode);
		}

		void u2(int value) {
			JvmRuntimeBuilder.emitU2(this.code, value);
		}

		void aload(int slot) {
			this.code.add(Opcode.ALOAD);
			this.code.add(slot);
		}

		void astore(int slot) {
			this.code.add(Opcode.ASTORE);
			this.code.add(slot);
		}

		void aaload() {
			this.code.add(Opcode.AALOAD);
		}

		void iload(int slot) {
			this.code.add(Opcode.ILOAD);
			this.code.add(slot);
		}

		void istore(int slot) {
			this.code.add(Opcode.ISTORE);
			this.code.add(slot);
		}

		void iinc(int slot, int delta) {
			this.code.add(Opcode.IINC);
			this.code.add(slot);
			this.code.add(delta & 0xFF);
		}

		void aastore() {
			this.code.add(Opcode.AASTORE);
		}

		void aconstNull() {
			this.code.add(Opcode.ACONST_NULL);
		}

		void iconst(int n) {
			if (n == -1) {
				this.code.add(Opcode.ICONST_M1);
			}
			else if (n >= 0 && n <= 5) {
				this.code.add(Opcode.ICONST_0 + n);
			}
			else if (n >= -128 && n <= 127) {
				this.code.add(Opcode.BIPUSH);
				this.code.add(n & 0xFF);
			}
			else {
				this.code.add(Opcode.SIPUSH);
				JvmRuntimeBuilder.emitU2(this.code, n);
			}
		}

		void ldc(int index) {
			if (index <= 255) {
				this.code.add(Opcode.LDC);
				this.code.add(index);
			}
			else {
				this.code.add(Opcode.LDC_W);
				JvmRuntimeBuilder.emitU2(this.code, index);
			}
		}

		void checkcast(ClassConstant c) {
			this.code.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(this.code, c.index());
		}

		void anewarray(ClassConstant c) {
			this.code.add(Opcode.ANEWARRAY);
			JvmRuntimeBuilder.emitU2(this.code, c.index());
		}

		void areturn() {
			this.code.add(Opcode.ARETURN);
		}

		List<Integer> finish() {
			if (!this.pending.isEmpty()) {
				throw new IllegalStateException("Unbound labels in async runtime assembly: " + this.pending.keySet());
			}
			return this.code;
		}

	}

}
