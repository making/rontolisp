package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.ClosRegistry;

/**
 * Builds the {@code _aritySurplus} runtime helper behind the internal
 * {@code %arity-surplus-message} primitive -- the message of the {@code &optional}
 * surplus-argument check ({@code LambdaLists}):
 *
 * <pre>{@code _aritySurplus(int max, int required, Object rest) -> String}</pre>
 *
 * <p>
 * Answers the runtime (quote-framed) string
 * {@code "Function expects at most MAX argument(s), got M"}, M being {@code required}
 * plus the length of {@code rest}, assembled out of the constants
 * {@link ClosRegistry#aritySurplusMessage} composes, as {@code _arityMsg} is out of
 * {@link ClosRegistry#arityMessage}'s. One method every check shares, with plain
 * {@code StringBuilder} appends: the check spelled in Lisp rendered the count with
 * {@code prin1-to-string} over {@code (+ required (length rest))}, which dragged the
 * mutable-string wrap, the generic {@code length} and the code-point helpers into a
 * program that used none of them.
 */
final class JvmAritySurplusRuntimeBuilder {

	/**
	 * An arity-surplus runtime method body ready to be emitted into the generated class.
	 */
	record AritySurplusMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	static final String METHOD = "_aritySurplus";

	static final String DESC = "(IILjava/lang/Object;)Ljava/lang/String;";

	private JvmAritySurplusRuntimeBuilder() {
	}

	static AritySurplusMethod build(ConstantPool cp, ClassConstant objectArrayClass) {
		ClassConstant sb = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant appendStr = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant appendInt = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant toString = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		// Slots: 0 = max, 1 = the running count (starts at required), 2 = the rest
		// cursor, 3 = the builder.
		JvmAsm a = new JvmAsm();
		a.anew(sb);
		a.dup();
		a.ldcString(cp.addString("\"" + ClosRegistry.ARITY_MESSAGE_PREFIX + ClosRegistry.ARITY_AT_MOST));
		a.invokespecial(sbInit);
		a.astore(3);
		a.aload(3);
		a.iload(0);
		a.invokevirtual(appendInt);
		a.ldcString(cp.addString(ClosRegistry.ARITY_ARGUMENT));
		a.invokevirtual(appendStr);
		a.pop();
		int singular = a.label();
		a.iload(0);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPEQ, singular);
		a.aload(3);
		a.ldcString(cp.addString(ClosRegistry.ARITY_PLURAL));
		a.invokevirtual(appendStr);
		a.pop();
		a.bind(singular);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.aload(2);
		a.branch(Opcode.IFNULL, done);
		a.iinc(1, 1);
		a.aload(2);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(2);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.aload(3);
		a.ldcString(cp.addString(ClosRegistry.ARITY_MESSAGE_INFIX));
		a.invokevirtual(appendStr);
		a.iload(1);
		a.invokevirtual(appendInt);
		a.ldcString(cp.addString("\""));
		a.invokevirtual(appendStr);
		a.invokevirtual(toString);
		a.areturn();
		return new AritySurplusMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), 3, 4, a.finish());
	}

}
