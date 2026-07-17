package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the first-class future runtime of the {@code --component} async state machines
 * (emitted only when the program uses {@code rontolisp:async-defun}/{@code async-lambda}/
 * {@code await}; every other module is byte-identical to a build that never knew about
 * it). A future is a {@code TYPE_FUTURE} struct {@code {mut i32 state, mut value, mut
 * waiters, mut source}} with state 0 = pending, 1 = fulfilled (value = the result), 2 =
 * rejected (value = the {@code $lisp-cond} payload cons). {@code waiters} is a cons list
 * of resume closures ({@code TYPE_CLOSURE} over a suspended function's
 * {@code TYPE_ASYNC_FRAME}); {@code source} is reserved for the host-waitable registry.
 *
 * <p>
 * Waiter wake-up is a direct call in this tier (no microtask queue): settling a future
 * resumes each waiter through the arity-1 dispatch function; a waiter that runs to
 * completion settles its own frame's future in turn (the cascade), one that suspends
 * again has already re-registered itself, and one whose resumed code signals an uncaught
 * condition rejects its frame's future with the caught payload.
 */
final class WasmFutureRuntimeBuilder {

	/** Number of functions this block appends (in {@code build} index order). */
	static final int FUNC_COUNT = 10;

	static final int OFF_NEW = 0;

	static final int OFF_SETTLE = 1;

	static final int OFF_REJECT = 2;

	static final int OFF_ADD_WAITER = 3;

	static final int OFF_WAKE = 4;

	static final int OFF_POLL = 5;

	/**
	 * {@code _subtask_future(token, lift) -> future}: the import layer's bridge from an
	 * async-lowered call to a first-class future. An eagerly-completed call (status
	 * RETURNED, no subtask) lifts immediately into a settled future; anything else
	 * becomes a pending future whose {@code (subtask, future, lift, token)} entry is
	 * pushed onto the scheduler registry and whose subtask is joined into the task's
	 * waitable-set, to be settled by {@code _sched_loop} (or, in serve mode, the
	 * callback) when the subtask reports RETURNED.
	 */
	static final int OFF_SUBTASK_FUTURE = 6;

	/**
	 * {@code _sched_loop(future) -> value}: the blocking event loop that drives a pending
	 * future to completion from a synchronous boundary (the implicit top-level async
	 * function's entry, and a wasm-export wrapper whose async target suspended). Blocking
	 * {@code waitable-set.wait} is base component-model-async and legal from an
	 * async-typed task; each RETURNED subtask event lifts its registry entry's result,
	 * drops the subtask and settles the entry's future (waking waiters, which may settle
	 * the driven future in cascade). A rejected future re-signals through
	 * {@code _future_poll} on exit.
	 */
	static final int OFF_SCHED_LOOP = 7;

	/**
	 * {@code _wasi_stream_read(stream) -> future}: the {@code rontolisp:stream-read} of a
	 * {@code TYPE_WASI_STREAM}. At EOF the future settles to nil; otherwise the read
	 * thunk (an arity-0 closure over the wasi byte-stream handle) produces the next chunk
	 * -- nil flips {@code eof} and runs the close protocol once -- and the chunk (or nil)
	 * comes back as a settled future. The thunk's underlying built-in blocks the task
	 * while a chunk is in flight (the pending-future upgrade is future work).
	 */
	static final int OFF_WSTREAM_READ = 8;

	/**
	 * {@code _wasi_stream_close(stream) -> nil}: the {@code rontolisp:stream-close} of a
	 * {@code TYPE_WASI_STREAM} -- flips {@code eof} and runs the close protocol thunk,
	 * once (a drained or already-closed stream is a no-op).
	 */
	static final int OFF_WSTREAM_CLOSE = 9;

	/**
	 * The module-level scheduler wiring {@code _subtask_future} and {@code _sched_loop}
	 * embed, or {@code null} when the program binds no async-calling interface (their
	 * bodies are then unreachable stubs -- nothing can produce a host-backed pending
	 * future).
	 *
	 * @param ordinals the chosen interface's waitable builtin import-slot ordinals (any
	 * interface's trio works: they alias the same canonical built-ins)
	 * @param registryGlobal the global index of the scheduler registry (a cons list of
	 * {@code (subtask . (future . (lift . token)))} entries)
	 * @param setGlobal the global index of the task waitable-set handle (0 = not yet
	 * created)
	 * @param allocFuncIndex the function index of {@code __ronto_alloc} (the event
	 * payload scratch)
	 */
	record Sched(WasmComponentImportCompiler.WaitOrdinals ordinals, int registryGlobal, int setGlobal,
			int allocFuncIndex) {
	}

