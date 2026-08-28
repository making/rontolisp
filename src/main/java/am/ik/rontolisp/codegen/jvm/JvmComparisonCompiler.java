package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles comparison operations ({@code =}, {@code <}, {@code >}, {@code <=},
 * {@code >=}).
 */
final class JvmComparisonCompiler {

	private JvmComparisonCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, int branchOpcode, String className) {
		List<LispVal> args = cons.toList();
		int branch;
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
			JvmArithCompiler.compileUnboxedOperand(args.get(2), ctx, className);
			// IEEE: a comparison against NaN is false. javac's rule: DCMPG for < and
			// <= (NaN falls out as +1, failing IFLT/IFLE), DCMPL for the others (NaN
			// falls out as -1, failing IFGT/IFGE/IFEQ).
			ctx.emit(branchOpcode == Opcode.IFLT || branchOpcode == Opcode.IFLE ? Opcode.DCMPG : Opcode.DCMPL);
			branch = branchOpcode;
		}
		else {
			// _cmpb returns the comparison as a bitmask (1 = lt, 2 = eq, 4 = gt,
			// 0 = unordered), so a NaN operand fails every operator -- a -1/0/1
			// signum compared against zero cannot express "unordered".
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.CMPB).index());
			JvmEmitHelper.emitIntConst(ctx, maskFor(branchOpcode));
			ctx.emit(Opcode.IAND);
			branch = Opcode.IFNE;
		}
		int ifPos = ctx.code.size();
		ctx.emit(branch);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int trueLabel = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, ifPos, trueLabel);
		JvmEmitHelper.compileTrue(ctx);
		int endLabel = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, endLabel);
	}

	private static int maskFor(int branchOpcode) {
		return switch (branchOpcode) {
			case Opcode.IFEQ -> 0b010;
			case Opcode.IFLT -> 0b001;
			case Opcode.IFGT -> 0b100;
			case Opcode.IFLE -> 0b011;
			case Opcode.IFGE -> 0b110;
			default -> throw new IllegalArgumentException("unexpected comparison branch: " + branchOpcode);
		};
	}

}
