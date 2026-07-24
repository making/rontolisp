package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the three internal non-local-exit primitives the compile-path
 * {@code CrossLambdaExitLowering} injects to make a {@code return-from} exit a function
 * from inside a nested lambda (a separately compiled wasm function a {@code br} cannot
 * cross), the wasm-GC EH counterpart of {@code JvmNlxCompiler}:
 *
 * <ul>
 * <li>{@code (%nlx-tag)} mints a fresh {@code (struct.new $cell)} -- a unique {@code (ref
 * eq)} that is the dynamic block-instance id. A {@code let} binds one per establishing
 * block activation, and the existing closure machinery captures it into the lambda, so
 * recursion targets the right frame.</li>
 * <li>{@code (%nlx-throw id value)} conses {@code (id . value)} and {@code throw}s it on
 * the dedicated {@code $block-exit} tag; the wasm stack unwind runs every intervening
 * {@code unwind-protect} (its {@code catch_all_ref} reraises with
 * {@code throw_ref}).</li>
 * <li>{@code (%nlx-catch id body...)} runs {@code body} inside a {@code try_table (catch
 * $block-exit)}; when the caught payload's id {@code ref.eq}s {@code id}, its value
 * becomes the form's value, otherwise it is rethrown (an outer block's exit).</li>
 * </ul>
 *
 * The {@code $block-exit} tag is distinct from {@code $lisp-cond}, so
 * {@code handler-case} ({@code catch $lisp-cond}) never intercepts a cross-lambda exit;
 * only the handler-depth bookkeeping needs the {@link WasmHandlerCaseCompiler} block-exit
 * passthrough.
 */
final class WasmNlxCompiler {

	private WasmNlxCompiler() {
	}

	/** {@code (%nlx-tag)} -- a fresh unique {@code (ref eq)} identity. */
	static void compileTag(WasmLispCompiler.Ctx ctx) {
		// struct.new $cell over a null field: a fresh, identity-distinct heap object.
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
	}

	/** {@code (%nlx-throw id value)} -- throw a block exit carrying (id . value). */
	static void compileThrow(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new IllegalArgumentException(LispNames.NLX_THROW_INTERNAL + " expects (%nlx-throw id value)");
		}
		// (id . value) on the $block-exit tag. Intervening unwind-protect cleanups run
		// via
		// their catch_all_ref; the throw is stack-polymorphic so the call site is
		// unchanged.
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.THROW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_BLOCK_EXIT);
	}

	/** {@code (%nlx-catch id body...)} -- catch a matching block exit; else rethrow. */
	static void compileCatch(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.NLX_CATCH_INTERNAL + " expects (%nlx-catch id body...)");
		}
		LispVal idForm = parts.get(1);
		int payloadSlot = ctx.allocTemp();
		// block $done (result (ref null eq)) -- the whole form's value.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int doneDepth = ctx.wasmCtrlDepth;
		// block $h (result (ref null eq)) -- the landing pad, receiving the payload cons.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int handlerDepth = ctx.wasmCtrlDepth;
		// try_table (result (ref null eq)) (catch $block-exit $h). Catch labels resolve
		// without the try_table's own label, so label 0 is block $h here.
		ctx.writer.write(Instruction.TRY_TABLE);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.CATCH);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_BLOCK_EXIT);
		ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - handlerDepth);
		ctx.wasmCtrlDepth++;
		// Body as an implicit progn, leaving its value on the stack.
		if (parts.size() <= 2) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		else if (ctx.asyncResume != null) {
			WasmAsyncEmit.compileGuardedProgn(parts.subList(2, parts.size()), ctx);
		}
		else {
			for (int i = 2; i < parts.size(); i++) {
				if (i > 2) {
					ctx.writer.write(Instruction.DROP);
				}
				WasmExprCompiler.compileExpr(parts.get(i), ctx);
			}
		}
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // try_table
		// Normal completion: the body value is on the stack; deliver it to $done.
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $h -- the payload cons is on the stack
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(payloadSlot);
		// tag = car(payload); if it ref.eq the id, deliver cdr(payload); else rethrow.
		emitConsField(ctx, payloadSlot, 0);
		WasmExprCompiler.compileExpr(idForm, ctx);
		ctx.writer.write(Instruction.REF_EQ);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		emitConsField(ctx, payloadSlot, 1);
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // if
		// No match: rethrow the same payload for an outer establishing block.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.THROW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_BLOCK_EXIT);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $done
	}

	/** Loads {@code (struct.get $cons index)} of the cons in {@code payloadSlot}. */
	private static void emitConsField(WasmLispCompiler.Ctx ctx, int payloadSlot, int index) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(index);
	}

}
