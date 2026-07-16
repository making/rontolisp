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
	static final int FUNC_COUNT = 6;

	static final int OFF_NEW = 0;

	static final int OFF_SETTLE = 1;

	static final int OFF_REJECT = 2;

	static final int OFF_ADD_WAITER = 3;

	static final int OFF_WAKE = 4;

	static final int OFF_POLL = 5;

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
			case OFF_SETTLE, OFF_REJECT, OFF_ADD_WAITER -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1; // (eq,eq)->eq
			default -> WasmLispCompiler.TYPE_CALLABLE_BASE; // (eq)->eq
		};
	}

	/**
	 * Builds the body of block member {@code off}.
	 * @param off the {@code OFF_*} member offset
	 * @param base the block's base function index ({@code _future_new})
	 * @param futureType the {@code TYPE_FUTURE} type index
	 * @param frameType the {@code TYPE_ASYNC_FRAME} type index
	 * @return the function body bytes (locals declaration included)
	 */
	static byte[] build(int off, int base, int futureType, int frameType) {
		return switch (off) {
			case OFF_NEW -> buildNew(futureType);
			case OFF_SETTLE -> buildSettleOrReject(base, futureType, 1);
			case OFF_REJECT -> buildSettleOrReject(base, futureType, 2);
			case OFF_ADD_WAITER -> buildAddWaiter(futureType);
			case OFF_WAKE -> buildWake(base, futureType, frameType);
			case OFF_POLL -> buildPoll(futureType);
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

	// _future_poll (v) -> (ref null eq): resolves settled futures (flattening chains)
	// and legacy promise chains; returns a PENDING future unchanged (the await site
	// suspends on it); throws the payload of a rejected future on $lisp-cond (the
	// memoized re-signal at await).
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
		// Legacy promise chain (a wit-import async call): resolve it (blocking wait
		// inside), then loop -- the resolved value may itself be a future.
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_PROMISE);
		w.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		getLocal(w, 0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PROMISE_AWAIT);
		setLocal(w, 0);
		w.write(Instruction.BR, 1); // -> loop (past this if)
		w.write(Instruction.END);
		// A plain value passes through.
		getLocal(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		return body.toByteArray();
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
