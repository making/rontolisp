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
 * <li>{@code (%nlx-tag)} mints the next integer from the {@code NLX_ID_CTR} memory cell
 * as a {@code ref.i31} -- a dynamic block-instance id unique BY VALUE (i31 {@code ref.eq}
 * is value equality). A {@code let} binds one per establishing block activation, and the
 * existing closure machinery captures it into the lambda, so recursion targets the right
 * frame.</li>
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
 * <p>
 * The user-level {@code catch}/{@code throw} pair rides the SAME tag
 * ({@link #compileTagCatch}/{@link #compileTagThrow}), so one passthrough keeps covering
 * both and each kind's landing pad forwards the other's payload untouched. They are told
 * apart by the payload shape: a block exit carries {@code (id . value)} with an i31 id, a
 * user throw {@code ((tag) . value)} with a wrapper cons. Without the wrapper the two
 * would collide -- {@code ref.eq} on an i31 is VALUE equality, so a {@code (catch 3 ...)}
 * would swallow the exit of a block whose minted id happens to be 3.
 */
final class WasmNlxCompiler {

	private WasmNlxCompiler() {
	}

	/** {@code (%nlx-tag)} -- a fresh block-instance id, unique BY VALUE. */
	static void compileTag(WasmLispCompiler.Ctx ctx) {
		// The next integer from the NLX_ID_CTR memory cell, boxed as ref.i31: i31
		// equality is value equality, so the throw/catch match never depends on
		// GC-struct identity surviving capture, unwinding and register pressure.
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.NLX_ID_CTR_ADDR);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.NLX_ID_CTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_ADD);
		ctx.writer.write(Instruction.I32_STORE, 0x02, 0x00);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.NLX_ID_CTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
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

	/**
	 * {@code (throw tag [result])} -- the user-level dynamic non-local exit, sharing the
	 * {@code $block-exit} tag with {@code %nlx-throw} (so the two pass through each
	 * other's landing pads and a single handler-case passthrough covers both). The
	 * payload is {@code ((tag) . value)}: the extra wrapper cons is what makes the two
	 * kinds distinguishable, since a block-instance id is an i31 and {@code ref.eq} would
	 * otherwise report a match for a fixnum catch tag that happens to equal a live id.
	 */
	static void compileTagThrow(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new IllegalArgumentException(LispNames.THROW + " expects (throw tag result)");
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		if (parts.size() == 3) {
			WasmExprCompiler.compileExpr(parts.get(2), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
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
		emitCatch(parts, ctx, false);
	}

	/**
	 * {@code (catch tag body...)} -- the user-level dynamic catcher. Same region shape as
	 * {@code %nlx-catch}, with two differences: the tag is an arbitrary expression
	 * evaluated ONCE before the protected region (CL evaluates it on entry, not on the
	 * unwind), and the landing accepts only the wrapped {@code ((tag) . value)} payload
	 * {@link #compileTagThrow} builds, comparing the tag with {@code eq}.
	 */
	static void compileTagCatch(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.CATCH + " expects (catch tag body...)");
		}
		emitCatch(parts, ctx, true);
	}

	private static void emitCatch(List<LispVal> parts, WasmLispCompiler.Ctx ctx, boolean eqTags) {
		LispVal idForm = parts.get(1);
		int payloadSlot = ctx.allocTemp();
		// Snapshot the block-instance id (a user catch's tag) into a dedicated local
		// BEFORE the protected region, and compare the landing against the snapshot
		// instead of re-reading the (boxed) id variable after the unwind. The id is
		// minted immediately before this form and never assigned again, so the snapshot
		// is exact; it keeps the landing's compare independent of the capture/box read
		// path. For a user catch the snapshot is also what makes the tag expression
		// evaluate exactly ONCE, on entry, as CL requires. Not used in state-machine
		// mode, where a resume re-enters past this entry code -- there a non-constant
		// catch tag is re-evaluated on the unwind (a quoted-symbol tag, the normal case,
		// is unaffected).
		int idSlot = -1;
		if (ctx.asyncResume == null) {
			idSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(idForm, ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(idSlot);
		}
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
		// car(payload) is the block-instance id (an i31) for %nlx-catch and the (tag)
		// wrapper cons for catch; either way, when it is ours deliver cdr(payload).
		if (eqTags) {
			// A bare id means a cross-lambda return-from is passing through: not ours.
			int wrapSlot = ctx.allocTemp();
			emitConsField(ctx, payloadSlot, 0);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(wrapSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(wrapSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
			ctx.wasmCtrlDepth++;
			emitConsField(ctx, wrapSlot, 0);
		}
		else {
			emitConsField(ctx, payloadSlot, 0);
		}
		if (idSlot >= 0) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(idSlot);
		}
		else {
			WasmExprCompiler.compileExpr(idForm, ctx);
		}
		if (eqTags) {
			// A user tag is an ordinary value compared with eq, not a unique id.
			WasmEmitHelper.emitEqComparison(ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_EQ);
		}
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		emitConsField(ctx, payloadSlot, 1);
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // if
		if (eqTags) {
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END); // if (the payload is a catch wrapper)
		}
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