	private WasmFutureRuntimeBuilder() {
	}

	/**
	 * Returns the function type index of block member {@code off} (reusing existing
	 * module types; the void-like helpers return nil under a callable type).
	 * @param off the {@code OFF_*} member offset
	 * @return the type index
	 */
	static int typeIndexOf(int off) {
		return switch (off) {
			case OFF_NEW -> WasmLispCompiler.TYPE_READ_LINE; // () -> eq
			case OFF_SETTLE, OFF_REJECT, OFF_ADD_WAITER, OFF_SUBTASK_FUTURE -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1; // (eq,eq)->eq
			default -> WasmLispCompiler.TYPE_CALLABLE_BASE; // (eq)->eq
		};
	}

	/**
	 * Builds the body of block member {@code off}.
	 * @param off the {@code OFF_*} member offset
	 * @param base the block's base function index ({@code _future_new})
	 * @param futureType the {@code TYPE_FUTURE} type index
	 * @param frameType the {@code TYPE_ASYNC_FRAME} type index
	 * @param streamType the {@code TYPE_WASI_STREAM} type index
	 * @param sched the scheduler wiring, or {@code null} when the module binds no
	 * async-calling interface
	 * @return the function body bytes (locals declaration included)
	 */
	static byte[] build(int off, int base, int futureType, int frameType, int streamType,
			@org.jspecify.annotations.Nullable Sched sched) {
		return switch (off) {
			case OFF_NEW -> buildNew(futureType);
			case OFF_SETTLE -> buildSettleOrReject(base, futureType, 1);
			case OFF_REJECT -> buildSettleOrReject(base, futureType, 2);
			case OFF_ADD_WAITER -> buildAddWaiter(futureType);
			case OFF_WAKE -> buildWake(base, futureType, frameType);
			case OFF_POLL -> buildPoll(futureType);
			case OFF_SUBTASK_FUTURE -> sched == null ? buildUnreachableStub() : buildSubtaskFuture(futureType, sched);
			case OFF_SCHED_LOOP -> sched == null ? buildUnreachableStub() : buildSchedLoop(base, futureType, sched);
			case OFF_WSTREAM_READ -> buildWasiStreamRead(futureType, streamType);
			case OFF_WSTREAM_CLOSE -> buildWasiStreamClose(streamType);
			default -> throw new IllegalArgumentException("unknown future runtime member: " + off);
		};
	}

