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
 * byte). A {@code TYPE_STRING}'s {@code $str_bytes} array holds UTF-8 bytes and the
 * character accessors ({@code length}/{@code char}) decode them, so a BINARY chunk read
 * off a socket cannot be walked character-wise -- bytes that happen to form a valid
 * multi-byte sequence would collapse into one character and shift everything after them.
 * sockets.lisp's chunk-buffer bookkeeping therefore walks the chunk's bytes through these
 * accessors (the bytes ARE the wire), and {@code write-byte} builds its one-byte payload
 * with {@code %str-from-byte} so the write path's raw {@code $str_bytes} copy puts
 * exactly that byte on the wire. Deliberately undocumented, component-only.
 */
final class WasmStrByteCompiler {

	private WasmStrByteCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.component) {
			throw new UnsupportedOperationException("rontolisp::" + member + " is an internal --component binding");
		}
		List<LispVal> args = cons.toList();
		switch (member) {
			case LispNames.STR_BYTE_LENGTH_INTERNAL -> {
				expectArgs(member, args, 1);
				// content bytes = the stored framed byte length (field 1) minus the two
				// surrounding quotes
				WasmExprCompiler.compileExpr(args.get(1), ctx);
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
				// array[i + 1]: content starts after the leading quote
				WasmExprCompiler.compileExpr(args.get(1), ctx);
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
			default -> throw new IllegalArgumentException("unknown string byte internal: " + member);
		}
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
