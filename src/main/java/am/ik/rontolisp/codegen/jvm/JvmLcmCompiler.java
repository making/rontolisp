package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code lcm} built-in: the least common multiple of two integers, computed
 * as {@code abs((a / gcd(a, b)) * b)}. Returns 0 when either argument is 0.
 */
final class JvmLcmCompiler {

	private static final String BIG = "Ljava/math/BigInteger;";

	private JvmLcmCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int savedNextLocal = ctx.nextLocal;
		int slotA = ctx.allocLocal("%lcm$a" + savedNextLocal);
		int slotB = ctx.allocLocal("%lcm$b" + savedNextLocal);
		int slotG = ctx.allocLocal("%lcm$g" + savedNextLocal);

		// A = _big(a); B = _big(b); G = A.gcd(B)
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slotA);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slotB);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slotA);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slotB);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "gcd", "(" + BIG + ")" + BIG).index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slotG);

		// if (G.signum() != 0) goto notZero
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slotG);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "signum", "()I").index());
		int ifnePos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);

		// zero case: result is 0
		ctx.emit(Opcode.LCONST_0);
		JvmEmitHelper.boxLong(ctx);
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);

		// notZero: abs((A / G) * B)
		int notZero = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, ifnePos, notZero);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slotA);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slotG);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "divide", "(" + BIG + ")" + BIG).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slotB);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "multiply", "(" + BIG + ")" + BIG).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "abs", "()" + BIG).index());
		JvmEmitHelper.normalizeBigInteger(ctx);

		int end = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoPos, end);
		ctx.nextLocal = savedNextLocal;
	}

}
