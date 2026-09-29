package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;

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
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className, left, right));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className, boolean left,
			boolean right) {
		List<LispVal> args = cons.toList();
		ClassConstant strClass = ctx.stringClass;
		MethodrefConstant lengthRef = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		MethodrefConstant length = lengthRef;
		MethodrefConstant substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;");
		MethodrefConstant concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodrefConstant indexOf = JvmEmitHelper.stringMethod(ctx, "indexOf", "(I)I");

		int bagRawSlot = ctx.allocTemp();
		int bagSlot = ctx.allocTemp();
		int sSlot = ctx.allocTemp();
		int startSlot = ctx.allocTemp();
		int endSlot = ctx.allocTemp();

		// bagRaw = (String) char-bag
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(strClass.entry()).astore(bagRawSlot);
		// bag = bagRaw.substring(1, bagRaw.length() - 1)
		ctx.body.aload(bagRawSlot).iconst_1().aload(bagRawSlot).invokevirtual(length.methodRefEntry());
		ctx.body.iconst_1().isub().invokevirtual(substring.methodRefEntry()).astore(bagSlot);
		// s = (String) string
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(strClass.entry()).astore(sSlot);
		// start = 1
		ctx.body.iconst_1().istore(startSlot);
		// end = s.length() - 1
		ctx.body.aload(sSlot).invokevirtual(length.methodRefEntry()).iconst_1().isub().istore(endSlot);

		MethodCode asm = ctx.body;
		if (left) {
			MethodCode.Label loop = asm.newLabel();
			MethodCode.Label done = asm.newLabel();
			asm.labelBinding(loop);
			asm.iload(startSlot);
			asm.iload(endSlot);
			asm.if_icmpge(done);
			asm.aload(bagSlot);
			asm.aload(sSlot);
			asm.iload(startSlot);
			asm.invokevirtual(ctx.stringCharAt.methodRefEntry());
			asm.invokevirtual(indexOf.methodRefEntry());
			asm.iflt(done);
			asm.iinc(startSlot, 1);
			asm.goto_(loop);
			asm.labelBinding(done);
		}
		if (right) {
			MethodCode.Label loop = asm.newLabel();
			MethodCode.Label done = asm.newLabel();
			asm.labelBinding(loop);
			asm.iload(endSlot);
			asm.iload(startSlot);
			asm.if_icmple(done);
			asm.aload(bagSlot);
			asm.aload(sSlot);
			asm.iload(endSlot);
			asm.loadConstant(1);
			asm.isub();
			asm.invokevirtual(ctx.stringCharAt.methodRefEntry());
			asm.invokevirtual(indexOf.methodRefEntry());
			asm.iflt(done);
			asm.iinc(endSlot, -1);
			asm.goto_(loop);
			asm.labelBinding(done);
		}

		// "\"" + s.substring(start, end) + "\""
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.body.aload(sSlot).iload(startSlot).iload(endSlot).invokevirtual(substring.methodRefEntry());
		ctx.body.invokevirtual(concat.methodRefEntry());
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.body.invokevirtual(concat.methodRefEntry());
	}

}
