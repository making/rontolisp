package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code eq} built-in function (general equality). Uses
 * {@code Object.equals()} for value comparison, which gives reference equality for
 * Object[] (cons cells) and value equality for Long, String, etc. Handles null (nil)
 * correctly.
 */
final class JvmEqGeneralCompiler {

	private JvmEqGeneralCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Evaluate both args
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int aSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(aSlot);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		int bSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(bSlot);
		// If a is null: return (b == null) ? t : nil
		ctx.emit(Opcode.ALOAD);
		ctx.emit(aSlot);
		int ifNonNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		// a is null
		ctx.emit(Opcode.ALOAD);
		ctx.emit(bSlot);
		int ifNonNull2Pos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		// both null -> t
		JvmEmitHelper.compileTrue(ctx);
		int gotoBothNullPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// a null, b not null -> nil
		JvmEmitHelper.patchBranch(ctx, ifNonNull2Pos, ctx.code.size());
		ctx.emit(Opcode.ACONST_NULL);
		int gotoANullPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// a is not null: a.equals(b) -> bool
		JvmEmitHelper.patchBranch(ctx, ifNonNullPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(aSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(bSlot);
		// _eqv is a.equals(b) plus element-wise comparison for ratios (array equals is
		// reference equality).
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.EQV).index());
		JvmEmitHelper.emitBoolFromInt(ctx);
		// end
		JvmEmitHelper.patchBranch(ctx, gotoBothNullPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoANullPos, ctx.code.size());
	}

}
