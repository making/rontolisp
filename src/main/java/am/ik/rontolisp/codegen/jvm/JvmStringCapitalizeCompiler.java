package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.jvm.OperandStack;

/**
 * Compiles {@code string-capitalize}: capitalizes the first letter of each alphanumeric
 * word and lowercases the rest. The surrounding quote bytes are not alphanumeric, so they
 * pass through unchanged and the word boundaries fall on the content.
 */
final class JvmStringCapitalizeCompiler {

	private JvmStringCapitalizeCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		ClassConstant sbClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = ctx.cp.addMethodref(sbClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("()V")));
		MethodrefConstant sbAppendChar = ctx.cp.addMethodref(sbClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("append"), ctx.cp.addUtf8("(C)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = ctx.cp.addMethodref(sbClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("toString"), ctx.cp.addUtf8("()Ljava/lang/String;")));
		ClassConstant charClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Character"));
		MethodrefConstant isLetterOrDigit = ctx.cp.addMethodref(charClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("isLetterOrDigit"), ctx.cp.addUtf8("(C)Z")));
		MethodrefConstant toUpper = ctx.cp.addMethodref(charClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("toUpperCase"), ctx.cp.addUtf8("(C)C")));
		MethodrefConstant toLower = ctx.cp.addMethodref(charClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("toLowerCase"), ctx.cp.addUtf8("(C)C")));
		MethodrefConstant stringLength = JvmEmitHelper.stringMethod(ctx, "length", "()I");

		// s = the argument coerced to a quoted string designator (string / symbol /
		// keyword)
		JvmStringDesignatorHelper.emitCoerce(cons, ctx, className);
		int sSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(sSlot);

		int sbSlot = ctx.allocTemp();
		int iSlot = ctx.allocTemp();
		int chSlot = ctx.allocTemp();
		int wsSlot = ctx.allocTemp();

		JvmAsm asm = new JvmAsm();
		int loop = asm.label();
		int end = asm.label();
		int notAlnum = asm.label();
		int down = asm.label();
		int ws0 = asm.label();
		int cont = asm.label();

		// sb = new StringBuilder()
		asm.anew(sbClass);
		asm.dup();
		asm.invokespecial(sbInit);
		asm.astore(sbSlot);
		// ws = 1; i = 0
		asm.iconst(1);
		asm.istore(wsSlot);
		asm.iconst(0);
		asm.istore(iSlot);

		asm.bind(loop);
		asm.iload(iSlot);
		asm.aload(sSlot);
		asm.invokevirtual(stringLength);
		asm.branch(Opcode.IF_ICMPGE, end);
		// ch = s.charAt(i)
		asm.aload(sSlot);
		asm.iload(iSlot);
		asm.invokevirtual(ctx.stringCharAt);
		asm.istore(chSlot);
		// if (!isLetterOrDigit(ch)) goto notAlnum
		asm.iload(chSlot);
		asm.invokestatic(isLetterOrDigit);
		asm.branch(Opcode.IFEQ, notAlnum);
		// alphanumeric: upcase at word start, else downcase
		asm.iload(wsSlot);
		asm.branch(Opcode.IFEQ, down);
		asm.aload(sbSlot);
		asm.iload(chSlot);
		asm.invokestatic(toUpper);
		asm.invokevirtual(sbAppendChar);
		asm.pop();
		asm.branch(Opcode.GOTO, ws0);
		asm.bind(down);
		asm.aload(sbSlot);
		asm.iload(chSlot);
		asm.invokestatic(toLower);
		asm.invokevirtual(sbAppendChar);
		asm.pop();
		asm.bind(ws0);
		asm.iconst(0);
		asm.istore(wsSlot);
		asm.branch(Opcode.GOTO, cont);
		// non-alphanumeric: append as-is, reset word boundary
		asm.bind(notAlnum);
		asm.aload(sbSlot);
		asm.iload(chSlot);
		asm.invokevirtual(sbAppendChar);
		asm.pop();
		asm.iconst(1);
		asm.istore(wsSlot);
		asm.bind(cont);
		asm.iinc(iSlot, 1);
		asm.branch(Opcode.GOTO, loop);

		asm.bind(end);
		asm.aload(sbSlot);
		asm.invokevirtual(sbToString);

		ctx.emitBlock(asm.finish(), OperandStack.Slot.REF);
	}

}