	// _future_new () -> (ref null eq): a fresh pending future.
	private static byte[] buildNew(int futureType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		i32(w, 0);
		refNullEq(w);
		refNullEq(w);
		refNullEq(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(futureType);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _future_settle / _future_reject (future, value-or-payload) -> nil: first settle
	// wins (idempotent), then wakes the waiters.
	private static byte[] buildSettleOrReject(int base, int futureType, int state) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		// Already settled: return nil.
		castFuture(w, 0, futureType);
		structGet(w, futureType, 0);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		refNullEq(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// state = 1|2, value = arg.
		castFuture(w, 0, futureType);
		i32(w, state);
		structSet(w, futureType, 0);
		castFuture(w, 0, futureType);
		getLocal(w, 1);
		structSet(w, futureType, 1);
		// Tail: wake the waiters (returns nil).
		getLocal(w, 0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_WAKE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _future_add_waiter (future, resumeClosure) -> nil: FIFO-appends a waiter node.
	private static byte[] buildAddWaiter(int futureType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int NODE = 2, CUR = 3;
		// locals: 2x (ref null eq)
		w.write(1);
		w.writeUnsignedLeb128(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// node = (closure . nil)
		getLocal(w, 1);
		refNullEq(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		setLocal(w, NODE);
		// Empty list: waiters = node.
		castFuture(w, 0, futureType);
		structGet(w, futureType, 2);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		castFuture(w, 0, futureType);
		getLocal(w, NODE);
		structSet(w, futureType, 2);
		refNullEq(w);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// Walk to the last node.
		castFuture(w, 0, futureType);
		structGet(w, futureType, 2);
		setLocal(w, CUR);
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// last.cdr = node
		castCons(w, CUR);
		getLocal(w, NODE);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		refNullEq(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _future_wake (future) -> nil: takes the waiter list and resumes each closure
	// through the arity-1 dispatch; a completed frame settles its own future, an
	// uncaught condition from the resumed code rejects it.
	private static byte[] buildWake(int base, int futureType, int frameType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int LIST = 1, CLOSURE = 2, FRAME = 3, RESULT = 4, PAYLOAD = 5;
		// locals: 5x (ref null eq)
		w.write(1);
		w.writeUnsignedLeb128(5);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// list = fut.waiters; fut.waiters = nil
		castFuture(w, 0, futureType);
		structGet(w, futureType, 2);
		setLocal(w, LIST);
		castFuture(w, 0, futureType);
		refNullEq(w);
		structSet(w, futureType, 2);
		// block $done / loop $next
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY); // $done
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY); // $next
		getLocal(w, LIST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1); // -> $done
		castCons(w, LIST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		setLocal(w, CLOSURE);
		castCons(w, LIST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, LIST);
		getLocal(w, CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		structGet(w, WasmLispCompiler.TYPE_CLOSURE, 1);
		setLocal(w, FRAME);
		// block $caught (result eq): the rejection payload lands here.
		w.write(Instruction.BLOCK);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// try_table (result eq) (catch $lisp-cond -> $caught)
		w.write(Instruction.TRY_TABLE);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.writeUnsignedLeb128(1);
		w.write(Instruction.CATCH);
		w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		w.writeUnsignedLeb128(0); // -> $caught
		getLocal(w, CLOSURE);
		refNullEq(w);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 1);
		w.write(Instruction.END); // try_table
		setLocal(w, RESULT);
		// Still suspended (the resume returned its own frame): nothing to do.
		getLocal(w, RESULT);
		getLocal(w, FRAME);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.BR_IF, 1); // -> $next (continue), depths: $caught=0, loop=1
		// Completed: settle the frame's future with the result (cascades).
		getLocal(w, FRAME);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(frameType);
		structGet(w, frameType, 2);
		getLocal(w, RESULT);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_SETTLE);
		w.write(Instruction.DROP);
		w.write(Instruction.BR, 1); // -> $next
		w.write(Instruction.END); // block $caught; payload on stack
		setLocal(w, PAYLOAD);
		getLocal(w, FRAME);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(frameType);
		structGet(w, frameType, 2);
		getLocal(w, PAYLOAD);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_REJECT);
		w.write(Instruction.DROP);
		w.write(Instruction.BR, 0); // -> $next
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block $done
		refNullEq(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _future_poll (v) -> (ref null eq): resolves settled futures (flattening chains);
	// returns a PENDING future unchanged (the await site suspends on it); throws the
	// payload of a rejected future on $lisp-cond (the memoized re-signal at await). A
	// TYPE_P1_FUTURE cannot reach an asyncMode module (its only producer, %async-run, is
	// the non-asyncMode lowering), so there is no degenerate-future branch.
	private static byte[] buildPoll(int futureType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int STATE = 1;
		// locals: 1x i32
		w.write(1);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY);
		// TYPE_FUTURE: dispatch on state.
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(futureType);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		castFuture(w, 0, futureType);
		structGet(w, futureType, 0);
		setLocal(w, STATE);
		// pending -> the future itself
		getLocal(w, STATE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		getLocal(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// fulfilled -> flatten
		getLocal(w, STATE);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		castFuture(w, 0, futureType);
		structGet(w, futureType, 1);
		setLocal(w, 0);
		w.write(Instruction.BR, 2); // -> loop (past this if and the TYPE_FUTURE if)
		w.write(Instruction.END);
		// rejected -> re-signal the memoized payload
		castFuture(w, 0, futureType);
		structGet(w, futureType, 1);
		w.write(Instruction.THROW);
		w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		w.write(Instruction.END); // if TYPE_FUTURE
		// A plain value passes through.
		getLocal(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _wasi_stream_read (stream) -> settled future of the next chunk (nil = EOF; the
	// first EOF runs the close protocol once).
	private static byte[] buildWasiStreamRead(int futureType, int streamType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int S = 0, CHUNK = 1;
		// locals: 1x (ref null eq)
		w.write(1);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// Drained already: a settled-nil future.
		castStream(w, S, streamType);
		structGet(w, streamType, 0);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		settledFuture(w, futureType, () -> refNullEq(w));
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// CHUNK = dispatch_0(readFn)
		castStream(w, S, streamType);
		structGet(w, streamType, 1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE);
		setLocal(w, CHUNK);
		// EOF: flip eof, run the close protocol once, settle to nil.
		getLocal(w, CHUNK);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		emitCloseOnce(w, S, streamType);
		w.write(Instruction.END);
		settledFuture(w, futureType, () -> getLocal(w, CHUNK));
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _wasi_stream_close (stream) -> nil: run the close protocol once.
	private static byte[] buildWasiStreamClose(int streamType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int S = 0;
		w.write(0); // no locals
		castStream(w, S, streamType);
		structGet(w, streamType, 0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		emitCloseOnce(w, S, streamType);
		w.write(Instruction.END);
		refNullEq(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// eof = 1; dispatch_0(closeFn); drop.
	private static void emitCloseOnce(WasmWriter w, int slot, int streamType) {
		castStream(w, slot, streamType);
		i32(w, 1);
		structSet(w, streamType, 0);
		castStream(w, slot, streamType);
		structGet(w, streamType, 2);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE);
		w.write(Instruction.DROP);
	}

	// struct.new futureType {1, <value>, null, null} -- a settled future.
	private static void settledFuture(WasmWriter w, int futureType, Runnable value) {
		i32(w, 1);
		value.run();
		refNullEq(w);
		refNullEq(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(futureType);
	}

	private static void castStream(WasmWriter w, int slot, int streamType) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(streamType);
	}

	// A stub body for the scheduler members of a module that binds no async-calling
	// interface: nothing can produce a host-backed pending future there, so reaching
	// one is a compiler bug worth trapping on.
	private static byte[] buildUnreachableStub() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _subtask_future (token, lift) -> future. token = the async-lowered call's
	// (packed . retptr) cons; lift = the member's lift wrapper as a function value.
	private static byte[] buildSubtaskFuture(int futureType, Sched sched) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int TOKEN = 0, FN = 1, PACKED = 2, SUB = 3, FUT = 4;
		// locals: 2x i32, 1x (ref null eq)
		w.write(2);
		w.writeUnsignedLeb128(2);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// PACKED = i31get(car(token))
		castCons(w, TOKEN);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		setLocal(w, PACKED);
		// Eagerly completed (status RETURNED, no subtask): lift now, settled future.
		getLocal(w, PACKED);
		i32(w, 0xF);
		w.write(Instruction.I32_AND);
		i32(w, WasmComponentImportCompiler.SUBTASK_STATUS_RETURNED);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		i32(w, 1);
		getLocal(w, FN);
		getLocal(w, TOKEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 1);
		refNullEq(w);
		refNullEq(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(futureType);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// Pending: register (subtask . (future . (lift . token))) and join the subtask
		// into the task waitable-set (created lazily).
		getLocal(w, PACKED);
		i32(w, 4);
		w.write(Instruction.I32_SHR_U);
		setLocal(w, SUB);
		i32(w, 0);
		refNullEq(w);
		refNullEq(w);
		refNullEq(w);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(futureType);
		setLocal(w, FUT);
		// registry = ((sub . (fut . (fn . token))) . registry)
		getLocal(w, SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		getLocal(w, FUT);
		getLocal(w, FN);
		getLocal(w, TOKEN);
		newCons(w); // (fn . token)
		newCons(w); // (fut . ...)
		newCons(w); // (sub . ...)
		globalGet(w, sched.registryGlobal());
		newCons(w);
		globalSet(w, sched.registryGlobal());
		// Lazily create the task waitable-set.
		globalGet(w, sched.setGlobal());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		callOrdinal(w, sched.ordinals().setNew());
		globalSet(w, sched.setGlobal());
		w.write(Instruction.END);
		// waitable.join(sub, set)
		getLocal(w, SUB);
		globalGet(w, sched.setGlobal());
		callOrdinal(w, sched.ordinals().join());
		getLocal(w, FUT);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _sched_loop (future) -> value: block on the task waitable-set until the driven
	// future settles, settling each RETURNED subtask's registry future on the way (the
	// waiter cascade may settle the driven one). The exit polls: a settled chain
	// flattens to the value, a rejection re-signals on $lisp-cond.
	private static byte[] buildSchedLoop(int base, int futureType, Sched sched) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int FUT = 0, EVTP = 1, EV = 2, WAITABLE = 3, PREV = 4, CUR = 5, ENTRY = 6, REST = 7, VAL = 8;
		// locals: 3x i32, 5x (ref null eq)
		w.write(2);
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);
		w.writeUnsignedLeb128(5);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// EVTP = __ronto_alloc(8): the (waitable, state) event payload scratch.
		i32(w, 8);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(sched.allocFuncIndex());
		setLocal(w, EVTP);
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY); // $done
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY); // $next
		// Settled: exit the loop.
		castFuture(w, FUT, futureType);
		structGet(w, futureType, 0);
		w.write(Instruction.BR_IF, 1); // -> $done
		// A pending future with no waitable-set is a deadlock: nothing can settle it.
		globalGet(w, sched.setGlobal());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		// EV = waitable-set.wait(set, evtp) -- blocking, base component-model-async.
		globalGet(w, sched.setGlobal());
		getLocal(w, EVTP);
		callOrdinal(w, sched.ordinals().setWait());
		setLocal(w, EV);
		// A subtask that reports RETURNED settles its registry entry; every other
		// event (a STARTED transition, an unknown waitable) is ignored.
		getLocal(w, EV);
		i32(w, WasmComponentImportCompiler.EVENT_SUBTASK);
		w.write(Instruction.I32_EQ);
		load32(w, EVTP, 4);
		i32(w, WasmComponentImportCompiler.SUBTASK_STATE_RETURNED);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		load32(w, EVTP, 0);
		setLocal(w, WAITABLE);
		// Find (and unlink) the registry entry whose subtask == WAITABLE.
		refNullEq(w);
		setLocal(w, PREV);
		globalGet(w, sched.registryGlobal());
		setLocal(w, CUR);
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY); // $miss
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY); // $found
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY); // $walk
		getLocal(w, CUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 2); // -> $miss
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		setLocal(w, ENTRY);
		castCons(w, ENTRY);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		getLocal(w, WAITABLE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 1); // -> $found
		getLocal(w, CUR);
		setLocal(w, PREV);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR);
		w.write(Instruction.BR, 0); // -> $walk
		w.write(Instruction.END); // loop $walk
		w.write(Instruction.END); // block $found
		// Unlink CUR from the registry.
		getLocal(w, PREV);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		globalSet(w, sched.registryGlobal());
		w.write(Instruction.ELSE);
		castCons(w, PREV);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.END);
		// REST = cdr(ENTRY) = (future . (lift . token))
		castCons(w, ENTRY);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, REST);
		// VAL = dispatch_1(lift, token)
		castCons(w, REST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		setLocal(w, ENTRY); // reuse: the (lift . token) pair
		castCons(w, ENTRY);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		castCons(w, ENTRY);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 1);
		setLocal(w, VAL);
		// subtask.drop, then settle the entry's future (wakes its waiters).
		getLocal(w, WAITABLE);
		callOrdinal(w, sched.ordinals().subtaskDrop());
		castCons(w, REST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		getLocal(w, VAL);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_SETTLE);
		w.write(Instruction.DROP);
		w.write(Instruction.END); // block $miss
		w.write(Instruction.END); // if RETURNED
		w.write(Instruction.BR, 0); // -> $next
		w.write(Instruction.END); // loop $next
		w.write(Instruction.END); // block $done
		// The exit: flatten the settled chain / re-signal a rejection.
		getLocal(w, FUT);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_POLL);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static void unboxI31(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	private static void newCons(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	private static void globalGet(WasmWriter w, int index) {
		w.write(Instruction.GET_GLOBAL);
		w.writeSignedLeb128(index);
	}

	private static void globalSet(WasmWriter w, int index) {
		w.write(Instruction.SET_GLOBAL);
		w.writeSignedLeb128(index);
	}

	private static void callOrdinal(WasmWriter w, int ordinal) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + ordinal);
	}

	private static void load32(WasmWriter w, int addrLocal, int offset) {
		getLocal(w, addrLocal);
		w.write(Instruction.I32_LOAD, 0x02);
		w.writeUnsignedLeb128(offset);
	}

	private static void castFuture(WasmWriter w, int slot, int futureType) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(futureType);
	}

	private static void castCons(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(type);
		w.writeSignedLeb128(field);
	}

	private static void structSet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(type);
		w.writeSignedLeb128(field);
	}

	private static void refNullEq(WasmWriter w) {
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

}
