package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the block return boundaries: the internal {@code %block} that the loop macros
 * ({@code do}/{@code dolist}/{@code dotimes}) wrap their expansion in, the user-facing
 * named {@code (block name body...)}, and the internal {@code (%fn-block name body...)}
 * function boundary the compilers wrap a {@code return-from}-containing defun/lambda body
 * in. All three emit the same shape: a WASM {@code block} whose result type is the
 * universal value type {@code (ref null eq)}; the body runs as an implicit {@code progn}
 * and leaves its value on the stack as the block result on normal completion, while an
 * exit form ({@link WasmReturnCompiler} / {@link WasmReturnFromCompiler}) branches out of
 * this block carrying the returned value, skipping the rest of the body and any result
 * form. They differ only in how the {@link WasmLispCompiler.BlockMarker} is keyed:
 * {@code %block} and {@code (block nil ...)} catch plain {@code return}; a named block
 * catches the {@code return-from} carrying its name; {@code %fn-block} additionally
 * catches any {@code return-from} whose name matches no enclosing block (the
 * function-boundary fallback).
 */
final class WasmBlockCompiler {

	private WasmBlockCompiler() {
	}

	/** The internal {@code (%block body...)} boundary: unnamed, catches plain return. */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBody(cons.toList(), 1, ctx, null, true, false);
	}

	/**
	 * A user {@code (block name body...)}: a lexical named target. {@code (block nil
	 * ...)} behaves exactly like {@code %block} (it catches plain {@code return}, and a
	 * {@code (return-from nil ...)} compiles to plain {@code return}).
	 */
	static void compileNamed(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.BLOCK + " expects a block name: " + cons.print());
		}
		String name = LispMacroExpander.blockName(parts.get(1));
		compileBody(parts, 2, ctx, name, name == null, false);
	}

	/**
	 * The internal {@code (%fn-block name body...)} function boundary: a named target
	 * ({@code name} is the defun's name, nil for a lambda) that is also the fallback for
	 * an unmatched {@code return-from}. It does NOT catch plain {@code return}.
	 */
	static void compileFnBlock(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.FN_BLOCK_INTERNAL + " expects a block name: " + cons.print());
		}
		String name = LispMacroExpander.blockName(parts.get(1));
		compileBody(parts, 2, ctx, name, false, true);
	}

	private static void compileBody(List<LispVal> parts, int bodyStart, WasmLispCompiler.Ctx ctx, @Nullable String name,
			boolean catchesPlain, boolean functionBoundary) {
		// block (result (ref null eq))
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		ctx.blockMarkers
			.push(new WasmLispCompiler.BlockMarker(ctx.wasmCtrlDepth, name, catchesPlain, functionBoundary));
		// Body forms run as a progn, leaving the last value on the stack.
		if (parts.size() <= bodyStart) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		else if (ctx.asyncResume != null) {
			WasmAsyncEmit.compileGuardedProgn(parts.subList(bodyStart, parts.size()), ctx);
		}
		else {
			for (int i = bodyStart; i < parts.size(); i++) {
				if (i > bodyStart) {
					ctx.writer.write(Instruction.DROP);
				}
				WasmExprCompiler.compileExpr(parts.get(i), ctx);
			}
		}
		ctx.blockMarkers.pop();
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

}
