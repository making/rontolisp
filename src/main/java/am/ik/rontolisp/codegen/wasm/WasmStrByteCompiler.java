package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the internal byte-level string accessors of the {@code --component} socket
 * layer: {@code rontolisp::%str-byte-length} (content byte count),
 * {@code rontolisp::%str-byte-ref} (the i-th content byte, 0-255) and
 * {@code rontolisp::%str-from-byte} (a one-byte-content string carrying an arbitrary
 * byte) and {@code rontolisp::%str-from-octets} (the same for a run of a packed octet
 * vector), and {@code rontolisp::%str-decode-raw} (such a string as the text its octets
 * decode to). A {@code TYPE_STRING}'s {@code $str_bytes} array holds UTF-8 bytes and the
 * character accessors ({@code length}/{@code char}) decode them, so a BINARY chunk read
 * off a socket cannot be walked character-wise -- bytes that happen to form a valid
 * multi-byte sequence would collapse into one character and shift everything after them.
 * sockets.lisp's chunk-buffer bookkeeping therefore walks the chunk's bytes through these
 * accessors (the bytes ARE the wire), and {@code write-byte} builds its one-byte payload
 * with {@code %str-from-byte} so the write path's raw {@code $str_bytes} copy puts
 * exactly that byte on the wire. The {@code --native} runner's provider
 * ({@code runner-sockets.lisp}, a Preview 1 module) assembles its lines and characters
 * from wire bytes the same way, so the accessors compile on both wasm-GC targets.
 * Deliberately undocumented: the names exist only post-splice.
 */
final class WasmStrByteCompiler {

	private WasmStrByteCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		switch (member) {
			case LispNames.STR_BYTE_LENGTH_INTERNAL -> {
				expectArgs(member, args, 1);
				// content bytes = the stored framed byte length (field 1) minus the two
				// surrounding quotes. A mutable character vector (a subseq/copy-seq
				// result, e.g. write-string's :start/:end slice) renders first.
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.emitCharvecToStrCall(ctx);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
				ctx.writer.writeUnsignedLeb128(1);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(2);
				ctx.writer.write(Instruction.I32_SUB);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNames.STR_BYTE_REF_INTERNAL -> {
				expectArgs(member, args, 2);
				// array[i + 1]: content starts after the leading quote (a character
				// vector renders first, as in the length accessor above)
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.emitCharvecToStrCall(ctx);
				WasmEmitHelper.emitStrBytesArray(ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.I32_ADD);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNames.STR_FROM_BYTE_INTERNAL -> {
				expectArgs(member, args, 1);
				// Stage '"' b '"' at the HEAP_PTR scratch (transient: no advance) and
				// finalize through _str_fresh, like every runtime string build. The byte
				// is evaluated FIRST so nothing that runs during its evaluation can
				// reuse the scratch after our stores.
				int tmp = ctx.allocTemp();
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmp);
				WasmEmitHelper.emitGrowHeapTo(ctx.writer, () -> {
					loadHeapPtr(ctx);
					ctx.writer.write(Instruction.I32_CONST);
					ctx.writer.writeSignedLeb128(3);
					ctx.writer.write(Instruction.I32_ADD);
				});
				loadHeapPtr(ctx);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(0x22);
				ctx.writer.write(Instruction.I32_STORE8, 0x00);
				ctx.writer.writeUnsignedLeb128(0);
				loadHeapPtr(ctx);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmp);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(Instruction.I32_STORE8, 0x00);
				ctx.writer.writeUnsignedLeb128(1);
				loadHeapPtr(ctx);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(0x22);
				ctx.writer.write(Instruction.I32_STORE8, 0x00);
				ctx.writer.writeUnsignedLeb128(2);
				loadHeapPtr(ctx);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(3);
				WasmEmitHelper.emitStrFreshCall(ctx.writer);
			}
			case LispNames.STR_FROM_OCTETS_INTERNAL -> {
				expectArgs(member, args, 3);
				// (vec start end): a $str_bytes of end - start + 2 bytes, the frame
				// quotes and ONE array.copy of the octets between them -- no UTF-8
				// check, as with %str-from-byte. The arguments are evaluated first, in
				// order, into locals; the string is then stamped with a fresh id from
				// the counter, as _iv_utf8_str does.
				int vec = ctx.allocTemp();
				int from = ctx.allocTemp();
				int to = ctx.allocTemp();
				int out = ctx.allocTemp();
				for (int i = 0; i < 3; i++) {
					WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(i == 0 ? vec : i == 1 ? from : to);
				}
				emitLength(ctx, from, to, 2);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(out);
				storeQuote(ctx, out, () -> {
					ctx.writer.write(Instruction.I32_CONST);
					ctx.writer.writeSignedLeb128(0);
				});
				storeQuote(ctx, out, () -> emitLength(ctx, from, to, 1));
				getOut(ctx, out);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(vec);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				ctx.writer.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
				getFixnum(ctx, from);
				emitLength(ctx, from, to, 0);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_COPY);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
				// counter = counter + 1; the string's id is the old value
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.STRING_ID_CTR_ADDR);
				loadStringIdCounter(ctx);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.I32_ADD);
				ctx.writer.write(Instruction.I32_STORE, 0x02);
				ctx.writer.writeUnsignedLeb128(0);
				loadStringIdCounter(ctx);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.I32_SUB);
				emitLength(ctx, from, to, 2);
				getOut(ctx, out);
				// the cursor seed, as WasmStringRuntimeBuilder.emitSeedCursor
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(-1);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
			}
			case LispNames.STR_DECODE_RAW_INTERNAL -> {
				expectArgs(member, args, 1);
				// the string the raw octets decode to (_str_decode)
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_BULK_BASE + WasmBulkRuntimeBuilder.STR_DECODE);
			}
			default -> throw new IllegalArgumentException("unknown string byte internal: " + member);
		}
	}

	// Pushes (to - from + extra) as an i32, the fixnums held in the two locals.
	private static void emitLength(WasmLispCompiler.Ctx ctx, int from, int to, int extra) {
		getFixnum(ctx, to);
		getFixnum(ctx, from);
		ctx.writer.write(Instruction.I32_SUB);
		if (extra != 0) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(extra);
			ctx.writer.write(Instruction.I32_ADD);
		}
	}

	private static void getFixnum(WasmLispCompiler.Ctx ctx, int local) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(local);
		WasmEmitHelper.castI31GetS(ctx);
	}

	// Pushes the $str_bytes held in a local (it is declared as an eqref).
	private static void getOut(WasmLispCompiler.Ctx ctx, int out) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(out);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STR_BYTES);
	}

	// out[index] = '"', the index pushed by the callback.
	private static void storeQuote(WasmLispCompiler.Ctx ctx, int out, Runnable index) {
		getOut(ctx, out);
		index.run();
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0x22);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
	}

	private static void loadStringIdCounter(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.STRING_ID_CTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02);
		ctx.writer.writeUnsignedLeb128(0);
	}

	private static void loadHeapPtr(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02);
		ctx.writer.writeUnsignedLeb128(0);
	}

	private static void expectArgs(String member, List<LispVal> args, int expected) {
		if (args.size() - 1 != expected) {
			throw new UnsupportedOperationException(
					"rontolisp::" + member + " expects " + expected + " arguments, got " + (args.size() - 1));
		}
	}

}
