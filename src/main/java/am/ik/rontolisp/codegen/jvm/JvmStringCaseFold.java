package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;

/**
 * The shared code-point walk behind {@code string-upcase} / {@code string-downcase} /
 * {@code string-capitalize}. CLHS defines all three as {@code char-upcase} /
 * {@code char-downcase} applied to each CHARACTER, so the fold is per code point and
 * preserves the character count -- deliberately NOT {@code String.toUpperCase}, whose
 * multi-character special casing (sharp s to {@code "SS"}) and context-sensitive Greek
 * final-sigma rule would change the length and diverge from the other backends.
 *
 * <p>
 * The argument arrives as a QUOTED runtime string ({@code "abc"}) -- the dispatcher runs
 * it through the shared {@code (string ...)} designator coercion first, so a symbol or a
 * character reaches the walk already spelled out. The framing quote bytes are neither
 * cased nor alphanumeric, so they pass through the walk untouched and the word boundaries
 * fall on the content.
 */
final class JvmStringCaseFold {

	/** Which per-code-point transform the walk applies. */
	enum Mode {

		UPCASE, DOWNCASE, CAPITALIZE

	}

	private JvmStringCaseFold() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, Mode mode) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className, mode));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className, Mode mode) {
		ClassEntry sbClass = ctx.cp.classEntry("java/lang/StringBuilder");
		MethodRefEntry sbInit = ctx.cp.methodRef(sbClass, "<init>", "()V");
		MethodRefEntry sbAppendCodePoint = ctx.cp.methodRef(sbClass, "appendCodePoint", "(I)Ljava/lang/StringBuilder;");
		MethodRefEntry sbToString = ctx.cp.methodRef(sbClass, "toString", "()Ljava/lang/String;");
		ClassEntry charClass = ctx.cp.classEntry("java/lang/Character");
		// Full-Unicode variants: (int)->(bool/int) accepts a code point, so a Latin-1
		// supplement letter or a supplementary alphabetic character is folded correctly.
		MethodRefEntry isLetterOrDigit = ctx.cp.methodRef(charClass, "isLetterOrDigit", "(I)Z");
		MethodRefEntry toUpper = ctx.cp.methodRef(charClass, "toUpperCase", "(I)I");
		MethodRefEntry toLower = ctx.cp.methodRef(charClass, "toLowerCase", "(I)I");
		MethodRefEntry charCharCount = ctx.cp.methodRef(charClass, "charCount", "(I)I");
		MethodRefEntry stringLength = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		MethodRefEntry stringCodePointAt = JvmEmitHelper.stringMethod(ctx, "codePointAt", "(I)I");

		// s = the argument, already normalized to a quoted runtime string by the shared
		// (string ...) designator coercion the dispatcher wraps it in. A mutable
		// character vector still normalizes here.
		JvmExprCompiler.compileExpr(cons.toList().get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass);
		int sSlot = ctx.allocTemp();
		ctx.body.astore(sSlot);

		boolean capitalize = mode == Mode.CAPITALIZE;
		int sbSlot = ctx.allocTemp();
		int iSlot = ctx.allocTemp();
		int chSlot = ctx.allocTemp();
		int wsSlot = capitalize ? ctx.allocTemp() : -1;

		MethodCode asm = ctx.body;
		MethodCode.Label loop = asm.newLabel();
		MethodCode.Label end = asm.newLabel();

		// sb = new StringBuilder()
		asm.new_(sbClass);
		asm.dup();
		asm.invokespecial(sbInit);
		asm.astore(sbSlot);
		if (capitalize) {
			// ws = 1 (the string starts at a word boundary)
			asm.loadConstant(1);
			asm.istore(wsSlot);
		}
		asm.loadConstant(0);
		asm.istore(iSlot);

		asm.labelBinding(loop);
		asm.iload(iSlot);
		asm.aload(sSlot);
		asm.invokevirtual(stringLength);
		asm.if_icmpge(end);
		// cp = s.codePointAt(i) -- walks by code point, so a supplementary code point
		// (surrogate pair) is one indexed step.
		asm.aload(sSlot);
		asm.iload(iSlot);
		asm.invokevirtual(stringCodePointAt);
		asm.istore(chSlot);
		if (capitalize) {
			MethodCode.Label notAlnum = asm.newLabel();
			MethodCode.Label down = asm.newLabel();
			MethodCode.Label ws0 = asm.newLabel();
			MethodCode.Label cont = asm.newLabel();
			// if (!isLetterOrDigit(cp)) goto notAlnum
			asm.iload(chSlot);
			asm.invokestatic(isLetterOrDigit);
			asm.ifeq(notAlnum);
			// alphanumeric: upcase at word start, else downcase.
			asm.iload(wsSlot);
			asm.ifeq(down);
			emitAppendFolded(asm, sbSlot, chSlot, toUpper, sbAppendCodePoint);
			asm.goto_(ws0);
			asm.labelBinding(down);
			emitAppendFolded(asm, sbSlot, chSlot, toLower, sbAppendCodePoint);
			asm.labelBinding(ws0);
			asm.loadConstant(0);
			asm.istore(wsSlot);
			asm.goto_(cont);
			// non-alphanumeric: append as-is, reset the word boundary
			asm.labelBinding(notAlnum);
			asm.aload(sbSlot);
			asm.iload(chSlot);
			asm.invokevirtual(sbAppendCodePoint);
			asm.pop();
			asm.loadConstant(1);
			asm.istore(wsSlot);
			asm.labelBinding(cont);
		}
		else {
			// Append the folded code point via StringBuilder.appendCodePoint(int) so a
			// supplementary result expands to its surrogate pair.
			emitAppendFolded(asm, sbSlot, chSlot, mode == Mode.UPCASE ? toUpper : toLower, sbAppendCodePoint);
		}
		// i += Character.charCount(cp) -- 1 for BMP, 2 for a surrogate pair.
		asm.iload(iSlot);
		asm.iload(chSlot);
		asm.invokestatic(charCharCount);
		asm.iadd();
		asm.istore(iSlot);
		asm.goto_(loop);

		asm.labelBinding(end);
		asm.aload(sbSlot);
		asm.invokevirtual(sbToString);

	}

	private static void emitAppendFolded(MethodCode asm, int sbSlot, int chSlot, MethodRefEntry fold,
			MethodRefEntry sbAppendCodePoint) {
		asm.aload(sbSlot);
		asm.iload(chSlot);
		asm.invokestatic(fold);
		asm.invokevirtual(sbAppendCodePoint);
		asm.pop();
	}

}
