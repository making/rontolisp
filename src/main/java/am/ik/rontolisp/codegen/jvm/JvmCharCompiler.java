package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the character built-ins. Characters are represented at runtime as a boxed
 * {@code java.lang.Character}, distinguished from other values by {@code instanceof}.
 * Strings carry the surrounding double quotes, so a Lisp index {@code i} reads the Java
 * char at position {@code i + 1}.
 */
final class JvmCharCompiler {

	private JvmCharCompiler() {
	}

	/**
	 * {@code (char string index)} / {@code (schar string index)}: the character at index.
	 */
	static void compileChar(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int sSlot = ctx.allocTemp();
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(sSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(sSlot);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.IADD);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.stringCharAt.index());
		boxChar(ctx);
	}

	/** {@code (char-code ch)}: the code point as an integer. */
	static void compileCharCode(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		charValue(ctx);
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	/** {@code (code-char n)}: the character with the given code point. */
	static void compileCodeChar(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		boxChar(ctx);
	}

	/** {@code (char-upcase ch)}. */
	static void compileUpcase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseFold(cons, ctx, className, "toUpperCase");
	}

	/** {@code (char-downcase ch)}. */
	static void compileDowncase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseFold(cons, ctx, className, "toLowerCase");
	}

	private static void compileCaseFold(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String method) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		charValue(ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, method, "(C)C").index());
		boxChar(ctx);
	}

	/** {@code (characterp x)}. */
	static void compileCharacterp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.characterClass(ctx).index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/** {@code (alpha-char-p ch)}. */
	static void compileAlphaCharP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		charValue(ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "isLetter", "(C)Z").index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/** {@code (digit-char-p ch [radix])}: the digit weight, or nil. */
	static void compileDigitCharP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		charValue(ctx);
		if (args.size() > 2) {
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.L2I);
		}
		else {
			JvmEmitHelper.emitIntConst(ctx, 10);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "digit", "(CI)I").index());
		// weight on stack: if weight < 0 return nil, else Long.valueOf(weight)
		ctx.emit(Opcode.DUP);
		int ifNotDigit = ctx.code.size();
		ctx.emit(Opcode.IFLT);
		ctx.emitU2(0);
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotDigit, ctx.code.size());
		ctx.emit(Opcode.POP); // discard the -1
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

	/** {@code (char= ...)} variadic equality. */
	static void compileEq(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPNE);
	}

	/** {@code (char< ...)} variadic strictly-increasing comparison. */
	static void compileLt(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPGE);
	}

	/** {@code (char<= ...)} variadic non-decreasing comparison. */
	static void compileLe(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileChain(cons, ctx, className, Opcode.IF_ICMPGT);
	}

	// Emits a variadic character comparison: each adjacent pair is compared on its code
	// point and the chain is true only when every pair satisfies the relation. failOpcode
	// is the branch that jumps to the nil result when a pair fails the relation.
	private static void compileChain(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int failOpcode) {
		List<LispVal> args = cons.toList();
		int prev = ctx.allocTemp();
		int cur = ctx.allocTemp();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		charValue(ctx);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(prev);
		List<Integer> failBranches = new ArrayList<>();
		for (int i = 2; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			charValue(ctx);
			ctx.emit(Opcode.ISTORE);
			ctx.emit(cur);
			ctx.emit(Opcode.ILOAD);
			ctx.emit(prev);
			ctx.emit(Opcode.ILOAD);
			ctx.emit(cur);
			failBranches.add(ctx.code.size());
			ctx.emit(failOpcode);
			ctx.emitU2(0);
			ctx.emit(Opcode.ILOAD);
			ctx.emit(cur);
			ctx.emit(Opcode.ISTORE);
			ctx.emit(prev);
		}
		JvmEmitHelper.compileTrue(ctx);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		for (int pos : failBranches) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

	// Unboxes the Character on the stack to its primitive char (an int on the JVM stack).
	private static void charValue(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(JvmEmitHelper.characterClass(ctx).index());
		MethodrefConstant charValue = JvmEmitHelper.characterMethod(ctx, "charValue", "()C");
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(charValue.index());
	}

	// Boxes the primitive char (int) on the stack into a Character.
	private static void boxChar(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.I2C);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "valueOf", "(C)Ljava/lang/Character;").index());
	}

}
