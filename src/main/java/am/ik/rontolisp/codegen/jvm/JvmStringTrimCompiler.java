package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code string-trim} / {@code string-left-trim} / {@code string-right-trim}.
 * The character bag and the target string both carry surrounding quotes, so the bag
 * content is {@code bag.substring(1, bag.length() - 1)} and trimming walks the target's
 * content indices ({@code 1 .. length - 1}); the trimmed content is re-wrapped in quotes.
 */
final class JvmStringTrimCompiler {

	private JvmStringTrimCompiler() {
	}

	static void compileTrim(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, true, true);
	}

	static void compileLeft(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, true, false);
	}

	static void compileRight(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, false, true);
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, boolean left, boolean right) {
		List<LispVal> args = cons.toList();
		int strClass = ctx.stringClass.index();
		MethodrefConstant lengthRef = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		int length = lengthRef.index();
		int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		MethodrefConstant indexOf = JvmEmitHelper.stringMethod(ctx, "indexOf", "(I)I");

		int bagRawSlot = ctx.allocTemp();
		int bagSlot = ctx.allocTemp();
		int sSlot = ctx.allocTemp();
		int startSlot = ctx.allocTemp();
		int endSlot = ctx.allocTemp();

		// bagRaw = (String) char-bag
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(strClass);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(bagRawSlot);
		// bag = bagRaw.substring(1, bagRaw.length() - 1)
		ctx.emit(Opcode.ALOAD);
		ctx.emit(bagRawSlot);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(bagRawSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(length);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(substring);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(bagSlot);
		// s = (String) string
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(strClass);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(sSlot);
		// start = 1
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(startSlot);
		// end = s.length() - 1
		ctx.emit(Opcode.ALOAD);
		ctx.emit(sSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(length);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISUB);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(endSlot);

		JvmAsm asm = new JvmAsm();
		if (left) {
			int loop = asm.label();
			int done = asm.label();
			asm.bind(loop);
			asm.iload(startSlot);
			asm.iload(endSlot);
			asm.branch(Opcode.IF_ICMPGE, done);
			asm.aload(bagSlot);
			asm.aload(sSlot);
			asm.iload(startSlot);
			asm.invokevirtual(ctx.stringCharAt);
			asm.invokevirtual(indexOf);
			asm.branch(Opcode.IFLT, done);
			asm.iinc(startSlot, 1);
			asm.branch(Opcode.GOTO, loop);
			asm.bind(done);
		}
		if (right) {
			int loop = asm.label();
			int done = asm.label();
			asm.bind(loop);
			asm.iload(endSlot);
			asm.iload(startSlot);
			asm.branch(Opcode.IF_ICMPLE, done);
			asm.aload(bagSlot);
			asm.aload(sSlot);
			asm.iload(endSlot);
			asm.iconst(1);
			asm.op(Opcode.ISUB);
			asm.invokevirtual(ctx.stringCharAt);
			asm.invokevirtual(indexOf);
			asm.branch(Opcode.IFLT, done);
			asm.iinc(endSlot, -1);
			asm.branch(Opcode.GOTO, loop);
			asm.bind(done);
		}
		ctx.emitBlock(asm.finish());

		// "\"" + s.substring(start, end) + "\""
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(sSlot);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(startSlot);
		ctx.emit(Opcode.ILOAD);
		ctx.emit(endSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(substring);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
	}

}
