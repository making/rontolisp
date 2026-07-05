package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the internal {@code %arrayp} predicate used by the {@code vector}/
 * {@code array}/{@code sequence} type specifiers. Arrays and hash tables share the outer
 * representation (a {@code TYPE_CELL} box holding a {@code (header-car . data)} cons, see
 * {@link WasmArrayCompiler}), so testing the cell alone is not enough: an array's header
 * car is its {@code TYPE_HASH_BUCKETS} dims array, while a hash table's is an i31 entry
 * count -- the dims test is what tells them apart.
 */
final class WasmArraypCompiler {

	private WasmArraypCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int valueSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(valueSlot);
		int innerSlot = ctx.allocTemp();

		// value is a cell? (i31/string/cons/closure values fail here)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		// inner = cell.get(value, 0)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(innerSlot);
		// inner is a header cons?
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(innerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		// header car is a dims array (an array), not an i31 count (a hash table)?
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(innerSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
