package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code eq} and {@code eql} built-in functions, which are one predicate
 * ({@code .kb/eq-numbers.md}): reference equality for Object[] (cons cells) and value
 * equality for Long, Double, ratios, String, etc. Both share the per-class {@code _pEql}
 * helper. Handles null (nil) correctly.
 */
final class JvmEqGeneralCompiler {

	private JvmEqGeneralCompiler() {
	}

	/**
	 * Compiles {@code eql} and {@code eq} (numbers compared by type and value).
	 * @param cons the call form
	 * @param ctx the compilation context
	 * @param className the class being compiled
	 */
	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Evaluate both args
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		// The nil handling around the numeric helper is the same wherever it is
		// written, so it lives in one per-class method (JvmEmitHelper.emitSharedCall)
		// instead of ~45 bytecodes per site.
		JvmEmitHelper.emitSharedCall(ctx, className, "_pEql", 2, JvmEqGeneralCompiler::emitCompare);
	}

	/** Emits the comparison over the two values in local slots 0 and 1. */
	private static void emitCompare(JvmLispCompiler.Ctx ctx) {
		int aSlot = 0;
		int bSlot = 1;
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
		// _eqv is a.equals(b) plus element-wise comparison for ratios.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.EQV).index());
		JvmEmitHelper.emitBoolFromInt(ctx);
		// end
		JvmEmitHelper.patchBranch(ctx, gotoBothNullPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoANullPos, ctx.code.size());
	}

}
