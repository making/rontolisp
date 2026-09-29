package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

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
		ctx.body.aload(aSlot);
		MethodCode.Label ifNonNullPos = ctx.body.newLabel();
		ctx.body.ifnonnull(ifNonNullPos);
		// a is null
		ctx.body.aload(bSlot);
		MethodCode.Label ifNonNull2Pos = ctx.body.newLabel();
		ctx.body.ifnonnull(ifNonNull2Pos);
		// both null -> t
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoBothNullPos = ctx.body.newLabel();
		ctx.body.goto_(gotoBothNullPos);
		// a null, b not null -> nil
		ctx.body.labelBinding(ifNonNull2Pos);
		ctx.body.aconst_null();
		MethodCode.Label gotoANullPos = ctx.body.newLabel();
		ctx.body.goto_(gotoANullPos);
		// a is not null: a.equals(b) -> bool
		ctx.body.labelBinding(ifNonNullPos);
		ctx.body.aload(aSlot).aload(bSlot);
		// _eqv is a.equals(b) plus element-wise comparison for ratios.
		ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.EQV).entry());
		JvmEmitHelper.emitBoolFromInt(ctx);
		// end
		ctx.body.labelBinding(gotoBothNullPos);
		ctx.body.labelBinding(gotoANullPos);
	}

}
