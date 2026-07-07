package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.Opcode;

/**
 * Emits the CL string-designator coercion shared by {@code string-upcase} /
 * {@code string-downcase} / {@code string-capitalize}. Compiles the (single) argument and
 * leaves a normalized, quoted runtime string ({@code "abc"}) on the operand stack: a real
 * string is used as-is, a symbol/keyword (a bare name, optionally with a leading keyword
 * colon) has the colon dropped and is wrapped in quotes so the case-folding callers can
 * transform the whole value uniformly.
 */
final class JvmStringDesignatorHelper {

	private JvmStringDesignatorHelper() {
	}

	static void emitCoerce(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		MethodrefConstant startsWith = JvmEmitHelper.stringMethod(ctx, "startsWith", "(Ljava/lang/String;)Z");
		MethodrefConstant substring = JvmEmitHelper.stringMethod(ctx, "substring", "(I)Ljava/lang/String;");
		MethodrefConstant concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		StringConstant quote = ctx.cp.addString("\"");
		StringConstant colon = ctx.cp.addString(":");

		// s = (String) arg
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		int sSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(sSlot);

		JvmAsm asm = new JvmAsm();
		int done = asm.label();
		int notColon = asm.label();
		// A leading quote marks a real string, which is already normalized.
		asm.aload(sSlot);
		asm.ldcString(quote);
		asm.invokevirtual(startsWith);
		asm.branch(Opcode.IFNE, done);
		// Symbol: drop a leading keyword colon.
		asm.aload(sSlot);
		asm.ldcString(colon);
		asm.invokevirtual(startsWith);
		asm.branch(Opcode.IFEQ, notColon);
		asm.aload(sSlot);
		asm.iconst(1);
		asm.invokevirtual(substring);
		asm.astore(sSlot);
		asm.bind(notColon);
		// s = "\"".concat(s).concat("\"")
		asm.ldcString(quote);
		asm.aload(sSlot);
		asm.invokevirtual(concat);
		asm.ldcString(quote);
		asm.invokevirtual(concat);
		asm.astore(sSlot);
		asm.bind(done);
		asm.aload(sSlot);
		ctx.code.addAll(asm.finish());
	}

}
