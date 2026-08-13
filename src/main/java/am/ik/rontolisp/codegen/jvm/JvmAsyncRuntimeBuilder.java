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
import am.ik.rontolisp.compiler.FetchResponseShape;

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
 * dispatches by type exactly like a same-thread signal.</li>
 * </ul>
 * When the program uses {@code rontolisp:fetch}, {@code _await} converts a settled
 * {@code HttpResponse} into the result property list
 * {@code (:status <int> :headers <alist> :body <stream>)} whose body is a one-chunk
 * closed stream (the JVM fetch stays buffered). Futures are flattened in a loop, like
 * JavaScript await.
 */
final class JvmAsyncRuntimeBuilder {

	/** Marker heading a stream's {@code Object[3]}; doubles as the EOF poison pill. */
	static final String SMARKER = "%stream\n";

	/** Marker heading a stream-read token's {@code Object[3]}. */
	static final String RMARKER = "%stream-read\n";

	/**
	 * Marker heading an async body's error payload {@code {EMARKER, throwable, cond}}.
	 */
	static final String EMARKER = "%async-error\n";

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
	 * @param usesFetch whether the HttpResponse branch of {@code _await} is emitted
	 * (gated so fetch-free programs never load {@code java.net.http} classes)
	 * @param longValueOf {@code Long.valueOf(J)}
	 * @param stringLength {@code String.length()}
	 * @param stringSubstring {@code String.substring(II)}
	 * @param stringConcat {@code String.concat(String)}
	 * @return the runtime bodies
	 */
	static AsyncRuntime build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, JvmLispCompiler.ConditionChannel channel,
			MethodrefConstant instanceInitRef, boolean usesFetch, MethodrefConstant longValueOf,
			MethodrefConstant stringLength, MethodrefConstant stringSubstring, MethodrefConstant stringConcat) {
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

		ConstantPool.StringConstant sMarker = cp.addString(SMARKER);
		ConstantPool.StringConstant rMarker = cp.addString(RMARKER);
		ConstantPool.StringConstant eMarker = cp.addString(EMARKER);
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
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(futureComplete.index());
			a.op(Opcode.POP);
			int tryEnd = a.pos();
			int done = a.label();
			a.branch(Opcode.GOTO, done);
			// catch (Throwable t): future.complete({EMARKER, t, _condTl.get()})
			int handler = a.pos();
			a.astore(1);
			a.aload(0);
			a.op(Opcode.GETFIELD);
			a.u2(futureField.index());
			a.checkcast(futureClass);
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
			a.aastore(); // [future, payload]
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
			// 3-element payload: the {EMARKER, t, cond} error envelope
			int plain = a.label();
			a.aload(4);
			a.op(Opcode.INSTANCEOF);
			a.u2(objectArrayClass.index());
			a.branch(Opcode.IFEQ, plain);
			a.aload(4);
			a.checkcast(objectArrayClass);
			a.op(Opcode.ARRAYLENGTH);
			a.iconst(3);
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
			a.op(Opcode.ATHROW);
			a.bind(plain);
			if (usesFetch) {
				emitHttpResponseBranch(a, cp, objectClass, objectArrayClass, stringClass, queueClass, queueCtor,
						queueOffer, atomicIntClass, atomicIntCtor, sMarker, quote, longValueOf, stringLength,
						stringSubstring, stringConcat, loop);
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

		// --- _drain_body(v): for http-handler response marshaling -- a stream drains
		// to its quoted string concatenation; any other value passes through. The chunks
		// come through _stream_read + _await rather than off the queue directly, which
		// is what makes ONE drain serve both stream modes (and leaves the buffered one
		// where it was: that pair takes the chunk, re-enqueues the pill at the end and
		// answers nil).
		{
			Asm a = new Asm();
			int passThrough = a.label();
			emitMarkerTest(a, objectArrayClass, sMarker, 0, passThrough);
			ConstantPool.StringConstant empty = cp.addString("");
			a.ldc(empty.index());
			a.astore(2); // acc (raw)
			int loop = a.label();
			int done = a.label();
			a.bind(loop);
			a.aload(0);
			a.op(Opcode.INVOKESTATIC);
			a.u2(streamReadSelf.index());
			a.op(Opcode.INVOKESTATIC);
			a.u2(awaitSelf.index());
			a.astore(3); // chunk
			a.aload(3);
			a.branch(Opcode.IFNULL, done);
			a.aload(2);
			a.aload(3);
			a.checkcast(stringClass);
			a.op(Opcode.DUP); // [acc, chunk, chunk]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringLength.index()); // [acc, chunk, len]
			a.iconst(1);
			a.op(Opcode.ISUB);
			a.iconst(1);
			a.op(Opcode.SWAP); // [acc, chunk, 1, len-1]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringSubstring.index()); // [acc, raw]
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringConcat.index()); // [acc']
			a.astore(2);
			a.branch(Opcode.GOTO, loop);
			a.bind(done);
			// quote-wrap the accumulation
			a.ldc(quote.index());
			a.aload(2);
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringConcat.index());
			a.ldc(quote.index());
			a.op(Opcode.INVOKEVIRTUAL);
			a.u2(stringConcat.index());
			a.areturn();
			a.bind(passThrough);
			a.aload(0);
			a.areturn();
			methods.add(new AsyncMethod(cp.addUtf8(DRAIN_BODY_METHOD), cp.addUtf8(UNARY_DESC), 4, 4, a.finish(),
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
	 * Emits the {@code _await} branch converting a settled {@code HttpResponse} into
	 * {@code (:status <int> :headers <alist> :body <one-chunk stream>)}. Slot layout
	 * continues the {@code _await} method's (r in slot 4); slots 6..15 are scratch.
	 */
	private static void emitHttpResponseBranch(Asm b, ConstantPool cp, ClassConstant objectClass,
			ClassConstant objectArrayClass, ClassConstant stringClass, ClassConstant queueClass,
			MethodrefConstant queueCtor, MethodrefConstant queueOffer, ClassConstant atomicIntClass,
			MethodrefConstant atomicIntCtor, ConstantPool.StringConstant sMarker, ConstantPool.StringConstant quote,
			MethodrefConstant longValueOf, MethodrefConstant stringLength, MethodrefConstant stringSubstring,
			MethodrefConstant stringConcat, int loopLabel) {
		ClassConstant httpResponseClass = cp.addClass(cp.addUtf8("java/net/http/HttpResponse"));
		MethodrefConstant statusCode = cp.addInterfaceMethodref(httpResponseClass,
				cp.addNameAndType(cp.addUtf8("statusCode"), cp.addUtf8("()I")));
		MethodrefConstant responseBody = cp.addInterfaceMethodref(httpResponseClass,
				cp.addNameAndType(cp.addUtf8("body"), cp.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant responseHeaders = cp.addInterfaceMethodref(httpResponseClass,
				cp.addNameAndType(cp.addUtf8("headers"), cp.addUtf8("()Ljava/net/http/HttpHeaders;")));
		ClassConstant httpHeadersClass = cp.addClass(cp.addUtf8("java/net/http/HttpHeaders"));
		MethodrefConstant headersMap = cp.addMethodref(httpHeadersClass,
				cp.addNameAndType(cp.addUtf8("map"), cp.addUtf8("()Ljava/util/Map;")));
		ClassConstant mapClass = cp.addClass(cp.addUtf8("java/util/Map"));
		MethodrefConstant mapEntrySet = cp.addInterfaceMethodref(mapClass,
				cp.addNameAndType(cp.addUtf8("entrySet"), cp.addUtf8("()Ljava/util/Set;")));
		ClassConstant setClass = cp.addClass(cp.addUtf8("java/util/Set"));
		MethodrefConstant setIterator = cp.addInterfaceMethodref(setClass,
				cp.addNameAndType(cp.addUtf8("iterator"), cp.addUtf8("()Ljava/util/Iterator;")));
		ClassConstant iteratorClass = cp.addClass(cp.addUtf8("java/util/Iterator"));
		MethodrefConstant iteratorHasNext = cp.addInterfaceMethodref(iteratorClass,
				cp.addNameAndType(cp.addUtf8("hasNext"), cp.addUtf8("()Z")));
		MethodrefConstant iteratorNext = cp.addInterfaceMethodref(iteratorClass,
				cp.addNameAndType(cp.addUtf8("next"), cp.addUtf8("()Ljava/lang/Object;")));
		ClassConstant entryClass = cp.addClass(cp.addUtf8("java/util/Map$Entry"));
		MethodrefConstant entryGetKey = cp.addInterfaceMethodref(entryClass,
				cp.addNameAndType(cp.addUtf8("getKey"), cp.addUtf8("()Ljava/lang/Object;")));
		MethodrefConstant entryGetValue = cp.addInterfaceMethodref(entryClass,
				cp.addNameAndType(cp.addUtf8("getValue"), cp.addUtf8("()Ljava/lang/Object;")));
		ClassConstant iterableClass = cp.addClass(cp.addUtf8("java/lang/Iterable"));
		MethodrefConstant stringJoin = cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("join"),
				cp.addUtf8("(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;")));
		ConstantPool.StringConstant comma = cp.addString(", ");

		int notHttp = b.label();
		b.aload(4);
		b.op(Opcode.INSTANCEOF);
		b.u2(httpResponseClass.index());
		b.branch(Opcode.IFEQ, notHttp);
		b.aload(4);
		b.checkcast(httpResponseClass);
		b.astore(6); // response

		// status (slot 7) = Long.valueOf(statusCode())
		b.aload(6);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(statusCode.index());
		b.op(1);
		b.op(0);
		b.op(Opcode.I2L);
		b.op(Opcode.INVOKESTATIC);
		b.u2(longValueOf.index());
		b.astore(7);

		// body stream (slot 8) = {SMARKER, q(quotedBody, pill), state(1)}
		b.op(Opcode.NEW);
		b.u2(queueClass.index());
		b.op(Opcode.DUP);
		b.op(Opcode.INVOKESPECIAL);
		b.u2(queueCtor.index());
		b.astore(9); // q
		b.aload(9);
		b.ldc(quote.index());
		b.aload(6);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(responseBody.index());
		b.op(1);
		b.op(0);
		b.checkcast(stringClass);
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(stringConcat.index());
		b.ldc(quote.index());
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(stringConcat.index()); // [q, quotedBody]
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(queueOffer.index());
		b.op(Opcode.POP);
		b.aload(9);
		b.ldc(sMarker.index());
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(queueOffer.index());
		b.op(Opcode.POP);
		b.iconst(3);
		b.anewarray(objectClass);
		b.op(Opcode.DUP);
		b.iconst(0);
		b.ldc(sMarker.index());
		b.aastore();
		b.op(Opcode.DUP);
		b.iconst(1);
		b.aload(9);
		b.aastore();
		b.op(Opcode.DUP);
		b.iconst(2);
		b.op(Opcode.NEW);
		b.u2(atomicIntClass.index());
		b.op(Opcode.DUP);
		b.iconst(1);
		b.op(Opcode.INVOKESPECIAL);
		b.u2(atomicIntCtor.index());
		b.aastore();
		b.astore(8);

		// header alist (slot 10)
		b.aconstNull();
		b.astore(10);
		b.aload(6);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(responseHeaders.index());
		b.op(1);
		b.op(0);
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(headersMap.index());
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(mapEntrySet.index());
		b.op(1);
		b.op(0);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(setIterator.index());
		b.op(1);
		b.op(0);
		b.astore(11);
		int eLoop = b.label();
		int eEnd = b.label();
		b.bind(eLoop);
		b.aload(11);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(iteratorHasNext.index());
		b.op(1);
		b.op(0);
		b.branch(Opcode.IFEQ, eEnd);
		b.aload(11);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(iteratorNext.index());
		b.op(1);
		b.op(0);
		b.checkcast(entryClass);
		b.astore(12);
		b.iconst(2);
		b.anewarray(objectClass);
		b.astore(13);
		b.aload(13);
		b.iconst(0);
		b.ldc(quote.index());
		b.aload(12);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(entryGetKey.index());
		b.op(1);
		b.op(0);
		b.checkcast(stringClass);
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(stringConcat.index());
		b.ldc(quote.index());
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(stringConcat.index());
		b.aastore();
		b.aload(13);
		b.iconst(1);
		b.ldc(quote.index());
		b.ldc(comma.index());
		b.aload(12);
		b.op(Opcode.INVOKEINTERFACE);
		b.u2(entryGetValue.index());
		b.op(1);
		b.op(0);
		b.checkcast(iterableClass);
		b.op(Opcode.INVOKESTATIC);
		b.u2(stringJoin.index());
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(stringConcat.index());
		b.ldc(quote.index());
		b.op(Opcode.INVOKEVIRTUAL);
		b.u2(stringConcat.index());
		b.aastore();
		// alist = cons(pair, alist)
		b.iconst(2);
		b.anewarray(objectClass);
		b.op(Opcode.DUP);
		b.iconst(0);
		b.aload(13);
		b.aastore();
		b.op(Opcode.DUP);
		b.iconst(1);
		b.aload(10);
		b.aastore();
		b.astore(10);
		b.branch(Opcode.GOTO, eLoop);
		b.bind(eEnd);

		// The fetch result plist, built tail-first in slot 14. The shape (keys, order)
		// is derived from the http-plist WIT response record; only the per-field value
		// slot is this backend's, so an unmapped record field fails the compile loudly.
		Map<String, Integer> responseValueSlot = Map.of("status", 7, "headers", 10, "body", 8);
		b.aconstNull();
		b.astore(14);
		List<FetchResponseShape.Field> responseFields = FetchResponseShape.responseFields();
		for (int i = responseFields.size() - 1; i >= 0; i--) {
			FetchResponseShape.Field field = responseFields.get(i);
			Integer valueSlot = responseValueSlot.get(field.name());
			if (valueSlot == null) {
				throw new IllegalStateException(
						"The fetch JVM runtime has no value slot for response field " + field.name());
			}
			consSlot(b, objectClass, valueSlot, 14);
			b.astore(14);
			consLdc(b, objectClass, cp.addString(field.keyword()), 14);
			b.astore(14);
		}
		b.aload(14);
		b.areturn();
		b.bind(notHttp);
	}

	/** Pushes {@code new Object[]{ aload(carSlot), aload(cdrSlot) }}. */
	private static void consSlot(Asm a, ClassConstant objectClass, int carSlot, int cdrSlot) {
		a.iconst(2);
		a.anewarray(objectClass);
		a.op(Opcode.DUP);
		a.iconst(0);
		a.aload(carSlot);
		a.aastore();
		a.op(Opcode.DUP);
		a.iconst(1);
		a.aload(cdrSlot);
		a.aastore();
	}

	/** Pushes {@code new Object[]{ <interned keyword>, aload(cdrSlot) }}. */
	private static void consLdc(Asm a, ClassConstant objectClass, ConstantPool.StringConstant sym, int cdrSlot) {
		a.iconst(2);
		a.anewarray(objectClass);
		a.op(Opcode.DUP);
		a.iconst(0);
		a.ldc(sym.index());
		a.aastore();
		a.op(Opcode.DUP);
		a.iconst(1);
		a.aload(cdrSlot);
		a.aastore();
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
