package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code read-line} built-in function. Without an argument it reads from
 * stdin (fd 0); with a stream argument the stream's i31-boxed file descriptor is unboxed
 * and read from instead. A CL 3-arg {@code (read-line stream eof-error-p eof-value)} is
 * lowered first through {@link LispMacroExpander#expandReadLineCompat} so the standard
 * "swallow EOF" idiom real libraries drive their per-line loops with works on WASM too.
 */
final class WasmReadLineCompiler {

	private WasmReadLineCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal rewritten = LispMacroExpander.expandReadLineCompat(cons);
		if (rewritten != null) {
			WasmExprCompiler.compileExpr(rewritten, ctx);
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException("read-line expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		// The source, under CL's stream designator rule: an explicit stream, or -- for an
		// omitted argument AND for an explicit nil -- the current *standard-input*
		// (WasmEmitHelper.inputStreamArg). A program that never binds it keeps fd 0.
		LispVal stream = WasmEmitHelper.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		if (stream == null) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0); // fd = 0 (stdin)
		}
		else {
			WasmExprCompiler.compileExpr(stream, ctx);
			WasmEmitHelper.streamFdOrStdin(ctx);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_LINE);
	}

	/**
	 * The operator {@link #pairExpansion} reads {@code _read_line}'s missing-newline-p
	 * through ({@link WasmLispCompiler#READ_LINE_END_ADDR}). Internal to this backend:
	 * nothing but that expansion spells it.
	 */
	static final String LINE_END_FLAG = "%READ-LINE-END-FLAG";

	private static final String LINE_VAR = "__rlp_line";

	/**
	 * {@code (%read-line-pair [stream])}, the read under a {@code read-line} producer's
	 * multiple-value lowering: {@code (let ((line (read-line stream))) (if line (cons
	 * line FLAG) nil))}, the one-argument {@code read-line} being the core call (with the
	 * writable-string wrap) and FLAG the missing-newline-p that call left -- read
	 * straight after it, before anything else can run (the module is single-threaded).
	 */
	static LispVal pairExpansion(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException(
					"%read-line-pair expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		List<LispVal> read = new java.util.ArrayList<>();
		read.add(new LispSymbol(LispNames.READ_LINE));
		read.addAll(parts.subList(1, parts.size()));
		LispSymbol line = new LispSymbol(LINE_VAR);
		LispVal pair = list(new LispSymbol(LispNames.CONS), line, list(new LispSymbol(LINE_END_FLAG)));
		return list(new LispSymbol(LispNames.LET), list(list(line, list(read.toArray(LispVal[]::new)))),
				list(new LispSymbol(LispNames.IF), line, pair, LispNil.INSTANCE));
	}

	private static LispVal list(LispVal... elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			list = new LispCons(elements[i], list);
		}
		return list;
	}

	/** Compiles {@link #LINE_END_FLAG}: {@code t} when the cell holds 1, else nil. */
	static void compileLineEndFlag(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.READ_LINE_END_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(am.ik.wasm.Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

}
