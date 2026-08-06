package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.jvm.OperandStack;

/**
 * The shared code-point walk behind {@code string-upcase} / {@code string-downcase} /
 * {@code string-capitalize}. CLHS defines all three as {@code char-upcase} /
 * {@code char-downcase} applied to each CHARACTER, so the fold is per code point and
 * preserves the character count -- deliberately NOT {@code String.toUpperCase}, whose
 * multi-character special casing (sharp s to {@code "SS"}) and context-sensitive Greek
 * final-sigma rule would change the length and diverge from the other backends.
 *
 * <p>
 * The argument is coerced to a QUOTED string designator ({@code "abc"}); the framing
 * quote bytes are neither cased nor alphanumeric, so they pass through the walk untouched
 * and the word boundaries fall on the content.
 */
final class JvmStringCaseFold {

	/** Which per-code-point transform the walk applies. */
	enum Mode {

		UPCASE, DOWNCASE, CAPITALIZE

	}

	private JvmStringCaseFold() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, Mode mode) {
		ClassConstant sbClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = ctx.cp.addMethodref(sbClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("()V")));
		MethodrefConstant sbAppendCodePoint = ctx.cp.addMethodref(sbClass, ctx.cp
			.addNameAndType(ctx.cp.addUtf8("appendCodePoint"), ctx.cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = ctx.cp.addMethodref(sbClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("toString"), ctx.cp.addUtf8("()Ljava/lang/String;")));
		ClassConstant charClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Character"));
		// Full-Unicode variants: (int)->(bool/int) accepts a code point, so a Latin-1
		// supplement letter or a supplementary alphabetic character is folded correctly.
		MethodrefConstant isLetterOrDigit = ctx.cp.addMethodref(charClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("isLetterOrDigit"), ctx.cp.addUtf8("(I)Z")));
		MethodrefConstant toUpper = ctx.cp.addMethodref(charClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("toUpperCase"), ctx.cp.addUtf8("(I)I")));
		MethodrefConstant toLower = ctx.cp.addMethodref(charClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("toLowerCase"), ctx.cp.addUtf8("(I)I")));
		MethodrefConstant charCharCount = ctx.cp.addMethodref(charClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("charCount"), ctx.cp.addUtf8("(I)I")));
		MethodrefConstant stringLength = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		MethodrefConstant stringCodePointAt = JvmEmitHelper.stringMethod(ctx, "codePointAt", "(I)I");

		// s = the argument coerced to a quoted string designator (string / symbol /
		// keyword)
		JvmStringDesignatorHelper.emitCoerce(cons, ctx, className);
		int sSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(sSlot);

		boolean capitalize = mode == Mode.CAPITALIZE;
		int sbSlot = ctx.allocTemp();
		int iSlot = ctx.allocTemp();
		int chSlot = ctx.allocTemp();
		int wsSlot = capitalize ? ctx.allocTemp() : -1;

		JvmAsm asm = new JvmAsm();
		int loop = asm.label();
		int end = asm.label();

		// sb = new StringBuilder()
		asm.anew(sbClass);
		asm.dup();
		asm.invokespecial(sbInit);
		asm.astore(sbSlot);
		if (capitalize) {
			// ws = 1 (the string starts at a word boundary)
			asm.iconst(1);
			asm.istore(wsSlot);
		}
		asm.iconst(0);
		asm.istore(iSlot);

		asm.bind(loop);
		asm.iload(iSlot);
		asm.aload(sSlot);
		asm.invokevirtual(stringLength);
		asm.branch(Opcode.IF_ICMPGE, end);
		// cp = s.codePointAt(i) -- walks by code point, so a supplementary code point
		// (surrogate pair) is one indexed step.
		asm.aload(sSlot);
		asm.iload(iSlot);
		asm.invokevirtual(stringCodePointAt);
		asm.istore(chSlot);
		if (capitalize) {
			int notAlnum = asm.label();
			int down = asm.label();
			int ws0 = asm.label();
			int cont = asm.label();
			// if (!isLetterOrDigit(cp)) goto notAlnum
			asm.iload(chSlot);
			asm.invokestatic(isLetterOrDigit);
			asm.branch(Opcode.IFEQ, notAlnum);
			// alphanumeric: upcase at word start, else downcase.
			asm.iload(wsSlot);
			asm.branch(Opcode.IFEQ, down);
			emitAppendFolded(asm, sbSlot, chSlot, toUpper, sbAppendCodePoint);
			asm.branch(Opcode.GOTO, ws0);
			asm.bind(down);
			emitAppendFolded(asm, sbSlot, chSlot, toLower, sbAppendCodePoint);
			asm.bind(ws0);
			asm.iconst(0);
			asm.istore(wsSlot);
			asm.branch(Opcode.GOTO, cont);
			// non-alphanumeric: append as-is, reset the word boundary
			asm.bind(notAlnum);
			asm.aload(sbSlot);
			asm.iload(chSlot);
			asm.invokevirtual(sbAppendCodePoint);
			asm.pop();
			asm.iconst(1);
			asm.istore(wsSlot);
			asm.bind(cont);
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
		asm.op(Opcode.IADD);
		asm.istore(iSlot);
		asm.branch(Opcode.GOTO, loop);

		asm.bind(end);
		asm.aload(sbSlot);
		asm.invokevirtual(sbToString);

		ctx.emitBlock(asm.finish(), OperandStack.Slot.REF);
	}

	private static void emitAppendFolded(JvmAsm asm, int sbSlot, int chSlot, MethodrefConstant fold,
			MethodrefConstant sbAppendCodePoint) {
		asm.aload(sbSlot);
		asm.iload(chSlot);
		asm.invokestatic(fold);
		asm.invokevirtual(sbAppendCodePoint);
		asm.pop();
	}

}
