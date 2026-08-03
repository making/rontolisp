package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.Opcode;
import am.ik.jvm.OperandStack;

/**
 * Emits the CL string-designator coercion shared by {@code string-upcase} /
 * {@code string-downcase} / {@code string-capitalize}. Compiles the (single) argument and
 * leaves a normalized, quoted runtime string ({@code "abc"}) on the operand stack: a real
 * string is used as-is, and anything else goes through the SAME
 * {@code _lispToDisplayString} coercion {@code string} and {@code symbol-name} use, then
 * gets quoted so the case-folding callers can transform the whole value uniformly.
 *
 * <p>
 * Using that runtime coercion rather than a local "drop a leading keyword colon" rule is
 * load-bearing: a symbol's runtime value here is its RESOLVED spelling, so
 * {@code (string-downcase 'foo::test)} answered {@code "foo::test"} on the compiled
 * backends where {@code (string 'foo::test)} answered {@code "TEST"} -- the interpreter
 * and SBCL both give {@code "test"}. sxql renders a column name with exactly that call,
 * so mito's migration DDL came out as {@code CREATE TABLE t (mito.type::test ...)}.
 */
final class JvmStringDesignatorHelper {

	private JvmStringDesignatorHelper() {
	}

	static void emitCoerce(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		MethodrefConstant startsWith = JvmEmitHelper.stringMethod(ctx, "startsWith", "(Ljava/lang/String;)Z");
		MethodrefConstant concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		StringConstant quote = ctx.cp.addString("\"");

		// s = (String) arg (a mutable character vector normalizes to a string first)
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		int sSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(sSlot);

		JvmAsm asm = new JvmAsm();
		int done = asm.label();
		// A leading quote marks a real string, which is already normalized.
		asm.aload(sSlot);
		asm.ldcString(quote);
		asm.invokevirtual(startsWith);
		asm.branch(Opcode.IFNE, done);
		// Anything else: s = "\"".concat(_lispToDisplayString(s)).concat("\"") -- the
		// keyword colon AND the package qualifier come off there, once, for every caller.
		asm.ldcString(quote);
		asm.aload(sSlot);
		asm.invokestatic(ctx.lispToDisplayString);
		asm.invokevirtual(concat);
		asm.ldcString(quote);
		asm.invokevirtual(concat);
		asm.astore(sSlot);
		asm.bind(done);
		asm.aload(sSlot);
		ctx.emitBlock(asm.finish(), OperandStack.Slot.REF);
	}

}
