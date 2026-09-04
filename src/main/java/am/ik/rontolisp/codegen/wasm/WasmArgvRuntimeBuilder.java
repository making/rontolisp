package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the {@code _argv} runtime helper for WASM Preview 1: {@code () -> (ref null eq)}
 * -- the program's argument vector as a Lisp list of strings, argv0 first (WASI's own
 * {@code args[0]}, which is what the host put on the command line). It scans the buffer
 * {@code args_sizes_get} / {@code args_get} fill, exactly the shape {@code _getenv} walks
 * for the environ one, and conses each NUL-terminated entry into a fresh heap string.
 *
 * <p>
 * The two WASI functions are APPENDED USER IMPORTS
 * ({@code am.ik.wasm.WasmImportInjector}), not fixed slots: the body calls them through
 * {@code WasmImportCompiler.PLACEHOLDER_FUNC_BASE + ordinal} the way
 * {@code --host-random}'s entropy import does, so the twelve index-pinned preview1 slots
 * -- and with them every existing module's bytes and the {@code --component} adapter's
 * export list -- are untouched. A program that never reads its command line imports
 * nothing new, and {@code --optimize} shakes the pair away with the helper.
 *
 * <p>
 * Under {@code --component} this helper is never called: {@code %host-argv} is the
 * spliced {@code environment.lisp} defun over wit-imported {@code wasi:cli/environment}'s
 * {@code get-arguments} there, the sibling of the {@code get-environment} binding beside
 * it.
 */
final class WasmArgvRuntimeBuilder {

	private static final int COUNT = 0;

	private static final int I = 1;

	private static final int P = 2; // the entry's linear pointer

	private static final int LEN = 3;

	private static final int HEAP = 4;

	private static final int K = 5; // copy index

	private static final int ACC = 6; // the list built so far

	private static final int QUOTE = 0x22;

	private WasmArgvRuntimeBuilder() {
	}

	/**
	 * The helper's body.
	 * @param argsSizesGetFunc the function index to call for {@code args_sizes_get}
	 * (placeholder-encoded)
	 * @param argsGetFunc the function index to call for {@code args_get}
	 * (placeholder-encoded)
	 * @param scratchBase the base of the program's env/argv scratch block, which the
	 * compiler places above the static data (see
	 * {@code .kb/wasm-linear-memory-layout.md}) -- the count / buffer-size words, the
	 * pointer array and the string buffer this body hands the two host calls are all
	 * offsets from it
	 * @return the encoded function body
	 */
	static byte[] build(int argsSizesGetFunc, int argsGetFunc, int scratchBase) {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);

		// 6 i32 locals (0..5) + one (ref null eq) accumulator (6).
		w.write(2);
		w.writeUnsignedLeb128(6);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.writeRefType(true, Type.EQ.code());

		// args_sizes_get(scratch COUNT, scratch BUFSIZE);
		// args_get(scratch PTRS, scratch BUF)
		i32(w, scratchBase + WasmLispCompiler.SCRATCH_ARGV_COUNT_OFFSET);
		i32(w, scratchBase + WasmLispCompiler.SCRATCH_ARGV_BUFSIZE_OFFSET);
		call(w, argsSizesGetFunc);
		w.write(Instruction.DROP);
		i32(w, scratchBase + WasmLispCompiler.SCRATCH_ARGV_PTRS_OFFSET);
		i32(w, scratchBase + WasmLispCompiler.SCRATCH_ARGV_BUF_OFFSET);
		call(w, argsGetFunc);
		w.write(Instruction.DROP);

		i32(w, scratchBase + WasmLispCompiler.SCRATCH_ARGV_COUNT_OFFSET);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, COUNT);
		// acc = nil ; i = count - 1 -- built BACKWARDS, so the list comes out in order.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, ACC);
		getLocal(w, COUNT);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, I);

		w.write(Instruction.BLOCK, 0x40); // $done
		w.write(Instruction.LOOP, 0x40); // $l
		getLocal(w, I);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // br to $done
		// p = load(scratch PTRS + i*4)
		i32(w, scratchBase + WasmLispCompiler.SCRATCH_ARGV_PTRS_OFFSET);
		getLocal(w, I);
		i32(w, 4);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, P);
		// len = strlen(p)
		i32(w, 0);
		setLocal(w, LEN);
		w.write(Instruction.BLOCK, 0x40); // $strDone
		w.write(Instruction.LOOP, 0x40); // $str
		getLocal(w, P);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // br to $strDone
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, LEN);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // end $str loop
		w.write(Instruction.END); // end $strDone block
		// heap = memory[HEAP_PTR_ADDR] ; heap[0] = '"'
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, HEAP);
		getLocal(w, HEAP);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// copy len bytes: heap[1 + k] = p[k]
		i32(w, 0);
		setLocal(w, K);
		w.write(Instruction.BLOCK, 0x40); // $copyDone
		w.write(Instruction.LOOP, 0x40); // $copy
		getLocal(w, K);
		getLocal(w, LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // br to $copyDone
		getLocal(w, HEAP);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		getLocal(w, P);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // end $copy loop
		w.write(Instruction.END); // end $copyDone block
		// heap[1 + len] = '"'
		getLocal(w, HEAP);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// acc = cons(_str_fresh(heap, len + 2), acc). HEAP_PTR is NOT advanced:
		// _str_fresh copies the bytes into a fresh GC array, so the scratch is reused
		// for the next entry -- the rule every runtime string build follows.
		getLocal(w, HEAP);
		getLocal(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		WasmEmitHelper.emitStrFreshCall(w);
		getLocal(w, ACC);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		setLocal(w, ACC);
		// i--
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, I);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // end $l loop
		w.write(Instruction.END); // end $done block

		getLocal(w, ACC);
		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * The body emitted when the program never reads its command line: the helper's index
	 * is fixed, so it must exist, but it imports nothing and answers nil.
	 * @return the encoded stub body
	 */
	static byte[] buildStub() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		w.write(0); // no locals
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(func);
	}

}
