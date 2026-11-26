package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the internal {@code %string-concat} built-in function: both arguments are
 * strings by contract (literals, format pieces, rendered messages, subseq results),
 * byte-copied back to back into a new heap string by the {@code _string_concat} runtime
 * function.
 *
 * <p>
 * Where the level trades size for speed, a CHAIN of them -- an operand that is itself a
 * {@code %string-concat}, which is what {@code format nil} and a Starlark template lower
 * to -- is one call of {@code _str_cat} for every four pieces
 * ({@link WasmBulkRuntimeBuilder}): the pieces are evaluated in the order the chain
 * evaluates them and joined once, where each link made a byte array and a string of its
 * own. A piece that is {@code (%princ-piece x)} or {@code (%decimal-piece x)} with
 * nothing routed through a printer control or a {@code print-object} method hands a
 * fixnum over as it is, and the callee writes its digits where they go; any other value
 * is rendered where it is evaluated, as the chain rendered it. A piece is taken as it is
 * when it is evaluated -- a mutable character vector normalized there -- so what a later
 * piece's evaluation writes is not read, as it was not.
 */
final class WasmStringConcatCompiler {

	private WasmStringConcatCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (WasmIntFusionCompiler.speedTradesEnabled(ctx) && ctx.asyncResume == null) {
			List<LispVal> pieces = new ArrayList<>();
			collectPieces(cons, pieces);
			if (pieces.size() > 2 || pieces.stream().anyMatch(piece -> renderedOperand(piece, ctx) != null)) {
				compilePieces(pieces, ctx);
				return;
			}
		}
		// The callee normalizes a mutable character vector, on either side.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_STRING_CONCAT);
	}

	// The operands of a chain, left to right: the order their forms are evaluated in.
	private static void collectPieces(LispVal form, List<LispVal> pieces) {
		if (form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& LispNames.STRING_CONCAT.equals(head.name())) {
			List<LispVal> args = cons.toList();
			if (args.size() == 3) {
				collectPieces(args.get(1), pieces);
				collectPieces(args.get(2), pieces);
				return;
			}
		}
		pieces.add(form);
	}

	// The operand of a piece that is the plain rendering of a value -- (%princ-piece x),
	// or (%decimal-piece x) where it is that -- or null: _princ_to_str is then all the
	// piece compiles to, and a fixnum's text is its decimal digits.
	private static @Nullable LispVal renderedOperand(LispVal piece, WasmLispCompiler.Ctx ctx) {
		if (!(piece instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			return null;
		}
		LispCons princ = cons;
		if (LispNames.DECIMAL_PIECE_INTERNAL.equals(head.name())) {
			if (ctx.functions.containsKey(LispNames.DECIMAL_TEXT_INTERNAL)) {
				return null;
			}
			princ = new LispCons(new LispSymbol(LispNames.PRINC_PIECE_INTERNAL), cons.cdr());
		}
		else if (!LispNames.PRINC_PIECE_INTERNAL.equals(head.name())) {
			return null;
		}
		return LispMacroExpander.expandPrintObjectHook(princ, ctx.closRegistry, ctx.printControls) == null ? args.get(1)
				: null;
	}

	private static void compilePieces(List<LispVal> pieces, WasmLispCompiler.Ctx ctx) {
		int next = 0;
		while (next < pieces.size()) {
			// what is joined so far is the first piece of the next call
			int room = next == 0 ? 4 : 3;
			int taken = Math.min(room, pieces.size() - next);
			for (LispVal piece : pieces.subList(next, next + taken)) {
				compilePiece(piece, ctx);
			}
			for (int pad = taken; pad < room; pad++) {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_BULK_BASE + WasmBulkRuntimeBuilder.CAT);
			next += taken;
		}
	}

	// Leaves the piece as _str_cat takes it: an immutable string, or a fixnum.
	private static void compilePiece(LispVal piece, WasmLispCompiler.Ctx ctx) {
		LispVal rendered = renderedOperand(piece, ctx);
		if (rendered == null) {
			WasmExprCompiler.compileExpr(piece, ctx);
			// Only a literal is taken as it stands. StringValuedForms.certainlyString is
			// no licence here: a piece routed through a printer control answers what
			// string-downcase answers, a character vector, which _string_concat
			// normalized (the ci-spec corpus traps without this call).
			if (ctx.charvecPossible && !(piece instanceof LispString)) {
				int held = ctx.allocTemp();
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(held);
				WasmEmitHelper.emitCharvecToStrLocal(ctx.writer, held, true);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(held);
			}
			return;
		}
		WasmExprCompiler.compileExpr(rendered, ctx);
		int value = ctx.allocTemp();
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(value);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(value);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(value);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINC_TO_STR);
		ctx.writer.write(Instruction.END);
	}

}
