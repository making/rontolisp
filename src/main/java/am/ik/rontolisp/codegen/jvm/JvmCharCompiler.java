package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the character built-ins. A CHARACTER on the JVM compile path is a length-1
 * {@code int[]} whose sole element is the Unicode code point -- a wider representation
 * than the old {@code java.lang.Character} 16-bit box, so a supplementary code point like
 * {@code #\U+1F600} carries its full 21-bit value through {@code code-char},
 * {@code char-code}, {@code char-upcase}/{@code char-downcase}, {@code (string ch)} and
 * every printer. The type discriminator is {@code instanceof int[]}: functions
 * ({@code Object[]}), ratios ({@code BigInteger[]}), packed float arrays
 * ({@code double[]} / {@code float[]}) all pick different array classes.
 *
 * <p>
 * Strings are UTF-16 buffers with surrounding double quotes; a Lisp CHARACTER index is a
 * CODE POINT index, translated by {@link JvmStringIndexRuntimeBuilder}'s {@code _cpoff},
 * so the same astral glyph reads back as one indexed character on {@code (char s i)} /
 * {@code (aref s i)} / {@code (subseq s a b)}.
 */
final class JvmCharCompiler {

	private JvmCharCompiler() {
	}

	/**
	 * {@code (char string index)} / {@code (schar string index)}: the code point at
	 * index, via ONE {@code _charRef} call ({@link JvmStringIndexRuntimeBuilder}). A
	 * mutable character vector reads its element there (an O(1) {@code _rmGet}, never a
	 * rendered string); an immutable string translates the CHARACTER position to a UTF-16
	 * code-unit offset via {@code _cpoff} so a supplementary code point counts as one
	 * indexed character.
	 */
	static void compileChar(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper
			.selfMethod(ctx, className, JvmStringIndexRuntimeBuilder.CHARREF_METHOD,
					JvmStringIndexRuntimeBuilder.CHARREF_DESC)
			.index());
		JvmEmitHelper.boxCodePoint(ctx);
	}

	/** {@code (char-code ch)}: the code point as an integer. */
	static void compileCharCode(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxCodePoint(ctx);
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	/** {@code (code-char n)}: the character with the given code point. */
	static void compileCodeChar(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		JvmEmitHelper.boxCodePoint(ctx);
	}

	/**
	 * {@code (char-upcase ch)}. Full-Unicode fold via {@code Character.toUpperCase(int)}.
	 */
	static void compileUpcase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseFold(cons, ctx, className, "toUpperCase");
	}

	/**
	 * {@code (char-downcase ch)}. Full-Unicode fold via
	 * {@code Character.toLowerCase(int)}.
	 */
	static void compileDowncase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileCaseFold(cons, ctx, className, "toLowerCase");
	}

	private static void compileCaseFold(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String method) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxCodePoint(ctx);
		// Character.toUpperCase(int)/toLowerCase(int) take a code point and return a code
		// point; a mapping that would expand to multiple code units lives on the String
		// overload, so this is the right level for a single-character fold.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, method, "(I)I").index());
		JvmEmitHelper.boxCodePoint(ctx);
	}

	/** {@code (characterp x)}: {@code instanceof int[]}. */
	static void compileCharacterp(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.charArrayClass(ctx).index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/** {@code (alpha-char-p ch)}: {@code Character.isLetter(int)} on the code point. */
	static void compileAlphaCharP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxCodePoint(ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "isLetter", "(I)Z").index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	/** {@code (digit-char-p ch [radix])}: the digit weight, or nil. */
	static void compileDigitCharP(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxCodePoint(ctx);
		if (args.size() > 2) {
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.L2I);
		}
		else {
			JvmEmitHelper.emitIntConst(ctx, 10);
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmEmitHelper.characterMethod(ctx, "digit", "(II)I").index());
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
		JvmEmitHelper.unboxCodePoint(ctx);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(prev);
		List<Integer> failBranches = new ArrayList<>();
		for (int i = 2; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			JvmEmitHelper.unboxCodePoint(ctx);
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

}
