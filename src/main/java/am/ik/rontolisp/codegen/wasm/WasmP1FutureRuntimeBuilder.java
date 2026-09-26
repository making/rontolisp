package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _p1_future_await((ref null eq)) -> (ref null eq)}
 * ({@code FUNC_P1_FUTURE_AWAIT}), the generic {@code rontolisp:await} resolver of the
 * degenerate (non-asyncMode) tier. A non-future value passes through unchanged (like
 * JavaScript await). A settled {@code TYPE_P1_FUTURE} ({@link #KIND_SETTLED}: what
 * {@code %async-run}, an {@code :async t} import wrapper and a stream read produce)
 * answers its memoized value, recursively awaited so nested futures flatten. Recursion is
 * why this is a real function rather than inline code at each await site (see
 * {@code WasmAwaitCompiler}).
 *
 * <p>
 * A module that names {@code rontolisp::%future-deferred} also meets a DEFERRED one
 * ({@link #KIND_DEFERRED}), whose value field is a thunk: every await calls it through
 * the arity-0 dispatch and awaits what it answers, so the future settles where it is
 * first awaited -- and a thunk that signals makes the await signal, which is how a
 * {@code --native} fetch reports a transport failure at the await rather than at the
 * call. Any other module's body is byte-identical to what it was before the kind existed.
 *
 * <p>
 * An exception-handling module also meets a FAILED one ({@link #KIND_FAILED}): the body
 * signalled, and each await signals its condition again -- the interpreter's errored
 * future, which re-signals at the await rather than at the call.
 *
 * <p>
 * A module with a {@code %mv-spill} global also meets a VALUES future
 * ({@link #KIND_VALUES}): {@code %async-run}'s, when the body answered other than exactly
 * one value, whose value field is {@code (primary . extras)} -- the channel as the body
 * left it. The await is then a multiple-value producer: it publishes one value, or the
 * extras of the last future of the chain.
 */
final class WasmP1FutureRuntimeBuilder {

	/** The {@code kind} of a future settled at creation. */
	static final int KIND_SETTLED = 2;

	/** The {@code kind} of a future settled by its thunk at each await. */
	static final int KIND_DEFERRED = 3;

	/**
	 * The {@code kind} of a future settled at creation with several (or zero) values: the
	 * value field is {@code (primary . extras)}.
	 */
	static final int KIND_VALUES = 4;

	/**
	 * The {@code kind} of a future whose body signalled: the value field is the
	 * {@code $lisp-cond} payload, which every await throws again.
	 */
	static final int KIND_FAILED = 5;

	private WasmP1FutureRuntimeBuilder() {
	}

	/**
	 * Builds the resolver's body.
	 * @param deferred whether the module can hold a {@link #KIND_DEFERRED} future
	 * @param failed whether the module can hold a {@link #KIND_FAILED} future
	 * @param reawaitFunc {@code --report-locations}' {@code _uncaught_reawait}, which a
	 * failed future's await hands the future and its payload before re-signalling it
	 * ({@link WasmUncaughtLocations}), or -1 when the module notes no hops
	 * @param spillGlobal the {@code %mv-spill} channel's global index, or -1 when the
	 * program has no multiple-value consumer (no {@link #KIND_VALUES} future can exist)
	 * @return the function body bytes (locals declaration included)
	 */
	static byte[] buildAwait(boolean deferred, boolean failed, int reawaitFunc, int spillGlobal) {
		final ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int V = 0;
		final int PRIMARY = 1;
		if (spillGlobal >= 0) {
			// locals: 1x (ref null eq)
			w.write(1);
			w.writeUnsignedLeb128(1);
			w.writeRefType(true, am.ik.wasm.Type.EQ.code());
			// One value unless the last future of the chain says otherwise.
			w.write(Instruction.REF_NULL);
			w.writeHeapType(am.ik.wasm.Type.EQ.code());
			w.write(Instruction.SET_GLOBAL);
			w.writeUnsignedLeb128(spillGlobal);
		}
		else {
			w.write(0); // no locals
		}

		// A non-future value passes through unchanged.
		getLocal(w, V);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, V);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		if (failed) {
			// Failed: the body's condition signals again -- the SAME payload, so what the
			// body's frames noted about it for --report-locations stays, rewound to where
			// the boundary left it.
			futureField(w, V, 0);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(KIND_FAILED);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			if (reawaitFunc >= 0) {
				getLocal(w, V);
				futureField(w, V, 1);
				call(w, reawaitFunc);
				w.write(Instruction.DROP);
			}
			futureField(w, V, 1);
			w.write(Instruction.THROW);
			w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
			w.write(Instruction.END);
		}

		if (deferred) {
			// Deferred: run the thunk (it keeps its own answer) and await that.
			futureField(w, V, 0);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(KIND_DEFERRED);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			futureField(w, V, 1);
			call(w, WasmLispCompiler.FUNC_DISPATCH_BASE);
			call(w, WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}

		if (spillGlobal >= 0) {
			// Values: (primary . extras). A primary that is itself a future is awaited
			// in turn (its own values decide); any other publishes the extras.
			futureField(w, V, 0);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(KIND_VALUES);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			futureField(w, V, 1);
			consField(w, 0);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(PRIMARY);
			getLocal(w, PRIMARY);
			w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			w.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
			w.write(Instruction.IF, 0x40);
			getLocal(w, PRIMARY);
			call(w, WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
			futureField(w, V, 1);
			consField(w, 1);
			w.write(Instruction.SET_GLOBAL);
			w.writeUnsignedLeb128(spillGlobal);
			getLocal(w, PRIMARY);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}

		// Settled: the memoized value, recursively awaited so a nested settled future
		// (an async body returning another async call's future) flattens like
		// JavaScript await; a non-future value returns immediately.
		futureField(w, V, 1);
		call(w, WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Pushes field fieldIdx of the future in local vSlot.
	private static void futureField(WasmWriter w, int vSlot, int fieldIdx) {
		getLocal(w, vSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
		w.writeUnsignedLeb128(fieldIdx);
	}

	// Replaces the cons on the stack with its car (0) or cdr (1).
	private static void consField(WasmWriter w, int fieldIdx) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(fieldIdx);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(func);
	}

}
