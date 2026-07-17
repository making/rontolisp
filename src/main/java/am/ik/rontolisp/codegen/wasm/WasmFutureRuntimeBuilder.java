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
	static final int FUNC_COUNT = 16;

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
	 * thunk (an arity-0 closure over the wasi byte-stream handle) issues one built-in
	 * read. A read that completes immediately comes back as a settled future of the chunk
	 * (nil = EOF, which flips {@code eof} and runs the close protocol once); a read the
	 * host reports BLOCKED comes back as the thunk's PENDING future -- the wrapper has
	 * already registered it (kind 1) and joined the stream handle into the task
	 * waitable-set, so this helper only attaches the stream struct to the registry entry
	 * (the scheduler needs it to run the close protocol when the completion event turns
	 * out to be EOF) and returns the future unwrapped: the task stays runnable while the
	 * chunk is in flight.
	 */
	static final int OFF_WSTREAM_READ = 8;

	/**
	 * {@code _wasi_stream_close(stream) -> nil}: the {@code rontolisp:stream-close} of a
	 * {@code TYPE_WASI_STREAM} -- flips {@code eof} and runs the close protocol thunk,
	 * once (a drained or already-closed stream is a no-op).
	 */
	static final int OFF_WSTREAM_CLOSE = 9;

	/**
	 * {@code _wake_list(waiters) -> nil}: resumes each waiter closure of a cons list. A
	 * waiter whose frame belongs to the CURRENT task (or to no task -- a synchronous
	 * boundary's frame, which never calls {@code task.return}) is resumed directly; one
	 * owned by ANOTHER task must not run in this task's context ({@code task.return} and
	 * the context slots are per-task), so it is appended to the owner's ready list and
	 * the owner's doorbell rung (on the empty-to-nonempty transition only -- the owner's
	 * standing read is pending exactly then, so the write completes immediately). The
	 * owner's callback (or its suspend path, for a ring that beat the doorbell's
	 * creation) drains the ready list on its own task.
	 */
	static final int OFF_WAKE_LIST = 10;

	/**
	 * {@code _sched_dispatch(event, waitable, code) -> 0}: the event-dispatch core shared
	 * by the blocking driver ({@code _sched_loop}) and the callback driver
	 * ({@code _async_cb}). A subtask that reports RETURNED lifts its registry entry's
	 * result and drops the subtask (kind 0); a stream-read completion lifts the chunk out
	 * of the staged buffer, recycles the buffer, unjoins the stream handle and runs the
	 * close protocol at EOF (kind 1); the entry's future is settled (waking waiters,
	 * possibly across tasks). Every other event is ignored.
	 */
	static final int OFF_SCHED_DISPATCH = 11;

	/**
	 * {@code _task_begin() -> nil}: the prologue of a callback-lifted export wrapper
	 * (serve's {@code handle}). Allocates a fresh task record
	 * {@code (id . (root-future . (doorbell-rx . (doorbell-tx . (waitable-set .
	 * ready-list)))))}, makes it the CURRENT task (frames created while it runs are owned
	 * by it) and resets the task waitable-set global to 0 so this task lazily creates its
	 * own set.
	 */
	static final int OFF_TASK_BEGIN = 12;

	/**
	 * {@code _task_suspend(future) -> code}: the pending path of a callback-lifted export
	 * wrapper. Records the root future, ensures the task waitable-set, creates the task's
	 * doorbell stream (arming the standing read and joining its readable end into the
	 * set), registers the task record for callback lookup, stores the task id in context
	 * slot 0 and the set handle in the record, then delegates to {@code _task_finish} --
	 * which drains any ready waiters deposited before the doorbell existed and returns
	 * the packed WAIT code (or EXIT, when a drained waiter completed the root).
	 */
	static final int OFF_TASK_SUSPEND = 13;

	/**
	 * {@code _task_finish(ignored) -> code}: drains the CURRENT task's ready list, then
	 * inspects its root future: pending = the packed {@code WAIT | (waitable-set << 4)}
	 * code, fulfilled = unlink the task record and EXIT (0) -- {@code task.return}
	 * already delivered the result mid-task -- and rejected = trap (the
	 * uncaught-condition shape of an exported function).
	 */
	static final int OFF_TASK_FINISH = 14;

	/**
	 * {@code _async_cb(event, waitable, code) -> code}: the REAL callback of a
	 * callback-lifted export, core-exported as {@code async_cb}. Restores the task
	 * identity from context slot 0 (the task id; the waitable-set handle comes back out
	 * of the found record), re-arms and drains on a doorbell completion, dispatches every
	 * other event through {@code _sched_dispatch}, and finishes with {@code _task_finish}
	 * (WAIT again or EXIT).
	 */
	static final int OFF_ASYNC_CB = 15;

	/**
	 * The module-level scheduler wiring {@code _subtask_future}, {@code _sched_loop} and
	 * the pending-read side of {@code _wasi_stream_read} embed, or {@code null} when the
	 * program binds no async-calling interface (their bodies are then unreachable stubs
	 * -- nothing can produce a host-backed pending future).
	 *
	 * <p>
	 * A registry entry is {@code (waitable . (kind . (future . data)))}, keyed by the
	 * waitable index the event loop receives (subtasks and stream/future end handles
	 * share the instance's one waitable index space, so one key never collides): kind 0 =
	 * a subtask whose {@code data} is the {@code (lift . token)} pair, kind 1 = an
	 * in-flight stream read whose {@code data} is {@code (buf . stream)} -- the staged
	 * chunk buffer (an i31 pointer, alive until the completion event) and the owning
	 * {@code TYPE_WASI_STREAM} (or nil for a raw read), whose close protocol the
	 * settlement runs at EOF.
	 *
	 * @param ordinals the chosen interface's waitable builtin import-slot ordinals (any
	 * interface's trio works: they alias the same canonical built-ins)
	 * @param registryGlobal the global index of the scheduler registry (a cons list of
	 * entries as above)
	 * @param setGlobal the global index of the task waitable-set handle (0 = not yet
	 * created)
	 * @param readFreeGlobal the global index of the read-buffer free list (a cons list of
	 * i31 buffer pointers; every completed read returns its buffer here, so the
	 * linear-memory cost is bounded by the maximum number of CONCURRENT reads)
	 * @param allocFuncIndex the function index of {@code __ronto_alloc} (the event
	 * payload scratch)
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem} (lifts a
	 * completed read's chunk bytes onto the GC heap)
	 */
	record Sched(WasmComponentImportCompiler.WaitOrdinals ordinals, int registryGlobal, int setGlobal,
			int readFreeGlobal, int allocFuncIndex, int strFromMemFuncIndex) {
	}

	/**
	 * The callback-task wiring of a module with a callback-lifted export (serve's
	 * {@code handle}), or {@code null} elsewhere (the task/callback members are then
	 * unreachable stubs, and {@code _wake_list}'s cross-task arm -- unreachable there,
	 * because no frame ever gets a non-null owner -- traps).
	 *
	 * @param ctxGet0 the {@code context.get} slot-0 import-slot ordinal (the task id --
	 * the ONE slot wasmtime 46 supports; the waitable-set handle lives in the task
	 * record)
	 * @param ctxSet0 the {@code context.set} slot-0 ordinal
	 * @param dbNew the doorbell {@code stream.new} ordinal (u64 payload; readable end =
	 * low 32 bits, writable = high)
	 * @param dbRead the doorbell async {@code stream.read} ordinal
	 * @param dbWrite the doorbell async {@code stream.write} ordinal
	 * @param wsNew the scheduler's own {@code waitable-set.new} ordinal
	 * @param wJoin the scheduler's own {@code waitable.join} ordinal
	 * @param setGlobal the global index of the CURRENT task's waitable-set handle (the
	 * same global {@link Sched#setGlobal()} names)
	 * @param tasksGlobal the global index of the task-record list (callback lookup)
	 * @param taskSeqGlobal the global index of the task-id counter (a {@code (mut
	 * i32)})
	 */
	record Cb(int ctxGet0, int ctxSet0, int dbNew, int dbRead, int dbWrite, int wsNew, int wJoin, int setGlobal,
			int tasksGlobal, int taskSeqGlobal) {
	}

	private WasmFutureRuntimeBuilder() {
	}

	/**
	 * Returns the function type index of block member {@code off} (reusing existing
	 * module types where one fits; the void-like helpers return nil under a callable
	 * type, and the i32-shaped members use the asyncMode callback type).
	 * @param off the {@code OFF_*} member offset
	 * @param asyncCbTypeIndex the {@code (i32,i32,i32) -> i32} callback type index
	 * (asyncTypeBase + 3)
	 * @return the type index
	 */
	static int typeIndexOf(int off, int asyncCbTypeIndex) {
		return switch (off) {
			case OFF_NEW, OFF_TASK_BEGIN -> WasmLispCompiler.TYPE_READ_LINE; // () -> eq
			case OFF_SETTLE, OFF_REJECT, OFF_ADD_WAITER, OFF_SUBTASK_FUTURE -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1; // (eq,eq)->eq
			case OFF_SCHED_DISPATCH, OFF_ASYNC_CB -> asyncCbTypeIndex; // (i32,i32,i32)->i32
			case OFF_TASK_SUSPEND, OFF_TASK_FINISH -> WasmLispCompiler.TYPE_RAT_GET; // (eq)->i32
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
	 * @param currentTaskGlobal the global index of the CURRENT task record
	 * @param sched the scheduler wiring, or {@code null} when the module binds no
	 * async-calling interface
	 * @param cb the callback-task wiring, or {@code null} when the module has no
	 * callback-lifted export
	 * @return the function body bytes (locals declaration included)
	 */
	static byte[] build(int off, int base, int futureType, int frameType, int streamType, int currentTaskGlobal,
			@org.jspecify.annotations.Nullable Sched sched, @org.jspecify.annotations.Nullable Cb cb) {
		return switch (off) {
			case OFF_NEW -> buildNew(futureType);
			case OFF_SETTLE -> buildSettleOrReject(base, futureType, 1);
			case OFF_REJECT -> buildSettleOrReject(base, futureType, 2);
			case OFF_ADD_WAITER -> buildAddWaiter(futureType);
			case OFF_WAKE -> buildWake(base, futureType);
			case OFF_POLL -> buildPoll(futureType);
			case OFF_SUBTASK_FUTURE -> sched == null ? buildUnreachableStub() : buildSubtaskFuture(futureType, sched);
			case OFF_SCHED_LOOP -> sched == null ? buildUnreachableStub() : buildSchedLoop(base, futureType, sched);
			case OFF_WSTREAM_READ -> buildWasiStreamRead(futureType, streamType, sched);
			case OFF_WSTREAM_CLOSE -> buildWasiStreamClose(streamType);
			case OFF_WAKE_LIST -> buildWakeList(base, futureType, frameType, currentTaskGlobal, cb);
			case OFF_SCHED_DISPATCH ->
				sched == null ? buildUnreachableStub() : buildSchedDispatch(base, streamType, sched);
			case OFF_TASK_BEGIN -> cb == null ? buildUnreachableStub() : buildTaskBegin(currentTaskGlobal, cb);
			case OFF_TASK_SUSPEND ->
				cb == null ? buildUnreachableStub() : buildTaskSuspend(base, currentTaskGlobal, cb);
			case OFF_TASK_FINISH ->
				cb == null ? buildUnreachableStub() : buildTaskFinish(base, futureType, currentTaskGlobal, cb);
			case OFF_ASYNC_CB -> cb == null ? buildUnreachableStub() : buildAsyncCb(base, currentTaskGlobal, cb);
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

	// _future_wake (future) -> nil: takes the waiter list (clearing it) and hands it to
	// _wake_list.
	private static byte[] buildWake(int base, int futureType) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int LIST = 1;
		// locals: 1x (ref null eq)
		w.write(1);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// list = fut.waiters; fut.waiters = nil
		castFuture(w, 0, futureType);
		structGet(w, futureType, 2);
		setLocal(w, LIST);
		castFuture(w, 0, futureType);
		refNullEq(w);
		structSet(w, futureType, 2);
		getLocal(w, LIST);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_WAKE_LIST);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _wake_list (waiters) -> nil: resumes each closure through the arity-1 dispatch; a
	// completed frame settles its own future (the cascade), an uncaught condition from
	// the resumed code rejects it. A waiter owned by ANOTHER task is deferred to that
	// task's ready list instead (its doorbell rung on the empty-to-nonempty transition;
	// a doorbell not yet created -- the owner is still in its eager phase -- skips the
	// ring, and the owner's suspend path drains the list).
	private static byte[] buildWakeList(int base, int futureType, int frameType, int currentTaskGlobal,
			WasmFutureRuntimeBuilder.@org.jspecify.annotations.Nullable Cb cb) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int LIST = 0;
		final int WASEMPTY = 1, TX = 2, RING = 3;
		final int CLOSURE = 4, FRAME = 5, RESULT = 6, PAYLOAD = 7, OWNER = 8, CELL = 9;
		// locals: 3x i32, 6x (ref null eq)
		w.write(2);
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);
		w.writeUnsignedLeb128(6);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
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
		getLocal(w, FRAME);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(frameType);
		structGet(w, frameType, 4);
		setLocal(w, OWNER);
		// Defer to the owner when the frame belongs to a DIFFERENT task (a null owner
		// is a synchronous boundary's frame -- no task.return inside -- and is resumed
		// directly by whichever task settled the future).
		getLocal(w, OWNER);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		getLocal(w, OWNER);
		globalGet(w, currentTaskGlobal);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		if (cb == null) {
			// No callback-lifted export: no frame can carry a non-null owner.
			w.write(Instruction.UNREACHABLE);
		}
		else {
			// CELL = the owner record's (doorbell-tx . (set . ready)) cons.
			castCons(w, OWNER);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			setLocal(w, CELL);
			// TX = the owner's doorbell writable end, then CELL = the (set . ready)
			// cons the ready list hangs off.
			castCons(w, CELL);
			structGet(w, WasmLispCompiler.TYPE_CONS, 0);
			unboxI31(w);
			setLocal(w, TX);
			castCons(w, CELL);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			setLocal(w, CELL);
			castCons(w, CELL);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			w.write(Instruction.REF_IS_NULL);
			setLocal(w, WASEMPTY);
			// ready = (closure . ready)
			castCons(w, CELL);
			getLocal(w, CLOSURE);
			castCons(w, CELL);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			newCons(w);
			structSet(w, WasmLispCompiler.TYPE_CONS, 1);
			getLocal(w, WASEMPTY);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			getLocal(w, TX);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			// Ring: one u64 element out of the shared write scratch; the owner's
			// standing read is pending exactly on the empty-to-nonempty transition,
			// so the write completes immediately (a BLOCKED result is a scheduler
			// bug worth trapping on).
			getLocal(w, TX);
			i32(w, WasmLispCompiler.DB_WRITE_SCRATCH_ADDR);
			i32(w, 1);
			callOrdinal(w, cb.dbWrite());
			setLocal(w, RING);
			getLocal(w, RING);
			i32(w, -1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			w.write(Instruction.END); // if TX
			w.write(Instruction.END); // if WASEMPTY
			w.write(Instruction.BR, 1); // -> $next
		}
		w.write(Instruction.END); // if defer
		// Direct resume. block $caught (result eq): the rejection payload lands here.
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

	// _wasi_stream_read (stream) -> future of the next chunk (nil = EOF; the first EOF
	// runs the close protocol once). A read thunk that completed immediately yields a
	// settled future; one the host reported BLOCKED yields the thunk's pending future,
	// whose registry entry (pushed by the read wrapper) gets the stream struct attached
	// so the scheduler can run the close protocol if the completion turns out to be EOF.
	private static byte[] buildWasiStreamRead(int futureType, int streamType,
			@org.jspecify.annotations.Nullable Sched sched) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int S = 0, CHUNK = 1, CUR = 2, REST = 3;
		// locals: 3x (ref null eq)
		w.write(1);
		w.writeUnsignedLeb128(3);
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
		if (sched != null) {
			// A pending read: find the registry entry whose future is CHUNK (the read
			// wrapper pushed it just before returning) and attach the stream struct as
			// the entry data's cdr, then hand the pending future straight to the caller.
			getLocal(w, CHUNK);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(futureType);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			globalGet(w, sched.registryGlobal());
			setLocal(w, CUR);
			w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY); // $out
			w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY); // $walk
			getLocal(w, CUR);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.BR_IF, 1); // -> $out (not registered: a settled future)
			// REST = cdr(cdr(car(CUR))) = (future . data)
			castCons(w, CUR);
			structGet(w, WasmLispCompiler.TYPE_CONS, 0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			setLocal(w, REST);
			castCons(w, REST);
			structGet(w, WasmLispCompiler.TYPE_CONS, 0);
			getLocal(w, CHUNK);
			w.write(Instruction.REF_EQ);
			w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			// data.cdr = the stream struct ((buf . nil) -> (buf . stream))
			castCons(w, REST);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			getLocal(w, S);
			structSet(w, WasmLispCompiler.TYPE_CONS, 1);
			getLocal(w, CHUNK);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
			castCons(w, CUR);
			structGet(w, WasmLispCompiler.TYPE_CONS, 1);
			setLocal(w, CUR);
			w.write(Instruction.BR, 0); // -> $walk
			w.write(Instruction.END); // loop $walk
			w.write(Instruction.END); // block $out
			w.write(Instruction.END); // if TYPE_FUTURE
		}
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
		// Pending: register (subtask . (0 . (future . (lift . token)))) -- kind 0, a
		// subtask entry -- and join the subtask into the task waitable-set (created
		// lazily).
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
		// registry = ((sub . (0 . (fut . (fn . token)))) . registry)
		getLocal(w, SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		getLocal(w, FUT);
		getLocal(w, FN);
		getLocal(w, TOKEN);
		newCons(w); // (fn . token)
		newCons(w); // (fut . ...)
		newCons(w); // (kind . ...)
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

	// _sched_loop (future) -> value: the BLOCKING driver of a synchronous boundary (the
	// top-level _start entry, and a wasm-export wrapper whose async target suspended).
	// Blocks on the task waitable-set until the driven future settles, feeding each
	// event through _sched_dispatch (whose settle cascade may settle the driven one).
	// The exit polls: a settled chain flattens to the value, a rejection re-signals on
	// $lisp-cond. The serve `handle` boundary does NOT use this: it returns the packed
	// WAIT code to the host instead and the events arrive through _async_cb.
	private static byte[] buildSchedLoop(int base, int futureType, Sched sched) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int FUT = 0, EVTP = 1, EV = 2;
		// locals: 2x i32
		w.write(1);
		w.writeUnsignedLeb128(2);
		w.write(Type.I32);
		// EVTP = __ronto_alloc(8): the (waitable, payload) event scratch.
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
		getLocal(w, EV);
		load32(w, EVTP, 0);
		load32(w, EVTP, 4);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_SCHED_DISPATCH);
		w.write(Instruction.DROP);
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

	// _sched_dispatch (event, waitable, code) -> 0: the event-dispatch core. A subtask
	// that reports RETURNED and a stream-read completion settle their registry entries;
	// every other event (a STARTED transition, an unknown waitable) is ignored. A
	// subtask's result is lifted and the subtask dropped (kind 0); a stream-read
	// completion lifts the chunk out of the staged buffer (0 bytes = EOF), recycles the
	// buffer, unjoins the stream handle (it survives for the next read) and runs the
	// stream's close protocol when the completion is EOF (kind 1). Settling wakes the
	// entry's waiters -- possibly deferring cross-task ones through their doorbells.
	private static byte[] buildSchedDispatch(int base, int streamType, Sched sched) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int EV = 0, WAITABLE = 1, CODE = 2;
		final int KIND = 3, N = 4;
		final int PREV = 5, CUR = 6, ENTRY = 7, REST = 8, DATA = 9, VAL = 10;
		// locals: 2x i32, 6x (ref null eq)
		w.write(2);
		w.writeUnsignedLeb128(2);
		w.write(Type.I32);
		w.writeUnsignedLeb128(6);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		getLocal(w, EV);
		i32(w, WasmComponentImportCompiler.EVENT_SUBTASK);
		w.write(Instruction.I32_EQ);
		getLocal(w, CODE);
		i32(w, WasmComponentImportCompiler.SUBTASK_STATE_RETURNED);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		getLocal(w, EV);
		i32(w, WasmComponentImportCompiler.EVENT_STREAM_READ);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		// Find (and unlink) the registry entry whose waitable == WAITABLE.
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
		// cdr(ENTRY) = (kind . (future . data))
		castCons(w, ENTRY);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, REST);
		castCons(w, REST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		setLocal(w, KIND);
		castCons(w, REST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, REST); // (future . data)
		castCons(w, REST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, DATA);
		getLocal(w, KIND);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		// kind 0 (subtask): VAL = dispatch_1(lift, token), then release the subtask.
		castCons(w, DATA);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		castCons(w, DATA);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE + 1);
		setLocal(w, VAL);
		getLocal(w, WAITABLE);
		callOrdinal(w, sched.ordinals().subtaskDrop());
		w.write(Instruction.ELSE);
		// kind 1 (stream read): unjoin the handle, lift the chunk (0 bytes = EOF),
		// recycle the buffer, and run the close protocol on an EOF of an attached,
		// not-yet-closed stream.
		getLocal(w, WAITABLE);
		i32(w, 0);
		callOrdinal(w, sched.ordinals().join());
		getLocal(w, CODE);
		i32(w, 4);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.TEE_LOCAL);
		w.writeSignedLeb128(N); // the byte count
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		castCons(w, DATA);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		getLocal(w, N);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(sched.strFromMemFuncIndex());
		w.write(Instruction.ELSE);
		refNullEq(w);
		w.write(Instruction.END);
		setLocal(w, VAL);
		// readFree = (buf . readFree)
		castCons(w, DATA);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		globalGet(w, sched.readFreeGlobal());
		newCons(w);
		globalSet(w, sched.readFreeGlobal());
		// EOF close protocol: DATA = the attached stream (reuse the local).
		castCons(w, DATA);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, DATA);
		getLocal(w, VAL);
		w.write(Instruction.REF_IS_NULL);
		getLocal(w, DATA);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		castStream(w, DATA, streamType);
		structGet(w, streamType, 0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		emitCloseOnce(w, DATA, streamType);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END); // if kind
		// Settle the entry's future (wakes its waiters).
		castCons(w, REST);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		getLocal(w, VAL);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_SETTLE);
		w.write(Instruction.DROP);
		w.write(Instruction.END); // block $miss
		w.write(Instruction.END); // if interesting
		i32(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _task_begin () -> nil: fresh task record
	// (id . (root . (rx . (tx . (set . ready))))), CURRENT = the record, task
	// waitable-set = 0 (created lazily by this task).
	private static byte[] buildTaskBegin(int currentTaskGlobal, Cb cb) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		globalGet(w, cb.taskSeqGlobal());
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		globalSet(w, cb.taskSeqGlobal());
		globalGet(w, cb.taskSeqGlobal());
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		refNullEq(w); // root
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW); // rx
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW); // tx
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW); // set
		refNullEq(w); // ready
		newCons(w); // (set . ready)
		newCons(w); // (tx . ...)
		newCons(w); // (rx . ...)
		newCons(w); // (root . ...)
		newCons(w); // (id . ...)
		globalSet(w, currentTaskGlobal);
		i32(w, 0);
		globalSet(w, cb.setGlobal());
		refNullEq(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _task_suspend (future) -> code: record the root, ensure the task waitable-set,
	// create+arm+join the doorbell, register the task and its context slots, then
	// _task_finish (which drains ready waiters deposited before the doorbell existed).
	private static byte[] buildTaskSuspend(int base, int currentTaskGlobal, Cb cb) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int FUT = 0, RX = 1, TX = 2, R = 3, D = 4, REC = 5, CELL = 6;
		// locals: 3x i32, 1x i64, 2x (ref null eq)
		w.write(3);
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.I64);
		w.writeUnsignedLeb128(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		globalGet(w, currentTaskGlobal);
		setLocal(w, REC);
		// CELL = cdr(REC); CELL.car = the root future.
		castCons(w, REC);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CELL);
		castCons(w, CELL);
		getLocal(w, FUT);
		structSet(w, WasmLispCompiler.TYPE_CONS, 0);
		// Ensure the task waitable-set (the task may have joined nothing yet).
		globalGet(w, cb.setGlobal());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		callOrdinal(w, cb.wsNew());
		globalSet(w, cb.setGlobal());
		w.write(Instruction.END);
		// Doorbell: readable = low 32 bits, writable = high.
		callOrdinal(w, cb.dbNew());
		setLocal(w, D);
		getLocal(w, D);
		w.write(Instruction.I32_WRAP_I64);
		setLocal(w, RX);
		getLocal(w, D);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(32);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		setLocal(w, TX);
		// CELL = cddr(REC); CELL.car = rx; CELL = cdddr(REC); CELL.car = tx.
		castCons(w, CELL);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CELL);
		castCons(w, CELL);
		getLocal(w, RX);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		structSet(w, WasmLispCompiler.TYPE_CONS, 0);
		castCons(w, CELL);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CELL);
		castCons(w, CELL);
		getLocal(w, TX);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		structSet(w, WasmLispCompiler.TYPE_CONS, 0);
		// Standing read: nothing can have written yet, so it must report BLOCKED.
		getLocal(w, RX);
		i32(w, WasmLispCompiler.DB_READ_SCRATCH_ADDR);
		i32(w, 1);
		callOrdinal(w, cb.dbRead());
		setLocal(w, R);
		getLocal(w, R);
		i32(w, -1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		getLocal(w, RX);
		globalGet(w, cb.setGlobal());
		callOrdinal(w, cb.wJoin());
		// CELL = the (set . ready) cons; CELL.car = the task waitable-set handle
		// (wasmtime 46 has ONE context slot, so the set rides the record).
		castCons(w, CELL);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CELL);
		castCons(w, CELL);
		globalGet(w, cb.setGlobal());
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		structSet(w, WasmLispCompiler.TYPE_CONS, 0);
		// Register the task and its context identity.
		getLocal(w, REC);
		globalGet(w, cb.tasksGlobal());
		newCons(w);
		globalSet(w, cb.tasksGlobal());
		castCons(w, REC);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		callOrdinal(w, cb.ctxSet0());
		refNullEq(w);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_TASK_FINISH);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _task_finish (ignored) -> code: drain the ready list, then WAIT (root pending) /
	// EXIT after unlinking the record (root fulfilled) / trap (root rejected).
	private static byte[] buildTaskFinish(int base, int futureType, int currentTaskGlobal, Cb cb) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int STATE = 1, REC = 2, CELL = 3, PREV = 4, CUR = 5;
		// locals: 1x i32, 4x (ref null eq)
		w.write(2);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.writeUnsignedLeb128(4);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		globalGet(w, currentTaskGlobal);
		setLocal(w, REC);
		// CELL = the (set . ready) cons; drain: list = CELL.cdr, CELL.cdr = nil.
		castCons(w, REC);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CELL);
		castCons(w, CELL);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR); // reuse: the drained list
		castCons(w, CELL);
		refNullEq(w);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		getLocal(w, CUR);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_WAKE_LIST);
		w.write(Instruction.DROP);
		// STATE = root.state
		castCons(w, REC);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(futureType);
		structGet(w, futureType, 0);
		setLocal(w, STATE);
		// Pending: keep the task alive, wait on its set.
		getLocal(w, STATE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		i32(w, WasmComponentImportCompiler.CALLBACK_CODE_WAIT);
		globalGet(w, cb.setGlobal());
		i32(w, 4);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// Rejected: the export-boundary trap shape (task.return never happened).
		getLocal(w, STATE);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		// Fulfilled: unlink the record (a task that never suspended is not registered),
		// clear CURRENT, EXIT.
		refNullEq(w);
		setLocal(w, PREV);
		globalGet(w, cb.tasksGlobal());
		setLocal(w, CUR);
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY); // $out
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY); // $walk
		getLocal(w, CUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1); // -> $out
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		getLocal(w, REC);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		getLocal(w, PREV);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		globalSet(w, cb.tasksGlobal());
		w.write(Instruction.ELSE);
		castCons(w, PREV);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		structSet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.END);
		w.write(Instruction.BR, 2); // -> $out
		w.write(Instruction.END);
		getLocal(w, CUR);
		setLocal(w, PREV);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR);
		w.write(Instruction.BR, 0); // -> $walk
		w.write(Instruction.END); // loop $walk
		w.write(Instruction.END); // block $out
		refNullEq(w);
		globalSet(w, currentTaskGlobal);
		i32(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _async_cb (event, waitable, code) -> code: the real callback. Restores the task
	// identity from the context slots, re-arms+drains on a doorbell completion,
	// dispatches everything else, then finishes (WAIT again or EXIT). An escaping
	// condition keeps the trap shape (catch_all -> unreachable), like every other
	// host-callable entry.
	private static byte[] buildAsyncCb(int base, int currentTaskGlobal, Cb cb) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		final int EV = 0, WAITABLE = 1, CODE = 2;
		final int TID = 3, RX = 4, R = 5;
		final int CUR = 6, ENTRY = 7;
		// locals: 3x i32, 2x (ref null eq)
		w.write(2);
		w.writeUnsignedLeb128(3);
		w.write(Type.I32);
		w.writeUnsignedLeb128(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.TRY_TABLE, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.CATCH_ALL);
		w.writeUnsignedLeb128(0);
		// Restore the task identity: the record, found by the context-slot task id in
		// the task list (a missing record is a scheduler bug worth trapping on), then
		// the set handle out of the record.
		callOrdinal(w, cb.ctxGet0());
		setLocal(w, TID);
		globalGet(w, cb.tasksGlobal());
		setLocal(w, CUR);
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY); // $found
		w.write(Instruction.LOOP, WasmLispCompiler.BLOCKTYPE_EMPTY); // $walk
		getLocal(w, CUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		setLocal(w, ENTRY);
		castCons(w, ENTRY);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		getLocal(w, TID);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF, 1); // -> $found
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR);
		w.write(Instruction.BR, 0); // -> $walk
		w.write(Instruction.END); // loop $walk
		w.write(Instruction.END); // block $found
		getLocal(w, ENTRY);
		globalSet(w, currentTaskGlobal);
		// RX = the task's doorbell readable end; the set handle global is restored
		// from the record's (set . ready) cons.
		castCons(w, ENTRY);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR); // reuse: the (rx . (tx . (set . ready))) tail
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		setLocal(w, RX);
		castCons(w, CUR);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		unboxI31(w);
		globalSet(w, cb.setGlobal());
		getLocal(w, EV);
		i32(w, WasmComponentImportCompiler.EVENT_STREAM_READ);
		w.write(Instruction.I32_EQ);
		getLocal(w, WAITABLE);
		getLocal(w, RX);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		// A doorbell ring: re-arm the standing read BEFORE draining, so a ring
		// during the drain queues the next event. No write can be pending here
		// (rings only happen against a pending read), so the re-arm must BLOCK.
		getLocal(w, RX);
		i32(w, WasmLispCompiler.DB_READ_SCRATCH_ADDR);
		i32(w, 1);
		callOrdinal(w, cb.dbRead());
		setLocal(w, R);
		getLocal(w, R);
		i32(w, -1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		getLocal(w, EV);
		getLocal(w, WAITABLE);
		getLocal(w, CODE);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_SCHED_DISPATCH);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
		refNullEq(w);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(base + OFF_TASK_FINISH);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // try_table
		w.write(Instruction.END); // block
		w.write(Instruction.UNREACHABLE);
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
